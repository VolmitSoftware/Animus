/// The 3D stage: one canvas the renderer draws into, a DOM layer for the
/// text the client draws in world space (names and text displays), the
/// pointer, wheel, touch and keyboard input that drives the orbit camera,
/// and the stage's own states drawn in its box: a skeleton while the model
/// pack loads, an alert with Retry when WebGL or the pack fails. What it
/// cannot draw is kept on the controller ([McStageController.notes]) for
/// the frame's status line ([McStageFrame]).
///
/// Jaspr renders the wrapper, the text layer and the overlays; the canvas is
/// created and owned imperatively under an element Jaspr renders empty and
/// never rebuilds. Frames are dirty-gated: one is drawn after a controller
/// change, a resize, the pack or a texture arriving, or a restored context;
/// an idle stage draws nothing. Adapted from Gloss Web's `McStage`.
library;

import 'dart:async';
import 'dart:js_interop';
import 'dart:js_interop_unsafe';
import 'dart:math' as math;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show ChangeNotifier, Component;
import 'package:web/web.dart' as web;

import '../../logic/held_notes.dart';
import '../../logic/item_ids.dart' show itemName;
import '../../mc/assets/mc_ids.dart';
import '../../mc/npc/mc_text.dart';
import '../../mc/npc/npc_scene.dart';
import '../../mc/scene/mc_camera.dart';
import '../../mc/scene/mc_math.dart';
import '../../mc/scene/mc_occlusion.dart';
import '../../mc/scene/mc_scene.dart';
import '../../ui/alert.dart';
import '../../ui/skeleton.dart';
import 'mc_asset_loader.dart';
import 'mc_gl.dart';
import 'mc_gl_renderer.dart';
import '../shortcut_sheet.dart' show platformKeys;
import '../typing_pause.dart' show typingPause;
import '../../logic/gui/gui_text.dart';

/// Pixels of drag one arrow key press stands for.
const double mcStageKeyStepPx = 24;

/// What the stage draws and from where.
final class McStageController extends ChangeNotifier {
  McStageController();

  McScene _scene = McScene.empty;
  List<NpcText> _texts = const <NpcText>[];
  McCamera _camera = mcCameraFraming(const McBounds(McVec3(-0.5, 0, -0.5), McVec3(0.5, 1.8, 0.5)).corners);
  McCamera _home = mcCameraFraming(const McBounds(McVec3(-0.5, 0, -0.5), McVec3(0.5, 1.8, 0.5)).corners);

  /// Frames rendered so far; the browser check reads `data-frames`,
  /// `data-drawn` for the meshes the last frame drew, `data-culled` for the
  /// neighbours it left out and `data-subject` for where the subject is.
  int frames = 0;

  /// Nodes the last frame could not draw yet (a texture still decoding).
  int unbuilt = 0;

  bool _webGlAvailable = true;
  List<String> _notes = const <String>[];

  McScene get scene => _scene;

  /// What the last frames could not draw (a texture that failed, an item
  /// with no model), settled like field problems; the frame's status line
  /// counts them and its tip lists them.
  List<String> get notes => _notes;

  set notes(List<String> value) {
    if (value.join('\n') == _notes.join('\n')) return;
    _notes = value;
    notifyListeners();
  }
  List<NpcText> get texts => _texts;
  McCamera get camera => _camera;
  McCamera get home => _home;
  bool get webGlAvailable => _webGlAvailable;

  set webGlAvailable(bool value) {
    if (value == _webGlAvailable) return;
    _webGlAvailable = value;
    notifyListeners();
  }

  set camera(McCamera value) {
    if (value == _camera) return;
    _camera = value;
    notifyListeners();
  }

  List<McVec3> _frame = const <McVec3>[];

  List<McVec3> _focus = const <McVec3>[];

  /// The subject's own points (its body and the text on it), which other
  /// NPCs' names keep clear of; the framed points unless [show] was given
  /// others.
  List<McVec3> get focus => _focus;
  double _yawDeg = 0;
  /// Zero until the stage is measured; the framing assumes 4:3 till then.
  double _widthPx = 0;
  double _heightPx = 0;

  McBounds? _reach;

  Map<String, McOccluder> _occluders = const <String, McOccluder>{};
  McBounds? _subject;
  McBounds? _plates;
  Set<McOccluder> _bodies = const <McOccluder>{};

