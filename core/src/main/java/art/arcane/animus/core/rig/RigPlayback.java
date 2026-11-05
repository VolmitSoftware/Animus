package art.arcane.animus.core.rig;

import art.arcane.animus.core.mount.SeatGeometry;
import art.arcane.animus.core.virtual.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Function;

/**
 * One NPC's animation state on its owner: up to four layers, the rig's idle and walk clips on layer 0 while nothing
 * else plays there, and the segment planner that turns sampled poses into interpolated display updates.
 * <p>
 * Segments start at {@code sampleTicks} and grow while every bone moves linearly within 0.01 blocks and 0.5 degrees,
 * up to {@link #MAX_SEGMENT} ticks; a visibility change, a clip ending or a fade ending closes a segment. Owner only.
 */
public final class RigPlayback {
    public static final int LAYERS = 4, MAX_SEGMENT = 20, MAX_FADE = 40;
    public static final double MIN_SPEED = 0.25, MAX_SPEED = 4;
    private static final double TRANSLATION_TOLERANCE = 0.01, LINEAR_TOLERANCE = Math.sin(Math.toRadians(0.5));

    /** A played clip reached its end: a once clip stopped, or a hold clip now holds its last frame. */
    public record Finished(String animation, int layer) {}

    private static final class Playing {
        final AnimationDocument animation; final double speed; final Optional<AnimationDocument.Loop> loop; final long start; final boolean explicit;
        long fadeStart = -1; int fadeTicks; boolean reported;
        Playing(AnimationDocument animation, double speed, Optional<AnimationDocument.Loop> loop, long start, boolean explicit) {
            this.animation = animation; this.speed = speed; this.loop = loop; this.start = start; this.explicit = explicit;
        }
        RigSampler.Layer at(double tick) {
            double weight = fadeStart < 0 ? 1 : fadeTicks == 0 ? 0 : 1 - (tick - fadeStart) / fadeTicks;
            return new RigSampler.Layer(animation, speed, tick - start, loop, Math.max(0, Math.min(1, weight)));
        }
        boolean faded(long tick) { return fadeStart >= 0 && tick >= fadeStart + fadeTicks; }
    }

    private final RigDocument rig;
    private Function<String, Optional<AnimationDocument>> animations;
    private final SeatGeometry seat;
    private final int[] shown;
    private final Playing[] layers = new Playing[LAYERS];
    private final List<Finished> finished = new ArrayList<>();
    private boolean moving, changed = true;
    private RigFrame frame;
    private float plannedYaw;
    private long sequence;

    /** @param seat the body's passenger point; bones ride there, so their transforms are taken relative to it */
    public RigPlayback(RigDocument rig, Function<String, Optional<AnimationDocument>> animations, SeatGeometry seat) {
        this.rig = Objects.requireNonNull(rig); this.animations = Objects.requireNonNull(animations); this.seat = Objects.requireNonNull(seat);
        shown = rig.displayBones();
        ambient(0);
    }

    public RigDocument rig() { return rig; }

    /** A catalog reload kept this rig: later plays and the idle and walk clips read the new animations. */
    public void animations(Function<String, Optional<AnimationDocument>> lookup, long now) {
        animations = Objects.requireNonNull(lookup);
        if (layers[0] != null && !layers[0].explicit) layers[0] = null;
        if (layers[0] == null) ambient(now);
        changed = true;
    }
    /** The newest planned segment, or null before the first plan. */
    public RigFrame frame() { return frame; }

    public void play(String animation, double speed, Optional<AnimationDocument.Loop> loop, int layer, long now) {
        if (layer < 0 || layer >= LAYERS) throw new IllegalArgumentException("Animation layer must be 0.." + (LAYERS - 1));
        if (!Double.isFinite(speed) || speed < MIN_SPEED || speed > MAX_SPEED) throw new IllegalArgumentException("Animation speed must be 0.25..4");
        AnimationDocument clip = animations.apply(animation).orElseThrow(() -> new IllegalArgumentException("Unknown or invalid animation: " + animation));
        if (!clip.rig().equals(rig.id())) throw new IllegalArgumentException(animation + " animates " + clip.rig() + ", not " + rig.id());
        layers[layer] = new Playing(clip, speed, Objects.requireNonNull(loop), now, true);
        changed = true;
    }

    /** Stops one layer, or every played layer; layer 0 returns to the rig's idle or walk clip. */
    public void stop(OptionalInt layer, int fadeTicks, long now) {
        if (fadeTicks < 0 || fadeTicks > MAX_FADE) throw new IllegalArgumentException("fadeTicks must be 0.." + MAX_FADE);
        if (layer.isPresent() && (layer.getAsInt() < 0 || layer.getAsInt() >= LAYERS)) throw new IllegalArgumentException("Animation layer must be 0.." + (LAYERS - 1));
        for (int i = 0; i < LAYERS; i++) {
            if (layer.isPresent() && layer.getAsInt() != i) continue;
            Playing playing = layers[i];
            if (playing == null || !playing.explicit) continue;
            if (fadeTicks == 0) { layers[i] = null; if (i == 0) ambient(now); }
            else if (playing.fadeStart < 0) { playing.fadeStart = now; playing.fadeTicks = fadeTicks; }
        }
        changed = true;
    }

    /** Navigation reports whether the body is walking; layer 0 switches between idle and walk when it is free. */
    public void moving(boolean walking, long now) {
        if (walking == moving) return;
        moving = walking;
        if (layers[0] == null || !layers[0].explicit) ambient(now);
    }

