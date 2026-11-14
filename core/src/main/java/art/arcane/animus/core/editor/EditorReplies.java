package art.arcane.animus.core.editor;

import art.arcane.animus.core.editor.transport.EditorRequest;
import art.arcane.animus.core.editor.transport.EditorResponse;
import com.google.gson.JsonObject;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Error bodies with diagnostics and retry hints, header merging, the query string and the caller's address. */
public final class EditorReplies {
    /** Set by the direct listener from the socket; any copy sent by a client is dropped first. */
    public static final String PEER_HEADER = "X-Animus-Peer";

    private EditorReplies() {}

    public static EditorResponse error(EditorError error) {
        JsonObject body = new JsonObject(), detail = new JsonObject();
        detail.addProperty("code", error.code().wireName());
        String message = error.getMessage().isBlank() ? error.code().wireName() : error.getMessage();
        detail.addProperty("message", message.length() > 2048 ? message.substring(0, 2048) : message);
        error.scope().ifPresent((EditorScope scope) -> detail.addProperty("scope", scope.wireName()));
        if (error.diagnostics() != null) detail.add("diagnostics", error.diagnostics());
        if (error.retryAfterMillis() >= 0) detail.addProperty("retryAfterMillis", error.retryAfterMillis());
        body.add("error", detail);
        Map<String, String> headers = error.retryAfterMillis() >= 0
                ? Map.of("Retry-After", Long.toString(Math.max(1, (error.retryAfterMillis() + 999) / 1000))) : Map.of();
        return new EditorResponse(error.code().status(), headers, Optional.of(body));
    }

    public static EditorResponse empty(int status) { return new EditorResponse(status, Map.of(), Optional.empty()); }

    public static EditorResponse withHeaders(EditorResponse response, Map<String, String> extra) {
        Map<String, String> merged = new LinkedHashMap<>(response.headers());
        merged.putAll(extra);
        return new EditorResponse(response.status(), merged, response.body());
    }

    /** The decoded query string; repeated keys keep the first value. */
    public static Map<String, String> query(EditorRequest request) throws EditorError {
        Map<String, String> query = new LinkedHashMap<>();
        int question = request.path().indexOf('?');
        if (question < 0) return query;
        for (String pair : request.path().substring(question + 1).split("&")) {
            if (pair.isEmpty()) continue;
            int equals = pair.indexOf('=');
            try {
                String key = URLDecoder.decode(equals < 0 ? pair : pair.substring(0, equals), StandardCharsets.UTF_8);
                String value = equals < 0 ? "" : URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
                query.putIfAbsent(key, value);
            } catch (IllegalArgumentException invalid) {
                throw new EditorError(EditorError.Code.INVALID_REQUEST, "The query string is not URL encoded.");
            }
        }
        return query;
    }

    /** The browser's address: the socket's on the direct path, the relay's X-Forwarded-For on the relay path. */
    public static String address(EditorRequest request) {
        String value = request.transport() == EditorRequest.Transport.DIRECT
                ? request.header(PEER_HEADER).orElse("unknown") : request.header("X-Forwarded-For").map((String list) -> list.split(",")[0].strip()).orElse("relay");
        return value.length() > 64 ? value.substring(0, 64) : value;
    }
}