  /// Bodies near the subject by their nodes' keys; the ones in the way are
  /// left out ([culled]).
  Map<String, McOccluder> get occluders => _occluders;

  /// Shows [scene]; [frame] moves the home framing to the new subject (and
  /// the camera with it when the camera was home). [reach] is where the
  /// camera may pan, around the origin by default. [focus] is the subject
  /// when the framed points hold more (a route). [occluders] are the
  /// neighbours' boxes by node key.
  void show(McScene scene, List<NpcText> texts, {required double yawDeg, bool frame = false, McBounds? reach, List<McVec3>? focus, Map<String, McOccluder> occluders = const <String, McOccluder>{}}) {
    _scene = scene;
    _texts = texts;
    _frame = scene.frame;
    _focus = focus ?? scene.frame;
    _subject = _focus.isEmpty ? null : McBounds.of(_focus);
    _plates = npcPlates(texts);
    if (!identical(occluders, _occluders)) _bodies = occluders.values.toSet();
    _occluders = occluders;
    _yawDeg = yawDeg;
    final bool home = _camera == _home;
    if (reach?.min != _reach?.min || reach?.max != _reach?.max) {
      _reach = reach;
      _camera = _camera.within(reach);
    }
    _reframe(force: frame || home);
    notifyListeners();
  }

  /// The stage's size in CSS pixels; a home camera refits to it.
  void resize(double widthPx, double heightPx) {
    if (!(widthPx > 0) || !(heightPx > 0) || (widthPx == _widthPx && heightPx == _heightPx)) return;
    _widthPx = widthPx;
    _heightPx = heightPx;
    _reframe();
    notifyListeners();
  }

  void _reframe({bool force = false}) {
    if (_frame.isEmpty) return;
    final McCamera home = mcHomeCamera(_frame, _texts, yawDeg: _yawDeg, widthPx: _widthPx, heightPx: _heightPx, reach: _reach);
    if (force || _camera == _home) _camera = home;
    _home = home;
  }

  /// The neighbours the stage leaves out for [camera] in a [widthPx] x
  /// [heightPx] view ([mcCulled]); their names go with them.
  Set<McOccluder> culled(McCamera camera, double widthPx, double heightPx) =>
      _subject == null || _bodies.isEmpty ? const <McOccluder>{} : mcCulled(camera, _subject!, _bodies, widthPx: widthPx, heightPx: heightPx, plates: _plates);

  void resetCamera() => camera = _home;
}

/// Widgets drawn over the stage in pixel space, rebuilt with the camera.
/// Elements under `[data-gizmo]` take their own pointer input; the orbit
/// camera ignores presses on them.
typedef McStageOverlay = List<Widget> Function(McCamera camera, double widthPx, double heightPx);

/// A line for a texture the renderer could not decode.
String mcTextureNote(String id, {required bool runtime, required bool inPack}) => runtime
    ? 'An image failed to load and is drawn white. Load the PNG again.'
    : inPack
        ? 'Texture $id failed to load and is drawn white. Reload to retry.'
        : 'Texture $id is not in the asset pack and is drawn white. Extract the pack again from a 26.3 client jar.';

/// A line for an item the stage has no model for, from the resolver's reason.
String mcItemNote(String item, String reason) {
  final String name = itemName(item.contains(':') ? item : 'minecraft:${mcStrip(item)}');
  if (reason.startsWith('special renderer')) {
    return '$name is not drawn: the game draws it with its own renderer (shields, tridents, skulls, banners, chests and beds work this way), which the preview does not have.';
  }
  if (reason.startsWith('no item definition')) return '$name is not drawn: Minecraft 26.3 has no such item.';
  return '$name is not drawn: it has no model the preview can draw.';
}

/// What the keys and pointer do on a stage, for its description and tips.
String mcStageControls() =>
    'Drag to orbit, Shift or right drag to pan, ${platformKeys('Mod')} and the wheel to zoom. Focused: arrows orbit, Shift and arrows pan, + and - zoom, 0 resets.';

class McStage extends StatefulWidget {
  const McStage({required this.controller, this.label = 'NPC preview', this.overlay, this.onPick, this.attributes = const <String, String>{}, super.key});

  final McStageController controller;
  final String label;
  final McStageOverlay? overlay;

  /// A click on the stage that did not orbit, at stage pixels.
  final void Function(double x, double y)? onPick;