    private void ambient(long now) {
        Optional<AnimationDocument> clip = rig.animation(moving ? "walk" : "idle").or(() -> moving ? rig.animation("idle") : Optional.empty())
                .flatMap(animations).filter((AnimationDocument document) -> document.rig().equals(rig.id()));
        Playing current = layers[0];
        if (clip.isPresent() && current != null && !current.explicit && current.animation.id().equals(clip.get().id())) return;
        layers[0] = clip.map((AnimationDocument document) -> new Playing(document, 1, Optional.empty(), now, false)).orElse(null);
        changed = true;
    }

    /** Clips that ended and fades that completed by {@code now}; finished played clips are reported once. */
    public List<Finished> drainFinished(long now) {
        for (int i = 0; i < LAYERS; i++) {
            Playing playing = layers[i];
            if (playing == null) continue;
            if (playing.faded(now)) { layers[i] = null; if (i == 0) ambient(now); changed = true; continue; }
            if (!playing.explicit || playing.fadeStart >= 0 || playing.reported || !playing.at(now).ended()) continue;
            playing.reported = true;
            finished.add(new Finished(playing.animation.id(), i));
            if (playing.at(now).mode() == AnimationDocument.Loop.ONCE) { layers[i] = null; if (i == 0) ambient(now); changed = true; }
        }
        List<Finished> result = List.copyOf(finished);
        finished.clear();
        return result;
    }

    /** True when {@link #plan} would make a new segment now. */
    public boolean due(long now, float yaw) {
        return frame == null || changed || yaw != plannedYaw || now >= frame.startTick() + frame.durationTicks();
    }

    /** A new segment from {@code now}, or null while the current one still runs unchanged. */
    public RigFrame plan(long now, float yaw, int sampleTicks, long nanos) {
        if (!due(now, yaw)) return null;
        int step = Math.max(1, Math.min(MAX_SEGMENT, sampleTicks));
        List<Sample> samples = new ArrayList<>();
        samples.add(sample(now, yaw));
        long limit = now + MAX_SEGMENT;
        for (Playing playing : layers) {
            if (playing == null) continue;
            if (playing.fadeStart >= 0) limit = Math.min(limit, Math.max(now + 1, playing.fadeStart + playing.fadeTicks));
            AnimationDocument.Loop mode = playing.loop.orElse(playing.animation.loop());
            if (playing.explicit && (mode == AnimationDocument.Loop.ONCE || mode == AnimationDocument.Loop.HOLD)) {
                long end = playing.start + (long) Math.ceil(playing.animation.durationTicks() / playing.speed);
                if (end > now) limit = Math.min(limit, end);
            }
        }
        int length = (int) Math.min(step, limit - now);
        samples.add(sample(now + length, yaw));
        while (length + step <= limit - now) {
            Sample next = sample(now + length + step, yaw);
            if (!next.sameVisibility(samples.getLast())) break;
            samples.add(next);
            if (!linear(samples)) { samples.removeLast(); break; }
            length += step;
        }
        Sample end = samples.getLast();
        Sample start = samples.getFirst();
        Vec3 point = seat.passengerFeet(Vec3.ZERO, yaw, 0);
        DisplayTransform[] targets = new DisplayTransform[shown.length];
        int[] durations = new int[shown.length];
        long[] revisions = new long[shown.length];
        sequence++;
        for (int i = 0; i < shown.length; i++) {
            Affine matrix = end.matrices[i];
            DisplayTransform target = (end.visible[i] ? DisplayTransform.of(matrix) : DisplayTransform.hidden(matrix)).offset(-point.x(), -point.y(), -point.z());
            targets[i] = target;
            durations[i] = end.visible[i] && start.visible[i] ? length : 0;
            revisions[i] = frame != null && i < frame.bones() && frame.target(i).equals(target) ? frame.revision(i) : sequence;
        }
        frame = new RigFrame(sequence, now, length, nanos, targets, durations, revisions);
        plannedYaw = yaw;
        changed = false;
        return frame;
    }

    private record Sample(Affine[] matrices, boolean[] visible) {
        boolean sameVisibility(Sample other) { return java.util.Arrays.equals(visible, other.visible); }
    }

    private Sample sample(long tick, float yaw) {
        List<RigSampler.Layer> active = new ArrayList<>(LAYERS);
        for (Playing playing : layers) if (playing != null) active.add(playing.at(tick));
        RigSampler.Pose pose = RigSampler.sample(rig, active, yaw);
        Affine[] matrices = new Affine[shown.length];
        boolean[] visible = new boolean[shown.length];
        for (int i = 0; i < shown.length; i++) {
            RigSampler.BonePose bone = pose.bones().get(shown[i]);
            matrices[i] = bone.visual().orElse(bone.model());
            visible[i] = bone.visible();
        }
        return new Sample(matrices, visible);
    }

    /** Every intermediate sample lies on the straight path between the first and the last, within the tolerances. */
    private static boolean linear(List<Sample> samples) {
        Sample first = samples.getFirst(), last = samples.getLast();
        int spans = samples.size() - 1;
        for (int j = 1; j < spans; j++) {
            Sample middle = samples.get(j);
            double u = (double) j / spans;
            for (int bone = 0; bone < first.matrices.length; bone++) {
                Affine expected = first.matrices[bone].lerp(last.matrices[bone], u);
                if (expected.translationDistance(middle.matrices[bone]) > TRANSLATION_TOLERANCE || expected.linearDistance(middle.matrices[bone]) > LINEAR_TOLERANCE) return false;
            }
        }
        return true;
    }

    public String status() {
        StringBuilder text = new StringBuilder("rig=").append(rig.id());
        for (int i = 0; i < LAYERS; i++) {
            Playing playing = layers[i];
            if (playing == null) continue;
            text.append("; layer").append(i).append('=').append(playing.animation.id()).append(playing.explicit ? "" : " (ambient)")
                    .append(playing.fadeStart >= 0 ? " fading" : "");
        }
        return text.toString();
    }
}
