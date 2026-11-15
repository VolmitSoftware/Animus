package art.arcane.animus.bukkit.combat;

import art.arcane.animus.core.virtual.Hitbox;
import art.arcane.animus.core.virtual.Vec3;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Explosive;
import org.bukkit.entity.Wither;
import org.bukkit.util.Vector;

/**
 * Vanilla explosion damage for virtual NPC bodies, which a real explosion never reaches: the blast's power, how far
 * the body's feet are from its centre, and how much of the body the centre can see. On the thread that owns the blocks.
 */
public final class Explosions {
    /** A bed or respawn anchor blowing up, where no entity caused it. */
    public static final float BLOCK_POWER = 5;
    private static final int SAMPLES = 3;

    private Explosions() {}

    /** The power of an explosion this entity caused, as vanilla gives it: a creeper's radius, doubled when charged, and so on. */
    public static float power(Entity source) {
        if (source instanceof Creeper creeper) return creeper.getExplosionRadius() * (creeper.isPowered() ? 2 : 1);
        if (source instanceof Explosive explosive) return explosive.getYield();
        if (source instanceof EnderCrystal) return 6;
        if (source instanceof Wither) return 7;
        return 4;
    }

    /** What an explosion of this power at this centre deals a body with these bounds; 0 beyond twice the power. */
    public static double damage(World world, Vec3 centre, float power, Hitbox bounds) {
        double diameter = power * 2.0;
        if (!(diameter > 0)) return 0;
        Vec3 feet = new Vec3((bounds.min().x() + bounds.max().x()) / 2, bounds.min().y(), (bounds.min().z() + bounds.max().z()) / 2);
        double distance = feet.distance(centre) / diameter;
        if (distance > 1) return 0;
        double impact = (1 - distance) * seen(world, centre, bounds);
        return (int) ((impact * impact + impact) / 2 * 7 * diameter + 1);
    }

    /** The share of points spread through the body that have a clear line to the centre. */
    static double seen(World world, Vec3 centre, Hitbox bounds) {
        int clear = 0, total = 0;
        for (int x = 0; x < SAMPLES; x++) for (int y = 0; y < SAMPLES; y++) for (int z = 0; z < SAMPLES; z++) {
            Vec3 point = new Vec3(between(bounds.min().x(), bounds.max().x(), x), between(bounds.min().y(), bounds.max().y(), y), between(bounds.min().z(), bounds.max().z(), z));
            Vector line = new Vector(centre.x() - point.x(), centre.y() - point.y(), centre.z() - point.z());
            double length = line.length();
            total++;
            if (length < .0001 || open(world, point, line, length)) clear++;
        }
        return (double) clear / total;
    }
    /** A line through blocks this thread may not read, as on another Folia region, counts as open. */
    private static boolean open(World world, Vec3 point, Vector line, double length) {
        try { return world.rayTraceBlocks(new Location(world, point.x(), point.y(), point.z()), line.normalize(), length, FluidCollisionMode.NEVER, true) == null; }
        catch (RuntimeException unreadable) { return true; }
    }
    private static double between(double low, double high, int step) { return low + (high - low) * step / (SAMPLES - 1); }
}
