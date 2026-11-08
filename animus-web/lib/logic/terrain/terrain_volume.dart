/// Slices joined into one box of block states, the input of the mesher and
/// of point checks. Cells outside every loaded slice are unknown.
library;

import 'dart:typed_data';

import 'terrain_data.dart';

/// A palette index for a cell no slice covered.
const int terrainUnknownCell = 0xFFFF;

final class TerrainVolume {
  TerrainVolume(this.x0, this.y0, this.z0, this.sx, this.sy, this.sz)
      : cells = Uint16List(sx * sy * sz)..fillRange(0, sx * sy * sz, terrainUnknownCell);

  /// The box [x0]..[x0]+[sx] (and so on) filled from [slices].
  factory TerrainVolume.of(int x0, int y0, int z0, int sx, int sy, int sz, Iterable<TerrainSlice> slices) {
    final TerrainVolume volume = TerrainVolume(x0, y0, z0, sx, sy, sz);
    for (final TerrainSlice slice in slices) {
      volume.add(slice);
    }
    return volume;
  }

  final int x0;
  final int y0;
  final int z0;
  final int sx;
  final int sy;
  final int sz;
  final Uint16List cells;
  final List<String> palette = <String>[];
  final Map<String, int> _ids = <String, int>{};

  int _intern(String state) => _ids.putIfAbsent(state, () {
        palette.add(state);
        return palette.length - 1;
      });

  /// Copies the part of [slice] inside the box; a slice that is not ok
  /// leaves its cells unknown.
  void add(TerrainSlice slice) {
    if (slice.state != TerrainChunkState.ok) return;
    final List<int> local = <int>[for (final String state in slice.palette) _intern(state)];
    for (int ly = 0; ly < slice.height; ly++) {
      final int y = slice.y + ly - y0;
      if (y < 0 || y >= sy) continue;
      for (int lz = 0; lz < 16; lz++) {
        final int z = slice.cz * 16 + lz - z0;
        if (z < 0 || z >= sz) continue;
        for (int lx = 0; lx < 16; lx++) {
          final int x = slice.cx * 16 + lx - x0;
          if (x < 0 || x >= sx) continue;
          cells[(y * sz + z) * sx + x] = local[slice.indices[(ly * 16 + lz) * 16 + lx]];
        }
      }
    }
  }

  bool contains(int x, int y, int z) => x >= x0 && y >= y0 && z >= z0 && x < x0 + sx && y < y0 + sy && z < z0 + sz;

  /// The palette index at world [x], [y], [z], or [terrainUnknownCell].
  int indexAt(int x, int y, int z) => contains(x, y, z) ? cells[((y - y0) * sz + (z - z0)) * sx + (x - x0)] : terrainUnknownCell;

  /// The state at world [x], [y], [z], or null when unknown.
  String? stateAt(int x, int y, int z) {
    final int index = indexAt(x, y, z);
    return index == terrainUnknownCell ? null : palette[index];
  }
}
