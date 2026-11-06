package art.arcane.animus.core.recording;

import art.arcane.animus.core.navigation.WaypointSettings;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * The recordings a route's steps play, read once per route load and kept with the route. A recording that could not be
 * read keeps its error instead, and a step that plays it is a blocked leg with that error.
 */
public record RouteRecordings(Map<String, RecordingDocument> documents, Map<String, String> errors) {
    public static final RouteRecordings NONE = new RouteRecordings(Map.of(), Map.of());

    /** Reads one recording by id within the live byte bound; throws with the reason it cannot be played. */
    @FunctionalInterface
    public interface Reader { RecordingDocument read(String recording) throws IOException; }

    /** A step that can start, or the reason it cannot. */
    public record Start(RecordingRun run, String problem) {}

    public RouteRecordings {
        documents = Map.copyOf(documents); errors = Map.copyOf(errors);
    }

    /** Reads each distinct recording the route's steps name, in point order. Call from the runtime's admitted I/O lane. */
    public static RouteRecordings read(WaypointSettings settings, Reader reader) {
        if (settings.recordings().isEmpty()) return NONE;
        Map<String, RecordingDocument> documents = new LinkedHashMap<>();
        Map<String, String> errors = new LinkedHashMap<>();
        for (String recording : settings.recordings()) {
            try { documents.put(recording, reader.read(recording)); }
            catch (IOException | RuntimeException failure) {
                errors.put(recording, failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
            }
        }
        return new RouteRecordings(documents, errors);
    }

    /** The recording changed between two loads of the same route: read differently, appeared or failed. */
    public boolean changed(RouteRecordings previous, String recording) {
        return !Objects.equals(documents.get(recording), previous.documents.get(recording)) || !Objects.equals(errors.get(recording), previous.errors.get(recording));
    }

    /**
     * The run for route point {@code index}, a step, played by a body standing in {@code world}; or why it cannot play: an
     * unread or unreadable recording, another or an unloaded world, or a range the recording does not hold.
     */
    public Start start(int index, WaypointSettings.Step step, String world, Function<String, Optional<String>> resolve) {
        String recording = step.recording();
        if (errors.containsKey(recording)) return new Start(null, "recording " + recording + ": " + errors.get(recording));
        RecordingDocument document = documents.get(recording);
        if (document == null) return new Start(null, "recording " + recording + " has not been read");
        Optional<String> refusal = RecordingWorlds.refusal(document, world, resolve).or(() -> step.problem(index, document.durationTicks()));
        if (refusal.isPresent()) return new Start(null, refusal.get());
        return new Start(RecordingRun.step(document, index, step.fromTick(), step.end(document.durationTicks())), null);
    }
}
