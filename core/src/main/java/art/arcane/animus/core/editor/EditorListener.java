package art.arcane.animus.core.editor;

import art.arcane.animus.core.config.EditorSettings;
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
import java.util.Optional;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;

/**
 * The embedded HTTP/1.1 and WebSocket listener for the direct path, at {@code /animus/v1/}. Plain HTTP only on a loopback
 * address; anything else needs TLS from the operator's PEM files. Every connection runs on its own virtual thread and the
 * total is bounded by {@code openConnections}; heads are limited to 16 KiB and bodies to the configured sizes before they
 * are read. Requests go to the shared {@link EditorRequestHandler}, as relayed ones do. The import upload's raw bytes reach
 * the handler as a base64 JSON string, the form the relay carries too.
 */
public final class EditorListener implements AutoCloseable {
    public static final String BASE = "/animus";
    /** Connections one address may hold at once, so slow or idle clients from one place cannot take every slot. */
    public static final int CONNECTIONS_PER_ADDRESS = 16;
    /** The largest body a request without a valid token may send: pairing and the open endpoints. */
    public static final int OPEN_BODY_BYTES = 4096;
    private static final int HEAD_BYTES = 16 * 1024, WEBSOCKET_FRAME_BYTES = 64 * 1024, REQUESTS_PER_CONNECTION = 1000;
    /** An idle connection waits this long for the next request; once its first byte arrives the whole head must follow within {@link #HEAD_MILLIS}. */
    private static final int IDLE_MILLIS = 15_000, HEAD_MILLIS = 5_000;
    /**
     * A body without a token must arrive whole within {@link #OPEN_BODY_MILLIS}; an authenticated one gets
     * {@link #BODY_MILLIS} plus a millisecond for each {@link #BODY_BYTES_PER_MILLI} bytes, however slowly it trickles.
     */
    private static final int OPEN_BODY_MILLIS = 5_000, BODY_MILLIS = 30_000, BODY_BYTES_PER_MILLI = 16;
    /** Keeps null members: documents carry them on purpose, such as an emptied slot's {@code "item": null}. */
    private static final Gson GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
    private static final String WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private volatile EditorSettings settings;
    private final Path dataDirectory;
    private final EditorRequestHandler handler;
    private final Consumer<String> log;
    private final Semaphore connections;
    private final Set<Socket> open = ConcurrentHashMap.newKeySet();
    private final Map<String, Integer> perAddress = new ConcurrentHashMap<>();
    private volatile ServerSocket server;
    private volatile boolean closed;
    private Thread acceptor;

    public EditorListener(EditorSettings settings, Path dataDirectory, EditorRequestHandler handler, Consumer<String> log) {
        this.settings = Objects.requireNonNull(settings);
        this.dataDirectory = Objects.requireNonNull(dataDirectory);
        this.handler = Objects.requireNonNull(handler);
        this.log = Objects.requireNonNull(log);
        connections = new Semaphore(settings.openConnections());
    }

