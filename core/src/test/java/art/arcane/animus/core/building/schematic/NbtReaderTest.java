package art.arcane.animus.core.building.schematic;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import static art.arcane.animus.core.building.schematic.NbtWriter.*;
import static org.junit.jupiter.api.Assertions.*;

class NbtReaderTest {
    private static final NbtReader.Limits SMALL = new NbtReader.Limits(1024 * 1024, 64, 100_000);

    private static NbtTag.Compound everyType() {
        return compound(
                "byte", new NbtTag.ByteTag((byte) -5),
                "short", new NbtTag.ShortTag((short) -300),
                "int", integer(123456789),
                "long", new NbtTag.LongTag(-1234567890123456789L),
                "float", new NbtTag.FloatTag(1.5f),
                "double", new NbtTag.DoubleTag(-2.25),
                "string", string("café ☃ \u0000 end"),
                "bytes", bytes(new byte[] {1, -2, 3}),
                "ints", ints(7, -8, 9),
                "longs", new NbtTag.LongArrayTag(new long[] {Long.MIN_VALUE, 0, Long.MAX_VALUE}),
                "list", intList(1, 2, 3),
                "empty", list(NbtTag.END),
                "nested", compound("inner", string("x"), "lists", list(NbtTag.LIST, intList(4), list(NbtTag.STRING, string("y")))));
    }

    private static void assertEveryType(NbtTag.Compound value) {
        assertEquals((byte) -5, ((NbtTag.ByteTag) value.get("byte").orElseThrow()).value());
        assertEquals(-300, value.integer("short").orElseThrow());
        assertEquals(123456789, value.integer("int").orElseThrow());
        assertEquals(-5, value.integer("byte").orElseThrow());
        assertEquals(-1234567890123456789L, ((NbtTag.LongTag) value.get("long").orElseThrow()).value());
        assertEquals(1.5f, ((NbtTag.FloatTag) value.get("float").orElseThrow()).value());
        assertEquals(-2.25, ((NbtTag.DoubleTag) value.get("double").orElseThrow()).value());
        assertEquals("café ☃ \u0000 end", value.string("string").orElseThrow());
        assertArrayEquals(new byte[] {1, -2, 3}, value.bytes("bytes").orElseThrow());
        assertArrayEquals(new int[] {7, -8, 9}, value.ints("ints").orElseThrow());
        assertArrayEquals(new long[] {Long.MIN_VALUE, 0, Long.MAX_VALUE}, ((NbtTag.LongArrayTag) value.get("longs").orElseThrow()).value());
        assertEquals(3, value.list("list").orElseThrow().size());
        assertEquals(2, ((NbtTag.IntTag) value.list("list").orElseThrow().get(1)).value());
        assertEquals(0, value.list("empty").orElseThrow().size());
        NbtTag.Compound nested = value.compound("nested").orElseThrow();
        assertEquals("x", nested.string("inner").orElseThrow());
        assertEquals(NbtTag.LIST, nested.list("lists").orElseThrow().elementType());
        assertTrue(value.integer("string").isEmpty());
        assertTrue(value.string("missing").isEmpty());
        assertEquals(Arrays.asList("byte", "short", "int", "long", "float", "double", "string", "bytes", "ints", "longs", "list", "empty", "nested"),
                value.values().keySet().stream().toList());
    }

    @Test
    void readsEveryTagTypeRaw() throws SchematicException {
        NbtReader.Root root = NbtReader.read(write("root name", everyType(), false), SMALL);
        assertEquals("root name", root.name());
        assertEveryType(root.value());
    }

    @Test
    void readsEveryTagTypeGzipped() throws SchematicException {
        NbtReader.Root root = NbtReader.read(write("", everyType(), true), SMALL);
        assertEquals("", root.name());
        assertEveryType(root.value());
    }

    @Test
    void ignoresTrailingBytes() throws SchematicException {
        byte[] file = write("", compound("a", integer(1)), false);
        byte[] padded = Arrays.copyOf(file, file.length + 5);
        assertEquals(1, NbtReader.read(padded, SMALL).value().integer("a").orElseThrow());
    }

    @Test
    void rejectsTruncatedData() {
        byte[] file = write("", everyType(), false);
        for (int cut = 0; cut < file.length; cut++) {
            byte[] truncated = Arrays.copyOf(file, cut);
            SchematicException failure = assertThrows(SchematicException.class, () -> NbtReader.read(truncated, SMALL), "cut " + cut);
            assertNotNull(failure.getMessage());
        }
        SchematicException failure = assertThrows(SchematicException.class, () -> NbtReader.read(Arrays.copyOf(file, file.length - 1), SMALL));
        assertEquals("unexpected end of data", failure.getMessage());
    }

