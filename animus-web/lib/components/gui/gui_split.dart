/// The one layout of the GUI, shop, hologram, menu, text animation and
/// quest pages, the document frame's own: the form (one tab strip) on the
/// left and the preview in a StageFrame on the right from 1100 px; at
/// 761-1099 px the preview is a band above the form, at most 240 px tall;
/// on phones the form comes first and the page header's Preview button
/// ([PreviewToggle]) puts the preview above it. A page whose preview is the
/// editor itself (the chest window) keeps it first on phones too.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:web/web.dart' as web;

import '../../services/browser.dart' show watchMedia;
import '../../ui/stage_frame.dart';
import '../../ui/stack.dart';

/// Where the preview sits at the current width.
enum GuiPlace { side, band, phone }

/// Whether the page header's Preview button is on, which shows a phone's
/// preview above the form. The document frame provides it.
class PreviewToggle extends InheritedWidget {
  const PreviewToggle({required this.shown, required super.child, super.key});

  final bool shown;

  static bool shownOf(BuildContext context) => context.dependOnInheritedComponentOfExactType<PreviewToggle>()?.shown ?? false;

  @override
  bool updateShouldNotify(PreviewToggle oldComponent) => oldComponent.shown != shown;
}

class GuiSplit extends StatefulWidget {
  const GuiSplit({required this.form, required this.preview, this.previewFirst = false, this.label, super.key});

  /// The form: its tab strip and the open tab.
  final List<Widget> form;

  /// The preview frame; [band] is true while it sits above the form.
  final Widget Function(bool band) preview;

  /// The preview is the editor (the chest window): first at every width.
  final bool previewFirst;

  /// Names the form region.
  final String? label;

  @override
  State<GuiSplit> createState() => _GuiSplitState();
}

class _GuiSplitState extends State<GuiSplit> {
  GuiPlace _place = GuiPlace.side;
  final List<void Function()> _stops = <void Function()>[];

  @override
  void initState() {
    super.initState();
    final (bool phone, void Function() stopPhone) = watchMedia('(max-width: 760px)', (bool _) => _measure());
    final (bool band, void Function() stopBand) = watchMedia('(max-width: 1099px)', (bool _) => _measure());
    _place = _placeFor(phone, band);
    _stops.addAll(<void Function()>[stopPhone, stopBand]);
  }

  @override
  void dispose() {
    for (final void Function() stop in _stops) {
      stop();
    }
    super.dispose();
  }

  static GuiPlace _placeFor(bool phone, bool band) => phone ? GuiPlace.phone : (band ? GuiPlace.band : GuiPlace.side);

  void _measure() {
    if (!mounted) return;
    final GuiPlace next = _placeFor(web.window.matchMedia('(max-width: 760px)').matches, web.window.matchMedia('(max-width: 1099px)').matches);
    if (next != _place) setState(() => _place = next);
  }

  @override
  Widget build(BuildContext context) {
    final Widget form = dom.div(
      classes: 'ui-doc-form animus-gui-form',
      attributes: <String, String>{'role': 'region', 'aria-label': component.label ?? 'Form'},
      <Widget>[stack(spacing: 16, component.form)],
    );
    Widget stage(bool band) => dom.div(classes: 'ui-doc-preview', <Widget>[component.preview(band)]);
    return switch (_place) {
      GuiPlace.side => dom.div(classes: 'ui-doc-body has-preview animus-gui-split${component.previewFirst ? ' is-preview-first' : ''}', <Widget>[
          form,
          StageSide(child: stage(false)),
        ]),
      GuiPlace.band => dom.div(classes: 'ui-doc-body is-band', <Widget>[stage(true), form]),
      GuiPlace.phone => dom.div(classes: 'ui-doc-body is-stacked', <Widget>[
          if (component.previewFirst || PreviewToggle.shownOf(context)) dom.div(id: 'ui-doc-preview', <Widget>[stage(false)]),
          form,
        ]),
    };
  }
}
