package art.arcane.animus.core.building;

import art.arcane.animus.core.navigation.BlockPos;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The full, fixed order of one build. The same schematic, options and origin always give the same plan, so a saved
 * step number is enough to resume. Steps are grouped into segments that must finish in order; the steps inside one
 * segment may be done in any order, which lets several NPCs share it.
 */
public final class BuildPlan {
    private final Schematic schematic;
    private final List<BlockState> palette;
    private final BuildOptions options;
    private final BlockPos origin;
    private final int[] steps;
    private final int excavations;
    private final int[] segmentEnds;
    private final BuildPhase[] segmentPhases;
    private final int shiftX, shiftY, shiftZ;

    BuildPlan(Schematic schematic, List<BlockState> palette, BuildOptions options, BlockPos origin, int[] steps, int excavations,
              int[] segmentEnds, BuildPhase[] segmentPhases, int shiftX, int shiftY, int shiftZ) {
        this.schematic = Objects.requireNonNull(schematic);
        this.palette = List.copyOf(palette);
        this.options = Objects.requireNonNull(options);
        this.origin = Objects.requireNonNull(origin);
        this.steps = steps; this.excavations = excavations;
        this.segmentEnds = segmentEnds; this.segmentPhases = segmentPhases;
        this.shiftX = shiftX; this.shiftY = shiftY; this.shiftZ = shiftZ;
    }

    public Schematic schematic() { return schematic; }
    public BuildOptions options() { return options; }
    public BlockPos origin() { return origin; }
    public int size() { return steps.length; }
    public int excavations() { return excavations; }
    public int cell(int step) { return steps[step]; }
    public boolean excavation(int step) { return step < excavations; }
    /** What the step leaves at its cell: air for excavation, otherwise the transformed schematic state. */
    public BlockState target(int step) { return excavation(step) ? BlockState.AIR : palette.get(schematic.cell(steps[step])); }
    /** The transformed schematic state of the step's cell, also for excavation steps. */
    public BlockState design(int step) { return palette.get(schematic.cell(steps[step])); }
    public int paletteIndex(int step) { return schematic.cell(steps[step]); }
    public BlockState state(int paletteIndex) { return palette.get(paletteIndex); }
    public int segments() { return segmentEnds.length; }
    /** The first step after the segment that holds this step. */
    public int segmentEnd(int step) {
        int found = Arrays.binarySearch(segmentEnds, step + 1);
        int segment = found >= 0 ? found : -found - 1;
        return segmentEnds[Math.min(segment, segmentEnds.length - 1)];
    }
    public BuildPhase phase(int step) {
        int found = Arrays.binarySearch(segmentEnds, step + 1);
        int segment = found >= 0 ? found : -found - 1;
        return segmentPhases[Math.min(segment, segmentPhases.length - 1)];
    }

    /** The world position of a schematic cell. */
    public BlockPos position(int cell) {
        int x = schematic.x(cell) + shiftX, y = schematic.y(cell) + shiftY, z = schematic.z(cell) + shiftZ;
        int[] turned = options.transform().apply(x, z);
        return new BlockPos(origin.x() + options.offsetX() + turned[0], origin.y() + options.offsetY() + y, origin.z() + options.offsetZ() + turned[1]);
    }
    public BlockPos stepPosition(int step) { return position(steps[step]); }

    /** Lowest and highest world corner of the whole box. */
    public BlockPos minimum() { return corner(true); }
    public BlockPos maximum() { return corner(false); }
    private BlockPos corner(boolean lowest) {
        int last = schematic.volume() - 1;
        BlockPos a = position(0), b = position(last);
        return lowest ? new BlockPos(Math.min(a.x(), b.x()), Math.min(a.y(), b.y()), Math.min(a.z(), b.z()))
                : new BlockPos(Math.max(a.x(), b.x()), Math.max(a.y(), b.y()), Math.max(a.z(), b.z()));
    }
}