    @Test
    void rejectsTruncatedGzip() {
        byte[] file = write("", everyType(), true);
        assertThrows(SchematicException.class, () -> NbtReader.read(Arrays.copyOf(file, file.length / 2), SMALL));
    }

    private static byte[] rootWithField(int type, int listElementType, int length) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeByte(NbtTag.COMPOUND);
        output.writeUTF("");
        output.writeByte(type);
        output.writeUTF("field");
        if (type == NbtTag.LIST) output.writeByte(listElementType);
        output.writeInt(length);
        output.write(new byte[16]);
        output.writeByte(NbtTag.END);
        return bytes.toByteArray();
    }

    @Test
    void rejectsDeclaredLengthsLargerThanRemainingData() throws IOException {
        int[][] cases = {{NbtTag.BYTE_ARRAY, 0}, {NbtTag.INT_ARRAY, 0}, {NbtTag.LONG_ARRAY, 0}, {NbtTag.LIST, NbtTag.INT}, {NbtTag.LIST, NbtTag.COMPOUND}};
        for (int[] test : cases) {
            byte[] file = rootWithField(test[0], test[1], Integer.MAX_VALUE);
            SchematicException failure = assertThrows(SchematicException.class, () -> NbtReader.read(file, NbtReader.Limits.DEFAULT));
            assertTrue(failure.getMessage().contains("larger than the remaining data"), failure.getMessage());
        }
    }

    @Test
    void rejectsNegativeLengths() throws IOException {
        int[][] cases = {{NbtTag.BYTE_ARRAY, 0}, {NbtTag.INT_ARRAY, 0}, {NbtTag.LONG_ARRAY, 0}, {NbtTag.LIST, NbtTag.INT}};
        for (int[] test : cases) {
            byte[] file = rootWithField(test[0], test[1], -1);
            SchematicException failure = assertThrows(SchematicException.class, () -> NbtReader.read(file, SMALL));
            assertTrue(failure.getMessage().contains("negative"), failure.getMessage());
        }
    }

    @Test
    void rejectsNestingOverDepthLimit() throws SchematicException {
        NbtTag.Compound nested = compound();
        for (int level = 0; level < 9; level++) nested = compound("child", nested);
        byte[] file = write("", nested, false);
        assertNotNull(NbtReader.read(file, new NbtReader.Limits(1024, 10, 1000)));
        SchematicException failure = assertThrows(SchematicException.class, () -> NbtReader.read(file, new NbtReader.Limits(1024, 9, 1000)));
        assertTrue(failure.getMessage().contains("nesting"), failure.getMessage());
        NbtTag.ListTag lists = intList(1);
        for (int level = 0; level < 9; level++) lists = list(NbtTag.LIST, lists);
        byte[] listFile = write("", compound("lists", lists), false);
        assertThrows(SchematicException.class, () -> NbtReader.read(listFile, new NbtReader.Limits(1024, 10, 1000)));
    }

    @Test
    void rejectsTagCountOverLimit() throws SchematicException {
        int[] values = new int[50];
        byte[] file = write("", compound("values", intList(values)), false);
        assertNotNull(NbtReader.read(file, new NbtReader.Limits(4096, 8, 52)));
        SchematicException failure = assertThrows(SchematicException.class, () -> NbtReader.read(file, new NbtReader.Limits(4096, 8, 51)));
        assertTrue(failure.getMessage().contains("tag count"), failure.getMessage());
    }

    @Test
    void rejectsGzipBombOverDecompressedLimit() {
        byte[] bomb = gzip(new byte[4 * 1024 * 1024]);
        assertTrue(bomb.length < 64 * 1024);
        SchematicException failure = assertThrows(SchematicException.class, () -> NbtReader.read(bomb, new NbtReader.Limits(1024 * 1024, 64, 1000)));
        assertEquals("decompressed size exceeds 1048576 bytes", failure.getMessage());
    }

    @Test
    void rejectsNonCompoundRoot() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeByte(NbtTag.STRING);
        output.writeUTF("");
        output.writeUTF("text");
        SchematicException failure = assertThrows(SchematicException.class, () -> NbtReader.read(bytes.toByteArray(), SMALL));
        assertTrue(failure.getMessage().contains("not a compound"), failure.getMessage());
        assertThrows(SchematicException.class, () -> NbtReader.read(new byte[0], SMALL));
    }

    @Test
    void rejectsUnknownTagId() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeByte(NbtTag.COMPOUND);
        output.writeUTF("");
        output.writeByte(42);
        output.writeUTF("bad");
        output.writeByte(0);
        assertThrows(SchematicException.class, () -> NbtReader.read(bytes.toByteArray(), SMALL));
    }
}
