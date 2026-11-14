package art.arcane.animus.core.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * {@code [editor]} in {@code config.toml}: the web editor API, its embedded listener, tokens and bounds. Every key applies on
 * hotload: {@code enabled} opens or closes the editor, a changed address, port, TLS file, {@code openConnections} or
 * {@code listenerEnabled} restarts only the listener, and the rest apply to the next request. Issued tokens stay valid.
 * The listener serves plain HTTP only on a loopback address; any other address needs {@code tlsCertificate} and {@code tlsPrivateKey}.
 * The {@code [editor.notify]} sub-table is {@link EditorNotifySettings}. {@code serverName} names the server in the editor; empty
 * takes the server's MOTD, else its folder name.
 */
public record EditorSettings(boolean enabled, String serverName, String editorUrl, boolean listenerEnabled, String listenAddress, int port, String advertisedUrl,
                             String tlsCertificate, String tlsPrivateKey, List<String> allowedOrigins,
                             int tokenDays, int auditDays, int requestBodyBytes, int importBodyBytes, int changeSetDocuments,
                             int concurrentRequestsPerToken, int requestsPerSecond, int pairAttemptsPerMinute, int openConnections,
                             int eventFramesPerSecond, int previewsPerServer, int npcsPerPreview, int previewMinutes, int idempotencyHours, int terrainRadius,
                             EditorNotifySettings notices) {
    private static final Set<String> LOOPBACK = Set.of("127.0.0.1", "localhost", "::1", "0:0:0:0:0:0:0:1");
    public static final EditorSettings DEFAULT = new EditorSettings(true, "", "https://animus.volmitsoftware.com", true, "127.0.0.1", 9797, "", "", "",
            List.of("https://animus.volmitsoftware.com", "https://animus-editor.web.app", "https://animus-editor.firebaseapp.com"),
            7, 30, 4 * 1024 * 1024, 16 * 1024 * 1024, 512, 8, 20, 10, 64, 20, 4, 64, 15, 24, 160, EditorNotifySettings.DEFAULT);

    public EditorSettings {
        if (serverName == null || serverName.length() > 64) throw new IllegalArgumentException("serverName must be at most 64 characters");
        url("editorUrl", editorUrl, Set.of("https", "http"));
        if (listenAddress == null || listenAddress.isBlank() || listenAddress.length() > 255) throw new IllegalArgumentException("listenAddress must be a host name or address");
        // 0 binds any free port; the listener reports and pairing links carry the port it bound.
        require("port", port, 0, 65535);
        if (!advertisedUrl.isEmpty()) url("advertisedUrl", advertisedUrl, Set.of("https", "http"));
        if (tlsCertificate.isEmpty() != tlsPrivateKey.isEmpty()) throw new IllegalArgumentException("tlsCertificate and tlsPrivateKey must be set together");
        if (listenerEnabled && tlsCertificate.isEmpty() && !loopback(listenAddress))
            throw new IllegalArgumentException("listenAddress " + listenAddress + " is not loopback; plain HTTP is served only on 127.0.0.1, so set tlsCertificate and tlsPrivateKey or use a TLS proxy in front of a loopback listener");
        if (allowedOrigins.size() > 32) throw new IllegalArgumentException("allowedOrigins allows at most 32 origins");
        for (String origin : allowedOrigins) origin(origin);
        allowedOrigins = List.copyOf(allowedOrigins);
        require("tokenDays", tokenDays, 1, 365);
        require("auditDays", auditDays, 1, 3650);
        require("requestBodyBytes", requestBodyBytes, 1024, 64 * 1024 * 1024);
        require("importBodyBytes", importBodyBytes, 1024, 256 * 1024 * 1024);
        require("changeSetDocuments", changeSetDocuments, 1, 512);
        require("concurrentRequestsPerToken", concurrentRequestsPerToken, 1, 64);
        require("requestsPerSecond", requestsPerSecond, 1, 1000);
        require("pairAttemptsPerMinute", pairAttemptsPerMinute, 1, 600);
        require("openConnections", openConnections, 1, 1024);
        require("eventFramesPerSecond", eventFramesPerSecond, 1, 100);
        require("previewsPerServer", previewsPerServer, 0, 64);
        require("npcsPerPreview", npcsPerPreview, 1, 64);
        require("previewMinutes", previewMinutes, 1, 120);
        require("idempotencyHours", idempotencyHours, 1, 168);
        require("terrainRadius", terrainRadius, 0, 1024);
        java.util.Objects.requireNonNull(notices);
    }

    public static boolean loopback(String host) { return LOOPBACK.contains(host.toLowerCase(Locale.ROOT)); }

    public boolean tls() { return !tlsCertificate.isEmpty(); }

    /** Reads the table; the relay keys that share it are read by {@link EditorRelaySettings}, and unknown keys were dropped with a warning before. */
    public static EditorSettings parse(JsonObject table) {
        EditorSettings d = DEFAULT;
        return new EditorSettings(bool(table, "enabled", d.enabled), text(table, "serverName", d.serverName), text(table, "editorUrl", d.editorUrl), bool(table, "listenerEnabled", d.listenerEnabled),
                text(table, "listenAddress", d.listenAddress), integer(table, "port", d.port), text(table, "advertisedUrl", d.advertisedUrl),
                text(table, "tlsCertificate", d.tlsCertificate), text(table, "tlsPrivateKey", d.tlsPrivateKey), strings(table, "allowedOrigins", d.allowedOrigins), integer(table, "tokenDays", d.tokenDays),
                integer(table, "auditDays", d.auditDays), integer(table, "requestBodyBytes", d.requestBodyBytes), integer(table, "importBodyBytes", d.importBodyBytes),
                integer(table, "changeSetDocuments", d.changeSetDocuments), integer(table, "concurrentRequestsPerToken", d.concurrentRequestsPerToken),
                integer(table, "requestsPerSecond", d.requestsPerSecond), integer(table, "pairAttemptsPerMinute", d.pairAttemptsPerMinute),
                integer(table, "openConnections", d.openConnections), integer(table, "eventFramesPerSecond", d.eventFramesPerSecond),
                integer(table, "previewsPerServer", d.previewsPerServer), integer(table, "npcsPerPreview", d.npcsPerPreview),
                integer(table, "previewMinutes", d.previewMinutes), integer(table, "idempotencyHours", d.idempotencyHours),
                integer(table, "terrainRadius", d.terrainRadius), notices(table, d.notices));
    }

    /** The table written into a new config.toml. */
    public JsonObject toJson() {
        JsonObject table = new JsonObject();
        table.addProperty("enabled", enabled);
        table.addProperty("serverName", serverName);
        table.addProperty("editorUrl", editorUrl);
        table.addProperty("listenerEnabled", listenerEnabled);
        table.addProperty("listenAddress", listenAddress);
        table.addProperty("port", port);
        table.addProperty("advertisedUrl", advertisedUrl);
        table.addProperty("tlsCertificate", tlsCertificate);
        table.addProperty("tlsPrivateKey", tlsPrivateKey);
        JsonArray origins = new JsonArray();
        allowedOrigins.forEach(origins::add);
        table.add("allowedOrigins", origins);
        table.addProperty("tokenDays", tokenDays);
        table.addProperty("auditDays", auditDays);
        table.addProperty("requestBodyBytes", requestBodyBytes);
        table.addProperty("importBodyBytes", importBodyBytes);
        table.addProperty("changeSetDocuments", changeSetDocuments);
        table.addProperty("concurrentRequestsPerToken", concurrentRequestsPerToken);
        table.addProperty("requestsPerSecond", requestsPerSecond);
        table.addProperty("pairAttemptsPerMinute", pairAttemptsPerMinute);
        table.addProperty("openConnections", openConnections);
        table.addProperty("eventFramesPerSecond", eventFramesPerSecond);
        table.addProperty("previewsPerServer", previewsPerServer);
        table.addProperty("npcsPerPreview", npcsPerPreview);
        table.addProperty("previewMinutes", previewMinutes);
        table.addProperty("idempotencyHours", idempotencyHours);
        table.addProperty("terrainRadius", terrainRadius);
        table.add("notify", notices.toJson());
        return table;
    }

    private static EditorNotifySettings notices(JsonObject table, EditorNotifySettings fallback) {
        if (!table.has("notify")) return fallback;
        if (!table.get("notify").isJsonObject()) throw new IllegalArgumentException("notify must be a table");
        try { return EditorNotifySettings.parse(table.getAsJsonObject("notify")); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("notify: " + invalid.getMessage(), invalid); }
    }

    private static void origin(String origin) {
        URI uri = url("allowedOrigins", origin, Set.of("https", "http"));
        if (uri.getRawPath() != null && !uri.getRawPath().isEmpty() || uri.getRawQuery() != null || uri.getRawFragment() != null || origin.endsWith("/"))
            throw new IllegalArgumentException("allowedOrigins entries are scheme://host[:port] with no path: " + origin);
        if (uri.getScheme().equals("http") && !loopback(uri.getHost()))
            throw new IllegalArgumentException("allowedOrigins accepts http:// only on loopback: " + origin);
    }

    private static URI url(String field, String value, Set<String> schemes) {
        try {
            URI uri = new URI(value);
            if (uri.getScheme() == null || !schemes.contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null)
                throw new URISyntaxException(value, "unexpected scheme or host");
            return uri;
        } catch (URISyntaxException invalid) {
            throw new IllegalArgumentException(field + " must be an absolute " + String.join(" or ", schemes) + " URL: " + value);
        }
    }

    private static void require(String field, int value, int minimum, int maximum) {
        if (value < minimum || value > maximum) throw new IllegalArgumentException(field + " must be within " + minimum + ".." + maximum + ", not " + value);
    }

    private static boolean bool(JsonObject table, String key, boolean fallback) {
        if (!table.has(key)) return fallback;
        JsonElement value = table.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException(key + " must be a boolean");
        return value.getAsBoolean();
    }

    private static String text(JsonObject table, String key, String fallback) {
        if (!table.has(key)) return fallback;
        JsonElement value = table.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException(key + " must be a string");
        return value.getAsString();
    }

    private static int integer(JsonObject table, String key, int fallback) {
        if (!table.has(key)) return fallback;
        JsonElement value = table.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException(key + " must be an integer");
        try { return value.getAsBigDecimal().intValueExact(); }
        catch (ArithmeticException invalid) { throw new IllegalArgumentException(key + " must be an integer", invalid); }
    }

    private static List<String> strings(JsonObject table, String key, List<String> fallback) {
        if (!table.has(key)) return fallback;
        JsonElement value = table.get(key);
        if (!value.isJsonArray()) throw new IllegalArgumentException(key + " must be an array of strings");
        List<String> values = new ArrayList<>();
        for (JsonElement entry : value.getAsJsonArray()) {
            if (!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString()) throw new IllegalArgumentException(key + " must be an array of strings");
            values.add(entry.getAsString());
        }
        return values;
    }
}
