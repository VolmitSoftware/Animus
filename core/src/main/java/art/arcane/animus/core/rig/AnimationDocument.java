package art.arcane.animus.core.rig;

import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * {@code animations/<namespace>/<path>.json}: keyframe tracks over a rig's bones, validated against
 * {@code schema/animation-v1.schema.json} plus strictly increasing keyframe ticks within the duration. Sampling follows
 * dev/editor-contracts.md, in {@link RigSampler}.
 */
public record AnimationDocument(String id, long revision, String rig, int durationTicks, Loop loop, List<Track> tracks, List<Event> events, String source) {
    public static final int MAX_DURATION = 1200, MAX_TRACKS = 64, MAX_KEYFRAMES = 256, MAX_EVENTS = 256;
    private static final Set<String> FIELDS = Set.of("schemaVersion", "revision", "id", "rig", "durationTicks", "loop", "tracks", "events");
    private static final Set<String> TRACK_FIELDS = Set.of("bone", "target", "blend", "keyframes");
    private static final Set<String> KEYFRAME_FIELDS = Set.of("tick", "value", "easing");
    private static final Set<String> EVENT_FIELDS = Set.of("tick", "capability", "arguments");
    private static final double LIMIT = 4096;

    public enum Loop { ONCE, LOOP, PINGPONG, HOLD;
        public String key() { return name().toLowerCase(Locale.ROOT); }
        public static Optional<Loop> parse(String text) {
            for (Loop loop : values()) if (loop.key().equals(text)) return Optional.of(loop);
            return Optional.empty();
        }
    }
    public enum Target { ROTATION, TRANSLATION, SCALE, VISIBLE }
    public enum Blend { REPLACE, ADD, MULTIPLY }
    /** {@code value} holds three numbers, or one number (1 or 0) for a visible track. */
    public record Keyframe(double tick, double[] value, Easing easing) {
        public Keyframe { value = value.clone(); Objects.requireNonNull(easing); }
        @Override public double[] value() { return value.clone(); }
        double component(int index) { return value[index]; }
    }
    public record Track(String bone, Target target, Blend blend, List<Keyframe> keyframes) {
        public Track { Objects.requireNonNull(bone); keyframes = List.copyOf(keyframes); }
    }
    /** A sound or particle capability run at a tick. Arguments are the capability's own JSON object. */
    public record Event(int tick, String capability, String arguments) {}
    public record Result(Optional<AnimationDocument> document, List<String> problems) {
        public Result { Objects.requireNonNull(document); problems = List.copyOf(problems); }
    }

    public AnimationDocument { tracks = List.copyOf(tracks); events = List.copyOf(events); }

    /** Problems against the rig the document names: the rig id and every track's bone. */
    public List<String> problemsAgainst(RigDocument document) {
        List<String> problems = new ArrayList<>();
        if (!document.id().equals(rig)) problems.add("$.rig: names " + rig + ", not " + document.id());
        for (int i = 0; i < tracks.size(); i++)
            if (document.index(tracks.get(i).bone()) < 0) problems.add("$.tracks[" + i + "].bone: " + tracks.get(i).bone() + " is not a bone of " + document.id());
        return List.copyOf(problems);
    }

    public static Result parse(String expectedId, String source) {
        List<String> problems = new ArrayList<>();
        if (source.getBytes(StandardCharsets.UTF_8).length > RigFields.MAX_BYTES) return new Result(Optional.empty(), List.of("$: exceeds 256 KiB"));
        JsonObject root;
        try { root = JsonDocuments.object(source); }
        catch (IOException invalid) { return new Result(Optional.empty(), List.of("$: " + invalid.getMessage())); }
        RigFields.keys(root, FIELDS, "$", problems);
        RigFields.schemaVersion(root, problems);
        long revision = RigFields.revision(root, problems);
        Optional<String> id = RigFields.documentId(root, "id", "$", problems);
        if (id.isPresent() && expectedId != null && !id.get().equals(expectedId)) problems.add("$.id: expected " + expectedId + " to match the file's location");
        Optional<String> rig = RigFields.documentId(root, "rig", "$", problems);
        int duration = RigFields.integer(root.get("durationTicks"), 1, MAX_DURATION, "$.durationTicks", problems, 1);
        Loop loop = Loop.ONCE;
        if (root.has("loop")) loop = RigFields.choice(root, "loop", "$", Set.of("once", "loop", "pingpong", "hold"), problems).flatMap(Loop::parse).orElse(Loop.ONCE);
        List<Track> tracks = new ArrayList<>();
        JsonElement trackList = root.get("tracks");
        if (trackList == null || !trackList.isJsonArray() || trackList.getAsJsonArray().size() > MAX_TRACKS) problems.add("$.tracks: expected at most " + MAX_TRACKS + " tracks");
        else for (int i = 0; i < trackList.getAsJsonArray().size(); i++)
            track(trackList.getAsJsonArray().get(i), "$.tracks[" + i + "]", duration, problems).ifPresent(tracks::add);
        List<Event> events = new ArrayList<>();
        if (root.has("events")) {
            JsonElement eventList = root.get("events");
            if (!eventList.isJsonArray() || eventList.getAsJsonArray().size() > MAX_EVENTS) problems.add("$.events: expected at most " + MAX_EVENTS + " events");
            else for (int i = 0; i < eventList.getAsJsonArray().size(); i++)
                event(eventList.getAsJsonArray().get(i), "$.events[" + i + "]", duration, problems).ifPresent(events::add);
        }
        if (!problems.isEmpty() || id.isEmpty() || rig.isEmpty()) return new Result(Optional.empty(), problems);
        return new Result(Optional.of(new AnimationDocument(id.get(), revision, rig.get(), duration, loop, tracks, events, source)), List.of());
    }

