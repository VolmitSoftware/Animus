/// Fetches `web/assets/mc/<version>/` once per session and hands every stage
/// the same decoded pack. Lazy: nothing loads until the first stage mounts,
/// and entity textures decode on first use. Failure is sticky and
/// observable so a stage can say why it is blank instead of retrying.
/// Adapted from Gloss Web's asset loader.
library;

import 'dart:async';
import 'dart:convert';
import 'dart:js_interop';
import 'dart:typed_data';

import 'package:web/web.dart' as web;

import '../../mc/assets/mc_model_resolver.dart';
import '../../mc/assets/mc_model_store.dart';
import '../../mc/assets/mc_pack_manifest.dart';
import '../../mc/mesh/mc_generated_mesher.dart';
import '../../mc/mesh/mc_mesh.dart';

final class _TilePixels implements McPixelSource {
  _TilePixels(this.width, this.height, this._alpha);

  @override
  final int width;
  @override
  final int height;
  final Uint8List _alpha;

  @override
  int alphaAt(int x, int y) => x < 0 || y < 0 || x >= width || y >= height ? 0 : _alpha[y * width + x];
}

final class McAssetPackData {
  McAssetPackData._(this.manifest, this.resolver, this.blocksImage, this.itemsImage, this._blocksAlpha, this._itemsAlpha);

  final McPackManifest manifest;
  final McModelResolver resolver;
  final web.HTMLImageElement blocksImage;
  final web.HTMLImageElement itemsImage;
  final Map<String, web.HTMLImageElement> _images = <String, web.HTMLImageElement>{};
  final Map<String, String> _registered = <String, String>{};
  final Map<String, Future<void>> _decoding = <String, Future<void>>{};
  final Set<String> _failed = <String>{};
  final Uint8List _blocksAlpha;
  final Uint8List _itemsAlpha;

  String get baseUrl => mcPackBaseUrl(manifest.version);

  /// A decoded image by texture id (`entity/horse/horse_brown`) or by a
  /// runtime id passed to [register]. Throws when not decoded yet.
  web.HTMLImageElement image(String id) {
    final web.HTMLImageElement? found = _images[id];
    if (found == null) throw StateError('no pack image $id');
    return found;
  }

  bool hasImage(String id) => _images.containsKey(id);

  /// True once decoding [id] failed (a missing texture or a broken PNG).
  bool failed(String id) => _failed.contains(id);

  /// Names a runtime image (a loaded skin) so nodes can bind it by [id]; a
  /// new [url] for the same id replaces the old image.
  void register(String id, String url) {
    if (_registered[id] == url) return;
    _registered[id] = url;
    _images.remove(id);
    _failed.remove(id);
    _decoding.remove(id);
  }

  /// Starts decoding [id] if needed; resolves when it is ready or failed.
  Future<void> ensure(String id) {
    if (_images.containsKey(id) || _failed.contains(id)) return Future<void>.value();
    final String? url = _registered[id] ?? _packUrl(id);
    if (url == null) {
      _failed.add(id);
      return Future<void>.value();
    }
    return _decoding.putIfAbsent(id, () async {
      try {
        final web.HTMLImageElement decoded = await mcDecodeImage(url);
        if (_registered[id] == null || _registered[id] == url) _images[id] = decoded;
      } on Object {
        _failed.add(id);
      } finally {
        _decoding.remove(id);
        McAssetLoader.notify();
      }
    });
  }

  String? _packUrl(String id) {
    final String? path = manifest.entityTextures[id];
    return path == null ? null : '$baseUrl/$path';
  }

  /// Frame 0 of a texture wherever it lives, items first: a generated item's
  /// `block/` layer is copied into the items atlas by the pack.
  McUvRect? uvAny(String textureId) => manifest.items.uv(textureId) ?? manifest.blocks.uv(textureId);

  /// Alpha of one texture tile (frame 0), read once from the atlas the id is
  /// in, items first like [uvAny].
  McPixelSource pixels(String textureId) {
    final bool inItems = manifest.items.entries.containsKey(textureId);
    final McAtlas atlas = inItems ? manifest.items : manifest.blocks;
    final Uint8List alpha = inItems ? _itemsAlpha : _blocksAlpha;
    final McAtlasEntry? entry = atlas.entries[textureId];
    if (entry == null) return const McListPixels(16, 16, <int>[]);
    final int h = entry.height ~/ entry.frames;
    final Uint8List tile = Uint8List(entry.width * h);
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < entry.width; x++) {
        tile[y * entry.width + x] = alpha[(entry.y + y) * atlas.width + entry.x + x];
      }
    }
    return _TilePixels(entry.width, h, tile);
  }
}

