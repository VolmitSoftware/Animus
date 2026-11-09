package art.arcane.animus.core.editor;

import art.arcane.animus.core.config.EditorSettings;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The embedded listener over real loopback sockets: HTTP, CORS preflight, bounds, WebSocket events and TLS. */
class EditorListenerTest {
    @TempDir Path directory;

    private static EditorSettings settings(int port, String certificate, String key) {
        EditorSettings d = EditorSettings.DEFAULT;
        return new EditorSettings(true, d.editorUrl(), true, "127.0.0.1", port, "", certificate, key, d.allowedOrigins(), 7, 30, 4096, 16384, 512, 8, 20, 10, 64, 20, 4, 64, 15, 24, 160, art.arcane.animus.core.config.EditorNotifySettings.DEFAULT);
    }

    @Test
    void http_helloPreflightAndBounds() throws Exception {
        EditorSettings settings = settings(0, "", "");
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
            HttpResponse<String> outside = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + listener.port() + "/other")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(404, outside.statusCode());
            String token = harness.token();
            HttpResponse<String> large = client.send(HttpRequest.newBuilder(URI.create(base + "/v1/validate")).header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.ofString("x".repeat(settings.requestBodyBytes() + 1))).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(413, large.statusCode());
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
        EditorSettings settings = settings(0, "", "");
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
                    }).get(20, TimeUnit.SECONDS);
            socket.sendText("{\"type\":\"auth\",\"token\":\"" + token + "\"}", true).get(20, TimeUnit.SECONDS);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (harness.api.parts().events().open() == 0 && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(1, harness.api.parts().events().open());
            JsonObject event = new JsonObject();
            event.addProperty("type", "preview");
            event.addProperty("previewId", "prev_00000001");
            event.addProperty("state", "expired");
            harness.api.parts().events().publish(event);
            harness.api.parts().events().tick();
            String frame = frames.poll(20, TimeUnit.SECONDS);
            assertNotNull(frame);
            assertEquals("expired", JsonDocuments.object(frame).get("state").getAsString());
            harness.api.revoke(token.substring(4, token.indexOf('.')), "token-revoked");
            assertEquals("closing", JsonDocuments.object(frames.poll(20, TimeUnit.SECONDS)).get("type").getAsString());
            assertEquals(1000, closed.get(20, TimeUnit.SECONDS));
        }
    }

    @Test
    void websocket_slowConsumerAtQueueLimit_closesWithLimitReason() throws Exception {
        EditorSettings settings = settings(0, "", "");
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
                    }).get(20, TimeUnit.SECONDS);
            socket.sendText("{\"type\":\"auth\",\"token\":\"" + token + "\"}", true).get(20, TimeUnit.SECONDS);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (harness.api.parts().events().open() == 0 && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(1, harness.api.parts().events().open());
            // No tick drains the stream: it falls a whole queue behind, then one more event overflows it.
            for (int i = 0; i <= EditorEvents.QUEUE_LIMIT; i++) {
                JsonObject event = new JsonObject();
                event.addProperty("type", "preview");
                event.addProperty("previewId", "prev_" + String.format("%08d", i));
                event.addProperty("state", "expired");
                harness.api.parts().events().publish(event);
            }
            JsonObject closing = JsonDocuments.object(frames.poll(20, TimeUnit.SECONDS));
            assertEquals("closing", closing.get("type").getAsString(), "the backlog is dropped, not sent ahead of the reason");
            assertEquals("limit", closing.get("reason").getAsString());
            assertEquals(1000, closed.get(20, TimeUnit.SECONDS), "a clean close, not a protocol error");
            assertEquals(0, harness.api.parts().events().open());
        }
    }

    @Test
    void websocket_clientThatStopsReading_isDroppedWithoutStallingOtherStreams() throws Exception {
        EditorSettings settings = settings(0, "", "");
        try (EditorHarness harness = new EditorHarness(directory, settings);
             EditorListener listener = new EditorListener(settings, harness.root, harness.api, (String line) -> {})) {
            listener.start();
            String token = harness.token();
            AtomicInteger delivered = new AtomicInteger();
            WebSocket fast = HttpClient.newHttpClient().newWebSocketBuilder().header("Origin", EditorHarness.ORIGIN)
                    .buildAsync(URI.create("ws://127.0.0.1:" + listener.port() + "/animus/v1/events"), new WebSocket.Listener() {
                        @Override public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                            if (last) delivered.incrementAndGet();
                            webSocket.request(1);
                            return null;
                        }
                    }).get(20, TimeUnit.SECONDS);
            fast.sendText("{\"type\":\"auth\",\"token\":\"" + token + "\"}", true).get(20, TimeUnit.SECONDS);
            try (Socket slow = new Socket()) {
                slow.setReceiveBufferSize(4096);
                slow.connect(new java.net.InetSocketAddress("127.0.0.1", listener.port()));
                OutputStream output = slow.getOutputStream();
                output.write(("GET /animus/v1/events HTTP/1.1\r\nHost: 127.0.0.1\r\nOrigin: " + EditorHarness.ORIGIN + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                        + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                output.write(maskedText("{\"type\":\"auth\",\"token\":\"" + token + "\"}"));
                output.flush();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                while (harness.api.parts().events().open() < 2 && System.nanoTime() < deadline) Thread.sleep(10);
                assertEquals(2, harness.api.parts().events().open());
                // The slow socket never reads again: its send buffers fill while the fast one keeps up.
                int events = 160;
                CompletableFuture<Void> pacing = CompletableFuture.runAsync(() -> {
                    for (int i = 0; i < events; i++) {
                        JsonObject result = new JsonObject();
                        result.add("documents", new JsonArray());
                        result.addProperty("pad", "x".repeat(48 * 1024));
                        JsonObject event = new JsonObject();
                        event.addProperty("type", "change");
                        event.add("result", result);
                        harness.api.parts().events().publish(event);
                        harness.api.parts().events().tick();
                        // Paced like the frame timer: the reading browser stays a few frames behind at most.
                        long wait = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                        while (delivered.get() < i - 4 && System.nanoTime() < wait) Thread.onSpinWait();
                    }
                });
                pacing.get(20, TimeUnit.SECONDS);
                deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                while ((delivered.get() < events || harness.api.parts().events().open() != 1) && System.nanoTime() < deadline) Thread.sleep(10);
                assertEquals(events, delivered.get(), "the reading stream got every event");
                assertEquals(1, harness.api.parts().events().open(), "the stream that stopped reading was dropped");
            }
        }
    }

    /** One masked client text frame under 64 KiB. */
    private static byte[] maskedText(String text) {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8), mask = {1, 2, 3, 4};
        int head = payload.length < 126 ? 2 : 4;
        byte[] frame = new byte[head + 4 + payload.length];
        frame[0] = (byte) 0x81;
        if (head == 2) frame[1] = (byte) (0x80 | payload.length);
        else { frame[1] = (byte) (0x80 | 126); frame[2] = (byte) (payload.length >>> 8); frame[3] = (byte) payload.length; }
        System.arraycopy(mask, 0, frame, head, 4);
        for (int index = 0; index < payload.length; index++) frame[head + 4 + index] = (byte) (payload[index] ^ mask[index % 4]);
        return frame;
    }

    @Test
    void apply_connectionDroppedMidApply_resolvesThroughChangeStatus() throws Exception {
        EditorSettings settings = settings(0, "", "");
        try (EditorHarness harness = new EditorHarness(directory, settings);
             EditorListener listener = new EditorListener(settings, harness.root, harness.api, (String line) -> {})) {
            listener.start();
            String token = harness.token();
            String key = "key-0000000000000042";
            JsonObject current = EditorHarness.json(harness.send("GET", "/v1/documents/npc/examples/greeter", token, null));
            JsonObject source = current.getAsJsonObject("source").deepCopy();
            source.addProperty("name", "Dropped");
            JsonObject change = new JsonObject();
            change.addProperty("kind", "npc");
            change.addProperty("id", "examples/greeter");
            change.add("baseRevision", current.get("revision"));
            change.add("baseHash", current.get("hash"));
            change.add("source", source);
            JsonArray documents = new JsonArray();
            documents.add(change);
            JsonObject set = new JsonObject();
            set.addProperty("idempotencyKey", key);
            set.add("documents", documents);
            byte[] body = set.toString().getBytes(StandardCharsets.UTF_8);
            // Hold the file lane so the apply is still queued when its connection drops.
            CountDownLatch release = new CountDownLatch(1);
            harness.lane.execute(() -> {
                try { release.await(); }
                catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
            });
            try (Socket client = new Socket("127.0.0.1", listener.port())) {
                String head = "POST /animus/v1/changes HTTP/1.1\r\nHost: 127.0.0.1\r\nOrigin: " + EditorHarness.ORIGIN + "\r\nAuthorization: Bearer " + token
                        + "\r\nX-Animus-Counter: " + harness.nextCounter() + "\r\nContent-Type: application/json\r\nContent-Length: " + body.length + "\r\n\r\n";
                OutputStream output = client.getOutputStream();
                output.write(head.getBytes(StandardCharsets.US_ASCII));
                output.write(body);
                output.flush();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                while (!harness.api.parts().changes().pending(key) && System.nanoTime() < deadline) Thread.sleep(10);
                assertTrue(harness.api.parts().changes().pending(key));
            }
            // The editor never saw the answer; after reconnecting it asks for the key's status instead of sending again.
            HttpClient http = HttpClient.newHttpClient();
            HttpRequest status = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + listener.port() + "/animus/v1/changes/" + key))
                    .header("Origin", EditorHarness.ORIGIN).header("Authorization", "Bearer " + token).build();
            assertEquals("pending", JsonDocuments.object(http.send(status, HttpResponse.BodyHandlers.ofString()).body()).get("status").getAsString());
            release.countDown();
            String settled = "pending";
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (settled.equals("pending") && System.nanoTime() < deadline) {
                settled = JsonDocuments.object(http.send(status, HttpResponse.BodyHandlers.ofString()).body()).get("status").getAsString();
                if (settled.equals("pending")) Thread.sleep(10);
            }
            assertEquals("applied", settled, "the server finishes an apply whose client went away");
            assertTrue(harness.read("npcs/examples/greeter.json").contains("\"Dropped\""));
            assertEquals(1, harness.host.applied.size(), "written once");
        }
    }

    @Test
    void websocket_badToken_isClosed() throws Exception {
        EditorSettings settings = settings(0, "", "");
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
                    }).get(20, TimeUnit.SECONDS);
            socket.sendText("{\"type\":\"auth\",\"token\":\"anm_wrong\"}", true).get(20, TimeUnit.SECONDS);
            assertEquals(4401, closed.get(20, TimeUnit.SECONDS));
        }
    }

    @Test
    void tls_operatorPemFiles_serveHttps() throws Exception {
        Path certificate = directory.resolve("certificate.pem"), key = directory.resolve("key.pem");
        try (InputStream input = EditorListenerTest.class.getResourceAsStream("/editor-tls/certificate.pem")) { Files.write(certificate, input.readAllBytes()); }
        try (InputStream input = EditorListenerTest.class.getResourceAsStream("/editor-tls/key.pem")) { Files.write(key, input.readAllBytes()); }
        EditorSettings settings = settings(0, certificate.toString(), key.toString());
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

    /** Sends a request head on a raw socket and reads the status line, failing if the server waits for more. */
    private static String statusLine(Socket client, String head) throws IOException {
        client.setSoTimeout(5000);
        client.getOutputStream().write(head.getBytes(StandardCharsets.US_ASCII));
        client.getOutputStream().flush();
        StringBuilder line = new StringBuilder();
        InputStream input = client.getInputStream();
        for (int read = input.read(); read >= 0 && read != '\r'; read = input.read()) line.append((char) read);
        return line.toString();
    }

    @Test
    void http_tokenIsCheckedBeforeTheBodyIsRead() throws Exception {
        EditorSettings settings = settings(0, "", "");
        try (EditorHarness harness = new EditorHarness(directory, settings);
             EditorListener listener = new EditorListener(settings, harness.root, harness.api, (String line) -> {})) {
            listener.start();
            try (Socket client = new Socket("127.0.0.1", listener.port())) {
                String status = statusLine(client, "POST /animus/v1/validate HTTP/1.1\r\nHost: x\r\nContent-Length: 4000\r\n\r\n");
                assertEquals("HTTP/1.1 401 Unauthorized", status, "an unauthenticated body is never waited for");
            }
            try (Socket client = new Socket("127.0.0.1", listener.port())) {
                String status = statusLine(client, "POST /animus/v1/pair HTTP/1.1\r\nHost: x\r\nContent-Length: 8192\r\n\r\n");
                assertEquals("HTTP/1.1 413 Content Too Large", status, "pairing takes a small body only");
            }
            String token = harness.token();
            try (Socket client = new Socket("127.0.0.1", listener.port())) {
                String status = statusLine(client, "POST /animus/v1/imports HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer " + token + "\r\nContent-Length: 4000\r\n\r\n");
                assertEquals("HTTP/1.1 403 Forbidden", status, "an upload without the import scope is refused before its bytes arrive");
            }
        }
    }

    @Test
    void http_slowHeadIsCutOff() throws Exception {
        EditorSettings settings = settings(0, "", "");
        try (EditorHarness harness = new EditorHarness(directory, settings);
             EditorListener listener = new EditorListener(settings, harness.root, harness.api, (String line) -> {})) {
            listener.start();
            try (Socket client = new Socket("127.0.0.1", listener.port())) {
                client.setSoTimeout(20_000);
                OutputStream output = client.getOutputStream();
                output.write("GET /animus/v1/hello HTTP/1.1\r\n".getBytes(StandardCharsets.US_ASCII));
                long started = System.nanoTime();
                boolean closed = false;
                try {
                    for (int index = 0; index < 40 && !closed; index++) {
                        output.write('X');
                        output.flush();
                        Thread.sleep(250);
                    }
                } catch (IOException reset) { closed = true; }
                if (!closed) closed = client.getInputStream().read() < 0;
                assertTrue(closed, "a head that trickles in is dropped");
                assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(9), "within the head deadline");
            }
        }
    }

    @Test
    void http_slowOpenBodyIsCutOff() throws Exception {
        EditorSettings settings = settings(0, "", "");
        try (EditorHarness harness = new EditorHarness(directory, settings);
             EditorListener listener = new EditorListener(settings, harness.root, harness.api, (String line) -> {})) {
            listener.start();
            try (Socket client = new Socket("127.0.0.1", listener.port())) {
                client.setSoTimeout(20_000);
                OutputStream output = client.getOutputStream();
                output.write(("POST /animus/v1/pair HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\nContent-Length: 4096\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                long started = System.nanoTime();
                boolean closed = false;
                try {
                    for (int index = 0; index < 60 && !closed; index++) {
                        output.write(' ');
                        output.flush();
                        Thread.sleep(250);
                    }
                } catch (IOException reset) { closed = true; }
                if (!closed) closed = client.getInputStream().read() < 0;
                assertTrue(closed, "a body that trickles in without a token is dropped");
                assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(9), "within the open body deadline");
            }
        }
    }

    @Test
    void addresses_ipv6CountsByItsSlash64() throws Exception {
        assertEquals(EditorListener.addressKey(java.net.InetAddress.getByName("2001:db8:1:2::1")), EditorListener.addressKey(java.net.InetAddress.getByName("2001:db8:1:2:ffff::9")));
        assertNotEquals(EditorListener.addressKey(java.net.InetAddress.getByName("2001:db8:1:2::1")), EditorListener.addressKey(java.net.InetAddress.getByName("2001:db8:1:3::1")));
        assertEquals("203.0.113.9", EditorListener.addressKey(java.net.InetAddress.getByName("203.0.113.9")));
    }

    @Test
    void http_oneAddressCannotHoldEveryConnection() throws Exception {
        EditorSettings settings = settings(0, "", "");
        try (EditorHarness harness = new EditorHarness(directory, settings);
             EditorListener listener = new EditorListener(settings, harness.root, harness.api, (String line) -> {})) {
            listener.start();
            List<Socket> held = new java.util.ArrayList<>();
            try {
                for (int index = 0; index < EditorListener.CONNECTIONS_PER_ADDRESS; index++) held.add(new Socket("127.0.0.1", listener.port()));
                try (Socket extra = new Socket("127.0.0.1", listener.port())) {
                    extra.setSoTimeout(5000);
                    byte[] reply = extra.getInputStream().readAllBytes();
                    assertTrue(new String(reply, StandardCharsets.ISO_8859_1).startsWith("HTTP/1.1 503"), "the address has its share of connections");
                }
            } finally { for (Socket socket : held) socket.close(); }
        }
    }

    @Test
    void settings_plainHttpOffLoopback_isRefused() {
        EditorSettings d = EditorSettings.DEFAULT;
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> new EditorSettings(true, d.editorUrl(), true, "0.0.0.0", 9797, "", "", "",
                d.allowedOrigins(), 7, 30, 4096, 16384, 512, 8, 20, 10, 64, 20, 4, 64, 15, 24, 160, art.arcane.animus.core.config.EditorNotifySettings.DEFAULT));
        assertTrue(refused.getMessage().contains("not loopback"));
        assertThrows(IllegalArgumentException.class, () -> new EditorSettings(true, d.editorUrl(), false, "127.0.0.1", 9797, "", "", "",
                List.of("http://example.net"), 7, 30, 4096, 16384, 512, 8, 20, 10, 64, 20, 4, 64, 15, 24, 160, art.arcane.animus.core.config.EditorNotifySettings.DEFAULT));
    }
}
