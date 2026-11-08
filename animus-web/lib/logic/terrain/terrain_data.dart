/// What `GET /v1/terrain/surface` and `GET /v1/terrain/blocks` answer,
/// decoded: a 64x64 surface tile (top block, its height and water depth per
/// column) and a chunk column slice (block states, palette plus packed
/// indices). Both keep the JSON they came from, which the browser cache
/// stores as is.
library;

import 'dart:convert';
import 'dart:typed_data';

/// Columns along one side of a surface tile.
const int terrainTileSize = 64;

/// Chunks along one side of a surface tile.
const int terrainTileChunks = terrainTileSize ~/ 16;

/// The most blocks one slice request reads upward.
const int terrainSliceMaxHeight = 64;

/// [value] divided by [divisor], rounded down, for negative coordinates
/// too (`>>` is not safe for negative numbers in compiled JavaScript).
int terrainFloorDiv(int value, int divisor) => (value / divisor).floor();

/// A surface height the server could not read.
const int terrainUnknownHeight = -32768;

/// Why a chunk has no data.
enum TerrainChunkState {
  ok,

  /// Not loaded on the server; Animus never loads a chunk to read it.
  unloaded,

  /// Outside the radius this token may read around its NPCs.
  forbidden;

  static TerrainChunkState parse(Object? value) => switch (value) {
        'ok' => TerrainChunkState.ok,
        'forbidden' => TerrainChunkState.forbidden,
        _ => TerrainChunkState.unloaded,
      };
}

/// A block state string split into its id and properties:
/// `minecraft:oak_stairs[facing=east,half=bottom]`.
final class TerrainBlockState {
  TerrainBlockState(this.id, this.properties);

  factory TerrainBlockState.parse(String text) {
    final int open = text.indexOf('[');
    final String id = open < 0 ? text : text.substring(0, open);
    final Map<String, String> properties = <String, String>{};
    if (open >= 0 && text.endsWith(']')) {
      for (final String pair in text.substring(open + 1, text.length - 1).split(',')) {
        final int equals = pair.indexOf('=');
        if (equals > 0) properties[pair.substring(0, equals)] = pair.substring(equals + 1);
      }
    }
    return TerrainBlockState(id, properties);
  }

  /// Namespaced id, `minecraft:stone`.
  final String id;
  final Map<String, String> properties;

  /// The id without the `minecraft:` namespace, the pack's blockstate key.
  String get key => id.startsWith('minecraft:') ? id.substring(10) : id;

  bool get waterlogged => properties['waterlogged'] == 'true';
}

/// The packed indices of a slice: [bits] per entry, least significant bit
/// first across the bytes.
Uint16List terrainUnpack(Uint8List bytes, int bits, int count) {
  final Uint16List out = Uint16List(count);
  if (bits == 0) return out;
  final int mask = (1 << bits) - 1;
  int bit = 0;
  for (int i = 0; i < count; i++) {
    int value = 0;
    int read = 0;
    while (read < bits) {
      final int byte = bit >> 3, offset = bit & 7;
      final int take = (8 - offset) < (bits - read) ? 8 - offset : bits - read;
      final int chunk = (bytes[byte] >> offset) & ((1 << take) - 1);
      value |= chunk << read;
      read += take;
      bit += take;
    }
    out[i] = value & mask;
  }
  return out;
}

/// The inverse of [terrainUnpack].
Uint8List terrainPack(List<int> values, int bits) {
  final Uint8List out = Uint8List((values.length * bits + 7) >> 3);
  if (bits == 0) return out;
  int bit = 0;
  for (final int value in values) {
    int written = 0;
    while (written < bits) {
      final int byte = bit >> 3, offset = bit & 7;
      final int take = (8 - offset) < (bits - written) ? 8 - offset : bits - written;
      out[byte] |= ((value >> written) & ((1 << take) - 1)) << offset;
      written += take;
      bit += take;
    }
  }
  return out;
}

/// Bits per index for a palette of [length] entries: 0 for one entry.
int terrainBitsFor(int length) {
  if (length <= 1) return 0;
  int bits = 0;
  while ((1 << bits) < length) {
    bits++;
  }
  return bits;
}

/// One column of a surface tile.
final class TerrainColumn {
  const TerrainColumn(this.height, this.block, this.water);

  /// The y of the highest block that is not air.
  final int height;

  /// The namespaced id of that block.
  final String block;

  /// Water blocks from the top down when the top is water, else 0.
  final int water;
}

/// A 64x64 surface tile.
final class TerrainSurfaceTile {
  TerrainSurfaceTile._(this.json, this.world, this.tx, this.tz, this.minY, this.maxY, this.hash, this.chunks, this.palette, this.heights, this.blocks,
      this.water);

