package art.arcane.animus.bukkit.combat;

import art.arcane.animus.core.virtual.Hitbox;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.VirtualNpcState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.AnimalTamer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

/**
 * Lets real mobs fight NPCs whose combat sets {@code targetableByMobs}, with their own vanilla AI. A drawn NPC gets a
 * decoy: a mannequin no player is ever sent, with no gravity, no collision and no sound, kept on the NPC's body. A mob
 * that would fight a player standing there targets the decoy, so it paths, swings, shoots, swells or leaps as it would
 * at a player. Every hit on the decoy is cancelled by the caller and dealt to the NPC instead.
 * <p>
 * Which mobs: monsters that hunt players (zombies, skeletons, creepers, spiders in the dark, illagers, witches and the
 * rest of {@link #HUNTERS}); wolves against a skeleton body, or against an NPC their owner fought; iron and snow golems
 * against a monster body. This class keeps the decoys of Paper and Spigot on the main thread; on Folia each NPC's owner
 * keeps its own decoy with the same rules. Owner thread only.
 */
public final class NpcDecoys implements AutoCloseable {
    /** What the decoys need from combat. */
    public interface Host {
        /** The NPC's state while it is active and lets mobs target it; empty otherwise. */
        Optional<VirtualNpcState.Snapshot> targetable(UUID npc);
        /** The NPC's body type, such as {@code minecraft:zombie}. */
        String body(UUID npc);
        /** Whether protection lets this mob attack the NPC where both stand. */
        boolean allows(Mob mob, VirtualNpcState.Snapshot npc);
        /** Whether this player and the NPC fought each other lately, which turns the player's wolves on the NPC. */
        boolean fought(UUID npc, UUID player);
        /** The real entities that move with the NPC: the mob it rides and the players on it. */
        Set<UUID> attached(UUID npc);
    }

    /** Monsters that hunt any player in reach, as their target goals do. */
    static final Set<String> HUNTERS = Set.of("minecraft:zombie", "minecraft:husk", "minecraft:drowned", "minecraft:zombie_villager", "minecraft:skeleton",
            "minecraft:stray", "minecraft:bogged", "minecraft:parched", "minecraft:wither_skeleton", "minecraft:creeper", "minecraft:spider", "minecraft:cave_spider",
            "minecraft:pillager", "minecraft:vindicator", "minecraft:evoker", "minecraft:illusioner", "minecraft:witch", "minecraft:ravager", "minecraft:vex",
            "minecraft:blaze", "minecraft:endermite", "minecraft:silverfish", "minecraft:slime", "minecraft:magma_cube");
    private static final Set<String> SKELETONS = Set.of("minecraft:skeleton", "minecraft:stray", "minecraft:bogged", "minecraft:parched", "minecraft:wither_skeleton");
    /** Bodies of monsters, which iron and snow golems attack; creepers excepted, as golems leave them alone. */
    static final Set<String> MONSTERS;
    static {
        java.util.HashSet<String> monsters = new java.util.HashSet<>(HUNTERS);
        monsters.addAll(Set.of("minecraft:enderman", "minecraft:zombified_piglin", "minecraft:piglin", "minecraft:piglin_brute", "minecraft:hoglin", "minecraft:zoglin",
                "minecraft:warden", "minecraft:guardian", "minecraft:elder_guardian", "minecraft:shulker", "minecraft:phantom", "minecraft:breeze", "minecraft:ghast", "minecraft:wither"));
        monsters.remove("minecraft:creeper");
        MONSTERS = Set.copyOf(monsters);
    }
    /** How far a draw looks for mobs, and how many mobs one NPC draws at most. */
    static final double RANGE = 16;
    static final int PER_NPC = 6;
    /** A spider fights only below this light, as its target goal does. */
    static final int SPIDER_LIGHT = 12;
    private static final double FOLLOW = .05;
    private static final double MANNEQUIN_HEIGHT = 1.8;

    private record Decoy(UUID npc, Mannequin body) {}

