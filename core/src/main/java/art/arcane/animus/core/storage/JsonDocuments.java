package art.arcane.animus.core.storage;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.HexFormat;

/** Bounded strict JSON, including duplicate-key rejection (Gson's tree parser alone permits them). */
public final class JsonDocuments {
    public static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final Gson GSON = new GsonBuilder().serializeNulls().setPrettyPrinting().disableHtmlEscaping().create();
    private JsonDocuments() {}

    public static String read(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IOException(path + ": exceeds " + MAX_BYTES + " bytes");
            return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        }
    }

    public static JsonObject object(String text) throws IOException {
        if (text.length() > MAX_BYTES) throw new IOException("JSON exceeds size limit");
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setLenient(false);
            JsonElement root = readValue(reader, 0);
            if (!root.isJsonObject() || reader.peek() != JsonToken.END_DOCUMENT)
                throw new IOException("$: expected exactly one JSON object");
            return root.getAsJsonObject();
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new IOException("Invalid JSON: " + e.getMessage(), e);
        }
    }

    private static JsonElement readValue(JsonReader reader, int depth) throws IOException {
        if (depth > 64) throw new IOException(reader.getPath() + ": nesting exceeds 64");
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                JsonObject object = new JsonObject();
                reader.beginObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (object.has(key)) throw new IOException(reader.getPath() + ": duplicate key");
                    object.add(key, readValue(reader, depth + 1));
                }
                reader.endObject();
                yield object;
            }
            case BEGIN_ARRAY -> {
                JsonArray array = new JsonArray();
                reader.beginArray();
                while (reader.hasNext()) array.add(readValue(reader, depth + 1));
                reader.endArray();
                yield array;
            }
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> new JsonPrimitive(new BigDecimal(reader.nextString()));
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> { reader.nextNull(); yield JsonNull.INSTANCE; }
            default -> throw new IOException(reader.getPath() + ": expected JSON value");
        };
    }

    public static String write(JsonElement value) { return GSON.toJson(value) + "\n"; }
    public static String hash(String content) {
        if (content == null) return "missing";
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
}
