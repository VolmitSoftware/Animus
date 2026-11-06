package art.arcane.animus.core.recording;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RecordingSimplifierTest {
    private static RecordingFrame frame(int tick, double dx, double yaw, int flags) { return new RecordingFrame(tick, dx, 0, 0, yaw, 0, flags); }

    @Test void straightConstantMotionKeepsOnlyTheEnds() {
        List<RecordingFrame> frames = new ArrayList<>();
        for (int i = 0; i <= 20; i++) frames.add(frame(i * 2, i * 0.5, 10 + i, 1));
        List<RecordingFrame> simplified = RecordingSimplifier.simplify(frames, List.of());
        assertEquals(List.of(frames.getFirst(), frames.getLast()), simplified);
    }

    @Test void cornersFlagChangesAndEventTicksAreKept() {
        List<RecordingFrame> frames = List.of(frame(0, 0, 0, 1), frame(2, 1, 0, 1), frame(4, 2, 0, 1), frame(6, 2, 0, 1), frame(8, 2, 0, 3),
                frame(10, 2, 0, 3), frame(12, 2, 0, 3), frame(14, 2, 0, 3));
        List<RecordingFrame> simplified = RecordingSimplifier.simplify(frames, List.of(new RecordingEvent.Swing(12), new RecordingEvent.Swing(13)));
        assertEquals(List.of(frames.get(0), frames.get(2), frames.get(4), frames.get(6), frames.get(7)), simplified);
    }

    @Test void smallWobblesWithinToleranceAreDropped() {
        List<RecordingFrame> frames = List.of(frame(0, 0, 0, 0), frame(2, 1.01, 1.5, 0), frame(4, 2, 0, 0));
        assertEquals(2, RecordingSimplifier.simplify(frames, List.of()).size());
        List<RecordingFrame> turning = List.of(frame(0, 0, 0, 0), frame(2, 1, 2.5, 0), frame(4, 2, 0, 0));
        assertEquals(3, RecordingSimplifier.simplify(turning, List.of()).size());
    }

    @Test void yawToleranceUsesTheShortArc() {
        List<RecordingFrame> frames = List.of(frame(0, 0, 179, 0), frame(2, 0, -180, 0), frame(4, 0, -179, 0));
        assertEquals(2, RecordingSimplifier.simplify(frames, List.of()).size());
    }

    @Test void unorderedFramesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> RecordingSimplifier.simplify(List.of(frame(2, 0, 0, 0), frame(2, 0, 0, 0)), List.of()));
    }

    @Test void randomWalksStayWithinToleranceAfterSimplification() {
        Random random = new Random(7);
        for (int run = 0; run < 150; run++) {
            List<RecordingFrame> frames = new ArrayList<>();
            List<RecordingEvent> events = new ArrayList<>();
            double x = 0, y = 0, z = 0, yaw = random.nextDouble() * 360 - 180, pitch = 0;
            int flags = 1, sample = 1 + random.nextInt(3), count = 2 + random.nextInt(400);
            double vx = random.nextGaussian() * 0.2, vz = random.nextGaussian() * 0.2;
            for (int i = 0; i < count; i++) {
                frames.add(new RecordingFrame(i * sample, x, y, z, yaw, pitch, flags));
                if (random.nextInt(8) == 0) { vx = random.nextGaussian() * 0.3; vz = random.nextGaussian() * 0.3; }
                x += vx + random.nextGaussian() * 0.005;
                z += vz + random.nextGaussian() * 0.005;
                y += random.nextInt(20) == 0 ? random.nextGaussian() : 0;
                yaw += random.nextGaussian() * (random.nextInt(4) == 0 ? 20 : 0.3);
                if (yaw >= 180) yaw -= 360;
                if (yaw < -180) yaw += 360;
                pitch = Math.max(-90, Math.min(90, pitch + random.nextGaussian()));
                if (random.nextInt(30) == 0) flags ^= RecordingFrame.SNEAKING;
                if (i + 1 < count && random.nextInt(50) == 0) events.add(new RecordingEvent.Swing((i + 1) * sample));
            }
            List<RecordingFrame> simplified = RecordingSimplifier.simplify(frames, events);
            assertEquals(frames.getFirst(), simplified.getFirst());
            assertEquals(frames.getLast(), simplified.getLast());
            assertTrue(simplified.size() <= frames.size());
            RecordingDocument document = new RecordingDocument(1, "walk", java.util.UUID.randomUUID(), "world",
                    new RecordingDocument.Origin(0, 64, 0, 0, 0), sample, frames.getLast().tick(), simplified, events);
            for (RecordingFrame original : frames) {
                RecordingPlayback playback = new RecordingPlayback(document, Playback.ONCE, original.tick());
                RecordingPose pose = playback.pose();
                double distance = Math.sqrt(Math.pow(pose.x() - original.dx(), 2) + Math.pow(pose.y() - 64 - original.dy(), 2) + Math.pow(pose.z() - original.dz(), 2));
                assertTrue(distance <= RecordingSimplifier.POSITION_TOLERANCE + 1e-9, "run " + run + " tick " + original.tick() + " off by " + distance);
                assertTrue(Math.abs(RecordingFrame.yawDelta(original.yaw(), pose.yaw())) <= RecordingSimplifier.ANGLE_TOLERANCE + 1e-9, "run " + run + " yaw at " + original.tick());
                assertTrue(Math.abs(original.pitch() - pose.pitch()) <= RecordingSimplifier.ANGLE_TOLERANCE + 1e-9, "run " + run + " pitch at " + original.tick());
                assertEquals(original.flags(), pose.flags(), "run " + run + " flags at " + original.tick());
            }
            for (RecordingEvent event : events) {
                int tick = event.tick();
                if (frames.stream().anyMatch((RecordingFrame f) -> f.tick() == tick))
                    assertTrue(simplified.stream().anyMatch((RecordingFrame f) -> f.tick() == tick), "event frame kept at " + tick);
            }
        }
    }
}
