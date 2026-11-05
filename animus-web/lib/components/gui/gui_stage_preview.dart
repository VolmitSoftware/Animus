/// The 3D stage for holograms and holographic menus, redrawn whenever the
/// scene's [signature] changes, with the notes on what it cannot show.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../mc/gui/gui_scene.dart';
import '../mc/mc_stage.dart';

class GuiStagePreview extends StatefulWidget {
  const GuiStagePreview({required this.scene, required this.signature, required this.label, this.toolbar = const <Widget>[], super.key});

  final GuiScene scene;

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

  void _sync({bool frame = false}) {
    if (_shown == component.signature && !frame) return;
    _shown = component.signature;
    _stage.show(component.scene.scene, component.scene.texts, yawDeg: 0, frame: frame);
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