  /// Extra attributes on the stage element (`data-*` for checks).
  final Map<String, String> attributes;

  @override
  State<McStage> createState() => _McStageState();
}

class _McStageState extends State<McStage> {
  static int _instances = 0;
  late final String _id = 'animus-mc-stage-${_instances++}';
  late final String _canvasHostId = '$_id-gl';

  web.HTMLElement? _host;
  web.HTMLElement? _stage;
  McGl? _gl;
  McGlRenderer? _renderer;
  web.ResizeObserver? _resize;
  double _widthPx = 0;
  double _heightPx = 0;
  bool _framePending = false;
  bool _dirty = true;
  bool _postFramePending = false;
  bool _disposed = false;
  bool _lost = false;
  /// The renderer's latest notes; new ones reach the controller after a
  /// pause.
  List<String> _latestNotes = const <String>[];
  Timer? _notesPause;

  bool _dragging = false;
  bool _panning = false;
  double _downX = 0;
  double _downY = 0;
  bool _moved = false;
  int? _pointerId;
  double _lastX = 0;
  double _lastY = 0;
  bool _pinching = false;
  double _pinchDistance = 0;
  double _pinchX = 0;
  double _pinchY = 0;
  final Map<String, JSFunction> _listeners = <String, JSFunction>{};

  McStageController get _c => component.controller;

  @override
  void initState() {
    super.initState();
    _c.addListener(_onController);
    McAssetLoader.addListener(_onPack);
    unawaited(McAssetLoader.load().then<void>((McAssetPackData _) {}, onError: (Object _) {}));
    _schedulePostFrame();
  }

  @override
  void didUpdateWidget(McStage oldComponent) {
    super.didUpdateWidget(oldComponent);
    if (!identical(oldComponent.controller, component.controller)) {
      oldComponent.controller.removeListener(_onController);
      component.controller.addListener(_onController);
      _invalidate();
    }
  }

  @override
  void dispose() {
    _disposed = true;
    _notesPause?.cancel();
    _c.removeListener(_onController);
    McAssetLoader.removeListener(_onPack);
    _resize?.disconnect();
    _detachInput();
    _gl?.dispose();
    super.dispose();
  }

  void _onController() {
    if (_disposed) return;
    _invalidate();
    setState(() {});
  }

  void _onPack() {
    if (_disposed) return;
    _attachGl();
    _invalidate();
    setState(() {});
  }

  void _retry() {
    unawaited(McAssetLoader.retry().then<void>((McAssetPackData _) {}, onError: (Object _) {}));
  }

  void _schedulePostFrame() {
    if (_postFramePending || _disposed) return;
    _postFramePending = true;
    context.binding.addPostFrameCallback(() {
      _postFramePending = false;
      if (_disposed) return;
      _attachToDom();
      _placeTexts();
    });
  }

  /// The subject's box on the page, from the points the camera frames, or
  /// null when none is in front of the camera.
  (double, double, double, double)? _subjectBox(double pageLeft, double pageTop) {
    double minX = double.infinity, minY = double.infinity, maxX = -double.infinity, maxY = -double.infinity;
    for (final McVec3 point in _c.focus) {
      final McVec3? at = mcProjectToPixels(_c.camera, point, widthPx: _widthPx, heightPx: _heightPx);
      if (at == null) continue;
      minX = math.min(minX, at.x);
      maxX = math.max(maxX, at.x);
      minY = math.min(minY, at.y);
      maxY = math.max(maxY, at.y);
    }
    if (minX > maxX) return null;
    return (pageLeft + minX, pageTop + minY, maxX - minX, maxY - minY);
  }

