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
    private static final int HEAD_BYTES = 16 * 1024, WEBSOCKET_FRAME_BYTES = 64 * 1024, REQUESTS_PER_CONNECTION = 1000;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final String WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private volatile EditorSettings settings;
    private final Path dataDirectory;
    private final EditorRequestHandler handler;
    private final Consumer<String> log;
    private final Semaphore connections;
    private final Set<Socket> open = ConcurrentHashMap.newKeySet();
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
        if (socket == null) return "Editor listener stopped";
        return "Editor listener " + (settings.tls() ? "https" : "http") + "://" + settings.listenAddress() + ":" + socket.getLocalPort() + BASE
                + " | connections=" + (settings.openConnections() - connections.availablePermits()) + "/" + settings.openConnections();
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
            if (!connections.tryAcquire()) {
                Thread.ofVirtual().start(() -> refuse(socket));
                continue;
            }
            open.add(socket);
            if (closed) {
                open.remove(socket);
                connections.release();
                try { socket.close(); }
                catch (IOException ignored) { /* Closing while stopping. */ }
                return;
            }
            Thread.ofVirtual().name("Animus-editor-connection").start(() -> {
                try { serve(socket); }
                finally {
                    open.remove(socket);
                    connections.release();
                }
            });
        }
    }

    private void refuse(Socket socket) {
        try (socket) {
            socket.setSoTimeout(2000);
            write(socket.getOutputStream(), EditorReplies.error(new EditorError(EditorError.Code.BUSY, "The editor listener has its maximum of connections open.", 1000, null)), false);
        } catch (IOException ignored) { /* The client left first. */ }
    }

    private record Head(String method, String target, Map<String, String> headers) {}

    private void serve(Socket socket) {
        String address = socket.getInetAddress().getHostAddress();
        try (socket) {
            socket.setSoTimeout(15_000);
            socket.setTcpNoDelay(true);
            InputStream input = new BufferedInputStream(socket.getInputStream());
            OutputStream output = socket.getOutputStream();
            for (int served = 0; served < REQUESTS_PER_CONNECTION && !closed; served++) {
                Head head;
                try { head = head(input); }
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
                long limit = target.startsWith("/v1/imports") ? settings.importBodyBytes() : settings.requestBodyBytes();
                if (length < 0 || length > limit) {
                    write(output, EditorReplies.error(new EditorError(EditorError.Code.TOO_LARGE, "Request bodies are limited to " + limit + " bytes here.")), false);
                    return;
                }
                socket.setSoTimeout(60_000);
                byte[] body = input.readNBytes((int) length);
                if (body.length != length) return;
                socket.setSoTimeout(15_000);
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

    /** Reads a request line and headers, or null at a clean end of stream. */
    private static Head head(InputStream input) throws IOException, EditorError {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int state = 0, read;
        while (state < 4) {
            read = input.read();
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
        WebSocketSink sink = new WebSocketSink(socket, output);
        String accept;
        try { accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + WEBSOCKET_GUID).getBytes(StandardCharsets.US_ASCII))); }
        catch (NoSuchAlgorithmException missing) { throw new IOException(missing); }
        synchronized (sink) {
            output.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            output.flush();
        }
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

    /** Server-to-browser frames, unmasked, serialized per connection. */
    private static final class WebSocketSink implements EditorEventSink {
        private final Socket socket;
        private final OutputStream output;
        volatile boolean closed;

        WebSocketSink(Socket socket, OutputStream output) {
            this.socket = socket;
            this.output = output;
        }

        synchronized void frame(int opcode, byte[] payload) throws IOException {
            if (closed && opcode != 0x8) throw new SocketException("closed");
            ByteArrayOutputStream frame = new ByteArrayOutputStream(payload.length + 10);
            frame.write(0x80 | opcode);
            if (payload.length < 126) frame.write(payload.length);
            else if (payload.length < 65536) { frame.write(126); frame.write(payload.length >>> 8); frame.write(payload.length & 0xFF); }
            else {
                frame.write(127);
                for (int shift = 56; shift >= 0; shift -= 8) frame.write((int) (((long) payload.length >>> shift) & 0xFF));
            }
            frame.write(payload);
            output.write(frame.toByteArray());
            output.flush();
        }

        @Override public boolean send(JsonObject event) {
            try { frame(0x1, GSON.toJson(event).getBytes(StandardCharsets.UTF_8)); return true; }
            catch (IOException gone) { closed = true; return false; }
        }

        @Override public void close() { close(1000, "closing"); }

        synchronized void close(int code, String reason) {
            if (closed) return;
            closed = true;
            byte[] text = reason.getBytes(StandardCharsets.UTF_8);
            byte[] payload = new byte[2 + Math.min(text.length, 120)];
            payload[0] = (byte) (code >>> 8);
            payload[1] = (byte) code;
            System.arraycopy(text, 0, payload, 2, payload.length - 2);
            try { frame(0x8, payload); }
            catch (IOException gone) { /* Already closed by the browser. */ }
            try { socket.close(); }
            catch (IOException ignored) { /* Closing twice is harmless. */ }
        }
    }
}
