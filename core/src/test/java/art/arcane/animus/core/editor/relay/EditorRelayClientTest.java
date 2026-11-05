package art.arcane.animus.core.editor.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.config.EditorRelaySettings;
import art.arcane.animus.core.editor.EditorEvents;
import art.arcane.animus.core.editor.EditorIdentity;
import art.arcane.animus.core.editor.SharedFixtures;
import art.arcane.animus.core.editor.transport.EditorEventSink;
import art.arcane.animus.core.editor.transport.EditorEventStream;
import art.arcane.animus.core.editor.transport.EditorRequest;
import art.arcane.animus.core.editor.transport.EditorRequestHandler;
import art.arcane.animus.core.editor.transport.EditorResponse;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EditorRelayClientTest {
    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final String NONCE = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8";

    private FakeRelay relay;
    private EditorRelayClient client;
    private final FakeHandler handler = new FakeHandler();
    private final EditorIdentity identity = EditorIdentity.generate();

    @BeforeEach
    void startRelay() throws Exception { relay = new FakeRelay(); }

    @AfterEach
    void stop() throws Exception {
        if (client != null) client.close();
        relay.close();
    }

    private EditorRelaySettings settings(int frameBytes, int concurrent) {
        return new EditorRelaySettings(true, relay.url(), 1, 2, frameBytes, concurrent);
    }

    private FakeRelay.Session connect(EditorRelaySettings settings, Duration idle) throws Exception {
        client = new EditorRelayClient(settings, identity, handler, Duration.ofSeconds(2), idle);
        client.start();
        FakeRelay.Session session = relay.accept(WAIT);
        session.register(NONCE);
        awaitRegistered();
        return session;
    }

    private FakeRelay.Session connect() throws Exception { return connect(settings(1024 * 1024, 64), Duration.ofSeconds(90)); }

    private void awaitRegistered() throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!client.isRegistered() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(client.isRegistered(), "registered");
    }

    private static JsonObject fixture(String relative) throws Exception { return SharedFixtures.object(SharedFixtures.fixture(relative)); }

    private static JsonObject request(long id, String method, String path) {
        JsonObject frame = new JsonObject();
        frame.addProperty("type", "request");
        frame.addProperty("id", id);
        frame.addProperty("method", method);
        frame.addProperty("path", path);
        JsonObject headers = new JsonObject();
        headers.addProperty("Authorization", "Bearer anm_x");
        frame.add("headers", headers);
        return frame;
    }

    @Test
    void shouldRegisterWithTheSignedChallengeUnderItsFingerprint() throws Exception {
        client = new EditorRelayClient(settings(1024 * 1024, 64), identity, handler, Duration.ofSeconds(2), Duration.ofSeconds(90));
        client.start();
        FakeRelay.Session session = relay.accept(WAIT);
        JsonObject register = session.register(NONCE);
        assertEquals("register", register.get("type").getAsString());
        assertEquals(1, register.get("protocol").getAsInt());
        assertEquals(identity.serverKey(), register.get("serverKey").getAsString());
        assertEquals(4, register.size());
        awaitRegistered();
    }

    @Test
    void shouldReplayARelayRequestIntoTheHandlerAndReturnItsResponse() throws Exception {
        FakeRelay.Session session = connect();
        session.send(fixture("editor/messages/relay-request.0.json"));
        FakeHandler.Call call = handler.calls.poll(5, TimeUnit.SECONDS);
        EditorRequest request = call.request();
        assertEquals(EditorRequest.Transport.RELAY, request.transport());
        assertEquals("POST", request.method());
        assertEquals("/v1/validate", request.path());
        assertEquals(Optional.of("Bearer anm_x"), request.header("authorization"));
        assertEquals(Optional.of("12"), request.header("X-Animus-Counter"));
        assertEquals(JsonParser.parseString("{\"documents\":[]}"), request.body().orElseThrow());
        call.response().complete(EditorResponse.json(200, JsonParser.parseString("{\"valid\":true,\"documents\":[]}")));
        assertEquals(fixture("editor/messages/relay-response.0.json"), session.next(WAIT));
    }

    @Test
    void shouldAnswerConcurrentRequestsInCompletionOrder() throws Exception {
        FakeRelay.Session session = connect();
        session.send(request(1, "GET", "/v1/catalog?kind=npc"));
        session.send(request(2, "GET", "/v1/capabilities"));
        FakeHandler.Call first = handler.calls.poll(5, TimeUnit.SECONDS);
        FakeHandler.Call second = handler.calls.poll(5, TimeUnit.SECONDS);
        assertEquals("/v1/catalog", first.request().route());
        second.response().complete(EditorResponse.json(200, new JsonObject()));
        assertEquals(2, session.next(WAIT).get("id").getAsLong());
        first.response().complete(EditorResponse.error(404, "not-found", "no such kind"));
        JsonObject answer = session.next(WAIT);
        assertEquals(1, answer.get("id").getAsLong());
        assertEquals(404, answer.get("status").getAsInt());
        assertEquals("not-found", answer.getAsJsonObject("body").getAsJsonObject("error").get("code").getAsString());
    }

    @Test
    void shouldStreamEventsUntilTheBrowserClosesTheStream() throws Exception {
        FakeRelay.Session session = connect();
        session.send(request(3, "GET", "/v1/events"));
        FakeHandler.Stream stream = handler.streams.poll(5, TimeUnit.SECONDS);
        CountDownLatch closed = new CountDownLatch(1);
        stream.opened().complete(new EditorEventStream(EditorResponse.json(200, new JsonObject()), closed::countDown));
        JsonObject response = session.next(WAIT);
        assertEquals(200, response.get("status").getAsInt());
        assertTrue(stream.sink().send(fixture("editor/messages/event.0.json")));
        assertEquals(fixture("editor/messages/relay-event.0.json"), session.next(WAIT));
        session.send(fixture("editor/messages/relay-close.0.json"));
        assertTrue(closed.await(5, TimeUnit.SECONDS));
        assertFalse(stream.sink().send(fixture("editor/messages/event.0.json")));
    }

    @Test
    void shouldSendCloseWhenTheServerEndsAStream() throws Exception {
        FakeRelay.Session session = connect();
        session.send(request(4, "GET", "/v1/events"));
        FakeHandler.Stream stream = handler.streams.poll(5, TimeUnit.SECONDS);
        stream.opened().complete(new EditorEventStream(EditorResponse.json(200, new JsonObject()), () -> {}));
        session.next(WAIT);
        stream.sink().close();
        JsonObject close = session.next(WAIT);
        assertEquals("close", close.get("type").getAsString());
        assertEquals(4, close.get("id").getAsLong());
    }

    @Test
    void shouldForwardTheLimitReasonThenCloseWhenARelayedStreamFallsBehind() throws Exception {
        FakeRelay.Session session = connect();
        session.send(request(6, "GET", "/v1/events"));
        FakeHandler.Stream stream = handler.streams.poll(5, TimeUnit.SECONDS);
        EditorEvents events = new EditorEvents();
        Runnable remove = events.add("tok", stream.sink(), (String id) -> true);
        stream.opened().complete(new EditorEventStream(EditorResponse.json(200, new JsonObject()), remove));
        assertEquals(200, session.next(WAIT).get("status").getAsInt());
        for (int i = 0; i <= EditorEvents.QUEUE_LIMIT; i++) {
            JsonObject event = new JsonObject();
            event.addProperty("type", "preview");
            event.addProperty("previewId", "prev_" + String.format("%08d", i));
            event.addProperty("state", "expired");
            events.publish(event);
        }
        JsonObject closing = session.next(WAIT);
        assertEquals("event", closing.get("type").getAsString());
        assertEquals(6, closing.get("id").getAsLong());
        assertEquals("closing", closing.getAsJsonObject("event").get("type").getAsString());
        assertEquals("limit", closing.getAsJsonObject("event").get("reason").getAsString());
        JsonObject close = session.next(WAIT);
        assertEquals("close", close.get("type").getAsString());
        assertEquals(6, close.get("id").getAsLong());
        assertEquals(0, events.open());
        assertTrue(client.isRegistered(), "only the stream ends; the relay connection stays up");
    }

    @Test
    void shouldEndARefusedStreamWithItsResponse() throws Exception {
        FakeRelay.Session session = connect();
        session.send(request(5, "GET", "/v1/events"));
        FakeHandler.Stream stream = handler.streams.poll(5, TimeUnit.SECONDS);
        stream.opened().complete(EditorEventStream.refused(EditorResponse.error(401, "unauthorized", "no token")));
        assertEquals(401, session.next(WAIT).get("status").getAsInt());
        assertFalse(stream.sink().send(new JsonObject()));
    }

    @Test
    void shouldAnswerBusyBeyondTheConcurrencyBound() throws Exception {
        FakeRelay.Session session = connect(settings(1024 * 1024, 1), Duration.ofSeconds(90));
        session.send(request(1, "GET", "/v1/capabilities"));
        handler.calls.poll(5, TimeUnit.SECONDS);
        session.send(request(2, "GET", "/v1/capabilities"));
        JsonObject busy = session.next(WAIT);
        assertEquals(2, busy.get("id").getAsLong());
        assertEquals(503, busy.get("status").getAsInt());
        assertEquals("busy", busy.getAsJsonObject("body").getAsJsonObject("error").get("code").getAsString());
    }

    @Test
    void shouldTurnHandlerFailuresIntoInternalErrors() throws Exception {
        FakeRelay.Session session = connect();
        session.send(request(1, "POST", "/v1/validate"));
        handler.calls.poll(5, TimeUnit.SECONDS).response().completeExceptionally(new IllegalStateException("boom"));
        JsonObject answer = session.next(WAIT);
        assertEquals(500, answer.get("status").getAsInt());
        assertEquals("internal", answer.getAsJsonObject("body").getAsJsonObject("error").get("code").getAsString());
    }

    @Test
    void shouldReplaceAnOversizedResponseWithTooLarge() throws Exception {
        FakeRelay.Session session = connect(settings(64 * 1024, 64), Duration.ofSeconds(90));
        session.send(request(1, "GET", "/v1/catalog"));
        JsonObject body = new JsonObject();
        body.addProperty("padding", "x".repeat(70 * 1024));
        handler.calls.poll(5, TimeUnit.SECONDS).response().complete(EditorResponse.json(200, body));
        JsonObject answer = session.next(WAIT);
        assertEquals(413, answer.get("status").getAsInt());
    }

    @Test
    void shouldDropOversizedFramesAndReconnect() throws Exception {
        FakeRelay.Session session = connect(settings(64 * 1024, 64), Duration.ofSeconds(90));
        JsonObject frame = request(1, "POST", "/v1/validate");
        JsonObject body = new JsonObject();
        body.addProperty("padding", "x".repeat(70 * 1024));
        frame.add("body", body);
        session.send(frame);
        assertTrue(session.awaitClosed(WAIT));
        relay.accept(WAIT).register(NONCE);
        awaitRegistered();
        assertTrue(handler.calls.isEmpty());
    }

    @Test
    void shouldReconnectAfterADropAndCloseOpenStreams() throws Exception {
        FakeRelay.Session session = connect();
        session.send(request(3, "GET", "/v1/events"));
        FakeHandler.Stream stream = handler.streams.poll(5, TimeUnit.SECONDS);
        CountDownLatch closed = new CountDownLatch(1);
        stream.opened().complete(new EditorEventStream(EditorResponse.json(200, new JsonObject()), closed::countDown));
        session.next(WAIT);
        session.drop();
        assertTrue(closed.await(5, TimeUnit.SECONDS));
        FakeRelay.Session again = relay.accept(WAIT);
        again.register(NONCE);
        awaitRegistered();
        again.send(request(1, "GET", "/v1/capabilities"));
        handler.calls.poll(5, TimeUnit.SECONDS).response().complete(EditorResponse.json(200, new JsonObject()));
        assertEquals(1, again.next(WAIT).get("id").getAsLong());
    }

    @Test
    void shouldReconnectWhenTheRelayGoesSilent() throws Exception {
        FakeRelay.Session session = connect(settings(1024 * 1024, 64), Duration.ofMillis(600));
        session.ping();
        assertTrue(session.awaitClosed(WAIT));
        relay.accept(WAIT);
    }

    @Test
    void shouldReconnectWhenRegistrationNeverCompletes() throws Exception {
        client = new EditorRelayClient(settings(1024 * 1024, 64), identity, handler, Duration.ofMillis(500), Duration.ofSeconds(90));
        client.start();
        FakeRelay.Session silent = relay.accept(WAIT);
        assertTrue(silent.awaitClosed(WAIT));
        relay.accept(WAIT);
        assertFalse(client.isRegistered());
    }

    @Test
    void shouldReconnectWhenTheRelayRefusesTheSignature() throws Exception {
        client = new EditorRelayClient(settings(1024 * 1024, 64), identity, handler, Duration.ofSeconds(2), Duration.ofSeconds(90));
        client.start();
        FakeRelay.Session session = relay.accept(WAIT);
        JsonObject challenge = new JsonObject();
        challenge.addProperty("type", "challenge");
        challenge.addProperty("protocol", 2);
        challenge.addProperty("nonce", NONCE);
        session.send(challenge);
        assertTrue(session.awaitClosed(WAIT));
        relay.accept(WAIT);
    }

    @Test
    void shouldNotConnectWhenDisabled() throws Exception {
        client = new EditorRelayClient(new EditorRelaySettings(false, relay.url(), 1, 2, 1024 * 1024, 64), identity, handler);
        client.start();
        assertTrue(relay.noConnection(Duration.ofMillis(500)));
    }

    @Test
    void shouldCloseTheConnectionAndStreamsOnClose() throws Exception {
        FakeRelay.Session session = connect();
        session.send(request(3, "GET", "/v1/events"));
        FakeHandler.Stream stream = handler.streams.poll(5, TimeUnit.SECONDS);
        CountDownLatch closed = new CountDownLatch(1);
        stream.opened().complete(new EditorEventStream(EditorResponse.json(200, new JsonObject()), closed::countDown));
        session.next(WAIT);
        client.close();
        assertTrue(closed.await(5, TimeUnit.SECONDS));
        assertTrue(session.awaitClosed(WAIT));
        assertTrue(relay.noConnection(Duration.ofMillis(1500)));
        client = null;
    }

    /** Records calls and lets each test answer them. */
    private static final class FakeHandler implements EditorRequestHandler {
        record Call(EditorRequest request, CompletableFuture<EditorResponse> response) {}

        record Stream(EditorRequest request, EditorEventSink sink, CompletableFuture<EditorEventStream> opened) {}

        final BlockingQueue<Call> calls = new LinkedBlockingQueue<>();
        final BlockingQueue<Stream> streams = new LinkedBlockingQueue<>();

        @Override
        public CompletionStage<EditorResponse> handle(EditorRequest request) {
            CompletableFuture<EditorResponse> response = new CompletableFuture<>();
            calls.add(new Call(request, response));
            return response;
        }

        @Override
        public CompletionStage<EditorEventStream> openEvents(EditorRequest request, EditorEventSink sink) {
            CompletableFuture<EditorEventStream> opened = new CompletableFuture<>();
            streams.add(new Stream(request, sink, opened));
            return opened;
        }
    }
}
