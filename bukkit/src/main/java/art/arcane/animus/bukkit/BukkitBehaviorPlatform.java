package art.arcane.animus.bukkit;

import art.arcane.animus.api.NpcHandle;
import art.arcane.animus.api.Outcome;
import art.arcane.animus.bukkit.runtime.PersistedNpcService;
import art.arcane.animus.core.BuildIdentity;
import art.arcane.animus.core.behavior.builtin.BehaviorPlatform;
import art.arcane.animus.core.extension.CapabilityRefusal;
import art.arcane.animus.core.navigation.MovementProfile;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;
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
import org.bukkit.scheduler.BukkitTask;

/** Bukkit owner-thread implementation. On Folia nothing here schedules or touches the world. */
final class BukkitBehaviorPlatform implements BehaviorPlatform {
    private static final String FOLIA_REASON = "the general NPC runtime is disabled on Folia";
    private static final int MAXIMUM_REASON = 512;
    private static final int MAXIMUM_AUDIENCE = 256;
    private final JavaPlugin plugin;
    private final Server server;
    private final PersistedNpcService living;
    private final RuntimeCombatService combat;
    private final boolean folia;

    BukkitBehaviorPlatform(JavaPlugin plugin, PersistedNpcService living, RuntimeCombatService combat, boolean folia) {
        this.plugin = plugin;
        server = plugin.getServer();
        this.living = living;
        this.combat = combat;
        this.folia = folia;
    }

    static Optional<String> unavailability(boolean folia, BooleanSupplier runtimeAvailable, Supplier<String> runtimeStatus) {
        if (folia) return Optional.of(FOLIA_REASON);
        return runtimeAvailable.getAsBoolean() ? Optional.empty() : Optional.of(runtimeStatus.get());
    }

    /** Runtime services report a rejected request through these two exception types; anything else is a real fault. */
    static <T> T mapped(Supplier<T> work) {
        try { return work.get(); }
        catch (IllegalArgumentException rejected) { throw refusal(Outcome.Status.INVALID, "platform-rejected", rejected); }
        catch (IllegalStateException rejected) { throw refusal(Outcome.Status.UNAVAILABLE, "platform-unavailable", rejected); }
    }
    private static CapabilityRefusal refusal(Outcome.Status status, String code, RuntimeException cause) {
        String message = cause.getMessage() == null || cause.getMessage().isBlank() ? cause.getClass().getSimpleName() : cause.getMessage();
        return new CapabilityRefusal(status, code, message.substring(0, Math.min(message.length(), MAXIMUM_REASON)));
    }

    @Override public Optional<String> unavailable() { return unavailability(folia, living::available, living::status); }

    @Override public AutoCloseable ownerTicks(Runnable tick) {
        BukkitTask task = server.getScheduler().runTaskTimer(plugin, tick, 1, 1);
        return task::cancel;
    }

    @Override public boolean hasPermission(UUID player, String node) {
        Player online = server.getPlayer(player);
        return online != null && online.hasPermission(node);
    }

    @Override public NpcStatus npcStatus(NpcHandle npc) {
        return living.active(npc.uuid()).map(active -> new NpcStatus(active.state().active(), active.state().health())).orElse(new NpcStatus(false, 0));
    }

    @Override public boolean playerInRange(NpcHandle npc, Optional<UUID> player, double radius) {
        Optional<PersistedNpcService.ActiveNpc> active = living.active(npc.uuid());
        if (active.isEmpty()) return false;
        Location at = location(active.get());
        if (player.isEmpty()) return !nearby(at, radius, 1).isEmpty();
        Player online = server.getPlayer(player.get());
        return online != null && within(online, at, radius);
    }

    @Override public int say(NpcHandle npc, Audience audience, String text) {
        PersistedNpcService.ActiveNpc active = require(npc);
        List<Player> viewers = viewers(audience, location(active), false);
        String line = active.name() + ": " + text;
        for (Player viewer : viewers) viewer.sendMessage(line);
        return viewers.size();
    }

    @Override public int presentChoices(NpcHandle npc, UUID player, String prompt, String token, List<ChoiceOption> options) {
        PersistedNpcService.ActiveNpc active = require(npc);
        Player online = server.getPlayer(player);
        if (online == null) return 0;
        online.sendMessage(active.name() + ": " + prompt);
        online.spigot().sendMessage(BukkitChoiceMessages.options(BuildIdentity.ID, token, options));
        return 1;
    }

    @Override public void runCommand(CommandSource source, Optional<UUID> player, String command) {
        boolean accepted;
        if (source == CommandSource.CONSOLE) accepted = server.dispatchCommand(server.getConsoleSender(), command);
        else {
            Player online = player.map(server::getPlayer).orElse(null);
            if (online == null) throw new CapabilityRefusal(Outcome.Status.UNAVAILABLE, "player-offline", "The triggering player is no longer online");
            accepted = online.performCommand(command);
        }
        if (!accepted) throw new CapabilityRefusal(Outcome.Status.INVALID, "command-rejected", "The server did not accept this command");
    }

    @Override public String navigate(NpcHandle npc, Vec3 destination, MovementProfile.Mode mode, double speed) {
        PersistedNpcService.ActiveNpc active = require(npc);
        WorldPosition goal = new WorldPosition(active.state().position().world(), destination);
        return mapped(() -> living.navigate(active.id(), goal, mode, speed).toString());
    }

    @Override public void followRoute(NpcHandle npc, String route) {
        PersistedNpcService.ActiveNpc active = require(npc);
        mapped(() -> { living.followRoute(active.id(), route); return true; });
    }

