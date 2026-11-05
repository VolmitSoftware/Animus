package art.arcane.animus.core.building.schematic;

import art.arcane.animus.core.building.BlockState;
import art.arcane.animus.core.building.Schematic;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static art.arcane.animus.core.building.schematic.NbtWriter.*;
import static org.junit.jupiter.api.Assertions.*;

class SchematicReaderTest {
    private static final SchematicReader.Limits LIMITS = SchematicReader.Limits.DEFAULT;

    private static NbtTag.Compound palette(String... texts) {
        Map<String, NbtTag> values = new LinkedHashMap<>();
        for (int index = 0; index < texts.length; index++) values.put(texts[index], integer(index));
        return new NbtTag.Compound(values);
    }

    private static NbtTag.Compound spongeV2(int width, int height, int length, NbtTag.Compound palette, byte[] data, Object... extra) {
        List<Object> pairs = new ArrayList<>(List.of("Version", integer(2), "Width", shortTag(width), "Height", shortTag(height),
                "Length", shortTag(length), "Palette", palette, "BlockData", bytes(data)));
        pairs.addAll(List.of(extra));
        return compound(pairs.toArray());
    }

    private static Schematic schem(NbtTag.Compound root) throws SchematicException {
        return SchematicReader.read("build.schem", write("Schematic", root, true), LIMITS);
    }

    private static String state(Schematic schematic, int x, int y, int z) {
        int cell = schematic.cell(schematic.index(x, y, z));
        return cell == Schematic.VOID ? "void" : schematic.palette().get(cell).toString();
    }

    @Test
    void readsSpongeV2RootFields() throws SchematicException {
        Schematic schematic = schem(spongeV2(2, 1, 1, palette("minecraft:stone", "minecraft:oak_log[axis=y]"), varints(0, 1),
                "BlockEntities", list(NbtTag.COMPOUND, compound(), compound()), "Offset", ints(1, 2, 3)));
        assertEquals("sponge-v2", schematic.format());
        assertEquals("minecraft:stone", state(schematic, 0, 0, 0));
        assertEquals("minecraft:oak_log[axis=y]", state(schematic, 1, 0, 0));
        assertEquals(2, schematic.blockEntities());
        assertEquals(1, schematic.offsetX());
        assertEquals(2, schematic.offsetY());
        assertEquals(3, schematic.offsetZ());
        assertTrue(schematic.warnings().isEmpty());
    }

    @Test
    void readsSidesAsUnsignedShorts() throws SchematicException {
        Schematic accepted = schem(spongeV2(200, 1, 1, palette("minecraft:stone"), varints(new int[200])));
        assertEquals(200, accepted.width());
        NbtTag.Compound wide = spongeV2(-32768, 1, 1, palette("minecraft:stone"), new byte[0]);
        SchematicException failure = assertThrows(SchematicException.class, () -> schem(wide));
        assertTrue(failure.getMessage().contains("32768") && failure.getMessage().contains("maximum side"), failure.getMessage());
        SchematicReader.Limits wideLimits = new SchematicReader.Limits(NbtReader.Limits.DEFAULT, 40_000, 4096, 40_000);
        Schematic unsigned = SchematicReader.read("wide.schem", write("Schematic", spongeV2(-32768, 1, 1, palette("minecraft:stone"), varints(new int[32768])), false), wideLimits);
        assertEquals(32768, unsigned.width());
    }

    @Test
    void readsSpongeV3WrappedRoot() throws SchematicException {
        NbtTag.Compound blocks = compound("Palette", palette("minecraft:air", "minecraft:glass"), "Data", bytes(varints(1, 0, 1)),
                "BlockEntities", list(NbtTag.COMPOUND, compound()));
        NbtTag.Compound inner = compound("Version", integer(3), "Width", shortTag(3), "Height", shortTag(1), "Length", shortTag(1),
                "Offset", ints(-1, 0, 4), "Blocks", blocks);
        Schematic schematic = SchematicReader.read("x.SCHEM", write("", compound("Schematic", inner), true), LIMITS);
        assertEquals("sponge-v3", schematic.format());
        assertEquals("minecraft:glass", state(schematic, 0, 0, 0));
        assertEquals("minecraft:air", state(schematic, 1, 0, 0));
        assertEquals(1, schematic.blockEntities());
        assertEquals(-1, schematic.offsetX());
        assertEquals(4, schematic.offsetZ());
    }

