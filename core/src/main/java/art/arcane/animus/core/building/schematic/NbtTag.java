package art.arcane.animus.core.building.schematic;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/** A Java edition NBT tag tree. The End tag only terminates compounds on disk and has no value form. */
public sealed interface NbtTag {
    int END = 0, BYTE = 1, SHORT = 2, INT = 3, LONG = 4, FLOAT = 5, DOUBLE = 6, BYTE_ARRAY = 7, STRING = 8, LIST = 9,
            COMPOUND = 10, INT_ARRAY = 11, LONG_ARRAY = 12;

    /** The on-disk tag id. */
    int id();

    record ByteTag(byte value) implements NbtTag {
        @Override public int id() { return BYTE; }
    }

    record ShortTag(short value) implements NbtTag {
        @Override public int id() { return SHORT; }
    }

    record IntTag(int value) implements NbtTag {
        @Override public int id() { return INT; }
    }

    record LongTag(long value) implements NbtTag {
        @Override public int id() { return LONG; }
    }

    record FloatTag(float value) implements NbtTag {
        @Override public int id() { return FLOAT; }
    }

    record DoubleTag(double value) implements NbtTag {
        @Override public int id() { return DOUBLE; }
    }

    record StringTag(String value) implements NbtTag {
        public StringTag { Objects.requireNonNull(value, "value"); }
        @Override public int id() { return STRING; }
    }

    record ByteArrayTag(byte[] value) implements NbtTag {
        public ByteArrayTag { Objects.requireNonNull(value, "value"); }
        @Override public int id() { return BYTE_ARRAY; }
    }

    record IntArrayTag(int[] value) implements NbtTag {
        public IntArrayTag { Objects.requireNonNull(value, "value"); }
        @Override public int id() { return INT_ARRAY; }
    }

    record LongArrayTag(long[] value) implements NbtTag {
        public LongArrayTag { Objects.requireNonNull(value, "value"); }
        @Override public int id() { return LONG_ARRAY; }
    }

    /** A list of tags that all have {@code elementType}; an empty list may have element type End. */
    record ListTag(int elementType, List<NbtTag> values) implements NbtTag {
        public ListTag {
            values = List.copyOf(values);
            for (NbtTag value : values)
                if (value.id() != elementType) throw new IllegalArgumentException("List element type " + value.id() + " does not match " + elementType);
        }

        @Override public int id() { return LIST; }
        public int size() { return values.size(); }
        public NbtTag get(int index) { return values.get(index); }
    }

    /** Named child tags in file order. */
    record Compound(Map<String, NbtTag> values) implements NbtTag {
        public Compound {
            values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }

        @Override public int id() { return COMPOUND; }
        public boolean has(String key) { return values.containsKey(key); }
        public Optional<NbtTag> get(String key) { return Optional.ofNullable(values.get(key)); }

        /** A byte, short or int child widened to int. */
        public OptionalInt integer(String key) {
            NbtTag tag = values.get(key);
            return switch (tag) {
                case ByteTag value -> OptionalInt.of(value.value());
                case ShortTag value -> OptionalInt.of(value.value());
                case IntTag value -> OptionalInt.of(value.value());
                case null, default -> OptionalInt.empty();
            };
        }

        public Optional<String> string(String key) {
            return values.get(key) instanceof StringTag value ? Optional.of(value.value()) : Optional.empty();
        }

        public Optional<Compound> compound(String key) {
            return values.get(key) instanceof Compound value ? Optional.of(value) : Optional.empty();
        }

        public Optional<ListTag> list(String key) {
            return values.get(key) instanceof ListTag value ? Optional.of(value) : Optional.empty();
        }

        public Optional<byte[]> bytes(String key) {
            return values.get(key) instanceof ByteArrayTag value ? Optional.of(value.value()) : Optional.empty();
        }

        public Optional<int[]> ints(String key) {
            return values.get(key) instanceof IntArrayTag value ? Optional.of(value.value()) : Optional.empty();
        }
    }
}