/// Why the pack did not load, in the terms a stage reports.
enum McPackFailure {
  /// The site has no pack (HTTP 404): it was built without the extracted
  /// Minecraft assets.
  missing,

  /// The request failed or the server answered with another error.
  network,
}

/// A pack request the site answered with [status].
final class McPackHttpError implements Exception {
  const McPackHttpError(this.url, this.status);

  final String url;
  final int status;

  @override
  String toString() => 'GET $url: HTTP $status';
}

final class McAssetLoader {
  static const String version = '26.3';
  static Future<McAssetPackData>? _loading;
  static McAssetPackData? _loaded;
  static Object? _error;

  /// True while the pack is being fetched and decoded.
  static bool get loading => _loading != null && _loaded == null && _error == null;

  static McPackFailure? get failure {
    final Object? error = _error;
    if (error == null) return null;
    return error is McPackHttpError && error.status == 404 ? McPackFailure.missing : McPackFailure.network;
  }

  /// Forgets a failed load and starts again.
  static Future<McAssetPackData> retry() {
    if (_error != null) {
      _error = null;
      _loading = null;
      notify();
    }
    return load();
  }
  static final List<void Function()> _listeners = <void Function()>[];

  static final Map<String, String> _runtime = <String, String>{};

  static McAssetPackData? get loaded => _loaded;

  /// Names a runtime image (a loaded skin) for every stage, before or after
  /// the pack arrives.
  static void registerImage(String id, String url) {
    _runtime[id] = url;
    _loaded?.register(id, url);
    notify();
  }
  static bool get failed => _error != null;
  static Object? get error => _error;

  static void addListener(void Function() listener) => _listeners.add(listener);
  static void removeListener(void Function() listener) => _listeners.remove(listener);

  static Future<McAssetPackData> load() => _loading ??= _load().then(
        (McAssetPackData data) {
          _runtime.forEach(data.register);
          _loaded = data;
          notify();
          return data;
        },
        onError: (Object error) {
          _error = error;
          notify();
          throw error;
        },
      );

  static void notify() {
    for (final void Function() listener in List<void Function()>.of(_listeners)) {
      listener();
    }
  }

  static Future<McAssetPackData> _load() async {
    final String base = mcPackBaseUrl(version);
    final McPackManifest manifest = McPackManifest.fromJson(await _json('$base/pack.json'));
    final McJsonModelStore store = McJsonModelStore.fromJson(await _json('$base/${manifest.modelsPath}'));
    final web.HTMLImageElement blocks = await mcDecodeImage('$base/${manifest.blocks.image}');
    final web.HTMLImageElement items = await mcDecodeImage('$base/${manifest.items.image}');
    return McAssetPackData._(manifest, McModelResolver(store), blocks, items, _alphaOf(blocks), _alphaOf(items));
  }

  static Future<Map<String, Object?>> _json(String url) async {
    final web.Response response = await web.window.fetch(url.toJS).toDart;
    if (!response.ok) throw McPackHttpError(url, response.status);
    final String text = (await response.text().toDart).toDart;
    return jsonDecode(text) as Map<String, Object?>;
  }

  static Uint8List _alphaOf(web.HTMLImageElement image) {
    final web.HTMLCanvasElement canvas = web.HTMLCanvasElement()
      ..width = image.naturalWidth
      ..height = image.naturalHeight;
    // Read once on the CPU: a GPU canvas would stall on the readback.
    final web.CanvasRenderingContext2D ctx =
        canvas.getContext('2d', <String, Object?>{'willReadFrequently': true}.jsify()) as web.CanvasRenderingContext2D;
    ctx.drawImage(image, 0, 0);
    final web.ImageData data = ctx.getImageData(0, 0, canvas.width, canvas.height);
    final Uint8ClampedList raw = data.data.toDart;
    final Uint8List alpha = Uint8List(canvas.width * canvas.height);
    for (int i = 0; i < alpha.length; i++) {
      alpha[i] = raw[i * 4 + 3];
    }
    return alpha;
  }
}

Future<web.HTMLImageElement> mcDecodeImage(String url) async {
  final web.HTMLImageElement element = web.HTMLImageElement()
    ..decoding = 'async'
    ..src = url;
  await element.decode().toDart;
  return element;
}