    public synchronized void start() throws IOException {
        if (server != null) throw new IllegalStateException("The editor listener is already running");
        InetAddress address = InetAddress.getByName(settings.listenAddress());
        if (!settings.tls() && !address.isLoopbackAddress())
            throw new IOException("The editor listener serves plain HTTP only on loopback; " + settings.listenAddress() + " needs tlsCertificate and tlsPrivateKey");
        ServerSocket socket;
        if (settings.tls()) {
            SSLContext context = PemTls.context(dataDirectory.resolve(settings.tlsCertificate()), dataDirectory.resolve(settings.tlsPrivateKey()));
            SSLServerSocket secure = (SSLServerSocket) context.getServerSocketFactory().createServerSocket();
            secure.setEnabledProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
            socket = secure;
        } else socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(address, settings.port()), 128);
        server = socket;
        acceptor = Thread.ofPlatform().daemon().name("Animus-editor-listener").start(this::accept);
    }

    /** True when {@code next} needs a new socket: the address, port, TLS files, connection bound or the switch changed. */
    public static boolean rebind(EditorSettings current, EditorSettings next) {
        return current.listenerEnabled() != next.listenerEnabled() || !current.listenAddress().equals(next.listenAddress()) || current.port() != next.port()
                || !current.tlsCertificate().equals(next.tlsCertificate()) || !current.tlsPrivateKey().equals(next.tlsPrivateKey())
                || current.openConnections() != next.openConnections();
    }

    /** A hotload that keeps the socket: body limits apply to the next request. */
    public void settings(EditorSettings next) {
        if (rebind(settings, next)) throw new IllegalArgumentException("These settings need a new listener");
        settings = next;
    }

    /** The bound port, which differs from the setting when it was 0 in tests. */
    public int port() {
        ServerSocket socket = server;
        return socket == null ? -1 : socket.getLocalPort();
    }

    public String describe() {
        ServerSocket socket = server;
        if (socket == null) return "listener stopped";
        return "listener " + settings.listenAddress() + ":" + socket.getLocalPort() + (settings.tls() ? " (https)" : " (http)")
                + " " + (settings.openConnections() - connections.availablePermits()) + "/" + settings.openConnections() + " connections";
    }

    @Override
    public void close() {
        closed = true;
        ServerSocket socket = server;
        server = null;
        if (socket != null) {
            try { socket.close(); }
            catch (IOException ignored) { /* Closing an accepting socket only ends the accept loop. */ }
        }
        for (Socket connection : open) {
            try { connection.close(); }
            catch (IOException ignored) { /* The connection thread ends on its next read or write. */ }
        }
    }

    private void accept() {
        while (!closed) {
            Socket socket;
            try { socket = server.accept(); }
            catch (IOException | NullPointerException stopped) {
                if (!closed) log.accept("Editor listener stopped accepting: " + stopped.getMessage());
                return;
            }
            String address = addressKey(socket.getInetAddress());
            if (!connections.tryAcquire()) {
                Thread.ofVirtual().start(() -> refuse(socket, "The editor listener has its maximum of connections open."));
                continue;
            }
            if (perAddress.merge(address, 1, Integer::sum) > CONNECTIONS_PER_ADDRESS) {
                leave(address);
                connections.release();
                Thread.ofVirtual().start(() -> refuse(socket, "This address has its maximum of " + CONNECTIONS_PER_ADDRESS + " editor connections open."));
                continue;
            }
            open.add(socket);
            if (closed) {
                open.remove(socket);
                leave(address);
                connections.release();
                try { socket.close(); }
                catch (IOException ignored) { /* Closing while stopping. */ }
                return;
            }
            Thread.ofVirtual().name("Animus-editor-connection").start(() -> {
                try { serve(socket); }
                finally {
                    open.remove(socket);
                    leave(address);
                    connections.release();
                }
            });
        }
    }

    private void leave(String address) { perAddress.computeIfPresent(address, (String key, Integer count) -> count <= 1 ? null : count - 1); }

    private void refuse(Socket socket, String reason) {
        try (socket) {
            socket.setSoTimeout(2000);
            write(socket.getOutputStream(), EditorReplies.error(new EditorError(EditorError.Code.BUSY, reason, 1000, null)), false);
        } catch (IOException ignored) { /* The client left first. */ }
    }

    private record Head(String method, String target, Map<String, String> headers) {}

    private void serve(Socket socket) {
        String address = socket.getInetAddress().getHostAddress();
        try (socket) {
            socket.setTcpNoDelay(true);
            InputStream input = new BufferedInputStream(socket.getInputStream());
            OutputStream output = socket.getOutputStream();
            for (int served = 0; served < REQUESTS_PER_CONNECTION && !closed; served++) {
                Head head;
                try { head = head(socket, input); }
                catch (EOFException | SocketTimeoutException idle) { return; }
                catch (EditorError invalid) { write(output, EditorReplies.error(invalid), false); return; }
                if (head == null) return;
                if (!head.target().equals(BASE) && !head.target().startsWith(BASE + "/")) {
                    write(output, EditorReplies.error(new EditorError(EditorError.Code.NOT_FOUND, "The editor API is under " + BASE + "/v1/.")), false);
                    return;
                }
                String target = head.target().substring(BASE.length());
                if (!target.startsWith("/v1/")) {
                    write(output, EditorReplies.error(new EditorError(EditorError.Code.NOT_FOUND, "The editor API is under " + BASE + "/v1/.")), false);
                    return;
                }
                boolean upgrade = "websocket".equalsIgnoreCase(head.headers().getOrDefault("upgrade", ""));
                if (upgrade) { websocket(socket, input, output, head, target, address); return; }
                if (head.headers().containsKey("transfer-encoding")) {
                    write(output, EditorReplies.error(new EditorError(EditorError.Code.INVALID_REQUEST, "Send a Content-Length body; chunked bodies are not accepted.")), false);
                    return;
                }
                long length;
                try { length = Long.parseLong(head.headers().getOrDefault("content-length", "0").strip()); }
                catch (NumberFormatException invalid) { write(output, EditorReplies.error(new EditorError(EditorError.Code.INVALID_REQUEST, "Content-Length is not a number.")), false); return; }
                // The token and scope are checked from the head, so only an authenticated request may send a large body.
                EditorRequestHandler.Admission admission = handler.admit(new EditorRequest(EditorRequest.Transport.DIRECT, head.method(), target, headers(head, address), Optional.empty()));
                if (admission.refusal().isPresent()) { write(output, admission.refusal().get(), false); return; }
                long limit = !admission.authenticated() ? OPEN_BODY_BYTES : target.startsWith("/v1/imports") ? settings.importBodyBytes() : settings.requestBodyBytes();
                if (length < 0 || length > limit) {
                    write(output, EditorReplies.error(new EditorError(EditorError.Code.TOO_LARGE, "Request bodies are limited to " + limit + " bytes here.")), false);
                    return;
                }
                byte[] body;
                try { body = readBody(socket, input, (int) length, admission.authenticated() ? BODY_MILLIS + length / BODY_BYTES_PER_MILLI : OPEN_BODY_MILLIS); }
                catch (SocketTimeoutException slow) { return; }
                if (body.length != length) return;
                EditorResponse response;
                try {
                    EditorRequest request = new EditorRequest(EditorRequest.Transport.DIRECT, head.method(), target, headers(head, address), body(target, body));
                    response = handler.handle(request).toCompletableFuture().get(120, TimeUnit.SECONDS);
                } catch (EditorError invalid) {
                    response = EditorReplies.error(invalid);
                } catch (TimeoutException slow) {
                    response = EditorReplies.error(new EditorError(EditorError.Code.BUSY, "The request did not finish in time; check its status before retrying.", 1000, null));
                } catch (ExecutionException failure) {
                    response = EditorReplies.error(new EditorError(EditorError.Code.INTERNAL, "The server could not complete the request."));
                } catch (InterruptedException stopping) {
                    Thread.currentThread().interrupt();
                    return;
                }
                boolean keepAlive = !"close".equalsIgnoreCase(head.headers().getOrDefault("connection", ""));
                write(output, response, keepAlive);
                if (!keepAlive) return;
            }
        } catch (IOException gone) {
            // The client closed the connection or the TLS handshake failed; nothing is owed to it.
        }
    }

    /**
     * The key an address's connections are counted under: an IPv4 address itself, an IPv6 address by its {@code /64}, since
     * one host can usually choose any address inside its {@code /64}.
     */
    static String addressKey(InetAddress address) {
        if (!(address instanceof java.net.Inet6Address)) return address.getHostAddress();
        byte[] bytes = address.getAddress();
        StringBuilder prefix = new StringBuilder();
        for (int index = 0; index < 8; index += 2) prefix.append(Integer.toHexString(((bytes[index] & 0xff) << 8) | (bytes[index + 1] & 0xff))).append(':');
        return prefix.append(":/64").toString();
    }

    /** Reads exactly {@code length} bytes, or fewer at end of stream, within {@code millis} in all. */
    private static byte[] readBody(Socket socket, InputStream input, int length, long millis) throws IOException {
        byte[] body = new byte[length];
        long deadline = System.nanoTime() + millis * 1_000_000L;
        int read = 0;
        while (read < length) {
            long left = (deadline - System.nanoTime()) / 1_000_000;
            if (left <= 0) throw new SocketTimeoutException("The request body was too slow");
            socket.setSoTimeout((int) Math.min(left, Integer.MAX_VALUE));
            int count = input.read(body, read, length - read);
            if (count < 0) return java.util.Arrays.copyOf(body, read);
            read += count;
        }
        return body;
    }

    /** The client's headers with the socket's address in {@link EditorReplies#PEER_HEADER}; a client cannot supply its own. */
    private static Map<String, String> headers(Head head, String address) {
        Map<String, String> headers = new LinkedHashMap<>(head.headers());
        headers.remove(EditorReplies.PEER_HEADER.toLowerCase(Locale.ROOT));
        headers.put(EditorReplies.PEER_HEADER, address);
        return headers;
    }

    /** Strict JSON for API bodies; the import upload's bytes as a base64 string. */
    private static Optional<JsonElement> body(String target, byte[] bytes) throws EditorError {
        if (bytes.length == 0) return Optional.empty();
        String route = target.contains("?") ? target.substring(0, target.indexOf('?')) : target;
        if (route.equals("/v1/imports")) return Optional.of(new JsonPrimitive(Base64.getEncoder().encodeToString(bytes)));
        try { return Optional.of(JsonDocuments.object(new String(bytes, StandardCharsets.UTF_8))); }
        catch (IOException invalid) { throw new EditorError(EditorError.Code.INVALID_REQUEST, "The body is not one JSON object: " + invalid.getMessage()); }
    }

    /**
     * Reads a request line and headers, or null at a clean end of stream. The first byte may take {@link #IDLE_MILLIS}; the
     * rest of the head must arrive within {@link #HEAD_MILLIS} of it, however slowly it trickles.
     */
    private static Head head(Socket socket, InputStream input) throws IOException, EditorError {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int state = 0, read;
        socket.setSoTimeout(IDLE_MILLIS);
        long deadline = 0;
        while (state < 4) {
            if (deadline != 0) {
                long left = (deadline - System.nanoTime()) / 1_000_000;
                if (left <= 0) throw new SocketTimeoutException("The request head was too slow");
                socket.setSoTimeout((int) left);
            }
            read = input.read();
            if (deadline == 0) deadline = System.nanoTime() + HEAD_MILLIS * 1_000_000L;
            if (read < 0) {
                if (bytes.size() == 0) return null;
                throw new EOFException();
            }
            bytes.write(read);
            if (bytes.size() > HEAD_BYTES) throw new EditorError(EditorError.Code.TOO_LARGE, "Request heads are limited to " + HEAD_BYTES + " bytes.");
            state = switch (state) {
                case 0, 2 -> read == '\r' ? state + 1 : 0;
                case 1, 3 -> read == '\n' ? state + 1 : read == '\r' ? 1 : 0;
                default -> 0;
            };
        }
        String[] lines = bytes.toString(StandardCharsets.ISO_8859_1).split("\r\n");
        String[] request = lines[0].split(" ");
        if (request.length != 3 || !request[2].startsWith("HTTP/1.") || !request[1].startsWith("/"))
            throw new EditorError(EditorError.Code.INVALID_REQUEST, "Expected an HTTP/1.1 request line.");
        Map<String, String> headers = new LinkedHashMap<>();
        for (int index = 1; index < lines.length; index++) {
            if (lines[index].isEmpty()) continue;
            int colon = lines[index].indexOf(':');
            if (colon < 1) throw new EditorError(EditorError.Code.INVALID_REQUEST, "Malformed header line.");
            String name = lines[index].substring(0, colon).strip().toLowerCase(Locale.ROOT);
            String value = lines[index].substring(colon + 1).strip();
            if (headers.containsKey(name) && (name.equals("content-length") || name.equals("authorization") || name.equals("origin")))
                throw new EditorError(EditorError.Code.INVALID_REQUEST, "Header " + name + " appears twice.");
            headers.merge(name, value, (String first, String second) -> first + ", " + second);
        }
        return new Head(request[0], request[1], headers);
    }

    private static void write(OutputStream output, EditorResponse response, boolean keepAlive) throws IOException {
        StringBuilder head = new StringBuilder("HTTP/1.1 ").append(response.status()).append(' ').append(reason(response.status())).append("\r\n");
        byte[] body = response.body().map((JsonElement json) -> GSON.toJson(json).getBytes(StandardCharsets.UTF_8)).orElse(new byte[0]);
        if (response.body().isPresent()) head.append("Content-Type: application/json; charset=utf-8\r\n");
        head.append("Content-Length: ").append(body.length).append("\r\n");
        head.append("Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\n");
        for (Map.Entry<String, String> header : response.headers().entrySet()) {
            if (header.getKey().matches("[A-Za-z0-9-]+") && !header.getValue().contains("\r") && !header.getValue().contains("\n"))
                head.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
        }
        head.append("Connection: ").append(keepAlive ? "keep-alive" : "close").append("\r\n\r\n");
        output.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        output.write(body);
        output.flush();
    }

    private static String reason(int status) {
        return switch (status) {
            case 101 -> "Switching Protocols"; case 200 -> "OK"; case 204 -> "No Content"; case 400 -> "Bad Request"; case 401 -> "Unauthorized";
            case 403 -> "Forbidden"; case 404 -> "Not Found"; case 409 -> "Conflict"; case 410 -> "Gone"; case 413 -> "Content Too Large";
            case 422 -> "Unprocessable Content"; case 429 -> "Too Many Requests"; case 503 -> "Service Unavailable"; default -> "Error";
        };
    }

    private void websocket(Socket socket, InputStream input, OutputStream output, Head head, String target, String address) throws IOException {
        String key = head.headers().get("sec-websocket-key");
        if (!head.method().equals("GET") || !target.equals("/v1/events") || key == null || !"13".equals(head.headers().get("sec-websocket-version"))
                || !head.headers().getOrDefault("connection", "").toLowerCase(Locale.ROOT).contains("upgrade")) {
            write(output, EditorReplies.error(new EditorError(EditorError.Code.INVALID_REQUEST, "Only GET /animus/v1/events upgrades to a WebSocket (version 13).")), false);
            return;
        }
        String accept;
        try { accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + WEBSOCKET_GUID).getBytes(StandardCharsets.US_ASCII))); }
        catch (NoSuchAlgorithmException missing) { throw new IOException(missing); }
        output.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1));
        output.flush();
        WebSocketSink sink = new WebSocketSink(socket, output);
        try { stream(socket, input, sink, head, target, address); }
        finally {
            sink.close(1000, "closed");
            sink.awaitWritten();
        }
    }

    /** Authenticates the stream, then serves it until either side closes. */
    private void stream(Socket socket, InputStream input, WebSocketSink sink, Head head, String target, String address) throws IOException {
        // A browser cannot set headers on a WebSocket, so its first frame carries the token (event-auth), within 10 seconds.
        socket.setSoTimeout(10_000);
        String token;
        try {
            String first = firstText(input, sink);
            if (first == null) return;
            JsonObject frame = JsonDocuments.object(first);
            if (frame.size() != 2 || !frame.has("type") || !"auth".equals(frame.get("type").getAsString()) || !frame.has("token") || !frame.get("token").isJsonPrimitive())
                throw new IOException("expected event-auth");
            token = frame.get("token").getAsString();
        } catch (SocketTimeoutException late) { sink.close(4401, "unauthorized"); return; }
        catch (IOException | RuntimeException invalid) { sink.close(4400, "invalid-request"); return; }
        Map<String, String> headers = headers(head, address);
        headers.put("Authorization", "Bearer " + token);
        EditorEventStream stream;
        try {
            stream = handler.openEvents(new EditorRequest(EditorRequest.Transport.DIRECT, "GET", target, headers, Optional.empty()), sink).toCompletableFuture().get(10, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException failure) { sink.close(1011, "internal"); return; }
        catch (InterruptedException stopping) { Thread.currentThread().interrupt(); sink.close(1001, "server-stopping"); return; }
        if (!stream.open()) {
            String code = stream.response().body().filter(JsonElement::isJsonObject).map((JsonElement body) -> body.getAsJsonObject().getAsJsonObject("error").get("code").getAsString()).orElse("refused");
            sink.close(4000 + stream.response().status(), code);
            return;
        }
        try {
            socket.setSoTimeout(75_000);
            Thread pinger = Thread.ofVirtual().start(() -> {
                try {
                    while (!sink.closed && !closed) {
                        Thread.sleep(30_000);
                        sink.frame(0x9, new byte[0]);
                    }
                } catch (InterruptedException | IOException stopped) { /* The connection ended. */ }
            });
            try { frames(input, sink); }
            finally { pinger.interrupt(); }
        } finally {
            stream.onClose().run();
            sink.close(1000, "closed");
        }
    }

    /** The first text frame, answering pings meanwhile; null when the browser leaves first. */
    private static String firstText(InputStream input, WebSocketSink sink) throws IOException {
        while (true) {
            Frame frame = frame(input, sink);
            if (frame == null) return null;
            if (frame.opcode() == 0x1) return new String(frame.payload(), StandardCharsets.UTF_8);
            if (frame.opcode() == 0x9) sink.frame(0xA, frame.payload());
            else if (frame.opcode() == 0x8) { sink.close(1000, "closed"); return null; }
        }
    }

    private record Frame(int opcode, byte[] payload) {}

    /** One masked, unfragmented client frame of at most 64 KiB, or null at the end of the stream or after a refused frame. */
    private static Frame frame(InputStream input, WebSocketSink sink) throws IOException {
        int first = input.read(), second = input.read();
        if (first < 0 || second < 0) return null;
        boolean fin = (first & 0x80) != 0, masked = (second & 0x80) != 0;
        long length = second & 0x7F;
        if (length == 126) length = ((long) input.read() << 8) | input.read();
        else if (length == 127) {
            length = 0;
            for (int index = 0; index < 8; index++) length = (length << 8) | input.read();
        }
        if (!masked || !fin || length < 0 || length > WEBSOCKET_FRAME_BYTES) { sink.close(1009, "limit"); return null; }
        byte[] mask = input.readNBytes(4);
        byte[] payload = input.readNBytes((int) length);
        if (mask.length != 4 || payload.length != length) return null;
        for (int index = 0; index < payload.length; index++) payload[index] ^= mask[index % 4];
        return new Frame(first & 0x0F, payload);
    }

    /** After authentication the browser only pings and closes; other text is ignored. */
    private static void frames(InputStream input, WebSocketSink sink) throws IOException {
        while (!sink.closed) {
            Frame frame = frame(input, sink);
            if (frame == null) return;
            switch (frame.opcode()) {
                case 0x1, 0xA -> { }
                case 0x8 -> { sink.close(1000, "closed"); return; }
                case 0x9 -> sink.frame(0xA, frame.payload());
                default -> { sink.close(1003, "unsupported"); return; }
            }
        }
    }

    /**
     * Server-to-browser frames, unmasked. Callers only queue them; one writer thread per connection does the socket writes,
     * so a browser that stops reading blocks nobody else. Past {@link #OUTBOUND_BYTES} waiting, the backlog is dropped and
     * the stream closes with reason {@code limit}, which the editor answers by reconnecting and reloading what changed.
     */
    private static final class WebSocketSink implements EditorEventSink {
        static final int OUTBOUND_BYTES = 1024 * 1024;
        private static final long CLOSE_GRACE_MILLIS = 2000;
        private final Socket socket;
        private final OutputStream output;
        private final ArrayDeque<byte[]> queue = new ArrayDeque<>();
        private final Thread writer;
        private int queued;
        /** No frame but the close is accepted any more. */
        volatile boolean closed;
        /** The close frame is queued; the writer ends after it. */
        private boolean finished;

        WebSocketSink(Socket socket, OutputStream output) {
            this.socket = socket;
            this.output = output;
            writer = Thread.ofVirtual().name("Animus-editor-events").start(this::write);
        }

        void frame(int opcode, byte[] payload) throws IOException {
            byte[] encoded = encode(opcode, payload);
            synchronized (this) {
                if (closed) throw new SocketException("closed");
                if (queued + encoded.length > OUTBOUND_BYTES) {
                    overflow();
                    throw new SocketException("limit");
                }
                enqueue(encoded);
            }
        }

        @Override public boolean send(JsonObject event) {
            byte[] payload = GSON.toJson(event).getBytes(StandardCharsets.UTF_8);
            if ("closing".equals(event.get("type").getAsString())) {
                // The reason goes straight after what was already on the wire: the browser reloads anyway.
                synchronized (this) {
                    if (closed) return false;
                    dropBacklog();
                    enqueue(encode(0x1, payload));
                }
                return true;
            }
            try { frame(0x1, payload); return true; }
            catch (IOException gone) { return false; }
        }

        @Override public void close() { close(1000, "closing"); }

        void close(int code, String reason) {
            byte[] text = reason.getBytes(StandardCharsets.UTF_8);
            byte[] payload = new byte[2 + Math.min(text.length, 120)];
            payload[0] = (byte) (code >>> 8);
            payload[1] = (byte) code;
            System.arraycopy(text, 0, payload, 2, payload.length - 2);
            synchronized (this) {
                if (finished) return;
                closed = true;
                finished = true;
                enqueue(encode(0x8, payload));
            }
            // A browser that reads nothing would hold the writer forever; the socket closes after a grace period regardless.
            Thread.ofVirtual().start(() -> {
                try { writer.join(CLOSE_GRACE_MILLIS); }
                catch (InterruptedException stopping) { Thread.currentThread().interrupt(); }
                closeSocket();
            });
        }

        /** Waits briefly for queued frames, the close last, to reach the socket. */
        void awaitWritten() {
            try { writer.join(CLOSE_GRACE_MILLIS); }
            catch (InterruptedException stopping) { Thread.currentThread().interrupt(); }
        }

        private void overflow() {
            dropBacklog();
            JsonObject closing = new JsonObject();
            closing.addProperty("type", "closing");
            closing.addProperty("reason", "limit");
            enqueue(encode(0x1, GSON.toJson(closing).getBytes(StandardCharsets.UTF_8)));
            Thread.ofVirtual().start(() -> close(1000, "limit"));
            closed = true;
        }

        private void dropBacklog() {
            queue.clear();
            queued = 0;
        }

        private void enqueue(byte[] encoded) {
            queue.addLast(encoded);
            queued += encoded.length;
            notifyAll();
        }

        private void write() {
            try {
                while (true) {
                    byte[] next;
                    synchronized (this) {
                        while (queue.isEmpty() && !finished) wait();
                        next = queue.pollFirst();
                        if (next == null) return;
                        queued -= next.length;
                    }
                    output.write(next);
                    output.flush();
                }
            } catch (IOException | InterruptedException gone) {
                synchronized (this) { closed = true; finished = true; dropBacklog(); }
            } finally { closeSocket(); }
        }

        private void closeSocket() {
            try { socket.close(); }
            catch (IOException ignored) { /* Closing twice is harmless. */ }
        }

        private static byte[] encode(int opcode, byte[] payload) {
            ByteArrayOutputStream frame = new ByteArrayOutputStream(payload.length + 10);
            frame.write(0x80 | opcode);
            if (payload.length < 126) frame.write(payload.length);
            else if (payload.length < 65536) { frame.write(126); frame.write(payload.length >>> 8); frame.write(payload.length & 0xFF); }
            else {
                frame.write(127);
                for (int shift = 56; shift >= 0; shift -= 8) frame.write((int) (((long) payload.length >>> shift) & 0xFF));
            }
            frame.writeBytes(payload);
            return frame.toByteArray();
        }
    }
}
