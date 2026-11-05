package art.arcane.animus.core.navigation;

import art.arcane.animus.core.navigation.VoxelSnapshot.Cell;
import art.arcane.animus.core.virtual.Vec3;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The doors one walking NPC holds open on its route. Just before a step reaches a closed door, fence gate or trapdoor,
 * the NPC opens it for real; once the NPC is clear of it, it closes it again. Only doors this passage opened are ever
 * closed, so a door a player left open stays open. Owner thread only.
 */
public final class DoorPassage {
    /** How far ahead of a step a door is opened, so it swings before the body reaches it. */
    static final double LOOK_AHEAD = 0.6;
    /** How far past a door's block the body must be before the door closes behind it. */
    static final double CLEARANCE = 0.5;
    private static final double EPSILON = MotionValidator.EPSILON;
    private static final int MAXIMUM_HELD = 16;

    /** What a platform does to a real door. */
    public interface Doors {
        /**
         * Opens the closed hand-opened block at {@code at} for the NPC, on the thread that owns its chunk, after the
         * platform's protection allows it. False, and the block unchanged, when protection, ownership or the block refuses
         * or the check fails.
         */
        boolean open(UUID npc, String world, BlockPos at);
        /**
         * Closes a block this NPC opened, when it is still loaded, still the same kind of block and still open. A platform
         * whose caller does not own the block's chunk hands the work to its owner. Never loads a chunk.
         */
        void close(String world, BlockPos at);
    }

    /** Refuses every door: for platforms where nothing may open one. */
    public static final Doors NONE = new Doors() {
        @Override public boolean open(UUID npc, String world, BlockPos at) { return false; }
        @Override public void close(String world, BlockPos at) {}
    };

    private final UUID npc;
    private final Set<BlockPos> held = new LinkedHashSet<>();
    private String world;

    public DoorPassage(UUID npc) { this.npc = Objects.requireNonNull(npc, "npc"); }

    /** Reads every door the body may open as already open, for planning. */
    public static MotionValidator.Sampler opened(MotionValidator.Sampler terrain) {
        Objects.requireNonNull(terrain, "terrain");
        return (int x, int y, int z) -> {
            Cell cell = terrain.sample(x, y, z);
            return cell == null ? null : cell.whenOpen();
        };
    }

    /**
     * Opens every closed door the body would touch stepping from {@code from} to {@code to}, looking a little further
     * ahead. Returns the first door it could not open; the step must then not be taken.
     */
    public Optional<BlockPos> prepare(String world, Vec3 from, Vec3 to, double width, double height, MotionValidator.Sampler live, Doors doors) {
        Objects.requireNonNull(world); Objects.requireNonNull(from); Objects.requireNonNull(to); Objects.requireNonNull(live); Objects.requireNonNull(doors);
        if (this.world != null && !this.world.equals(world)) closeAll(doors);
        double dx = to.x() - from.x(), dz = to.z() - from.z(), across = Math.hypot(dx, dz);
        Vec3 ahead = across > EPSILON ? to.add(new Vec3(dx / across * LOOK_AHEAD, 0, dz / across * LOOK_AHEAD)) : to;
        double half = width / 2;
        double lowX = Math.min(from.x(), ahead.x()) - half, highX = Math.max(from.x(), ahead.x()) + half;
        double lowZ = Math.min(from.z(), ahead.z()) - half, highZ = Math.max(from.z(), ahead.z()) + half;
        double lowY = Math.min(from.y(), ahead.y()), highY = Math.max(from.y(), ahead.y()) + height;
        int minX = MotionValidator.floor(lowX + EPSILON), maxX = MotionValidator.floor(highX - EPSILON);
        int minZ = MotionValidator.floor(lowZ + EPSILON), maxZ = MotionValidator.floor(highZ - EPSILON);
        int minY = MotionValidator.floor(lowY + EPSILON), maxY = MotionValidator.floor(highY - EPSILON);
        for (int y = minY; y <= maxY; y++) for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
            Cell cell = live.sample(x, y, z);
            if (cell == null || !cell.openable() || !touches(cell, x, y, z, lowX, highX, lowY, highY, lowZ, highZ)) continue;
            BlockPos at = new BlockPos(x, y, z);
            if (held.size() >= MAXIMUM_HELD || !open(world, at, doors)) return Optional.of(at);
            this.world = world;
            held.add(at);
        }
        return Optional.empty();
    }

    /** Closes the doors the body at {@code at} is clear of. */
    public void passed(Vec3 at, double width, Doors doors) {
        Objects.requireNonNull(at); Objects.requireNonNull(doors);
        double reach = width / 2 + CLEARANCE;
        List<BlockPos> clear = new ArrayList<>();
        for (BlockPos door : held)
            if (at.x() + reach <= door.x() || at.x() - reach >= door.x() + 1 || at.z() + reach <= door.z() || at.z() - reach >= door.z() + 1) clear.add(door);
        for (BlockPos door : clear) { held.remove(door); close(door, doors); }
    }

    /** Closes every door still held: the route ended, was cancelled, or the NPC left. */
    public void closeAll(Doors doors) {
        Objects.requireNonNull(doors);
        List<BlockPos> all = List.copyOf(held);
        held.clear();
        for (BlockPos door : all) close(door, doors);
        world = null;
    }

    /** How many doors this NPC holds open now. */
    public int held() { return held.size(); }

    private boolean open(String world, BlockPos at, Doors doors) {
        try { return doors.open(npc, world, at); }
        catch (RuntimeException failure) { return false; }
    }

    private void close(BlockPos door, Doors doors) {
        try { doors.close(world, door); }
        catch (RuntimeException ignored) { /* The platform reports its own failures; a door it cannot close stays as it is. */ }
    }

    private static boolean touches(Cell cell, int x, int y, int z, double lowX, double highX, double lowY, double highY, double lowZ, double highZ) {
        for (Cell.Box box : cell.boxes())
            if (MotionValidator.overlaps(x + box.minX(), x + box.maxX(), lowX, highX) && MotionValidator.overlaps(z + box.minZ(), z + box.maxZ(), lowZ, highZ)
                    && MotionValidator.overlaps(y + box.minY(), y + box.maxY(), lowY, highY)) return true;
        return false;
    }
}