  /// Lays the text layer out in the stage's safe area, which only the
  /// browser can measure: each block is snapped to whole pixels (crisp
  /// glyphs) and shifted back inside the stage. The subject's texts go
  /// first, nearest first, each moved up past a block already placed. Editor
  /// labels ([NpcText.yields]: other NPCs' names, route numbers) go last and
  /// never move: one that would cover or touch a placed block (it would read
  /// as one more line of it), or cover the subject's body, is hidden.
  /// `translate` leaves the projected `left`, `top` and `transform` alone.
  void _placeTexts() {
    final web.Element? stage = web.document.getElementById(_id);
    if (stage == null) return;
    final web.DOMRect frame = stage.getBoundingClientRect();
    if (frame.width <= 0 || frame.height <= 0) return;
    final web.NodeList found = stage.querySelectorAll('[data-text]');
    final List<web.HTMLElement> texts = <web.HTMLElement>[
      for (int index = 0; index < found.length; index++) found.item(index)! as web.HTMLElement,
    ];
    int rank(web.HTMLElement text) => text.hasAttribute('data-placed') ? -1 : (text.hasAttribute('data-yields') ? 1 : 0);
    int depth(web.HTMLElement text) => int.tryParse(text.style.getPropertyValue('z-index')) ?? 0;
    texts.sort((web.HTMLElement a, web.HTMLElement b) {
      final int byRank = rank(a).compareTo(rank(b));
      return byRank != 0 ? byRank : depth(b).compareTo(depth(a));
    });
    final List<(double, double, double, double)> placed = <(double, double, double, double)>[];
    final (double, double, double, double)? subject = _subjectBox(frame.left, frame.top);
    final double minX = frame.left + mcTextInsetPx, maxX = frame.right - mcTextInsetPx;
    final double minY = frame.top + mcTextInsetPx, maxY = frame.bottom - mcTextInsetPx;
    for (final web.HTMLElement text in texts) {
      text.style.removeProperty('translate');
      text.style.removeProperty('visibility');
      final web.DOMRect box = text.getBoundingClientRect();
      if (rank(text) < 0) {
        placed.add((box.left, box.top, box.width, box.height));
        continue;
      }
      double left = box.left, top = box.top;
      final double width = box.width, height = box.height;
      left = left < minX ? minX : (left + width > maxX ? maxX - width : left);
      top = top < minY ? minY : (top + height > maxY ? maxY - height : top);
      bool covers((double, double, double, double) other) =>
          left < other.$1 + other.$3 && other.$1 < left + width && top < other.$2 + other.$4 && other.$2 < top + height;
      if (rank(text) > 0) {
        final double gap = height / 2;
        bool touches((double, double, double, double) other) =>
            left < other.$1 + other.$3 + gap && other.$1 - gap < left + width && top < other.$2 + other.$4 + gap && other.$2 - gap < top + height;
        if (placed.any(touches) || (subject != null && covers(subject))) {
          text.style.setProperty('visibility', 'hidden');
          continue;
        }
      } else {
        for (int tries = 0; tries < 8; tries++) {
          final (double, double, double, double)? hit = placed.where(covers).firstOrNull;
          if (hit == null) break;
          top = hit.$2 - height - 2;
        }
        top = math.max(minY, top);
      }
      placed.add((left, top, width, height));
      final double dx = left.roundToDouble() - box.left, dy = top.roundToDouble() - box.top;
      if (dx.abs() >= 0.05 || dy.abs() >= 0.05) text.style.setProperty('translate', '${dx.toStringAsFixed(2)}px ${dy.toStringAsFixed(2)}px');
    }
  }

  void _attachToDom() {
    final web.Element? host = web.document.getElementById(_canvasHostId);
    if (host == null || identical(_host, host)) return;
    _host = host as web.HTMLElement;
    final web.HTMLCanvasElement canvas = web.HTMLCanvasElement()..className = 'animus-mc-gl';
    host.append(canvas);
    McGl? gl;
    try {
      gl = McGl.create(canvas);
    } on Object catch (error) {
      web.console.warn('mc stage: $error'.toJS);
      gl = null;
    }
    if (gl == null) {
      _c.webGlAvailable = false;
    } else {
      _gl = gl;
      gl.onLost(() {
        if (!_disposed) setState(() => _lost = true);
      });
      gl.onRestored(() {
        if (_disposed) return;
        setState(() => _lost = false);
        _invalidate();
      });
    }
    _attachGl();
    final web.HTMLElement stage = _stage = web.document.getElementById(_id)! as web.HTMLElement;
    _observe(stage);
    _attachInput(stage);
    _requestFrame();
  }

  void _attachGl() {
    final McGl? gl = _gl;
    final McAssetPackData? pack = McAssetLoader.loaded;
    if (gl == null || pack == null || _renderer != null) return;
    try {
      _renderer = McGlRenderer(gl, pack);
    } on Object catch (error) {
      web.console.warn('mc renderer: $error'.toJS);
      _c.webGlAvailable = false;
    }
  }

