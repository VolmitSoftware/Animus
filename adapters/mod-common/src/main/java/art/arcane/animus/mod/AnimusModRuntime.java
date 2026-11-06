package art.arcane.animus.mod;

import art.arcane.animus.core.command.DebugCommands;
import art.arcane.animus.core.metrics.AnimusMetrics;
import art.arcane.animus.core.metrics.DebugWatch;
import art.arcane.animus.core.metrics.MetricsSnapshot;
import art.arcane.animus.core.metrics.Subsystem;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.text.RichText;
import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.AnimusSplash;
import art.arcane.animus.core.BuildIdentity;
import art.arcane.animus.core.Platform;
import art.arcane.animus.core.RuntimeEnvironment;
import art.arcane.animus.core.behavior.builtin.BuiltinCapabilities;
import art.arcane.animus.core.behavior.builtin.DialogueTriggers;
import art.arcane.animus.core.behavior.SnapshotFacts;
import art.arcane.animus.core.behavior.trigger.ServerTriggerDispatcher;
import art.arcane.animus.core.behavior.trigger.TriggerDispatcher;
import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.building.BuildCommands;
import art.arcane.animus.core.building.BuildProtection;
import art.arcane.animus.core.building.BuildService;
import art.arcane.animus.core.command.AnimusCommandTree;
import art.arcane.animus.core.command.AnimusDirector;
import art.arcane.animus.core.command.CommandServices;
import art.arcane.animus.core.command.ProofControl;
import art.arcane.animus.core.command.BehaviorCommands;
import art.arcane.animus.core.editor.EditorCommands;
import art.arcane.animus.core.editor.EditorHost;
import art.arcane.animus.core.editor.EditorService;
import art.arcane.animus.core.editor.RuntimeEditorHost;
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
import java.util.Optional;
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
import net.minecraft.world.level.Level;
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
    private static final ProofControl BUKKIT_ONLY = ProofControl.unavailable("Packet proof previews are available only on Bukkit and Paper servers.");
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
        ModScreens screens;
        volatile art.arcane.animus.core.shop.ShopService<ServerPlayer, ItemStack> shops;
        VanillaDisplayTransport displays;
        volatile art.arcane.animus.core.gui.HoloService<ServerPlayer, ItemStack> guis;
        BuildService<ServerPlayer, ItemStack> builds;
        BuiltinCapabilities builtins;
        ExtensionRuntime extensions;
        volatile BehaviorCommands behaviors;
        ModServerBehaviors serverBehaviors;
        AnimusDirector commands;
        volatile EditorService editor;
        final DebugWatch watch = new DebugWatch();
        art.arcane.animus.core.hotload.HotloadService hotload;
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
        AnimusMetrics measured = AnimusMetrics.restart();
        try {
            next.runtime = new AnimusRuntime(environment, (Throwable failure) -> LOGGER.error("Animus file cleanup failed", failure));
            next.runtime.start();
            next.runtime.npcs().diagnostics().stream().limit(20).forEach((String problem) -> LOGGER.warn("NPC definition recovery: {}", problem));
            next.ticks = new ModTicks(LOGGER::warn);
            Executor files = next.runtime.fileExecutor((Runnable task, MutationLane.EngineTaskEvent event, Throwable failure) -> {
                if (event == MutationLane.EngineTaskEvent.FAILED) LOGGER.warn("Animus file task failed", failure);
            });
            NpcRuntimeSettings npcSettings = next.runtime.npcSettings();
            PersistedNpcService.Settings settings = PersistedNpcService.Settings.from(npcSettings);
            next.host = new VanillaRuntimeHost(server, next.ticks, LOGGER::warn, this::interactionAllowed, next.runtime.moduleHooks().buildProtection(BuildProtection.NONE));
            next.living = new PersistedNpcService<>(next.host, next.runtime, files, settings);
            LOGGER.info(next.living.status());
            next.combat = new ModCombat(server, next.runtime, next.living, next.host.terrain(), LOGGER::warn);
            LOGGER.info(next.combat.status());
            next.ticks.every(next.combat::tick);
            Running owner = next;
            next.screens = new ModScreens(server, next.ticks, art.arcane.animus.core.shop.ShopService.economy(next.runtime.shopSettings(),
                    next.runtime.moduleHooks().economy(art.arcane.animus.core.inventory.Economy.none(ModScreens.NO_ECONOMY))));
            next.shops = new art.arcane.animus.core.shop.ShopService<>(next.runtime, next.screens, next.living, files,
                    (String name) -> Optional.ofNullable(server.getPlayerList().getPlayerByName(name)).map(ServerPlayer::getUUID), LOGGER::info);
            next.screens.bind(next.shops.screens());
            next.displays = new VanillaDisplayTransport(next.screens::display, LOGGER::warn);
            next.guis = new art.arcane.animus.core.gui.HoloService<>(next.runtime, next.shops, next.displays, next.living, files,
                    art.arcane.animus.core.gui.GlossBridge.absent("Gloss is a Bukkit plugin and cannot run on mod servers"), LOGGER::info);
            art.arcane.animus.core.hire.HireService<ServerPlayer, ItemStack> hires = new art.arcane.animus.core.hire.HireService<>(next.runtime, next.shops.screens(), next.living, files,
                    next.combat.hireGuarding(), LOGGER::info);
            next.ticks.every(measured.timed(Subsystem.BEHAVIORS, hires::tick));
            LOGGER.info(hires.status());
            art.arcane.animus.core.shop.ShopService<ServerPlayer, ItemStack> screenService = next.shops;
            art.arcane.animus.core.gui.HoloService<ServerPlayer, ItemStack> holoService = next.guis;
            next.living.interactions((ServerPlayer player, UUID npc, boolean attack, boolean sneaking) ->
                    hires.interacted(player, npc, attack, sneaking) || screenService.interacted(player, npc, attack, sneaking) || holoService.interacted(player, npc, attack, sneaking));
            next.ticks.every(measured.timed(Subsystem.GUI, () -> {
                for (UUID viewer : screenService.screens().viewing()) {
                    ServerPlayer online = server.getPlayerList().getPlayer(viewer);
                    if (online != null) screenService.screens().tick(online);
                }
                for (ServerPlayer online : List.copyOf(server.getPlayerList().getPlayers())) holoService.tick(online);
            }));
            next.shops.reload((art.arcane.animus.core.shop.ShopCatalog catalog) -> {});
            next.guis.reload((art.arcane.animus.core.gui.HoloCatalog catalog) -> {});
            LOGGER.info(next.shops.status());
            LOGGER.info(next.guis.status());
            next.builds = new BuildService<>(next.runtime, next.living, files, new VanillaBuildPlatform(server, next.host.terrain()), next.runtime.moduleHooks().buildProtection(BuildProtection.NONE),
                    "none (mod servers have no standard protection API; a module protection hook can deny block changes)", next.ticks::every,
                    () -> { BehaviorCommands current = owner.behaviors; return current == null ? null : current.triggers(); }, LOGGER::info, null);
            LOGGER.info(next.builds.status());
            DialogueTriggers dialogue = new DialogueTriggers(() -> { BehaviorCommands current = owner.behaviors; return current == null ? null : current.triggers(); });
            next.builtins = new BuiltinCapabilities(new VanillaBehaviorPlatform(server, next.living, next.combat, next.builds, next.ticks, () -> owner.guis),
                    next.runtime.behaviorState(), dialogue::publish, next.runtime.behaviorSettings().actions(), LOGGER::info);
            next.extensions = new ExtensionRuntime(next.runtime.documents().resolve("modules"), modulePlatform(), next.runtime.definitionGateway(),
                    next.runtime.moduleSettings(), LOGGER::info, (ExtensionRuntime.State initialized) -> {
                        BehaviorCommands current = owner.behaviors;
                        if (current != null) current.modulesInitialized(initialized);
                    }, next.builtins);
            next.extensions.connectHooks(next.runtime.moduleHooks());
            next.extensions.follow(next.runtime);
            ExtensionRuntime modules = next.extensions;
            next.living.observeInteractions(modules::interacted);
            next.behaviors = new BehaviorCommands(dataDirectory, next.runtime, next.extensions, LOGGER::info);
            next.runtime.catalogs(new art.arcane.animus.core.RuntimeCatalogs(new art.arcane.animus.core.RuntimeCatalogs.Parts(next.runtime, next.living::refresh,
                    () -> owner.behaviors, () -> screenService.reload((art.arcane.animus.core.shop.ShopCatalog catalog) -> {}),
                    () -> holoService.reload((art.arcane.animus.core.gui.HoloCatalog catalog) -> {}), LOGGER::info)));
            BuiltinCapabilities actions = next.builtins;
            next.runtime.onSettings((art.arcane.animus.core.config.PluginSettings settingsNow) -> actions.settings(settingsNow.behaviors().actions()));
            next.hotload = new art.arcane.animus.core.hotload.HotloadService(new art.arcane.animus.core.hotload.HotloadService.Parts(next.runtime, files, server::execute, next.living,
                    new ModOperators(server), screenService::refreshOpen, LOGGER::info));
            next.hotload.start();
            TriggerDispatcher triggers = next.behaviors.triggers();
            ServerTriggerDispatcher servers = next.behaviors.servers();
            if (triggers != null) next.ticks.every(new art.arcane.animus.core.behavior.trigger.TriggerLink(triggers, servers, next.living, LOGGER::info));
            NpcRepository definitions = next.runtime.npcs();
            PersistedNpcService<ServerPlayer, ItemStack> npcs = next.living;
            SnapshotFacts facts = new SnapshotFacts(SnapshotFacts.npcs(definitions::snapshot, npcs::state));
            next.behaviors.facts(facts);
            next.serverBehaviors = new ModServerBehaviors(server, servers, facts, LOGGER::info);
            next.ticks.every(measured.timed(Subsystem.BEHAVIORS, next.serverBehaviors::tick));
            DebugCommands debugCommands = new DebugCommands(() -> measured, owner.watch,
                    (String id) -> owner.runtime.isRunning() ? owner.runtime.npcs().find(id).map(NpcDefinition::uuid) : Optional.empty(),
                    () -> { EditorService active = owner.editor; return active == null ? List.of("Editor: off") : active.status(); });
            next.commands = new AnimusCommandTree(new CommandServices(next.runtime, files, next.living, () -> owner.behaviors, next.extensions, next.builtins,
                    next.combat.control(), next.shops, next.guis, new BuildCommands(() -> owner.builds, owner.builds::unavailable), new EditorCommands(() -> owner.editor, files),
                    debugCommands, BUKKIT_ONLY, () -> List.of(server.getPlayerNames()), () -> owner.stopping)).director();
            measured.extra("combat.waiting", next.combat::waiting);
            measured.extra("combat.load", next.combat::load);
            measured.extra("editor.streams", () -> { EditorService active = owner.editor; return active == null ? 0 : active.eventStreams(); });
            measured.listen((MetricsSnapshot snapshot) -> owner.watch.publish(snapshot, (UUID id, String line) -> {
                ServerPlayer player = server.getPlayerList().getPlayer(id);
                if (player == null || owner.stopping) return false;
                owner.screens.actionBar(player, RichText.colorize(line));
                return true;
            }));
            next.ticks.every(measured::serverTick);
            next.extensions.start();
            startEditor(server, next, files);
            running = next;
            ModHooks.attach(this);
            LOGGER.info(AnimusSplash.render(environment, platform + " / Minecraft " + environment.minecraftVersion(), true));
            AnimusRuntime started = next.runtime;
            files.execute(() -> {
                try { new art.arcane.animus.core.migration.CitizensDropIn(started.documents(), started.npcs(), started.importHost()).startupNotice().ifPresent(LOGGER::info); }
                catch (IOException | RuntimeException failure) { LOGGER.warn("Citizens migration check failed: {}", failure.getMessage()); }
            });
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
        active.watch.clear();
        if (active.hotload != null) active.hotload.close();
        EditorService editor = active.editor;
        active.editor = null;
        if (editor != null) editor.close();
        if (active.serverBehaviors != null) active.serverBehaviors.close();
        if (active.behaviors != null) active.behaviors.close();
        if (active.builtins != null) active.builtins.close();
        if (active.screens != null) active.screens.closeAll();
        if (active.guis != null) active.guis.close();
        if (active.shops != null) active.shops.close();
        if (active.extensions != null) active.extensions.close();
        if (active.builds != null) active.builds.close();
        if (active.combat != null) active.combat.close();
        if (active.living != null) active.living.close();
        if (active.ticks != null) active.ticks.close();
        if (active.runtime != null) active.runtime.closeAsync();
    }

    /** The web editor API, the same as on Bukkit: the embedded listener and the relay client, both off until config.toml enables them. */
    private void startEditor(MinecraftServer server, Running next, Executor files) {
        java.util.function.Supplier<Map<String, EditorHost.Feature>> features = () -> Map.of(
                "combat", EditorHost.Feature.yes(),
                "gloss", EditorHost.Feature.no("Gloss is a Bukkit plugin"),
                "placeholderapi", EditorHost.Feature.no("PlaceholderAPI is a Bukkit plugin"));
        EditorService service = new EditorService(next.runtime, new RuntimeEditorHost<>(new RuntimeEditorHost.Parts<>(next.runtime, files, next.living,
                next.extensions, platform.name().toLowerCase(java.util.Locale.ROOT), "", features,
                (ServerPlayer player) -> VanillaRuntimeHost.permitted(player.permissions(), "animus.admin"), LOGGER::info)), LOGGER::info);
        try {
            service.start();
            next.editor = service;
            service.status().forEach(LOGGER::info);
        } catch (IOException | RuntimeException failure) {
            LOGGER.warn("The web editor API could not start", failure);
            service.close();
        }
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
        active.commands.execute(new ModActor(source, active.ticks), words(text));
        return 1;
    }
    /** Completes the word being typed; a suggestion replaces only that word. */
    private CompletableFuture<Suggestions> suggest(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        Running active = running;
        if (active == null) return builder.buildFuture();
        String remaining = builder.getRemaining();
        SuggestionsBuilder last = builder.createOffset(builder.getStart() + remaining.lastIndexOf(' ') + 1);
        for (String option : active.commands.complete(new ModActor(context.getSource(), active.ticks), remaining.split(" ", -1))) last.suggest(option);
        return last.buildFuture();
    }
    /** The words of a command, split as a Bukkit command receives them. */
    static String[] words(String text) { return text.isEmpty() ? new String[0] : text.split(" ", -1); }
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
        if (transport != null && transport.interact(player, entityId, attack, hand, secondary)) return true;
        return active != null && active.displays != null && active.displays.interact(player, entityId, attack, hand, secondary);
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
        if (active == null || player.position().distanceToSqr(x, y, z) < CORRECTION_SQUARED) return;
        if (active.shops != null) active.shops.screens().teleported(player);
        if (active.guis != null) active.guis.teleported(player);
        active.living.teleported(player);
    }
    void joined(ServerPlayer player) {
        Running active = running;
        if (active == null) return;
        active.living.joined(player);
        if (active.serverBehaviors != null) active.serverBehaviors.joined(player);
    }
    void quit(ServerPlayer player) {
        Running active = running;
        if (active == null) return;
        if (active.shops != null) active.shops.screens().quit(player);
        if (active.guis != null) active.guis.quit(player);
        active.living.quit(player);
        if (active.serverBehaviors != null) active.serverBehaviors.quit(player);
    }
    void respawned(ServerPlayer old, ServerPlayer player) {
        Running active = running;
        if (active == null) return;
        if (active.shops != null) active.shops.screens().worldChanged(player);
        if (active.guis != null) active.guis.worldChanged(player);
        active.living.respawned(player);
        if (active.serverBehaviors != null) {
            active.serverBehaviors.respawned(player);
            active.serverBehaviors.worldChanged(player, ModWorldKeys.key(old.level()), ModWorldKeys.key(player.level()));
        }
    }
    /** A teleport that ended in another dimension. */
    void worldChanged(ServerPlayer player, Level from) {
        Running active = running;
        if (active != null && active.serverBehaviors != null) active.serverBehaviors.worldChanged(player, ModWorldKeys.key(from), ModWorldKeys.key(player.level()));
    }
    void died(ServerPlayer player) {
        Running active = running;
        if (active == null) return;
        if (active.shops != null) active.shops.screens().died(player);
        if (active.guis != null) active.guis.died(player);
        active.living.died(player);
        if (active.serverBehaviors != null) active.serverBehaviors.died(player);
    }
    void realDamaged(LivingEntity victim, DamageSource source) { Running active = running; if (active != null) active.combat.realDamaged(victim, source); }
    void chat(ServerPlayer player, String message) {
        Running active = running;
        if (active == null) return;
        active.combat.chat(player, message);
        if (active.serverBehaviors != null) active.serverBehaviors.chat(player, message);
    }
    boolean helperHit(Projectile projectile, HitResult hit) { Running active = running; return active != null && active.combat.helperHit(projectile, hit); }
    boolean suppressesDrops(LivingEntity entity) { Running active = running; return active != null && active.combat.suppressesDrops(entity); }
}
