package art.arcane.animus.core.building;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Mirroring then clockwise rotation about the vertical axis, applied to positions and to block state properties.
 * Minecraft's X grows east and Z grows south, so a clockwise quarter turn seen from above maps {@code (x, z)} to
 * {@code (-z, x)} and north to east.
 */
public record BlockTransform(Rotation rotation, Mirror mirror) {
    public static final BlockTransform IDENTITY = new BlockTransform(Rotation.NONE, Mirror.NONE);
    private static final List<String> HORIZONTAL = List.of("north", "east", "south", "west");

    /** Clockwise quarter turns seen from above. */
    public enum Rotation {
        NONE(0), CLOCKWISE_90(1), CLOCKWISE_180(2), CLOCKWISE_270(3);
        private final int turns;
        Rotation(int turns) { this.turns = turns; }
        public int turns() { return turns; }
        public int degrees() { return turns * 90; }
        public static Rotation degrees(int degrees) {
            for (Rotation rotation : values()) if (rotation.degrees() == degrees) return rotation;
            throw new IllegalArgumentException("Rotation must be 0, 90, 180 or 270");
        }
    }
    /** {@code X} swaps east and west by negating X; {@code Z} swaps north and south by negating Z. */
    public enum Mirror {
        NONE, X, Z;
        public static Mirror named(String text) {
            try { return valueOf(text.toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Mirror must be none, x or z"); }
        }
    }

    public BlockTransform {
        Objects.requireNonNull(rotation, "rotation");
        Objects.requireNonNull(mirror, "mirror");
    }

    /** The transformed horizontal offset. */
    public int[] apply(int x, int z) {
        int px = mirror == Mirror.X ? -x : x, pz = mirror == Mirror.Z ? -z : z;
        for (int turn = 0; turn < rotation.turns(); turn++) {
            int next = -pz;
            pz = px;
            px = next;
        }
        return new int[]{px, pz};
    }

    /** The schematic offset that {@link #apply(int, int)} turns into this one. */
    public int[] invert(int x, int z) {
        int px = x, pz = z;
        for (int turn = 0; turn < rotation.turns(); turn++) {
            int next = pz;
            pz = -px;
            px = next;
        }
        return new int[]{mirror == Mirror.X ? -px : px, mirror == Mirror.Z ? -pz : pz};
    }

    /** A horizontal direction name after the transform; other names pass through. */
    public String direction(String name) {
        int index = HORIZONTAL.indexOf(name);
        if (index < 0) return name;
        if (mirror == Mirror.X && (index == 1 || index == 3)) index = 4 - index;
        if (mirror == Mirror.Z && (index == 0 || index == 2)) index = 2 - index;
        return HORIZONTAL.get((index + rotation.turns()) % 4);
    }

    private boolean flips() { return mirror != Mirror.NONE; }

    /** The state with its directional properties transformed. Unknown properties are kept as written. */
    public BlockState apply(BlockState state) {
        if (rotation == Rotation.NONE && mirror == Mirror.NONE) return state;
        Map<String, String> source = state.properties();
        if (source.isEmpty()) return state;
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, String> entry : source.entrySet()) {
            String key = entry.getKey(), value = entry.getValue();
            if (HORIZONTAL.contains(key)) { result.put(direction(key), value); continue; }
            switch (key) {
                case "facing", "horizontal_facing", "vertical_direction" -> result.put(key, direction(value));
                case "rotation" -> result.put(key, rotationValue(value));
                case "axis" -> result.put(key, rotation.turns() % 2 == 1 && (value.equals("x") || value.equals("z")) ? (value.equals("x") ? "z" : "x") : value);
                case "shape" -> result.put(key, shape(value));
                case "hinge" -> result.put(key, flips() ? swap(value, "left", "right") : value);
                case "type" -> result.put(key, flips() && (value.equals("left") || value.equals("right")) ? swap(value, "left", "right") : value);
                case "orientation" -> result.put(key, orientation(value));
                default -> result.put(key, value);
            }
        }
        return state.withProperties(result);
    }

    /** Signs, banners and heads use 16 steps with 0 facing south and 4 facing west. */
    private String rotationValue(String value) {
        int step;
        try { step = Integer.parseInt(value); }
        catch (NumberFormatException invalid) { return value; }
        if (step < 0 || step > 15) return value;
        if (mirror == Mirror.X) step = (16 - step) % 16;
        if (mirror == Mirror.Z) step = (24 - step) % 16;
        return Integer.toString((step + rotation.turns() * 4) % 16);
    }

    /** Stair shapes swap sides when mirrored; rail shapes move with their directions. */
    private String shape(String value) {
        if (value.startsWith("inner_") || value.startsWith("outer_"))
            return flips() ? swap(value, value.substring(0, 6) + "left", value.substring(0, 6) + "right") : value;
        if (value.startsWith("ascending_")) return "ascending_" + direction(value.substring(10));
        String[] parts = value.split("_");
        if (parts.length != 2 || !HORIZONTAL.contains(parts[0]) || !HORIZONTAL.contains(parts[1])) return value;
        String first = direction(parts[0]), second = direction(parts[1]);
        boolean straight = HORIZONTAL.indexOf(first) % 2 == HORIZONTAL.indexOf(second) % 2;
        if (straight) return HORIZONTAL.indexOf(first) % 2 == 0 ? "north_south" : "east_west";
        boolean firstVertical = first.equals("north") || first.equals("south");
        return firstVertical ? first + "_" + second : second + "_" + first;
    }

    /** Jigsaw and crafter orientations are a front and a top direction, such as {@code north_up}. */
    private String orientation(String value) {
        String[] parts = value.split("_");
        if (parts.length != 2) return value;
        return direction(parts[0]) + "_" + direction(parts[1]);
    }

    private static String swap(String value, String first, String second) {
        if (value.equals(first)) return second;
        if (value.equals(second)) return first;
        return value;
    }
}
