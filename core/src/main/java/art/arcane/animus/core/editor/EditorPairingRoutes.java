package art.arcane.animus.core.editor;

import art.arcane.animus.core.BuildIdentity;
import art.arcane.animus.core.config.EditorNotifySettings;
import art.arcane.volmlib.util.editor.EditorRefusal;
import art.arcane.volmlib.util.editor.EditorTokens;
import art.arcane.volmlib.util.editor.PairingRate;
import art.arcane.volmlib.util.editor.transport.EditorRequest;
import art.arcane.volmlib.util.editor.transport.EditorResponse;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The open endpoints {@code GET /v1/hello} and {@code POST /v1/pair}, the per-address pairing rate, and what a failed pairing
 * tells the console and the player who made the link.
 */
final class EditorPairingRoutes {
    private final EditorApi api;
    private final PairingRate rate;

    EditorPairingRoutes(EditorApi api) {
        this.api = api;
        this.rate = new PairingRate(api.parts().clock(), () -> api.parts().settings().pairAttemptsPerMinute());
    }

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
        String tokenId = EditorMessages.string(body, "tokenId", EditorMessages.TOKEN_ID, "$.tokenId", "the token id from the pairing link");
        String secret = EditorMessages.string(body, "secret", EditorMessages.SECRET, "$.secret", "the secret from the pairing link");
        Optional<String> client = EditorMessages.optionalString(body, "client", 128);
        Map.Entry<String, EditorTokens.Token<EditorScope>> issued;
        Optional<EditorTokens.Open<EditorScope>> waiting = parts.tokens().pairing(tokenId);
        try { issued = parts.tokens().exchange(tokenId, secret, client); }
        catch (IOException failure) { throw new EditorError(EditorError.Code.INTERNAL, "The token store could not be written."); }
        catch (EditorRefusal refusal) {
            EditorError refused = EditorError.of(refusal);
            api.audit("pair", Optional.of(tokenId), Optional.empty(), request, List.of(), refused.code().wireName());
            String address = EditorReplies.address(request);
            if (refused.code() == EditorError.Code.PAIRING_EXPIRED) {
                parts.warnings().once("pair " + tokenId, Duration.ofHours(1), "Editor pairing " + tokenId + " failed: the link expired before it was opened; run /animus editor pair again");
                waiting.ifPresent((EditorTokens.Open<EditorScope> open) -> refused(open, "the link expired; make a new one"));
            } else {
                String why = switch (refused.code()) {
                    case PAIRING_USED -> "the link was already used";
                    case PAIRING_REVOKED -> "the link was revoked";
                    default -> "the link is unknown";
                };
                parts.warnings().once("pair unknown " + address, Duration.ofMinutes(1), "Editor pairing from " + address + " failed: " + why);
            }
            throw refused;
        }
        EditorTokens.Token<EditorScope> token = issued.getValue();
        api.audit("pair", Optional.of(token.id()), token.admin(), request, List.of(), "paired");
        parts.notices().record(new EditorNotices.Event(EditorNotifySettings.Kind.PAIR, EditorNotices.Who.of(token), "paired", "", List.of(), Optional.empty(), List.of()));
        JsonObject response = new JsonObject();
        response.addProperty("token", issued.getKey());
        response.addProperty("tokenId", token.id());
        JsonArray scopes = new JsonArray();
        token.scopes().forEach((EditorScope scope) -> scopes.add(scope.wireName()));
        response.add("scopes", scopes);
        if (!token.folders().isEmpty()) {
            JsonArray groups = new JsonArray();
            token.folders().forEach(groups::add);
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
    void refused(EditorTokens.Open<EditorScope> pairing, String why) {
        EditorNotices.Who who = new EditorNotices.Who(pairing.tokenId(), pairing.grant().label(), pairing.grant().admin());
        api.parts().notices().record(new EditorNotices.Event(EditorNotifySettings.Kind.REFUSED, who, "could not pair:", why, List.of(), Optional.empty(), List.of()));
    }

    /** Counts a pairing attempt against the address's window (IPv6 by its /64) and refuses it past {@code pairAttemptsPerMinute}. */
    void rate(String address) throws EditorError {
        try { rate.pairing(address); }
        catch (EditorRefusal refused) { throw EditorError.of(refused); }
    }

    /**
     * Refuses an event stream from an address whose window is used up, before its token is checked, so a guesser gets no
     * more tries this minute. Only a failed authentication counts against the window ({@link #failed}); reconnects and page
     * reloads with a valid token do not.
     */
    void guard(String address) throws EditorError {
        try { rate.guard(address); }
        catch (EditorRefusal refused) { throw EditorError.of(refused); }
    }

    /** Counts an event stream whose token was refused against the address's window. */
    void failed(String address) { rate.failed(address); }
}
