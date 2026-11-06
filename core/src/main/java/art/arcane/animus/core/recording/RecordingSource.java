package art.arcane.animus.core.recording;

import art.arcane.animus.core.navigation.WaypointSettings;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Objects;
import java.util.Optional;

/**
 * The third movement source of {@code animus:navigation}: a recording the NPC plays, exclusive with {@code route} and
 * inline points. {@code "recording": "<id>", "playback": "once|loop|pingpong"}; without playback the recording's own
 * {@code playback} decides, see {@link #mode}.
 */
public record RecordingSource(String recording, Optional<Playback> playback) {
    private static final String FIELD = "$.modules.animus:navigation";

    public RecordingSource {
        Objects.requireNonNull(playback);
        if (recording == null || !recording.matches(RecordingDocument.ID)) throw new IllegalArgumentException("Invalid recording identifier");
    }

    /** The mode this source plays {@code document} in: its own playback, else the document's, else {@link Playback#DEFAULT}. */
    public Playback mode(RecordingDocument document) { return playback.or(() -> document.details().playback()).orElse(Playback.DEFAULT); }

    /**
     * The recording an enabled navigation module plays, or empty when it plays none.
     *
     * @throws IllegalArgumentException naming the field as {@code $.modules.animus:navigation.<field>: message}
     */
    public static Optional<RecordingSource> parse(JsonObject definition) {
        JsonElement modules = definition.get("modules");
        if (modules == null || !modules.isJsonObject()) return Optional.empty();
        JsonElement module = modules.getAsJsonObject().get("animus:navigation");
        if (module == null || !module.isJsonObject()) return Optional.empty();
        JsonObject data = module.getAsJsonObject();
        JsonElement enabled = data.get("enabled");
        if (enabled == null || !enabled.isJsonPrimitive() || !enabled.getAsJsonPrimitive().isBoolean() || !enabled.getAsBoolean()) return Optional.empty();
        if (!data.has("recording")) {
            if (data.has("playback")) throw new IllegalArgumentException(FIELD + ".playback: requires recording");
            return Optional.empty();
        }
        JsonElement reference = data.get("recording");
        if (!reference.isJsonPrimitive() || !reference.getAsJsonPrimitive().isString() || !reference.getAsString().matches(RecordingDocument.ID))
            throw new IllegalArgumentException(FIELD + ".recording: expected 1-64 lowercase letters, digits, _ or -");
        if (data.has("route")) throw new IllegalArgumentException(FIELD + ".recording: cannot be combined with route");
        for (String conflicting : WaypointSettings.ROUTE_FIELDS)
            if (data.has(conflicting)) throw new IllegalArgumentException(FIELD + ".recording: cannot be combined with " + conflicting);
        Optional<Playback> playback = Optional.empty();
        if (data.has("playback")) {
            JsonElement mode = data.get("playback");
            Optional<Playback> parsed = mode.isJsonPrimitive() && mode.getAsJsonPrimitive().isString() ? Playback.parse(mode.getAsString()) : Optional.empty();
            playback = Optional.of(parsed.orElseThrow(() -> new IllegalArgumentException(FIELD + ".playback: expected once, loop or pingpong")));
        }
        return Optional.of(new RecordingSource(reference.getAsString(), playback));
    }
}
