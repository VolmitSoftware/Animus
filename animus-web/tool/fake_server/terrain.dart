/// `GET /v1/terrain/surface` and `GET /v1/terrain/blocks` over a generated
/// fixture world: rolling grass with a flat plaza at the origin, a pond, an
/// oak, a stone path that ends in a slab and stairs, a fence, a glass
/// pillar, flowers and grass. Chunks east of x 128 read as unloaded. A
/// chunk farther than [fakeTerrainRadius] from every NPC the token may see
/// is forbidden, as on a real server.
library;

import 'dart:convert';
import 'dart:math' as math;
import 'dart:typed_data';

import 'package:animus_editor/logic/terrain/terrain_data.dart';
import 'package:crypto/crypto.dart';

import 'protocol.dart';

/// `[editor] terrainRadius` of the fake server.
const int fakeTerrainRadius = 160;

const int _minY = -64;
const int _maxY = 320;

/// Chunks at or past this x read as not loaded.
const int fakeUnloadedChunkX = 8;

/// The fixture world's surface height at [x], [z].
int fakeSurfaceY(int x, int z) {
  if (x.abs() <= 4 && z.abs() <= 4) return 64;
  return 64 + (2.5 * math.sin(x / 7.0) + 2 * math.cos(z / 9.0)).floor();
}

bool _pond(int x, int z) => (x - 10) * (x - 10) + (z + 8) * (z + 8) < 16;

int _noise(int x, int z) => ((x * 73856093) ^ (z * 19349663)) & 0x7fffffff;

/// The block state at [x], [y], [z] of the fixture world.
String fakeBlockAt(int x, int y, int z) {
  final int surface = fakeSurfaceY(x, z);
  if (_pond(x, z)) {
    if (y <= 59) return 'minecraft:stone';
    if (y == 60) return 'minecraft:sand';
    if (y <= 63) return 'minecraft:water[level=0]';
    return 'minecraft:air';
  }
  // The oak: four logs and a crown.
  const int treeX = -8, treeZ = -6;
  final int treeBase = fakeSurfaceY(treeX, treeZ);
  if (x == treeX && z == treeZ && y > treeBase && y <= treeBase + 4) return 'minecraft:oak_log[axis=y]';
  final int dx = (x - treeX).abs(), dz = (z - treeZ).abs();
  if (y >= treeBase + 3 && y <= treeBase + 4 && dx <= 2 && dz <= 2 && !(dx == 2 && dz == 2)) return 'minecraft:oak_leaves[distance=1,persistent=false,waterlogged=false]';
  if (y == treeBase + 5 && dx <= 1 && dz <= 1) return 'minecraft:oak_leaves[distance=1,persistent=false,waterlogged=false]';
  // The path east along z 0, a slab and a stair at its end.
  if (z == 0 && x >= 5 && x <= 20) {
    if (y == surface) return 'minecraft:stone_bricks';
    if (y < surface) return 'minecraft:stone';
    return 'minecraft:air';
  }
  if (z == 0 && x == 21 && y == surface + 1) return 'minecraft:stone_brick_slab[type=bottom,waterlogged=false]';
  if (z == 0 && x == 22 && y == surface + 1) return 'minecraft:stone_brick_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]';
  if (z == 6 && x >= -5 && x <= -1 && y == surface + 1) {
    return 'minecraft:oak_fence[east=${x < -1},north=false,south=false,waterlogged=false,west=${x > -5}]';
  }
  if (x == 3 && z == -4 && y > surface && y <= surface + 2) return 'minecraft:glass';
  if (y > surface + 1) return 'minecraft:air';
  if (y == surface + 1) {
    final int roll = _noise(x, z) % 23;
    if (roll == 0) return 'minecraft:poppy';
    if (roll < 4) return 'minecraft:short_grass';
    return 'minecraft:air';
  }
  if (y == surface) return 'minecraft:grass_block[snowy=false]';
  if (y >= surface - 3) return 'minecraft:dirt';
  return 'minecraft:stone';
}

String _id(String state) {
  final int open = state.indexOf('[');
  return open < 0 ? state : state.substring(0, open);
}

/// One NPC position a token may see.
typedef FakeAnchor = ({String world, double x, double z});

TerrainChunkState _chunkState(String world, int cx, int cz, List<FakeAnchor> anchors) {
  final int x0 = cx * 16, z0 = cz * 16;
  bool near = false;
  for (final FakeAnchor anchor in anchors) {
    if (anchor.world != world) continue;
    final double dx = anchor.x < x0 ? x0 - anchor.x : (anchor.x > x0 + 16 ? anchor.x - x0 - 16 : 0);
    final double dz = anchor.z < z0 ? z0 - anchor.z : (anchor.z > z0 + 16 ? anchor.z - z0 - 16 : 0);
    if (math.sqrt(dx * dx + dz * dz) <= fakeTerrainRadius) near = true;
  }
  if (!near) return TerrainChunkState.forbidden;
  if (world != 'world' || cx >= fakeUnloadedChunkX) return TerrainChunkState.unloaded;
  return TerrainChunkState.ok;
}

int _int(Map<String, String> query, String key, int min, int max) {
  final int? value = int.tryParse(query[key] ?? '');
  if (value == null || value < min || value > max) throw ApiError('invalid-request', '$key must be an integer $min..$max.');
  return value;
}

String _world(Map<String, String> query) {
  final String? world = query['world'];
  if (world == null || world.isEmpty) throw ApiError('invalid-request', 'world is required.');
  if (world != 'world' && world != 'world_nether') throw ApiError('not-found', 'No loaded world $world.');
  return world;
}

