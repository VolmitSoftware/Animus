package art.arcane.animus.mod;

import art.arcane.animus.core.metrics.AnimusMetrics;
import art.arcane.animus.core.metrics.Subsystem;
import art.arcane.animus.core.behavior.builtin.DialogueFormat;
import art.arcane.animus.api.NpcHandle;
import art.arcane.animus.core.BuildIdentity;
import art.arcane.animus.core.behavior.builtin.BehaviorPlatform;
import art.arcane.animus.core.behavior.builtin.Charges;
import art.arcane.animus.core.behavior.builtin.PlatformRefusals;
import art.arcane.animus.core.behavior.ServerNpc;
import art.arcane.animus.core.building.BuildService;
import art.arcane.animus.core.navigation.MovementProfile;
import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.recording.Playback;
import art.arcane.animus.core.runtime.NpcRuntime;
import art.arcane.animus.core.runtime.PersistedNpcService;
import art.arcane.animus.core.behavior.trigger.HiddenNpcs;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import art.arcane.animus.api.Outcome;
import art.arcane.animus.core.extension.CapabilityRefusal;
import art.arcane.animus.core.text.RichText;
import com.mojang.brigadier.ParseResults;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.WrittenBookContent;

/** Behavior world work on the mod server thread, the single owner of every NPC and player. */
final class VanillaBehaviorPlatform implements BehaviorPlatform {
    private static final int MAXIMUM_AUDIENCE = 256;
    private final MinecraftServer server;
    private final PersistedNpcService<ServerPlayer, ItemStack> living;
    private final ModCombat combat;
    private final ModTicks ticks;
    private final BuildService<ServerPlayer, ItemStack> builds;
    private final java.util.function.Supplier<art.arcane.animus.core.gui.HoloService<ServerPlayer, ItemStack>> guis;
    private final java.util.function.Supplier<art.arcane.animus.core.inventory.Economy> economy;

    VanillaBehaviorPlatform(MinecraftServer server, PersistedNpcService<ServerPlayer, ItemStack> living, ModCombat combat, BuildService<ServerPlayer, ItemStack> builds, ModTicks ticks,
                            java.util.function.Supplier<art.arcane.animus.core.gui.HoloService<ServerPlayer, ItemStack>> guis,
                            java.util.function.Supplier<art.arcane.animus.core.inventory.Economy> economy) {
        this.guis = guis;
        this.economy = Objects.requireNonNull(economy);
        this.server = Objects.requireNonNull(server); this.living = Objects.requireNonNull(living);
        this.combat = Objects.requireNonNull(combat); this.ticks = Objects.requireNonNull(ticks);
        this.builds = Objects.requireNonNull(builds);
    }