  void _observe(web.HTMLElement stage) {
    _resize?.disconnect();
    _resize = web.ResizeObserver(((JSAny _, JSAny _) => _measure(stage)).toJS)..observe(stage);
    _measure(stage);
  }

  void _measure(web.HTMLElement stage) {
    if (_disposed) return;
    final web.DOMRect rect = stage.getBoundingClientRect();
    if (rect.width == _widthPx && rect.height == _heightPx) return;
    setState(() {
      _widthPx = rect.width;
      _heightPx = rect.height;
    });
    _c.resize(rect.width, rect.height);
    _invalidate();
  }

  void _invalidate() {
    _dirty = true;
    _requestFrame();
  }

  void _requestFrame() {
    if (_framePending || _disposed) return;
    _framePending = true;
    web.window.requestAnimationFrame(
      ((JSNumber _) {
        _framePending = false;
        if (_disposed || !_dirty) return;
        _dirty = false;
        _render();
      }).toJS,
    );
  }

  void _render() {
    final McGlRenderer? renderer = _renderer;
    if (renderer == null || _widthPx <= 0 || _heightPx <= 0) return;
    final Set<McOccluder> culled = _c.culled(_c.camera, _widthPx, _heightPx);
    _c.unbuilt = renderer.render(_c.scene, _c.camera, widthPx: _widthPx, heightPx: _heightPx, dpr: web.window.devicePixelRatio, occluders: _c.occluders, culled: culled);
    _c.frames++;
    final (double, double, double, double)? subject = _subjectBox(0, 0);
    _host?.setAttribute('data-frames', '${_c.frames}');
    _host?.setAttribute('data-unbuilt', '${_c.unbuilt}');
    _host?.setAttribute('data-drawn', '${renderer.drawn}');
    _host?.setAttribute('data-culled', '${culled.length}');
    _host?.setAttribute('data-subject', subject == null ? '' : <double>[subject.$1, subject.$2, subject.$1 + subject.$3, subject.$2 + subject.$4].map((double v) => v.round()).join(' '));
    final McAssetPackData? pack = McAssetLoader.loaded;
    final List<String> notes = <String>[
      for (final String id in renderer.failedTextures) mcTextureNote(id, runtime: pack?.runtime(id) ?? false, inPack: pack?.manifest.entityTextures.containsKey(id) ?? false),
      for (final MapEntry<String, String> item in renderer.undrawable.entries) mcItemNote(item.key, item.value),
    ];
    if (notes.join('\n') == _latestNotes.join('\n')) return;
    _latestNotes = notes;
    _notesPause?.cancel();
    final List<String> kept = keptNotes(_c.notes, notes);
    _c.notes = kept;
    if (kept.length < notes.length) {
      _notesPause = Timer(typingPause, () {
        if (!_disposed) _c.notes = _latestNotes;
      });
    }
  }

  // --- input ---------------------------------------------------------------

  bool get _focused => _stage != null && identical(web.document.activeElement, _stage);

