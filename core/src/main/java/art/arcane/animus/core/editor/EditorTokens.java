package art.arcane.animus.core.editor;

import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocuments;
import art.arcane.animus.core.storage.NpcPaths;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Editor tokens in {@code state/editor/tokens.json}: only a SHA-256 of each token is stored, with its label, issuing admin,
 * scopes, NPC groups, expiry, last use and the last accepted {@code X-Animus-Counter}. Pairing secrets live in memory only.
 */
public final class EditorTokens {
    public static final String PATH = "state/editor/tokens.json";
    public static final int MAXIMUM_TOKENS = 256, MAXIMUM_PAIRINGS = 64;
    public static final Duration PAIRING_LIFETIME = Duration.ofMinutes(10);
    /** Older stores may list the removed {@code admin} scope, which never granted anything; loading drops it. */
    private static final String RETIRED_SCOPE = "admin";

    /**
     * A stored token. {@code admin} is empty for tokens paired from the console. {@code adminChecked} is when the server last
     * confirmed that the admin still holds {@code animus.admin}; pairing counts as a confirmation.
     */
    public record Token(String id, String hash, String label, Optional<UUID> admin, List<EditorScope> scopes, List<String> groups, Instant created,
                        Instant expires, Optional<Instant> lastUsed, long counter, Optional<String> client, Instant adminChecked) {
        public Token {
            scopes = List.copyOf(scopes);
            groups = List.copyOf(groups);
        }

        public boolean has(EditorScope scope) { return scopes.contains(scope); }

        /** True when the token may touch NPCs in this folder: one of its groups or a folder inside one. A token without groups reaches every folder. */
        public boolean group(String folder) { return groups.isEmpty() || groups.stream().anyMatch((String group) -> folder.equals(group) || folder.startsWith(group + "/")); }
    }

    /** What /animus editor pair asked for. */
    public record Grant(String label, Optional<UUID> admin, List<EditorScope> scopes, List<String> groups, Duration lifetime) {
        public Grant {
            if (label.length() > 64) throw new IllegalArgumentException("label: at most 64 characters");
            if (scopes.isEmpty()) throw new IllegalArgumentException("scopes: at least one scope");
            for (String group : groups) if (group.isEmpty() || !NpcPaths.folder(group)) throw new IllegalArgumentException("groups: " + group + " is not an NPC folder");
            if (lifetime.isNegative() || lifetime.isZero() || lifetime.compareTo(Duration.ofDays(365)) > 0) throw new IllegalArgumentException("expires: 1 minute to 365 days");
            scopes = List.copyOf(scopes);
            groups = List.copyOf(groups);
        }
    }

    /** A pairing waiting for its browser. */
    public record Pairing(String tokenId, String secret, Instant expires, Grant grant) {}

    private record Pending(String secretHash, Instant expires, Grant grant) {}

    private final DocumentStore store;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Token> tokens = new LinkedHashMap<>();
    private final Map<String, Instant> revoked = new LinkedHashMap<>();
    private final Map<String, Pending> pairings = new LinkedHashMap<>();

    public EditorTokens(DocumentStore store, Clock clock) throws IOException {
        this.store = Objects.requireNonNull(store);
        this.clock = Objects.requireNonNull(clock);
        load();
    }

    /** Starts a pairing; the code carries the secret, which is good once for ten minutes. */
    public synchronized Pairing pair(Grant grant) {
        prunePairings();
        if (pendingPairings() >= MAXIMUM_PAIRINGS || pairings.size() >= MAXIMUM_PAIRINGS * 4) throw new IllegalStateException("Too many pairings are waiting; let some expire first");
        if (tokens.size() >= MAXIMUM_TOKENS) throw new IllegalStateException("Token limit of " + MAXIMUM_TOKENS + " reached; revoke unused tokens first");
        String id = "tok_" + randomText(12);
        String secret = randomText(32);
        Instant expires = now().plus(PAIRING_LIFETIME);
        pairings.put(id, new Pending(sha256(secret), expires, grant));
        return new Pairing(id, secret, expires, grant);
    }

