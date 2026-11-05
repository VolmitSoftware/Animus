/// Inventory icons for the chest and shop windows: each item model meshed by
/// the stage's renderer at its `gui` display pose, drawn once into a small
/// offscreen WebGL2 canvas with an orthographic camera and kept as a PNG data
/// URL. Items with no JSON geometry use their atlas sprite. One context is
/// shared by every window.
library;

import 'dart:js_interop';

import 'package:web/web.dart' as web;

import '../../mc/assets/mc_display_pose.dart';
import '../../mc/assets/mc_model_resolver.dart';
import '../../mc/mesh/mc_mesh.dart';
import '../../mc/scene/mc_math.dart';
import '../../mc/scene/mc_scene.dart';
import 'mc_asset_loader.dart';
import 'mc_gl.dart';
import 'mc_gl_renderer.dart';

final class McItemIcons {
  McItemIcons._();

  static const int sizePx = 64;

  static final Map<String, String?> _urls = <String, String?>{};
  static final Set<String> _queued = <String>{};
  static final List<void Function()> _listeners = <void Function()>[];
  static McGlRenderer? _renderer;
  static web.HTMLCanvasElement? _canvas;
  static bool _unavailable = false;
  static bool _scheduled = false;
  static bool _listening = false;

  static void addListener(void Function() listener) => _listeners.add(listener);

  static void removeListener(void Function() listener) => _listeners.remove(listener);

  /// The bare item key (`diamond_sword`) of an icon's `item` text, or null
  /// when the text needs the server (an expression) or is not an item id.
  static String? keyOf(String? item) {
    if (item == null || item.contains('{{')) return null;
    final String trimmed = item.trim().toLowerCase();
    final int colon = trimmed.indexOf(':');
    if (colon >= 0 && trimmed.substring(0, colon) != 'minecraft') return null;
    final String key = trimmed.substring(colon + 1);
    return RegExp(r'^[a-z0-9_./-]+$').hasMatch(key) ? key : null;
  }

  /// The icon URL for [key], or null until it is drawn (or when it cannot be).
  static String? url(String key) {
    if (_urls.containsKey(key)) return _urls[key];
    if (_unavailable || McAssetLoader.failed) return null;
    _queued.add(key);
    _schedule();
    return null;
  }

  /// True once [key] was tried and has no icon.
  static bool missing(String key) => (_urls.containsKey(key) && _urls[key] == null) || _unavailable || McAssetLoader.failed;

  static void _schedule() {
    if (_scheduled) return;
    _scheduled = true;
    if (!_listening) {
      _listening = true;
      McAssetLoader.addListener(_schedule);
    }
    McAssetLoader.load().then((_) {}, onError: (Object _) {});
    web.window.requestAnimationFrame(((JSNumber _) {
      _scheduled = false;
      _drain();
    }).toJS);
  }

  static void _drain() {
    final McAssetPackData? pack = McAssetLoader.loaded;
    if (pack == null) {
      if (McAssetLoader.failed) _notify();
      return;
    }
    final McGlRenderer? renderer = _rendererFor(pack);
    bool changed = false;
    bool waiting = false;
    for (final String key in _queued.toList()) {
      final String? drawn = renderer == null ? null : _draw(renderer, pack, key);
      if (drawn == _pending) {
        waiting = true;
        continue;
      }
      _queued.remove(key);
      _urls[key] = drawn ?? _sprite(pack, key);
      changed = true;
    }
    if (changed) _notify();
    if (waiting) _schedule();
  }

  static const String _pending = 'pending';

  static McGlRenderer? _rendererFor(McAssetPackData pack) {
    if (_renderer != null || _unavailable) return _renderer;
    final web.HTMLCanvasElement canvas = _canvas = web.HTMLCanvasElement()
      ..width = sizePx
      ..height = sizePx;
    final McGl? gl = McGl.create(canvas);
    if (gl == null) {
      _unavailable = true;
      return null;
    }
    return _renderer = McGlRenderer(gl, pack);
  }

  /// A data URL, null for an item the renderer cannot mesh, or [_pending].
  static String? _draw(McGlRenderer renderer, McAssetPackData pack, String key) {
    final McItemModelResult result = pack.resolver.resolveItem(key);
    if (result is! McItemModelResolved) return null;
    final McItemNode node = McItemNode(key: 'icon', itemId: key, pose: McDisplayPose.gui, transform: McMat4.identity());
    final bool drawn;
    try {
      drawn = renderer.renderIcon(node, sizePx: sizePx);
    } on Object {
      return null;
    }
    if (!drawn) return _pending;
    return _canvas!.toDataURL('image/png');
  }

  /// The item atlas sprite `item/<key>`, cropped to a canvas.
  static String? _sprite(McAssetPackData pack, String key) {
    final McUvRect? uv = pack.manifest.items.uv('item/$key');
    if (uv == null) return null;
    final int width = pack.manifest.items.width, height = pack.manifest.items.height;
    final web.HTMLCanvasElement canvas = web.HTMLCanvasElement()
      ..width = 16
      ..height = 16;
    final web.CanvasRenderingContext2D ctx = canvas.getContext('2d')! as web.CanvasRenderingContext2D;
    ctx.imageSmoothingEnabled = false;
    ctx.drawImage(
      pack.itemsImage,
      uv.u0 * width,
      uv.v0 * height,
      (uv.u1 - uv.u0) * width,
      (uv.v1 - uv.v0) * height,
      0,
      0,
      16,
      16,
    );
    return canvas.toDataURL('image/png');
  }

  static void _notify() {
    for (final void Function() listener in List<void Function()>.of(_listeners)) {
      listener();
    }
  }
}
