package art.arcane.animus.core.recording;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Drops frames a playback would reproduce anyway. A frame goes when its pose lies within {@link #POSITION_TOLERANCE}
 * blocks and {@link #ANGLE_TOLERANCE} degrees of the straight interpolation between the frames kept around it, so every
 * original frame stays within tolerance of the simplified recording. The first and last frames, every frame at an event
 * tick and every frame whose flags differ from the frame before are always kept.
 */
public final class RecordingSimplifier {
    public static final double POSITION_TOLERANCE = 0.02, ANGLE_TOLERANCE = 2;
    /** The most original frames one kept segment spans, which bounds the work to linear in the frame count. */
    static final int MAXIMUM_SPAN = 256;

    private RecordingSimplifier() {}

    /**
     * @param frames frames with strictly increasing ticks
     * @param events the recording's events; frames at their ticks are kept
     * @return the kept frames, in order
     */
    public static List<RecordingFrame> simplify(List<RecordingFrame> frames, List<RecordingEvent> events) {
        for (int i = 1; i < frames.size(); i++)
            if (frames.get(i).tick() <= frames.get(i - 1).tick()) throw new IllegalArgumentException("Frame ticks must strictly increase");
        if (frames.size() <= 2) return List.copyOf(frames);
        Set<Integer> eventTicks = new HashSet<>();
        for (RecordingEvent event : events) eventTicks.add(event.tick());
        int count = frames.size();
        boolean[] pinned = new boolean[count];
        pinned[0] = true;
        pinned[count - 1] = true;
        for (int i = 1; i < count; i++)
            pinned[i] |= frames.get(i).flags() != frames.get(i - 1).flags() || eventTicks.contains(frames.get(i).tick());
        List<RecordingFrame> kept = new ArrayList<>();
        kept.add(frames.getFirst());
        int anchor = 0;
        while (anchor < count - 1) {
            int end = anchor + 1;
            for (int candidate = anchor + 2; candidate < count && candidate - anchor <= MAXIMUM_SPAN && !pinned[candidate - 1]; candidate++) {
                if (!covers(frames, anchor, candidate)) break;
                end = candidate;
            }
            kept.add(frames.get(end));
            anchor = end;
        }
        return List.copyOf(kept);
    }

    /** Whether every frame strictly between {@code from} and {@code to} lies within tolerance of their interpolation. */
    private static boolean covers(List<RecordingFrame> frames, int from, int to) {
        RecordingFrame a = frames.get(from), b = frames.get(to);
        double span = b.tick() - a.tick(), turn = RecordingFrame.yawDelta(a.yaw(), b.yaw());
        for (int i = from + 1; i < to; i++) {
            RecordingFrame m = frames.get(i);
            double u = (m.tick() - a.tick()) / span;
            double x = a.dx() + (b.dx() - a.dx()) * u - m.dx(), y = a.dy() + (b.dy() - a.dy()) * u - m.dy(), z = a.dz() + (b.dz() - a.dz()) * u - m.dz();
            if (x * x + y * y + z * z > POSITION_TOLERANCE * POSITION_TOLERANCE) return false;
            if (Math.abs(RecordingFrame.yawDelta(a.yaw() + turn * u, m.yaw())) > ANGLE_TOLERANCE) return false;
            if (Math.abs(a.pitch() + (b.pitch() - a.pitch()) * u - m.pitch()) > ANGLE_TOLERANCE) return false;
        }
        return true;
    }
}