    /** Exchanges a pairing secret for a token. Returns the token text, which is shown once and never stored. */
    public synchronized Map.Entry<String, Token> exchange(String tokenId, String secret, Optional<String> client) throws EditorError, IOException {
        Pending pending = pairings.get(tokenId);
        if (pending == null) throw new EditorError(EditorError.Code.PAIRING_INVALID, "This pairing code is not known to the server.");
        if (!MessageDigest.isEqual(pending.secretHash().getBytes(StandardCharsets.US_ASCII), sha256(secret).getBytes(StandardCharsets.US_ASCII)))
            throw new EditorError(EditorError.Code.PAIRING_INVALID, "This pairing code is not known to the server.");
        pairings.remove(tokenId);
        if (!now().isBefore(pending.expires())) throw new EditorError(EditorError.Code.PAIRING_EXPIRED, "This pairing code expired; run /animus editor pair again.");
        String tokenSecret = randomText(32);
        String text = "anm_" + tokenId + "." + tokenSecret;
        Instant created = now();
        Token token = new Token(tokenId, sha256(text), pending.grant().label(), pending.grant().admin(), pending.grant().scopes(), pending.grant().groups(),
                created, created.plus(pending.grant().lifetime()), Optional.empty(), 0, client.map((String name) -> name.length() > 128 ? name.substring(0, 128) : name), created);
        tokens.put(tokenId, token);
        save();
        return Map.entry(text, token);
    }

    /** The token a bearer value names, or why it is refused. Constant-time hash comparison. */
    public synchronized Token authenticate(String bearer) throws EditorError {
        if (bearer == null || !bearer.matches("anm_[A-Za-z0-9_-]{8,64}\\.[A-Za-z0-9_-]{43}"))
            throw new EditorError(EditorError.Code.UNAUTHORIZED, "A valid editor token is required.");
        String id = bearer.substring(4, bearer.indexOf('.'));
        if (revoked.containsKey(id)) throw new EditorError(EditorError.Code.TOKEN_REVOKED, "This token was revoked.");
        Token token = tokens.get(id);
        if (token == null || !MessageDigest.isEqual(token.hash().getBytes(StandardCharsets.US_ASCII), sha256(bearer).getBytes(StandardCharsets.US_ASCII)))
            throw new EditorError(EditorError.Code.UNAUTHORIZED, "A valid editor token is required.");
        if (!now().isBefore(token.expires())) throw new EditorError(EditorError.Code.TOKEN_EXPIRED, "This token expired; pair the browser again.");
        Token used = new Token(token.id(), token.hash(), token.label(), token.admin(), token.scopes(), token.groups(), token.created(), token.expires(),
                Optional.of(now()), token.counter(), token.client(), token.adminChecked());
        tokens.put(id, used);
        return used;
    }

    /** Refuses a token revoked or expired since its request was authenticated, as work queued for it starts. */
    public synchronized void stillValid(String tokenId) throws EditorError {
        Token token = tokens.get(tokenId);
        if (token == null) throw new EditorError(EditorError.Code.TOKEN_REVOKED, "This token was revoked.");
        if (!now().isBefore(token.expires())) throw new EditorError(EditorError.Code.TOKEN_EXPIRED, "This token expired; pair the browser again.");
    }

    /** Accepts a mutating request's counter, which must exceed every earlier one for the token, and stores it before the request runs. */
    public synchronized void advance(String tokenId, long counter) throws EditorError, IOException {
        Token token = tokens.get(tokenId);
        if (token == null) throw new EditorError(EditorError.Code.TOKEN_REVOKED, "This token was revoked.");
        if (counter <= token.counter()) throw new EditorError(EditorError.Code.COUNTER_REPLAYED, "X-Animus-Counter must exceed " + token.counter() + ".");
        tokens.put(tokenId, new Token(token.id(), token.hash(), token.label(), token.admin(), token.scopes(), token.groups(), token.created(), token.expires(),
                token.lastUsed(), counter, token.client(), token.adminChecked()));
        save();
    }

    /** Records that the token's admin still holds {@code animus.admin}. Writes at most once an hour per token. */
    public synchronized void confirmAdmin(String tokenId) throws IOException {
        Token token = tokens.get(tokenId);
        if (token == null || token.adminChecked().plus(Duration.ofHours(1)).isAfter(now())) return;
        tokens.put(tokenId, new Token(token.id(), token.hash(), token.label(), token.admin(), token.scopes(), token.groups(), token.created(), token.expires(),
                token.lastUsed(), token.counter(), token.client(), now()));
        save();
    }

    /** Revokes one token, or every token for {@code all}. Returns the revoked ids. */
    public synchronized List<String> revoke(String idOrAll) throws IOException {
        List<String> ids = idOrAll.equals("all") ? new ArrayList<>(tokens.keySet()) : tokens.containsKey(idOrAll) ? List.of(idOrAll) : List.of();
        if (ids.isEmpty()) return List.of();
        for (String id : ids) {
            Token token = tokens.remove(id);
            revoked.put(id, token.expires());
        }
        save();
        return List.copyOf(ids);
    }

