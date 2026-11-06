package art.arcane.animus.core.rig;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Samples a rig's bones for a list of playing layers at one moment. Pure and platform free; the browser editor's Dart
 * sampler implements the same rules, pinned by {@code schema/fixtures/animation/samples/}. See dev/editor-contracts.md.
 */
public final class RigSampler {
    private RigSampler() {}

    /**
     * One playing clip. {@code elapsedTicks} is the time since the layer started, {@code loop} overrides the clip's loop
     * mode, and {@code weight} (0..1) fades the layer's contribution; 1 is the contract's plain behaviour.
     */
    public record Layer(AnimationDocument animation, double speed, double elapsedTicks, Optional<AnimationDocument.Loop> loop, double weight) {
        public Layer { Objects.requireNonNull(animation); Objects.requireNonNull(loop); }
        public static Layer of(AnimationDocument animation, double speed) { return new Layer(animation, speed, 0, Optional.empty(), 1); }
        public Layer at(double elapsed) { return new Layer(animation, speed, elapsed, loop, weight); }
        public Layer weighted(double fade) { return new Layer(animation, speed, elapsedTicks, loop, fade); }
        public AnimationDocument.Loop mode() { return loop.orElse(animation.loop()); }
        /** Clip time after speed and loop mode. */
        public double clipTime() { return RigSampler.clipTime(elapsedTicks * speed, animation.durationTicks(), mode()); }
        /** A once or hold layer has reached its last frame. */
        public boolean ended() {
            AnimationDocument.Loop mode = mode();
            return (mode == AnimationDocument.Loop.ONCE || mode == AnimationDocument.Loop.HOLD) && elapsedTicks * speed >= animation.durationTicks();
        }
    }
    /** Channels are relative to the rest pose; {@code visible} includes every ancestor. */
    public record BonePose(String id, double[] rotation, double[] translation, double[] scale, boolean visible, Affine model, Optional<Affine> visual) {
        @Override public double[] rotation() { return rotation.clone(); }
        @Override public double[] translation() { return translation.clone(); }
        @Override public double[] scale() { return scale.clone(); }
    }
    public record Pose(List<BonePose> bones) {
        public Pose { bones = List.copyOf(bones); }
        public BonePose bone(String id) {
            for (BonePose bone : bones) if (bone.id().equals(id)) return bone;
            throw new IllegalArgumentException("No bone " + id);
        }
    }

    public static double clipTime(double t, double duration, AnimationDocument.Loop loop) {
        return switch (loop) {
            case ONCE, HOLD -> Math.min(Math.max(t, 0), duration);
            case LOOP -> t - duration * Math.floor(t / duration);
            case PINGPONG -> {
                double p = t - 2 * duration * Math.floor(t / (2 * duration));
                yield p <= duration ? p : 2 * duration - p;
            }
        };
    }

    /** @param yaw the body yaw in Minecraft degrees (0 faces +Z, 90 faces -X) */
    public static Pose sample(RigDocument rig, List<Layer> layers, double yaw) {
        int count = rig.bones().size();
        double[][] rotation = new double[count][3], translation = new double[count][3], scale = new double[count][];
        boolean[] visible = new boolean[count];
        for (int i = 0; i < count; i++) { scale[i] = new double[]{1, 1, 1}; visible[i] = true; }
        for (Layer layer : layers) {
            double time = layer.clipTime(), weight = Math.max(0, Math.min(1, layer.weight()));
            if (weight == 0) continue;
            for (AnimationDocument.Track track : layer.animation().tracks()) {
                int bone = rig.index(track.bone());
                if (bone < 0) continue;
                double[] value = value(track, time);
                switch (track.target()) {
                    case VISIBLE -> { if (weight >= 0.5) visible[bone] = value[0] != 0; }
                    case ROTATION -> blend(rotation[bone], value, track.blend(), weight);
                    case TRANSLATION -> blend(translation[bone], value, track.blend(), weight);
                    case SCALE -> blend(scale[bone], value, track.blend(), weight);
                }
            }
        }
        Affine[] models = new Affine[count];
        boolean[] shown = new boolean[count];
        List<BonePose> poses = new ArrayList<>(count);
        Affine base = Affine.rotationY(-yaw);
        for (int i = 0; i < count; i++) {
            RigDocument.Bone bone = rig.bones().get(i);
            double[] pivot = bone.pivot(), parentPivot = bone.parent() < 0 ? new double[3] : rig.bones().get(bone.parent()).pivot();
            Affine local = Affine.translation(pivot[0] - parentPivot[0], pivot[1] - parentPivot[1], pivot[2] - parentPivot[2])
                    .times(Affine.translation(translation[i])).times(Affine.rotation(bone.rotation())).times(Affine.rotation(rotation[i]))
                    .times(Affine.scale(scale[i]));
            models[i] = (bone.parent() < 0 ? base : models[bone.parent()]).times(local);
            shown[i] = visible[i] && (bone.parent() < 0 || shown[bone.parent()]);
            Optional<Affine> visual = bone.shown() ? Optional.of(models[i].times(bone.visual().transform())) : Optional.empty();
            poses.add(new BonePose(bone.id(), rotation[i], translation[i], scale[i], shown[i], models[i], visual));
        }
        return new Pose(poses);
    }

    private static void blend(double[] channel, double[] value, AnimationDocument.Blend blend, double weight) {
        for (int c = 0; c < 3; c++) {
            channel[c] = switch (blend) {
                case REPLACE -> channel[c] + (value[c] - channel[c]) * weight;
                case ADD -> channel[c] + value[c] * weight;
                case MULTIPLY -> channel[c] * (1 + (value[c] - 1) * weight);
            };
        }
    }

    /** The track's value at clip time: held outside the keyframes, eased between them, stepped for visibility. */
    static double[] value(AnimationDocument.Track track, double time) {
        List<AnimationDocument.Keyframe> keys = track.keyframes();
        AnimationDocument.Keyframe first = keys.getFirst(), last = keys.getLast();
        if (time <= first.tick()) return first.value();
        if (time >= last.tick()) return last.value();
        int low = 0, high = keys.size() - 1;
        while (high - low > 1) {
            int middle = (low + high) >>> 1;
            if (keys.get(middle).tick() <= time) low = middle; else high = middle;
        }
        AnimationDocument.Keyframe a = keys.get(low), b = keys.get(high);
        if (track.target() == AnimationDocument.Target.VISIBLE) return a.value();
        double progress = a.easing().apply((time - a.tick()) / (b.tick() - a.tick()));
        double[] result = new double[3];
        for (int c = 0; c < 3; c++) result[c] = a.component(c) + (b.component(c) - a.component(c)) * progress;
        return result;
    }
}
