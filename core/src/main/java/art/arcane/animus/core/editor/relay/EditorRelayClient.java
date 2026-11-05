package art.arcane.animus.core.editor.relay;

import art.arcane.animus.core.config.EditorRelaySettings;
import art.arcane.animus.core.editor.EditorIdentity;
import art.arcane.animus.core.editor.transport.EditorEventSink;
import art.arcane.animus.core.editor.transport.EditorEventStream;
import art.arcane.animus.core.editor.transport.EditorRequest;
import art.arcane.animus.core.editor.transport.EditorRequestHandler;
import art.arcane.animus.core.editor.transport.EditorResponse;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Connects this server out to the editor relay and answers the browser requests it forwards through the same
 * {@link EditorRequestHandler} as the direct listener. Protocol: {@code animus-web/relay/README.md} and the
 * {@code relay-*} definitions in {@code schema/editor-v1.schema.json}.
 *
 * <p>Runs on its own daemon thread and the JDK WebSocket threads and never touches world state; the handler does that on
 * its own lanes. Reconnects with jittered backoff, reads one frame at a time, and refuses frames over
 * {@code relayFrameBytes}. The relay can read everything it forwards (no end-to-end encryption yet).
 */
public final class EditorRelayClient implements AutoCloseable {
    public static final int PROTOCOL = 1;
    public static final String CHALLENGE_PREFIX = "animus-relay/1 ";
    private static final System.Logger LOG = System.getLogger("Animus");
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final int MAX_QUEUED_FRAMES = 1024;
    private static final long MAX_ID = 9007199254740991L;

    private final EditorRelaySettings settings;
    private final EditorIdentity identity;
    private final EditorRequestHandler handler;
    private final RelayBackoff backoff;
    private final Duration handshakeTimeout;
    private final Duration idleTimeout;
    private final ScheduledExecutorService scheduler;
    private final HttpClient http;
    private final Object lock = new Object();
    private boolean running;
    private int attempt;
    private Connection current;
    private ScheduledFuture<?> reconnect;

    public EditorRelayClient(EditorRelaySettings settings, EditorIdentity identity, EditorRequestHandler handler) {
        this(settings, identity, handler, Duration.ofSeconds(10), Duration.ofSeconds(90));
    }

