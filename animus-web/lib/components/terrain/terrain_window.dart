/// The blocks a stage draws along a path: every chunk slice within the
/// radius of the path's points and legs ([terrainPathPlan]), 32 blocks tall
/// around the path beside it, each chunk meshed on its own and its mesh
/// kept while neither its slice, its cut nor a neighbour changed. Loads run
/// one at a time per window; a newer request makes an older one's result
/// stale and it is dropped.
library;

import 'dart:async';

import '../../logic/terrain/terrain_data.dart';
import '../../logic/terrain/terrain_path.dart';
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

/// The meshed window and what it knows, for drawing and for snapping.
final class TerrainWindowResult {
  const TerrainWindowResult({required this.nodes, required this.volume, required this.chunks, required this.unloaded, required this.forbidden, required this.truncated, required this.fullStates});

  final List<McMeshNode> nodes;
  final TerrainVolume volume;

  /// Chunk slices asked for, and how many came back unloaded or forbidden.
  final int chunks;
  final int unloaded;
  final int forbidden;

  /// True when the path needs more slices than one view asks for.
  final bool truncated;
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

/// One chunk's meshes and what decided them.
typedef _ChunkMeshes = ({String signature, List<TerrainMesh> meshes});

final class TerrainWindow {
  int _generation = 0;
  final Map<(int, int), _ChunkMeshes> _meshes = <(int, int), _ChunkMeshes>{};

  /// Reads and meshes [plan] in [world]; positions in the meshes are
  /// relative to [origin]. Null when a newer load started.
  Future<TerrainWindowResult?> load(TerrainClient client, String world, TerrainPathPlan plan, {required McVec3 origin, required String keyPrefix}) async {
    final int generation = ++_generation;
    final TerrainAssets assets = await TerrainAssets.load();
    const int height = terrainPathBelow + terrainPathAbove;
    final List<TerrainSlice?> slices = await Future.wait(<Future<TerrainSlice?>>[
      for (final TerrainPathChunk chunk in plan.chunks) client.slice(world, chunk.cx, chunk.cz, chunk.y, height),
    ]);
    if (generation != _generation) return null;
    final TerrainVolume volume = TerrainVolume();
    final Map<(int, int), String> cuts = <(int, int), String>{};
    for (final TerrainSlice? slice in slices) {
      if (slice == null || slice.state != TerrainChunkState.ok) continue;
      volume.add(slice, keep: plan.covers);
      cuts[(slice.cx, slice.cz)] = '${slice.hash}@${slice.y}:${plan.mask(slice.cx, slice.cz)}';
    }
    final String at = '$world:${origin.x}:${origin.y}:${origin.z}';
    final List<McMeshNode> nodes = <McMeshNode>[];
    final Set<(int, int)> kept = <(int, int)>{};
    int fresh = 0;
    for (final (int cx, int cz) in volume.chunks.toList()) {
      final String signature = <String?>[at, cuts[(cx, cz)], cuts[(cx - 1, cz)], cuts[(cx + 1, cz)], cuts[(cx, cz - 1)], cuts[(cx, cz + 1)]].join('|');
      _ChunkMeshes? meshed = _meshes[(cx, cz)];
      if (meshed == null || meshed.signature != signature) {
        // Let the page breathe between chunks on a long path.
        if (++fresh % 8 == 0) {
          await Future<void>.delayed(Duration.zero);
          if (generation != _generation) return null;
        }
        meshed = (
          signature: signature,
          meshes: terrainMesh(
            volume,
            assets.models,
            origin: origin,
            chunk: (cx, cz),
            water: assets.pack.manifest.blocks.uv('block/water_still') ?? McUvRect.missing,
            lava: assets.pack.manifest.blocks.uv('block/lava_still') ?? McUvRect.missing,
          ),
        );
        _meshes[(cx, cz)] = meshed;
      }
      kept.add((cx, cz));
      for (int i = 0; i < meshed.meshes.length; i++) {
        nodes.add(McMeshNode(key: '$keyPrefix:$cx:$cz:$i', mesh: meshed.meshes[i].mesh, meshSignature: '${signature.hashCode}:$cx:$cz:$i', translucent: meshed.meshes[i].translucent));
      }
    }
    _meshes.removeWhere(((int, int) chunk, _ChunkMeshes _) => !kept.contains(chunk));
    return TerrainWindowResult(
      nodes: nodes,
      volume: volume,
      chunks: slices.length,
      unloaded: slices.where((TerrainSlice? slice) => slice == null || slice.state == TerrainChunkState.unloaded).length,
      forbidden: slices.where((TerrainSlice? slice) => slice?.state == TerrainChunkState.forbidden).length,
      truncated: plan.truncated,
      fullStates: assets.fullStates,
    );
  }

  /// Drops any load still running.
  void cancel() => _generation++;
}
