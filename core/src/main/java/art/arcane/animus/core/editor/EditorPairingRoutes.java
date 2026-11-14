package art.arcane.animus.core.editor;

import art.arcane.animus.core.BuildIdentity;
import art.arcane.animus.core.config.EditorNotifySettings;
import art.arcane.animus.core.editor.transport.EditorRequest;
import art.arcane.animus.core.editor.transport.EditorResponse;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The open endpoints {@code GET /v1/hello} and {@code POST /v1/pair}, the per-address pairing rate, and what a failed pairing
 * tells the console and the player who made the link.
 */
final class EditorPairingRoutes {
    private static final int PAIR_WINDOW_ADDRESSES = 4096;
    private static final String SHARED_PAIR_WINDOW = "";
    private final EditorApi api;
    private final Map<String, long[]> pairWindows = new ConcurrentHashMap<>();

    EditorPairingRoutes(EditorApi api) { this.api = api; }

    JsonObject hello() {
        EditorApi.Parts parts = api.parts();
        JsonObject body = new JsonObject();
        body.addProperty("protocol", EditorApi.PROTOCOL);
        JsonArray protocols = new JsonArray();
        protocols.add(EditorApi.PROTOCOL);
        body.add("protocols", protocols);
        body.addProperty("fingerprint", parts.identity().fingerprint());
        body.addProperty("animus", BuildIdentity.VERSION.length() > 64 ? BuildIdentity.VERSION.substring(0, 64) : BuildIdentity.VERSION);
        body.addProperty("platform", parts.host().platform());
        String minecraft = parts.minecraftVersion();
        body.addProperty("minecraft", minecraft.matches("\\d+\\.\\d+(\\.\\d+)?") ? minecraft : "0.0");
        body.addProperty("serverName", serverName());
        return body;
    }

    String serverName() {
        String configured = api.parts().settings().serverName();
        return EditorServerName.bounded(configured.isBlank() ? api.parts().host().serverName() : configured);
    }

    EditorResponse pair(EditorRequest request) throws EditorError {
        EditorApi.Parts parts = api.parts();
        rate(EditorReplies.address(request));
        JsonObject body = EditorMessages.body(request);
        EditorMessages.fields(body, "$", Set.of("tokenId", "secret", "client"));
        String tokenId = EditorMessages.string(body, "tokenId", EditorMessages.TOKEN_ID, "$.tokenId");
        String secret = EditorMessages.string(body, "secret", EditorMessages.SECRET, "$.secret");
        Optional<String> client = EditorMessages.optionalString(body, "client", 128);
        Map.Entry<String, EditorTokens.Token> issued;
        Optional<EditorTokens.Open> waiting = parts.tokens().pairing(tokenId);
        try { issued = parts.tokens().exchange(tokenId, secret, client); }
        catch (IOException failure) { throw new EditorError(EditorError.Code.INTERNAL, "The token store could not be written."); }
        catch (EditorError refused) {
            api.audit("pair", Optional.of(tokenId), Optional.empty(), request, List.of(), refused.code().wireName());
            String address = EditorReplies.address(request);
            if (refused.code() == EditorError.Code.PAIRING_EXPIRED) {
                parts.warnings().once("pair " + tokenId, Duration.ofHours(1), "Editor pairing " + tokenId + " failed: the link expired before it was opened; run /animus editor pair again");
                waiting.ifPresent((EditorTokens.Open open) -> refused(open, "the link expired; make a new one"));
            } else parts.warnings().once("pair unknown " + address, Duration.ofMinutes(1), "Editor pairing from " + address + " failed: the link is unknown, used or revoked");
            throw refused;
        }
        EditorTokens.Token token = issued.getValue();
        api.audit("pair", Optional.of(token.id()), token.admin(), request, List.of(), "paired");
        parts.notices().record(new EditorNotices.Event(EditorNotifySettings.Kind.PAIR, EditorNotices.Who.of(token), "paired", "", List.of(), Optional.empty(), List.of()));
        JsonObject response = new JsonObject();
        response.addProperty("token", issued.getKey());
        response.addProperty("tokenId", token.id());
        JsonArray scopes = new JsonArray();
        token.scopes().forEach((EditorScope scope) -> scopes.add(scope.wireName()));
        response.add("scopes", scopes);
        if (!token.groups().isEmpty()) {
            JsonArray groups = new JsonArray();
            token.groups().forEach(groups::add);
            response.add("groups", groups);
        }
        response.addProperty("expiresAt", EditorMessages.instant(token.expires()));
        response.addProperty("fingerprint", parts.identity().fingerprint());
        response.addProperty("counter", token.counter());
        token.admin().ifPresent((UUID admin) -> response.addProperty("admin", admin.toString()));
        response.addProperty("serverName", serverName());
        return EditorResponse.json(200, response);
    }

    /** Tells the player who made a pairing link, in game, that a browser could not use it. */
    void refused(EditorTokens.Open pairing, String why) {
        EditorNotices.Who who = new EditorNotices.Who(pairing.tokenId(), pairing.grant().label(), pairing.grant().admin());
        api.parts().notices().record(new EditorNotices.Event(EditorNotifySettings.Kind.REFUSED, who, "could not pair:", why, List.of(), Optional.empty(), List.of()));
    }

    /** Counts a pairing attempt against the address's window and refuses it past {@code pairAttemptsPerMinute}. */
    void rate(String address) throws EditorError {
        long[] window = window(address);
        synchronized (window) {
            if (++window[1] > api.parts().settings().pairAttemptsPerMinute()) throw limited();
        }
    }

    /**
     * Refuses an event stream from an address whose window is used up, before its token is checked, so a guesser gets no
     * more tries this minute. Only a failed authentication counts against the window ({@link #failed}); reconnects and page
     * reloads with a valid token do not.
     */
    void guard(String address) throws EditorError {
        long[] window = window(address);
        synchronized (window) {
            if (window[1] >= api.parts().settings().pairAttemptsPerMinute()) throw limited();
        }
    }

    /** Counts an event stream whose token was refused against the address's window. */
    void failed(String address) {
        long[] window = window(address);
        synchronized (window) { window[1]++; }
    }

    /**
     * The address's fixed one-minute window, shared by pairing and failed event authentication, reset when a new minute
     * starts. Past {@link #PAIR_WINDOW_ADDRESSES} addresses in one minute, new ones share a single window, so the map stays bounded.
     */
    private long[] window(String address) {
        long minute = api.parts().clock().millis() / 60_000;
        long[] window = pairWindows.get(address);
        if (window == null) {
            if (pairWindows.size() >= PAIR_WINDOW_ADDRESSES) pairWindows.entrySet().removeIf((Map.Entry<String, long[]> entry) -> entry.getValue()[0] != minute);
            String key = pairWindows.size() >= PAIR_WINDOW_ADDRESSES ? SHARED_PAIR_WINDOW : address;
            window = pairWindows.computeIfAbsent(key, (String unused) -> new long[]{minute, 0});
        }
        synchronized (window) {
            if (window[0] != minute) { window[0] = minute; window[1] = 0; }
        }
        return window;
    }

    private EditorError limited() {
        return new EditorError(EditorError.Code.RATE_LIMITED, "Too many pairing attempts from this address.", 60_000 - api.parts().clock().millis() % 60_000, null);
    }
}
