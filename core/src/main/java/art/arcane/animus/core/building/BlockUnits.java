package art.arcane.animus.core.building;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Blocks that the game keeps as several cells of one object: the two halves of a door or tall plant ({@code half}), the
 * foot and head of a bed ({@code part}), the two halves of a double chest ({@code type=left|right}) and an extended
 * piston with its head. A build places, removes and undoes such an object as one unit, never as a lone half. Big
 * dripleaf columns are grouped by the planner, since their length is not in the block state.
 */
public final class BlockUnits {
    /** Another cell of the same object: its offset from this cell and the state it holds. */
    public record Partner(int dx, int dy, int dz, BlockState state) {}

    private static final List<String> HORIZONTAL = List.of("north", "east", "south", "west");
    /** North, east, south, west, up and down. */
    private static final int[][] STEP = {{0, 0, -1}, {1, 0, 0}, {0, 0, 1}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}};

    private BlockUnits() {}

    /** The other cell of the object this state belongs to, or empty for a block that stands alone. */
    public static Optional<Partner> partner(BlockState state) {
        String path = state.path();
        Optional<String> half = state.property("half");
        if (half.isPresent() && (half.get().equals("lower") || half.get().equals("upper"))) {
            boolean lower = half.get().equals("lower");
            return Optional.of(new Partner(0, lower ? 1 : -1, 0, with(state, "half", lower ? "upper" : "lower")));
        }
        Optional<String> part = state.property("part");
        Optional<Integer> facing = state.property("facing").map(HORIZONTAL::indexOf).filter((Integer index) -> index >= 0);
        if (part.isPresent() && facing.isPresent() && path.endsWith("_bed") && (part.get().equals("foot") || part.get().equals("head"))) {
            boolean foot = part.get().equals("foot");
            int[] step = STEP[foot ? facing.get() : (facing.get() + 2) % 4];
            return Optional.of(new Partner(step[0], 0, step[2], with(with(state, "part", foot ? "head" : "foot"), "occupied", "false")));
        }
        Optional<String> type = state.property("type");
        if (type.isPresent() && facing.isPresent() && path.endsWith("chest") && (type.get().equals("left") || type.get().equals("right"))) {
            boolean left = type.get().equals("left");
            int[] step = STEP[(facing.get() + (left ? 1 : 3)) % 4];
            return Optional.of(new Partner(step[0], 0, step[2], with(state, "type", left ? "right" : "left")));
        }
        Optional<Integer> pushed = state.property("facing").map(BlockUnits::direction).filter((Integer index) -> index >= 0);
        if ((path.equals("piston") || path.equals("sticky_piston")) && state.property("extended").filter("true"::equals).isPresent() && pushed.isPresent()) {
            int[] step = STEP[pushed.get()];
            Map<String, String> head = new HashMap<>();
            head.put("facing", state.property("facing").orElseThrow());
            head.put("short", "false");
            head.put("type", path.equals("sticky_piston") ? "sticky" : "normal");
            return Optional.of(new Partner(step[0], step[1], step[2], new BlockState(namespace(state) + "piston_head", head)));
        }
        if (path.equals("piston_head") && pushed.isPresent()) {
            int[] step = STEP[pushed.get()];
            Map<String, String> base = new HashMap<>();
            base.put("facing", state.property("facing").orElseThrow());
            base.put("extended", "true");
            String name = state.property("type").filter("sticky"::equals).isPresent() ? "sticky_piston" : "piston";
            return Optional.of(new Partner(-step[0], -step[1], -step[2], new BlockState(namespace(state) + name, base)));
        }
        return Optional.empty();
    }

    /**
     * Whether this cell leads its object: the lower half, the foot of a bed, the left half of a chest or the piston body.
     * The leading cell is placed first and pays for the object.
     */
    public static boolean leads(BlockState state) {
        if (partner(state).isEmpty()) return true;
        String path = state.path();
        return state.property("half").filter("lower"::equals).isPresent() || state.property("part").filter("foot"::equals).isPresent()
                || state.property("type").filter("left"::equals).isPresent() && path.endsWith("chest") || path.equals("piston") || path.equals("sticky_piston");
    }

    /** Whether two states are the same object part, ignoring a bed's {@code occupied} flag. */
    public static boolean matches(BlockState expected, BlockState actual) {
        if (!expected.name().equals(actual.name())) return false;
        if (!expected.path().endsWith("_bed")) return expected.properties().equals(actual.properties());
        return without(expected, "occupied").equals(without(actual, "occupied"));
    }

    /** A bed is placed empty; every other state is placed as written. */
    public static BlockState normalized(BlockState state) {
        return state.path().endsWith("_bed") && state.property("occupied").isPresent() ? with(state, "occupied", "false") : state;
    }

    /** Big dripleaf stems carry a head on top: the column is one object. */
    public static boolean dripleafStem(BlockState state) { return state.path().equals("big_dripleaf_stem"); }
    public static boolean dripleafHead(BlockState state) { return state.path().equals("big_dripleaf"); }
    /** The head a stem column without one gets: a big dripleaf facing the same way. */
    public static BlockState dripleafHeadFor(BlockState stem) {
        Map<String, String> head = new HashMap<>();
        stem.property("facing").ifPresent((String facing) -> head.put("facing", facing));
        head.put("tilt", "none");
        head.put("waterlogged", stem.property("waterlogged").orElse("false"));
        return new BlockState(namespace(stem) + "big_dripleaf", head);
    }

    private static int direction(String name) {
        int horizontal = HORIZONTAL.indexOf(name);
        if (horizontal >= 0) return horizontal;
        return name.equals("up") ? 4 : name.equals("down") ? 5 : -1;
    }
    private static String namespace(BlockState state) { return state.name().substring(0, state.name().indexOf(':') + 1); }
    private static BlockState with(BlockState state, String key, String value) {
        if (!state.properties().containsKey(key) && !key.equals("half") && !key.equals("part") && !key.equals("type")) return state;
        Map<String, String> next = new HashMap<>(state.properties());
        next.put(key, value);
        return state.withProperties(next);
    }
    private static Map<String, String> without(BlockState state, String key) {
        Map<String, String> next = new HashMap<>(state.properties());
        next.remove(key);
        return next;
    }
}
