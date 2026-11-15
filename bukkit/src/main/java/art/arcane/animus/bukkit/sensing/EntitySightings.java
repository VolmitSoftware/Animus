package art.arcane.animus.bukkit.sensing;

import art.arcane.animus.core.behavior.trigger.EntitySensor;
import art.arcane.animus.core.behavior.trigger.TriggerEvent;
import art.arcane.animus.core.virtual.Hitbox;
import art.arcane.animus.core.virtual.Vec3;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Item;
import org.bukkit.entity.LeashHitch;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

/** What sensed triggers see of real entities on Bukkit. Every call runs on the thread that owns the entities it reads. */
final class EntitySightings {
    /** Chunk ownership on Folia; everything is owned elsewhere. */
    @FunctionalInterface interface Owned { boolean chunks(World world, int minX, int minZ, int maxX, int maxZ); }
    /** The entities in the box nearest {@code center} first, at most {@code limit}, and whether that was all of them. */
    record Look(List<Entity> entities, boolean complete) {}

    private EntitySightings() {}

    /** Mobs, players, items, minecarts, boats, falling blocks and lit TNT. Spectators, projectiles, displays and hanging things are left out. */
    static boolean sensed(Entity entity) {
        if (entity instanceof Player player) return player.getGameMode() != GameMode.SPECTATOR;
        return entity instanceof LivingEntity || entity instanceof Item || entity instanceof Minecart || entity instanceof Boat
                || entity instanceof FallingBlock || entity instanceof TNTPrimed;
    }

    /**
     * Only chunks this thread owns are read; on Folia the rest of the box belongs to another region. {@code standIns} are
     * entities that stand in for an NPC body, such as combat's decoys: they are never seen and take no slot of the limit.
     */
    static Look nearby(World world, BoundingBox box, Vec3 center, int limit, Owned owned, Predicate<Entity> standIns, Predicate<Entity> wanted) {
        int minX = ((int) Math.floor(box.getMinX())) >> 4, maxX = ((int) Math.floor(box.getMaxX())) >> 4;
        int minZ = ((int) Math.floor(box.getMinZ())) >> 4, maxZ = ((int) Math.floor(box.getMaxZ())) >> 4;
        List<Entity> found = new ArrayList<>();
        Predicate<Entity> keep = (Entity entity) -> sensed(entity) && !standIns.test(entity) && wanted.test(entity);
        if (owned.chunks(world, minX, minZ, maxX, maxZ) && loaded(world, minX, minZ, maxX, maxZ)) found.addAll(world.getNearbyEntities(box, keep::test));
        else {
            for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
                if (!owned.chunks(world, x, z, x, z) || !world.isChunkLoaded(x, z)) continue;
                Chunk chunk = world.getChunkAt(x, z);
                for (Entity entity : chunk.getEntities()) if (keep.test(entity) && box.overlaps(entity.getBoundingBox())) found.add(entity);
            }
        }
        found.sort(Comparator.comparingDouble((Entity entity) -> entity.getLocation().toVector().distanceSquared(new Vector(center.x(), center.y(), center.z()))));
        return found.size() <= limit ? new Look(found, true) : new Look(List.copyOf(found.subList(0, limit)), false);
    }
    private static boolean loaded(World world, int minX, int minZ, int maxX, int maxZ) {
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) if (!world.isChunkLoaded(x, z)) return false;
        return true;
    }

    /**
     * {@code readable} is false for an entity another thread may own: its UUID, type and a player's name stay readable,
     * a custom name and tags are left out.
     */
    static TriggerEvent.Subject subject(Entity entity, boolean readable) {
        String type = entity.getType().getKey().toString();
        String custom = readable ? entity.getCustomName() : null;
        String name = entity instanceof Player player ? player.getName() : custom == null ? "" : custom;
        return new TriggerEvent.Subject(entity.getUniqueId(), type, name, readable ? Set.copyOf(entity.getScoreboardTags()) : Set.of());
    }
    static EntitySensor.Party party(Entity entity, boolean readable) { return new EntitySensor.Party(subject(entity, readable), entity instanceof Player); }

    /** One sighting. A thrower is resolved only when it is an online player, since other entities may belong to another thread. */
    static EntitySensor.Seen seen(Entity entity, Server server) {
        BoundingBox box = entity.getBoundingBox();
        Hitbox bounds = box.getWidthX() > 0 && box.getHeight() > 0 && box.getWidthZ() > 0
                ? new Hitbox(new Vec3(box.getMinX(), box.getMinY(), box.getMinZ()), new Vec3(box.getMaxX(), box.getMaxY(), box.getMaxZ()))
                : Hitbox.atFeet(new Vec3(box.getCenterX(), box.getMinY(), box.getCenterZ()), 0.1, 0.1);
        Vector velocity = entity.getVelocity();
        org.bukkit.Location at = entity.getLocation();
        EntitySensor.Role role = entity instanceof Item ? EntitySensor.Role.ITEM : entity instanceof Minecart || entity instanceof Boat ? EntitySensor.Role.VEHICLE
                : entity instanceof LivingEntity ? EntitySensor.Role.LIVING : EntitySensor.Role.OTHER;
        Optional<EntitySensor.Party> rider = Optional.empty(), thrower = Optional.empty(), holder = Optional.empty();
        Optional<String> item = Optional.empty();
        int amount = 0;
        if (role == EntitySensor.Role.VEHICLE && !entity.getPassengers().isEmpty()) rider = Optional.of(party(entity.getPassengers().getFirst(), true));
        if (entity instanceof Item dropped) {
            item = Optional.of(dropped.getItemStack().getType().getKey().toString());
            amount = dropped.getItemStack().getAmount();
            UUID who = dropped.getThrower();
            Player player = who == null ? null : server.getPlayer(who);
            if (player != null) thrower = Optional.of(new EntitySensor.Party(subject(player, false), true));
            else if (who != null) thrower = Optional.of(new EntitySensor.Party(new TriggerEvent.Subject(who, "", "", Set.of()), false));
        }
        if (entity instanceof LivingEntity living && !(entity instanceof Player) && living.isLeashed()) {
            Entity lead = living.getLeashHolder();
            // A mob tied to a fence is not being led anywhere.
            if (!(lead instanceof LeashHitch)) holder = Optional.of(party(lead, true));
        }
        return new EntitySensor.Seen(subject(entity, true), role, entity instanceof Player, new Vec3(at.getX(), at.getY(), at.getZ()), bounds,
                new Vec3(velocity.getX(), velocity.getY(), velocity.getZ()), rider, thrower, holder, item, amount, entity.getTicksLived());
    }
}
