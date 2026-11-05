package art.arcane.animus.core.building.schematic;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.GZIPInputStream;

/**
 * A bounded reader for Java edition binary NBT (big-endian), raw or gzipped. Every declared length is checked against
 * the bytes that remain before anything is allocated, so a hostile file cannot make it allocate more than its own size.
 */
public final class NbtReader {
    /** Size, nesting and tag-count limits. Depth counts nested compound and list levels, the root compound being 1. */
    public record Limits(int maximumBytes, int maximumDepth, int maximumTags) {
        public static final Limits DEFAULT = new Limits(64 * 1024 * 1024, 64, 2_000_000);

        public Limits {
            if (maximumBytes < 1 || maximumDepth < 1 || maximumTags < 1) throw new IllegalArgumentException("NBT limits must be positive");
        }
    }

    /** The named root compound of a file. */
    public record Root(String name, NbtTag.Compound value) {
        public Root {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(value, "value");
        }
    }

    private final byte[] data;
    private final Limits limits;
    private int cursor;
    private int tags;

    private NbtReader(byte[] data, Limits limits) {
        this.data = data;
        this.limits = limits;
    }

    /** Reads a whole file; trailing bytes after the root compound are ignored. */
    public static Root read(byte[] file, Limits limits) throws SchematicException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(limits, "limits");
        byte[] bytes = gzipped(file) ? gunzip(file, limits.maximumBytes()) : file;
        if (bytes.length > limits.maximumBytes()) throw new SchematicException("data size exceeds " + limits.maximumBytes() + " bytes");
        NbtReader reader = new NbtReader(bytes, limits);
        try {
            return reader.root();
        } catch (RuntimeException failure) {
            throw new SchematicException("unreadable NBT data: " + failure.getMessage(), failure);
        }
    }

    public static Root read(byte[] file) throws SchematicException {
        return read(file, Limits.DEFAULT);
    }

    private static boolean gzipped(byte[] file) {
        return file.length >= 2 && (file[0] & 0xFF) == 0x1f && (file[1] & 0xFF) == 0x8b;
    }

    private static byte[] gunzip(byte[] file, int maximumBytes) throws SchematicException {
        try (InputStream input = new GZIPInputStream(new ByteArrayInputStream(file))) {
            ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maximumBytes, Math.max(64, file.length * 4)));
            byte[] buffer = new byte[16384];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) > 0) {
                total += read;
                if (total > maximumBytes) throw new SchematicException("decompressed size exceeds " + maximumBytes + " bytes");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } catch (IOException | RuntimeException failure) {
            throw new SchematicException("the gzip data is damaged: " + failure.getMessage(), failure);
        }
    }

    private Root root() throws SchematicException {
        int type = u8();
        if (type != NbtTag.COMPOUND) throw new SchematicException("the root tag is not a compound (tag id " + type + ")");
        String name = string();
        count();
        return new Root(name, compound(1));
    }

    private NbtTag payload(int type, int depth) throws SchematicException {
        return switch (type) {
            case NbtTag.BYTE -> new NbtTag.ByteTag((byte) u8());
            case NbtTag.SHORT -> new NbtTag.ShortTag(s16());
            case NbtTag.INT -> new NbtTag.IntTag(s32());
            case NbtTag.LONG -> new NbtTag.LongTag(s64());
            case NbtTag.FLOAT -> new NbtTag.FloatTag(Float.intBitsToFloat(s32()));
            case NbtTag.DOUBLE -> new NbtTag.DoubleTag(Double.longBitsToDouble(s64()));
            case NbtTag.STRING -> new NbtTag.StringTag(string());
            case NbtTag.BYTE_ARRAY -> {
                int length = length(1);
                byte[] value = new byte[length];
                System.arraycopy(data, cursor, value, 0, length);
                cursor += length;
                yield new NbtTag.ByteArrayTag(value);
            }
            case NbtTag.INT_ARRAY -> {
                int length = length(4);
                int[] value = new int[length];
                for (int index = 0; index < length; index++) value[index] = s32();
                yield new NbtTag.IntArrayTag(value);
            }
            case NbtTag.LONG_ARRAY -> {
                int length = length(8);
                long[] value = new long[length];
                for (int index = 0; index < length; index++) value[index] = s64();
                yield new NbtTag.LongArrayTag(value);
            }
            case NbtTag.LIST -> list(depth);
            case NbtTag.COMPOUND -> compound(depth);
            default -> throw new SchematicException("unknown NBT tag id " + type);
        };
    }

    private NbtTag.Compound compound(int depth) throws SchematicException {
        depth(depth);
        Map<String, NbtTag> values = new LinkedHashMap<>();
        while (true) {
            int type = u8();
            if (type == NbtTag.END) return new NbtTag.Compound(values);
            String name = string();
            count();
            values.put(name, payload(type, depth + 1));
        }
    }

    private NbtTag.ListTag list(int depth) throws SchematicException {
        depth(depth);
        int elementType = u8();
        if (elementType > NbtTag.LONG_ARRAY) throw new SchematicException("unknown NBT list element tag id " + elementType);
        int length = s32();
        if (length < 0) throw new SchematicException("negative NBT length " + length);
        if (elementType == NbtTag.END) {
            if (length != 0) throw new SchematicException("an NBT list of End tags is not empty");
            return new NbtTag.ListTag(NbtTag.END, List.of());
        }
        if ((long) length * minimumSize(elementType) > remaining())
            throw new SchematicException("NBT list length " + length + " is larger than the remaining data");
        List<NbtTag> values = new ArrayList<>(length);
        for (int index = 0; index < length; index++) {
            count();
            values.add(payload(elementType, depth + 1));
        }
        return new NbtTag.ListTag(elementType, values);
    }

    private static int minimumSize(int type) {
        return switch (type) {
            case NbtTag.BYTE, NbtTag.COMPOUND -> 1;
            case NbtTag.SHORT, NbtTag.STRING -> 2;
            case NbtTag.LONG, NbtTag.DOUBLE -> 8;
            case NbtTag.LIST -> 5;
            default -> 4;
        };
    }

    private void depth(int depth) throws SchematicException {
        if (depth > limits.maximumDepth()) throw new SchematicException("NBT nesting exceeds " + limits.maximumDepth() + " levels");
    }

    private void count() throws SchematicException {
        if (++tags > limits.maximumTags()) throw new SchematicException("NBT tag count exceeds " + limits.maximumTags());
    }

    private int remaining() {
        return data.length - cursor;
    }

    private void need(int bytes) throws SchematicException {
        if (bytes > remaining()) throw new SchematicException("unexpected end of data");
    }

    /** Reads a signed int length and checks that {@code length * elementSize} bytes remain. */
    private int length(int elementSize) throws SchematicException {
        int length = s32();
        if (length < 0) throw new SchematicException("negative NBT length " + length);
        if ((long) length * elementSize > remaining()) throw new SchematicException("NBT array length " + length + " is larger than the remaining data");
        return length;
    }

    private int u8() throws SchematicException {
        need(1);
        return data[cursor++] & 0xFF;
    }

    private short s16() throws SchematicException {
        need(2);
        int value = ((data[cursor] & 0xFF) << 8) | (data[cursor + 1] & 0xFF);
        cursor += 2;
        return (short) value;
    }

    private int s32() throws SchematicException {
        need(4);
        int value = ((data[cursor] & 0xFF) << 24) | ((data[cursor + 1] & 0xFF) << 16) | ((data[cursor + 2] & 0xFF) << 8) | (data[cursor + 3] & 0xFF);
        cursor += 4;
        return value;
    }

    private long s64() throws SchematicException {
        long high = s32() & 0xFFFFFFFFL;
        long low = s32() & 0xFFFFFFFFL;
        return (high << 32) | low;
    }

    /** A u16-length modified UTF-8 string, decoded with {@link java.io.DataInputStream#readUTF} rules. */
    private String string() throws SchematicException {
        int length = s16() & 0xFFFF;
        need(length);
        int end = cursor + length;
        StringBuilder text = new StringBuilder(length);
        while (cursor < end) {
            int first = data[cursor] & 0xFF;
            if (first < 0x80) {
                text.append((char) first);
                cursor++;
            } else if ((first & 0xE0) == 0xC0) {
                if (cursor + 2 > end) throw new SchematicException("malformed NBT string");
                int second = data[cursor + 1] & 0xFF;
                if ((second & 0xC0) != 0x80) throw new SchematicException("malformed NBT string");
                text.append((char) (((first & 0x1F) << 6) | (second & 0x3F)));
                cursor += 2;
            } else if ((first & 0xF0) == 0xE0) {
                if (cursor + 3 > end) throw new SchematicException("malformed NBT string");
                int second = data[cursor + 1] & 0xFF, third = data[cursor + 2] & 0xFF;
                if ((second & 0xC0) != 0x80 || (third & 0xC0) != 0x80) throw new SchematicException("malformed NBT string");
                text.append((char) (((first & 0x0F) << 12) | ((second & 0x3F) << 6) | (third & 0x3F)));
                cursor += 3;
            } else {
                throw new SchematicException("malformed NBT string");
            }
        }
        return text.toString();
    }
}
