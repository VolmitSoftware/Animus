package art.arcane.animus.mod;

import art.arcane.animus.core.behavior.builtin.DialogueFormat;
import art.arcane.animus.api.NpcHandle;
import art.arcane.animus.core.BuildIdentity;
import art.arcane.animus.core.behavior.builtin.BehaviorPlatform;
import art.arcane.animus.core.behavior.builtin.PlatformRefusals;
import art.arcane.animus.core.building.BuildService;
import art.arcane.animus.core.navigation.MovementProfile;
import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.runtime.NpcRuntime;
import art.arcane.animus.core.runtime.PersistedNpcService;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import com.mojang.brigadier.ParseResults;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Behavior world work on the mod server thread, the single owner of every NPC and player. */
final class VanillaBehaviorPlatform implements BehaviorPlatform {
    private static final int MAXIMUM_AUDIENCE = 256;
    private final MinecraftServer server;
    private final PersistedNpcService<ServerPlayer, ItemStack> living;
    private final ModCombat combat;
    private final ModTicks ticks;
    private final BuildService<ServerPlayer, ItemStack> builds;
    private final java.util.function.Supplier<art.arcane.animus.core.gui.HoloService<ServerPlayer, ItemStack>> guis;

    VanillaBehaviorPlatform(MinecraftServer server, PersistedNpcService<ServerPlayer, ItemStack> living, ModCombat combat, BuildService<ServerPlayer, ItemStack> builds, ModTicks ticks,
                            java.util.function.Supplier<art.arcane.animus.core.gui.HoloService<ServerPlayer, ItemStack>> guis) {
        this.guis = guis;
        this.server = Objects.requireNonNull(server); this.living = Objects.requireNonNull(living);
        this.combat = Objects.requireNonNull(combat); this.ticks = Objects.requireNonNull(ticks);
        this.builds = Objects.requireNonNull(builds);
    }

    @Override public Optional<String> unavailable() { return PlatformRefusals.unavailability(living::available, living::status); }
    @Override public AutoCloseable ownerTicks(Runnable tick) { return ticks.every(tick); }
    @Override public boolean onNpcOwner(NpcHandle npc, Runnable work, Consumer<String> refused) { return living.onOwner(npc.uuid(), work, refused); }

