/// The 3D stage for holograms and holographic menus, redrawn whenever the
/// scene's [signature] changes, with the notes on what it cannot show. A
/// scene with text animations is rebuilt from [sceneAt] every [tickMs], so
/// `|animation.<id>|` tokens play their frames, until the viewer pauses
/// them; under reduced motion they start paused. Scene targets are drawn as
/// pick rings over the stage, through the stage's overlay hook.
library;

import 'dart:async';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;
import 'package:web/web.dart' as web;

import '../../mc/gui/gui_scene.dart';
import '../../mc/npc/npc_scene.dart';
import '../../mc/scene/mc_camera.dart';
import '../../mc/scene/mc_math.dart';
import '../mc/mc_stage.dart';
import 'gui_fields.dart';

class GuiStagePreview extends StatefulWidget {
  const GuiStagePreview({
    required this.scene,
    required this.signature,
    required this.label,
    this.toolbar = const <Widget>[],
    this.sceneAt,
    this.tickMs,
    this.selected,
    this.onPick,
    super.key,
  });

  final GuiScene scene;

  /// The scene at a wall-clock millisecond, for animated text; with [tickMs].
  final GuiScene Function(int nowMs)? sceneAt;
  final int? tickMs;

  /// Equal signatures keep the current camera and scene.
  final String signature;
  final String label;
  final List<Widget> toolbar;

  /// The scene target drawn with the selection ring.
  final String? selected;

  /// Called with the target a click on the stage picks.
  final void Function(String key)? onPick;

  @override
  State<GuiStagePreview> createState() => _GuiStagePreviewState();
}

class _GuiStagePreviewState extends State<GuiStagePreview> {
  final McStageController _stage = McStageController();
  String? _shown;
  String? _frames;
  Timer? _timer;
  int? _timerMs;
  late bool _playing = !prefersReducedMotion();

  @override
  void initState() {
    super.initState();
    _sync(frame: true);
  }

  @override
  void didUpdateWidget(GuiStagePreview oldComponent) {
    super.didUpdateWidget(oldComponent);
    _sync();
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  bool get _animated => component.sceneAt != null && component.tickMs != null;

  GuiScene get _current {
    final GuiScene Function(int nowMs)? at = component.sceneAt;
    return at == null || component.tickMs == null ? component.scene : at(DateTime.now().millisecondsSinceEpoch);
  }

  static String _textsOf(GuiScene scene) => scene.texts.map((NpcText text) => text.lines.join('\n')).join('\u0000');

  void _sync({bool frame = false}) {
    _schedule();
    if (_shown == component.signature && !frame) return;
    _shown = component.signature;
    final GuiScene scene = _current;
    _frames = _textsOf(scene);
    _stage.show(scene.scene, scene.texts, yawDeg: 0, frame: frame);
  }

  void _schedule() {
    final int? tick = _animated && _playing ? component.tickMs : null;
    if (tick == _timerMs) return;
    _timer?.cancel();
    _timerMs = tick;
    _timer = tick == null ? null : Timer.periodic(Duration(milliseconds: tick), (Timer _) => _tick());
  }

  /// Redraws only when a frame changed, keeping the camera.
  void _tick() {
    final GuiScene scene = _current;
    final String frames = _textsOf(scene);
    if (frames == _frames) return;
    _frames = frames;
    _stage.show(scene.scene, scene.texts, yawDeg: 0);
  }

  void _togglePlay() {
    setState(() => _playing = !_playing);
    _schedule();
  }

  /// One ring per target: a box around its projected corners. Pointer only;
  /// the component list beside the stage is the keyboard path.
  List<Widget> _overlay(McCamera camera, double width, double height) {
    final void Function(String key)? pick = component.onPick;
    final List<Widget> out = <Widget>[];
    for (final GuiSceneTarget target in component.scene.targets) {
      double left = double.infinity, top = double.infinity, right = -double.infinity, bottom = -double.infinity;
      for (final double x in <double>[-1, 1]) {
        for (final double y in <double>[-1, 1]) {
          for (final double z in <double>[-1, 1]) {
            final McVec3? at = mcProjectToPixels(
              camera,
              target.centre + McVec3(target.half.x * x, target.half.y * y, target.half.z * z),
              widthPx: width,
              heightPx: height,
            );
            if (at == null) continue;
            if (at.x < left) left = at.x;
            if (at.x > right) right = at.x;
            if (at.y < top) top = at.y;
            if (at.y > bottom) bottom = at.y;
          }
        }
      }
      if (left == double.infinity) continue;
      final bool selected = target.key == component.selected;
      out.add(dom.div(
        classes: 'animus-gui-pick${selected ? ' is-selected' : ''}',
        attributes: <String, String>{
          'aria-hidden': 'true',
          'data-pick': target.key,
          if (pick != null) 'data-gizmo': 'pick',
        },
        styles: dom.Styles(raw: <String, String>{
          'left': '${left.toStringAsFixed(1)}px',
          'top': '${top.toStringAsFixed(1)}px',
          'width': '${(right - left).toStringAsFixed(1)}px',
          'height': '${(bottom - top).toStringAsFixed(1)}px',
        }),
        events: <String, void Function(web.Event)>{
          'pointerdown': (web.Event event) {
            if (pick == null) return;
            event.preventDefault();
            pick(target.key);
          },
        },
        const <Widget>[],
      ));
    }
    return out;
  }

  @override
  Widget build(BuildContext context) {
    final bool hasTargets = component.scene.targets.isNotEmpty;
    return dom.div(classes: 'animus-gui-stage-3d', <Widget>[
      dom.div(classes: 'animus-npc-preview-bar', <Widget>[
        Button.ghost(label: 'Reset view', size: ButtonSize.small, onPressed: _stage.resetCamera),
        if (_animated)
          Button.ghost(
            label: _playing ? 'Pause animations' : 'Play animations',
            size: ButtonSize.small,
            attributes: <String, String>{'aria-pressed': '${!_playing}', 'data-anim': 'stage-toggle'},
            onPressed: _togglePlay,
          ),
        ...component.toolbar,
      ]),
      McStage(controller: _stage, label: component.label, overlay: hasTargets ? _overlay : null),
      if (_animated && !_playing && prefersReducedMotion())
        const dom.p(classes: 'animus-muted', <Widget>[Component.text('Text animations are paused because your system asks for reduced motion.')]),
      dom.ul(classes: 'animus-npc-notes', attributes: const <String, String>{'aria-live': 'polite'}, <Widget>[
        for (final String note in component.scene.notes) dom.li(<Widget>[Component.text(note)]),
      ]),
    ]);
  }
}
