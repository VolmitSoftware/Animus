/// The 3D stage: one canvas the renderer draws into, a DOM layer for the
/// text the client draws in world space (names and text displays), and the
/// pointer, wheel and touch input that drives the orbit camera.
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

import '../../mc/npc/mc_text.dart';
import '../../mc/npc/npc_scene.dart';
import '../../mc/scene/mc_camera.dart';
import '../../mc/scene/mc_math.dart';
import '../../mc/scene/mc_scene.dart';
import 'mc_asset_loader.dart';
import 'mc_gl.dart';
import 'mc_gl_renderer.dart';

/// What the stage draws and from where.
final class McStageController extends ChangeNotifier {
  McStageController();

  McScene _scene = McScene.empty;
  List<NpcText> _texts = const <NpcText>[];
  McCamera _camera = mcCameraFraming(const McBounds(McVec3(-0.5, 0, -0.5), McVec3(0.5, 1.8, 0.5)).corners);
  McCamera _home = mcCameraFraming(const McBounds(McVec3(-0.5, 0, -0.5), McVec3(0.5, 1.8, 0.5)).corners);

  /// Frames rendered so far; the browser check reads `data-frames`.
  int frames = 0;

  /// Nodes the last frame could not draw yet (texture decoding, or an item
  /// the client draws with a special renderer).
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
  double _aspect = 4 / 3;

  /// Shows [scene]; [frame] moves the home framing to the new subject (and
  /// the camera with it when the camera was home).
  void show(McScene scene, List<NpcText> texts, {required double yawDeg, bool frame = false}) {
    _scene = scene;
    _texts = texts;
    _frame = scene.frame;
    _yawDeg = yawDeg;
    _reframe(force: frame);
    notifyListeners();
  }

  /// The stage's width over height; a home camera refits to it.
  set aspect(double value) {
    if (!(value > 0) || (value - _aspect).abs() < 1e-3) return;
    _aspect = value;
    _reframe();
    notifyListeners();
  }

  void _reframe({bool force = false}) {
    if (_frame.isEmpty) return;
    final McCamera home = mcCameraFraming(_frame, yawDeg: _yawDeg + 180, aspect: _aspect);
    if (force || _camera == _home) _camera = home;
    _home = home;
  }

  void resetCamera() => camera = _home;
}

/// Widgets drawn over the stage in pixel space, rebuilt with the camera.
/// Elements under `[data-gizmo]` take their own pointer input; the orbit
/// camera ignores presses on them.
typedef McStageOverlay = List<Widget> Function(McCamera camera, double widthPx, double heightPx);

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
  McGl? _gl;
  McGlRenderer? _renderer;
  web.ResizeObserver? _resize;
  double _widthPx = 0;
  double _heightPx = 0;
  bool _framePending = false;
  bool _dirty = true;
  bool _postFramePending = false;
  bool _disposed = false;
  String? _problem;

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
    if (McAssetLoader.failed) {
      setState(() => _problem = 'The Minecraft asset pack is missing. Run tool/extract_minecraft_world_assets.dart before building.');
      return;
    }
    _attachGl();
    _invalidate();
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
      gl.onRestored(_invalidate);
    }
    _attachGl();
    final web.HTMLElement stage = web.document.getElementById(_id)! as web.HTMLElement;
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
    if (rect.height > 0) _c.aspect = rect.width / rect.height;
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
  }

  // --- input ---------------------------------------------------------------

  void _attachInput(web.HTMLElement stage) {
    _detachInput();
    void on(String type, void Function(web.Event) handler) {
      final JSFunction fn = handler.toJS;
      _listeners[type] = fn;
      stage.addEventListener(type, fn);
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
    }

    on('pointerup', release);
    on('pointercancel', release);
    on('contextmenu', (web.Event e) => e.preventDefault());
    on('wheel', (web.Event e) {
      e.preventDefault();
      final web.WheelEvent w = e as web.WheelEvent;
      _c.camera = _c.camera.dollyBy(mcWheelPixels(_num(w, 'deltaY'), w.deltaMode));
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
      final double fontPx = mcTextFontPx(_heightPx, at.z);
      out.add(dom.div(
        classes: 'animus-mc-text${text.nameplate ? ' is-nameplate' : ''}',
        attributes: const <String, String>{'data-text': 'true'},
        styles: dom.Styles(raw: <String, String>{
          'left': '${at.x.toStringAsFixed(1)}px',
          'top': '${at.y.toStringAsFixed(1)}px',
          'font-size': '${fontPx.toStringAsFixed(2)}px',
          'z-index': '${(1000 - at.z * 10).round().clamp(0, 1000)}',
        }),
        <Widget>[
          for (final String line in text.lines)
            dom.div(classes: 'animus-mc-text-line', <Widget>[
              for (final McTextRun run in mcLegacyRuns(line))
                dom.span(
                  styles: dom.Styles(raw: <String, String>{
                    'color': run.cssColor,
                    if (run.bold) 'font-weight': '700',
                    if (run.italic) 'font-style': 'italic',
                    if (run.underlined || run.strikethrough)
                      'text-decoration': '${run.underlined ? 'underline ' : ''}${run.strikethrough ? 'line-through' : ''}'.trim(),
                  }),
                  <Widget>[Component.text(run.text)],
                ),
              if (line.isEmpty) const Component.text(' '),
            ]),
        ],
      ));
    }
    return out;
  }

  @override
  Widget build(BuildContext context) {
    _schedulePostFrame();
    final String? problem = _problem ?? (_c.webGlAvailable ? null : 'This browser has no WebGL2, so the 3D preview is off.');
    return dom.div(
      id: _id,
      classes: 'animus-mc-stage',
      attributes: <String, String>{
        'role': 'img',
        'aria-label': component.label,
        'data-webgl': _c.webGlAvailable ? 'ready' : 'unavailable',
      },
      <Widget>[
        dom.div(id: _canvasHostId, classes: 'animus-mc-gl-host', attributes: <String, String>{'data-frames': '${_c.frames}'}, const <Widget>[]),
        dom.div(classes: 'animus-mc-text-layer', _textLayer()),
        if (component.overlay != null && _widthPx > 0 && _heightPx > 0)
          dom.div(classes: 'animus-mc-overlay', component.overlay!(_c.camera, _widthPx, _heightPx)),
        if (problem != null) dom.p(classes: 'animus-mc-problem', <Widget>[Component.text(problem)]),
      ],
    );
  }
}
