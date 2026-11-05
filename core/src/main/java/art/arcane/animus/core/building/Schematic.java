package art.arcane.animus.core.building;

import java.util.List;
import java.util.Objects;

/**
 * A parsed schematic: a box of cells, each holding a palette index or {@link #VOID} for a cell that leaves the world as it
 * is. Cell index order is {@code x + z * width + y * width * length}. {@code offset} is where the schematic's minimum
 * corner sits relative to its own saved origin.
 */
public record Schematic(String format, int width, int height, int length, int offsetX, int offsetY, int offsetZ,
                        List<BlockState> palette, int[] cells, int blockEntities, List<String> warnings) {
    public static final int VOID = -1;

    public Schematic {
        Objects.requireNonNull(format, "format");
        palette = List.copyOf(palette);
        Objects.requireNonNull(cells, "cells");
        warnings = List.copyOf(warnings);
        if (width < 1 || height < 1 || length < 1) throw new IllegalArgumentException("Schematic dimensions must be positive");
        if ((long) width * height * length != cells.length) throw new IllegalArgumentException("Schematic cell count does not match its dimensions");
        for (int cell : cells)
            if (cell != VOID && (cell < 0 || cell >= palette.size())) throw new IllegalArgumentException("Schematic cell names palette entry " + cell + " of " + palette.size());
    }

    public int volume() { return cells.length; }
    public int index(int x, int y, int z) { return x + z * width + y * width * length; }
    public int x(int index) { return index % width; }
    public int z(int index) { return (index / width) % length; }
    public int y(int index) { return index / (width * length); }
    /** The palette index at a cell, or {@link #VOID}. */
    public int cell(int index) { return cells[index]; }
    /** Cells that are not void. */
    public int definedCells() {
        int count = 0;
        for (int cell : cells) if (cell != VOID) count++;
        return count;
    }
}