    /** Ends tokens past their expiry and forgets old revocations. Returns the ids that expired now. */
    public synchronized List<String> expire() throws IOException {
        Instant now = now();
        List<String> expired = new ArrayList<>();
        for (Token token : tokens.values()) if (!now.isBefore(token.expires())) expired.add(token.id());
        boolean changed = !expired.isEmpty();
        for (String id : expired) revoked.put(id, tokens.remove(id).expires());
        Iterator<Map.Entry<String, Instant>> old = revoked.entrySet().iterator();
        while (old.hasNext()) if (old.next().getValue().plus(Duration.ofDays(1)).isBefore(now)) { old.remove(); changed = true; }
        prunePairings();
        if (changed) save();
        return expired;
    }

    public synchronized List<Token> list() { return List.copyOf(tokens.values()); }

    public synchronized Optional<Token> find(String id) { return Optional.ofNullable(tokens.get(id)); }

    public synchronized int pendingPairings() {
        prunePairings();
        Instant now = now();
        return (int) pairings.values().stream().filter((Pending pending) -> now.isBefore(pending.expires())).count();
    }

    private void prunePairings() {
        Instant now = now();
        // Expired pairings stay an hour so a late browser learns the code expired instead of that it never existed.
        pairings.values().removeIf((Pending pending) -> now.isAfter(pending.expires().plus(Duration.ofHours(1))));
    }

    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.SECONDS); }

    private String randomText(int bytes) {
        byte[] value = new byte[bytes];
        random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    static String sha256(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException missing) { throw new IllegalStateException(missing); }
    }

    private void load() throws IOException {
        Path file = store.resolve(PATH);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return;
        try {
            JsonObject saved = JsonDocuments.object(JsonDocuments.read(file));
            for (JsonElement element : saved.getAsJsonArray("tokens")) {
                JsonObject entry = element.getAsJsonObject();
                List<EditorScope> scopes = new ArrayList<>();
                for (JsonElement scope : entry.getAsJsonArray("scopes")) {
                    if (scope.getAsString().equals(RETIRED_SCOPE)) continue;
                    scopes.add(EditorScope.parse(scope.getAsString()).orElseThrow(() -> new IllegalArgumentException("unknown scope " + scope)));
                }
                List<String> groups = new ArrayList<>();
                for (JsonElement group : entry.getAsJsonArray("groups")) groups.add(group.getAsString());
                Token token = new Token(entry.get("id").getAsString(), entry.get("hash").getAsString(), entry.get("label").getAsString(),
                        optional(entry, "admin").map(UUID::fromString), scopes, groups, Instant.parse(entry.get("created").getAsString()),
                        Instant.parse(entry.get("expires").getAsString()), optional(entry, "lastUsed").map(Instant::parse), entry.get("counter").getAsLong(),
                        optional(entry, "client"), optional(entry, "adminChecked").map(Instant::parse).orElse(Instant.parse(entry.get("created").getAsString())));
                tokens.put(token.id(), token);
            }
            if (saved.has("revoked")) for (JsonElement element : saved.getAsJsonArray("revoked")) {
                JsonObject entry = element.getAsJsonObject();
                revoked.put(entry.get("id").getAsString(), Instant.parse(entry.get("expires").getAsString()));
            }
        } catch (IOException | RuntimeException invalid) {
            throw new IOException(PATH + " is unreadable; move it away to unpair every browser: " + invalid.getMessage(), invalid);
        }
    }

    private void save() throws IOException {
        JsonObject saved = new JsonObject();
        saved.addProperty("schemaVersion", 1);
        JsonArray list = new JsonArray();
        for (Token token : tokens.values()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", token.id());
            entry.addProperty("hash", token.hash());
            entry.addProperty("label", token.label());
            token.admin().ifPresent((UUID admin) -> entry.addProperty("admin", admin.toString()));
            JsonArray scopes = new JsonArray();
            token.scopes().forEach((EditorScope scope) -> scopes.add(scope.wireName()));
            entry.add("scopes", scopes);
            JsonArray groups = new JsonArray();
            token.groups().forEach(groups::add);
            entry.add("groups", groups);
            entry.addProperty("created", token.created().toString());
            entry.addProperty("expires", token.expires().toString());
            token.lastUsed().ifPresent((Instant used) -> entry.addProperty("lastUsed", used.toString()));
            entry.addProperty("counter", token.counter());
            token.client().ifPresent((String client) -> entry.addProperty("client", client));
            entry.addProperty("adminChecked", token.adminChecked().toString());
            list.add(entry);
        }
        saved.add("tokens", list);
        JsonArray gone = new JsonArray();
        revoked.forEach((String id, Instant expires) -> {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", id);
            entry.addProperty("expires", expires.toString());
            gone.add(entry);
        });
        saved.add("revoked", gone);
        DocumentStore.atomic(store.resolve(PATH), JsonDocuments.write(saved));
    }

    private static Optional<String> optional(JsonObject entry, String key) {
        JsonElement value = entry.get(key);
        return value == null || value.isJsonNull() ? Optional.empty() : Optional.of(value.getAsString());
    }
}
