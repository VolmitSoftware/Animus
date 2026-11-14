package art.arcane.animus.core.editor;

import art.arcane.animus.core.editor.transport.EditorEventSink;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Event streams of {@code WS /v1/events}. Each stream sends at most {@code framesPerSecond} frames; queued catalog changes
 * merge per document and go out in frames of at most {@link #CATALOG_FRAME_BYTES} of entries, and a stream that falls 256
 * frames behind is closed with reason {@code limit}. {@link #tick} drains one frame per stream and is called by the editor
 * service at the frame rate.
 */
public final class EditorEvents {
    public static final int QUEUE_LIMIT = 256;
    /** The most entry bytes one catalog frame carries, so a frame fits the smallest relay frame limit. */
    public static final int CATALOG_FRAME_BYTES = 48 * 1024;

    /** One authenticated stream. {@code visible} filters NPC documents by the token's groups. */
    private static final class Stream {
        final String tokenId;
        final EditorEventSink sink;
        final Predicate<String> visible;
        final Deque<JsonObject> queue = new ArrayDeque<>();
        /** Catalog changes waiting to be sent, by {@code kind\nid}: a newer version or a delete replaces the older one. */
        final Map<String, JsonObject> documents = new LinkedHashMap<>(), deleted = new LinkedHashMap<>();
        boolean closed;

        Stream(String tokenId, EditorEventSink sink, Predicate<String> visible) {
            this.tokenId = tokenId;
            this.sink = sink;
            this.visible = visible;
        }
    }

    private final List<Stream> streams = new ArrayList<>();

    /** Registers an authenticated stream. Returns a handle that removes it. */
    public synchronized Runnable add(String tokenId, EditorEventSink sink, Predicate<String> npcVisible) {
        Stream stream = new Stream(Objects.requireNonNull(tokenId), Objects.requireNonNull(sink), Objects.requireNonNull(npcVisible));
        streams.add(stream);
        return () -> remove(stream);
    }

    private synchronized void remove(Stream stream) {
        stream.closed = true;
        streams.remove(stream);
    }

    public synchronized int open() { return streams.size(); }

    public synchronized int open(String tokenId) {
        int count = 0;
        for (Stream stream : streams) if (stream.tokenId.equals(tokenId)) count++;
        return count;
    }

    /** Queues an event for every stream. Catalog and diagnostics frames show only NPCs the stream's token may see. */
    public synchronized void publish(JsonObject event) {
        String type = event.get("type").getAsString();
        for (Stream stream : new ArrayList<>(streams)) {
            if (type.equals("catalog")) {
                merge(stream, filter(event, stream.visible));
                continue;
            }
            if (type.equals("diagnostics")) {
                JsonObject filtered = filter(event, stream.visible);
                if (filtered.getAsJsonArray("documents").isEmpty()) continue;
                enqueue(stream, filtered);
                continue;
            }
            if (type.equals("change") && !visible(event.getAsJsonObject("result").getAsJsonArray("documents"), stream.visible)) continue;
            enqueue(stream, event.deepCopy());
        }
    }

    /** Sends a {@code closing} frame to the token's streams and closes them. */
    public void close(String tokenId, String reason) { closeWhere((Stream stream) -> stream.tokenId.equals(tokenId), reason); }

    public void closeAll(String reason) { closeWhere((Stream stream) -> true, reason); }

    private void closeWhere(Predicate<Stream> which, String reason) {
        List<Stream> closing = new ArrayList<>();
        synchronized (this) {
            for (Stream stream : streams) if (which.test(stream)) closing.add(stream);
            streams.removeAll(closing);
            closing.forEach((Stream stream) -> stream.closed = true);
        }
        JsonObject frame = new JsonObject();
        frame.addProperty("type", "closing");
        frame.addProperty("reason", reason);
        for (Stream stream : closing) {
            stream.sink.send(frame.deepCopy());
            stream.sink.close();
        }
    }

    /** Sends at most one frame per stream: the oldest queued frame, else the next catalog frame. */
    public void tick() {
        List<Map.Entry<Stream, JsonObject>> sends = new ArrayList<>();
        synchronized (this) {
            for (Stream stream : streams) {
                JsonObject next = stream.queue.pollFirst();
                if (next == null) next = catalogFrame(stream);
                if (next != null) sends.add(Map.entry(stream, next));
            }
        }
        List<Stream> gone = new ArrayList<>();
        for (Map.Entry<Stream, JsonObject> send : sends)
            if (!send.getKey().sink.send(send.getValue())) gone.add(send.getKey());
        for (Stream stream : gone) remove(stream);
    }

    private void enqueue(Stream stream, JsonObject frame) {
        if (stream.queue.size() >= QUEUE_LIMIT) {
            Stream late = stream;
            Thread.ofVirtual().start(() -> closeWhere((Stream candidate) -> candidate == late, "limit"));
            return;
        }
        stream.queue.addLast(frame);
    }

    private static boolean visible(JsonArray documents, Predicate<String> npcVisible) {
        for (JsonElement element : documents) if (allowed(element.getAsJsonObject(), npcVisible)) return true;
        return documents.isEmpty();
    }

    private static boolean allowed(JsonObject document, Predicate<String> npcVisible) {
        return !document.get("kind").getAsString().equals("npc") || npcVisible.test(document.get("id").getAsString());
    }

    private static JsonObject filter(JsonObject event, Predicate<String> npcVisible) {
        JsonObject copy = new JsonObject();
        copy.addProperty("type", event.get("type").getAsString());
        JsonArray documents = new JsonArray();
        for (JsonElement element : event.getAsJsonArray("documents")) if (allowed(element.getAsJsonObject(), npcVisible)) documents.add(element.deepCopy());
        copy.add("documents", documents);
        if (event.has("deleted")) {
            JsonArray deleted = new JsonArray();
            for (JsonElement element : event.getAsJsonArray("deleted")) if (allowed(element.getAsJsonObject(), npcVisible)) deleted.add(element.deepCopy());
            copy.add("deleted", deleted);
        }
        return copy;
    }

    /** Later versions replace earlier ones; a delete removes a queued version and the other way round. */
    private static void merge(Stream stream, JsonObject event) {
        for (JsonElement element : event.getAsJsonArray("documents")) {
            JsonObject document = element.getAsJsonObject();
            String key = key(document);
            stream.deleted.remove(key);
            stream.documents.remove(key);
            stream.documents.put(key, document);
        }
        if (event.has("deleted")) for (JsonElement element : event.getAsJsonArray("deleted")) {
            JsonObject reference = element.getAsJsonObject();
            String key = key(reference);
            stream.documents.remove(key);
            stream.deleted.remove(key);
            stream.deleted.put(key, reference);
        }
    }

    private static String key(JsonObject document) { return document.get("kind").getAsString() + "\n" + document.get("id").getAsString(); }

    /** The oldest waiting catalog changes, versions first, up to {@link #CATALOG_FRAME_BYTES}; at least one entry. Null when none wait. */
    private static JsonObject catalogFrame(Stream stream) {
        if (stream.documents.isEmpty() && stream.deleted.isEmpty()) return null;
        JsonArray documents = new JsonArray(), deleted = new JsonArray();
        int bytes = 0;
        for (Map<String, JsonObject> waiting : List.of(stream.documents, stream.deleted)) {
            JsonArray into = waiting == stream.documents ? documents : deleted;
            for (Iterator<JsonObject> iterator = waiting.values().iterator(); iterator.hasNext(); ) {
                JsonObject entry = iterator.next();
                int size = entry.toString().getBytes(StandardCharsets.UTF_8).length;
                if (bytes > 0 && bytes + size > CATALOG_FRAME_BYTES) break;
                bytes += size;
                into.add(entry);
                iterator.remove();
            }
            if (!waiting.isEmpty()) break;
        }
        JsonObject frame = new JsonObject();
        frame.addProperty("type", "catalog");
        frame.add("documents", documents);
        if (!deleted.isEmpty()) frame.add("deleted", deleted);
        return frame;
    }
}
