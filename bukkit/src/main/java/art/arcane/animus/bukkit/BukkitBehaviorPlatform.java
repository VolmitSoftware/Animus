package art.arcane.animus.bukkit;

import art.arcane.animus.core.runtime.NpcRuntime;
import art.arcane.animus.api.NpcHandle;
import art.arcane.animus.api.Outcome;
import art.arcane.animus.core.BuildIdentity;
import art.arcane.animus.core.behavior.builtin.BehaviorPlatform;
import art.arcane.animus.core.behavior.builtin.PlatformRefusals;
import art.arcane.animus.core.building.BuildService;
import art.arcane.animus.core.extension.CapabilityRefusal;
import art.arcane.animus.core.navigation.MovementProfile;
import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.shop.ShopService;
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
    private static final int MAXIMUM_AUDIENCE = 256;
    private final Server server;
    private final NpcRuntime<Player, ItemStack> living;
    private final RuntimeCombatService combat;
    private final BuildService<Player, ItemStack> builds;
    private final Supplier<String> buildsUnavailable;
    private final Function<Runnable, AutoCloseable> ticks;
    private final Supplier<ShopService<Player, ItemStack>> shops;

    BukkitBehaviorPlatform(JavaPlugin plugin, NpcRuntime<Player, ItemStack> living, RuntimeCombatService combat, BuildService<Player, ItemStack> builds,
                           Supplier<String> buildsUnavailable, Function<Runnable, AutoCloseable> ticks, Supplier<ShopService<Player, ItemStack>> shops) {
        this.shops = shops;
        server = plugin.getServer();
        this.living = living;
        this.combat = combat;
        this.builds = builds;
        this.buildsUnavailable = buildsUnavailable;
        this.ticks = ticks;
    }


    @Override public Optional<String> unavailable() { return PlatformRefusals.unavailability(living::available, living::status); }
    @Override public AutoCloseable ownerTicks(Runnable tick) { return ticks.apply(tick); }
    @Override public boolean onNpcOwner(NpcHandle npc, Runnable work, Consumer<String> refused) { return living.onOwner(npc.uuid(), work, refused); }

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
        String line = active.name() + ": " + text;
        return deliver(audience, active.state().position(), false, viewer -> viewer.sendMessage(line));
    }

    @Override public int presentChoices(NpcHandle npc, UUID player, String prompt, String token, List<ChoiceOption> options) {
        NpcRuntime.ActiveNpc active = require(npc);
        List<ChoiceOption> copied = List.copyOf(options);
        return living.players().deliver(player, online -> {
            online.sendMessage(active.name() + ": " + prompt);
            online.spigot().sendMessage(BukkitChoiceMessages.options(BuildIdentity.ID, token, copied));
        }) ? 1 : 0;
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

    @Override public void look(NpcHandle npc, Vec3 target) {
        NpcRuntime.ActiveNpc active = require(npc);
        PlatformRefusals.mapped(() -> { living.lookAt(active.id(), new WorldPosition(active.state().position().world(), target)); return true; });
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

    @Override public void setEquipment(NpcHandle npc, EquipmentSlot slot, Optional<String> item) {
        NpcRuntime.ActiveNpc active = require(npc);
        ItemStack stack = new ItemStack(item.map(BukkitBehaviorPlatform::material).orElse(Material.AIR));
        PlatformRefusals.mapped(() -> { living.setEquipment(active.id(), slot, stack); return true; });
    }

    private static Material material(String key) {
        Material material = Material.matchMaterial(key);
        if (material == null || !material.isItem()) throw PlatformRefusals.unknownItem(key);
        return material;
    }

    @Override public int playSound(NpcHandle npc, Audience audience, SoundEffect sound) {
        Location at = bodyCenter(requireVoice(npc));
        return deliver(audience, position(at), true, viewer -> viewer.playSound(at, sound.sound(), sound.volume(), sound.pitch()));
    }

    @Override public int spawnParticle(NpcHandle npc, Audience audience, ParticleEffect effect) {
        Location at = bodyCenter(requireVoice(npc));
        Particle particle = particle(effect.particle());
        return deliver(audience, position(at), true, viewer -> viewer.spawnParticle(particle, at, effect.count(), effect.spread(), effect.spread(), effect.spread()));
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

    /** The trigger player wherever its owner is, or nearby players this owner may read. Returns how many were reached. */
    private int deliver(Audience audience, WorldPosition at, boolean sameWorldOnly, Consumer<Player> action) {
        if (audience.player().isPresent()) {
            // Known at once on the player's owner; a player another region owns counts once the work is handed over.
            AtomicBoolean skipped = new AtomicBoolean();
            boolean handed = living.players().deliver(audience.player().get(), viewer -> {
                if (!sameWorldOnly || viewer.getWorld().getUID().toString().equals(at.world())) action.accept(viewer);
                else skipped.set(true);
            });
            return handed && !skipped.get() ? 1 : 0;
        }
        List<Player> nearby = living.players().ownedNear(at, audience.radius(), MAXIMUM_AUDIENCE);
        for (Player viewer : nearby) action.accept(viewer);
        return nearby.size();
    }

    /** Hands the window to the player's owner, which on Folia may be another region. */
    @Override public boolean openScreen(NpcHandle npc, UUID player, ScreenKind kind, String id) {
        ShopService<Player, ItemStack> service = shops.get();
        if (service == null || !service.enabled())
            throw new CapabilityRefusal(Outcome.Status.UNAVAILABLE, "screens-unavailable", "Shops and chest GUIs are disabled in config.toml");
        return living.players().deliver(player, (Player target) -> {
            if (kind == ScreenKind.SHOP) service.openShop(target, id, Optional.of(npc.uuid()));
            else service.openGui(target, id, java.util.Map.of(), Optional.of(npc.uuid()));
        });
    }
}
