package art.arcane.animus.core.editor;

import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.Platform;
import art.arcane.animus.core.RuntimeEnvironment;
import art.arcane.animus.core.config.PluginSettings;
import art.arcane.animus.core.editor.transport.EditorRequest;
import art.arcane.animus.core.editor.transport.EditorResponse;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A hotload of {@code [editor]} through the running settings: only the changed subsystem restarts, and tokens survive. */
class EditorHotloadTest {
    private static final String OTHER_ORIGIN = "https://editor.example.net";
    @TempDir Path directory;
    private Path data;
    private AnimusRuntime runtime;
    private EditorHarness harness;
    private EditorService service;
    private final List<String> log = new CopyOnWriteArrayList<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeEach
    void open() throws Exception {
        data = directory.resolve("server");
        Files.createDirectories(data);
        harness = new EditorHarness(directory.resolve("host"));
    }

    @AfterEach
    void close() throws Exception {
        if (service != null) service.close();
        if (runtime != null) runtime.close();
        harness.close();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); }
    }

    private static String config(String editor) { return "statusMessage = \"Hello\"\n[editor]\n" + editor; }

    private static String listener(int port) { return "listenerEnabled = true\nrelayEnabled = false\nport = " + port + "\n"; }

    private void start(String text) throws Exception {
        Files.writeString(data.resolve("config.toml"), text);
        runtime = new AnimusRuntime(new RuntimeEnvironment(Platform.BUKKIT, "26.2", data));
        runtime.start();
        service = new EditorService(runtime, harness.host, log::add);
        service.start();
    }

    /** Starts with the listener on a free port; another process can take a probed port first, so a lost race picks another. */
    private int startListening(java.util.function.IntFunction<String> text) throws Exception {
        for (int attempt = 0; ; attempt++) {
            int port = freePort();
            start(text.apply(port));
            if (service.listener().isPresent() || attempt == 4) return port;
            service.close();
            runtime.close();
        }
    }

    private void hotload(String text) throws IOException { runtime.applySettings(PluginSettings.parse(text, data.resolve("config.toml"))); }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > until) throw new AssertionError("Timed out waiting for " + what);
            Thread.sleep(10);
        }
    }

    private int listenerPort() { return service.listener().map(EditorListener::port).orElse(-1); }

    private String token() throws Exception {
        EditorApi api = service.api().orElseThrow();
        EditorTokens.Pairing pairing = api.parts().tokens().pair(EditorHarness.grant(EditorScope.DEFAULT_PAIRING));
        JsonObject body = new JsonObject();
        body.addProperty("tokenId", pairing.tokenId());
        body.addProperty("secret", pairing.secret());
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Origin", EditorHarness.ORIGIN);
        headers.put(EditorReplies.PEER_HEADER, "127.0.0.1");
        EditorResponse response = api.handle(new EditorRequest(EditorRequest.Transport.DIRECT, "POST", "/v1/pair", headers, Optional.of(body)))
                .toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(200, response.status(), response.body().toString());
        return EditorHarness.json(response).get("token").getAsString();
    }

    private int catalog(String base, String token) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + "/animus/v1/catalog?kind=npc")).header("Authorization", "Bearer " + token).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private static boolean refused(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getByName(host), port), 2000);
            return false;
        } catch (IOException closed) {
            return true;
        }
    }

    @Test
    void portChange_rebindsListener_tokenStaysValid() throws Exception {
        int first = startListening((int free) -> config(listener(free)));
        String token = token();
        assertEquals(200, catalog("http://127.0.0.1:" + first, token));
        int second = freePort();
        hotload(config(listener(second)));
        await(() -> listenerPort() == second, "the listener on the new port");
        assertTrue(refused("127.0.0.1", first), "the old port is closed");
        assertEquals(200, catalog("http://127.0.0.1:" + second, token), "the token issued before the restart still works");
    }

    @Test
    void addressChange_rebindsListener() throws Exception {
        int port = startListening((int free) -> config(listener(free)));
        EditorListener before = service.listener().orElseThrow();
        String token = token();
        hotload(config(listener(port) + "listenAddress = \"::1\"\n"));
        await(() -> service.listener().map((EditorListener now) -> now != before).orElse(false), "a new listener");
        assertTrue(refused("127.0.0.1", port), "the IPv4 loopback socket is closed");
        assertEquals(200, catalog("http://[::1]:" + port, token));
    }

    @Test
    void tlsChange_rebindsListenerAsHttps() throws Exception {
        Path certificate = data.resolve("certificate.pem"), key = data.resolve("key.pem");
        try (InputStream input = EditorHotloadTest.class.getResourceAsStream("/editor-tls/certificate.pem")) { Files.write(certificate, input.readAllBytes()); }
        try (InputStream input = EditorHotloadTest.class.getResourceAsStream("/editor-tls/key.pem")) { Files.write(key, input.readAllBytes()); }
        int port = startListening((int free) -> config(listener(free)));
        EditorListener before = service.listener().orElseThrow();
        String token = token();
        hotload(config(listener(port) + "tlsCertificate = \"certificate.pem\"\ntlsPrivateKey = \"key.pem\"\n"));
        await(() -> service.listener().map((EditorListener now) -> now != before).orElse(false), "a new listener");
        SSLContext trustAll = SSLContext.getInstance("TLS");
        trustAll.init(null, new TrustManager[]{new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String type) {}
            @Override public void checkServerTrusted(X509Certificate[] chain, String type) {}
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }}, null);
        HttpClient secure = HttpClient.newBuilder().sslContext(trustAll).connectTimeout(Duration.ofSeconds(5)).build();
        HttpResponse<String> response = secure.send(HttpRequest.newBuilder(URI.create("https://localhost:" + port + "/animus/v1/catalog?kind=npc"))
                .header("Authorization", "Bearer " + token).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertThrows(IOException.class, () -> http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/animus/v1/hello"))
                .timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString()));
    }

    @Test
    void originAndLimitChanges_applyWithoutRestartingListener() throws Exception {
        int port = startListening((int free) -> config(listener(free)));
        EditorListener before = service.listener().orElseThrow();
        String base = "http://127.0.0.1:" + port + "/animus/v1/changes";
        HttpRequest preflight = HttpRequest.newBuilder(URI.create(base)).method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", OTHER_ORIGIN).header("Access-Control-Request-Method", "POST").build();
        assertTrue(http.send(preflight, HttpResponse.BodyHandlers.ofString()).headers().firstValue("Access-Control-Allow-Origin").isEmpty());
        hotload(config(listener(port) + "allowedOrigins = [\"" + OTHER_ORIGIN + "\"]\nrequestBodyBytes = 2048\nrequestsPerSecond = 5\n"));
        await(() -> service.settings().allowedOrigins().equals(List.of(OTHER_ORIGIN)), "the new origins");
        assertSame(before, service.listener().orElseThrow(), "the listener kept its socket");
        assertEquals(port, listenerPort());
        assertEquals(OTHER_ORIGIN, http.send(preflight, HttpResponse.BodyHandlers.ofString()).headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
        assertEquals(List.of(OTHER_ORIGIN), service.api().orElseThrow().parts().settings().allowedOrigins());
        HttpResponse<String> large = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/animus/v1/validate"))
                .POST(HttpRequest.BodyPublishers.ofString("x".repeat(3000))).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(413, large.statusCode(), "the listener reads the new body limit");
    }

    @Test
    void unrelatedChange_restartsNothing() throws Exception {
        int port = freePort();
        try (FakeAgent agent = new FakeAgent()) {
            start(config(listener(port).replace("relayEnabled = false", "relayEnabled = true\nrelayUrl = \"" + agent.url() + "\"")));
            await(() -> agent.accepted() == 1, "the relay connection");
            EditorListener before = service.listener().orElseThrow();
            EditorApi api = service.api().orElseThrow();
            hotload("statusMessage = \"Changed\"\n[editor]\n" + listener(port).replace("relayEnabled = false", "relayEnabled = true\nrelayUrl = \"" + agent.url() + "\""));
            hotload("statusMessage = \"Changed\"\n[editor]\n" + listener(port).replace("relayEnabled = false", "relayEnabled = true\nrelayUrl = \"" + agent.url() + "\"") + "tokenDays = 3\n");
            await(() -> service.settings().tokenDays() == 3, "the new token lifetime");
            Thread.sleep(200);
            assertSame(before, service.listener().orElseThrow());
            assertSame(api, service.api().orElseThrow());
            assertEquals(1, agent.accepted(), "the relay was not reconnected");
            assertFalse(agent.closed(0), "the relay connection stayed open");
        }
    }

    @Test
    void relayEnableAndUrlChange_restartOnlyTheRelay() throws Exception {
        int port = startListening((int free) -> config(listener(free)));
        EditorListener before = service.listener().orElseThrow();
        String token = token();
        try (FakeAgent first = new FakeAgent(); FakeAgent second = new FakeAgent()) {
            hotload(config(listener(port).replace("relayEnabled = false", "relayEnabled = true\nrelayUrl = \"" + first.url() + "\"")));
            await(() -> first.accepted() == 1, "the relay connecting");
            assertTrue(service.status().stream().anyMatch((String line) -> line.contains(" | relay " + first.url() + " ")));
            hotload(config(listener(port).replace("relayEnabled = false", "relayEnabled = true\nrelayUrl = \"" + second.url() + "\"")));
            await(() -> second.accepted() == 1, "the relay on the new URL");
            await(() -> first.closed(0), "the old relay connection closing");
            hotload(config(listener(port)));
            await(() -> second.closed(0), "the relay stopping");
            await(() -> service.status().getFirst().contains(" | relay off | "), "relay status off");
            assertSame(before, service.listener().orElseThrow(), "the listener did not restart");
            assertEquals(200, catalog("http://127.0.0.1:" + port, token));
        }
    }

    @Test
    void portInUse_keepsRunningWithReason_laterFixRebinds() throws Exception {
        int port = startListening((int free) -> config(listener(free)));
        String token = token();
        try (ServerSocket taken = new ServerSocket()) {
            taken.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            hotload(config(listener(taken.getLocalPort())));
            await(() -> service.listener().isEmpty() && service.status().stream().anyMatch((String line) -> line.contains(" | listener failed: ")),
                    "the listener reporting why it is down");
            assertTrue(service.api().isPresent(), "the API keeps running for the relay");
            assertTrue(refused("127.0.0.1", port), "the old listener was closed");
        }
        int fixed = freePort();
        hotload(config(listener(fixed)));
        await(() -> listenerPort() == fixed, "the listener on the fixed port");
        assertTrue(service.status().getFirst().startsWith("Editor ready | listener 127.0.0.1:" + fixed + " (http) "), service.status().getFirst());
        assertEquals(200, catalog("http://127.0.0.1:" + fixed, token));
    }

    @Test
    void editorSwitch_closesAndReopens_tokenSurvives() throws Exception {
        int port = startListening((int free) -> config(listener(free)));
        String token = token();
        hotload(config("enabled = false\n" + listener(port)));
        await(() -> service.api().isEmpty(), "the editor closing");
        await(() -> refused("127.0.0.1", port), "the listener closing");
        assertTrue(service.status().getFirst().startsWith("Editor disabled"));
        hotload(config(listener(port)));
        await(() -> listenerPort() == port, "the editor reopening");
        assertEquals(200, catalog("http://127.0.0.1:" + port, token), "tokens are stored on disk and survive the switch");
        assertTrue(log.contains("Editor disabled by config.toml"));
        assertNotEquals(-1, listenerPort());
    }

    /** A relay stand-in that accepts the WebSocket connection and never answers, so the client stays connecting. */
    private static final class FakeAgent implements AutoCloseable {
        private final ServerSocket server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        private final List<AtomicBoolean> ended = new CopyOnWriteArrayList<>();
        private final List<Socket> sockets = new CopyOnWriteArrayList<>();

        FakeAgent() throws IOException {
            Thread.ofPlatform().daemon().start(() -> {
                while (!server.isClosed()) {
                    Socket socket;
                    try { socket = server.accept(); }
                    catch (IOException stopped) { return; }
                    AtomicBoolean end = new AtomicBoolean();
                    sockets.add(socket);
                    ended.add(end);
                    Thread.ofPlatform().daemon().start(() -> {
                        try (InputStream input = socket.getInputStream()) {
                            while (input.read() >= 0) { /* Discard the upgrade request. */ }
                        } catch (IOException gone) { /* Closed. */ }
                        end.set(true);
                    });
                }
            });
        }

        String url() { return "ws://127.0.0.1:" + server.getLocalPort(); }

        int accepted() { return ended.size(); }

        boolean closed(int index) { return ended.size() > index && ended.get(index).get(); }

        @Override public void close() throws IOException {
            server.close();
            for (Socket socket : sockets) socket.close();
        }
    }
}