    private final Plugin plugin;
    private final Host host;
    /** The persistent data key every decoy carries, under this plugin's namespace. */
    public static final String MARK = "npc_decoy";
    private final NamespacedKey key;
    private final Map<UUID, Decoy> byNpc = new HashMap<>();
    private final Map<UUID, UUID> byEntity = new HashMap<>();

    public NpcDecoys(Plugin plugin, Host host) {
        this.plugin = Objects.requireNonNull(plugin);
        this.host = Objects.requireNonNull(host);
        key = new NamespacedKey(plugin, MARK);
    }

    public int size() { return byNpc.size(); }
    /**
     * Marks a projectile that strikes an NPC; false when it already carried the mark, so a decoy it touches again after it
     * lands, as a trident bounces or a potion drops, does not count it twice. On the projectile's owner.
     */
    public static boolean firstStrike(Projectile projectile, NamespacedKey struck) {
        org.bukkit.persistence.PersistentDataContainer data = projectile.getPersistentDataContainer();
        if (data.has(struck, PersistentDataType.BYTE)) return false;
        data.set(struck, PersistentDataType.BYTE, (byte) 1);
        return true;
    }
    /**
     * The NPC a mob's projectile was meant for when it strikes a player who moves with that NPC: the mob targets the NPC's
     * decoy, and a seated or riding player's server body stands inside it, at the NPC's feet.
     */
    public static Optional<UUID> meantFor(Projectile projectile, Entity struck, Function<Entity, Optional<UUID>> decoyNpc, Function<UUID, Set<UUID>> attached) {
        if (!(struck instanceof Player) || !(projectile.getShooter() instanceof Mob mob)) return Optional.empty();
        return decoyNpc.apply(mob.getTarget()).filter((UUID npc) -> attached.apply(npc).contains(struck.getUniqueId()));
    }
    /** The NPC this entity stands in for, if it is a current decoy. */
    public Optional<UUID> npc(Entity entity) { return entity == null ? Optional.empty() : Optional.ofNullable(byEntity.get(entity.getUniqueId())); }
    /** Whether this entity is a decoy, current or left over from before a reload. */
    public boolean decoy(Entity entity) { return entity != null && (byEntity.containsKey(entity.getUniqueId()) || entity.getPersistentDataContainer().has(key, PersistentDataType.BYTE)); }

    /**
     * One draw pass over these targetable NPCs: each gets a decoy when a mob nearby would fight it, and up to
     * {@link #PER_NPC} such mobs that have nothing else to fight target the decoy.
     */
    public void draw(List<UUID> npcs) {
        for (UUID npc : npcs) {
            Optional<VirtualNpcState.Snapshot> found = host.targetable(npc);
            if (found.isEmpty()) { forget(npc); continue; }
            VirtualNpcState.Snapshot self = found.get();
            World world = world(self);
            Vec3 at = self.position().point();
            if (world == null || !world.isChunkLoaded(((int) Math.floor(at.x())) >> 4, ((int) Math.floor(at.z())) >> 4)) continue;
            String body = host.body(npc);
            Decoy current = byNpc.get(npc);
            Scan scan = scan(world, self, current == null ? null : current.body(), (Mob mob) -> fights(mob, npc, body) && host.allows(mob, self));
            // A decoy no mob targets any more is taken away until a mob would fight the NPC again.
            if (scan.candidates().isEmpty() && scan.drawn() == 0 && current != null) { forget(npc); continue; }
            if (scan.candidates().isEmpty() || scan.drawn() >= PER_NPC) continue;
            Decoy decoy = decoy(npc, self, world);
            if (decoy == null) continue;
            int drawn = scan.drawn();
            for (Mob mob : scan.candidates()) {
                if (drawn >= PER_NPC) break;
                mob.setTarget(decoy.body());
                if (mob.getTarget() == decoy.body()) drawn++;
            }
        }
    }

