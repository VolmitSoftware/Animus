package art.arcane.animus.core.migration;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads the Java object stream Citizens writes for item meta: Bukkit's {@code BukkitObjectOutputStream} around a
 * {@code ConfigurationSerializable} map. Nothing is instantiated; only the data of known JDK, Guava and Bukkit
 * container classes is read. Maps become JSON objects, lists arrays, boxed numbers and booleans primitives and enum
 * constants their names. Any other class becomes {@code {"?class": name}}, which callers treat as unreadable.
 */
final class JavaSerialized {
    static final String UNKNOWN = "?class";
    private static final int MAX_DEPTH = 64, MAX_HANDLES = 100_000, MAX_ITEMS = 100_000;
    private static final int TC_NULL = 0x70, TC_REFERENCE = 0x71, TC_CLASSDESC = 0x72, TC_OBJECT = 0x73, TC_STRING = 0x74, TC_ARRAY = 0x75,
            TC_CLASS = 0x76, TC_BLOCKDATA = 0x77, TC_ENDBLOCKDATA = 0x78, TC_LONGSTRING = 0x7C, TC_ENUM = 0x7E;
    private static final int SC_WRITE_METHOD = 0x01, SC_SERIALIZABLE = 0x02, SC_EXTERNALIZABLE = 0x04;

    private record Field(char type, String name) {}
    private record Descriptor(String name, int flags, List<Field> fields, Descriptor parent) {}

    private final byte[] bytes;
    private int position, depth;
    private final List<Object> handles = new ArrayList<>();

    private JavaSerialized(byte[] bytes) { this.bytes = bytes; }

    /** The decoded value of a base64 Java object stream, or empty when it is not one this reader understands. */
    static Optional<JsonElement> decode(String base64) {
        byte[] data;
        try { data = Base64.getDecoder().decode(base64); }
        catch (IllegalArgumentException invalid) { return Optional.empty(); }
        JavaSerialized reader = new JavaSerialized(data);
        try {
            if (reader.u2() != 0xACED || reader.u2() != 5) return Optional.empty();
            Object value = reader.content();
            if (reader.position != data.length) return Optional.empty();
            return Optional.of(value instanceof JsonElement element ? element : JsonNull.INSTANCE);
        } catch (IllegalStateException | IndexOutOfBoundsException invalid) { return Optional.empty(); }
    }

    private int u1() { return bytes[position++] & 0xFF; }
    private int u2() { return u1() << 8 | u1(); }
    private int s4() { return u2() << 16 | u2(); }
    private void skip(int count) {
        if (count < 0 || position + count > bytes.length) throw new IllegalStateException("truncated");
        position += count;
    }
    private String utf(int length) {
        if (length < 0 || position + length > bytes.length) throw new IllegalStateException("truncated");
        String text = modifiedUtf8(bytes, position, length);
        position += length;
        return text;
    }

    /** Java's modified UTF-8: NUL as two bytes and supplementary characters as surrogate pairs. */
    static String modifiedUtf8(byte[] data, int start, int length) {
        StringBuilder text = new StringBuilder(length);
        int index = start, end = start + length;
        while (index < end) {
            int a = data[index++] & 0xFF;
            if (a < 0x80) text.append((char) a);
            else if ((a & 0xE0) == 0xC0) {
                if (index >= end) throw new IllegalStateException("utf");
                text.append((char) ((a & 0x1F) << 6 | data[index++] & 0x3F));
            } else if ((a & 0xF0) == 0xE0) {
                if (index + 1 >= end) throw new IllegalStateException("utf");
                int b = data[index++] & 0x3F, c = data[index++] & 0x3F;
                text.append((char) ((a & 0x0F) << 12 | b << 6 | c));
            } else throw new IllegalStateException("utf");
        }
        return text.toString();
    }

    private int handle(Object value) {
        if (handles.size() >= MAX_HANDLES) throw new IllegalStateException("handles");
        handles.add(value);
        return handles.size() - 1;
    }