    /** {@code idleTimeout}: how long without any frame or ping from the relay before the connection counts as dead. */
    EditorRelayClient(EditorRelaySettings settings, EditorIdentity identity, EditorRequestHandler handler, Duration handshakeTimeout,
                      Duration idleTimeout) {
        this.settings = settings;
        this.identity = identity;
        this.handler = handler;
        this.backoff = new RelayBackoff(settings.reconnectMinSeconds() * 1000L, settings.reconnectMaxSeconds() * 1000L);
        this.handshakeTimeout = handshakeTimeout;
        this.idleTimeout = idleTimeout;
        this.scheduler = Executors.newSingleThreadScheduledExecutor((Runnable task) -> {
            Thread thread = new Thread(task, "animus-editor-relay");
            thread.setDaemon(true);
            return thread;
        });
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** Starts connecting. Does nothing when {@code relayEnabled} is false. */
    public void start() {
        if (!settings.enabled()) return;
        synchronized (lock) {
            if (running) return;
            running = true;
            attempt = 0;
        }
        scheduler.execute(this::connect);
    }

    /** True while a connection is registered with the relay. */
    public boolean isRegistered() {
        synchronized (lock) { return current != null && current.registered; }
    }

    @Override
    public void close() {
        Connection connection;
        synchronized (lock) {
            running = false;
            if (reconnect != null) reconnect.cancel(false);
            connection = current;
            current = null;
        }
        if (connection != null) connection.end(true);
        scheduler.shutdown();
        try { scheduler.awaitTermination(2, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        scheduler.shutdownNow();
        http.shutdownNow();
    }

    private void connect() {
        Connection connection = new Connection();
        synchronized (lock) {
            if (!running) return;
            current = connection;
        }
        http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10)).buildAsync(settings.agentUri(), connection)
                .whenComplete((WebSocket socket, Throwable failure) -> {
                    if (failure != null) connection.fail("connect failed: " + reason(failure));
                });
    }

    private void scheduleReconnect(Connection ended) {
        synchronized (lock) {
            if (current != ended) return;
            current = null;
            if (!running) return;
            long delay = backoff.delayMillis(attempt++, ThreadLocalRandom.current().nextDouble());
            LOG.log(System.Logger.Level.DEBUG, "Editor relay reconnect in {0} ms (attempt {1})", delay, attempt);
            try { reconnect = scheduler.schedule(this::connect, delay, TimeUnit.MILLISECONDS); }
            catch (java.util.concurrent.RejectedExecutionException stopped) { running = false; }
        }
    }

    private static String reason(Throwable failure) {
        Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
        return cause.getClass().getSimpleName() + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
    }

    /** One WebSocket to the relay, from open to close. */
    private final class Connection implements WebSocket.Listener {
        private final StringBuilder text = new StringBuilder();
        private final ArrayDeque<String> outbound = new ArrayDeque<>();
        private final Map<Long, Route> routes = new ConcurrentHashMap<>();
        private final AtomicInteger active = new AtomicInteger();
        private volatile WebSocket socket;
        private volatile boolean registered;
        private volatile long lastSeen = System.nanoTime();
        private boolean sending;
        private boolean ended;
        private ScheduledFuture<?> watchdog;

        @Override
        public void onOpen(WebSocket webSocket) {
            socket = webSocket;
            lastSeen = System.nanoTime();
            long started = System.nanoTime();
            try {
                watchdog = scheduler.scheduleAtFixedRate(() -> watch(started), 250, 250, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.RejectedExecutionException stopped) {
                end(true);
                return;
            }
            webSocket.request(1);
        }

        private void watch(long started) {
            long now = System.nanoTime();
            if (!registered && now - started > handshakeTimeout.toNanos()) fail("registration timed out");
            else if (now - lastSeen > idleTimeout.toNanos()) fail("no frames from the relay for " + idleTimeout.toSeconds() + " s");
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            lastSeen = System.nanoTime();
            text.append(data);
            if (text.length() > settings.frameBytes()) {
                fail("frame over " + settings.frameBytes() + " bytes");
                return null;
            }
            if (last) {
                String frame = text.toString();
                text.setLength(0);
                if (frame.getBytes(StandardCharsets.UTF_8).length > settings.frameBytes()) {
                    fail("frame over " + settings.frameBytes() + " bytes");
                    return null;
                }
                try { receive(JsonDocuments.object(frame)); }
                catch (IOException | RuntimeException invalid) {
                    fail("invalid frame: " + invalid.getMessage());
                    return null;
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            fail("binary frame");
            return null;
        }

        @Override
        public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
            lastSeen = System.nanoTime();
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
            lastSeen = System.nanoTime();
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            end(false);
            LOG.log(System.Logger.Level.DEBUG, "Editor relay closed {0} {1}", statusCode, reason);
            scheduleReconnect(this);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) { fail(reason(error)); }

        private void receive(JsonObject frame) {
            String type = string(frame, "type");
            if (!registered) {
                switch (type) {
                    case "challenge" -> {
                        if (number(frame, "protocol") != PROTOCOL) throw new IllegalArgumentException("relay protocol " + frame.get("protocol"));
                        String nonce = string(frame, "nonce");
                        if (!nonce.matches("[A-Za-z0-9_-]{43}")) throw new IllegalArgumentException("challenge nonce");
                        JsonObject register = new JsonObject();
                        register.addProperty("type", "register");
                        register.addProperty("protocol", PROTOCOL);
                        register.addProperty("serverKey", identity.serverKey());
                        register.addProperty("signature", identity.sign(CHALLENGE_PREFIX + nonce));
                        send(register);
                    }
                    case "registered" -> {
                        if (!identity.fingerprint().equals(string(frame, "fingerprint"))) throw new IllegalArgumentException("registered under another fingerprint");
                        registered = true;
                        synchronized (lock) { attempt = 0; }
                        LOG.log(System.Logger.Level.INFO, "Editor relay registered {0} at {1}", identity.fingerprint(), settings.url());
                    }
                    case "error" -> refused(frame);
                    default -> throw new IllegalArgumentException("unexpected " + type + " before registration");
                }
                return;
            }
            switch (type) {
                case "request" -> request(frame);
                case "close" -> {
                    Route route = routes.remove(id(frame));
                    if (route != null) route.cancel();
                }
                case "error" -> refused(frame);
                default -> { }
            }
        }

        private void refused(JsonObject frame) {
            LOG.log(System.Logger.Level.WARNING, "Editor relay refused the connection: {0} {1}", frame.get("code"), frame.get("message"));
        }

        private void request(JsonObject frame) {
            long id = id(frame);
            Map<String, String> headers = new LinkedHashMap<>();
            if (frame.has("headers")) {
                for (Map.Entry<String, JsonElement> header : frame.getAsJsonObject("headers").entrySet())
                    headers.put(header.getKey(), header.getValue().getAsString());
            }
            EditorRequest request;
            try {
                request = new EditorRequest(EditorRequest.Transport.RELAY, string(frame, "method"), string(frame, "path"), headers,
                        Optional.ofNullable(frame.get("body")));
            } catch (IllegalArgumentException invalid) {
                respond(id, EditorResponse.error(400, "invalid-request", invalid.getMessage()));
                return;
            }
            if (routes.containsKey(id)) {
                respond(id, EditorResponse.error(400, "invalid-request", "id is in use"));
                return;
            }
            if (active.incrementAndGet() > settings.concurrentRequests()) {
                active.decrementAndGet();
                respond(id, EditorResponse.error(503, "busy", "too many relayed requests"));
                return;
            }
            Route route = new Route(id);
            routes.put(id, route);
            boolean stream = request.method().equals("GET") && request.route().equals("/v1/events");
            CompletionStage<?> answered;
            try {
                if (stream) {
                    answered = handler.openEvents(request, route).whenComplete((EditorEventStream opened, Throwable failure) -> {
                        if (failure != null) route.answer(internal(failure), false);
                        else route.opened(opened);
                    });
                } else {
                    answered = handler.handle(request).whenComplete((EditorResponse response, Throwable failure) ->
                            route.answer(failure != null ? internal(failure) : response, false));
                }
            } catch (RuntimeException failure) {
                route.answer(internal(failure), false);
                answered = CompletableFuture.completedFuture(null);
            }
            answered.whenComplete((Object result, Throwable failure) -> active.decrementAndGet());
        }

        private EditorResponse internal(Throwable failure) {
            LOG.log(System.Logger.Level.WARNING, "Editor relay request failed", failure);
            return EditorResponse.error(500, "internal", "the request failed on the server");
        }

        /** Queues the response; false when it was over the frame limit and a too-large error went instead. */
        private boolean respond(long id, EditorResponse response) {
            JsonObject frame = new JsonObject();
            frame.addProperty("type", "response");
            frame.addProperty("id", id);
            frame.addProperty("status", response.status());
            if (!response.headers().isEmpty()) {
                JsonObject headers = new JsonObject();
                response.headers().forEach(headers::addProperty);
                frame.add("headers", headers);
            }
            response.body().ifPresent((JsonElement body) -> frame.add("body", body));
            if (send(frame)) return true;
            respondTooLarge(id);
            return false;
        }

        private void respondTooLarge(long id) {
            JsonObject frame = new JsonObject();
            frame.addProperty("type", "response");
            frame.addProperty("id", id);
            frame.addProperty("status", 413);
            frame.add("body", GSON.toJsonTree(Map.of("error", Map.of("code", "too-large", "message", "the response exceeds the relay frame limit"))));
            send(frame);
        }

        /** Queues one frame; false when it is over the frame limit. */
        private boolean send(JsonObject frame) {
            String encoded = GSON.toJson(frame);
            if (encoded.getBytes(StandardCharsets.UTF_8).length > settings.frameBytes()) return false;
            synchronized (this) {
                if (ended) return true;
                if (outbound.size() >= MAX_QUEUED_FRAMES) {
                    scheduler.execute(() -> fail("send queue full"));
                    return true;
                }
                outbound.add(encoded);
                if (sending) return true;
                sending = true;
            }
            scheduler.execute(this::pump);
            return true;
        }

        private void pump() {
            String next;
            synchronized (this) {
                next = ended ? null : outbound.poll();
                if (next == null) {
                    sending = false;
                    return;
                }
            }
            WebSocket target = socket;
            if (target == null) return;
            target.sendText(next, true).whenCompleteAsync((WebSocket sent, Throwable failure) -> {
                if (failure != null) fail("send failed: " + reason(failure));
                else pump();
            }, scheduler);
        }

        private void fail(String why) {
            if (end(false)) {
                LOG.log(System.Logger.Level.DEBUG, "Editor relay connection ended: {0}", why);
                WebSocket target = socket;
                if (target != null) target.abort();
                scheduleReconnect(this);
            }
        }

        /** Ends the connection once: closes every stream and drops every pending answer. */
        private boolean end(boolean closeSocket) {
            List<Route> open;
            synchronized (this) {
                if (ended) return false;
                ended = true;
                outbound.clear();
                open = new ArrayList<>(routes.values());
                routes.clear();
            }
            if (watchdog != null) watchdog.cancel(false);
            for (Route route : open) route.cancel();
            WebSocket target = socket;
            if (closeSocket && target != null) {
                try { target.sendClose(WebSocket.NORMAL_CLOSURE, "server stopping"); }
                catch (RuntimeException ignored) { target.abort(); }
            }
            return true;
        }

        /** One request or event stream, keyed by the relay's id; it is also the stream's event sink. */
        private final class Route implements EditorEventSink {
            private final long id;
            private boolean answered, streaming, cancelled;
            private Runnable onClose;

            private Route(long id) { this.id = id; }

            private void answer(EditorResponse response, boolean keep) {
                synchronized (this) {
                    if (answered || cancelled) return;
                    answered = true;
                }
                if (!keep) routes.remove(id, this);
                boolean delivered = respond(id, response);
                if (!keep) return;
                if (!delivered) {
                    cancel();
                    return;
                }
                synchronized (this) { streaming = true; }
            }

            private void opened(EditorEventStream stream) {
                boolean open = stream.open();
                Runnable closeNow = null;
                synchronized (this) {
                    if (open) {
                        onClose = stream.onClose();
                        if (cancelled) closeNow = onClose;
                    }
                }
                if (closeNow != null) {
                    closeNow.run();
                    return;
                }
                answer(stream.response(), open);
            }

            /** The browser left, the relay closed the id, or the connection ended. */
            private void cancel() {
                Runnable close;
                synchronized (this) {
                    if (cancelled) return;
                    cancelled = true;
                    close = onClose;
                }
                routes.remove(id, this);
                if (close != null) close.run();
            }

            @Override
            public boolean send(JsonObject event) {
                synchronized (this) { if (!streaming || cancelled) return false; }
                JsonObject frame = new JsonObject();
                frame.addProperty("type", "event");
                frame.addProperty("id", id);
                frame.add("event", event);
                return Connection.this.send(frame);
            }

            @Override
            public void close() {
                synchronized (this) {
                    if (cancelled) return;
                    cancelled = true;
                }
                if (routes.remove(id, this)) {
                    JsonObject frame = new JsonObject();
                    frame.addProperty("type", "close");
                    frame.addProperty("id", id);
                    Connection.this.send(frame);
                }
            }
        }
    }

    private static String string(JsonObject frame, String field) {
        JsonElement value = frame.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException(field + " must be a string");
        return value.getAsString();
    }

    private static long number(JsonObject frame, String field) {
        JsonElement value = frame.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException(field + " must be a number");
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        return primitive.getAsBigDecimal().longValueExact();
    }

    private static long id(JsonObject frame) {
        long id = number(frame, "id");
        if (id < 1 || id > MAX_ID) throw new IllegalArgumentException("id out of range");
        return id;
    }
}
