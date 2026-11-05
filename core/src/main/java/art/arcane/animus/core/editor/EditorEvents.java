package art.arcane.animus.core.editor;

import art.arcane.animus.core.editor.transport.EditorEventSink;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Event streams of {@code WS /v1/events}. Each stream sends at most {@code framesPerSecond} frames; queued catalog frames
 * merge into one, and a stream that falls 256 frames behind is closed with reason {@code limit}. {@link #tick} drains one
 * frame per stream and is called by the editor service at the frame rate.
 */
public final class EditorEvents {
    public static final int QUEUE_LIMIT = 256;

    /** One authenticated stream. {@code visible} filters NPC documents by the token's groups. */
    private static final class Stream {
        final String tokenId;
        final EditorEventSink sink;
        final Predicate<String> visible;
        final Deque<JsonObject> queue = new ArrayDeque<>();
        JsonObject catalog;
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
                JsonObject filtered = filter(event, stream.visible);
                if (filtered.getAsJsonArray("documents").isEmpty() && (!filtered.has("deleted") || filtered.getAsJsonArray("deleted").isEmpty())) continue;
                stream.catalog = stream.catalog == null ? filtered : merge(stream.catalog, filtered);
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

    /** Sends at most one frame per stream: the oldest queued frame, else the merged catalog frame. */
    public void tick() {
        List<Map.Entry<Stream, JsonObject>> sends = new ArrayList<>();
        synchronized (this) {
            for (Stream stream : streams) {
                JsonObject next = stream.queue.pollFirst();
                if (next == null && stream.catalog != null) { next = stream.catalog; stream.catalog = null; }
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
    private static JsonObject merge(JsonObject earlier, JsonObject later) {
        Map<String, JsonObject> documents = new LinkedHashMap<>(), deleted = new LinkedHashMap<>();
        for (JsonObject frame : List.of(earlier, later)) {
            for (JsonElement element : frame.getAsJsonArray("documents")) {
                JsonObject document = element.getAsJsonObject();
                String key = document.get("kind").getAsString() + "\n" + document.get("id").getAsString();
                deleted.remove(key);
                documents.put(key, document);
            }
            if (frame.has("deleted")) for (JsonElement element : frame.getAsJsonArray("deleted")) {
                JsonObject reference = element.getAsJsonObject();
                String key = reference.get("kind").getAsString() + "\n" + reference.get("id").getAsString();
                documents.remove(key);
                deleted.put(key, reference);
            }
        }
        JsonObject merged = new JsonObject();
        merged.addProperty("type", "catalog");
        JsonArray list = new JsonArray();
        documents.values().forEach(list::add);
        merged.add("documents", list);
        if (!deleted.isEmpty()) {
            JsonArray removed = new JsonArray();
            deleted.values().forEach(removed::add);
            merged.add("deleted", removed);
        }
        return merged;
    }
}
