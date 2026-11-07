package art.arcane.animus.core.virtual;

import art.arcane.animus.core.npc.BodyVariant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;

/**
 * Exact presentation rules of the native ender dragon. Its model faces opposite its rotation field, its clients
 * pick eight child parts rather than the root, and its phase field drives how it folds and flaps.
 * This only describes a presentation: there is no dragon AI, no boss bar and no world entity.
 */
public final class DragonBody {
    public static final String TYPE = "minecraft:ender_dragon";
    public static final int PART_COUNT = 8, RESERVED_IDS = PART_COUNT + 1;
    public static final int PHASE_HOLDING_PATTERN = 0, PHASE_SITTING_SCANNING = 6, PHASE_HOVERING = 10;
    /** Clients interpolate parts and the neck and tail lag behind while turning, so hits get this much slack. */
    private static final double HIT_TOLERANCE = 1;
    /** Offset from the feet at rotation zero: sideways, up, forward-along-the-rotation-field, then part size. */
    private record Part(double width, double height, double side, double up, double along) {}
    private static final List<Part> PARTS = List.of(
            new Part(1, 1, 0, 0, 6.5),          // head
            new Part(3, 3, 0, 0, 5.5),          // neck
            new Part(5, 3, 0, 0, 0.5),          // body
            new Part(2, 2, 0, 1.5, -3.5),       // tail
            new Part(2, 2, 0, 1.5, -5.5),       // tail
            new Part(2, 2, 0, 1.5, -7.5),       // tail tip
            new Part(4, 2, 4.5, 2, 0),          // wing
            new Part(4, 2, -4.5, 2, 0));        // wing
    private DragonBody() {}

    /** The rotation to put on the wire so that the dragon's head points along {@code facingYaw}. */
    public static float wireYaw(float facingYaw) {
        float wrapped = (facingYaw + 180) % 360;
        if (wrapped > 180) wrapped -= 360;
        if (wrapped < -180) wrapped += 360;
        return wrapped;
    }
    /** A resting phase picked from the ground under the dragon rather than from its definition. */
    public static final int AUTOMATIC = -1;

    /** The phase a still dragon shows by its definition's {@code dragon.phase}, or {@link #AUTOMATIC}. */
    public static int restingPhase(BodyVariant variant) {
        return switch (variant.choices().getOrDefault("phase", "")) {
            case "hovering" -> PHASE_HOVERING;
            case "sitting" -> PHASE_SITTING_SCANNING;
            default -> AUTOMATIC;
        };
    }
    public static int phase(boolean moving, boolean supported) {
        if (moving) return PHASE_HOLDING_PATTERN;
        return supported ? PHASE_SITTING_SCANNING : PHASE_HOVERING;
    }
    /** Part boxes in client child-ID order offset, as a body at rest would lay them out. */
    public static List<Hitbox> parts(Vec3 feet, float facingYaw) {
        Objects.requireNonNull(feet);
        double radians = Math.toRadians(facingYaw), forwardX = -Math.sin(radians), forwardZ = Math.cos(radians);
        List<Hitbox> boxes = new ArrayList<>(PART_COUNT);
        for (Part part : PARTS) {
            Vec3 at = new Vec3(feet.x() + forwardX * part.along() + forwardZ * part.side(), feet.y() + part.up(),
                    feet.z() + forwardZ * part.along() - forwardX * part.side());
            boxes.add(Hitbox.atFeet(at, part.width(), part.height()));
        }
        return boxes;
    }
    /** Hits are measured against server-computed parts, never against which part ID the client claims to have struck. */
    public static ViewerSession.HitShape hitShape(float facingYaw) {
        return (npc, origin, direction, reach) -> {
            OptionalDouble nearest = OptionalDouble.empty();
            for (Hitbox box : parts(npc.position().point(), facingYaw)) {
                OptionalDouble hit = box.inflated(HIT_TOLERANCE).rayDistance(origin, direction, reach);
                if (hit.isPresent() && (nearest.isEmpty() || hit.getAsDouble() < nearest.getAsDouble())) nearest = hit;
            }
            return nearest;
        };
    }
}
