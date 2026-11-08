/// A surface tile as a 64x64 RGBA image, one pixel per column, coloured
/// like a vanilla map: the top block's colour, lighter where the ground
/// rises toward the south and darker where it falls, water darker with
/// depth. Unloaded chunks are flat grey, forbidden ones hatched.
library;

import 'dart:typed_data';

import '../../logic/terrain/terrain_data.dart';
import '../../logic/terrain/terrain_snap.dart';
import '../assets/mc_model_resolver.dart';
import '../assets/mc_tint.dart';

/// The colour of a block id, `0xRRGGBB`.
typedef TerrainColorOf = int Function(String id);

const int _waterColor = 0x3F76E4;

int _channel(int color, int shift) => (color >> shift) & 0xFF;

int _scaled(int value, double factor) {
  final int out = (value * factor).round();
  return out < 0 ? 0 : (out > 255 ? 255 : out);
}

/// The RGBA pixels of [tile].
Uint8List terrainTileRgba(TerrainSurfaceTile tile, TerrainColorOf colorOf) {
  final Uint8List out = Uint8List(terrainTileSize * terrainTileSize * 4);
  for (int z = 0; z < terrainTileSize; z++) {
    for (int x = 0; x < terrainTileSize; x++) {
      final int p = (z * terrainTileSize + x) * 4;
      final TerrainColumn? column = tile.local(x, z);
      if (column == null) {
        final bool forbidden = tile.chunkAt(x, z) == TerrainChunkState.forbidden;
        final bool stripe = ((x + z) & 7) < 2;
        final int grey = forbidden ? (stripe ? 120 : 150) : 140;
        out[p] = grey;
        out[p + 1] = grey;
        out[p + 2] = grey;
        out[p + 3] = forbidden ? 110 : 70;
        continue;
      }
      int color = colorOf(column.block);
      double factor = 1;
      if (column.water > 0) {
        color = _waterColor;
        factor = (1.0 - column.water * 0.06).clamp(0.55, 1.0);
      } else {
        final TerrainColumn? north = z > 0 ? tile.local(x, z - 1) : null;
        if (north != null) {
          final int rise = terrainSurfaceStand(column) - terrainSurfaceStand(north);
          factor = rise > 0 ? 1.12 : (rise < 0 ? 0.82 : 1.0);
        }
      }
      out[p] = _scaled(_channel(color, 16), factor);
      out[p + 1] = _scaled(_channel(color, 8), factor);
      out[p + 2] = _scaled(_channel(color, 0), factor);
      out[p + 3] = 255;
    }
  }
  return out;
}

/// The texture a block shows from above: the `up` face of its default
/// model's first element that has one, with that face's tint.
(String, List<double>)? terrainTopTexture(McModelResolver resolver, String id) {
  final String key = id.startsWith('minecraft:') ? id.substring(10) : id;
  final McResolvedModel? model = resolver.resolveBlock(key);
  if (model == null) return null;
  McResolvedFace? side;
  for (final McResolvedElement element in model.elements) {
    final McResolvedFace? up = element.faces['up'];
    if (up != null) return (up.texture, mcTintForBlock(key, up.tintIndex));
    side ??= element.faces['north'] ?? element.faces['south'];
  }
  return side == null ? null : (side.texture, mcTintForBlock(key, side.tintIndex));
}

/// The alpha-weighted mean of an RGBA region, times [tint], as `0xRRGGBB`;
/// null when the region is fully transparent.
int? terrainMeanColor(Uint8List rgba, int stride, int x0, int y0, int width, int height, List<double> tint) {
  double r = 0, g = 0, b = 0, weight = 0;
  for (int y = y0; y < y0 + height; y++) {
    for (int x = x0; x < x0 + width; x++) {
      final int p = (y * stride + x) * 4;
      final double a = rgba[p + 3] / 255;
      r += rgba[p] * a;
      g += rgba[p + 1] * a;
      b += rgba[p + 2] * a;
      weight += a;
    }
  }
  if (weight == 0) return null;
  return _scaled((r / weight).round(), tint[0]) << 16 | _scaled((g / weight).round(), tint[1]) << 8 | _scaled((b / weight).round(), tint[2]);
}
