/// A [TerrainVolume] to meshes for the stage: one pass per 16-block
/// section, faces hidden by a full opaque neighbour (or by the same glass),
/// water and lava drawn as surfaces at 14/16 of a block, translucent blocks
/// and water in their own meshes so the renderer blends them last. Cells
/// outside the volume or unknown count as open, so the window's cut edges
/// show. Positions are world blocks minus [origin], the NPC's feet.
library;

import '../../logic/terrain/terrain_data.dart';
import '../../logic/terrain/terrain_volume.dart';
import '../assets/mc_tint.dart';
import '../mesh/mc_element_mesher.dart';
import '../mesh/mc_mesh.dart';
import '../scene/mc_math.dart';
import 'terrain_models.dart';

/// Vertices per mesh before the mesher starts another (16-bit indices).
const int _vertexLimit = 65532;

/// The top of a fluid block with no fluid above it.
const double terrainFluidTop = 14 / 16;

/// One mesh and whether it is drawn in the blended pass.
typedef TerrainMesh = ({McMesh mesh, bool translucent});

final class _Builders {
  _Builders(this.translucent);

  final bool translucent;
  final List<McMesh> done = <McMesh>[];
  McMeshBuilder current = McMeshBuilder();

  McMeshBuilder room() {
    if (current.vertexCount + 4 > _vertexLimit) {
      done.add(current.build());
      current = McMeshBuilder();
    }
    return current;
  }

  List<TerrainMesh> finish() => <TerrainMesh>[
        for (final McMesh mesh in <McMesh>[...done, if (current.vertexCount > 0) current.build()]) (mesh: mesh, translucent: translucent),
      ];
}

/// Meshes [volume] with [models]; [water] and [lava] are the atlas rects of
/// the still fluid textures.
List<TerrainMesh> terrainMesh(TerrainVolume volume, TerrainModels models, {required McVec3 origin, required McUvRect water, required McUvRect lava}) {
  final List<TerrainBlockModel> byIndex = <TerrainBlockModel>[for (final String state in volume.palette) models.of(state)];
  final List<String> ids = <String>[for (final String state in volume.palette) TerrainBlockState.parse(state).id];
  TerrainBlockModel? at(int x, int y, int z) {
    final int index = volume.indexAt(x, y, z);
    return index == terrainUnknownCell ? null : byIndex[index];
  }

  String? idAt(int x, int y, int z) {
    final int index = volume.indexAt(x, y, z);
    return index == terrainUnknownCell ? null : ids[index];
  }

  final _Builders opaque = _Builders(false), blended = _Builders(true);
  for (int y = volume.y0; y < volume.y0 + volume.sy; y++) {
    for (int z = volume.z0; z < volume.z0 + volume.sz; z++) {
      for (int x = volume.x0; x < volume.x0 + volume.sx; x++) {
        final TerrainBlockModel? block = at(x, y, z);
        if (block == null || (block.quads.isEmpty && block.fluid == null)) continue;
        final double ox = x - origin.x, oy = y - origin.y, oz = z - origin.z;
        final String id = idAt(x, y, z)!;
        bool hidden(int side) {
          final (int dx, int dy, int dz) = terrainSides[side];
          final TerrainBlockModel? next = at(x + dx, y + dy, z + dz);
          if (next == null) return false;
          return next.occludes || (block.sameCulls && next.sameCulls && idAt(x + dx, y + dy, z + dz) == id);
        }

        final _Builders target = block.translucent ? blended : opaque;
        for (final TerrainQuad quad in block.quads) {
          if (quad.cull != TerrainQuad.noCull && hidden(quad.cull)) continue;
          final List<double> c = quad.corners;
          target.room().quad(
            <List<double>>[
              <double>[c[0] + ox, c[1] + oy, c[2] + oz],
              <double>[c[3] + ox, c[4] + oy, c[5] + oz],
              <double>[c[6] + ox, c[7] + oy, c[8] + oz],
              <double>[c[9] + ox, c[10] + oy, c[11] + oz],
            ],
            <List<double>>[
              <double>[quad.uvs[0], quad.uvs[1]],
              <double>[quad.uvs[2], quad.uvs[3]],
              <double>[quad.uvs[4], quad.uvs[5]],
              <double>[quad.uvs[6], quad.uvs[7]],
            ],
            quad.shade,
            quad.tint,
          );
        }
        final String? fluid = block.fluid;
        if (fluid != null) {
          bool sameFluid(int dx, int dy, int dz) => at(x + dx, y + dy, z + dz)?.fluid == fluid;
          final double top = sameFluid(0, 1, 0) ? 1 : terrainFluidTop;
          _fluid(fluid == 'water' ? blended : opaque, fluid == 'water' ? water : lava, fluid == 'water' ? mcTintWater : mcTintNone, ox, oy, oz, top,
              (int side) {
            final (int dx, int dy, int dz) = terrainSides[side];
            if (sameFluid(dx, dy, dz)) return true;
            final TerrainBlockModel? next = at(x + dx, y + dy, z + dz);
            return next != null && next.occludes;
          });
        }
      }
    }
  }
  return <TerrainMesh>[...opaque.finish(), ...blended.finish()];
}

/// The faces of one fluid block up to [top] that [hidden] does not cover.
void _fluid(_Builders target, McUvRect r, List<double> tint, double x, double y, double z, double top, bool Function(int side) hidden) {
  final List<List<double>> uv = <List<double>>[
    <double>[r.u0, r.v0],
    <double>[r.u1, r.v0],
    <double>[r.u1, r.v1],
    <double>[r.u0, r.v1],
  ];
  final double x1 = x + 1, z1 = z + 1, y1 = y + top;
  final List<List<List<double>>> faces = <List<List<double>>>[
    <List<double>>[<double>[x, y, z1], <double>[x1, y, z1], <double>[x1, y, z], <double>[x, y, z]],
    <List<double>>[<double>[x, y1, z], <double>[x1, y1, z], <double>[x1, y1, z1], <double>[x, y1, z1]],
    <List<double>>[<double>[x1, y1, z], <double>[x, y1, z], <double>[x, y, z], <double>[x1, y, z]],
    <List<double>>[<double>[x, y1, z1], <double>[x1, y1, z1], <double>[x1, y, z1], <double>[x, y, z1]],
    <List<double>>[<double>[x, y1, z], <double>[x, y1, z1], <double>[x, y, z1], <double>[x, y, z]],
    <List<double>>[<double>[x1, y1, z1], <double>[x1, y1, z], <double>[x1, y, z], <double>[x1, y, z1]],
  ];
  const List<double> shades = <double>[mcShadeDown, mcShadeUp, mcShadeNorthSouth, mcShadeNorthSouth, mcShadeEastWest, mcShadeEastWest];
  for (int side = 0; side < 6; side++) {
    // The top always shows unless more of the same fluid sits on it.
    if (side == 1 ? top >= 1 : hidden(side)) continue;
    target.room().quad(faces[side], uv, shades[side], tint);
  }
}
