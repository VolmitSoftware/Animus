package art.arcane.animus.core.editor;

import art.arcane.animus.core.config.EditorSettings;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The embedded listener over real loopback sockets: HTTP, CORS preflight, bounds, WebSocket events and TLS. */
class EditorListenerTest {
    @TempDir Path directory;

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); }
    }

    private static EditorSettings settings(int port, String certificate, String key) {
        EditorSettings d = EditorSettings.DEFAULT;
        return new EditorSettings(true, d.editorUrl(), true, "127.0.0.1", port, "", certificate, key, d.allowedOrigins(), 7, 30, 4096, 16384, 512, 8, 20, 10, 64, 20, 4, 64, 15, 24);
    }

    @Test
    void http_helloPreflightAndBounds() throws Exception {
        EditorSettings settings = settings(freePort(), "", "");
        try (EditorHarness harness = new EditorHarness(directory, settings);
             EditorListener listener = new EditorListener(settings, harness.root, harness.api, (String line) -> {})) {
            listener.start();
            HttpClient client = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + listener.port() + "/animus";
            HttpResponse<String> hello = client.send(HttpRequest.newBuilder(URI.create(base + "/v1/hello")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, hello.statusCode());
            assertEquals(harness.api.parts().identity().fingerprint(), JsonDocuments.object(hello.body()).get("fingerprint").getAsString());
            HttpResponse<String> preflight = client.send(HttpRequest.newBuilder(URI.create(base + "/v1/changes")).method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                    .header("Origin", EditorHarness.ORIGIN).header("Access-Control-Request-Method", "POST").build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(204, preflight.statusCode());
            assertEquals(EditorHarness.ORIGIN, preflight.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
            assertTrue(preflight.headers().firstValue("Access-Control-Allow-Headers").orElseThrow().contains("X-Animus-Counter"));
            HttpResponse<String> large = client.send(HttpRequest.newBuilder(URI.create(base + "/v1/validate"))
                    .POST(HttpRequest.BodyPublishers.ofString("x".repeat(settings.requestBodyBytes() + 1))).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(413, large.statusCode());
            HttpResponse<String> outside = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + listener.port() + "/other")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(404, outside.statusCode());
            String token = harness.token();
            HttpResponse<String> catalog = client.send(HttpRequest.newBuilder(URI.create(base + "/v1/catalog?kind=npc")).header("Authorization", "Bearer " + token).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, catalog.statusCode());
            assertEquals(3, JsonDocuments.object(catalog.body()).getAsJsonArray("documents").size());
            HttpResponse<String> spoofed = client.send(HttpRequest.newBuilder(URI.create(base + "/v1/validate")).header("Authorization", "Bearer " + token)
                    .header("X-Animus-Counter", "1").header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"documents\":[],\"documents\":[]}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(400, spoofed.statusCode(), "duplicate keys are refused by the strict parser");
        }
    }

    @Test
    void websocket_authFrameThenEvents() throws Exception {
        EditorSettings settings = settings(freePort(), "", "");
        try (EditorHarness harness = new EditorHarness(directory, settings);
             EditorListener listener = new EditorListener(settings, harness.root, harness.api, (String line) -> {})) {
            listener.start();
            String token = harness.token();
            LinkedBlockingQueue<String> frames = new LinkedBlockingQueue<>();
            CompletableFuture<Integer> closed = new CompletableFuture<>();
            WebSocket socket = HttpClient.newHttpClient().newWebSocketBuilder().header("Origin", EditorHarness.ORIGIN)
                    .buildAsync(URI.create("ws://127.0.0.1:" + listener.port() + "/animus/v1/events"), new WebSocket.Listener() {
                        @Override public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                            frames.add(data.toString());
                            webSocket.request(1);
                            return null;
                        }

                        @Override public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                            closed.complete(statusCode);
                            return null;
                        }
                    }).get(5, TimeUnit.SECONDS);
            socket.sendText("{\"type\":\"auth\",\"token\":\"" + token + "\"}", true).get(5, TimeUnit.SECONDS);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (harness.api.parts().events().open() == 0 && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(1, harness.api.parts().events().open());
            JsonObject event = new JsonObject();
            event.addProperty("type", "preview");
            event.addProperty("previewId", "prev_00000001");
            event.addProperty("state", "expired");
            harness.api.parts().events().publish(event);
            harness.api.parts().events().tick();
            String frame = frames.poll(5, TimeUnit.SECONDS);
            assertNotNull(frame);
            assertEquals("expired", JsonDocuments.object(frame).get("state").getAsString());
            harness.api.revoke(token.substring(4, token.indexOf('.')), "token-revoked");
            assertEquals("closing", JsonDocuments.object(frames.poll(5, TimeUnit.SECONDS)).get("type").getAsString());
            assertEquals(1000, closed.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void websocket_badToken_isClosed() throws Exception {
        EditorSettings settings = settings(freePort(), "", "");
        try (EditorHarness harness = new EditorHarness(directory, settings);
             EditorListener listener = new EditorListener(settings, harness.root, harness.api, (String line) -> {})) {
            listener.start();
            CompletableFuture<Integer> closed = new CompletableFuture<>();
            WebSocket socket = HttpClient.newHttpClient().newWebSocketBuilder()
                    .buildAsync(URI.create("ws://127.0.0.1:" + listener.port() + "/animus/v1/events"), new WebSocket.Listener() {
                        @Override public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                            closed.complete(statusCode);
                            return null;
                        }
                    }).get(5, TimeUnit.SECONDS);
            socket.sendText("{\"type\":\"auth\",\"token\":\"anm_wrong\"}", true).get(5, TimeUnit.SECONDS);
            assertEquals(4401, closed.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void tls_operatorPemFiles_serveHttps() throws Exception {
        Path certificate = directory.resolve("certificate.pem"), key = directory.resolve("key.pem");
        try (InputStream input = EditorListenerTest.class.getResourceAsStream("/editor-tls/certificate.pem")) { Files.write(certificate, input.readAllBytes()); }
        try (InputStream input = EditorListenerTest.class.getResourceAsStream("/editor-tls/key.pem")) { Files.write(key, input.readAllBytes()); }
        EditorSettings settings = settings(freePort(), certificate.toString(), key.toString());
        try (EditorHarness harness = new EditorHarness(directory, settings);
             EditorListener listener = new EditorListener(settings, harness.root, harness.api, (String line) -> {})) {
            listener.start();
            SSLContext trustAll = SSLContext.getInstance("TLS");
            trustAll.init(null, new TrustManager[]{new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] chain, String type) {}
                @Override public void checkServerTrusted(X509Certificate[] chain, String type) {}
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }}, null);
            HttpClient client = HttpClient.newBuilder().sslContext(trustAll).connectTimeout(Duration.ofSeconds(5)).build();
            HttpResponse<String> hello = client.send(HttpRequest.newBuilder(URI.create("https://localhost:" + listener.port() + "/animus/v1/hello")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, hello.statusCode());
            assertThrows(IOException.class, () -> HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + listener.port() + "/animus/v1/hello"))
                    .timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString()));
        }
    }

    @Test
    void settings_plainHttpOffLoopback_isRefused() {
        EditorSettings d = EditorSettings.DEFAULT;
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> new EditorSettings(true, d.editorUrl(), true, "0.0.0.0", 9797, "", "", "",
                d.allowedOrigins(), 7, 30, 4096, 16384, 512, 8, 20, 10, 64, 20, 4, 64, 15, 24));
        assertTrue(refused.getMessage().contains("not loopback"));
        assertThrows(IllegalArgumentException.class, () -> new EditorSettings(true, d.editorUrl(), false, "127.0.0.1", 9797, "", "", "",
                List.of("http://example.net"), 7, 30, 4096, 16384, 512, 8, 20, 10, 64, 20, 4, 64, 15, 24));
    }
}
