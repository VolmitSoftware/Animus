/// A 3D stage in the kit's [StageFrame]: the header (title, the toggles
/// that change what it shows, its actions, Reset view, Fullscreen), the
/// canvas, and one short status line under it with the details and the
/// camera keys in its tip. What the renderer could not draw is counted on
/// that line, never written over the canvas.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';

import '../../logic/stage/stage_status.dart';
import '../../ui/action_row.dart';
import '../../ui/empty_state.dart';
import '../../ui/menu.dart';
import '../../ui/stage_frame.dart';
import 'mc_asset_loader.dart';
import 'mc_stage.dart';

class McStageFrame extends StatefulWidget {
  const McStageFrame({
    required this.title,
    required this.controller,
    required this.label,
    this.toggles = const <Widget>[],
    this.actions = const <RowAction>[],
    this.more = const <AppMenuEntry>[],
    this.status = const <StageNote>[],
    this.extra = const <String>[],
    this.notes = const <String>[],
    this.overlay,
    this.onPick,
    this.collapsible = false,
    this.empty = false,
    this.emptyMessage = 'Nothing to preview',
    this.attributes = const <String, String>{},
    super.key,
  });

  final String title;
  final McStageController controller;

  /// The canvas's accessible name.
  final String label;

  /// Leading header controls: ToggleChips / ToggleChoice for what shows.
  final List<Widget> toggles;

  /// Header buttons before Reset view and Fullscreen.
  final List<RowAction> actions;

  /// The header's "..." menu: rarer view options (the radius) and the
  /// toggles that do not fit beside the title.
  final List<AppMenuEntry> more;

  /// The status line's phrases with the sentence each gives in the tip,
  /// most important first ([StageStatus]).
  final List<StageNote> status;

  /// Tip sentences with no phrase of their own.
  final List<String> extra;

  /// What the scene leaves out (a chest on a llama), counted with what the
  /// renderer could not draw.
  final List<String> notes;
  final McStageOverlay? overlay;
  final void Function(double x, double y)? onPick;

  /// Hide and Show for the band above a form at 761-1099 px.
  final bool collapsible;

  /// Nothing to draw: an empty state instead of the canvas.
  final bool empty;
  final String emptyMessage;

  /// `data-*` attributes on the stage element, for checks.
  final Map<String, String> attributes;

  @override
  State<McStageFrame> createState() => _McStageFrameState();
}

class _McStageFrameState extends State<McStageFrame> {
  List<String> _notes = const <String>[];
  bool _broken = false;

  @override
  void initState() {
    super.initState();
    _notes = component.controller.notes;
    _broken = _cannotDraw;
    component.controller.addListener(_changed);
    McAssetLoader.addListener(_changed);
  }

  /// The stage shows an alert instead of a picture: no WebGL, or no models.
  bool get _cannotDraw => McAssetLoader.failed || !component.controller.webGlAvailable;

  @override
  void didUpdateWidget(McStageFrame oldComponent) {
    super.didUpdateWidget(oldComponent);
    if (!identical(oldComponent.controller, component.controller)) {
      oldComponent.controller.removeListener(_changed);
      component.controller.addListener(_changed);
      _notes = component.controller.notes;
    }
  }

  @override
  void dispose() {
    component.controller.removeListener(_changed);
    McAssetLoader.removeListener(_changed);
    super.dispose();
  }

  /// Rebuilds only when the renderer's notes or whether it can draw
  /// changed; camera moves redraw the stage alone.
  void _changed() {
    final List<String> notes = component.controller.notes;
    final bool broken = _cannotDraw;
    if (!mounted || (identical(notes, _notes) && broken == _broken)) return;
    setState(() {
      _notes = notes;
      _broken = broken;
    });
  }

  @override
  Widget build(BuildContext context) {
    final StageStatus status = StageStatus(component.status, unrendered: <String>[...component.notes, ..._notes], extra: component.extra);
    // With nothing drawn, the header and the line have nothing to act on;
    // the stage's own alert says why and offers Retry.
    final bool idle = component.empty || _broken;
    return StageFrame(
      title: component.title,
      toggles: idle ? const <Widget>[] : component.toggles,
      actions: idle ? const <RowAction>[] : component.actions,
      more: idle ? const <AppMenuEntry>[] : component.more,
      onReset: idle ? null : component.controller.resetCamera,
      fullscreen: !idle,
      collapsible: component.collapsible,
      status: idle ? null : status.line,
      statusTip: status.tip(mcStageControls()),
      child: component.empty
          ? EmptyState(message: component.emptyMessage, icon: ArcaneIcon.box())
          : McStage(controller: component.controller, label: component.label, overlay: component.overlay, onPick: component.onPick, attributes: <String, String>{
              ...component.attributes,
              // Everything the stage left out, for checks: the line counts it and the tip shows two.
              'data-notes': status.unrendered.join('\n'),
            }),
    );
  }
}
