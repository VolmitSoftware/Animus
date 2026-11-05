package art.arcane.animus.core.editor.transport;

import java.util.concurrent.CompletionStage;

/**
 * The transport-neutral {@code /animus/v1} API. The direct listener and the relay client both call it, so every check
 * (tokens, scopes, counters, origins, limits) lives behind it. Implementations must not block the calling thread on
 * server work: complete the stage from the file lane or the server scheduler.
 */
public interface EditorRequestHandler {
    /** Answers one request other than {@code GET /v1/events}. */
    CompletionStage<EditorResponse> handle(EditorRequest request);

    /**
     * Opens {@code GET /v1/events}. Over the relay the token is in the request's {@code Authorization} header instead of an
     * {@code event-auth} frame. A 2xx {@link EditorEventStream#response()} keeps the stream open and {@code sink} receives
     * events until either side closes it; any other status ends it.
     */
    CompletionStage<EditorEventStream> openEvents(EditorRequest request, EditorEventSink sink);
}
