package art.arcane.animus.core.building;

import art.arcane.animus.core.navigation.BlockPos;
import art.arcane.animus.core.virtual.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One NPC's scaffolding and what it does next. The NPC walks to the foot of the column, stands in each cell to place the
 * scaffolding there and climbs onto it, walks out along the walkway placing each block in front of it, and works from the
 * top. Taking it down runs the other way: it steps back off each walkway block before removing it, then climbs down the
 * column one cell at a time, removing the cell it stands in. Pure: the session carries out each {@link Action}.
 */
final class Scaffold {
    enum Phase { APPROACH, RAISE, WORK, LOWER }
    /** A scaffolding block the NPC placed: where, what was there before, and the state placed. */
    record Cell(BlockPos at, String before, String after) {
        Cell { Objects.requireNonNull(at); Objects.requireNonNull(before); Objects.requireNonNull(after); }
    }

    sealed interface Action permits Move, Put, Take, Ready, Done {}
    /** Walk or climb until the feet are here. */
    record Move(Vec3 feet) implements Action {}
    /** Place scaffolding here; {@code distance} is the game's distance from the column, 0 in the column. */
    record Put(BlockPos at, int distance) implements Action {}
    /** Remove this scaffolding block. */
    record Take(Cell cell) implements Action {}
    /** The NPC stands on top and can build from there. */
    record Ready() implements Action {}
    /** Every block is down. */
    record Done() implements Action {}

    private static final double CLOSE = .35, LEVEL = .3;
    final String npc;
    private ScaffoldPlanner.Layout layout;
    private final List<Cell> placed = new ArrayList<>();
    private Phase phase = Phase.APPROACH;
    private int keep;
    private boolean aborted;
    private Vec3 goal;
    private long goalSince;

    Scaffold(String npc, ScaffoldPlanner.Layout layout) {
        this.npc = Objects.requireNonNull(npc);
        this.layout = Objects.requireNonNull(layout);
    }

    ScaffoldPlanner.Layout layout() { return layout; }
    Phase phase() { return phase; }
    List<Cell> placed() { return List.copyOf(placed); }
    BlockPos stand() { return layout.stand(); }
    boolean working() { return phase == Phase.WORK; }

    void placed(Cell cell) { placed.add(cell); }
    void removed() { placed.removeLast(); }

    /** The scaffold cannot go up where it was planned: it comes down, and the block is reached another way. */
    void abort() { aborted = true; lower(0); }
    boolean aborted() { return aborted; }

    /** Take the scaffold down until {@code remaining} blocks of its column are left. */
    void lower(int remaining) {
        keep = Math.max(0, Math.min(remaining, Math.min(placed.size(), layout.column().size())));
        if (keep > 0 && placed.size() > layout.column().size()) keep = 0;
        phase = Phase.LOWER;
    }

    /**
     * Moves the scaffold to a taller or shorter column at the same place, without a walkway; false when the new layout
     * needs a different scaffold.
     */
    boolean retarget(ScaffoldPlanner.Layout next) {
        if (!layout.walkway().isEmpty() || !next.walkway().isEmpty() || !next.base().equals(layout.base())
                || placed.size() > layout.column().size()) return false;
        int shared = Math.min(placed.size(), next.column().size());
        for (int index = 0; index < shared; index++) if (!placed.get(index).at().equals(next.column().get(index))) return false;
        layout = next;
        if (placed.size() > next.column().size()) lower(next.column().size());
        else phase = placed.isEmpty() ? Phase.APPROACH : Phase.RAISE;
        return true;
    }

    /** The next thing to do with the NPC's feet where they are, at tick {@code now}. */
    Action next(Vec3 feet, long now) {
        while (true) {
            switch (phase) {
                case APPROACH -> {
                    Vec3 base = centre(layout.base(), 0);
                    if (!at(feet, base)) return move(base, now);
                    phase = Phase.RAISE;
                }
                case RAISE -> {
                    List<BlockPos> cells = layout.cells();
                    int index = placed.size();
                    if (index >= cells.size()) {
                        Vec3 top = centre(layout.stand(), 0);
                        if (!at(feet, top)) return move(top, now);
                        phase = Phase.WORK;
                        continue;
                    }
                    BlockPos cell = cells.get(index);
                    boolean column = index < layout.column().size();
                    Vec3 from = column ? centre(cell, 0) : centre(cells.get(index - 1), 1);
                    if (!at(feet, from)) return move(from, now);
                    return new Put(cell, column ? 0 : index - layout.column().size() + 1);
                }
                case WORK -> {
                    Vec3 top = centre(layout.stand(), 0);
                    return at(feet, top) ? new Ready() : move(top, now);
                }
                case LOWER -> {
                    if (placed.size() <= keep) {
                        if (keep == 0) return new Done();
                        phase = Phase.RAISE;
                        continue;
                    }
                    int index = placed.size() - 1;
                    Cell last = placed.get(index);
                    boolean column = index < layout.column().size();
                    Vec3 from = column ? centre(last.at(), 0) : centre(placed.get(index - 1).at(), 1);
                    if (!at(feet, from)) return move(from, now);
                    return new Take(last);
                }
            }
        }
    }

    /** Ticks since the NPC started toward the place it is moving to. */
    long moving(long now) { return goal == null ? 0 : now - goalSince; }

    private Action move(Vec3 feet, long now) {
        if (!feet.equals(goal)) { goal = feet; goalSince = now; }
        return new Move(feet);
    }

    private static boolean at(Vec3 feet, Vec3 goal) {
        return Math.hypot(feet.x() - goal.x(), feet.z() - goal.z()) <= CLOSE && Math.abs(feet.y() - goal.y()) <= LEVEL;
    }
    private static Vec3 centre(BlockPos cell, int above) { return new Vec3(cell.x() + .5, cell.y() + above, cell.z() + .5); }
}