String _hash(List<Object?> parts) => 'sha256:${sha256.convert(utf8.encode(jsonEncode(parts)))}';

/// An answer with the ETag, or 304 when the browser already has [hash].
FakeResponse _cached(FakeRequest request, String hash, Map<String, Object?> body) {
  final Map<String, String> headers = <String, String>{'ETag': '"$hash"'};
  final String? known = request.header('if-none-match')?.replaceAll('"', '').trim();
  if (known == hash) return FakeResponse(304, headers: headers);
  return FakeResponse(200, body: body, headers: headers);
}

/// `GET /v1/terrain/surface?world=&tx=&tz=`.
FakeResponse fakeTerrainSurface(FakeRequest request, List<FakeAnchor> anchors) {
  final Map<String, String> query = request.query;
  final String world = _world(query);
  final int tx = _int(query, 'tx', -468750, 468750), tz = _int(query, 'tz', -468750, 468750);
  final List<TerrainChunkState> chunks = <TerrainChunkState>[
    for (int cz = 0; cz < terrainTileChunks; cz++)
      for (int cx = 0; cx < terrainTileChunks; cx++) _chunkState(world, tx * terrainTileChunks + cx, tz * terrainTileChunks + cz, anchors),
  ];
  const int cells = terrainTileSize * terrainTileSize;
  final ByteData heights = ByteData(cells * 2), blocks = ByteData(cells * 2);
  final Uint8List water = Uint8List(cells);
  final List<String> palette = <String>['minecraft:air'];
  final Map<String, int> ids = <String, int>{'minecraft:air': 0};
  for (int z = 0; z < terrainTileSize; z++) {
    for (int x = 0; x < terrainTileSize; x++) {
      final int index = z * terrainTileSize + x;
      if (chunks[(z >> 4) * terrainTileChunks + (x >> 4)] != TerrainChunkState.ok) {
        heights.setInt16(index * 2, terrainUnknownHeight, Endian.little);
        continue;
      }
      final int wx = tx * terrainTileSize + x, wz = tz * terrainTileSize + z;
      int y = fakeSurfaceY(wx, wz) + 6;
      while (y > _minY && fakeBlockAt(wx, y, wz) == 'minecraft:air') {
        y--;
      }
      final String id = _id(fakeBlockAt(wx, y, wz));
      heights.setInt16(index * 2, y, Endian.little);
      blocks.setUint16(index * 2, ids.putIfAbsent(id, () {
        palette.add(id);
        return palette.length - 1;
      }), Endian.little);
      if (id == 'minecraft:water') {
        int depth = 0;
        while (depth < 255 && _id(fakeBlockAt(wx, y - depth, wz)) == 'minecraft:water') {
          depth++;
        }
        water[index] = depth;
      }
    }
  }
  final List<String> states = <String>[for (final TerrainChunkState state in chunks) state.name];
  final String heightText = base64Encode(heights.buffer.asUint8List()), blockText = base64Encode(blocks.buffer.asUint8List()), waterText = base64Encode(water);
  final String hash = _hash(<Object?>[world, tx, tz, states, palette, heightText, blockText, waterText]);
  return _cached(request, hash, <String, Object?>{
    'world': world,
    'tx': tx,
    'tz': tz,
    'size': terrainTileSize,
    'minY': _minY,
    'maxY': _maxY,
    'hash': hash,
    'chunks': states,
    'palette': palette,
    'heights': heightText,
    'blocks': blockText,
    'water': waterText,
  });
}

/// `GET /v1/terrain/blocks?world=&cx=&cz=&y=&height=`.
FakeResponse fakeTerrainBlocks(FakeRequest request, List<FakeAnchor> anchors) {
  final Map<String, String> query = request.query;
  final String world = _world(query);
  final int cx = _int(query, 'cx', -1875000, 1875000), cz = _int(query, 'cz', -1875000, 1875000);
  final int asked = _int(query, 'y', -30000000, 30000000), askedHeight = _int(query, 'height', 1, terrainSliceMaxHeight);
  final int y0 = math.max(asked, _minY), y1 = math.min(asked + askedHeight, _maxY);
  if (y1 <= y0) throw ApiError('invalid-request', 'y $asked to ${asked + askedHeight} is outside the world ($_minY to $_maxY).');
  final int height = y1 - y0;
  final TerrainChunkState state = _chunkState(world, cx, cz, anchors);
  List<String> palette = <String>['minecraft:air'];
  int bits = 0;
  String data = '';
  if (state == TerrainChunkState.ok) {
    final Map<String, int> ids = <String, int>{'minecraft:air': 0};
    final List<int> indices = <int>[
      for (int y = y0; y < y1; y++)
        for (int z = 0; z < 16; z++)
          for (int x = 0; x < 16; x++)
            ids.putIfAbsent(fakeBlockAt(cx * 16 + x, y, cz * 16 + z), () {
              palette.add(fakeBlockAt(cx * 16 + x, y, cz * 16 + z));
              return palette.length - 1;
            }),
    ];
    bits = terrainBitsFor(palette.length);
    data = base64Encode(terrainPack(indices, bits));
  } else {
    palette = <String>['minecraft:air'];
  }
  final String hash = _hash(<Object?>[world, cx, cz, y0, height, state.name, palette, bits, data]);
  return _cached(request, hash, <String, Object?>{
    'world': world,
    'cx': cx,
    'cz': cz,
    'y': y0,
    'height': height,
    'status': state.name,
    'hash': hash,
    'palette': palette,
    'bits': bits,
    'data': data,
  });
}
