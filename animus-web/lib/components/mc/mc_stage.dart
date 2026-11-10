/// The 3D stage: one canvas the renderer draws into, a DOM layer for the
/// text the client draws in world space (names and text displays), the
/// pointer, wheel, touch and keyboard input that drives the orbit camera,
/// and a status line under the stage that says what is loading, what failed
/// and what the stage cannot draw.
///
/// Jaspr renders the wrapper, the text layer and the controls; the canvas is
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

import '../../config/docs_links.dart';
import '../../logic/held_notes.dart';
import '../../mc/assets/mc_ids.dart';
import '../../mc/npc/mc_text.dart';
import '../../mc/npc/npc_scene.dart';
import '../../mc/scene/mc_camera.dart';
import '../../mc/scene/mc_math.dart';
import '../../mc/scene/mc_scene.dart';
import 'mc_asset_loader.dart';
import 'mc_gl.dart';
import '../info_tip.dart';
import 'mc_gl_renderer.dart';
import '../shortcut_sheet.dart' show platformKeys;
import '../typing_pause.dart' show typingPause;

/// Pixels of drag one arrow key press stands for.
const double mcStageKeyStepPx = 24;

/// What the stage draws and from where.
final class McStageController extends ChangeNotifier {
  McStageController();

  McScene _scene = McScene.empty;
  List<NpcText> _texts = const <NpcText>[];
  McCamera _camera = mcCameraFraming(const McBounds(McVec3(-0.5, 0, -0.5), McVec3(0.5, 1.8, 0.5)).corners);
  McCamera _home = mcCameraFraming(const McBounds(McVec3(-0.5, 0, -0.5), McVec3(0.5, 1.8, 0.5)).corners);

  /// Frames rendered so far; the browser check reads `data-frames`, and
  /// `data-drawn` for the meshes the last frame drew.
  int frames = 0;

  /// Nodes the last frame could not draw yet (a texture still decoding).
  int unbuilt = 0;

  bool _webGlAvailable = true;

  McScene get scene => _scene;
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
  double _yawDeg = 0;
  /// Zero until the stage is measured; the framing assumes 4:3 till then.
  double _widthPx = 0;
  double _heightPx = 0;

  McBounds? _reach;

  /// Shows [scene]; [frame] moves the home framing to the new subject (and
  /// the camera with it when the camera was home). [reach] is where the
  /// camera may pan, around the origin by default.
  void show(McScene scene, List<NpcText> texts, {required double yawDeg, bool frame = false, McBounds? reach}) {
    _scene = scene;
    _texts = texts;
    _frame = scene.frame;
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
    final McCamera home = mcFrameTexts(
      mcCameraFraming(_frame, yawDeg: _yawDeg + 180, aspect: _heightPx > 0 ? _widthPx / _heightPx : 4 / 3, reach: _reach),
      _texts,
      _frame,
      widthPx: _widthPx,
      heightPx: _heightPx,
    );
    if (force || _camera == _home) _camera = home;
    _home = home;
  }

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
  final String id = item.contains(':') ? item : 'minecraft:${mcStrip(item)}';
  if (reason.startsWith('special renderer')) {
    return '$id is not drawn: the game draws it with its own renderer (shields, tridents, skulls, banners, chests and beds work this way), which the preview does not have.';
  }
  if (reason.startsWith('no item definition')) return '$id is not drawn: Minecraft 26.3 has no item with that id. Check the spelling.';
  return '$id is not drawn: it has no model the preview can draw.';
}

class McStage extends StatefulWidget {
  const McStage({required this.controller, this.label = 'NPC preview', this.overlay, super.key});

  final McStageController controller;
  final String label;
  final McStageOverlay? overlay;

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
  List<String> _notes = const <String>[];

  /// The renderer's latest notes; new ones reach [_notes] after a pause.
  List<String> _latestNotes = const <String>[];
  Timer? _notesPause;