    private Object content() {
        if (++depth > MAX_DEPTH) throw new IllegalStateException("depth");
        try {
            int tag = u1();
            return switch (tag) {
                case TC_NULL -> JsonNull.INSTANCE;
                case TC_REFERENCE -> {
                    int index = s4() - 0x7E0000;
                    if (index < 0 || index >= handles.size() || handles.get(index) == null) throw new IllegalStateException("reference");
                    yield handles.get(index);
                }
                case TC_STRING -> { JsonPrimitive text = new JsonPrimitive(utf(u2())); handle(text); yield text; }
                case TC_LONGSTRING -> {
                    if (s4() != 0) throw new IllegalStateException("long string");
                    JsonPrimitive text = new JsonPrimitive(utf(s4())); handle(text); yield text;
                }
                case TC_CLASSDESC -> descriptor();
                case TC_OBJECT -> object();
                case TC_ARRAY -> array();
                case TC_ENUM -> {
                    descriptorOf(content());
                    int index = handle(null);
                    Object name = content();
                    if (!(name instanceof JsonPrimitive constant) || !constant.isString()) throw new IllegalStateException("enum");
                    handles.set(index, constant);
                    yield constant;
                }
                case TC_CLASS -> { descriptorOf(content()); JsonObject type = unknown("java.lang.Class"); handle(type); yield type; }
                default -> throw new IllegalStateException("tag " + tag);
            };
        } finally { depth--; }
    }

    private static Descriptor descriptorOf(Object value) {
        if (value instanceof Descriptor descriptor) return descriptor;
        if (value == JsonNull.INSTANCE) return null;
        throw new IllegalStateException("descriptor");
    }

    private Descriptor descriptor() {
        String name = utf(u2());
        skip(8);
        int flags = u1();
        int count = u2();
        List<Field> fields = new ArrayList<>();
        int index = handle(null);
        for (int i = 0; i < count; i++) {
            char type = (char) u1();
            String field = utf(u2());
            if (type == 'L' || type == '[') {
                Object className = content();
                if (!(className instanceof JsonPrimitive)) throw new IllegalStateException("field type");
            } else if ("BCDFIJSZ".indexOf(type) < 0) throw new IllegalStateException("field type");
            fields.add(new Field(type, field));
        }
        annotations();
        Descriptor descriptor = new Descriptor(name, flags, List.copyOf(fields), descriptorOf(content()));
        handles.set(index, descriptor);
        return descriptor;
    }

    /** Class or object annotations up to the end marker; block data is returned as raw bytes. */
    private List<Object> annotations() {
        List<Object> values = new ArrayList<>();
        while (true) {
            if (position >= bytes.length) throw new IllegalStateException("truncated");
            int tag = bytes[position] & 0xFF;
            if (tag == TC_ENDBLOCKDATA) { position++; return values; }
            if (tag == TC_BLOCKDATA) {
                position++;
                int length = u1();
                int start = position;
                skip(length);
                values.add(java.util.Arrays.copyOfRange(bytes, start, start + length));
            } else values.add(content());
            if (values.size() > MAX_ITEMS) throw new IllegalStateException("annotations");
        }
    }

    private Object primitive(char type) {
        return switch (type) {
            case 'B' -> new JsonPrimitive((long) (byte) u1());
            case 'C' -> new JsonPrimitive(String.valueOf((char) u2()));
            case 'S' -> new JsonPrimitive((long) (short) u2());
            case 'I' -> new JsonPrimitive((long) s4());
            case 'J' -> {
                long high = s4() & 0xFFFFFFFFL, low = s4() & 0xFFFFFFFFL;
                long value = high << 32 | low;
                yield Math.abs(value) <= (1L << 53) ? new JsonPrimitive(value) : new JsonPrimitive(Long.toString(value));
            }
            case 'F' -> new JsonPrimitive((double) Float.intBitsToFloat(s4()));
            case 'D' -> {
                long high = s4() & 0xFFFFFFFFL, low = s4() & 0xFFFFFFFFL;
                yield new JsonPrimitive(Double.longBitsToDouble(high << 32 | low));
            }
            case 'Z' -> new JsonPrimitive(u1() != 0);
            default -> throw new IllegalStateException("primitive");
        };
    }

