package art.arcane.animus.core.building.schematic;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/** Test-only NBT writer and fixture builders. */
final class NbtWriter {
    private NbtWriter() {}

    static byte[] write(String name, NbtTag.Compound root, boolean gzip) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(gzip ? new GZIPOutputStream(bytes) : bytes)) {
                output.writeByte(NbtTag.COMPOUND);
                output.writeUTF(name);
                payload(output, root);
            }
            return bytes.toByteArray();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    static byte[] gzip(byte[] raw) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (GZIPOutputStream output = new GZIPOutputStream(bytes)) {
                output.write(raw);
            }
            return bytes.toByteArray();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static void payload(DataOutputStream output, NbtTag tag) throws IOException {
        switch (tag) {
            case NbtTag.ByteTag value -> output.writeByte(value.value());
            case NbtTag.ShortTag value -> output.writeShort(value.value());
            case NbtTag.IntTag value -> output.writeInt(value.value());
            case NbtTag.LongTag value -> output.writeLong(value.value());
            case NbtTag.FloatTag value -> output.writeFloat(value.value());
            case NbtTag.DoubleTag value -> output.writeDouble(value.value());
            case NbtTag.StringTag value -> output.writeUTF(value.value());
            case NbtTag.ByteArrayTag value -> {
                output.writeInt(value.value().length);
                output.write(value.value());
            }
            case NbtTag.IntArrayTag value -> {
                output.writeInt(value.value().length);
                for (int element : value.value()) output.writeInt(element);
            }
            case NbtTag.LongArrayTag value -> {
                output.writeInt(value.value().length);
                for (long element : value.value()) output.writeLong(element);
            }
            case NbtTag.ListTag value -> {
                output.writeByte(value.elementType());
                output.writeInt(value.size());
                for (NbtTag element : value.values()) payload(output, element);
            }
            case NbtTag.Compound value -> {
                for (Map.Entry<String, NbtTag> entry : value.values().entrySet()) {
                    output.writeByte(entry.getValue().id());
                    output.writeUTF(entry.getKey());
                    payload(output, entry.getValue());
                }
                output.writeByte(NbtTag.END);
            }
        }
    }

    /** Builds a compound from alternating key and tag arguments. */
    static NbtTag.Compound compound(Object... pairs) {
        Map<String, NbtTag> values = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) values.put((String) pairs[index], (NbtTag) pairs[index + 1]);
        return new NbtTag.Compound(values);
    }

    static NbtTag.ListTag list(int type, NbtTag... values) {
        return new NbtTag.ListTag(type, List.of(values));
    }

    static NbtTag.ListTag list(int type, List<? extends NbtTag> values) {
        return new NbtTag.ListTag(type, List.copyOf(values));
    }

    static NbtTag.IntTag integer(int value) { return new NbtTag.IntTag(value); }
    static NbtTag.ShortTag shortTag(int value) { return new NbtTag.ShortTag((short) value); }
    static NbtTag.StringTag string(String value) { return new NbtTag.StringTag(value); }
    static NbtTag.ByteArrayTag bytes(byte[] value) { return new NbtTag.ByteArrayTag(value); }
    static NbtTag.IntArrayTag ints(int... value) { return new NbtTag.IntArrayTag(value); }

    static NbtTag.ListTag intList(int... values) {
        NbtTag[] tags = new NbtTag[values.length];
        for (int index = 0; index < values.length; index++) tags[index] = integer(values[index]);
        return list(NbtTag.INT, tags);
    }

    /** Encodes unsigned VarInts as Sponge block data. */
    static byte[] varints(int... values) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int value : values) {
            int remaining = value;
            while ((remaining & ~0x7F) != 0) {
                bytes.write((remaining & 0x7F) | 0x80);
                remaining >>>= 7;
            }
            bytes.write(remaining);
        }
        return bytes.toByteArray();
    }
}
