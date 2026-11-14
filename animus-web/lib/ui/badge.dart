/// State labels on shadcn's status badge: Valid, 3 problems, Changed,
/// Spawned. A badge is text first; colour only repeats what it says.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;
import 'package:web/web.dart' as web;

import 'tone.dart';

class StatusBadge extends StatelessWidget {
  const StatusBadge(this.label, {this.tone = BadgeTone.neutral, this.dot = false, this.attributes = const <String, String>{}, super.key}) : dotOnly = false;

  /// Only the coloured dot, where a bar has no room for the words; the label
  /// is still read out and is the tooltip.
  const StatusBadge.dotOnly(this.label, {this.tone = BadgeTone.neutral, this.attributes = const <String, String>{}, super.key})
      : dot = true,
        dotOnly = true;

  final String label;
  final BadgeTone tone;

  /// A leading dot for live state (online, applying); off for labels.
  final bool dot;
  final bool dotOnly;

  /// Kept on a wrapper (the library badge takes none): `role`, `data-*`.
  final Map<String, String> attributes;

  @override
  Widget build(BuildContext context) {
    final Widget badge = ArcaneStatusBadge(
      label: dotOnly ? '' : label,
      size: ComponentSize.sm,
      // Info ("New", "Not applied", "Starts here") is the theme's secondary
      // badge: Midnight has no blue.
      variant: switch (tone) {
        BadgeTone.neutral => BadgeVariant.outline,
        BadgeTone.info => BadgeVariant.secondary,
        _ => BadgeVariant.status,
      },
      status: switch (tone) {
        BadgeTone.ok => StatusType.success,
        BadgeTone.warning => StatusType.warning,
        BadgeTone.error => StatusType.error,
        BadgeTone.info || BadgeTone.neutral => StatusType.info,
      },
      showDot: dot,
      background: dotOnly ? 'transparent' : null,
      borderColor: dotOnly ? 'transparent' : null,
    );
    if (dotOnly) {
      return dom.span(classes: 'ui-badge is-${tone.name}', attributes: <String, String>{...attributes, 'title': label}, <Widget>[
        badge,
        dom.span(classes: 'animus-sr-only', <Widget>[Component.text(label)]),
      ]);
    }
    return dom.span(classes: 'ui-badge is-${tone.name}', attributes: attributes, <Widget>[badge]);
  }
}

/// A badge that opens what it counts (the problem list from "3 problems").
class BadgeButton extends StatelessWidget {
  const BadgeButton(this.label, {required this.onPressed, this.tone = BadgeTone.neutral, this.expanded, this.controls, super.key});

  final String label;
  final BadgeTone tone;
  final void Function() onPressed;

  /// `aria-expanded` and `aria-controls` for a badge that shows a panel.
  final bool? expanded;
  final String? controls;

  @override
  Widget build(BuildContext context) => dom.button(
        classes: 'ui-badge-button',
        attributes: <String, String>{
          'type': 'button',
          if (expanded != null) 'aria-expanded': '$expanded',
          'aria-controls': ?controls,
        },
        events: <String, void Function(web.Event)>{'click': (web.Event _) => onPressed()},
        <Widget>[StatusBadge(label, tone: tone)],
      );
}
