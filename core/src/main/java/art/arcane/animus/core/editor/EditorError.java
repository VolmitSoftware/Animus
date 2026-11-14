package art.arcane.animus.core.editor;

import com.google.gson.JsonArray;
import java.util.Objects;
import java.util.Optional;

/** A refused editor request: an error code of schema/editor-v1.schema.json and the HTTP status the endpoint table gives it. */
public final class EditorError extends Exception {
    public enum Code {
        UNAUTHORIZED("unauthorized", 401), TOKEN_EXPIRED("token-expired", 401), TOKEN_REVOKED("token-revoked", 401),
        SCOPE_MISSING("scope-missing", 403), GROUP_DENIED("group-denied", 403), ORIGIN_NOT_ALLOWED("origin-not-allowed", 403),
        COUNTER_REPLAYED("counter-replayed", 409), PAIRING_INVALID("pairing-invalid", 401), PAIRING_EXPIRED("pairing-expired", 410),
        NOT_FOUND("not-found", 404), INVALID_REQUEST("invalid-request", 400), TOO_LARGE("too-large", 413), RATE_LIMITED("rate-limited", 429),
        BUSY("busy", 503), CONFLICT("conflict", 409), PREVIEW_REFUSED("preview-refused", 422), PLAYER_OFFLINE("player-offline", 409),
        GLOSS_UNAVAILABLE("gloss-unavailable", 422), UNSUPPORTED_PROTOCOL("unsupported-protocol", 400), SERVER_OFFLINE("server-offline", 503),
        TIMEOUT("timeout", 504), INTERNAL("internal", 500), WORLD_UNLOADED("world-unloaded", 404);

        private final String wireName;
        private final int status;

        Code(String wireName, int status) { this.wireName = wireName; this.status = status; }

        public String wireName() { return wireName; }

        public int status() { return status; }
    }

    private final Code code;
    private final long retryAfterMillis;
    private final JsonArray diagnostics;
    private final EditorScope scope;

    public EditorError(Code code, String message) { this(code, message, -1, null); }

    public EditorError(Code code, String message, long retryAfterMillis, JsonArray diagnostics) { this(code, message, retryAfterMillis, diagnostics, null); }

    private EditorError(Code code, String message, long retryAfterMillis, JsonArray diagnostics, EditorScope scope) {
        super(Objects.requireNonNull(message), null, false, false);
        this.code = Objects.requireNonNull(code);
        this.retryAfterMillis = retryAfterMillis;
        this.diagnostics = diagnostics;
        this.scope = scope;
    }

    /** A {@code scope-missing} refusal that names the scope the token lacks. */
    public static EditorError scopeMissing(EditorScope scope, String message) { return new EditorError(Code.SCOPE_MISSING, message, -1, null, Objects.requireNonNull(scope)); }

    public Code code() { return code; }

    /** The scope a {@code scope-missing} refusal lacks. */
    public Optional<EditorScope> scope() { return Optional.ofNullable(scope); }

    public long retryAfterMillis() { return retryAfterMillis; }

    public JsonArray diagnostics() { return diagnostics; }
}