    @Override public CompletionStage<Void> routeFinished(NpcHandle npc) {
        PersistedNpcService.ActiveNpc active = require(npc);
        return mapped(() -> living.awaitRoute(active.id()));
    }

    @Override public String navigationStatus(NpcHandle npc) {
        return living.active(npc.uuid()).map(active -> living.navigationStatus(active.id())).orElse("inactive");
    }

    @Override public boolean stopNavigation(NpcHandle npc) {
        Optional<PersistedNpcService.ActiveNpc> active = living.active(npc.uuid());
        return active.isPresent() && mapped(() -> living.stopNavigation(active.get().id()));
    }

    @Override public void look(NpcHandle npc, Vec3 target) {
        PersistedNpcService.ActiveNpc active = require(npc);
        mapped(() -> { living.lookAt(active.id(), new WorldPosition(active.state().position().world(), target)); return true; });
    }

    @Override public void lookAtPlayer(NpcHandle npc, UUID player) {
        PersistedNpcService.ActiveNpc active = require(npc);
        Player online = server.getPlayer(player);
        if (online == null) throw new CapabilityRefusal(Outcome.Status.UNAVAILABLE, "player-offline", "The triggering player is no longer online");
        Location eye = online.getEyeLocation();
        WorldPosition target = new WorldPosition(online.getWorld().getUID().toString(), new Vec3(eye.getX(), eye.getY(), eye.getZ()));
        mapped(() -> { living.lookAt(active.id(), target); return true; });
    }

    @Override public void setAppearance(NpcHandle npc, AppearanceChange change) {
        PersistedNpcService.ActiveNpc active = require(npc);
        mapped(() -> { living.setAppearance(active.id(), change.name(), change.skin(), change.reset()); return true; });
    }

    @Override public void setEquipment(NpcHandle npc, EquipmentSlot slot, Optional<String> item) {
        PersistedNpcService.ActiveNpc active = require(npc);
        ItemStack stack = new ItemStack(item.map(BukkitBehaviorPlatform::material).orElse(Material.AIR));
        mapped(() -> { living.setEquipment(active.id(), slot, stack); return true; });
    }

    private static Material material(String key) {
        Material material = Material.matchMaterial(key);
        if (material == null || !material.isItem()) throw new CapabilityRefusal(Outcome.Status.INVALID, "unknown-item", "Unknown item: " + key);
        return material;
    }

    @Override public int playSound(NpcHandle npc, Audience audience, SoundEffect sound) {
        Location at = bodyCenter(require(npc));
        List<Player> viewers = viewers(audience, at, true);
        for (Player viewer : viewers) viewer.playSound(at, sound.sound(), sound.volume(), sound.pitch());
        return viewers.size();
    }

    @Override public int spawnParticle(NpcHandle npc, Audience audience, ParticleEffect effect) {
        Location at = bodyCenter(require(npc));
        Particle particle = particle(effect.particle());
        List<Player> viewers = viewers(audience, at, true);
        for (Player viewer : viewers) viewer.spawnParticle(particle, at, effect.count(), effect.spread(), effect.spread(), effect.spread());
        return viewers.size();
    }

    private static Particle particle(String key) {
        NamespacedKey name = NamespacedKey.fromString(key);
        Particle particle = name == null ? null : Registry.PARTICLE_TYPE.get(name);
        if (particle == null) throw new CapabilityRefusal(Outcome.Status.INVALID, "unknown-particle", "Unknown particle: " + key);
        if (particle.getDataType() != Void.class) throw new CapabilityRefusal(Outcome.Status.INVALID, "particle-needs-data", "This particle needs extra data, which behaviors cannot supply yet: " + key);
        return particle;
    }

    @Override public void combat(NpcHandle npc, CombatOperation operation, Optional<UUID> target) {
        PersistedNpcService.ActiveNpc active = require(npc);
        mapped(() -> combat.operate(active.state().npcId(), operation, target));
    }

    private PersistedNpcService.ActiveNpc require(NpcHandle npc) {
        return living.active(npc.uuid()).orElseThrow(() -> new CapabilityRefusal(Outcome.Status.UNAVAILABLE, "npc-inactive", "The NPC is not active"));
    }

    private Location location(PersistedNpcService.ActiveNpc active) {
        WorldPosition position = active.state().position();
        World world = server.getWorld(UUID.fromString(position.world()));
        if (world == null) throw new CapabilityRefusal(Outcome.Status.UNAVAILABLE, "world-unavailable", "The NPC's world is not loaded");
        return new Location(world, position.point().x(), position.point().y(), position.point().z());
    }

    private Location bodyCenter(PersistedNpcService.ActiveNpc active) {
        Location at = location(active);
        at.setY((active.state().bounds().min().y() + active.state().bounds().max().y()) / 2);
        return at;
    }

    private List<Player> viewers(Audience audience, Location at, boolean sameWorldOnly) {
        if (audience.player().isEmpty()) return nearby(at, audience.radius(), MAXIMUM_AUDIENCE);
        Player online = server.getPlayer(audience.player().get());
        if (online == null || sameWorldOnly && !online.getWorld().equals(at.getWorld())) return List.of();
        return List.of(online);
    }

    private static List<Player> nearby(Location at, double radius, int limit) {
        List<Player> result = new ArrayList<>();
        for (Player candidate : at.getWorld().getPlayers()) {
            if (result.size() >= limit) break;
            if (within(candidate, at, radius)) result.add(candidate);
        }
        return result;
    }

    private static boolean within(Player player, Location at, double radius) {
        return player.getWorld().equals(at.getWorld()) && player.getLocation().distanceSquared(at) <= radius * radius;
    }
}
