package art.arcane.animus.core.building;

import art.arcane.animus.core.navigation.BlockPos;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Turns a schematic into a {@link BuildPlan}. Excavation, when asked for, clears the whole box from the top down
 * first. Then the build goes up in groups of {@code layers} layers (or one group with {@code groupAll}); inside a group
 * the phases follow {@link BuildPhase#GROUP_ORDER}, air from the top of the group down and everything else bottom up,
 * and each layer follows the column pattern. A multi-block object (see {@link BlockUnits}) is one step at its leading
 * cell; a lone half gets its missing partner, or is left out when another block holds the partner's cell. Pure: the same
 * input always gives the same plan.
 */
public final class BuildPlanner {
    private BuildPlanner() {}

    /**
     * @param palette the schematic palette after rotation, mirroring and any renaming, in the schematic's own order
     * @param placeable false for palette entries this server cannot place; their cells are left alone
     */
    public static BuildPlan plan(Schematic schematic, List<BlockState> palette, boolean[] placeable, BuildOptions options, BlockPos origin) {
        Objects.requireNonNull(schematic); Objects.requireNonNull(options); Objects.requireNonNull(origin);
        if (palette.size() != schematic.palette().size() || placeable.length != palette.size())
            throw new IllegalArgumentException("Planner palette does not match the schematic palette");
        BuildPhase[] phases = new BuildPhase[palette.size()];
        for (int index = 0; index < phases.length; index++) phases[index] = placeable[index] ? BuildPhase.of(palette.get(index)) : null;
        int width = schematic.width(), height = schematic.height(), length = schematic.length();
        int[] columns = columns(width, length, options.pattern());
        int bottom = options.anchor() == BuildOptions.Anchor.CENTER ? bottom(schematic, phases) : 0;
        int shiftX = 0, shiftY = 0, shiftZ = 0;
        switch (options.anchor()) {
            case CENTER -> { shiftX = -(width / 2); shiftY = -bottom; shiftZ = -(length / 2); }
            case CORNER -> { }
            case SCHEMATIC -> { shiftX = schematic.offsetX(); shiftY = schematic.offsetY(); shiftZ = schematic.offsetZ(); }
        }
        Grouping grouping = group(schematic, palette, phases, options, origin, shiftX, shiftY, shiftZ);
        BitSet skip = grouping.skip();
        int[] steps = new int[count(schematic, phases, options, skip)];
        int size = 0;
        List<Integer> ends = new ArrayList<>();
        List<BuildPhase> segmentPhases = new ArrayList<>();
        int layerArea = width * length;
        if (options.excavate()) {
            for (int y = height - 1; y >= 0; y--)
                for (int column : columns) {
                    int cell = y * layerArea + column;
                    if (defined(schematic, phases, cell)) steps[size++] = cell;
                }
            if (size > 0) { ends.add(size); segmentPhases.add(BuildPhase.EXCAVATE); }
        }
        int excavations = size;
        int groupSize = options.groupAll() ? height : options.layers();
        for (int groupStart = 0; groupStart < height; groupStart += groupSize) {
            int groupEnd = Math.min(height, groupStart + groupSize);
            for (BuildPhase phase : BuildPhase.GROUP_ORDER) {
                if (skipped(phase, options)) continue;
                int before = size;
                boolean downward = phase == BuildPhase.CLEAR;
                for (int layer = 0; layer < groupEnd - groupStart; layer++) {
                    int y = downward ? groupEnd - 1 - layer : groupStart + layer;
                    for (int column : columns) {
                        int cell = y * layerArea + column;
                        int entry = schematic.cell(cell);
                        if (entry != Schematic.VOID && phases[entry] == phase && !skip.get(cell)) steps[size++] = cell;
                    }
                }
                if (size > before) { ends.add(size); segmentPhases.add(phase); }
            }
        }
        if (size != steps.length) throw new IllegalStateException("Planner counted " + steps.length + " steps but produced " + size);
        int[] segmentEnds = ends.stream().mapToInt(Integer::intValue).toArray();
        return new BuildPlan(schematic, palette, options, origin, steps, excavations, segmentEnds,
                segmentPhases.toArray(new BuildPhase[0]), shiftX, shiftY, shiftZ, grouping.units());
    }

    /** Units by leading cell, and the cells that are no building step of their own: riders of a unit or lone halves left out. */
    private record Grouping(Map<Integer, BuildPlan.Unit> units, BitSet skip) {}

    private static Grouping group(Schematic schematic, List<BlockState> palette, BuildPhase[] phases, BuildOptions options, BlockPos origin,
                                  int shiftX, int shiftY, int shiftZ) {
        Map<Integer, BuildPlan.Unit> units = new HashMap<>();
        BitSet skip = new BitSet();
        Placer placer = new Placer(schematic, options, origin, shiftX, shiftY, shiftZ);
        for (int cell = 0; cell < schematic.volume(); cell++) {
            if (skip.get(cell) || units.containsKey(cell)) continue;
            int entry = schematic.cell(cell);
            if (entry == Schematic.VOID || phases[entry] == null || phases[entry] == BuildPhase.CLEAR) continue;
            BlockState state = palette.get(entry);
            if (BlockUnits.dripleafStem(state)) { dripleaf(schematic, palette, phases, placer, cell, units, skip); continue; }
            Optional<BlockUnits.Partner> found = BlockUnits.partner(state);
            if (found.isEmpty()) continue;
            BlockUnits.Partner partner = found.get();
            int[] back = options.transform().invert(partner.dx(), partner.dz());
            int x = schematic.x(cell) + back[0], y = schematic.y(cell) + partner.dy(), z = schematic.z(cell) + back[1];
            int other = inside(schematic, x, y, z) ? schematic.index(x, y, z) : -1;
            int otherEntry = other < 0 ? Schematic.VOID : schematic.cell(other);
            BlockState otherState = otherEntry == Schematic.VOID || phases[otherEntry] == null ? null : palette.get(otherEntry);
            BlockPos at = placer.position(cell), partnerAt = new BlockPos(at.x() + partner.dx(), at.y() + partner.dy(), at.z() + partner.dz());
            boolean paired = otherState != null && otherState.name().equals(partner.state().name())
                    && BlockUnits.partner(otherState).filter((BlockUnits.Partner reply) -> reply.dx() == -partner.dx() && reply.dy() == -partner.dy()
                            && reply.dz() == -partner.dz()).isPresent();
            List<BuildPlan.Part> parts = new ArrayList<>(2);
            parts.add(new BuildPlan.Part(at, BlockUnits.normalized(state)));
            if (paired) {
                if (!BlockUnits.leads(state)) continue;
                parts.add(new BuildPlan.Part(partnerAt, BlockUnits.normalized(otherState)));
                skip.set(other);
            } else {
                // A lone half gets its partner where nothing else is wanted, otherwise it stays out.
                if (otherEntry != Schematic.VOID && (otherState == null || !otherState.air())) { skip.set(cell); continue; }
                parts.add(new BuildPlan.Part(partnerAt, partner.state()));
                if (other >= 0 && otherEntry != Schematic.VOID) skip.set(other);
            }
            parts.sort(Comparator.comparingInt((BuildPlan.Part part) -> part.at().y()).thenComparing((BuildPlan.Part part) -> !BlockUnits.leads(part.state())));
            units.put(cell, new BuildPlan.Unit(parts));
        }
        return new Grouping(units, skip);
    }

    /** A column of big dripleaf stems with its head on top is one unit led by the lowest stem; a missing head is added. */
    private static void dripleaf(Schematic schematic, List<BlockState> palette, BuildPhase[] phases, Placer placer, int bottom,
                                 Map<Integer, BuildPlan.Unit> units, BitSet skip) {
        int x = schematic.x(bottom), z = schematic.z(bottom);
        List<BuildPlan.Part> parts = new ArrayList<>();
        List<Integer> riders = new ArrayList<>();
        int y = schematic.y(bottom);
        BlockState stem = palette.get(schematic.cell(bottom));
        while (true) {
            int cell = schematic.index(x, y, z);
            parts.add(new BuildPlan.Part(placer.position(cell), palette.get(schematic.cell(cell))));
            if (cell != bottom) riders.add(cell);
            stem = palette.get(schematic.cell(cell));
            int above = inside(schematic, x, y + 1, z) ? schematic.index(x, y + 1, z) : -1;
            int entry = above < 0 ? Schematic.VOID : schematic.cell(above);
            BlockState next = entry == Schematic.VOID || phases[entry] == null ? null : palette.get(entry);
            if (next != null && BlockUnits.dripleafStem(next)) { y++; continue; }
            BlockPos top = placer.position(cell);
            BlockPos headAt = new BlockPos(top.x(), top.y() + 1, top.z());
            if (next != null && BlockUnits.dripleafHead(next)) { parts.add(new BuildPlan.Part(headAt, next)); riders.add(above); }
            else if (entry == Schematic.VOID || next != null && next.air()) {
                parts.add(new BuildPlan.Part(headAt, BlockUnits.dripleafHeadFor(stem)));
                if (above >= 0 && entry != Schematic.VOID) riders.add(above);
            } else { skip.set(bottom); riders.forEach(skip::set); return; }
            break;
        }
        riders.forEach(skip::set);
        units.put(bottom, new BuildPlan.Unit(parts));
    }

    private static boolean inside(Schematic schematic, int x, int y, int z) {
        return x >= 0 && y >= 0 && z >= 0 && x < schematic.width() && y < schematic.height() && z < schematic.length();
    }

    /** World positions of schematic cells, as {@link BuildPlan#position} gives them. */
    private record Placer(Schematic schematic, BuildOptions options, BlockPos origin, int shiftX, int shiftY, int shiftZ) {
        BlockPos position(int cell) {
            int[] turned = options.transform().apply(schematic.x(cell) + shiftX, schematic.z(cell) + shiftZ);
            return new BlockPos(origin.x() + options.offsetX() + turned[0], origin.y() + options.offsetY() + schematic.y(cell) + shiftY,
                    origin.z() + options.offsetZ() + turned[1]);
        }
    }

    private static boolean skipped(BuildPhase phase, BuildOptions options) {
        return phase == BuildPhase.CLEAR && (options.ignoreAir() || options.excavate()) || phase == BuildPhase.FLUID && options.ignoreLiquids();
    }
    private static boolean defined(Schematic schematic, BuildPhase[] phases, int cell) {
        int entry = schematic.cell(cell);
        return entry != Schematic.VOID && phases[entry] != null;
    }
    private static int count(Schematic schematic, BuildPhase[] phases, BuildOptions options, BitSet skip) {
        int total = 0;
        for (int cell = 0; cell < schematic.volume(); cell++) {
            int entry = schematic.cell(cell);
            if (entry == Schematic.VOID || phases[entry] == null) continue;
            if (options.excavate()) total++;
            if (!skipped(phases[entry], options) && !skip.get(cell)) total++;
        }
        return total;
    }
    /** The lowest layer holding anything but air, so a centred build stands on its origin. */
    private static int bottom(Schematic schematic, BuildPhase[] phases) {
        for (int cell = 0; cell < schematic.volume(); cell++) {
            int entry = schematic.cell(cell);
            if (entry != Schematic.VOID && phases[entry] != null && phases[entry] != BuildPhase.CLEAR) return schematic.y(cell);
        }
        return 0;
    }

    /**
     * Column indices ({@code x + z * width}) in pattern order. Spiral walks rings from the outside in, each ring clockwise
     * from its north-west corner; reverse spiral walks the rings from the inside out. Linear goes row by row from the
     * north-west corner; reverse linear starts from the south-east corner.
     */
    static int[] columns(int width, int length, BuildOptions.Pattern pattern) {
        int area = width * length;
        int[] order = new int[area];
        switch (pattern) {
            case LINEAR -> { for (int column = 0; column < area; column++) order[column] = column; }
            case REVERSE_LINEAR -> { for (int column = 0; column < area; column++) order[column] = area - 1 - column; }
            case SPIRAL, REVERSE_SPIRAL -> {
                long[] keys = new long[area];
                for (int z = 0; z < length; z++)
                    for (int x = 0; x < width; x++) {
                        int ring = Math.min(Math.min(x, z), Math.min(width - 1 - x, length - 1 - z));
                        long rank = pattern == BuildOptions.Pattern.SPIRAL ? ring : (long) (Math.min(width, length) - 1) / 2 - ring;
                        keys[x + z * width] = rank << 40 | (long) perimeter(x, z, ring, width, length) << 24 | (x + (long) z * width);
                    }
                Arrays.sort(keys);
                for (int index = 0; index < area; index++) order[index] = (int) (keys[index] & 0xFFFFFF);
            }
        }
        return order;
    }
    private static int perimeter(int x, int z, int ring, int width, int length) {
        int right = width - 1 - ring, bottom = length - 1 - ring, across = right - ring, down = bottom - ring;
        if (z == ring) return x - ring;
        if (x == right) return across + (z - ring);
        if (z == bottom) return across + down + (right - x);
        return 2 * across + down + (bottom - z);
    }
}
