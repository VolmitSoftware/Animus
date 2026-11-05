package art.arcane.animus.core.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

/**
 * The relay client's keys in the {@code [editor]} table of {@code config.toml}. The server connects out to {@code relayUrl}
 * (its {@code /agent} path) and the browser reaches it through the same relay's {@code /app} path. The relay operator can
 * read the traffic it forwards until relay end-to-end encryption ships. Requires a restart.
 */
public record EditorRelaySettings(boolean enabled, String url, int reconnectMinSeconds, int reconnectMaxSeconds, int frameBytes,
                                  int concurrentRequests) {
    public static final String DEFAULT_URL = "wss://relay.animus.volmitsoftware.com";
    public static final EditorRelaySettings DEFAULT = new EditorRelaySettings(true, DEFAULT_URL, 1, 60, 1024 * 1024, 64);
    private static final Set<String> LOOPBACK = Set.of("127.0.0.1", "localhost", "[::1]", "::1");

    public EditorRelaySettings {
        url = url.strip();
        checkUrl(url);
        require("relayReconnectMinSeconds", reconnectMinSeconds, 1, 3600);
        require("relayReconnectMaxSeconds", reconnectMaxSeconds, reconnectMinSeconds, 3600);
        require("relayFrameBytes", frameBytes, 64 * 1024, 16 * 1024 * 1024);
        require("relayConcurrentRequests", concurrentRequests, 1, 1024);
    }

    /** {@code relayUrl} is {@code wss://host[:port][/path]}, or {@code ws://} on a loopback host for local testing. */
    private static void checkUrl(String url) {
        URI uri;
        try { uri = new URI(url); }
        catch (URISyntaxException invalid) { throw new IllegalArgumentException("relayUrl is not a URL", invalid); }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (host.isEmpty() || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null)
            throw new IllegalArgumentException("relayUrl must be wss://host[:port][/path] without user, query or fragment");
        if (!scheme.equals("wss") && !(scheme.equals("ws") && LOOPBACK.contains(host)))
            throw new IllegalArgumentException("relayUrl must use wss:// (ws:// only on 127.0.0.1 or localhost)");
    }

    /** The relay's server path, {@code relayUrl} followed by {@code /agent}. */
    public URI agentUri() {
        String base = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        return URI.create(base + "/agent");
    }

    private static void require(String field, int value, int minimum, int maximum) {
        if (value < minimum || value > maximum) throw new IllegalArgumentException(field + " must be within " + minimum + ".." + maximum);
    }
}