    @Override public Optional<String> unavailable() { return PlatformRefusals.unavailability(living::available, living::status); }
    @Override public AutoCloseable ownerTicks(Runnable tick) { return ticks.every(AnimusMetrics.current().timed(Subsystem.BEHAVIORS, tick)); }
    @Override public boolean onNpcOwner(NpcHandle npc, Runnable work, Consumer<String> refused) { return living.onOwner(npc.uuid(), work, refused); }
    /** The server thread owns every player too, so player and global work run inline there like NPC work. */
    @Override public boolean onPlayerOwner(UUID player, Runnable work, Consumer<String> refused) { return living.onOwner(player, work, refused); }
    @Override public boolean onGlobalOwner(Runnable work, Consumer<String> refused) { return living.onOwner(ServerNpc.HANDLE.uuid(), work, refused); }

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
        return deliver(active, audience, active.state().position(), false, (ServerPlayer viewer) -> viewer.sendSystemMessage(line));
    }
    @Override public int presentChoices(NpcHandle npc, UUID player, String prompt, String token, List<ChoiceOption> options) {
        NpcRuntime.ActiveNpc active = require(npc);
        if (living.hidden().hides(player, active.id(), active.state().npcId())) return 0;
        Component message = ModText.component(DialogueFormat.speech(active.name(), prompt));
        Component buttons = ModText.parts(DialogueFormat.buttons(BuildIdentity.ID, token, List.copyOf(options)));
        return living.players().deliver(player, (ServerPlayer online) -> { online.sendSystemMessage(message); online.sendSystemMessage(buttons); }) ? 1 : 0;
    }
    /** Online players only; an offline player's name is not known here. */
    @Override public Optional<String> playerName(UUID player) {
        ServerPlayer online = server.getPlayerList().getPlayer(player);
        return online == null ? Optional.empty() : Optional.of(online.getGameProfile().name());
    }
    /**
     * Mod servers have no placeholder plugin, so nothing is filled, and no permission system to lend nodes from. An
     * operator command runs with the server's operator level for that one command.
     */
    @Override public void runCommand(CommandRun run) {
        if (!run.permissions().isEmpty())
            throw new CapabilityRefusal(Outcome.Status.UNAVAILABLE, "permissions-unsupported", "Mod servers cannot lend permissions to a command; only Paper, Spigot and Folia can");
        CommandSourceStack stack;
        if (run.source() == CommandSource.CONSOLE) stack = server.createCommandSourceStack();
        else {
            UUID target = run.player().filter(living.players()::online).orElseThrow(PlatformRefusals::offline);
            stack = living.players().owned(target).orElseThrow(PlatformRefusals::offline).createCommandSourceStack();
            // The operator level lives only in this command's source; the operator list is never touched, so nothing can be left behind.
            if (run.operator()) stack = stack.withPermission(stack.permissions().union(server.operatorUserPermissions()));
        }
        Commands commands = server.getCommands();
        String text = run.command().startsWith("/") ? run.command().substring(1) : run.command();
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
    @Override public void playRecording(NpcHandle npc, String recording, Optional<Playback> playback) {
        NpcRuntime.ActiveNpc active = require(npc);
        PlatformRefusals.mapped(() -> { living.playRecording(active.id(), recording, playback); return true; });
    }
    @Override public boolean resumePatrol(NpcHandle npc) {
        NpcRuntime.ActiveNpc active = require(npc);
        return PlatformRefusals.mapped(() -> living.resumePatrol(active.id()));
    }
    @Override public void look(NpcHandle npc, Vec3 target) {
        NpcRuntime.ActiveNpc active = require(npc);
        PlatformRefusals.mapped(() -> { living.lookAt(active.id(), new WorldPosition(active.state().position().world(), target)); return true; });
    }
    @Override public boolean inWorld(NpcHandle npc, String world) {
        String at = require(npc).state().position().world();
        return ModWorldKeys.resolve(server, world).map(at::equals).orElse(false);
    }
    @Override public Point anchor(NpcHandle npc, String name) {
        NpcRuntime.ActiveNpc active = require(npc);
        art.arcane.animus.core.npc.NpcAnchors.Anchor anchor = PlatformRefusals.mapped(() -> living.anchor(active.id(), name));
        return new Point(anchor.world(), anchor.position(), java.util.OptionalDouble.empty(), java.util.OptionalDouble.empty());
    }
    @Override public void animate(NpcHandle npc, art.arcane.animus.core.npc.NpcAnimation animation) {
        NpcRuntime.ActiveNpc active = require(npc);
        PlatformRefusals.mapped(() -> { living.animate(active.id(), animation); return true; });
    }
    @Override public void pose(NpcHandle npc, String pose) {
        NpcRuntime.ActiveNpc active = require(npc);
        PlatformRefusals.mapped(() -> { living.pose(active.id(), pose); return true; });
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
    @Override public void bubble(NpcHandle npc, String text, int ticks) {
        NpcRuntime.ActiveNpc active = require(npc);
        PlatformRefusals.mapped(() -> { living.bubble(active.id(), text, ticks); return true; });
    }
    @Override public void playAnimation(NpcHandle npc, String animation, double speed, Optional<String> loop, int layer) {
        NpcRuntime.ActiveNpc active = require(npc);
        PlatformRefusals.mapped(() -> { living.playAnimation(active.id(), animation, speed, loop, layer); return true; });
    }

    @Override public void stopAnimation(NpcHandle npc, java.util.OptionalInt layer, int fadeTicks) {
        NpcRuntime.ActiveNpc active = require(npc);
        PlatformRefusals.mapped(() -> { living.stopAnimation(active.id(), layer, fadeTicks); return true; });
    }

    @Override public void setEquipment(NpcHandle npc, EquipmentSlot slot, Optional<String> item) {
        NpcRuntime.ActiveNpc active = require(npc);
        ItemStack stack = item.map(VanillaBehaviorPlatform::stack).orElse(ItemStack.EMPTY);
        PlatformRefusals.mapped(() -> { living.setEquipment(active.id(), slot, stack); return true; });
    }

    @Override public void setAttribute(NpcHandle npc, String attribute, java.util.OptionalDouble value) {
        NpcRuntime.ActiveNpc active = require(npc);
        PlatformRefusals.mapped(() -> { living.setAttribute(active.id(), attribute, value); return true; });
    }
    private static ItemStack stack(String key) {
        Identifier id = Identifier.tryParse(key);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) throw PlatformRefusals.unknownItem(key);
        return new ItemStack(BuiltInRegistries.ITEM.getValue(id));
    }
    @Override public int playSound(NpcHandle npc, Audience audience, SoundEffect sound) {
        NpcRuntime.ActiveNpc active = requireVoice(npc);
        WorldPosition at = bodyCenter(active);
        Holder<SoundEvent> event = sound(sound.sound());
        long seed = server.overworld().getRandom().nextLong();
        return deliver(active, audience, at, true, (ServerPlayer viewer) -> viewer.connection.send(new ClientboundSoundPacket(event, SoundSource.NEUTRAL,
                at.point().x(), at.point().y(), at.point().z(), sound.volume(), sound.pitch(), seed)));
    }
    static Holder<SoundEvent> sound(String key) {
        Identifier id = Identifier.tryParse(key);
        if (id == null) throw PlatformRefusals.refusal(art.arcane.animus.api.Outcome.Status.INVALID, "unknown-sound", new IllegalArgumentException("Unknown sound: " + key));
        return BuiltInRegistries.SOUND_EVENT.get(id).<Holder<SoundEvent>>map((Holder.Reference<SoundEvent> reference) -> reference)
                .orElseGet(() -> Holder.direct(SoundEvent.createVariableRangeEvent(id)));
    }
    @Override public int spawnParticle(NpcHandle npc, Audience audience, ParticleEffect effect) {
        NpcRuntime.ActiveNpc active = requireVoice(npc);
        WorldPosition center = bodyCenter(active);
        WorldPosition at = effect.height().isPresent()
                ? new WorldPosition(center.world(), new Vec3(center.point().x(), active.state().bounds().min().y() + effect.height().getAsDouble(), center.point().z())) : center;
        SimpleParticleType particle = particle(effect.particle());
        ServerLevel level = ModWorldKeys.level(server, at.world());
        if (level == null) throw PlatformRefusals.worldUnavailable();
        return deliver(active, audience, at, true, (ServerPlayer viewer) -> level.sendParticles(viewer, particle, false, false,
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

    @Override public int message(NpcHandle npc, Optional<UUID> player, MessageTarget to, String text) {
        Component line = formatted(text);
        return switch (to) {
            case CONSOLE -> { server.sendSystemMessage(line); yield 1; }
            case PLAYER -> living.players().deliver(player.orElseThrow(() -> invalid("message-needs-player", "A player message needs a triggering player")),
                    (ServerPlayer online) -> online.sendSystemMessage(line)) ? 1 : 0;
            case ALL -> broadcast(server.getPlayerList().getPlayers(), line);
            case WORLD -> {
                String world;
                if (ServerNpc.is(npc)) {
                    Optional<ServerPlayer> online = living.players().owned(player.orElseThrow(() -> invalid("message-needs-world", "A world message in a server behavior needs a triggering player")));
                    if (online.isEmpty()) yield 0;
                    world = ModWorldKeys.key(online.get().level());
                } else world = requireVoice(npc).state().position().world();
                ServerLevel level = ModWorldKeys.level(server, world);
                if (level == null) throw PlatformRefusals.worldUnavailable();
                yield broadcast(level.players(), line);
            }
            case NEARBY -> messageNearby(npc, 16, text);
        };
    }
    @Override public int messageNearby(NpcHandle npc, double radius, String text) {
        NpcRuntime.ActiveNpc active = requireVoice(npc);
        Component line = formatted(text);
        return deliver(active, new Audience(Optional.empty(), radius), active.state().position(), false, (ServerPlayer viewer) -> viewer.sendSystemMessage(line));
    }
    private static int broadcast(List<ServerPlayer> receivers, Component line) {
        List<ServerPlayer> snapshot = List.copyOf(receivers);
        for (ServerPlayer receiver : snapshot) receiver.sendSystemMessage(line);
        return snapshot.size();
    }
    @Override public int title(UUID player, Title title) {
        Component main = formatted(title.title().orElse("")), sub = formatted(title.subtitle().orElse(""));
        return living.players().deliver(player, (ServerPlayer online) -> {
            online.connection.send(new ClientboundSetTitlesAnimationPacket(title.fadeIn(), title.stay(), title.fadeOut()));
            online.connection.send(new ClientboundSetSubtitleTextPacket(sub));
            online.connection.send(new ClientboundSetTitleTextPacket(main));
        }) ? 1 : 0;
    }
    @Override public int actionbar(UUID player, String text) {
        Component line = formatted(text);
        return living.players().deliver(player, (ServerPlayer online) -> online.sendSystemMessage(line, true)) ? 1 : 0;
    }
    /** Each player's behavior boss bars by id, and the players shown a behavior sidebar; players gone offline are dropped as these change. */
    private final java.util.Map<UUID, java.util.Map<String, net.minecraft.server.level.ServerBossEvent>> bossBars = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<UUID> sidebars = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final String SIDEBAR = "animus_sidebar";

    @Override public int bossBar(UUID player, BossBar bar) {
        bossBars.entrySet().removeIf((java.util.Map.Entry<UUID, java.util.Map<String, net.minecraft.server.level.ServerBossEvent>> entry) -> {
            if (living.players().owned(entry.getKey()).isPresent()) return false;
            entry.getValue().values().forEach(net.minecraft.server.level.ServerBossEvent::removeAllPlayers);
            return true;
        });
        Optional<ServerPlayer> online = living.players().owned(player);
        if (online.isEmpty()) return 0;
        java.util.Map<String, net.minecraft.server.level.ServerBossEvent> mine = bossBars.computeIfAbsent(player, (UUID ignored) -> new java.util.concurrent.ConcurrentHashMap<>());
        if (bar.remove()) {
            net.minecraft.server.level.ServerBossEvent shown = mine.remove(bar.id());
            if (shown != null) shown.removeAllPlayers();
            return 1;
        }
        net.minecraft.server.level.ServerBossEvent shown = mine.computeIfAbsent(bar.id(), (String ignored) -> {
            net.minecraft.server.level.ServerBossEvent created = new net.minecraft.server.level.ServerBossEvent(UUID.randomUUID(), Component.empty(),
                    net.minecraft.world.BossEvent.BossBarColor.WHITE, net.minecraft.world.BossEvent.BossBarOverlay.PROGRESS);
            created.setProgress(0);
            return created;
        });
        bar.title().ifPresent((String title) -> shown.setName(formatted(title)));
        bar.progress().ifPresent((double progress) -> shown.setProgress((float) progress));
        bar.color().ifPresent((String color) -> shown.setColor(net.minecraft.world.BossEvent.BossBarColor.valueOf(color.toUpperCase(java.util.Locale.ROOT))));
        bar.style().ifPresent((String style) -> shown.setOverlay(style.equals("solid") ? net.minecraft.world.BossEvent.BossBarOverlay.PROGRESS
                : net.minecraft.world.BossEvent.BossBarOverlay.valueOf("NOTCHED_" + style.substring(style.indexOf('_') + 1))));
        if (!shown.getPlayers().contains(online.get())) {
            shown.removeAllPlayers();
            shown.addPlayer(online.get());
        }
        return 1;
    }

    /** A sidebar is sent to the one player as packets of an objective no server scoreboard holds, so no other player sees it. */
    @Override public int sidebar(UUID player, Optional<Sidebar> sidebar) {
        sidebars.removeIf((UUID known) -> living.players().owned(known).isEmpty());
        Optional<ServerPlayer> online = living.players().owned(player);
        if (online.isEmpty()) return 0;
        net.minecraft.world.scores.Scoreboard scratch = new net.minecraft.world.scores.Scoreboard();
        net.minecraft.world.scores.Objective objective = scratch.addObjective(SIDEBAR, net.minecraft.world.scores.criteria.ObjectiveCriteria.DUMMY,
                formatted(sidebar.map(Sidebar::title).orElse("")), net.minecraft.world.scores.criteria.ObjectiveCriteria.RenderType.INTEGER, false, null);
        net.minecraft.server.network.ServerGamePacketListenerImpl connection = online.get().connection;
        if (sidebars.remove(player)) connection.send(new net.minecraft.network.protocol.game.ClientboundSetObjectivePacket(objective, net.minecraft.network.protocol.game.ClientboundSetObjectivePacket.METHOD_REMOVE));
        if (sidebar.isEmpty()) return 1;
        connection.send(new net.minecraft.network.protocol.game.ClientboundSetObjectivePacket(objective, net.minecraft.network.protocol.game.ClientboundSetObjectivePacket.METHOD_ADD));
        connection.send(new net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket(net.minecraft.world.scores.DisplaySlot.SIDEBAR, objective));
        List<String> lines = sidebar.get().lines();
        for (int line = 0; line < lines.size(); line++)
            connection.send(new net.minecraft.network.protocol.game.ClientboundSetScorePacket("line" + line, SIDEBAR, lines.size() - line, Optional.of(formatted(lines.get(line))), Optional.empty()));
        sidebars.add(player);
        return 1;
    }

    @Override public int playSoundAtPlayer(UUID player, Audience audience, SoundEffect sound) {
        Holder<SoundEvent> event = sound(sound.sound());
        Optional<WorldPosition> at = playerCenter(player);
        if (at.isEmpty()) return 0;
        Vec3 point = at.get().point();
        long seed = server.overworld().getRandom().nextLong();
        return deliverAt(at.get(), audience, (ServerPlayer viewer) -> viewer.connection.send(new ClientboundSoundPacket(event, SoundSource.NEUTRAL,
                point.x(), point.y(), point.z(), sound.volume(), sound.pitch(), seed)));
    }
    @Override public int spawnParticleAtPlayer(UUID player, Audience audience, ParticleEffect effect) {
        SimpleParticleType particle = particle(effect.particle());
        Optional<WorldPosition> at = effect.height().isPresent() ? playerFeet(player).map((WorldPosition feet) ->
                new WorldPosition(feet.world(), new Vec3(feet.point().x(), feet.point().y() + effect.height().getAsDouble(), feet.point().z()))) : playerCenter(player);
        if (at.isEmpty()) return 0;
        ServerLevel level = ModWorldKeys.level(server, at.get().world());
        if (level == null) throw PlatformRefusals.worldUnavailable();
        Vec3 point = at.get().point();
        return deliverAt(at.get(), audience, (ServerPlayer viewer) -> level.sendParticles(viewer, particle, false, false,
                point.x(), point.y(), point.z(), effect.count(), effect.spread(), effect.spread(), effect.spread(), 0));
    }
    @Override public boolean teleportPlayer(UUID player, NpcHandle npc, Destination to) {
        Optional<ServerPlayer> online = living.players().owned(player);
        if (online.isEmpty()) return false;
        ServerPlayer target = online.get();
        Placement place = switch (to) {
            case Point point -> new Placement(new WorldPosition(world(point, ModWorldKeys.key(target.level())), point.position()),
                    point.yaw().isPresent() ? (float) point.yaw().getAsDouble() : target.getYRot(), point.pitch().isPresent() ? (float) point.pitch().getAsDouble() : target.getXRot());
            case ToNpc ignored -> {
                if (ServerNpc.is(npc)) throw invalid("teleport-needs-npc", "A server behavior has no NPC to teleport to");
                yield new Placement(require(npc).state().position(), target.getYRot(), target.getXRot());
            }
            case ToPlayer other -> {
                ServerPlayer destination = living.players().owned(other.player()).orElseThrow(PlatformRefusals::offline);
                yield new Placement(position(destination), destination.getYRot(), destination.getXRot());
            }
        };
        ServerLevel level = ModWorldKeys.level(server, place.at().world());
        if (level == null) throw PlatformRefusals.worldUnavailable();
        Vec3 point = place.at().point();
        target.teleportTo(level, point.x(), point.y(), point.z(), java.util.Set.of(), place.yaw(), place.pitch(), true);
        return true;
    }
    /** Facing is not applied: the NPC runtime relocates an NPC without turning it. */
    @Override public void teleportNpc(NpcHandle npc, Destination to) {
        if (ServerNpc.is(npc)) throw invalid("teleport-needs-npc", "A server behavior has no NPC to teleport");
        NpcRuntime.ActiveNpc active = require(npc);
        WorldPosition at = switch (to) {
            case Point point -> new WorldPosition(world(point, active.state().position().world()), point.position());
            case ToNpc ignored -> throw invalid("teleport-to-self", "An NPC cannot be teleported to itself");
            case ToPlayer other -> position(living.players().owned(other.player()).orElseThrow(PlatformRefusals::offline));
        };
        if (!PlatformRefusals.mapped(() -> living.relocate(active.id(), at)))
            throw new CapabilityRefusal(Outcome.Status.FAILED, "teleport-blocked",
                    "The NPC cannot stand there: the destination must be loaded, clear and supported, and the NPC must not ride or carry anyone");
    }
    /**
     * One of the gift's items: from its data, the item text an inventory read gave, or else its key with its name, lore
     * and enchantments. An unknown item or enchantment, or data the server cannot read, is refused.
     */
    private ItemStack template(ItemGift gift) {
        if (gift.data().isPresent()) {
            try { return new net.minecraft.commands.arguments.item.ItemParser(server.registryAccess()).parse(new com.mojang.brigadier.StringReader(gift.data().get())).createItemStack(1); }
            catch (com.mojang.brigadier.exceptions.CommandSyntaxException unreadable) { throw new CapabilityRefusal(art.arcane.animus.api.Outcome.Status.INVALID, "item-data", "The item data does not describe an item this server knows"); }
        }
        ItemStack template = stack(gift.item());
        gift.name().ifPresent((String name) -> template.set(DataComponents.CUSTOM_NAME, itemText(name)));
        if (!gift.lore().isEmpty()) {
            List<Component> lines = new ArrayList<>();
            for (String line : gift.lore()) if (lines.size() < ItemLore.MAX_LINES) lines.add(itemText(line));
            template.set(DataComponents.LORE, new ItemLore(lines));
        }
        if (gift.enchantments().isEmpty()) return template;
        net.minecraft.core.Registry<net.minecraft.world.item.enchantment.Enchantment> enchantments = server.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT);
        for (java.util.Map.Entry<String, Integer> entry : gift.enchantments().entrySet()) {
            Identifier id = Identifier.tryParse(entry.getKey());
            Optional<Holder.Reference<net.minecraft.world.item.enchantment.Enchantment>> enchantment = id == null ? Optional.empty()
                    : enchantments.get(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.ENCHANTMENT, id));
            if (enchantment.isEmpty()) throw PlatformRefusals.unknownEnchantment(entry.getKey());
            template.enchant(enchantment.get(), entry.getValue());
        }
        return template;
    }
    @Override public int giveItem(UUID player, ItemGift gift) {
        ItemStack template = template(gift);
        Optional<ServerPlayer> online = living.players().owned(player);
        if (online.isEmpty()) return 0;
        int limit = Math.max(1, template.getMaxStackSize());
        for (int remaining = gift.amount(); remaining > 0; remaining -= limit) give(online.get(), template.copyWithCount(Math.min(remaining, limit)));
        return gift.amount();
    }
    @Override public List<HeldItem> inventory(UUID player) {
        Optional<ServerPlayer> online = living.players().owned(player);
        if (online.isEmpty()) return List.of();
        Inventory inventory = online.get().getInventory();
        List<ItemStack> stacks = new ArrayList<>();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) stacks.add(inventory.getItem(slot));
        return held(stacks, server.registryAccess());
    }

    /** The filled slots among the stacks, each with its key and components as data when it has any. */
    static List<HeldItem> held(List<ItemStack> stacks, net.minecraft.core.HolderLookup.Provider registries) {
        List<HeldItem> held = new ArrayList<>();
        for (int slot = 0; slot < stacks.size(); slot++) {
            ItemStack stack = stacks.get(slot);
            if (stack.isEmpty()) continue;
            Component name = stack.get(DataComponents.CUSTOM_NAME);
            String key = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            held.add(new HeldItem(slot, key, stack.getCount(), name == null ? Optional.empty() : Optional.of(name.getString()), data(key, stack, registries)));
        }
        return held;
    }

    /** The item's key and changed components in command syntax, which the item parser reads back; none for an unchanged item. */
    static Optional<String> data(String key, ItemStack stack, net.minecraft.core.HolderLookup.Provider registries) {
        net.minecraft.core.component.DataComponentPatch patch = stack.getComponentsPatch();
        if (patch.isEmpty()) return Optional.empty();
        Optional<net.minecraft.nbt.Tag> encoded = net.minecraft.core.component.DataComponentPatch.CODEC.encodeStart(registries.createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE), patch).result();
        if (encoded.isEmpty() || !(encoded.get() instanceof net.minecraft.nbt.CompoundTag components)) return Optional.empty();
        List<String> parts = new ArrayList<>();
        for (java.util.Map.Entry<String, net.minecraft.nbt.Tag> entry : components.entrySet())
            parts.add(entry.getKey().startsWith("!") ? entry.getKey() : entry.getKey() + "=" + entry.getValue());
        String text = key + "[" + String.join(",", parts) + "]";
        return text.length() <= MAXIMUM_ITEM_DATA ? Optional.of(text) : Optional.empty();
    }

    @Override public Optional<BlockHit> rayTrace(UUID player, double range) {
        Optional<ServerPlayer> online = living.players().owned(player);
        if (online.isEmpty()) return Optional.empty();
        net.minecraft.world.phys.HitResult hit = online.get().pick(range, 1.0f, false);
        if (hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK || !(hit instanceof net.minecraft.world.phys.BlockHitResult block)) return Optional.empty();
        net.minecraft.core.BlockPos pos = block.getBlockPos();
        net.minecraft.world.level.block.state.BlockState state = online.get().level().getBlockState(pos);
        if (state.isAir()) return Optional.empty();
        return Optional.of(new BlockHit(art.arcane.animus.core.behavior.SnapshotFacts.location(pos.getX(), pos.getY(), pos.getZ(), ModWorldKeys.key(online.get().level())),
                BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString()));
    }

    @Override public Optional<String> blockAt(String location) {
        String[] parts = location.split(",", -1);
        if (parts.length < 4) return Optional.empty();
        ServerLevel level = ModWorldKeys.resolve(server, parts[3].strip()).map((String key) -> ModWorldKeys.level(server, key)).orElse(null);
        if (level == null) return Optional.empty();
        try {
            net.minecraft.core.BlockPos pos = net.minecraft.core.BlockPos.containing(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]), Double.parseDouble(parts[2]));
            if (!level.isLoaded(pos)) return Optional.empty();
            return Optional.of(BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString());
        } catch (NumberFormatException unreadable) { return Optional.empty(); }
    }

    @Override public void explode(NpcHandle npc, Explosion explosion) {
        NpcRuntime.ActiveNpc active = requireVoice(npc);
        WorldPosition at = explosion.point().isPresent() ? new WorldPosition(active.state().position().world(), explosion.point().get()) : bodyCenter(active);
        String world = explosion.world().flatMap((String reference) -> ModWorldKeys.resolve(server, reference)).orElse(at.world());
        ServerLevel level = ModWorldKeys.level(server, world);
        if (level == null) throw PlatformRefusals.worldUnavailable();
        level.explode(null, at.point().x(), at.point().y(), at.point().z(), explosion.power(), explosion.fire(),
                explosion.breakBlocks() ? net.minecraft.world.level.Level.ExplosionInteraction.TNT : net.minecraft.world.level.Level.ExplosionInteraction.NONE);
    }

    @Override public void lookClose(NpcHandle npc, boolean enabled, java.util.OptionalDouble range, Optional<Boolean> lineOfSight) {
        NpcRuntime.ActiveNpc active = require(npc);
        PlatformRefusals.mapped(() -> { living.setLookClose(active.id(), enabled, range, lineOfSight); return true; });
    }
    @Override public boolean canFit(UUID player, ItemGift gift) {
        ItemStack template = template(gift);
        Optional<ServerPlayer> online = living.players().owned(player);
        if (online.isEmpty()) return false;
        Inventory inventory = online.get().getInventory();
        long room = 0;
        int limit = Math.max(1, template.getMaxStackSize());
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            ItemStack held = inventory.getItem(slot);
            if (held.isEmpty()) room += limit;
            else if (ItemStack.isSameItemSameComponents(held, template)) room += Math.max(0, limit - held.getCount());
            if (room >= gift.amount()) return true;
        }
        return false;
    }
    /** Main inventory and off hand only; worn armour is never taken. */
    @Override public int takeItem(UUID player, String item, int amount) {
        Item wanted = stack(item).getItem();
        Optional<ServerPlayer> online = living.players().owned(player);
        if (online.isEmpty()) return 0;
        Inventory inventory = online.get().getInventory();
        int taken = 0;
        for (int slot = 0; slot < inventory.getContainerSize() && taken < amount; slot++) {
            if (slot >= Inventory.INVENTORY_SIZE && slot != Inventory.SLOT_OFFHAND) continue;
            ItemStack held = inventory.getItem(slot);
            if (held.isEmpty() || !held.is(wanted)) continue;
            int take = Math.min(amount - taken, held.getCount());
            held.shrink(take);
            if (held.isEmpty()) inventory.setItem(slot, ItemStack.EMPTY);
            taken += take;
        }
        if (taken > 0) inventory.setChanged();
        return taken;
    }
    /** Items, levels, and money through the economy shops use: a Java module's, or none, which never affords money. */
    @Override public boolean affordable(UUID player, Cost cost) {
        Optional<ServerPlayer> online = living.players().owned(player);
        return online.isPresent() && Charges.affordable(payer(online.get()), cost);
    }
    @Override public boolean charge(UUID player, Cost cost) {
        Optional<ServerPlayer> online = living.players().owned(player);
        return online.isPresent() && Charges.charge(payer(online.get()), cost);
    }
    private Charges.Payer<ItemStack> payer(ServerPlayer player) {
        return new Charges.Payer<>(player.getUUID(), ModScreens.storage(player), ModScreens.VanillaItems.INSTANCE, this::stack, () -> player.experienceLevel,
                (int levels) -> player.giveExperienceLevels(-levels), economy.get());
    }
    private ItemStack stack(CostItem wanted) {
        List<String> errors = new ArrayList<>();
        ItemStack stack = VanillaRuntimeHost.stack(server, new art.arcane.animus.core.npc.NpcItem(wanted.item(), 1, wanted.components(), Optional.empty()), "$.items", errors);
        if (stack == null) throw new CapabilityRefusal(Outcome.Status.INVALID, "unknown-item", "Not an item on this server: " + wanted.item() + " " + String.join("; ", errors));
        return stack;
    }
    @Override public int giveBook(UUID player, Book book) {
        Optional<ServerPlayer> online = living.players().owned(player);
        if (online.isEmpty()) return 0;
        String title = RichText.plain(book.title());
        if (title.length() > WrittenBookContent.TITLE_MAX_LENGTH) title = title.substring(0, WrittenBookContent.TITLE_MAX_LENGTH);
        List<Filterable<Component>> pages = new ArrayList<>();
        for (String page : book.pages()) pages.add(Filterable.passThrough(formatted(page)));
        ItemStack stack = new ItemStack(Items.WRITTEN_BOOK);
        stack.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(Filterable.passThrough(title), book.author(), 0, pages, true));
        give(online.get(), stack);
        return 1;
    }

    private record Placement(WorldPosition at, float yaw, float pitch) {}
    /** Text formatted like dialogue, without a speaker prefix. */
    private static Component formatted(String raw) { return ModText.section(RichText.colorize(raw)); }
    private static Component itemText(String raw) { return ModText.component(raw, Style.EMPTY.withItalic(false)); }
    private static CapabilityRefusal invalid(String code, String message) { return new CapabilityRefusal(Outcome.Status.INVALID, code, message); }
    private String world(Point point, String current) {
        if (point.world().isEmpty()) return current;
        return ModWorldKeys.resolve(server, point.world().get())
                .orElseThrow(() -> new CapabilityRefusal(Outcome.Status.UNAVAILABLE, "world-unavailable", "World is not loaded: " + point.world().get()));
    }
    private static WorldPosition position(ServerPlayer player) {
        net.minecraft.world.phys.Vec3 at = player.position();
        return new WorldPosition(ModWorldKeys.key(player.level()), new Vec3(at.x(), at.y(), at.z()));
    }
    private Optional<WorldPosition> playerCenter(UUID player) {
        Optional<ServerPlayer> online = living.players().owned(player);
        if (online.isEmpty()) return Optional.empty();
        WorldPosition feet = position(online.get());
        return Optional.of(new WorldPosition(feet.world(), new Vec3(feet.point().x(), feet.point().y() + online.get().getBbHeight() / 2, feet.point().z())));
    }
    private Optional<WorldPosition> playerFeet(UUID player) { return living.players().owned(player).map(VanillaBehaviorPlatform::position); }
    /** The audience player in the same world, or every player within the radius of the point. */
    private int deliverAt(WorldPosition at, Audience audience, Consumer<ServerPlayer> action) {
        if (audience.player().isPresent()) {
            Optional<ServerPlayer> viewer = living.players().owned(audience.player().get());
            if (viewer.isEmpty() || !ModWorldKeys.key(viewer.get().level()).equals(at.world())) return 0;
            action.accept(viewer.get());
            return 1;
        }
        List<ServerPlayer> nearby = living.players().ownedNear(at, audience.radius(), MAXIMUM_AUDIENCE);
        for (ServerPlayer viewer : nearby) action.accept(viewer);
        return nearby.size();
    }
    /** Fills the inventory and drops what does not fit at the player's feet. */
    private static void give(ServerPlayer player, ItemStack stack) {
        player.getInventory().add(stack);
        if (stack.isEmpty()) return;
        ItemEntity dropped = new ItemEntity(player.level(), player.getX(), player.getY(), player.getZ(), stack);
        dropped.setNoPickUpDelay();
        player.level().addFreshEntity(dropped);
    }

    private NpcRuntime.ActiveNpc require(NpcHandle npc) { return living.active(npc.uuid()).orElseThrow(PlatformRefusals::inactive); }
    /** Dialogue, sounds and particles also come from a combat NPC that has just died, so its death routine can announce it. */
    private NpcRuntime.ActiveNpc requireVoice(NpcHandle npc) { return living.voice(npc.uuid()).orElseThrow(PlatformRefusals::inactive); }
    private static WorldPosition bodyCenter(NpcRuntime.ActiveNpc active) {
        WorldPosition position = active.state().position();
        double y = (active.state().bounds().min().y() + active.state().bounds().max().y()) / 2;
        return new WorldPosition(position.world(), new Vec3(position.point().x(), y, position.point().z()));
    }
    /** The trigger player, or nearby players, leaving out any viewer the speaker is hidden from. Returns how many were reached. */
    private int deliver(NpcRuntime.ActiveNpc speaker, Audience audience, WorldPosition at, boolean sameWorldOnly, Consumer<ServerPlayer> action) {
        HiddenNpcs hidden = living.hidden();
        if (audience.player().isPresent()) {
            if (hidden.hides(audience.player().get(), speaker.id(), speaker.state().npcId())) return 0;
            Optional<ServerPlayer> viewer = living.players().owned(audience.player().get());
            if (viewer.isEmpty()) return 0;
            if (sameWorldOnly && !ModWorldKeys.key(viewer.get().level()).equals(at.world())) return 0;
            action.accept(viewer.get());
            return 1;
        }
        List<ServerPlayer> nearby = living.players().ownedNear(at, audience.radius(), MAXIMUM_AUDIENCE);
        int reached = 0;
        for (ServerPlayer viewer : nearby) {
            if (hidden.hides(viewer.getUUID(), speaker.id(), speaker.state().npcId())) continue;
            action.accept(viewer);
            reached++;
        }
        return reached;
    }

    @Override public boolean openScreen(NpcHandle npc, UUID player, ScreenKind kind, String id) {
        art.arcane.animus.core.gui.HoloService<ServerPlayer, ItemStack> service = guis.get();
        if (service == null)
            throw new art.arcane.animus.core.extension.CapabilityRefusal(art.arcane.animus.api.Outcome.Status.UNAVAILABLE, "screens-unavailable", "GUIs are not running");
        art.arcane.animus.core.gui.GuiBinding.Kind target = art.arcane.animus.core.gui.HoloService.kind(kind);
        Optional<art.arcane.animus.core.extension.CapabilityRefusal> refused = service.refusal(target);
        if (refused.isPresent()) throw refused.get();
        Optional<UUID> owner = ServerNpc.is(npc) ? Optional.empty() : Optional.of(npc.uuid());
        return living.players().deliver(player, (ServerPlayer viewer) -> service.open(viewer, target, id, java.util.Map.of(), owner));
    }
}