  void _attachInput(web.HTMLElement stage) {
    _detachInput();
    void on(String type, void Function(web.Event) handler) {
      final JSFunction fn = handler.toJS;
      _listeners[type] = fn;
      stage.addEventListener(type, fn, <String, Object?>{'passive': false}.jsify()! as JSObject);
    }

    on('pointerdown', (web.Event e) {
      final web.PointerEvent p = e as web.PointerEvent;
      if (_pointerId != null) return;
      final web.EventTarget? target = e.target;
      if (target != null && target.isA<web.Element>() && (target as web.Element).closest('[data-gizmo], .animus-mc-state') != null) return;
      _pointerId = p.pointerId;
      _lastX = _downX = _num(p, 'clientX');
      _lastY = _downY = _num(p, 'clientY');
      _moved = false;
      stage.setPointerCapture(p.pointerId);
      stage.classList.add('is-orbiting');
      _dragging = true;
      _panning = p.button == 1 || p.button == 2 || p.shiftKey;
    });
    on('pointermove', (web.Event e) {
      final web.PointerEvent p = e as web.PointerEvent;
      if (!_dragging || p.pointerId != _pointerId) return;
      final double x = _num(p, 'clientX'), y = _num(p, 'clientY');
      final double dx = x - _lastX, dy = y - _lastY;
      _lastX = x;
      _lastY = y;
      if ((x - _downX).abs() + (y - _downY).abs() > 4) _moved = true;
      if (_pinching) return;
      _c.camera = _panning ? _c.camera.panBy(dx, dy, _heightPx) : _c.camera.orbitBy(dx, dy);
    });
    void release(web.Event e) {
      if (!_dragging || (e as web.PointerEvent).pointerId != _pointerId) return;
      final void Function(double, double)? pick = component.onPick;
      if (pick != null && !_moved && !_pinching && e.type == 'pointerup' && e.button == 0) {
        final web.DOMRect box = stage.getBoundingClientRect();
        pick(_num(e, 'clientX') - box.left, _num(e, 'clientY') - box.top);
      }
      _pointerId = null;
      _dragging = false;
      _panning = false;
      stage.classList.remove('is-orbiting');
    }

    on('pointerup', release);
    on('pointercancel', release);
    on('contextmenu', (web.Event e) => e.preventDefault());
    // The wheel zooms only with Ctrl (or a pinch on a trackpad) or once the
    // stage has focus, so scrolling the page past the stage still scrolls.
    on('wheel', (web.Event e) {
      final web.WheelEvent w = e as web.WheelEvent;
      if (!w.ctrlKey && !w.metaKey && !_focused) return;
      e.preventDefault();
      _c.camera = _c.camera.dollyBy(mcWheelPixels(_num(w, 'deltaY'), w.deltaMode));
    });
    on('keydown', (web.Event e) {
      final web.KeyboardEvent k = e as web.KeyboardEvent;
      if (!identical(k.target, stage)) return;
      final McCamera camera = _c.camera;
      final McCamera? next = switch (k.key) {
        'ArrowLeft' => k.shiftKey ? camera.panBy(-mcStageKeyStepPx, 0, _heightPx) : camera.orbitBy(-mcStageKeyStepPx, 0),
        'ArrowRight' => k.shiftKey ? camera.panBy(mcStageKeyStepPx, 0, _heightPx) : camera.orbitBy(mcStageKeyStepPx, 0),
        'ArrowUp' => k.shiftKey ? camera.panBy(0, -mcStageKeyStepPx, _heightPx) : camera.orbitBy(0, -mcStageKeyStepPx),
        'ArrowDown' => k.shiftKey ? camera.panBy(0, mcStageKeyStepPx, _heightPx) : camera.orbitBy(0, mcStageKeyStepPx),
        '+' || '=' => camera.dollyBy(-120),
        '-' || '_' => camera.dollyBy(120),
        '0' => _c.home,
        _ => null,
      };
      if (next == null) return;
      e.preventDefault();
      _c.camera = next;
    });
    on('touchstart', (web.Event e) {
      final web.TouchEvent t = e as web.TouchEvent;
      if (t.touches.length != 2) return;
      e.preventDefault();
      _pinching = true;
      _pinchDistance = _touchDistance(t);
      final (double x, double y) = _touchCentroid(t);
      _pinchX = x;
      _pinchY = y;
    });
    on('touchmove', (web.Event e) {
      final web.TouchEvent t = e as web.TouchEvent;
      if (t.touches.length != 2) return;
      e.preventDefault();
      final double d = _touchDistance(t);
      final (double x, double y) = _touchCentroid(t);
      McCamera camera = _c.camera;
      if (_pinchDistance > 0) camera = camera.dollyBy((_pinchDistance - d) * 2);
      camera = camera.panBy(x - _pinchX, y - _pinchY, _heightPx);
      _pinchDistance = d;
      _pinchX = x;
      _pinchY = y;
      _c.camera = camera;
    });
    void endPinch(web.Event e) {
      if ((e as web.TouchEvent).touches.length >= 2) return;
      _pinching = false;
      _pinchDistance = 0;
    }

    on('touchend', endPinch);
    on('touchcancel', endPinch);
  }

  void _detachInput() {
    final web.Element? stage = web.document.getElementById(_id);
    if (stage != null) {
      for (final MapEntry<String, JSFunction> entry in _listeners.entries) {
        stage.removeEventListener(entry.key, entry.value);
      }
    }
    _listeners.clear();
  }

  /// Numeric event properties are read untyped: `package:web` declares
  /// `clientX` as `int` and browsers hand back fractions.
  static double _num(web.Event event, String property) => _numOf(event as JSObject, property);