    @Test
    void readsSpongeV1TileEntities() throws SchematicException {
        NbtTag.Compound root = compound("Version", integer(1), "Width", shortTag(1), "Height", shortTag(1), "Length", shortTag(1),
                "Palette", palette("minecraft:chest"), "BlockData", bytes(varints(0)),
                "TileEntities", list(NbtTag.COMPOUND, compound(), compound(), compound()));
        Schematic schematic = schem(root);
        assertEquals("sponge-v1", schematic.format());
        assertEquals(3, schematic.blockEntities());
    }

    @Test
    void readsSchematicExtensionWhenSpongeShaped() throws SchematicException {
        Schematic schematic = SchematicReader.read("old.schematic", write("Schematic", spongeV2(1, 1, 1, palette("minecraft:stone"), varints(0)), true), LIMITS);
        assertEquals("sponge-v2", schematic.format());
    }

    @Test
    void readsMultiByteVarints() throws SchematicException {
        String[] texts = new String[300];
        for (int index = 0; index < texts.length; index++) texts[index] = "minecraft:block_" + index;
        int[] data = new int[300];
        for (int index = 0; index < data.length; index++) data[index] = 299 - index;
        Schematic schematic = schem(spongeV2(300, 1, 1, palette(texts), varints(data)));
        assertEquals(300, schematic.palette().size());
        for (int x = 0; x < 300; x++) assertEquals("minecraft:block_" + (299 - x), state(schematic, x, 0, 0));
    }

    @Test
    void prefersWorldEditOffsetMetadata() throws SchematicException {
        Schematic schematic = schem(spongeV2(1, 1, 1, palette("minecraft:stone"), varints(0), "Offset", ints(1, 1, 1),
                "Metadata", compound("WEOffsetX", integer(-5), "WEOffsetY", integer(6), "WEOffsetZ", integer(-7))));
        assertEquals(-5, schematic.offsetX());
        assertEquals(6, schematic.offsetY());
        assertEquals(-7, schematic.offsetZ());
        Schematic partial = schem(spongeV2(1, 1, 1, palette("minecraft:stone"), varints(0), "Offset", ints(1, 2, 3),
                "Metadata", compound("WEOffsetX", integer(-5))));
        assertEquals(1, partial.offsetX());
        Schematic none = schem(spongeV2(1, 1, 1, palette("minecraft:stone"), varints(0)));
        assertEquals(0, none.offsetX());
    }

    @Test
    void ordersCellsXThenZThenY() throws SchematicException {
        int width = 2, height = 3, length = 4;
        String[] texts = new String[width * height * length];
        int[] data = new int[texts.length];
        for (int y = 0; y < height; y++)
            for (int z = 0; z < length; z++)
                for (int x = 0; x < width; x++) {
                    int index = x + z * width + y * width * length;
                    texts[index] = "minecraft:b_" + x + "_" + y + "_" + z;
                    data[index] = index;
                }
        Schematic schematic = schem(spongeV2(width, height, length, palette(texts), varints(data)));
        for (int y = 0; y < height; y++)
            for (int z = 0; z < length; z++)
                for (int x = 0; x < width; x++) assertEquals("minecraft:b_" + x + "_" + y + "_" + z, state(schematic, x, y, z));
    }

    @Test
    void unknownPaletteTextBecomesVoidWithWarning() throws SchematicException {
        Schematic schematic = schem(spongeV2(3, 1, 1, palette("minecraft:stone", "Not A Block!", "minecraft:structure_void"), varints(1, 0, 1)));
        assertEquals("void", state(schematic, 0, 0, 0));
        assertEquals("minecraft:stone", state(schematic, 1, 0, 0));
        assertEquals(List.of(BlockState.parse("minecraft:stone")), schematic.palette());
        assertEquals(List.of("unknown block state 'Not A Block!' (2 cells left unchanged)"), schematic.warnings());
    }

    @Test
    void structureVoidBecomesVoidWithoutWarning() throws SchematicException {
        Schematic schematic = schem(spongeV2(2, 1, 1, palette("minecraft:structure_void", "minecraft:dirt"), varints(0, 1)));
        assertEquals("void", state(schematic, 0, 0, 0));
        assertEquals("minecraft:dirt", state(schematic, 1, 0, 0));
        assertEquals(1, schematic.palette().size());
        assertTrue(schematic.warnings().isEmpty());
        assertEquals(1, schematic.definedCells());
    }

