/// Slices kept per chunk, the input of the mesher and of point checks. Each
/// chunk keeps its own layers, so a path that climbs reads every chunk at
/// its own height. Cells outside every loaded slice, or cut off when the
/// slice was added, are unknown.
library;

import 'dart:typed_data';

import 'terrain_data.dart';

/// A palette index for a cell no slice covered.
const int terrainUnknownCell = 0xFFFF;

/// The layers one chunk holds: 16 by [height] by 16 cells from [y].
final class TerrainColumnRange {
  TerrainColumnRange(this.y, this.height) : cells = Uint16List(16 * 16 * height)..fillRange(0, 16 * 16 * height, terrainUnknownCell);

  final int y;
  final int height;
  final Uint16List cells;
}

final class TerrainVolume {
  TerrainVolume();

  /// The box [x0]..[x0]+[sx] (and so on) filled from [slices].
  factory TerrainVolume.of(int x0, int y0, int z0, int sx, int sy, int sz, Iterable<TerrainSlice> slices) {
    final TerrainVolume volume = TerrainVolume();
    for (final TerrainSlice slice in slices) {
      volume.add(slice, keep: (int x, int z) => x >= x0 && z >= z0 && x < x0 + sx && z < z0 + sz, minY: y0, maxY: y0 + sy);
    }
    return volume;
  }

  final Map<(int, int), TerrainColumnRange> _columns = <(int, int), TerrainColumnRange>{};
  final List<String> palette = <String>[];
  final Map<String, int> _ids = <String, int>{};

  /// The chunks that hold layers.
  Iterable<(int, int)> get chunks => _columns.keys;

  /// The layers chunk [cx], [cz] holds, or null.
  TerrainColumnRange? column(int cx, int cz) => _columns[(cx, cz)];

  int _intern(String state) => _ids.putIfAbsent(state, () {
        palette.add(state);
        return palette.length - 1;
      });

  /// Copies [slice] into its chunk, replacing what the chunk held: only
  /// the columns [keep] accepts and the layers from [minY] below [maxY].
  /// A slice that is not ok leaves its cells unknown.
  void add(TerrainSlice slice, {bool Function(int x, int z)? keep, int? minY, int? maxY}) {
    if (slice.state != TerrainChunkState.ok) return;
    final int from = minY == null || minY < slice.y ? slice.y : minY;
    final int to = maxY == null || maxY > slice.y + slice.height ? slice.y + slice.height : maxY;
    if (to <= from) return;
    final TerrainColumnRange column = TerrainColumnRange(from, to - from);
    final List<int> local = <int>[for (final String state in slice.palette) _intern(state)];
    for (int lz = 0; lz < 16; lz++) {
      for (int lx = 0; lx < 16; lx++) {
        if (keep != null && !keep(slice.cx * 16 + lx, slice.cz * 16 + lz)) continue;
        for (int y = from; y < to; y++) {
          column.cells[((y - from) * 16 + lz) * 16 + lx] = local[slice.indices[((y - slice.y) * 16 + lz) * 16 + lx]];
        }
      }
    }
    _columns[(slice.cx, slice.cz)] = column;
  }

  /// The palette index at world [x], [y], [z], or [terrainUnknownCell].
  int indexAt(int x, int y, int z) {
    final int cx = terrainFloorDiv(x, 16), cz = terrainFloorDiv(z, 16);
    final TerrainColumnRange? column = _columns[(cx, cz)];
    if (column == null || y < column.y || y >= column.y + column.height) return terrainUnknownCell;
    return column.cells[((y - column.y) * 16 + (z - cz * 16)) * 16 + (x - cx * 16)];
  }

  /// The state at world [x], [y], [z], or null when unknown.
  String? stateAt(int x, int y, int z) {
    final int index = indexAt(x, y, z);
    return index == terrainUnknownCell ? null : palette[index];
  }
}
