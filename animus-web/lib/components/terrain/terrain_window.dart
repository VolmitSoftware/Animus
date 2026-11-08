/// The block window a stage draws around a place: every chunk slice within
/// [TerrainWindow.radius] blocks of the centre, 32 blocks tall around its
/// y, joined into a volume and meshed. Loads run one at a time per window;
/// a newer request makes an older one's result stale and it is dropped.
library;

import 'dart:async';

import '../../logic/terrain/terrain_data.dart';
import '../../logic/terrain/terrain_snap.dart';
import '../../logic/terrain/terrain_volume.dart';
import '../../mc/mesh/mc_mesh.dart';
import '../../mc/scene/mc_math.dart';
import '../../mc/scene/mc_scene.dart';
import '../../mc/terrain/terrain_mesher.dart';
import '../../services/terrain/terrain_client.dart';
import 'terrain_assets.dart';

/// Radii the stage and the 3D route view offer, blocks.
const List<int> terrainRadii = <int>[8, 16, 24];

/// Blocks below and above the centre the window reads.
const int terrainWindowBelow = 16;
const int terrainWindowAbove = 16;

/// The meshed window and what it knows, for drawing and for snapping.
final class TerrainWindowResult {
  const TerrainWindowResult({required this.nodes, required this.volume, required this.chunks, required this.unloaded, required this.forbidden, required this.fullStates});

  final List<McMeshNode> nodes;
  final TerrainVolume volume;

  /// Chunk slices asked for, and how many came back unloaded or forbidden.
  final int chunks;
  final int unloaded;
  final int forbidden;
  final bool fullStates;
}

/// A [TerrainLookup] over one window's volume.
final class TerrainVolumeLookup implements TerrainLookup {
  const TerrainVolumeLookup(this.volume);

  final TerrainVolume volume;

  @override
  TerrainColumn? column(int x, int z) => null;

  @override
  String? state(int x, int y, int z) => volume.stateAt(x, y, z);
}

final class TerrainWindow {
  int _generation = 0;

  /// Reads and meshes the window around world [x], [y], [z]; positions in
  /// the meshes are relative to [origin]. Null when a newer load started.
  Future<TerrainWindowResult?> load(TerrainClient client, String world, double x, double y, double z, {required int radius, required McVec3 origin, required String keyPrefix}) async {
    final int generation = ++_generation;
    final TerrainAssets assets = await TerrainAssets.load();
    final int bx = x.floor(), by = y.floor(), bz = z.floor();
    final int x0 = bx - radius, z0 = bz - radius, y0 = by - terrainWindowBelow;
    final int size = radius * 2 + 1, height = terrainWindowBelow + terrainWindowAbove;
    final List<Future<TerrainSlice?>> asked = <Future<TerrainSlice?>>[
      for (int cz = terrainFloorDiv(z0, 16); cz <= terrainFloorDiv(z0 + size - 1, 16); cz++)
        for (int cx = terrainFloorDiv(x0, 16); cx <= terrainFloorDiv(x0 + size - 1, 16); cx++) client.slice(world, cx, cz, y0, height),
    ];
    final List<TerrainSlice?> slices = await Future.wait(asked);
    if (generation != _generation) return null;
    final TerrainVolume volume = TerrainVolume.of(x0, y0, z0, size, height, size, slices.whereType<TerrainSlice>());
    final List<TerrainMesh> meshes = terrainMesh(
      volume,
      assets.models,
      origin: origin,
      water: assets.pack.manifest.blocks.uv('block/water_still') ?? McUvRect.missing,
      lava: assets.pack.manifest.blocks.uv('block/lava_still') ?? McUvRect.missing,
    );
    final String signature = <String>[for (final TerrainSlice? slice in slices) slice?.hash ?? '-'].join(',');
    final String place = '$world:$x0:$y0:$z0:$size:${origin.x}:${origin.y}:${origin.z}';
    return TerrainWindowResult(
      nodes: <McMeshNode>[
        for (int i = 0; i < meshes.length; i++)
          McMeshNode(key: '$keyPrefix:$i', mesh: meshes[i].mesh, meshSignature: '$place:$i:${signature.hashCode}', translucent: meshes[i].translucent),
      ],
      volume: volume,
      chunks: slices.length,
      unloaded: slices.where((TerrainSlice? slice) => slice == null || slice.state == TerrainChunkState.unloaded).length,
      forbidden: slices.where((TerrainSlice? slice) => slice?.state == TerrainChunkState.forbidden).length,
      fullStates: assets.fullStates,
    );
  }

  /// Drops any load still running.
  void cancel() => _generation++;
}
