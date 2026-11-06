package art.arcane.animus.mod;

import art.arcane.animus.core.metrics.AnimusMetrics;
import art.arcane.animus.core.metrics.Subsystem;
import art.arcane.animus.core.behavior.builtin.DialogueFormat;
import art.arcane.animus.api.NpcHandle;
import art.arcane.animus.core.BuildIdentity;
import art.arcane.animus.core.behavior.builtin.BehaviorPlatform;
import art.arcane.animus.core.behavior.builtin.PlatformRefusals;
import art.arcane.animus.core.behavior.ServerNpc;
import art.arcane.animus.core.building.BuildService;
import art.arcane.animus.core.navigation.MovementProfile;
import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.recording.Playback;
import art.arcane.animus.core.runtime.NpcRuntime;
import art.arcane.animus.core.runtime.PersistedNpcService;
import art.arcane.animus.core.runtime.ViewerPreviews;
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

    VanillaBehaviorPlatform(MinecraftServer server, PersistedNpcService<ServerPlayer, ItemStack> living, ModCombat combat, BuildService<ServerPlayer, ItemStack> builds, ModTicks ticks,
                            java.util.function.Supplier<art.arcane.animus.core.gui.HoloService<ServerPlayer, ItemStack>> guis) {
        this.guis = guis;
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
        if (living.previews().hides(player, active)) return 0;
        Component message = ModText.component(DialogueFormat.speech(active.name(), prompt));
        Component buttons = ModText.parts(DialogueFormat.buttons(BuildIdentity.ID, token, List.copyOf(options)));
        return living.players().deliver(player, (ServerPlayer online) -> { online.sendSystemMessage(message); online.sendSystemMessage(buttons); }) ? 1 : 0;
    }
    /** Online players only; an offline player's name is not known here. */
    @Override public Optional<String> playerName(UUID player) {
        ServerPlayer online = server.getPlayerList().getPlayer(player);
        return online == null ? Optional.empty() : Optional.of(online.getGameProfile().name());
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
    @Override public void playRecording(NpcHandle npc, String recording, Playback playback) {
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
    private static Holder<SoundEvent> sound(String key) {
        Identifier id = Identifier.tryParse(key);
        if (id == null) throw PlatformRefusals.refusal(art.arcane.animus.api.Outcome.Status.INVALID, "unknown-sound", new IllegalArgumentException("Unknown sound: " + key));
        return BuiltInRegistries.SOUND_EVENT.get(id).<Holder<SoundEvent>>map((Holder.Reference<SoundEvent> reference) -> reference)
                .orElseGet(() -> Holder.direct(SoundEvent.createVariableRangeEvent(id)));
    }
    @Override public int spawnParticle(NpcHandle npc, Audience audience, ParticleEffect effect) {
        NpcRuntime.ActiveNpc active = requireVoice(npc);
        WorldPosition at = bodyCenter(active);
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
        };
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
        Optional<WorldPosition> at = playerCenter(player);
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
    @Override public int giveItem(UUID player, ItemGift gift) {
        ItemStack template = stack(gift.item());
        gift.name().ifPresent((String name) -> template.set(DataComponents.CUSTOM_NAME, itemText(name)));
        if (!gift.lore().isEmpty()) {
            List<Component> lines = new ArrayList<>();
            for (String line : gift.lore()) if (lines.size() < ItemLore.MAX_LINES) lines.add(itemText(line));
            template.set(DataComponents.LORE, new ItemLore(lines));
        }
        Optional<ServerPlayer> online = living.players().owned(player);
        if (online.isEmpty()) return 0;
        int limit = Math.max(1, template.getMaxStackSize());
        for (int remaining = gift.amount(); remaining > 0; remaining -= limit) give(online.get(), template.copyWithCount(Math.min(remaining, limit)));
        return gift.amount();
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
    /** The trigger player, or nearby players, leaving out any viewer whose editor preview hides the speaker. Returns how many were reached. */
    private int deliver(NpcRuntime.ActiveNpc speaker, Audience audience, WorldPosition at, boolean sameWorldOnly, Consumer<ServerPlayer> action) {
        ViewerPreviews previews = living.previews();
        if (audience.player().isPresent()) {
            if (previews.hides(audience.player().get(), speaker)) return 0;
            Optional<ServerPlayer> viewer = living.players().owned(audience.player().get());
            if (viewer.isEmpty()) return 0;
            if (sameWorldOnly && !ModWorldKeys.key(viewer.get().level()).equals(at.world())) return 0;
            action.accept(viewer.get());
            return 1;
        }
        List<ServerPlayer> nearby = living.players().ownedNear(at, audience.radius(), MAXIMUM_AUDIENCE);
        int reached = 0;
        for (ServerPlayer viewer : nearby) {
            if (previews.hides(viewer.getUUID(), speaker)) continue;
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
