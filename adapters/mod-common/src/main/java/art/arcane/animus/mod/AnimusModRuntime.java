package art.arcane.animus.mod;

import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.AnimusSplash;
import art.arcane.animus.core.BuildIdentity;
import art.arcane.animus.core.Platform;
import art.arcane.animus.core.RuntimeEnvironment;
import art.arcane.animus.core.behavior.builtin.BuiltinCapabilities;
import art.arcane.animus.core.behavior.builtin.DialogueTriggers;
import art.arcane.animus.core.behavior.trigger.TriggerDispatcher;
import art.arcane.animus.core.command.AnimusCommands;
import art.arcane.animus.core.command.BehaviorCommands;
import art.arcane.animus.core.command.CommandActor;
import art.arcane.animus.core.config.NpcRuntimeSettings;
import art.arcane.animus.core.extension.ExtensionRuntime;
import art.arcane.animus.core.extension.MutationLane;
import art.arcane.animus.core.runtime.PersistedNpcService;
import art.arcane.animus.core.runtime.RuntimeHost;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.HitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Animus on a mod server: the same runtime, commands, behaviors, modules and combat as on Bukkit, owned by the server
 * thread. A loader adapter calls {@link #start}, {@link #tick} after every server tick, {@link #stop} and
 * {@link #registerCommands}; the shared mixins report everything else through {@link ModHooks}.
 */
public final class AnimusModRuntime {
    private static final Logger LOGGER = LoggerFactory.getLogger(BuildIdentity.ID);
    /** Packet previews read Bukkit's server internals and are not part of the mod runtime. */
    private static final AnimusCommands.Extra BUKKIT_ONLY = new AnimusCommands.Extra() {
        @Override public void execute(CommandActor actor, String[] arguments) {
            actor.tell("Packet proof previews are available only on Bukkit and Paper servers.");
        }
        @Override public List<String> complete(CommandActor actor, String[] arguments) { return List.of(); }
    };
    /** A server correction moves a player less than this far; a real teleport moves it farther. */
    private static final double CORRECTION_SQUARED = 1.0;
    private final Platform platform;
    private final Path dataDirectory;
    private volatile Running running;

    /** Everything one server start owns; a new start builds a new one. */
    private static final class Running {
        AnimusRuntime runtime;
        ModTicks ticks;
        VanillaRuntimeHost host;
        PersistedNpcService<ServerPlayer, ItemStack> living;
        ModCombat combat;
        BuiltinCapabilities builtins;
        ExtensionRuntime extensions;
        volatile BehaviorCommands behaviors;
        AnimusCommands commands;
        volatile boolean stopping;
    }

    public AnimusModRuntime(Platform platform, Path configDirectory) {
        this.platform = Objects.requireNonNull(platform, "platform");
        this.dataDirectory = Objects.requireNonNull(configDirectory, "configDirectory").resolve(BuildIdentity.ID);
    }

    public void start(MinecraftServer server) {
        if (running != null) throw new IllegalStateException(BuildIdentity.NAME + " is already running");
        RuntimeEnvironment environment = new RuntimeEnvironment(platform, MinecraftVersion.current(), dataDirectory);
        Running next = new Running();
        try {
            next.runtime = new AnimusRuntime(environment, (Throwable failure) -> LOGGER.error("Animus file cleanup failed", failure));
            next.runtime.start();
            next.runtime.npcs().diagnostics().stream().limit(20).forEach((String problem) -> LOGGER.warn("NPC definition recovery: {}", problem));
            next.ticks = new ModTicks(LOGGER::warn);
            Executor files = next.runtime.fileExecutor((Runnable task, MutationLane.EngineTaskEvent event, Throwable failure) -> {
                if (event == MutationLane.EngineTaskEvent.FAILED) LOGGER.warn("Animus file task failed", failure);
            });
            NpcRuntimeSettings npcSettings = next.runtime.npcSettings();
            PersistedNpcService.Settings settings = new PersistedNpcService.Settings(npcSettings.enabled(), npcSettings.viewDistance(), npcSettings.maximumVisible(),
                    npcSettings.maximumViewers(), npcSettings.updatesPerTick());
            next.host = new VanillaRuntimeHost(server, next.ticks, LOGGER::warn, this::interactionAllowed);
            next.living = new PersistedNpcService<>(next.host, next.runtime, files, settings);
            LOGGER.info(next.living.status());
            next.combat = new ModCombat(server, next.runtime, next.living, next.host.terrain(), LOGGER::warn);
            LOGGER.info(next.combat.status());
            next.ticks.every(next.combat::tick);
            Running owner = next;
            DialogueTriggers dialogue = new DialogueTriggers(() -> { BehaviorCommands current = owner.behaviors; return current == null ? null : current.triggers(); });
            next.builtins = new BuiltinCapabilities(new VanillaBehaviorPlatform(server, next.living, next.combat, next.ticks),
                    next.runtime.behaviorState(), dialogue::publish, next.runtime.behaviorSettings().actions(), LOGGER::info);
            next.extensions = new ExtensionRuntime(next.runtime.documents().resolve("modules"), modulePlatform(), next.runtime.definitionGateway(),
                    next.runtime.moduleSettings(), LOGGER::info, (ExtensionRuntime.State initialized) -> {
                        BehaviorCommands current = owner.behaviors;
                        if (current != null) current.modulesInitialized(initialized);
                    }, next.builtins);
            next.behaviors = new BehaviorCommands(dataDirectory, next.runtime, next.extensions, LOGGER::info);
            TriggerDispatcher triggers = next.behaviors.triggers();
            if (triggers != null) {
                if (next.living.available()) { triggers.connect(); next.living.bindTriggers(triggers); }
                else {
                    triggers.unavailable(next.living.status());
                    LOGGER.info("Behavior triggers unavailable: {}", next.living.status());
                }
            }
            next.commands = new AnimusCommands(new AnimusCommands.Services(next.runtime, files, next.living, () -> owner.behaviors, next.extensions,
                    next.builtins, next.combat::execute, Map.of("proof", BUKKIT_ONLY, "foliaproof", BUKKIT_ONLY), () -> owner.stopping));
            next.extensions.start();
            running = next;
            ModHooks.attach(this);
            LOGGER.info(AnimusSplash.render(environment, platform + " / Minecraft " + environment.minecraftVersion(), true));
        } catch (IOException | RuntimeException | LinkageError failure) {
            LOGGER.error("Could not start " + BuildIdentity.NAME, failure);
            close(next);
        }
    }

    public void stop() {
        Running active = running;
        running = null;
        ModHooks.detach(this);
        if (active != null) close(active);
    }

    private static void close(Running active) {
        active.stopping = true;
        if (active.behaviors != null) active.behaviors.close();
        if (active.builtins != null) active.builtins.close();
        if (active.extensions != null) active.extensions.close();
        if (active.combat != null) active.combat.close();
        if (active.living != null) active.living.close();
        if (active.ticks != null) active.ticks.close();
        if (active.runtime != null) active.runtime.closeAsync();
    }

    /** Call once after every server tick, on the server thread. */
    public void tick() {
        Running active = running;
        if (active != null) active.ticks.tick();
    }

    public void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal(BuildIdentity.ID)
                .executes((CommandContext<CommandSourceStack> context) -> run(context.getSource(), ""))
                .then(Commands.argument("arguments", StringArgumentType.greedyString())
                        .suggests(this::suggest)
                        .executes((CommandContext<CommandSourceStack> context) -> run(context.getSource(), StringArgumentType.getString(context, "arguments")))));
    }

    private int run(CommandSourceStack source, String text) {
        Running active = running;
        if (active == null || active.stopping) { source.sendFailure(Component.literal("Animus is not running.")); return 0; }
        active.commands.execute(new ModActor(source, active.ticks), ModCommandLine.words(text));
        return 1;
    }
    private CompletableFuture<Suggestions> suggest(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        Running active = running;
        if (active == null) return builder.buildFuture();
        String remaining = builder.getRemaining();
        SuggestionsBuilder last = builder.createOffset(builder.getStart() + ModCommandLine.lastWordStart(remaining));
        for (String option : active.commands.complete(new ModActor(context.getSource(), active.ticks), ModCommandLine.completionWords(remaining))) last.suggest(option);
        return last.buildFuture();
    }
    private art.arcane.animus.api.Platform modulePlatform() {
        return switch (platform) {
            case FABRIC -> art.arcane.animus.api.Platform.FABRIC;
            case FORGE -> art.arcane.animus.api.Platform.FORGE;
            case NEOFORGE -> art.arcane.animus.api.Platform.NEOFORGE;
            case BUKKIT -> throw new IllegalStateException("Bukkit does not run the mod runtime");
        };
    }

    private boolean interactionAllowed(ServerPlayer player, UUID npc, RuntimeHost.Interaction action) {
        Running active = running;
        if (active != null && action == RuntimeHost.Interaction.ATTACK) active.combat.playerHit(player, npc);
        return true;
    }

    boolean interact(ServerPlayer player, int entityId, boolean attack, String hand, boolean secondary) {
        Running active = running;
        VanillaBodyTransport transport = active == null ? null : active.host.transport();
        return transport != null && transport.interact(player, entityId, attack, hand, secondary);
    }
    void input(ServerPlayer player, Input input) {
        Running active = running;
        VanillaBodyTransport transport = active == null ? null : active.host.transport();
        if (transport != null) transport.input(player, input);
    }
    void tickEnd(ServerPlayer player) {
        Running active = running;
        VanillaBodyTransport transport = active == null ? null : active.host.transport();
        if (transport != null) transport.tickEnd(player);
    }
    /** A server correction back to where the player already is keeps its presentation; any real teleport resets it. */
    void teleported(ServerPlayer player, double x, double y, double z) {
        Running active = running;
        if (active != null && player.position().distanceToSqr(x, y, z) >= CORRECTION_SQUARED) active.living.teleported(player);
    }
    void joined(ServerPlayer player) { Running active = running; if (active != null) active.living.joined(player); }
    void quit(ServerPlayer player) { Running active = running; if (active != null) active.living.quit(player); }
    void respawned(ServerPlayer player) { Running active = running; if (active != null) active.living.respawned(player); }
    void died(ServerPlayer player) { Running active = running; if (active != null) active.living.died(player); }
    void realDamaged(LivingEntity victim, DamageSource source) { Running active = running; if (active != null) active.combat.realDamaged(victim, source); }
    boolean helperHit(Projectile projectile, HitResult hit) { Running active = running; return active != null && active.combat.helperHit(projectile, hit); }
    boolean suppressesDrops(LivingEntity entity) { Running active = running; return active != null && active.combat.suppressesDrops(entity); }
}
