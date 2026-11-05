package art.arcane.animus.core.editor;

import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The pairing code printed by /animus web pair: {@code ANM1.} followed by the payload JSON in base64url without padding
 * (schema/editor-v1.schema.json, pairing-payload). The browser receives it only in the URL fragment.
 */
public record PairingCode(Optional<String> directUrl, Optional<String> relayUrl, String fingerprint, String serverKey, String tokenId,
                          String secret, Instant expiresAt, Optional<String> label, Optional<String> serverName) {
    public static final String PREFIX = "ANM1.";
    public static final int VERSION = 1, MAXIMUM_CODE_LENGTH = 4096;
    private static final List<String> FIELDS = List.of("v", "directUrl", "relayUrl", "fingerprint", "serverKey", "tokenId", "secret", "expiresAt", "label", "serverName");
    private static final Set<String> LOOPBACK = Set.of("127.0.0.1", "localhost");
    private static final String KEY = "[A-Za-z0-9_-]{43}";
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    public PairingCode {
        if (directUrl.isEmpty() && relayUrl.isEmpty()) throw new IllegalArgumentException("$: a pairing code needs directUrl, relayUrl or both");
        directUrl.ifPresent(PairingCode::checkDirect);
        relayUrl.ifPresent(PairingCode::checkRelay);
        if (!serverKey.matches(KEY)) throw new IllegalArgumentException("$.serverKey: expected a 32-byte Ed25519 key in base64url without padding");
        if (!fingerprint.equals(fingerprint(Base64.getUrlDecoder().decode(serverKey))))
            throw new IllegalArgumentException("$.fingerprint: does not match serverKey");
        if (!tokenId.matches("[A-Za-z0-9_-]{8,64}")) throw new IllegalArgumentException("$.tokenId: expected 8-64 base64url characters");
        if (!secret.matches(KEY)) throw new IllegalArgumentException("$.secret: expected 32 bytes in base64url without padding");
        label.ifPresent((String text) -> { if (text.length() > 64) throw new IllegalArgumentException("$.label: at most 64 characters"); });
        serverName.ifPresent((String text) -> { if (text.length() > 128) throw new IllegalArgumentException("$.serverName: at most 128 characters"); });
    }

    /** {@code sha256:<hex>} of the raw public key, as the relay registers the server. */
    public static String fingerprint(byte[] publicKey) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(publicKey));
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException(missing);
        }
    }

    /** @throws IllegalArgumentException naming the field that is wrong */
    public static PairingCode decode(String code) {
        if (code == null || code.length() > MAXIMUM_CODE_LENGTH || !code.startsWith(PREFIX) || !code.substring(PREFIX.length()).matches("[A-Za-z0-9_-]+"))
            throw new IllegalArgumentException("$: expected ANM1. followed by base64url without padding");
        JsonObject object;
        try {
            object = JsonDocuments.object(new String(Base64.getUrlDecoder().decode(code.substring(PREFIX.length())), StandardCharsets.UTF_8));
        } catch (IOException | IllegalArgumentException invalid) {
            throw new IllegalArgumentException("$: the pairing payload is not one JSON object");
        }
        for (String key : object.keySet()) if (!FIELDS.contains(key)) throw new IllegalArgumentException("$." + key + ": unknown field");
        JsonElement version = object.get("v");
        if (version == null || !version.isJsonPrimitive() || !version.getAsJsonPrimitive().isNumber() || version.getAsDouble() != VERSION)
            throw new IllegalArgumentException("$.v: expected " + VERSION);
        Instant expires;
        try { expires = Instant.parse(required(object, "expiresAt")); }
        catch (DateTimeParseException invalid) { throw new IllegalArgumentException("$.expiresAt: expected an ISO-8601 instant"); }
        return new PairingCode(optional(object, "directUrl"), optional(object, "relayUrl"), required(object, "fingerprint"), required(object, "serverKey"),
                required(object, "tokenId"), required(object, "secret"), expires, optional(object, "label"), optional(object, "serverName"));
    }

    /** The code with fields in the order of the schema, so a decoded code encodes to the same text. */
    public String encode() {
        JsonObject object = new JsonObject();
        object.addProperty("v", VERSION);
        directUrl.ifPresent((String url) -> object.addProperty("directUrl", url));
        relayUrl.ifPresent((String url) -> object.addProperty("relayUrl", url));
        object.addProperty("fingerprint", fingerprint);
        object.addProperty("serverKey", serverKey);
        object.addProperty("tokenId", tokenId);
        object.addProperty("secret", secret);
        object.addProperty("expiresAt", expiresAt.toString());
        label.ifPresent((String text) -> object.addProperty("label", text));
        serverName.ifPresent((String text) -> object.addProperty("serverName", text));
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(GSON.toJson(object).getBytes(StandardCharsets.UTF_8));
    }

    private static void checkDirect(String url) {
        URI uri = uri(url, "$.directUrl");
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean loopback = uri.getHost() != null && LOOPBACK.contains(uri.getHost().toLowerCase(Locale.ROOT));
        if (!scheme.equals("https") && !(scheme.equals("http") && loopback))
            throw new IllegalArgumentException("$.directUrl: expected https://, or http:// only on 127.0.0.1 or localhost, because browsers block plain HTTP from an HTTPS page");
    }

    private static void checkRelay(String url) {
        URI uri = uri(url, "$.relayUrl");
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean loopback = LOOPBACK.contains(uri.getHost().toLowerCase(Locale.ROOT));
        if (!scheme.equals("wss") && !(scheme.equals("ws") && loopback))
            throw new IllegalArgumentException("$.relayUrl: expected wss://, or ws:// only on 127.0.0.1 or localhost for local development");
    }

    private static URI uri(String url, String path) {
        try {
            URI uri = new URI(url);
            if (uri.getHost() == null || uri.getUserInfo() != null) throw new URISyntaxException(url, "expected a host and no user information");
            return uri;
        } catch (URISyntaxException invalid) {
            throw new IllegalArgumentException(path + ": expected an absolute URL");
        }
    }

    private static String required(JsonObject object, String key) {
        return optional(object, key).orElseThrow(() -> new IllegalArgumentException("$." + key + ": expected string"));
    }

    private static Optional<String> optional(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null) return Optional.empty();
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("$." + key + ": expected string");
        return Optional.of(value.getAsString());
    }
}
