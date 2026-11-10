package art.arcane.animus.bukkit;

import art.arcane.animus.bukkit.gui.BukkitCharges;
import art.arcane.animus.core.inventory.Economy;
import art.arcane.animus.core.behavior.ServerNpc;
import art.arcane.animus.core.behavior.builtin.DialogueFormat;
import art.arcane.animus.core.runtime.PersistedNpcService;
import art.arcane.animus.core.text.Placeholders;
import art.arcane.animus.core.text.RichText;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;
import art.arcane.animus.core.runtime.NpcRuntime;
import art.arcane.animus.core.runtime.ViewerPreviews;
import art.arcane.animus.api.NpcHandle;
import art.arcane.animus.api.Outcome;
import art.arcane.animus.core.BuildIdentity;
import art.arcane.animus.core.behavior.builtin.BehaviorPlatform;
import art.arcane.animus.core.behavior.builtin.PlatformRefusals;
import art.arcane.animus.core.building.BuildService;
import art.arcane.animus.core.extension.CapabilityRefusal;
import art.arcane.animus.core.navigation.MovementProfile;
import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.recording.Playback;
import art.arcane.animus.core.gui.GuiBinding;
import art.arcane.animus.core.gui.HoloService;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Behavior world work. {@link #onNpcOwner} places each action on the NPC's owner: the main thread on Bukkit and Paper,
 * the NPC's region on Folia. Players another region owns are reached through their own scheduler, never read here.
 */
final class BukkitBehaviorPlatform implements BehaviorPlatform {
    private static final int MAXIMUM_AUDIENCE = 256, MAXIMUM_GIFT = 6400, MAXIMUM_TITLE_TICKS = 72000, MAXIMUM_BOOK_TITLE = 32, MAXIMUM_BOOK_PAGES = 100;
    private final Server server;
    private final NpcRuntime<Player, ItemStack> living;
    private final RuntimeCombatService combat;
    private final BuildService<Player, ItemStack> builds;
    private final Supplier<String> buildsUnavailable;
    private final Function<Runnable, AutoCloseable> ticks;
    private final Supplier<HoloService<Player, ItemStack>> guis;
    private final BukkitOwners owners;
    private final PersistedNpcService<Player, ItemStack> relocator;
    private final Placeholders placeholders;
    private final Supplier<Economy> economy;

    /** {@code relocator} is the main-thread runtime that can move NPCs; null on Folia. */
    BukkitBehaviorPlatform(JavaPlugin plugin, NpcRuntime<Player, ItemStack> living, RuntimeCombatService combat, BuildService<Player, ItemStack> builds,
                           Supplier<String> buildsUnavailable, Function<Runnable, AutoCloseable> ticks, Supplier<HoloService<Player, ItemStack>> guis,
                           BukkitOwners owners, PersistedNpcService<Player, ItemStack> relocator, Placeholders placeholders, Supplier<Economy> economy) {
        this.placeholders = placeholders;
        this.economy = economy;
        this.guis = guis;
        server = plugin.getServer();
        this.living = living;
        this.combat = combat;
        this.builds = builds;
        this.buildsUnavailable = buildsUnavailable;
        this.ticks = ticks;
        this.owners = owners;
        this.relocator = relocator;
    }


    @Override public Optional<String> unavailable() { return PlatformRefusals.unavailability(living::available, living::status); }
    @Override public AutoCloseable ownerTicks(Runnable tick) { return ticks.apply(tick); }
    @Override public boolean onNpcOwner(NpcHandle npc, Runnable work, Consumer<String> refused) { return living.onOwner(npc.uuid(), work, refused); }
    @Override public boolean onPlayerOwner(UUID player, Runnable work, Consumer<String> refused) { return owners.player(player, work, refused); }
    @Override public boolean onGlobalOwner(Runnable work, Consumer<String> refused) { return owners.global(work, refused); }

    /** An offline player holds nothing; one another region owns cannot be read from here. */
    @Override public boolean hasPermission(UUID player, String node) {
        Optional<Player> online = living.players().owned(player);
        if (online.isPresent()) return online.get().hasPermission(node);
        if (!living.players().online(player)) return false;
        throw elsewhere();
    }

    /** Published state: safe from the owner tick, a choice click on the player's region, or the NPC's owner. */
    @Override public NpcStatus npcStatus(NpcHandle npc) {
        return living.active(npc.uuid()).map(active -> new NpcStatus(active.state().active(), active.state().health())).orElse(new NpcStatus(false, 0));
    }

    @Override public boolean playerInRange(NpcHandle npc, Optional<UUID> player, double radius) {
        Optional<NpcRuntime.ActiveNpc> active = living.active(npc.uuid());
        if (active.isEmpty()) return false;
        WorldPosition at = active.get().state().position();
        if (player.isEmpty()) return !living.players().ownedNear(at, radius, 1).isEmpty();
        Optional<Player> online = living.players().owned(player.get());
        if (online.isEmpty()) return false;
        Location location = online.get().getLocation();
        return location.getWorld().getUID().toString().equals(at.world())
                && new Vec3(location.getX(), location.getY(), location.getZ()).distance(at.point()) <= radius;
    }

    @Override public int say(NpcHandle npc, Audience audience, String text) {
        NpcRuntime.ActiveNpc active = requireVoice(npc);
        String line = DialogueFormat.speech(active.name(), text);
        boolean personal = personal(active.name()) || personal(text);
        return deliver(living.players(), living.previews(), active, audience, active.state().position(), false,
                (Player viewer) -> viewer.sendMessage(personal ? DialogueFormat.speech(fill(viewer, active.name()), fill(viewer, text)) : line));
    }

    @Override public int presentChoices(NpcHandle npc, UUID player, String prompt, String token, List<ChoiceOption> options) {
        NpcRuntime.ActiveNpc active = require(npc);
        if (living.previews().hides(player, active)) return 0;
        return living.players().deliver(player, online -> {
            List<ChoiceOption> shown = options.stream().map((ChoiceOption option) -> new ChoiceOption(option.id(), fill(online, option.text()), option.colour(),
                    option.hover().map((String hover) -> fill(online, hover)), option.style())).toList();
            online.sendMessage(DialogueFormat.speech(fill(online, active.name()), fill(online, prompt)));
            online.spigot().sendMessage(BukkitChoiceMessages.components(DialogueFormat.buttons(BuildIdentity.ID, token, shown)));
        }) ? 1 : 0;
    }

    /** Whether the text holds a placeholder worth filling for each recipient. */
    private boolean personal(String text) { return placeholders.active() && Placeholders.marked(text); }
    /** On the viewer's owner: the text with the viewer's placeholders filled. */
    private String fill(Player viewer, String text) { return placeholders.fill(viewer.getUniqueId(), text); }

    /** Online players by their current name; others by the server's cached profile. */
    @Override public Optional<String> playerName(UUID player) {
        Player online = server.getPlayer(player);
        if (online != null) return Optional.of(online.getName());
        return Optional.ofNullable(server.getOfflinePlayer(player).getName());
    }

    /** Folia: a command for a player another region owns, or for the console, is handed to that owner and not awaited. */
    @Override public void runCommand(CommandSource source, Optional<UUID> player, String command) {
        if (source == CommandSource.CONSOLE) {
            AtomicBoolean ran = new AtomicBoolean(), accepted = new AtomicBoolean();
            if (!living.players().console(() -> { accepted.set(server.dispatchCommand(server.getConsoleSender(), command)); ran.set(true); }))
                throw new CapabilityRefusal(Outcome.Status.UNAVAILABLE, "console-unavailable", "The console cannot accept commands now");
            if (ran.get() && !accepted.get()) throw PlatformRefusals.rejectedCommand();
            return;
        }
        UUID target = player.filter(living.players()::online).orElseThrow(PlatformRefusals::offline);
        Optional<Player> owned = living.players().owned(target);
        if (owned.isPresent()) {
            if (!owned.get().performCommand(command)) throw PlatformRefusals.rejectedCommand();
            return;
        }
        if (!living.players().deliver(target, online -> online.performCommand(command))) throw PlatformRefusals.offline();
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

    /** Published state, so the owner tick may read it on any server. */
    @Override public String navigationStatus(NpcHandle npc) {
        return living.active(npc.uuid()).map(active -> living.navigationStatus(active.id())).orElse("inactive");
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
        if (!living.players().online(player)) throw PlatformRefusals.offline();
        Player online = living.players().owned(player).orElseThrow(BukkitBehaviorPlatform::elsewhere);
        Location eye = online.getEyeLocation();
        WorldPosition target = new WorldPosition(online.getWorld().getUID().toString(), new Vec3(eye.getX(), eye.getY(), eye.getZ()));
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
        ItemStack stack = new ItemStack(item.map(BukkitBehaviorPlatform::material).orElse(Material.AIR));
        PlatformRefusals.mapped(() -> { living.setEquipment(active.id(), slot, stack); return true; });
    }

    @Override public void setAttribute(NpcHandle npc, String attribute, java.util.OptionalDouble value) {
        NpcRuntime.ActiveNpc active = require(npc);
        PlatformRefusals.mapped(() -> { living.setAttribute(active.id(), attribute, value); return true; });
    }

    private static Material material(String key) {
        Material material = Material.matchMaterial(key);
        if (material == null || !material.isItem()) throw PlatformRefusals.unknownItem(key);
        return material;
    }

    @Override public int playSound(NpcHandle npc, Audience audience, SoundEffect sound) {
        NpcRuntime.ActiveNpc active = requireVoice(npc);
        Location at = bodyCenter(active);
        return deliver(living.players(), living.previews(), active, audience, position(at), true, (Player viewer) -> viewer.playSound(at, sound.sound(), sound.volume(), sound.pitch()));
    }

    @Override public int spawnParticle(NpcHandle npc, Audience audience, ParticleEffect effect) {
        NpcRuntime.ActiveNpc active = requireVoice(npc);
        Location at = bodyCenter(active);
        Particle particle = particle(effect.particle());
        return deliver(living.players(), living.previews(), active, audience, position(at), true,
                (Player viewer) -> viewer.spawnParticle(particle, at, effect.count(), effect.spread(), effect.spread(), effect.spread()));
    }

    private static Particle particle(String key) {
        NamespacedKey name = NamespacedKey.fromString(key);
        Particle particle = name == null ? null : Registry.PARTICLE_TYPE.get(name);
        if (particle == null) throw PlatformRefusals.unknownParticle(key);
        if (particle.getDataType() != Void.class) throw PlatformRefusals.particleNeedsData(key);
        return particle;
    }

    @Override public void combat(NpcHandle npc, CombatOperation operation, Optional<UUID> target) {
        NpcRuntime.ActiveNpc active = require(npc);
        PlatformRefusals.mapped(() -> combat.operate(active.state().npcId(), operation, target));
    }

    @Override public String build(NpcHandle npc, BuildOperation operation, Optional<String> schematic, Optional<Vec3> origin, Map<String, String> options) {
        NpcRuntime.ActiveNpc active = require(npc);
        String reason = buildsUnavailable.get();
        if (builds == null || reason != null) throw new CapabilityRefusal(Outcome.Status.UNAVAILABLE, "build-unavailable", "Builds " + (reason == null ? "are unavailable" : reason));
        return builds.behavior(active.id(), operation, schematic, origin, options);
    }

    private NpcRuntime.ActiveNpc require(NpcHandle npc) {
        return living.active(npc.uuid()).orElseThrow(PlatformRefusals::inactive);
    }
    /** Dialogue, sounds and particles also come from a combat NPC that has just died, so its death routine can announce it. */
    private NpcRuntime.ActiveNpc requireVoice(NpcHandle npc) {
        return living.voice(npc.uuid()).orElseThrow(PlatformRefusals::inactive);
    }
    private static CapabilityRefusal elsewhere() {
        return new CapabilityRefusal(Outcome.Status.UNAVAILABLE, "player-in-other-region", "The triggering player is owned by another region than the NPC");
    }

    private Location location(NpcRuntime.ActiveNpc active) {
        WorldPosition position = active.state().position();
        World world = server.getWorld(UUID.fromString(position.world()));
        if (world == null) throw PlatformRefusals.worldUnavailable();
        return new Location(world, position.point().x(), position.point().y(), position.point().z());
    }

    private Location bodyCenter(NpcRuntime.ActiveNpc active) {
        Location at = location(active);
        at.setY((active.state().bounds().min().y() + active.state().bounds().max().y()) / 2);
        return at;
    }
    private static WorldPosition position(Location at) {
        return new WorldPosition(at.getWorld().getUID().toString(), new Vec3(at.getX(), at.getY(), at.getZ()));
    }

    /**
     * The trigger player wherever its owner is, or nearby players this owner may read, leaving out any viewer whose editor
     * preview hides the speaking NPC. Returns how many were reached.
     */
    static int deliver(NpcRuntime.PlayerAccess<Player> players, ViewerPreviews previews, NpcRuntime.ActiveNpc speaker, Audience audience,
                       WorldPosition at, boolean sameWorldOnly, Consumer<Player> action) {
        if (audience.player().isPresent()) {
            UUID target = audience.player().get();
            if (previews.hides(target, speaker)) return 0;
            // Known at once on the player's owner; a player another region owns counts once the work is handed over.
            AtomicBoolean skipped = new AtomicBoolean();
            boolean handed = players.deliver(target, (Player viewer) -> {
                if (!sameWorldOnly || viewer.getWorld().getUID().toString().equals(at.world())) action.accept(viewer);
                else skipped.set(true);
            });
            return handed && !skipped.get() ? 1 : 0;
        }
        List<Player> nearby = players.ownedNear(at, audience.radius(), MAXIMUM_AUDIENCE);
        int reached = 0;
        for (Player viewer : nearby) {
            if (previews.hides(viewer.getUniqueId(), speaker)) continue;
            action.accept(viewer);
            reached++;
        }
        return reached;
    }

    /** Hands the window or menu to the player's owner, which on Folia may be another region. */
    @Override public boolean openScreen(NpcHandle npc, UUID player, ScreenKind kind, String id) {
        HoloService<Player, ItemStack> service = guis.get();
        if (service == null) throw new CapabilityRefusal(Outcome.Status.UNAVAILABLE, "screens-unavailable", "GUIs are not running");
        GuiBinding.Kind target = HoloService.kind(kind);
        java.util.Optional<CapabilityRefusal> refused = service.refusal(target);
        if (refused.isPresent()) throw refused.get();
        Optional<UUID> opener = ServerNpc.is(npc) ? Optional.empty() : Optional.of(npc.uuid());
        return living.players().deliver(player, (Player viewer) -> service.open(viewer, target, id, java.util.Map.of(), opener));
    }

    /** Chat and console text is thread-safe to send; WORLD reads the NPC's world on its owner, or the player's in a server run. */
    @Override public int message(NpcHandle npc, Optional<UUID> player, MessageTarget to, String text) {
        String line = RichText.colorize(text);
        boolean personal = personal(text);
        switch (to) {
            case CONSOLE -> { server.getConsoleSender().sendMessage(line); return 1; }
            case ALL -> {
                int reached = 0;
                for (Player online : server.getOnlinePlayers()) { if (send(online, text, line, personal)) reached++; }
                return reached;
            }
            case PLAYER -> {
                Optional<Player> online = player.isEmpty() ? Optional.empty() : here(player.get());
                online.ifPresent((Player target) -> target.sendMessage(personal ? RichText.colorize(fill(target, text)) : line));
                return online.isPresent() ? 1 : 0;
            }
            case WORLD -> {
                World world;
                if (!ServerNpc.is(npc)) world = location(requireVoice(npc)).getWorld();
                else {
                    Optional<Player> online = player.isEmpty() ? Optional.empty() : here(player.get());
                    if (online.isEmpty()) return 0;
                    world = online.get().getWorld();
                }
                int reached = 0;
                for (Player viewer : world.getPlayers()) { if (send(viewer, text, line, personal)) reached++; }
                return reached;
            }
            default -> throw new IllegalArgumentException("Unknown message target " + to);
        }
    }

    /**
     * One broadcast line. Text with a placeholder is filled on the recipient's own owner, since PlaceholderAPI may read
     * the player's state; on Folia that is the player's region, which may not be this thread.
     */
    @Override public int messageNearby(NpcHandle npc, double radius, String text) {
        NpcRuntime.ActiveNpc active = requireVoice(npc);
        String line = RichText.colorize(text);
        boolean personal = personal(text);
        return deliver(living.players(), living.previews(), active, new Audience(Optional.empty(), radius), active.state().position(), false,
                (Player viewer) -> viewer.sendMessage(personal ? RichText.colorize(fill(viewer, text)) : line));
    }

    private boolean send(Player recipient, String text, String line, boolean personal) {
        if (!personal) { recipient.sendMessage(line); return true; }
        return living.players().deliver(recipient.getUniqueId(), (Player owned) -> owned.sendMessage(RichText.colorize(fill(owned, text))));
    }

    @Override public int title(UUID player, Title title) {
        Optional<Player> online = here(player);
        online.ifPresent((Player target) -> target.sendTitle(title.title().map((String text) -> RichText.colorize(fill(target, text))).orElse(""),
                title.subtitle().map((String text) -> RichText.colorize(fill(target, text))).orElse(""),
                ticks(title.fadeIn()), ticks(title.stay()), ticks(title.fadeOut())));
        return online.isPresent() ? 1 : 0;
    }

    @Override public int actionbar(UUID player, String text) {
        Optional<Player> online = here(player);
        online.ifPresent((Player target) -> target.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(RichText.colorize(fill(target, text)))));
        return online.isPresent() ? 1 : 0;
    }

    @Override public int playSoundAtPlayer(UUID player, Audience audience, SoundEffect sound) {
        Optional<Player> online = here(player);
        if (online.isEmpty()) return 0;
        Location at = online.get().getLocation();
        return atPlayer(online.get(), audience, at, (Player viewer) -> viewer.playSound(at, sound.sound(), sound.volume(), sound.pitch()));
    }

    @Override public int spawnParticleAtPlayer(UUID player, Audience audience, ParticleEffect effect) {
        Particle particle = particle(effect.particle());
        Optional<Player> online = here(player);
        if (online.isEmpty()) return 0;
        Location at = online.get().getLocation().add(0, 1, 0);
        return atPlayer(online.get(), audience, at,
                (Player viewer) -> viewer.spawnParticle(particle, at, effect.count(), effect.spread(), effect.spread(), effect.spread()));
    }

    /** The addressed viewer wherever its owner is, or the players near the source player this owner may read. */
    private int atPlayer(Player source, Audience audience, Location at, Consumer<Player> action) {
        String world = at.getWorld().getUID().toString();
        if (audience.player().isPresent()) {
            UUID target = audience.player().get();
            if (target.equals(source.getUniqueId())) { action.accept(source); return 1; }
            AtomicBoolean skipped = new AtomicBoolean();
            boolean handed = living.players().deliver(target, (Player viewer) -> {
                if (viewer.getWorld().getUID().toString().equals(world)) action.accept(viewer);
                else skipped.set(true);
            });
            return handed && !skipped.get() ? 1 : 0;
        }
        List<Player> nearby = living.players().ownedNear(position(at), audience.radius(), MAXIMUM_AUDIENCE);
        for (Player viewer : nearby) action.accept(viewer);
        return nearby.size();
    }

    /** On the player's owner. Folia teleports asynchronously, across worlds too. */
    @Override public boolean teleportPlayer(UUID player, NpcHandle npc, Destination to) {
        Optional<Player> online = here(player);
        if (online.isEmpty()) return false;
        Player target = online.get();
        Location from = target.getLocation();
        Location destination = switch (to) {
            case Point point -> new Location(point.world().map(this::world).orElse(from.getWorld()), point.position().x(), point.position().y(), point.position().z(),
                    (float) point.yaw().orElse(from.getYaw()), (float) point.pitch().orElse(from.getPitch()));
            case ToNpc ignored -> {
                if (ServerNpc.is(npc)) throw noNpc();
                Location at = location(require(npc));
                at.setYaw(from.getYaw()); at.setPitch(from.getPitch());
                yield at;
            }
            case ToPlayer other -> {
                if (!living.players().online(other.player())) throw destinationOffline();
                Location at = living.players().owned(other.player()).orElseThrow(() -> new CapabilityRefusal(Outcome.Status.UNAVAILABLE, "destination-in-other-region",
                        "The destination player is owned by another region; teleport to a point instead")).getLocation();
                at.setYaw(from.getYaw()); at.setPitch(from.getPitch());
                yield at;
            }
        };
        if (!owners.teleport(target, destination)) throw new CapabilityRefusal(Outcome.Status.UNAVAILABLE, "teleport-refused", "The server refused the teleport");
        return true;
    }

    /** On the NPC's owner, through the main-thread runtime's relocation onto clear, supported, loaded ground. */
    @Override public void teleportNpc(NpcHandle npc, Destination to) {
        if (ServerNpc.is(npc)) throw noNpc();
        if (to instanceof ToNpc) throw new CapabilityRefusal(Outcome.Status.INVALID, "teleport-to-self", "An NPC cannot be teleported to itself");
        if (relocator == null) throw new CapabilityRefusal(Outcome.Status.UNAVAILABLE, "npc-teleport-unavailable",
                "Teleporting NPCs is not available on Folia: moving an NPC between regions needs an ownership handoff that does not exist yet");
        NpcRuntime.ActiveNpc active = require(npc);
        WorldPosition current = active.state().position();
        WorldPosition destination;
        java.util.OptionalDouble yaw = java.util.OptionalDouble.empty(), pitch = java.util.OptionalDouble.empty();
        switch (to) {
            case Point point -> {
                String world = point.world().map((String name) -> world(name).getUID().toString()).orElse(current.world());
                destination = new WorldPosition(world, point.position());
                yaw = point.yaw(); pitch = point.pitch();
            }
            case ToPlayer other -> {
                if (!living.players().online(other.player())) throw destinationOffline();
                Location at = living.players().owned(other.player()).orElseThrow(BukkitBehaviorPlatform::elsewhere).getLocation();
                destination = position(at);
            }
            default -> throw new IllegalArgumentException("Unknown destination " + to);
        }
        if (!relocator.relocate(active.id(), destination)) throw new CapabilityRefusal(Outcome.Status.UNAVAILABLE, "teleport-blocked",
                "The destination is not clear, supported, loaded ground, or the NPC is riding or carrying a rider");
        if (yaw.isPresent() || pitch.isPresent()) {
            double turn = Math.toRadians(yaw.orElse(0)), tilt = Math.toRadians(pitch.orElse(0));
            Vec3 facing = new Vec3(-Math.sin(turn) * Math.cos(tilt), -Math.sin(tilt), Math.cos(turn) * Math.cos(tilt));
            WorldPosition target = new WorldPosition(destination.world(), destination.point().add(facing));
            PlatformRefusals.mapped(() -> { living.lookAt(active.id(), target); return true; });
        }
    }

    @Override public int giveItem(UUID player, ItemGift gift) {
        Material material = material(gift.item());
        Optional<Player> online = here(player);
        if (online.isEmpty()) return 0;
        ItemStack template = new ItemStack(material);
        if (gift.name().isPresent() || !gift.lore().isEmpty()) {
            ItemMeta meta = template.getItemMeta();
            if (meta != null) {
                gift.name().ifPresent((String name) -> meta.setDisplayName(RichText.colorize(name)));
                if (!gift.lore().isEmpty()) meta.setLore(gift.lore().stream().map(RichText::colorize).toList());
                template.setItemMeta(meta);
            }
        }
        int amount = Math.clamp(gift.amount(), 1, MAXIMUM_GIFT);
        for (int count : stacks(amount, template.getMaxStackSize())) {
            ItemStack stack = template.clone();
            stack.setAmount(count);
            hand(online.get(), stack);
        }
        return amount;
    }

    @Override public int takeItem(UUID player, String item, int amount) {
        Material material = material(item);
        Optional<Player> online = here(player);
        if (online.isEmpty() || amount <= 0) return 0;
        ItemStack[] slots = online.get().getInventory().getStorageContents();
        int[] counts = new int[slots.length];
        for (int slot = 0; slot < slots.length; slot++) counts[slot] = slots[slot] != null && slots[slot].getType() == material ? slots[slot].getAmount() : 0;
        int taken = take(counts, amount);
        if (taken == 0) return 0;
        for (int slot = 0; slot < slots.length; slot++) {
            if (slots[slot] == null || slots[slot].getType() != material || counts[slot] == slots[slot].getAmount()) continue;
            if (counts[slot] == 0) slots[slot] = null;
            else slots[slot].setAmount(counts[slot]);
        }
        online.get().getInventory().setStorageContents(slots);
        return taken;
    }

    @Override public boolean affordable(UUID player, Cost cost) {
        Optional<Player> online = here(player);
        return online.isPresent() && BukkitCharges.affordable(online.get(), cost, economy.get());
    }

    @Override public boolean charge(UUID player, Cost cost) {
        Optional<Player> online = here(player);
        return online.isPresent() && BukkitCharges.charge(online.get(), cost, economy.get());
    }

    @Override public int giveBook(UUID player, Book book) {
        Optional<Player> online = here(player);
        if (online.isEmpty()) return 0;
        ItemStack stack = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) stack.getItemMeta();
        if (meta != null) {
            meta.setTitle(bounded(RichText.colorize(book.title()), MAXIMUM_BOOK_TITLE));
            meta.setAuthor(RichText.colorize(book.author()));
            meta.setPages(book.pages().stream().limit(MAXIMUM_BOOK_PAGES).map(RichText::colorize).toList());
            stack.setItemMeta(meta);
        }
        hand(online.get(), stack);
        return 1;
    }

    /** Into the inventory, with whatever does not fit dropped at the player's feet. */
    private static void hand(Player player, ItemStack stack) {
        for (ItemStack rest : player.getInventory().addItem(stack).values()) player.getWorld().dropItem(player.getLocation(), rest);
    }

    /** {@code amount} split into stacks of at most {@code maximum}. */
    static int[] stacks(int amount, int maximum) {
        int size = Math.max(1, maximum), count = Math.max(0, amount);
        int[] stacks = new int[(count + size - 1) / size];
        for (int index = 0; index < stacks.length; index++) stacks[index] = Math.min(size, count - index * size);
        return stacks;
    }

    /** Takes up to {@code amount} from the slot counts in order, lowering them in place. Returns the amount taken. */
    static int take(int[] counts, int amount) {
        int remaining = Math.max(0, amount);
        for (int slot = 0; slot < counts.length && remaining > 0; slot++) {
            int removed = Math.min(Math.max(0, counts[slot]), remaining);
            counts[slot] -= removed;
            remaining -= removed;
        }
        return Math.max(0, amount) - remaining;
    }

    /** Title fades and stay, held to 0..72000 ticks. */
    static int ticks(int ticks) { return Math.clamp(ticks, 0, MAXIMUM_TITLE_TICKS); }

    private static String bounded(String text, int maximum) { return text.length() <= maximum ? text : text.substring(0, maximum); }

    /** The player on this owner; empty when offline. A player another owner holds is a routing fault. */
    private Optional<Player> here(UUID player) {
        Optional<Player> online = living.players().owned(player);
        if (online.isPresent() || !living.players().online(player)) return online;
        throw elsewhere();
    }

    /** A world by name, UUID or dimension key. */
    private World world(String name) {
        World world = BukkitWorldLookup.find(server, name);
        if (world == null) throw new CapabilityRefusal(Outcome.Status.INVALID, "unknown-world", "Unknown or unloaded world: " + name);
        return world;
    }

    private static CapabilityRefusal noNpc() {
        return new CapabilityRefusal(Outcome.Status.INVALID, "no-npc", "A server behavior has no NPC for this capability");
    }
    private static CapabilityRefusal destinationOffline() {
        return new CapabilityRefusal(Outcome.Status.UNAVAILABLE, "destination-offline", "The destination player is not online");
    }
}
