package art.arcane.animus.core.building.schematic;

import art.arcane.animus.core.building.BlockState;
import art.arcane.animus.core.building.Schematic;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Reads Sponge {@code .schem} (versions 1 to 3) and vanilla structure {@code .nbt} files into a {@link Schematic}.
 * Legacy MCEdit {@code .schematic} files are refused. Every size, count and index is checked against {@link Limits}.
 */
public final class SchematicReader {
    public static final int MAXIMUM_WARNINGS = 64;

    /** Limits on the NBT data and on the schematic it describes. */
    public record Limits(NbtReader.Limits nbt, int maximumVolume, int maximumPalette, int maximumSide) {
        public static final Limits DEFAULT = new Limits(NbtReader.Limits.DEFAULT, 1_000_000, 4096, 4096);

        public Limits {
            Objects.requireNonNull(nbt, "nbt");
            if (maximumVolume < 1 || maximumPalette < 1 || maximumSide < 1) throw new IllegalArgumentException("Schematic limits must be positive");
        }
    }

    private static final String LEGACY = "Legacy MCEdit .schematic files use numeric block IDs and are not supported; save the build again as a Sponge .schem file";

    private SchematicReader() {}

    public static Schematic read(String fileName, byte[] data, Limits limits) throws SchematicException {
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(limits, "limits");
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".schem")) return sponge(NbtReader.read(data, limits.nbt()).value(), limits);
        if (lower.endsWith(".nbt")) return structure(NbtReader.read(data, limits.nbt()).value(), limits);
        if (lower.endsWith(".schematic")) {
            NbtTag.Compound root = NbtReader.read(data, limits.nbt()).value();
            if (root.has("Materials") || root.bytes("Blocks").isPresent()) throw new SchematicException(LEGACY);
            return sponge(root, limits);
        }
        throw new SchematicException("unsupported schematic format");
    }

    public static Schematic read(String fileName, byte[] data) throws SchematicException {
        return read(fileName, data, Limits.DEFAULT);
    }

    private static Schematic sponge(NbtTag.Compound file, Limits limits) throws SchematicException {
        NbtTag.Compound root = file.compound("Schematic").orElse(file);
        OptionalInt declared = root.integer("Version");
        int version = declared.isPresent() ? declared.getAsInt() : root.compound("Blocks").isPresent() ? 3 : 2;
        if (version < 1 || version > 3) throw new SchematicException("unsupported Sponge schematic version " + version);
        int width = side(root, "Width", limits), height = side(root, "Height", limits), length = side(root, "Length", limits);
        int volume = volume(width, height, length, limits);
        NbtTag.Compound paletteTag;
        byte[] blockData;
        int blockEntities;
        int[] offset;
        if (version == 3) {
            NbtTag.Compound blocks = root.compound("Blocks").orElseThrow(() -> new SchematicException("the schematic has no Blocks section"));
            paletteTag = blocks.compound("Palette").orElseThrow(() -> new SchematicException("the schematic has no block palette"));
            blockData = blocks.bytes("Data").orElseThrow(() -> new SchematicException("the schematic has no block data"));
            blockEntities = blocks.list("BlockEntities").map(NbtTag.ListTag::size).orElse(0);
            offset = offset(root);
        } else {
            paletteTag = root.compound("Palette").orElseThrow(() -> new SchematicException("the schematic has no block palette"));
            blockData = root.bytes("BlockData").orElseThrow(() -> new SchematicException("the schematic has no block data"));
            blockEntities = root.list(version == 1 ? "TileEntities" : "BlockEntities").map(NbtTag.ListTag::size).orElse(0);
            offset = weOffset(root).orElse(null);
            if (offset == null) offset = offset(root);
        }
        String[] texts = spongePalette(paletteTag, limits);
        int[] raw = varints(blockData, volume, texts);
        BlockState[] states = new BlockState[texts.length];
        for (int index = 0; index < texts.length; index++) {
            if (texts[index] == null) continue;
            try {
                states[index] = BlockState.parse(texts[index]);
            } catch (IllegalArgumentException ignored) {
                states[index] = null;
            }
        }
        return assemble("sponge-v" + version, width, height, length, offset, states, texts, raw, blockEntities, new ArrayList<>());
    }

    private static Optional<int[]> weOffset(NbtTag.Compound root) {
        Optional<NbtTag.Compound> metadata = root.compound("Metadata");
        if (metadata.isEmpty()) return Optional.empty();
        OptionalInt x = metadata.get().integer("WEOffsetX"), y = metadata.get().integer("WEOffsetY"), z = metadata.get().integer("WEOffsetZ");
        if (x.isEmpty() || y.isEmpty() || z.isEmpty()) return Optional.empty();
        return Optional.of(new int[] {x.getAsInt(), y.getAsInt(), z.getAsInt()});
    }

    private static int[] offset(NbtTag.Compound root) throws SchematicException {
        Optional<int[]> offset = root.ints("Offset");
        if (offset.isEmpty()) return new int[3];
        if (offset.get().length != 3) throw new SchematicException("the schematic Offset must hold 3 numbers, not " + offset.get().length);
        return offset.get().clone();
    }

    private static int side(NbtTag.Compound root, String key, Limits limits) throws SchematicException {
        NbtTag tag = root.get(key).orElseThrow(() -> new SchematicException("the schematic has no " + key));
        int value = switch (tag) {
            case NbtTag.ShortTag shortTag -> shortTag.value() & 0xFFFF;
            case NbtTag.IntTag intTag -> intTag.value();
            case NbtTag.ByteTag byteTag -> byteTag.value() & 0xFF;
            default -> throw new SchematicException("the schematic " + key + " is not a number");
        };
        return checkSide(key, value, limits);
    }

    private static int checkSide(String key, int value, Limits limits) throws SchematicException {
        if (value < 1 || value > limits.maximumSide())
            throw new SchematicException("the schematic " + key + " " + value + " is outside 1.." + limits.maximumSide() + " (maximum side)");
        return value;
    }

    private static int volume(int width, int height, int length, Limits limits) throws SchematicException {
        long volume = (long) width * height * length;
        if (volume < 1 || volume > limits.maximumVolume())
            throw new SchematicException("the schematic volume " + volume + " exceeds the maximum volume of " + limits.maximumVolume() + " blocks");
        return (int) volume;
    }

    private static void checkPaletteSize(int size, Limits limits) throws SchematicException {
        if (size < 1 || size > limits.maximumPalette())
            throw new SchematicException("the schematic palette has " + size + " entries; it must have 1.." + limits.maximumPalette() + " (maximum palette)");
    }

    /** Returns texts indexed by palette id; slots no entry names are null. */
    private static String[] spongePalette(NbtTag.Compound palette, Limits limits) throws SchematicException {
        checkPaletteSize(palette.values().size(), limits);
        Map<Integer, String> byIndex = new HashMap<>();
        int maximum = -1;
        for (Map.Entry<String, NbtTag> entry : palette.values().entrySet()) {
            if (!(entry.getValue() instanceof NbtTag.IntTag(int index))) throw new SchematicException("palette entry '" + entry.getKey() + "' has no numeric id");
            if (index < 0 || index >= limits.maximumPalette()) throw new SchematicException("palette id " + index + " is outside 0.." + (limits.maximumPalette() - 1));
            if (byIndex.put(index, entry.getKey()) != null) throw new SchematicException("palette id " + index + " is used twice");
            maximum = Math.max(maximum, index);
        }
        String[] texts = new String[maximum + 1];
        for (Map.Entry<Integer, String> entry : byIndex.entrySet()) texts[entry.getKey()] = entry.getValue();
        return texts;
    }

    private static int[] varints(byte[] data, int volume, String[] texts) throws SchematicException {
        int[] cells = new int[volume];
        int cursor = 0;
        for (int cell = 0; cell < volume; cell++) {
            if (cursor >= data.length) throw new SchematicException("the block data holds " + cell + " blocks but the schematic needs " + volume);
            long value = 0;
            int shift = 0;
            while (true) {
                if (cursor >= data.length) throw new SchematicException("the block data ends inside a number");
                if (shift >= 35) throw new SchematicException("the block data holds a number longer than 5 bytes");
                int part = data[cursor++] & 0xFF;
                value |= (long) (part & 0x7F) << shift;
                shift += 7;
                if ((part & 0x80) == 0) break;
            }
            if (value >= texts.length || texts[(int) value] == null) throw new SchematicException("the block data names palette id " + value + ", which the palette does not define");
            cells[cell] = (int) value;
        }
        if (cursor != data.length) throw new SchematicException("the block data holds more blocks than the schematic's " + volume);
        return cells;
    }

    private static Schematic structure(NbtTag.Compound root, Limits limits) throws SchematicException {
        NbtTag.ListTag size = root.list("size").orElseThrow(() -> new SchematicException("the structure has no size"));
        if (size.size() != 3 || size.elementType() != NbtTag.INT) throw new SchematicException("the structure size must be a list of 3 numbers");
        int width = checkSide("width", ((NbtTag.IntTag) size.get(0)).value(), limits);
        int height = checkSide("height", ((NbtTag.IntTag) size.get(1)).value(), limits);
        int length = checkSide("length", ((NbtTag.IntTag) size.get(2)).value(), limits);
        int volume = volume(width, height, length, limits);
        List<String> warnings = new ArrayList<>();
        NbtTag.ListTag palette;
        Optional<NbtTag.ListTag> single = root.list("palette");
        if (single.isPresent()) {
            palette = single.get();
        } else {
            NbtTag.ListTag palettes = root.list("palettes").orElseThrow(() -> new SchematicException("the structure has no block palette"));
            if (palettes.size() == 0 || !(palettes.get(0) instanceof NbtTag.ListTag first)) throw new SchematicException("the structure has no block palette");
            if (palettes.size() > 1) warnings.add("structure has " + palettes.size() + " palettes; using the first");
            palette = first;
        }
        checkPaletteSize(palette.size(), limits);
        BlockState[] states = new BlockState[palette.size()];
        String[] texts = new String[palette.size()];
        for (int index = 0; index < palette.size(); index++) {
            if (!(palette.get(index) instanceof NbtTag.Compound entry)) throw new SchematicException("structure palette entry " + index + " is not a compound");
            String name = entry.string("Name").orElse("");
            texts[index] = name;
            states[index] = paletteState(name, entry.compound("Properties"));
            if (states[index] != null) texts[index] = states[index].toString();
        }
        int[] raw = new int[volume];
        Arrays.fill(raw, Schematic.VOID);
        BitSet entities = new BitSet(volume);
        NbtTag.ListTag blocks = root.list("blocks").orElseThrow(() -> new SchematicException("the structure has no blocks list"));
        for (int position = 0; position < blocks.size(); position++) {
            final int index = position;
            if (!(blocks.get(index) instanceof NbtTag.Compound block)) throw new SchematicException("structure block " + index + " is not a compound");
            int state = block.integer("state").orElseThrow(() -> new SchematicException("structure block " + index + " has no state"));
            if (state < 0 || state >= states.length) throw new SchematicException("structure block " + index + " names palette entry " + state + " of " + states.length);
            NbtTag.ListTag pos = block.list("pos").orElseThrow(() -> new SchematicException("structure block " + index + " has no position"));
            if (pos.size() != 3 || pos.elementType() != NbtTag.INT) throw new SchematicException("structure block " + index + " position must be 3 numbers");
            int x = ((NbtTag.IntTag) pos.get(0)).value(), y = ((NbtTag.IntTag) pos.get(1)).value(), z = ((NbtTag.IntTag) pos.get(2)).value();
            if (x < 0 || y < 0 || z < 0 || x >= width || y >= height || z >= length)
                throw new SchematicException("structure block " + index + " at " + x + "," + y + "," + z + " is outside the structure size");
            int cell = x + z * width + y * width * length;
            raw[cell] = state;
            entities.set(cell, block.compound("nbt").isPresent());
        }
        return assemble("structure", width, height, length, new int[3], states, texts, raw, entities.cardinality(), warnings);
    }

    private static BlockState paletteState(String name, Optional<NbtTag.Compound> properties) {
        if (name.isEmpty()) return null;
        Map<String, String> values = new LinkedHashMap<>();
        if (properties.isPresent()) {
            for (Map.Entry<String, NbtTag> entry : properties.get().values().entrySet()) {
                if (!(entry.getValue() instanceof NbtTag.StringTag(String value))) return null;
                values.put(entry.getKey(), value);
            }
        }
        try {
            return new BlockState(name.contains(":") ? name : "minecraft:" + name, values);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /**
     * Maps raw palette slots to a compact palette. A slot whose state is null (unparseable) or structure void becomes
     * {@link Schematic#VOID}; unparseable slots that cells use add a warning.
     */
    private static Schematic assemble(String format, int width, int height, int length, int[] offset, BlockState[] states, String[] texts,
                                      int[] raw, int blockEntities, List<String> warnings) {
        int[] usage = new int[states.length];
        for (int slot : raw) if (slot != Schematic.VOID) usage[slot]++;
        int[] remap = new int[states.length];
        Map<BlockState, Integer> compact = new LinkedHashMap<>();
        for (int slot = 0; slot < states.length; slot++) {
            remap[slot] = Schematic.VOID;
            if (texts[slot] == null) continue;
            BlockState state = states[slot];
            if (state == null) {
                warnings.add("unknown block state '" + texts[slot] + "' (" + usage[slot] + " cells left unchanged)");
                continue;
            }
            if (state.structureVoid() || usage[slot] == 0) continue;
            remap[slot] = compact.computeIfAbsent(state, ignored -> compact.size());
        }
        int[] cells = new int[raw.length];
        for (int index = 0; index < raw.length; index++) cells[index] = raw[index] == Schematic.VOID ? Schematic.VOID : remap[raw[index]];
        return new Schematic(format, width, height, length, offset[0], offset[1], offset[2], new ArrayList<>(compact.keySet()), cells,
                blockEntities, capped(warnings));
    }

    private static List<String> capped(List<String> warnings) {
        if (warnings.size() <= MAXIMUM_WARNINGS) return warnings;
        List<String> kept = new ArrayList<>(warnings.subList(0, MAXIMUM_WARNINGS));
        kept.add("and " + (warnings.size() - MAXIMUM_WARNINGS) + " more");
        return kept;
    }
}