    /** Keeps every decoy on its NPC's body; a decoy whose NPC is gone or no longer targetable is removed. */
    public void tick() {
        for (Iterator<Map.Entry<UUID, Decoy>> each = byNpc.entrySet().iterator(); each.hasNext();) {
            Decoy decoy = each.next().getValue();
            Optional<VirtualNpcState.Snapshot> self = host.targetable(decoy.npc());
            World world = self.map(this::world).orElse(null);
            if (self.isEmpty() || world == null || !decoy.body().isValid() || !decoy.body().getWorld().equals(world)) {
                remove(decoy);
                each.remove();
                continue;
            }
            Vec3 feet = self.get().position().point();
            Location at = decoy.body().getLocation();
            if (Math.abs(at.getX() - feet.x()) + Math.abs(at.getY() - feet.y()) + Math.abs(at.getZ() - feet.z()) > FOLLOW)
                decoy.body().teleport(new Location(world, feet.x(), feet.y(), feet.z(), at.getYaw(), at.getPitch()));
        }
    }

    /** Mobs near an NPC: how many target its decoy now, and those {@code wanted} accepts that have nothing else to fight, nearest first. */
    public record Scan(int drawn, List<Mob> candidates) {}
    /** On the thread that owns the whole area within {@link #RANGE} of the NPC. */
    public static Scan scan(World world, VirtualNpcState.Snapshot self, Mannequin decoy, Predicate<Mob> wanted) {
        Location centre = center(world, self.bounds());
        int drawn = 0;
        List<Mob> candidates = new ArrayList<>();
        for (Entity entity : world.getNearbyEntities(centre, RANGE, RANGE / 2, RANGE)) {
            if (!(entity instanceof Mob mob) || !mob.isValid() || mob.isDead() || !mob.hasAI() || !mob.isAware()) continue;
            LivingEntity target = mob.getTarget();
            if (decoy != null && target != null && target.getUniqueId().equals(decoy.getUniqueId())) { drawn++; continue; }
            if (target != null && target.isValid() && !target.isDead()) continue;
            if (!wanted.test(mob) || !inFollowRange(mob, centre) || !sees(mob, centre)) continue;
            candidates.add(mob);
        }
        candidates.sort(Comparator.comparingDouble((Mob mob) -> mob.getLocation().distanceSquared(centre)));
        return new Scan(drawn, candidates);
    }
    /** The corners of the area a draw for this NPC reads, as {@code {minX, minZ, maxX, maxZ}} chunk coordinates. */
    public static int[] area(VirtualNpcState.Snapshot self) {
        Hitbox bounds = self.bounds();
        double x = (bounds.min().x() + bounds.max().x()) / 2, z = (bounds.min().z() + bounds.max().z()) / 2;
        return new int[] {Math.floorDiv((int) Math.floor(x - RANGE), 16), Math.floorDiv((int) Math.floor(z - RANGE), 16),
                Math.floorDiv((int) Math.floor(x + RANGE), 16), Math.floorDiv((int) Math.floor(z + RANGE), 16)};
    }

    /** {@link #fights(Mob, String, Set, Predicate)} with this decoy host's riders and fights. */
    boolean fights(Mob mob, UUID npc, String body) { return fights(mob, body, host.attached(npc), (UUID owner) -> host.fought(npc, owner)); }
    /**
     * Whether this mob would fight a player standing where an NPC of this body stands, as its own AI chooses. A mob never
     * fights the NPC riding it ({@code attached}); a tame wolf joins a fight its owner had with the NPC ({@code fought}).
     */
    public static boolean fights(Mob mob, String body, Set<UUID> attached, Predicate<UUID> fought) {
        if (attached.contains(mob.getUniqueId())) return false;
        String type = mob.getType().getKey().toString();
        if (type.equals("minecraft:spider") || type.equals("minecraft:cave_spider")) return mob.getLocation().getBlock().getLightLevel() < SPIDER_LIGHT;
        if (HUNTERS.contains(type)) return true;
        if (type.equals("minecraft:iron_golem") || type.equals("minecraft:snow_golem")) return MONSTERS.contains(body);
        if (type.equals("minecraft:wolf")) {
            if (SKELETONS.contains(body)) return true;
            AnimalTamer owner = mob instanceof Tameable tame && tame.isTamed() ? tame.getOwner() : null;
            return owner != null && fought.test(owner.getUniqueId());
        }
        return false;
    }

