/// Skin PNGs the user loads into the preview (from a file or a URL), kept for
/// this page session and keyed by skin hash, or by NPC when the definition
/// names no skin. Signed skins reach the browser through the Animus server
/// once connected; until then this is how a preview wears one.
library;

import 'dart:async';
import 'dart:js_interop';

import 'package:web/web.dart' as web;

import '../components/mc/mc_asset_loader.dart';

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
    final web.Blob blob = await response.blob().toDart;
    final String url = web.URL.createObjectURL(blob);
    try {
      return await _keep(key, url, uri.toString());
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
