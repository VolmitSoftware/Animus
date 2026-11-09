package art.arcane.animus.core.command;

import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Coordinate words as vanilla {@code /tp} reads them: a number is absolute, {@code ~} or {@code ~2} is relative to where
 * the player stands, and {@code ^left ^up ^forward} is local to where the player faces. A word left out is the player's
 * own value. The console has no position, so it must give numbers. Values taken from the player keep two decimals.
 */
public final class Coordinates {
    /** The axes a positioned command takes, in order. */
    static final List<String> AXES = List.of("x", "y", "z");
    /** The reply when the console leaves a coordinate out or writes it relative. */
    static final String CONSOLE = "The console has no position; give x=, y= and z= as numbers, such as x=0 y=64 z=0";
    private static final Pattern WORD = Pattern.compile("[~^]?(-?\\d+(\\.\\d+)?)?");

    /** Which way the player faces, in degrees as Minecraft gives them. */
    public record Facing(float yaw, float pitch) {}

    /** Where relative words count from: the player's feet and facing. */
    public record Origin(Vec3 point, Facing facing) {}

    private Coordinates() {}

    /** Where the actor stands and faces; empty for the console. Read on the actor's owner. */
    static Optional<Origin> origin(CommandActor actor) {
        return actor.position().map((WorldPosition at) -> new Origin(at.point(), actor.facing().orElse(new Facing(0, 0))));
    }

    /** True for a word a coordinate may be: a number, {@code ~}, {@code ~N}, {@code ^} or {@code ^N}. */
    static boolean coordinate(String word) { return !word.isEmpty() && WORD.matcher(word).matches(); }

    /** The point three words name; an empty word is the origin's own value. Throws with the reply when they cannot be read. */
    public static Vec3 point(String x, String y, String z, Optional<Origin> origin) {
        boolean local = x.startsWith("^") || y.startsWith("^") || z.startsWith("^");
        if (local) {
            if (!(x.startsWith("^") && y.startsWith("^") && z.startsWith("^")))
                throw new IllegalArgumentException("Use ^ on all three or none, such as ^ ^ ^2 for two blocks ahead");
            Origin from = origin.orElseThrow(() -> new IllegalArgumentException(CONSOLE));
            return local(from, offset("x", x.substring(1)), offset("y", y.substring(1)), offset("z", z.substring(1)));
        }
        Optional<Vec3> at = origin.map(Origin::point);
        return new Vec3(axis("x", x, at.map(Vec3::x)), axis("y", y, at.map(Vec3::y)), axis("z", z, at.map(Vec3::z)));
    }

    /** {@code x,y,z} with any part relative, as whole block coordinates {@code x,y,z}; a value with neither ~ nor ^ is returned unchanged. */
    public static String block(String value, Optional<Origin> origin) {
        if (value.indexOf('~') < 0 && value.indexOf('^') < 0) return value;
        String[] parts = value.split(",", -1);
        if (parts.length != 3) throw new IllegalArgumentException("Give a block as x,y,z, such as ~,~,~ or 12,64,-30");
        Vec3 point = point(parts[0].strip(), parts[1].strip(), parts[2].strip(), origin);
        return (long) Math.floor(point.x()) + "," + (long) Math.floor(point.y()) + "," + (long) Math.floor(point.z());
    }

    private static double axis(String axis, String word, Optional<Double> origin) {
        if (word.isEmpty() || word.startsWith("~")) {
            double base = origin.orElseThrow(() -> new IllegalArgumentException(CONSOLE));
            return round(base + offset(axis, word.isEmpty() ? "" : word.substring(1)));
        }
        return number(axis, word);
    }

    private static double offset(String axis, String word) { return word.isEmpty() ? 0 : number(axis, word); }

    private static double number(String axis, String word) {
        try {
            double value = Double.parseDouble(word);
            if (Double.isFinite(value)) return value;
        } catch (NumberFormatException notNumber) { /* answered below */ }
        throw new IllegalArgumentException(axis + " must be a number, ~ or ~N, such as " + axis + "=12.5 or " + axis + "=~2");
    }

    /** Vanilla's local frame: {@code left} along the player's left, {@code up} along their up, {@code forward} where they look. */
    private static Vec3 local(Origin origin, double left, double up, double forward) {
        double yaw = Math.toRadians(origin.facing().yaw() + 90), pitch = Math.toRadians(-origin.facing().pitch()), raised = Math.toRadians(-origin.facing().pitch() + 90);
        Vec3 ahead = new Vec3(Math.cos(yaw) * Math.cos(pitch), Math.sin(pitch), Math.sin(yaw) * Math.cos(pitch));
        Vec3 above = new Vec3(Math.cos(yaw) * Math.cos(raised), Math.sin(raised), Math.sin(yaw) * Math.cos(raised));
        Vec3 side = new Vec3(ahead.y() * above.z() - ahead.z() * above.y(), ahead.z() * above.x() - ahead.x() * above.z(),
                ahead.x() * above.y() - ahead.y() * above.x()).multiply(-1);
        Vec3 point = origin.point().add(ahead.multiply(forward)).add(above.multiply(up)).add(side.multiply(left));
        return new Vec3(round(point.x()), round(point.y()), round(point.z()));
    }

    private static double round(double value) { return Math.round(value * 100) / 100.0; }

    /** {@code x y z} for a reply, whole numbers without a decimal point. */
    public static String shown(Vec3 point) { return plain(point.x()) + " " + plain(point.y()) + " " + plain(point.z()); }

    private static String plain(double value) { return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value); }
}