    private static boolean inFollowRange(Mob mob, Location target) {
        AttributeInstance follow = mob.getAttribute(Attribute.FOLLOW_RANGE);
        double range = follow == null ? RANGE : follow.getValue();
        return mob.getLocation().distanceSquared(target) <= range * range;
    }
    private static boolean sees(Mob mob, Location target) {
        Location eye = mob.getEyeLocation();
        Vector direction = target.toVector().subtract(eye.toVector());
        double distance = direction.length();
        return distance < .0001 || eye.getWorld().rayTraceBlocks(eye, direction.normalize(), distance, FluidCollisionMode.NEVER, true) == null;
    }

    private Decoy decoy(UUID npc, VirtualNpcState.Snapshot self, World world) {
        Decoy existing = byNpc.get(npc);
        if (existing != null && existing.body().isValid()) return existing;
        if (existing != null) forget(npc);
        Mannequin body = spawn(world, self, key);
        if (body == null) return null;
        Decoy made = new Decoy(npc, body);
        byNpc.put(npc, made);
        byEntity.put(body.getUniqueId(), npc);
        return made;
    }

    /**
     * A decoy on the NPC's feet: a mannequin no player is ever sent, with no gravity, no collision and no sound, marked
     * with {@code key} and sized to the NPC's body; null when the world refused it. On the thread that owns the feet.
     */
    public static Mannequin spawn(World world, VirtualNpcState.Snapshot self, NamespacedKey key) {
        Vec3 feet = self.position().point();
        double height = self.bounds().max().y() - self.bounds().min().y();
        Mannequin body = world.spawn(new Location(world, feet.x(), feet.y(), feet.z()), Mannequin.class, (Mannequin made) -> {
            made.setVisibleByDefault(false);
            made.setPersistent(false);
            made.setSilent(true);
            made.setGravity(false);
            made.setCollidable(false);
            made.setImmovable(true);
            made.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
            AttributeInstance scale = made.getAttribute(Attribute.SCALE);
            if (scale != null && height > 0) scale.setBaseValue(Math.max(.0625, Math.min(16, height / MANNEQUIN_HEIGHT)));
        });
        return body.isValid() ? body : null;
    }
    /**
     * Removes a decoy on the thread that owns it. With {@code letGo}, mobs that targeted it let go now and choose again with
     * their own AI; otherwise they notice on their own that it is gone.
     */
    public static void release(Mannequin decoy, boolean letGo) {
        if (!decoy.isValid()) return;
        if (letGo) for (Entity near : decoy.getNearbyEntities(RANGE * 2, RANGE, RANGE * 2))
            if (near instanceof Mob mob && mob.getTarget() == decoy) mob.setTarget(null);
        decoy.remove();
    }

    /** Removes the NPC's decoy; mobs that targeted it let go and choose again with their own AI. */
    public void forget(UUID npc) {
        Decoy decoy = byNpc.remove(npc);
        if (decoy != null) remove(decoy);
    }
    private void remove(Decoy decoy) {
        byEntity.remove(decoy.body().getUniqueId());
        release(decoy.body(), true);
    }

    private World world(VirtualNpcState.Snapshot self) {
        try { return plugin.getServer().getWorld(UUID.fromString(self.position().world())); }
        catch (IllegalArgumentException notUuid) { return plugin.getServer().getWorld(self.position().world()); }
    }
    static Location center(World world, Hitbox bounds) {
        return new Location(world, (bounds.min().x() + bounds.max().x()) / 2, (bounds.min().y() + bounds.max().y()) / 2, (bounds.min().z() + bounds.max().z()) / 2);
    }

    @Override public void close() {
        for (Decoy decoy : List.copyOf(byNpc.values())) remove(decoy);
        byNpc.clear();
        byEntity.clear();
    }
}