  /// Null when [json] is not a surface tile.
  static TerrainSurfaceTile? fromJson(Map<String, Object?> json) {
    try {
      final int size = (json['size'] as num?)?.toInt() ?? terrainTileSize;
      if (size != terrainTileSize) return null;
      final List<TerrainChunkState> chunks = <TerrainChunkState>[for (final Object? state in json['chunks']! as List<Object?>) TerrainChunkState.parse(state)];
      if (chunks.length != terrainTileChunks * terrainTileChunks) return null;
      final Uint8List heights = base64Decode(json['heights']! as String);
      final Uint8List blocks = base64Decode(json['blocks']! as String);
      final Uint8List water = base64Decode(json['water']! as String);
      const int cells = terrainTileSize * terrainTileSize;
      if (heights.length != cells * 2 || blocks.length != cells * 2 || water.length != cells) return null;
      return TerrainSurfaceTile._(
        json,
        json['world']! as String,
        (json['tx']! as num).toInt(),
        (json['tz']! as num).toInt(),
        (json['minY']! as num).toInt(),
        (json['maxY']! as num).toInt(),
        json['hash']! as String,
        chunks,
        <String>[for (final Object? id in json['palette']! as List<Object?>) id! as String],
        ByteData.sublistView(heights),
        ByteData.sublistView(blocks),
        water,
      );
    } on Object {
      return null;
    }
  }

  final Map<String, Object?> json;
  final String world;
  final int tx;
  final int tz;
  final int minY;
  final int maxY;
  final String hash;
  final List<TerrainChunkState> chunks;
  final List<String> palette;
  final ByteData heights;
  final ByteData blocks;
  final Uint8List water;

  int get minX => tx * terrainTileSize;
  int get minZ => tz * terrainTileSize;

  /// The state of the chunk holding local column [x], [z].
  TerrainChunkState chunkAt(int x, int z) => chunks[(z >> 4) * terrainTileChunks + (x >> 4)];

  /// Local column [x], [z] (0..63), or null when the server had no data.
  TerrainColumn? local(int x, int z) {
    final int index = z * terrainTileSize + x;
    final int height = heights.getInt16(index * 2, Endian.little);
    if (height == terrainUnknownHeight) return null;
    final int block = blocks.getUint16(index * 2, Endian.little);
    return TerrainColumn(height, block < palette.length ? palette[block] : 'minecraft:air', water[index]);
  }

  /// The column at world [x], [z], when it lies in this tile and is known.
  TerrainColumn? at(int x, int z) {
    final int lx = x - minX, lz = z - minZ;
    if (lx < 0 || lz < 0 || lx >= terrainTileSize || lz >= terrainTileSize) return null;
    return local(lx, lz);
  }
}

/// A chunk column slice of block states.
final class TerrainSlice {
  TerrainSlice._(this.json, this.world, this.cx, this.cz, this.y, this.height, this.state, this.hash, this.palette, this.indices);

  /// Null when [json] is not a slice.
  static TerrainSlice? fromJson(Map<String, Object?> json) {
    try {
      final int height = (json['height']! as num).toInt();
      if (height < 1 || height > terrainSliceMaxHeight) return null;
      final List<String> palette = <String>[for (final Object? state in json['palette']! as List<Object?>) state! as String];
      if (palette.isEmpty) return null;
      final int bits = (json['bits'] as num?)?.toInt() ?? 0;
      if (bits < 0 || bits > 12) return null;
      final int count = 16 * 16 * height;
      final Uint8List data = base64Decode((json['data'] as String?) ?? '');
      if (data.length < (count * bits + 7) >> 3) return null;
      final Uint16List indices = terrainUnpack(data, bits, count);
      for (final int index in indices) {
        if (index >= palette.length) return null;
      }
      return TerrainSlice._(
        json,
        json['world']! as String,
        (json['cx']! as num).toInt(),
        (json['cz']! as num).toInt(),
        (json['y']! as num).toInt(),
        height,
        TerrainChunkState.parse(json['status']),
        json['hash']! as String,
        palette,
        indices,
      );
    } on Object {
      return null;
    }
  }

  final Map<String, Object?> json;
  final String world;
  final int cx;
  final int cz;
  final int y;
  final int height;
  final TerrainChunkState state;
  final String hash;
  final List<String> palette;
  final Uint16List indices;

  /// The state string at world [x], [wy], [z], or null outside the slice.
  String? stateAt(int x, int wy, int z) {
    final int lx = x - cx * 16, lz = z - cz * 16, ly = wy - y;
    if (lx < 0 || lz < 0 || lx >= 16 || lz >= 16 || ly < 0 || ly >= height) return null;
    return palette[indices[(ly * 16 + lz) * 16 + lx]];
  }
}
