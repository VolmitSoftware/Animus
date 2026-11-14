/// What terrain drawing needs from the model pack, built once per session:
/// the baked block models (with the pack's full `blockstates.json` when
/// the site has it, default variants otherwise) and each block's colour
/// from above for the route grid's tiles.
library;

import 'dart:convert';
import 'dart:js_interop';
import 'dart:typed_data';

import 'package:web/web.dart' as web;

import '../../mc/assets/mc_pack_manifest.dart';
import '../../mc/terrain/terrain_models.dart';
import '../../mc/terrain/terrain_tile_image.dart';
import '../mc/mc_asset_loader.dart';

final class TerrainAssets {
  TerrainAssets._(this.pack, this.models, this._pixels, {required this.fullStates});

  static Future<TerrainAssets>? _loading;
  static bool _failed = false;

  /// The assets once [load] has finished.
  static TerrainAssets? loaded;

  /// Loads the pack and the blockstates once; after a failure, again only
  /// once the pack has been asked for again (the stage's Retry).
  static Future<TerrainAssets> load() {
    if (_failed && !McAssetLoader.failed) {
      _failed = false;
      _loading = null;
    }
    return _loading ??= _load().catchError((Object error) {
      _failed = true;
      throw error;
    });
  }

  static Future<TerrainAssets> _load() async {
    final McAssetPackData pack = await McAssetLoader.load();
    Map<String, Object?>? states;
    try {
      final web.Response response = await web.window.fetch('${pack.baseUrl}/blockstates.json'.toJS).toDart;
      if (response.ok) states = jsonDecode((await response.text().toDart).toDart) as Map<String, Object?>;
    } on Object {
      states = null;
    }
    final TerrainModels models = TerrainModels(pack.resolver, uv: pack.manifest.blocks.uv, blockstates: states);
    return loaded = TerrainAssets._(pack, models, _rgba(pack.blocksImage), fullStates: states != null);
  }

  final McAssetPackData pack;
  final TerrainModels models;
  final Uint8List _pixels;

  /// False when the site's pack predates `blockstates.json`: stairs, slabs
  /// and fences then draw in their default shape.
  final bool fullStates;

  final Map<String, int> _colors = <String, int>{};

  static Uint8List _rgba(web.HTMLImageElement image) {
    final web.HTMLCanvasElement canvas = web.HTMLCanvasElement()
      ..width = image.naturalWidth
      ..height = image.naturalHeight;
    final web.CanvasRenderingContext2D ctx = canvas.getContext('2d', <String, Object?>{'willReadFrequently': true}.jsify()) as web.CanvasRenderingContext2D;
    ctx.drawImage(image, 0, 0);
    return Uint8List.fromList(ctx.getImageData(0, 0, canvas.width, canvas.height).data.toDart);
  }

  /// The colour [id] shows from above, `0xRRGGBB`.
  int colorOf(String id) => _colors.putIfAbsent(id, () {
    final (String, List<double>)? top = terrainTopTexture(pack.resolver, id);
    final McAtlas atlas = pack.manifest.blocks;
    final McAtlasEntry? entry = top == null ? null : atlas.entries[top.$1];
    if (entry == null) return 0x8A8A8A;
    final int height = entry.height ~/ entry.frames;
    return terrainMeanColor(_pixels, atlas.width, entry.x, entry.y, entry.width, height, top!.$2) ?? 0x8A8A8A;
  });
}