    @Test
    void rejectsVolumeSideAndPaletteLimits() {
        SchematicReader.Limits tight = new SchematicReader.Limits(NbtReader.Limits.DEFAULT, 8, 2, 4);
        assertLimit(tight, spongeV2(5, 1, 1, palette("minecraft:stone"), varints(new int[5])), "maximum side");
        assertLimit(tight, spongeV2(3, 3, 1, palette("minecraft:stone"), varints(new int[9])), "maximum volume");
        assertLimit(tight, spongeV2(1, 1, 1, palette("minecraft:a", "minecraft:b", "minecraft:c"), varints(0)), "maximum palette");
        assertLimit(tight, spongeV2(1, 1, 1, new NbtTag.Compound(Map.of()), varints(0)), "maximum palette");
        assertLimit(tight, spongeV2(0, 1, 1, palette("minecraft:stone"), new byte[0]), "maximum side");
    }

    private static void assertLimit(SchematicReader.Limits limits, NbtTag.Compound root, String phrase) {
        SchematicException failure = assertThrows(SchematicException.class, () -> SchematicReader.read("a.schem", write("", root, false), limits));
        assertTrue(failure.getMessage().contains(phrase), failure.getMessage());
    }

    @Test
    void rejectsDataCountMismatch() {
        assertThrows(SchematicException.class, () -> schem(spongeV2(3, 1, 1, palette("minecraft:stone"), varints(0, 0))));
        assertThrows(SchematicException.class, () -> schem(spongeV2(3, 1, 1, palette("minecraft:stone"), varints(0, 0, 0, 0))));
        assertThrows(SchematicException.class, () -> schem(spongeV2(1, 1, 1, palette("minecraft:stone"), new byte[] {(byte) 0x80})));
        assertThrows(SchematicException.class, () -> schem(spongeV2(1, 1, 1, palette("minecraft:stone"),
                new byte[] {(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0})));
    }

    @Test
    void rejectsOutOfRangeAndUnmappedIndices() {
        assertThrows(SchematicException.class, () -> schem(spongeV2(1, 1, 1, palette("minecraft:stone"), varints(1))));
        NbtTag.Compound sparse = compound("minecraft:stone", integer(0), "minecraft:dirt", integer(2));
        SchematicException failure = assertThrows(SchematicException.class, () -> schem(spongeV2(1, 1, 1, sparse, varints(1))));
        assertTrue(failure.getMessage().contains("palette id 1"), failure.getMessage());
        NbtTag.Compound duplicate = compound("minecraft:stone", integer(0), "minecraft:dirt", integer(0));
        assertThrows(SchematicException.class, () -> schem(spongeV2(1, 1, 1, duplicate, varints(0))));
        NbtTag.Compound negative = compound("minecraft:stone", integer(-1));
        assertThrows(SchematicException.class, () -> schem(spongeV2(1, 1, 1, negative, varints(0))));
    }

    @Test
    void rejectsUnsupportedSpongeVersion() {
        NbtTag.Compound root = compound("Version", integer(4), "Width", shortTag(1), "Height", shortTag(1), "Length", shortTag(1));
        assertThrows(SchematicException.class, () -> schem(root));
    }

    private static NbtTag.Compound paletteEntry(String name, String... properties) {
        if (properties.length == 0) return compound("Name", string(name));
        Map<String, NbtTag> values = new LinkedHashMap<>();
        for (int index = 0; index < properties.length; index += 2) values.put(properties[index], string(properties[index + 1]));
        return compound("Name", string(name), "Properties", new NbtTag.Compound(values));
    }

    private static NbtTag.Compound block(int state, int x, int y, int z, boolean withNbt) {
        return withNbt ? compound("state", integer(state), "pos", intList(x, y, z), "nbt", compound("id", string("minecraft:chest")))
                : compound("state", integer(state), "pos", intList(x, y, z));
    }

    private static Schematic structure(NbtTag.Compound root) throws SchematicException {
        return SchematicReader.read("house.nbt", write("", root, true), LIMITS);
    }

    @Test
    void readsStructureWithPropertiesAndMissingCells() throws SchematicException {
        NbtTag.Compound root = compound("size", intList(2, 2, 3),
                "palette", list(NbtTag.COMPOUND, paletteEntry("minecraft:oak_stairs", "half", "bottom", "facing", "north"), paletteEntry("minecraft:stone"),
                        paletteEntry("minecraft:structure_void")),
                "blocks", list(NbtTag.COMPOUND, block(0, 1, 0, 2, false), block(1, 0, 1, 0, true), block(2, 1, 1, 1, false)),
                "entities", list(NbtTag.COMPOUND, compound("pos", intList(0, 0, 0))));
        Schematic schematic = structure(root);
        assertEquals("structure", schematic.format());
        assertEquals("minecraft:oak_stairs[facing=north,half=bottom]", state(schematic, 1, 0, 2));
        assertEquals("minecraft:stone", state(schematic, 0, 1, 0));
        assertEquals("void", state(schematic, 1, 1, 1));
        assertEquals("void", state(schematic, 0, 0, 0));
        assertEquals(2, schematic.definedCells());
        assertEquals(1, schematic.blockEntities());
        assertEquals(0, schematic.offsetX());
        assertTrue(schematic.warnings().isEmpty());
    }

    @Test
    void readsFirstOfMultiplePalettesWithWarning() throws SchematicException {
        NbtTag.Compound root = compound("size", intList(1, 1, 1),
                "palettes", list(NbtTag.LIST, list(NbtTag.COMPOUND, paletteEntry("minecraft:red_wool")), list(NbtTag.COMPOUND, paletteEntry("minecraft:blue_wool"))),
                "blocks", list(NbtTag.COMPOUND, block(0, 0, 0, 0, false)));
        Schematic schematic = structure(root);
        assertEquals("minecraft:red_wool", state(schematic, 0, 0, 0));
        assertEquals(List.of("structure has 2 palettes; using the first"), schematic.warnings());
    }

    @Test
    void duplicateStructurePositionsLastWins() throws SchematicException {
        NbtTag.Compound root = compound("size", intList(1, 1, 1),
                "palette", list(NbtTag.COMPOUND, paletteEntry("minecraft:stone"), paletteEntry("minecraft:dirt")),
                "blocks", list(NbtTag.COMPOUND, block(0, 0, 0, 0, true), block(1, 0, 0, 0, false)));
        Schematic schematic = structure(root);
        assertEquals("minecraft:dirt", state(schematic, 0, 0, 0));
        assertEquals(0, schematic.blockEntities());
        assertEquals(1, schematic.palette().size());
    }

    @Test
    void invalidStructurePaletteEntryBecomesVoidWithWarning() throws SchematicException {
        NbtTag.Compound root = compound("size", intList(2, 1, 1),
                "palette", list(NbtTag.COMPOUND, paletteEntry("minecraft:Bad Name"), paletteEntry("minecraft:stone")),
                "blocks", list(NbtTag.COMPOUND, block(0, 0, 0, 0, false), block(1, 1, 0, 0, false)));
        Schematic schematic = structure(root);
        assertEquals("void", state(schematic, 0, 0, 0));
        assertEquals(List.of("unknown block state 'minecraft:Bad Name' (1 cells left unchanged)"), schematic.warnings());
    }

    @Test
    void rejectsBadStructureBlocks() {
        NbtTag.ListTag palette = list(NbtTag.COMPOUND, paletteEntry("minecraft:stone"));
        assertThrows(SchematicException.class, () -> structure(compound("size", intList(1, 1, 1), "palette", palette,
                "blocks", list(NbtTag.COMPOUND, block(1, 0, 0, 0, false)))));
        assertThrows(SchematicException.class, () -> structure(compound("size", intList(1, 1, 1), "palette", palette,
                "blocks", list(NbtTag.COMPOUND, block(0, 1, 0, 0, false)))));
        assertThrows(SchematicException.class, () -> structure(compound("size", intList(0, 1, 1), "palette", palette,
                "blocks", list(NbtTag.COMPOUND))));
        assertThrows(SchematicException.class, () -> structure(compound("size", intList(1, 1), "palette", palette,
                "blocks", list(NbtTag.COMPOUND))));
    }

    @Test
    void rejectsLegacySchematic() {
        NbtTag.Compound legacy = compound("Width", shortTag(1), "Height", shortTag(1), "Length", shortTag(1), "Materials", string("Alpha"),
                "Blocks", bytes(new byte[] {1}), "Data", bytes(new byte[] {0}));
        SchematicException failure = assertThrows(SchematicException.class, () -> SchematicReader.read("old.schematic", write("Schematic", legacy, true), LIMITS));
        assertEquals("Legacy MCEdit .schematic files use numeric block IDs and are not supported; save the build again as a Sponge .schem file", failure.getMessage());
    }

    @Test
    void rejectsUnknownExtension() {
        SchematicException failure = assertThrows(SchematicException.class,
                () -> SchematicReader.read("build.litematic", write("", compound(), false), LIMITS));
        assertEquals("unsupported schematic format", failure.getMessage());
    }
}