    @Override public boolean hasPermission(UUID player, String node) {
        return living.players().owned(player).map((ServerPlayer online) -> VanillaRuntimeHost.permitted(online.permissions(), node)).orElse(false);
    }
    @Override public NpcStatus npcStatus(NpcHandle npc) {
        return living.active(npc.uuid()).map((NpcRuntime.ActiveNpc active) -> new NpcStatus(active.state().active(), active.state().health())).orElse(new NpcStatus(false, 0));
    }
    @Override public boolean playerInRange(NpcHandle npc, Optional<UUID> player, double radius) {
        Optional<NpcRuntime.ActiveNpc> active = living.active(npc.uuid());
        if (active.isEmpty()) return false;
        WorldPosition at = active.get().state().position();
        if (player.isEmpty()) return !living.players().ownedNear(at, radius, 1).isEmpty();
        Optional<ServerPlayer> online = living.players().owned(player.get());
        if (online.isEmpty()) return false;
        net.minecraft.world.phys.Vec3 position = online.get().position();
        return ModWorldKeys.key(online.get().level()).equals(at.world()) && new Vec3(position.x(), position.y(), position.z()).distance(at.point()) <= radius;
    }
    @Override public int say(NpcHandle npc, Audience audience, String text) {
        NpcRuntime.ActiveNpc active = requireVoice(npc);
        Component line = ModText.component(DialogueFormat.speech(active.name(), text));
        return deliver(audience, active.state().position(), false, (ServerPlayer viewer) -> viewer.sendSystemMessage(line));
    }
    @Override public int presentChoices(NpcHandle npc, UUID player, String prompt, String token, List<ChoiceOption> options) {
        NpcRuntime.ActiveNpc active = require(npc);
        Component message = ModText.component(DialogueFormat.speech(active.name(), prompt));
        Component buttons = ModText.parts(DialogueFormat.buttons(BuildIdentity.ID, token, List.copyOf(options)));
        return living.players().deliver(player, (ServerPlayer online) -> { online.sendSystemMessage(message); online.sendSystemMessage(buttons); }) ? 1 : 0;
    }
    @Override public void runCommand(CommandSource source, Optional<UUID> player, String command) {
        CommandSourceStack stack;
        if (source == CommandSource.CONSOLE) stack = server.createCommandSourceStack();
        else {
            UUID target = player.filter(living.players()::online).orElseThrow(PlatformRefusals::offline);
            stack = living.players().owned(target).orElseThrow(PlatformRefusals::offline).createCommandSourceStack();
        }
        Commands commands = server.getCommands();
        String text = command.startsWith("/") ? command.substring(1) : command;
        ParseResults<CommandSourceStack> parsed = commands.getDispatcher().parse(text, stack);
        if (Commands.getParseException(parsed) != null || parsed.getContext().getCommand() == null) throw PlatformRefusals.rejectedCommand();
        commands.performCommand(parsed, text);
    }
    @Override public String navigate(NpcHandle npc, Vec3 destination, MovementProfile.Mode mode, double speed) {
        NpcRuntime.ActiveNpc active = require(npc);
        WorldPosition goal = new WorldPosition(active.state().position().world(), destination);
        return PlatformRefusals.mapped(() -> living.navigate(active.id(), goal, mode, speed).toString());
    }
    @Override public void followRoute(NpcHandle npc, String route) {
        NpcRuntime.ActiveNpc active = require(npc);
        PlatformRefusals.mapped(() -> { living.followRoute(active.id(), route); return true; });
    }
    @Override public CompletionStage<Void> routeFinished(NpcHandle npc) {
        NpcRuntime.ActiveNpc active = require(npc);
        return PlatformRefusals.mapped(() -> living.awaitRoute(active.id()));
    }
    @Override public String navigationStatus(NpcHandle npc) {
        return living.active(npc.uuid()).map((NpcRuntime.ActiveNpc active) -> living.navigationStatus(active.id())).orElse("inactive");
    }
    @Override public boolean stopNavigation(NpcHandle npc) {
        Optional<NpcRuntime.ActiveNpc> active = living.active(npc.uuid());
        return active.isPresent() && PlatformRefusals.mapped(() -> living.stopNavigation(active.get().id()));
    }
    @Override public void look(NpcHandle npc, Vec3 target) {
        NpcRuntime.ActiveNpc active = require(npc);
        PlatformRefusals.mapped(() -> { living.lookAt(active.id(), new WorldPosition(active.state().position().world(), target)); return true; });
    }
    @Override public void lookAtPlayer(NpcHandle npc, UUID player) {
        NpcRuntime.ActiveNpc active = require(npc);
        ServerPlayer online = living.players().owned(player).orElseThrow(PlatformRefusals::offline);
        net.minecraft.world.phys.Vec3 eye = online.getEyePosition();
        WorldPosition target = new WorldPosition(ModWorldKeys.key(online.level()), new Vec3(eye.x(), eye.y(), eye.z()));
        PlatformRefusals.mapped(() -> { living.lookAt(active.id(), target); return true; });
    }
    @Override public void setAppearance(NpcHandle npc, AppearanceChange change) {
        NpcRuntime.ActiveNpc active = require(npc);
        PlatformRefusals.mapped(() -> { living.setAppearance(active.id(), change.name(), change.skin(), change.reset()); return true; });
    }
    @Override public void setEquipment(NpcHandle npc, EquipmentSlot slot, Optional<String> item) {
        NpcRuntime.ActiveNpc active = require(npc);
        ItemStack stack = item.map(VanillaBehaviorPlatform::stack).orElse(ItemStack.EMPTY);
        PlatformRefusals.mapped(() -> { living.setEquipment(active.id(), slot, stack); return true; });
    }
    private static ItemStack stack(String key) {
        Identifier id = Identifier.tryParse(key);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) throw PlatformRefusals.unknownItem(key);
        return new ItemStack(BuiltInRegistries.ITEM.getValue(id));
    }
    @Override public int playSound(NpcHandle npc, Audience audience, SoundEffect sound) {
        NpcRuntime.ActiveNpc active = requireVoice(npc);
        WorldPosition at = bodyCenter(active);
        Identifier id = Identifier.tryParse(sound.sound());
        if (id == null) throw PlatformRefusals.refusal(art.arcane.animus.api.Outcome.Status.INVALID, "unknown-sound", new IllegalArgumentException("Unknown sound: " + sound.sound()));
        Holder<SoundEvent> event = BuiltInRegistries.SOUND_EVENT.get(id).<Holder<SoundEvent>>map((Holder.Reference<SoundEvent> reference) -> reference)
                .orElseGet(() -> Holder.direct(SoundEvent.createVariableRangeEvent(id)));
        long seed = server.overworld().getRandom().nextLong();
        return deliver(audience, at, true, (ServerPlayer viewer) -> viewer.connection.send(new ClientboundSoundPacket(event, SoundSource.NEUTRAL,
                at.point().x(), at.point().y(), at.point().z(), sound.volume(), sound.pitch(), seed)));
    }
    @Override public int spawnParticle(NpcHandle npc, Audience audience, ParticleEffect effect) {
        NpcRuntime.ActiveNpc active = requireVoice(npc);
        WorldPosition at = bodyCenter(active);
        SimpleParticleType particle = particle(effect.particle());
        ServerLevel level = ModWorldKeys.level(server, at.world());
        if (level == null) throw PlatformRefusals.worldUnavailable();
        return deliver(audience, at, true, (ServerPlayer viewer) -> level.sendParticles(viewer, particle, false, false,
                at.point().x(), at.point().y(), at.point().z(), effect.count(), effect.spread(), effect.spread(), effect.spread(), 0));
    }
    private static SimpleParticleType particle(String key) {
        Identifier id = Identifier.tryParse(key);
        ParticleType<?> type = id == null ? null : BuiltInRegistries.PARTICLE_TYPE.getValue(id);
        if (type == null || !BuiltInRegistries.PARTICLE_TYPE.containsKey(id)) throw PlatformRefusals.unknownParticle(key);
        if (!(type instanceof SimpleParticleType simple)) throw PlatformRefusals.particleNeedsData(key);
        return simple;
    }
    @Override public void combat(NpcHandle npc, CombatOperation operation, Optional<UUID> target) {
        NpcRuntime.ActiveNpc active = require(npc);
        PlatformRefusals.mapped(() -> combat.operate(active.state().npcId(), operation, target));
    }

    @Override public String build(NpcHandle npc, BuildOperation operation, Optional<String> schematic, Optional<Vec3> origin, java.util.Map<String, String> options) {
        NpcRuntime.ActiveNpc active = require(npc);
        if (builds.unavailable().isPresent())
            throw new art.arcane.animus.core.extension.CapabilityRefusal(art.arcane.animus.api.Outcome.Status.UNAVAILABLE, "build-unavailable", "Builds " + builds.unavailable().get());
        return builds.behavior(active.id(), operation, schematic, origin, options);
    }

    private NpcRuntime.ActiveNpc require(NpcHandle npc) { return living.active(npc.uuid()).orElseThrow(PlatformRefusals::inactive); }
    /** Dialogue, sounds and particles also come from a combat NPC that has just died, so its death routine can announce it. */
    private NpcRuntime.ActiveNpc requireVoice(NpcHandle npc) { return living.voice(npc.uuid()).orElseThrow(PlatformRefusals::inactive); }
    private static WorldPosition bodyCenter(NpcRuntime.ActiveNpc active) {
        WorldPosition position = active.state().position();
        double y = (active.state().bounds().min().y() + active.state().bounds().max().y()) / 2;
        return new WorldPosition(position.world(), new Vec3(position.point().x(), y, position.point().z()));
    }
    /** The trigger player, or nearby players. Returns how many were reached. */
    private int deliver(Audience audience, WorldPosition at, boolean sameWorldOnly, Consumer<ServerPlayer> action) {
        if (audience.player().isPresent()) {
            Optional<ServerPlayer> viewer = living.players().owned(audience.player().get());
            if (viewer.isEmpty()) return 0;
            if (sameWorldOnly && !ModWorldKeys.key(viewer.get().level()).equals(at.world())) return 0;
            action.accept(viewer.get());
            return 1;
        }
        List<ServerPlayer> nearby = living.players().ownedNear(at, audience.radius(), MAXIMUM_AUDIENCE);
        for (ServerPlayer viewer : nearby) action.accept(viewer);
        return nearby.size();
    }

    @Override public boolean openScreen(NpcHandle npc, UUID player, ScreenKind kind, String id) {
        art.arcane.animus.core.gui.HoloService<ServerPlayer, ItemStack> service = guis.get();
        if (service == null)
            throw new art.arcane.animus.core.extension.CapabilityRefusal(art.arcane.animus.api.Outcome.Status.UNAVAILABLE, "screens-unavailable", "GUIs are not running");
        art.arcane.animus.core.gui.GuiBinding.Kind target = art.arcane.animus.core.gui.HoloService.kind(kind);
        Optional<art.arcane.animus.core.extension.CapabilityRefusal> refused = service.refusal(target);
        if (refused.isPresent()) throw refused.get();
        return living.players().deliver(player, (ServerPlayer viewer) -> service.open(viewer, target, id, java.util.Map.of(), Optional.of(npc.uuid())));
    }
}
