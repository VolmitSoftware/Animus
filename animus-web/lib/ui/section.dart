/// The one disclosure: a titled group of fields that opens and closes, on
/// shadcn's disclosure (native `details`) in its borderless variant: a
/// heading over fields on the page or card it sits on, never a box of its
/// own. The open state lives here, so a rebuild keeps it as the reader left
/// it; an open made outside (`revealAndFocus`) is reported back.
library;

import 'dart:js_interop';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;
import 'package:web/web.dart' as web;

import '../config/docs_links.dart';
import 'info_tip.dart';

class AppSection extends StatefulWidget {
  const AppSection({
    required this.children,
    this.title,
    this.summary,
    this.open = true,
    this.count,
    this.tip,
    this.docs,
    this.classes,
    this.attributes = const <String, String>{},
    this.onKey,
    this.onOpenChange,
    super.key,
  }) : assert(title != null || summary != null, 'A section needs a title or a summary.');

  /// The heading text; [summary] replaces it with widgets.
  final String? title;
  final List<Widget>? summary;
  final List<Widget> children;

  /// Whether it starts open. A later change of this value is followed; the
  /// reader's own open and close is kept otherwise. With [onOpenChange] it
  /// is the state itself.
  final bool open;

  /// Makes [open] the caller's: a click on the summary asks for the other
  /// state here, and the section changes only when [open] does.
  final void Function(bool open)? onOpenChange;

  /// A number after the title (items inside).
  final int? count;

  /// Tip text on the title, linking to [docs] or the surrounding page.
  final String? tip;
  final DocLink? docs;

  final String? classes;

  /// Extra attributes on the wrapper, such as a `data-testid`.
  final Map<String, String> attributes;

  /// A key pressed on this section's own summary; [show] opens or closes it.
  final void Function(web.KeyboardEvent event, void Function(bool open) show)? onKey;

  @override
  State<AppSection> createState() => _AppSectionState();
}

class _AppSectionState extends State<AppSection> {
  late bool _open = component.open;

  @override
  void didUpdateWidget(AppSection oldComponent) {
    super.didUpdateWidget(oldComponent);
    if (oldComponent.open != component.open) _open = component.open;
  }

  bool get _shown => component.onOpenChange == null ? _open : component.open;

  void _show(bool open) {
    if (!mounted || open == _shown) return;
    if (component.onOpenChange != null) {
      component.onOpenChange!(open);
    } else {
      setState(() => _open = open);
    }
  }

  /// A key pressed on this section's own summary, not a nested one's.
  void _key(web.Event event) {
    final web.EventTarget? target = event.target;
    if (!event.isA<web.KeyboardEvent>() || target == null || !target.isA<web.Element>()) return;
    final web.Element summary = target as web.Element;
    if (summary.tagName.toLowerCase() != 'summary' || !identical(summary.parentElement?.parentElement, event.currentTarget)) return;
    component.onKey!(event as web.KeyboardEvent, _show);
  }

  @override
  Widget build(BuildContext context) {
    final List<Widget> heading = component.summary ??
        <Widget>[
          dom.span(classes: 'ui-section-title', <Widget>[
            TipLabel(label: Component.text(component.title!), tip: component.tip, docs: component.docs),
          ]),
        ];
    return dom.div(
      classes: 'ui-section${component.classes == null ? '' : ' ${component.classes}'}',
      attributes: component.attributes,
      events: <String, void Function(web.Event)>{if (component.onKey != null) 'keydown': _key},
      <Widget>[
        ArcaneDisclosure(
          open: _shown,
          onOpenChange: _show,
          variant: DisclosureVariant.minimal,
          showTreeLines: false,
          summary: Row(gapSize: Gap.sm, children: <Widget>[
            ...heading,
            if (component.count != null) Text('${component.count}', color: TextColor.muted),
          ]),
          child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, spacing: 4, children: component.children),
        ),
      ],
    );
  }
}
