/// A collapsible part of the NPC inspector whose heading carries a one-line
/// summary, shown while the part is closed. On phone widths every part
/// but the first starts closed.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;
import 'package:web/web.dart' as web;

/// Whether the page is phone width, where the inspector starts collapsed.
bool get npcInspectorNarrow => web.window.matchMedia('(max-width: 520px)').matches;

class NpcSection extends StatelessWidget {
  const NpcSection({required this.title, required this.summary, required this.children, this.open = true, this.id, this.onToggle, super.key});

  final String title;

  /// What the part holds, in a few words, for the closed heading.
  final String summary;
  final List<Widget> children;
  final bool open;

  /// A stable id for tests and links.
  final String? id;

  /// Called when the reader opens or closes the part, so the owner keeps
  /// [open] in step and a rebuild does not undo it.
  final void Function(bool open)? onToggle;

  @override
  Widget build(BuildContext context) => dom.details(
    classes: 'animus-gui-section animus-npc-section',
    attributes: <String, String>{if (open) 'open': '', 'data-section': ?id},
    events: <String, void Function(web.Event)>{
      'toggle': (web.Event event) {
        final bool now = (event.target! as web.HTMLDetailsElement).open;
        if (now != open) onToggle?.call(now);
      },
    },
    <Widget>[
      dom.summary(<Widget>[
        dom.span(classes: 'animus-npc-section-title', <Widget>[Component.text(title)]),
        if (summary.isNotEmpty) dom.span(classes: 'animus-npc-section-summary', <Widget>[Component.text(summary)]),
      ]),
      dom.div(classes: 'animus-gui-section-body', children),
    ],
  );
}

/// A heading inside a section, for one group of fields.
class NpcSubhead extends StatelessWidget {
  const NpcSubhead(this.text, {this.help, super.key});

  final String text;
  final String? help;

  @override
  Widget build(BuildContext context) => dom.div(classes: 'animus-npc-subhead', <Widget>[
    dom.h4(<Widget>[Component.text(text)]),
    if (help != null) dom.p(classes: 'animus-gui-help', <Widget>[Component.text(help!)]),
  ]);
}
