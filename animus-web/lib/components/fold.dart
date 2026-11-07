/// A plain `<details>` fold that keeps its own open state, so a rebuild
/// (an edit, a server event) leaves it as the reader left it. jaspr drops
/// any attribute the build does not pass, so a fold that does not pass
/// `open` shuts on every rebuild.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:web/web.dart' as web;

import 'gui/gui_fields.dart' show takeSummaryClick;

class Fold extends StatefulWidget {
  const Fold({required this.summary, required this.children, this.open = false, this.classes, this.attributes = const <String, String>{}, super.key});

  /// What the summary line shows.
  final List<Widget> summary;
  final List<Widget> children;

  /// Whether the fold starts open. A later change of this value is followed;
  /// the reader's own open and close is kept otherwise.
  final bool open;
  final String? classes;

  /// Extra attributes on the `details` element, such as a `data-testid`.
  final Map<String, String> attributes;

  @override
  State<Fold> createState() => _FoldState();
}

class _FoldState extends State<Fold> {
  late bool _open = component.open;

  @override
  void didUpdateWidget(Fold oldComponent) {
    super.didUpdateWidget(oldComponent);
    if (oldComponent.open != component.open) _open = component.open;
  }

  @override
  Widget build(BuildContext context) => dom.details(
        classes: component.classes,
        attributes: <String, String>{...component.attributes, if (_open) 'open': ''},
        events: <String, void Function(web.Event)>{
          'toggle': (web.Event event) {
            final bool open = (event.currentTarget! as web.HTMLDetailsElement).open;
            if (open != _open) setState(() => _open = open);
          },
        },
        <Widget>[
          dom.summary(events: <String, void Function(web.Event)>{
            'click': (web.Event event) {
              if (takeSummaryClick(event)) setState(() => _open = !_open);
            },
          }, component.summary),
          ...component.children,
        ],
      );
}