  static double _numOf(JSObject object, String property) {
    final JSAny? value = object.getProperty<JSAny?>(property.toJS);
    return value != null && value.isA<JSNumber>() ? (value as JSNumber).toDartDouble : 0;
  }

  static double _touchDistance(web.TouchEvent t) {
    final web.Touch a = t.touches.item(0)!, b = t.touches.item(1)!;
    final double dx = _numOf(a, 'clientX') - _numOf(b, 'clientX');
    final double dy = _numOf(a, 'clientY') - _numOf(b, 'clientY');
    return math.sqrt(dx * dx + dy * dy);
  }

  static (double, double) _touchCentroid(web.TouchEvent t) {
    final web.Touch a = t.touches.item(0)!, b = t.touches.item(1)!;
    return ((_numOf(a, 'clientX') + _numOf(b, 'clientX')) / 2, (_numOf(a, 'clientY') + _numOf(b, 'clientY')) / 2);
  }

  // --- text layer ----------------------------------------------------------

  /// Each text block centred over its anchor, sized for its depth by
  /// [mcTextFontPx], the same rule for nameplates and text displays.
  List<Widget> _textLayer() {
    if (_widthPx <= 0 || _heightPx <= 0) return const <Widget>[];
    final List<Widget> out = <Widget>[];
    final Set<McOccluder> culled = _c.culled(_c.camera, _widthPx, _heightPx);
    final double nearDepth = _c.camera.distance * mcNearTextReach;
    for (final NpcText text in _c.texts) {
      if (text.occluder != null && culled.contains(text.occluder)) continue;
      // A name of colour codes alone ("&f") draws an empty plate.
      if (text.styled == null && guiPlainText(text.lines.join()).trim().isEmpty) continue;
      if (text.styled != null && text.styled!.every((List<McTextRun> row) => row.every((McTextRun run) => run.text.trim().isEmpty))) continue;
      final McVec3? at = mcProjectToPixels(_c.camera, text.anchor, widthPx: _widthPx, heightPx: _heightPx);
      if (at == null || (text.nearOnly && at.z > nearDepth)) continue;
      // Text whose anchor left the stage is not drawn, except a speech
      // bubble, which follows the stage's edge.
      if (!text.keepInside && (at.x < 0 || at.x > _widthPx || at.y < 0 || at.y > _heightPx)) continue;
      final double fontPx = mcCrispFontPx(mcTextFontPx(_heightPx, at.z) * text.scaleY);
      final double stretch = text.scaleX / text.scaleY;
      out.add(dom.div(
        classes: 'animus-mc-text${text.nameplate ? ' is-nameplate' : ''}${text.dim ? ' is-dim' : ''}',
        attributes: <String, String>{
          'data-text': 'true',
          if (text.keepInside) 'data-keep-inside': 'true',
          if (text.yields) 'data-yields': 'true',
          if (text.occluder != null) 'data-neighbour': 'true',
          if (text.placed) 'data-placed': 'true',
        },
        styles: dom.Styles(raw: <String, String>{
          'left': '${at.x.round()}px',
          'top': '${at.y.round()}px',
          'font-size': '${fontPx.round()}px',
          // The client wraps text displays at their line width (200 by
          // default), and nothing is wider or taller than the stage.
          'max-width': '${math.min(fontPx * text.lineWidth / 8, math.max(0, _widthPx - mcTextInsetPx * 2)).toStringAsFixed(1)}px',
          'max-height': '${math.max(0, _heightPx - mcTextInsetPx * 2).toStringAsFixed(1)}px',
          'z-index': '${(1000 - at.z * 10).round().clamp(0, 1000)}',
          if (text.background != null) 'background': _argb(text.background!),
          if (text.alignment != McTextAlign.center) 'text-align': text.alignment.name,
          if (stretch != 1) ...<String, String>{'transform': 'translate(-50%, -100%) scaleX(${stretch.toStringAsFixed(3)})', 'transform-origin': '50% 100%'},
          if (text.brightness != null) 'filter': 'brightness(${text.brightness!.toStringAsFixed(3)})',
        }),
        <Widget>[
          for (final List<McTextRun> runs in text.styled ?? <List<McTextRun>>[for (final List<List<McTextRun>> line in guiDisplayRows(text.lines, sample: false)) ...line])
            dom.div(classes: 'animus-mc-text-line', <Widget>[
              for (final McTextRun run in runs)
                dom.span(
                  classes: run.obfuscated ? 'is-obfuscated' : null,
                  styles: dom.Styles(raw: <String, String>{
                    'color': text.opacity == 1 ? run.cssColor : _rgba(run.color, text.opacity),
                    // The client's shadow is the glyph colour at a quarter
                    // brightness, one font pixel down and right.
                    if (text.shadow) 'text-shadow': '0.125em 0.125em 0 ${_rgba(_quarter(run.color), text.opacity)}',
                    if (run.bold) 'font-weight': '700',
                    if (run.italic) 'font-style': 'italic',
                    if (run.underlined || run.strikethrough)
                      'text-decoration': '${run.underlined ? 'underline ' : ''}${run.strikethrough ? 'line-through' : ''}'.trim(),
                  }),
                  <Widget>[Component.text(run.obfuscated ? mcObfuscate(run.text) : run.text)],
                ),
              if (runs.every((McTextRun run) => run.text.isEmpty)) const Component.text(' '),
            ]),
        ],
      ));
    }
    return out;
  }

