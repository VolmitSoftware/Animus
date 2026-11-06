package art.arcane.animus.core.recording;

import art.arcane.animus.core.navigation.WaypointSettings;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Objects;
import java.util.Optional;

/**
 * The third movement source of {@code animus:navigation}: a recording the NPC plays, exclusive with {@code route} and
 * inline points. {@code "recording": "<id>", "playback": "once|loop|pingpong"}, playback {@link Playback#DEFAULT} when absent.
 */
public record RecordingSource(String recording, Playback playback) {
    private static final String FIELD = "$.modules.animus:navigation";

    public RecordingSource {
        Objects.requireNonNull(playback);
        if (recording == null || !recording.matches(RecordingDocument.ID)) throw new IllegalArgumentException("Invalid recording identifier");
    }

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
        Playback playback = Playback.DEFAULT;
        if (data.has("playback")) {
            JsonElement mode = data.get("playback");
            Optional<Playback> parsed = mode.isJsonPrimitive() && mode.getAsJsonPrimitive().isString() ? Playback.parse(mode.getAsString()) : Optional.empty();
            playback = parsed.orElseThrow(() -> new IllegalArgumentException(FIELD + ".playback: expected once, loop or pingpong"));
        }
        return Optional.of(new RecordingSource(reference.getAsString(), playback));
    }
}