    private Object object() {
        Descriptor descriptor = descriptorOf(content());
        if (descriptor == null) throw new IllegalStateException("object");
        int index = handle(null);
        List<Descriptor> chain = new ArrayList<>();
        for (Descriptor at = descriptor; at != null; at = at.parent()) chain.addFirst(at);
        Map<String, Object> fields = new LinkedHashMap<>();
        List<Object> written = new ArrayList<>();
        for (Descriptor at : chain) {
            if ((at.flags() & SC_EXTERNALIZABLE) != 0) throw new IllegalStateException("externalizable");
            if ((at.flags() & SC_SERIALIZABLE) == 0) continue;
            for (Field field : at.fields()) fields.put(field.name(), field.type() == 'L' || field.type() == '[' ? content() : primitive(field.type()));
            if ((at.flags() & SC_WRITE_METHOD) != 0) written.addAll(annotations());
        }
        JsonElement value = interpret(descriptor.name(), fields, written);
        handles.set(index, value);
        return value;
    }

    private Object array() {
        Descriptor descriptor = descriptorOf(content());
        if (descriptor == null || descriptor.name().length() < 2 || descriptor.name().charAt(0) != '[') throw new IllegalStateException("array");
        int index = handle(null);
        int length = s4();
        if (length < 0 || length > MAX_ITEMS || length > bytes.length - position) throw new IllegalStateException("array length");
        char type = descriptor.name().charAt(1);
        JsonArray array = new JsonArray();
        handles.set(index, array);
        for (int i = 0; i < length; i++) {
            Object value = type == 'L' || type == '[' ? content() : primitive(type);
            array.add(value instanceof JsonElement element ? element : unknown("?"));
        }
        return array;
    }

    private static JsonObject unknown(String name) {
        JsonObject object = new JsonObject();
        object.addProperty(UNKNOWN, name);
        return object;
    }

    private static JsonElement element(Object value) { return value instanceof JsonElement element ? element : unknown("?"); }

    private static List<JsonElement> objects(List<Object> written) {
        List<JsonElement> values = new ArrayList<>();
        for (Object value : written) if (!(value instanceof byte[])) values.add(element(value));
        return values;
    }

    private static JsonElement interpret(String name, Map<String, Object> fields, List<Object> written) {
        switch (name) {
            case "java.lang.Boolean", "java.lang.Integer", "java.lang.Long", "java.lang.Short", "java.lang.Byte", "java.lang.Double", "java.lang.Float",
                 "java.lang.Character" -> { return element(fields.get("value")); }
            case "org.bukkit.util.io.Wrapper" -> { return element(fields.get("map")); }
            case "java.util.ArrayList", "java.util.LinkedList" -> {
                JsonArray array = new JsonArray();
                objects(written).forEach(array::add);
                return array;
            }
            case "java.util.HashMap", "java.util.LinkedHashMap", "java.util.TreeMap" -> {
                List<JsonElement> values = objects(written);
                if (values.size() % 2 != 0) return unknown(name);
                return map(values.subList(0, values.size() / 2 * 2), name);
            }
            default -> { }
        }
        if (name.startsWith("com.google.common.collect.Immutable") && name.endsWith("$SerializedForm")) {
            if (fields.get("keys") instanceof JsonArray keys && fields.get("values") instanceof JsonArray values && keys.size() == values.size()) {
                List<JsonElement> pairs = new ArrayList<>();
                for (int i = 0; i < keys.size(); i++) { pairs.add(keys.get(i)); pairs.add(values.get(i)); }
                return map(pairs, name);
            }
            if (fields.get("elements") instanceof JsonArray elements) return elements;
        }
        if (name.equals("java.util.Arrays$ArrayList") && fields.get("a") instanceof JsonArray elements) return elements;
        if (name.startsWith("java.util.Collections$Unmodifiable") && name.endsWith("List") && fields.get("list") instanceof JsonArray list) return list;
        return unknown(name);
    }

    private static JsonElement map(List<JsonElement> pairs, String name) {
        JsonObject object = new JsonObject();
        for (int i = 0; i + 1 < pairs.size(); i += 2) {
            JsonElement key = pairs.get(i);
            if (!key.isJsonPrimitive() || object.has(key.getAsString())) return unknown(name);
            object.add(key.getAsString(), pairs.get(i + 1));
        }
        return object;
    }
}