    private static Optional<Track> track(JsonElement element, String path, int duration, List<String> problems) {
        if (!element.isJsonObject()) { problems.add(path + ": expected object"); return Optional.empty(); }
        JsonObject track = element.getAsJsonObject();
        int before = problems.size();
        RigFields.keys(track, TRACK_FIELDS, path, problems);
        Optional<String> bone = RigFields.boneId(track.get("bone"), path + ".bone", problems);
        Optional<Target> target = RigFields.choice(track, "target", path, Set.of("rotation", "translation", "scale", "visible"), problems)
                .map((String value) -> Target.valueOf(value.toUpperCase(Locale.ROOT)));
        Blend blend = Blend.REPLACE;
        if (track.has("blend")) blend = RigFields.choice(track, "blend", path, Set.of("replace", "add", "multiply"), problems)
                .map((String value) -> Blend.valueOf(value.toUpperCase(Locale.ROOT))).orElse(Blend.REPLACE);
        if (target.isPresent() && target.get() == Target.VISIBLE && blend != Blend.REPLACE) problems.add(path + ".blend: visible tracks always replace");
        List<Keyframe> keyframes = new ArrayList<>();
        JsonElement list = track.get("keyframes");
        if (list == null || !list.isJsonArray() || list.getAsJsonArray().isEmpty() || list.getAsJsonArray().size() > MAX_KEYFRAMES) {
            problems.add(path + ".keyframes: expected 1.." + MAX_KEYFRAMES + " keyframes");
        } else if (target.isPresent()) {
            double previous = -1;
            for (int i = 0; i < list.getAsJsonArray().size(); i++) {
                String at = path + ".keyframes[" + i + "]";
                JsonElement entry = list.getAsJsonArray().get(i);
                if (!entry.isJsonObject()) { problems.add(at + ": expected object"); continue; }
                JsonObject keyframe = entry.getAsJsonObject();
                RigFields.keys(keyframe, KEYFRAME_FIELDS, at, problems);
                JsonElement tickValue = keyframe.get("tick");
                if (!RigFields.number(tickValue) || !Double.isFinite(tickValue.getAsDouble()) || tickValue.getAsDouble() < 0 || tickValue.getAsDouble() > duration) {
                    problems.add(at + ".tick: expected a tick within 0.." + duration);
                    continue;
                }
                double tick = tickValue.getAsDouble();
                if (tick <= previous) problems.add(at + ".tick: keyframe ticks must strictly increase");
                previous = tick;
                double[] value;
                if (target.get() == Target.VISIBLE) {
                    if (!RigFields.bool(keyframe.get("value"))) { problems.add(at + ".value: expected boolean"); continue; }
                    value = new double[]{keyframe.get("value").getAsBoolean() ? 1 : 0};
                } else {
                    value = RigFields.vector(keyframe.get("value"), at + ".value", LIMIT, problems);
                    if (value == null) continue;
                }
                Easing easing = Easing.LINEAR;
                if (keyframe.has("easing")) {
                    JsonElement text = keyframe.get("easing");
                    Optional<Easing> parsed = RigFields.string(text) ? Easing.parse(text.getAsString()) : Optional.empty();
                    if (parsed.isEmpty()) problems.add(at + ".easing: expected linear, hold, ease_in, ease_out, ease_in_out, back_out or bezier(x1,y1,x2,y2)");
                    else easing = parsed.get();
                }
                keyframes.add(new Keyframe(tick, value, easing));
            }
        }
        if (problems.size() != before || bone.isEmpty() || target.isEmpty()) return Optional.empty();
        return Optional.of(new Track(bone.get(), target.get(), blend, keyframes));
    }

    private static Optional<Event> event(JsonElement element, String path, int duration, List<String> problems) {
        if (!element.isJsonObject()) { problems.add(path + ": expected object"); return Optional.empty(); }
        JsonObject event = element.getAsJsonObject();
        int before = problems.size();
        RigFields.keys(event, EVENT_FIELDS, path, problems);
        int tick = RigFields.integer(event.get("tick"), 0, duration, path + ".tick", problems, 0);
        Optional<String> capability = RigFields.choice(event, "capability", path, Set.of("animus:play-sound", "animus:spawn-particle"), problems);
        String arguments = "{}";
        if (event.has("arguments")) {
            if (!event.get("arguments").isJsonObject()) problems.add(path + ".arguments: expected object");
            else arguments = event.get("arguments").toString();
        }
        if (problems.size() != before || capability.isEmpty()) return Optional.empty();
        return Optional.of(new Event(tick, capability.get(), arguments));
    }
}