  static String _rgba(int rgb, double alpha) => 'rgba(${(rgb >> 16) & 0xFF}, ${(rgb >> 8) & 0xFF}, ${rgb & 0xFF}, ${alpha.toStringAsFixed(3)})';

  static int _quarter(int rgb) => (((rgb >> 16) & 0xFF) ~/ 4) << 16 | (((rgb >> 8) & 0xFF) ~/ 4) << 8 | (rgb & 0xFF) ~/ 4;

  /// `#AARRGGBB` as a CSS colour.
  static String _argb(String hex) {
    final int value = int.tryParse(hex.substring(1), radix: 16) ?? 0x40000000;
    return 'rgba(${(value >> 16) & 0xFF}, ${(value >> 8) & 0xFF}, ${value & 0xFF}, ${(((value >> 24) & 0xFF) / 255).toStringAsFixed(3)})';
  }

  /// Why the stage cannot draw, in plain words, and whether Retry helps;
  /// null while it can.
  (String, bool)? get _problem {
    if (!_c.webGlAvailable) return ('This browser cannot draw 3D. Turn on hardware acceleration.', false);
    if (_lost) return ('The 3D view stopped. Reload the page if it stays blank.', false);
    return switch (McAssetLoader.failure) {
      McPackFailure.missing => ('The 3D models are missing from this site.', true),
      McPackFailure.network => ('The 3D models did not download.', true),
      null => null,
    };
  }

  @override
  Widget build(BuildContext context) {
    _schedulePostFrame();
    final (String, bool)? problem = _problem;
    final bool loading = McAssetLoader.loading && _c.webGlAvailable;
    return dom.div(
      id: _id,
      classes: 'animus-mc-stage',
      attributes: <String, String>{
        ...component.attributes,
        'role': 'img',
        'tabindex': '0',
        'aria-label': component.label,
        'aria-describedby': '$_id-keys',
        'data-webgl': _c.webGlAvailable ? 'ready' : 'unavailable',
        'data-pack': loading ? 'loading' : (McAssetLoader.failure?.name ?? (McAssetLoader.loaded == null ? 'waiting' : 'ready')),
      },
      <Widget>[
        dom.div(id: _canvasHostId, classes: 'animus-mc-gl-host', attributes: <String, String>{'data-frames': '${_c.frames}', 'data-unbuilt': '${_c.unbuilt}'}, const <Widget>[]),
        dom.div(classes: 'animus-mc-text-layer', _textLayer()),
        if (component.overlay != null && _widthPx > 0 && _heightPx > 0)
          dom.div(classes: 'animus-mc-overlay', component.overlay!(_c.camera, _widthPx, _heightPx)),
        dom.span(id: '$_id-keys', classes: 'animus-sr-only', <Widget>[Component.text(mcStageControls())]),
        if (loading && problem == null)
          const dom.div(classes: 'animus-mc-state', <Widget>[Skeleton(height: '100%', label: 'Loading the 3D models')]),
        if (problem != null)
          dom.div(classes: 'animus-mc-state is-problem', <Widget>[
            AppAlert(
              tone: AlertTone.error,
              message: problem.$1,
              live: true,
              action: problem.$2 ? Button.outline(label: 'Retry', size: ButtonSize.sm, onPressed: _retry) : null,
            ),
          ]),
      ],
    );
  }
}
