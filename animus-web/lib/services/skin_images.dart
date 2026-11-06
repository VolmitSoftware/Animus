/// Skin PNGs the user loads into the preview (from a file or a URL), kept for
/// this page session and keyed by skin hash, or by NPC when the definition
/// names no skin. Signed skins also load on their own from their texture URL
/// (see [SkinImages.textures]).
library;

import 'dart:async';
import 'dart:convert';
import 'dart:js_interop';
import 'dart:typed_data';

import 'package:web/web.dart' as web;

import '../components/mc/mc_asset_loader.dart';
import 'skin_textures.dart';

final class LoadedSkin {
  const LoadedSkin({required this.textureId, required this.source, required this.dataUrl});

  /// The runtime image id stages bind.
  final String textureId;

  /// Where it came from, for the panel's readout.
  final String source;

  /// The 64x64 PNG, for face thumbnails.
  final String dataUrl;
}

final class SkinImages {
  SkinImages._();

  static final Map<String, LoadedSkin> _loaded = <String, LoadedSkin>{};

  static LoadedSkin? forKey(String key) => _loaded[key];

  /// The skin under [key], registering [png] (a 64x64 PNG the workspace
  /// keeps, see `skinImagePath`) the first time it is asked for.
  static LoadedSkin? forKeyOrWorkspace(String key, Uint8List? png) {
    final LoadedSkin? loaded = _loaded[key];
    if (loaded != null || png == null || png.isEmpty) return loaded;
    final String id = 'skin:$key';
    final String url = 'data:image/png;base64,${base64Encode(png)}';
    McAssetLoader.registerImage(id, url);
    return _loaded[key] = LoadedSkin(textureId: id, source: 'the PNG kept in this workspace', dataUrl: url);
  }

  /// Keeps [skin] under another key too (a skin made before its hash was
  /// known).
  static void alias(String key, LoadedSkin skin) => _loaded[key] = skin;

  /// The PNG bytes of a loaded skin.
  static Uint8List pngBytes(LoadedSkin skin) => base64Decode(skin.dataUrl.substring(skin.dataUrl.indexOf(',') + 1));

  /// Lets the user pick a PNG and keeps it under [key]. Null when cancelled.
  static Future<LoadedSkin?> pickFile(String key) async {
    final web.File? file = await _pickPng();
    if (file == null) return null;
    final String url = web.URL.createObjectURL(file);
    try {
      return await _keep(key, url, file.name);
    } finally {
      web.URL.revokeObjectURL(url);
    }
  }

  /// Fetches a PNG from [address] (it must allow cross-origin reads) and keeps
  /// it under [key].
  static Future<LoadedSkin> fetchUrl(String key, String address) async {
    final Uri uri = Uri.parse(address.trim());
    if (uri.scheme != 'https' && uri.scheme != 'http') throw const FormatException('Use an http or https address.');
    final web.Response response = await web.window.fetch(uri.toString().toJS).toDart;
    if (!response.ok) throw StateError('HTTP ${response.status}');
    return _keepBlob(key, await response.blob().toDart, uri.toString());
  }

  /// Signed skins' textures, fetched from their documents' texture URLs and
  /// kept under the skin hash.
  static final SkinTextures textures = SkinTextures((String hash, Uri address) async {
    final web.Response response = await web.window.fetch(address.toString().toJS).toDart;
    if (!response.ok) throw SkinTextureRefused(response.status);
    await _keepBlob(hash, await response.blob().toDart, 'textures.minecraft.net');
  });

  static Future<LoadedSkin> _keepBlob(String key, web.Blob blob, String source) async {
    final String url = web.URL.createObjectURL(blob);
    try {
      return await _keep(key, url, source);
    } finally {
      web.URL.revokeObjectURL(url);
    }
  }

  /// Decodes [url], checks it is a 64x64 or legacy 64x32 skin, widens a
  /// legacy skin to 64x64 the way the client does, and registers it.
  static Future<LoadedSkin> _keep(String key, String url, String source) async {
    final web.HTMLImageElement image = await mcDecodeImage(url);
    final int w = image.naturalWidth, h = image.naturalHeight;
    if (w != 64 || (h != 64 && h != 32)) throw FormatException('A skin is 64x64 or 64x32 pixels; this image is ${w}x$h.');
    final web.HTMLCanvasElement canvas = web.HTMLCanvasElement()
      ..width = 64
      ..height = 64;
    final web.CanvasRenderingContext2D ctx = canvas.getContext('2d')! as web.CanvasRenderingContext2D;
    ctx.imageSmoothingEnabled = false;
    ctx.drawImage(image, 0, 0);
    if (h == 32) _widenLegacy(ctx);
    final String id = 'skin:$key';
    final String png = canvas.toDataURL('image/png');
    McAssetLoader.registerImage(id, png);
    final LoadedSkin skin = LoadedSkin(textureId: id, source: source, dataUrl: png);
    _loaded[key] = skin;
    return skin;
  }

  /// `SkinTextureDownloader.processLegacySkin`: the left leg and arm copy the
  /// right ones, each face mirrored.
  static void _widenLegacy(web.CanvasRenderingContext2D ctx) {
    void copy(int x, int y, int dx, int dy, int w, int h) {
      ctx.save();
      ctx.translate(x + dx + w, (y + dy).toDouble());
      ctx.scale(-1, 1);
      ctx.drawImage(ctx.canvas, x, y, w, h, 0, 0, w, h);
      ctx.restore();
    }

    copy(4, 16, 16, 32, 4, 4);
    copy(8, 16, 16, 32, 4, 4);
    copy(0, 20, 24, 32, 4, 12);
    copy(4, 20, 16, 32, 4, 12);
    copy(8, 20, 8, 32, 4, 12);
    copy(12, 20, 16, 32, 4, 12);
    copy(44, 16, -8, 32, 4, 4);
    copy(48, 16, -8, 32, 4, 4);
    copy(40, 20, 0, 32, 4, 12);
    copy(44, 20, -8, 32, 4, 12);
    copy(48, 20, -16, 32, 4, 12);
    copy(52, 20, -8, 32, 4, 12);
  }

  static Future<web.File?> _pickPng() {
    final Completer<web.File?> completer = Completer<web.File?>();
    final web.HTMLInputElement input = web.document.createElement('input') as web.HTMLInputElement;
    input.type = 'file';
    input.accept = 'image/png';
    input.style.display = 'none';
    input.addEventListener('change', ((web.Event _) {
      final web.File? file = input.files?.item(0);
      input.remove();
      if (!completer.isCompleted) completer.complete(file);
    }).toJS);
    input.addEventListener('cancel', ((web.Event _) {
      input.remove();
      if (!completer.isCompleted) completer.complete(null);
    }).toJS);
    web.document.body?.append(input);
    input.click();
    return completer.future;
  }
}