  bool _dragging = false;
  bool _panning = false;
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
      if (!_disposed) _attachToDom();
    });
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
    _c.unbuilt = renderer.render(_c.scene, _c.camera, widthPx: _widthPx, heightPx: _heightPx, dpr: web.window.devicePixelRatio);
    _c.frames++;
    _host?.setAttribute('data-frames', '${_c.frames}');
    _host?.setAttribute('data-unbuilt', '${_c.unbuilt}');
    _host?.setAttribute('data-drawn', '${renderer.drawn}');
    final McAssetPackData? pack = McAssetLoader.loaded;
    final List<String> notes = <String>[
      for (final String id in renderer.failedTextures) mcTextureNote(id, runtime: pack?.runtime(id) ?? false, inPack: pack?.manifest.entityTextures.containsKey(id) ?? false),
      for (final MapEntry<String, String> item in renderer.undrawable.entries) mcItemNote(item.key, item.value),
    ];
    if (notes.join('\n') == _latestNotes.join('\n')) return;
    _latestNotes = notes;
    _notesPause?.cancel();
    final List<String> kept = keptNotes(_notes, notes);
    if (kept.join('\n') != _notes.join('\n')) setState(() => _notes = kept);
    if (kept.length < notes.length) {
      _notesPause = Timer(typingPause, () {
        if (!_disposed) setState(() => _notes = _latestNotes);
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
      if (target != null && target.isA<web.Element>() && (target as web.Element).closest('[data-gizmo]') != null) return;
      _pointerId = p.pointerId;
      _lastX = _num(p, 'clientX');
      _lastY = _num(p, 'clientY');
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
      if (_pinching) return;
      _c.camera = _panning ? _c.camera.panBy(dx, dy, _heightPx) : _c.camera.orbitBy(dx, dy);
    });
    void release(web.Event e) {
      if (!_dragging || (e as web.PointerEvent).pointerId != _pointerId) return;
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
    for (final NpcText text in _c.texts) {
      final McVec3? at = mcProjectToPixels(_c.camera, text.anchor, widthPx: _widthPx, heightPx: _heightPx);
      if (at == null) continue;
      final double fontPx = mcTextFontPx(_heightPx, at.z) * text.scaleY;
      final double stretch = text.scaleX / text.scaleY;
      out.add(dom.div(
        classes: 'animus-mc-text${text.nameplate ? ' is-nameplate' : ''}${text.dim ? ' is-dim' : ''}',
        attributes: const <String, String>{'data-text': 'true'},
        styles: dom.Styles(raw: <String, String>{
          'left': '${at.x.toStringAsFixed(1)}px',
          'top': '${at.y.toStringAsFixed(1)}px',
          'font-size': '${fontPx.toStringAsFixed(2)}px',
          // The client wraps text displays at their line width (200 by default).
          'max-width': '${(fontPx * text.lineWidth / 8).toStringAsFixed(1)}px',
          'z-index': '${(1000 - at.z * 10).round().clamp(0, 1000)}',
          if (text.background != null) 'background': _argb(text.background!),
          if (text.alignment != McTextAlign.center) 'text-align': text.alignment.name,
          if (stretch != 1) ...<String, String>{'transform': 'translate(-50%, -100%) scaleX(${stretch.toStringAsFixed(3)})', 'transform-origin': '50% 100%'},
          if (text.brightness != null) 'filter': 'brightness(${text.brightness!.toStringAsFixed(3)})',
        }),
        <Widget>[
          for (final List<McTextRun> runs in text.styled ?? <List<McTextRun>>[for (final String line in text.lines) mcLegacyRuns(line)])
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

  /// The status line: what is loading, what failed and what to do, and what
  /// the stage leaves out. Null when there is nothing to say.
  (String, bool)? get _problem {
    if (!_c.webGlAvailable) {
      return (
        'WebGL2 is off or missing. Turn on hardware acceleration or use a current browser.',
        false,
      );
    }
    if (_lost) return ('Graphics reset. Reload if the preview stays blank.', false);
    return switch (McAssetLoader.failure) {
      McPackFailure.missing => ('Model pack missing from this site. See the README.', false),
      McPackFailure.network => ('Models failed to download. Check the connection.', true),
      null => null,
    };
  }

  @override
  Widget build(BuildContext context) {
    _schedulePostFrame();
    final (String, bool)? problem = _problem;
    final bool loading = McAssetLoader.loading && _c.webGlAvailable;
    return dom.div(classes: 'animus-mc-stage-frame', <Widget>[
      dom.div(
        id: _id,
        classes: 'animus-mc-stage',
        attributes: <String, String>{
          'role': 'img',
          'tabindex': '0',
          'aria-label': component.label,
          'aria-describedby': '$_id-keys $_id-status',
          'data-webgl': _c.webGlAvailable ? 'ready' : 'unavailable',
          'data-pack': loading ? 'loading' : (McAssetLoader.failure?.name ?? (McAssetLoader.loaded == null ? 'waiting' : 'ready')),
        },
        <Widget>[
          dom.div(id: _canvasHostId, classes: 'animus-mc-gl-host', attributes: <String, String>{'data-frames': '${_c.frames}', 'data-unbuilt': '${_c.unbuilt}'}, const <Widget>[]),
          dom.div(classes: 'animus-mc-text-layer', _textLayer()),
          if (component.overlay != null && _widthPx > 0 && _heightPx > 0)
            dom.div(classes: 'animus-mc-overlay', component.overlay!(_c.camera, _widthPx, _heightPx)),
          if (loading)
            const dom.div(classes: 'animus-mc-loading', attributes: <String, String>{'aria-hidden': 'true'}, <Widget>[
              dom.span(classes: 'animus-mc-spinner', <Widget>[]),
            ]),
        ],
      ),
      dom.p(id: '$_id-keys', classes: 'animus-mc-keys', <Widget>[
        InfoTip(
          tip: 'Drag to orbit, Shift or right drag to pan, ${platformKeys('Mod')} and the wheel to zoom. Focused: arrows orbit, Shift and arrows pan, + and - zoom, 0 resets.',
          docs: Docs.editorKeyboard,
          child: const Component.text('Controls'),
        ),
      ]),
      dom.div(id: '$_id-status', classes: 'animus-mc-status', attributes: const <String, String>{'aria-live': 'polite'}, <Widget>[
        if (loading) const dom.p(<Widget>[Component.text('Loading models (2 MB)')]),
        if (problem != null)
          dom.p(classes: 'animus-mc-problem', <Widget>[
            Component.text(problem.$1),
            if (problem.$2) Button.secondary(label: 'Retry', size: ButtonSize.small, onPressed: _retry),
          ]),
        if (_notes.isNotEmpty)
          dom.ul(classes: 'animus-npc-notes', <Widget>[
            for (final String note in _notes) dom.li(<Widget>[Component.text(note)]),
          ]),
      ]),
    ]);
  }
}
