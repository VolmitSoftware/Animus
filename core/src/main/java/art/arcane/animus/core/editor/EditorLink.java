package art.arcane.animus.core.editor;

import art.arcane.animus.core.config.EditorRelaySettings;
import art.arcane.animus.core.config.EditorSettings;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocuments;
import art.arcane.volmlib.util.editor.DurableWrite;
import art.arcane.volmlib.util.editor.EditorIdentity;
import art.arcane.volmlib.util.editor.EditorProduct;
import art.arcane.volmlib.util.editor.EditorTokens;
import art.arcane.volmlib.util.editor.ListenerSettings;
import art.arcane.volmlib.util.editor.StrictJson;
import art.arcane.volmlib.util.editor.relay.RelaySettings;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Animus's side of the shared editor link: its wire names, the files under {@code state/editor/}, and its token file layout
 * ({@code groups}, and {@code revoked} entries, marked {@code expired} when the token ran out instead).
 */
public final class EditorLink {
    public static final EditorProduct PRODUCT = new EditorProduct("Animus", "anm_", "ANM1.", "/animus editor pair", false);
    public static final String TOKENS_PATH = "state/editor/tokens.json", IDENTITY_PATH = "state/editor/identity.json";
    /** Raw upload bytes reach the API as one base64 string at this route; it and the routes under it may send {@code importBodyBytes}. */
    public static final String UPLOAD_ROUTE = "/v1/imports";
    public static final EditorTokens.Format<EditorScope> FORMAT = new TokenFormat();
    /** Writes through the document store, so the hotload watcher knows the write is Animus's own. */
    private static final DurableWrite WRITE = (Path file, byte[] content) -> DocumentStore.atomic(file, new String(content, StandardCharsets.UTF_8));

    private EditorLink() {}

    public static EditorTokens<EditorScope> tokens(DocumentStore store, Clock clock, Consumer<String> warn) throws IOException {
        return new EditorTokens<>(PRODUCT, store.resolve(TOKENS_PATH), TOKENS_PATH, FORMAT, WRITE, clock, warn);
    }

    public static EditorIdentity identity(DocumentStore store) throws IOException {
        return EditorIdentity.loadOrCreate(store.resolve(IDENTITY_PATH), IDENTITY_PATH, WRITE);
    }

    public static ListenerSettings listener(EditorSettings settings) {
        return new ListenerSettings(settings.listenAddress(), settings.port(), settings.tlsCertificate(), settings.tlsPrivateKey(), settings.openConnections(),
                settings.trustedProxies(), settings.requestBodyBytes(), UPLOAD_ROUTE, settings.importBodyBytes());
    }

    /** True when {@code next} needs a new listener: the switch, address, port, TLS files or connection bound changed. */
    public static boolean rebind(EditorSettings current, EditorSettings next) {
        return current.listenerEnabled() != next.listenerEnabled() || listener(current).rebind(listener(next));
    }

    public static RelaySettings relay(EditorRelaySettings settings) {
        return new RelaySettings(settings.enabled(), settings.url(), settings.reconnectMinSeconds(), settings.reconnectMaxSeconds(), settings.frameBytes(),
                settings.concurrentRequests());
    }

    private static final class TokenFormat implements EditorTokens.Format<EditorScope> {
        /** Older stores may list the removed {@code admin} scope, which never granted anything; reading drops it. */
        private static final String RETIRED_SCOPE = "admin";

        @Override
        public EditorTokens.Stored<EditorScope> decode(String json) throws IOException {
            JsonObject saved = StrictJson.object(json);
            List<EditorTokens.Token<EditorScope>> tokens = new ArrayList<>();
            for (JsonElement element : saved.getAsJsonArray("tokens")) {
                JsonObject entry = element.getAsJsonObject();
                List<EditorScope> scopes = new ArrayList<>();
                for (JsonElement scope : entry.getAsJsonArray("scopes")) {
                    if (scope.getAsString().equals(RETIRED_SCOPE)) continue;
                    scopes.add(EditorScope.parse(scope.getAsString()).orElseThrow(() -> new IllegalArgumentException("unknown scope " + scope)));
                }
                List<String> groups = new ArrayList<>();
                for (JsonElement group : entry.getAsJsonArray("groups")) groups.add(group.getAsString());
                Instant created = Instant.parse(entry.get("created").getAsString()), expires = Instant.parse(entry.get("expires").getAsString());
                // A store from before sliding expiry has no lifetime; it is the time from pairing to expiry.
                Duration lifetime = optional(entry, "lifetimeSeconds").map((String seconds) -> Duration.ofSeconds(Long.parseLong(seconds))).orElse(Duration.between(created, expires));
                tokens.add(new EditorTokens.Token<>(entry.get("id").getAsString(), entry.get("hash").getAsString(), entry.get("label").getAsString(),
                        optional(entry, "admin").map(UUID::fromString), scopes, groups, created, expires, lifetime, optional(entry, "lastUsed").map(Instant::parse),
                        entry.get("counter").getAsLong(), optional(entry, "client"), optional(entry, "adminChecked").map(Instant::parse).orElse(created)));
            }
            List<EditorTokens.Ended> ended = new ArrayList<>();
            if (saved.has("revoked")) for (JsonElement element : saved.getAsJsonArray("revoked")) {
                JsonObject entry = element.getAsJsonObject();
                ended.add(new EditorTokens.Ended(entry.get("id").getAsString(), Instant.parse(entry.get("expires").getAsString()),
                        entry.has("expired") && entry.get("expired").getAsBoolean()));
            }
            return new EditorTokens.Stored<>(tokens, ended);
        }

        @Override
        public String encode(EditorTokens.Stored<EditorScope> stored) {
            JsonObject saved = new JsonObject();
            saved.addProperty("schemaVersion", 1);
            JsonArray list = new JsonArray();
            for (EditorTokens.Token<EditorScope> token : stored.tokens()) {
                JsonObject entry = new JsonObject();
                entry.addProperty("id", token.id());
                entry.addProperty("hash", token.hash());
                entry.addProperty("label", token.label());
                token.admin().ifPresent((UUID admin) -> entry.addProperty("admin", admin.toString()));
                JsonArray scopes = new JsonArray();
                token.scopes().forEach((EditorScope scope) -> scopes.add(scope.wireName()));
                entry.add("scopes", scopes);
                JsonArray groups = new JsonArray();
                token.folders().forEach(groups::add);
                entry.add("groups", groups);
                entry.addProperty("created", token.created().toString());
                entry.addProperty("expires", token.expires().toString());
                entry.addProperty("lifetimeSeconds", token.lifetime().toSeconds());
                token.lastUsed().ifPresent((Instant used) -> entry.addProperty("lastUsed", used.toString()));
                entry.addProperty("counter", token.counter());
                token.client().ifPresent((String client) -> entry.addProperty("client", client));
                entry.addProperty("adminChecked", token.adminChecked().toString());
                list.add(entry);
            }
            saved.add("tokens", list);
            JsonArray gone = new JsonArray();
            for (EditorTokens.Ended end : stored.ended()) {
                JsonObject entry = new JsonObject();
                entry.addProperty("id", end.id());
                entry.addProperty("expires", end.expires().toString());
                if (end.expired()) entry.addProperty("expired", true);
                gone.add(entry);
            }
            saved.add("revoked", gone);
            return JsonDocuments.write(saved);
        }

        private static Optional<String> optional(JsonObject entry, String key) {
            JsonElement value = entry.get(key);
            return value == null || value.isJsonNull() ? Optional.empty() : Optional.of(value.getAsString());
        }
    }
}
