/// The 3D stage for holograms and holographic menus, redrawn whenever the
/// scene's [signature] changes, with the notes on what it cannot show. A
/// scene with text animations is rebuilt from [sceneAt] every [tickMs], so
/// `|animation.<id>|` tokens play their frames.
library;

import 'dart:async';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../mc/gui/gui_scene.dart';
import '../../mc/npc/npc_scene.dart';
import '../mc/mc_stage.dart';

class GuiStagePreview extends StatefulWidget {
  const GuiStagePreview({
    required this.scene,
    required this.signature,
    required this.label,
    this.toolbar = const <Widget>[],
    this.sceneAt,
    this.tickMs,
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

  @override
  State<GuiStagePreview> createState() => _GuiStagePreviewState();
}

class _GuiStagePreviewState extends State<GuiStagePreview> {
  final McStageController _stage = McStageController();
  String? _shown;
  String? _frames;
  Timer? _timer;
  int? _timerMs;

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
    final int? tick = component.sceneAt == null ? null : component.tickMs;
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

  @override
  Widget build(BuildContext context) {
    return dom.div(classes: 'animus-gui-stage-3d', <Widget>[
      dom.div(classes: 'animus-npc-preview-bar', <Widget>[
        Button.ghost(label: 'Reset view', size: ButtonSize.small, onPressed: _stage.resetCamera),
        ...component.toolbar,
      ]),
      McStage(controller: _stage, label: component.label),
      if (component.scene.notes.isNotEmpty)
        dom.ul(classes: 'animus-npc-notes', <Widget>[
          for (final String note in component.scene.notes) dom.li(<Widget>[Component.text(note)]),
        ]),
    ]);
  }
}
