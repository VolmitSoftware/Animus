/// Small pieces every NPC tab uses: a heading over a group of fields, the
/// "n advanced settings" line Simple shows, the tags field and the
/// read-only value row.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../config/docs_links.dart';
import '../gui/gui_fields.dart' show diagnosticsAt;
import '../../logic/inspect/schema_fields.dart';
import '../../logic/npc/npc_catalog.dart';
import '../../model/animus_document.dart';
import '../../ui/action_row.dart';
import '../../ui/field.dart';
import '../../ui/info_tip.dart';
import '../select/suggest_input.dart';
import 'chip_row.dart';
import 'npc_layout.dart';
/// A heading over the fields that follow it in a tab.
class NpcHeading extends StatelessWidget {
  const NpcHeading(this.text, {this.tip, this.docs, super.key});

  final String text;
  final String? tip;
  final DocLink? docs;

  @override
  Widget build(BuildContext context) => dom.h3(styles: _style, <Widget>[
        TipLabel(label: Component.text(text), tip: tip, docs: docs),
      ]);

  static final dom.Styles _style = const ArcaneStyleData(marginStringCustom: '0.75rem 0 0', fontSize: FontSize.sm, fontWeight: FontWeight.w600).toStyles();
}

/// A select at the start of a row of actions: it takes the room the
/// buttons leave and shrinks with the row instead of pushing it wider.
class NpcPick extends StatelessWidget {
  const NpcPick({required this.child, super.key});

  final Widget child;

  @override
  Widget build(BuildContext context) => Expanded(child: child);
}

/// A value the form shows but does not edit (the id, the UUID).
class NpcValueRow extends StatelessWidget {
  const NpcValueRow({required this.label, required this.value, this.tip, this.problems = const <Diagnostic>[], super.key});

  final String label;
  final String value;
  final String? tip;
  final List<Diagnostic> problems;

  @override
  Widget build(BuildContext context) => Field(
        label: label,
        tip: tip,
        problems: problems,
        child: npcCode(value),
      );
}

/// The definition's `tags`: chips with a remove button, and a field that
/// adds one (suggesting the workspace's other tags).
class NpcTagsField extends StatefulWidget {
  const NpcTagsField({required this.tags, required this.known, required this.onChanged, this.diagnostics = const <Diagnostic>[], super.key});

  final List<String> tags;

  /// Tags the workspace uses, offered while typing; worked out on demand.
  final List<String> Function() known;

  /// The new list, null to drop the field.
  final void Function(List<String>? tags) onChanged;
  final List<Diagnostic> diagnostics;

  @override
  State<NpcTagsField> createState() => _NpcTagsFieldState();
}

class _NpcTagsFieldState extends State<NpcTagsField> {
  String _adding = '';
  List<String>? _known;

  bool get _valid => npcTagPattern.hasMatch(_adding) && !component.tags.contains(_adding) && component.tags.length < npcTagLimit;

  void _add() {
    if (!_valid) return;
    component.onChanged(<String>[...component.tags, _adding]);
    setState(() => _adding = '');
  }

  @override
  Widget build(BuildContext context) {
    final List<String> tags = component.tags;
    return Field(
      label: 'Tags',
      tip: 'Labels for finding NPCs, at most 32: a-z, 0-9, _ and -. Trait groups can add tags too.',
      problems: diagnosticsAt(component.diagnostics, r'$.tags', nested: true),
      child: dom.div(classes: 'animus-npc-tags', attributes: const <String, String>{'data-npc': 'tags'}, <Widget>[
        if (tags.isNotEmpty)
          ChipRow(
            noun: 'tag',
            attribute: 'tag',
            items: <ChipItem>[for (final String tag in tags) ChipItem(tag)],
            onRemove: (int index) {
              final List<String> next = <String>[...tags]..removeAt(index);
              component.onChanged(next.isEmpty ? null : next);
            },
          ),
        ActionRow(
          label: 'Add tag',
          leading: <Widget>[
            SuggestInput(
              value: _adding,
              label: 'New tag',
              suggestions: <FieldOption>[
                for (final String tag in _known ??= component.known())
                  if (!tags.contains(tag)) FieldOption(tag),
              ],
              onChanged: (String value) => setState(() => _adding = value.trim().toLowerCase()),
              onSubmit: _valid ? _add : null,
            ),
          ],
          secondary: <RowAction>[RowAction('Add tag', _valid ? _add : null)],
        ),
      ]),
    );
  }
}

/// [value] as text for a read-only row.
String npcValueText(Object? value) => value == null ? '-' : (value is String ? value : '$value');
