/// Inspector parts that follow the NPC's body type: the settings only that
/// type has (`horse`, `llama`, `frog`, `armadillo`, `dragon`) as typed
/// fields from the NPC schema, the fields another body left behind, and the
/// note that stands in for the skin picker.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/gui/gui_json.dart';
import '../../logic/inspect/schema_fields.dart';
import '../../logic/npc/npc_appearance.dart';
import '../../model/animus_document.dart';
import '../gui/gui_fields.dart';
import '../inspect/inspect_fields.dart';
import 'npc_section.dart';

/// Headings for each body type's own settings.
const Map<String, String> _titles = <String, String>{
  'horse': 'Horse',
  'llama': 'Llama',
  'frog': 'Frog',
  'armadillo': 'Armadillo',
  'dragon': 'Ender dragon',
};

/// The settings field of [type], edited with the schema's typed fields;
/// nothing for bodies without settings.
class BodySettingsEditor extends StatelessWidget {
  const BodySettingsEditor({required this.type, required this.doc, required this.schema, required this.onChanged, this.diagnostics = const <Diagnostic>[], super.key});

  final String type;

  /// The definition fields.
  final JsonMap doc;
  final SchemaView schema;

  /// The settings field and its new value, null to remove it.
  final void Function(String field, Object? value) onChanged;
  final List<Diagnostic> diagnostics;

  @override
  Widget build(BuildContext context) {
    final String? field = bodySettingsField(type);
    if (field == null) return const dom.div(<Widget>[]);
    final JsonMap value = asMap(doc[field]);
    final Map<String, Object?> node = schema.at(<JsonStep>[field], <String, Object?>{'type': type, field: value}) ?? const <String, Object?>{};
    if (node.isEmpty) return const dom.div(<Widget>[]);
    final String title = _titles[field] ?? field;
    return dom.div(attributes: <String, String>{'role': 'group', 'aria-label': '$title settings', 'data-settings': field}, <Widget>[
      NpcSubhead(title, help: asString(node['description'])),
      InspectObject(
        schema: schema,
        node: node,
        value: value,
        path: '\$.$field',
        diagnostics: diagnostics,
        onChanged: (JsonMap next) => onChanged(field, next.isEmpty ? null : next),
      ),
    ]);
  }
}

/// In place of the skin picker on a body that wears none: a note, and the
/// skin's refusal when a group still gives one, since it blocks this body.
class NoSkinNote extends StatelessWidget {
  const NoSkinNote({this.diagnostics = const <Diagnostic>[], super.key});

  final List<Diagnostic> diagnostics;

  @override
  Widget build(BuildContext context) => dom.div(<Widget>[
        const dom.p(classes: 'animus-gui-help', <Widget>[Component.text('Only player bodies wear skins.')]),
        for (final Diagnostic problem in diagnosticsAt(diagnostics, r'$.skin'))
          dom.span(classes: 'animus-gui-problem is-${problem.severity.name}', <Widget>[Component.text(problem.message)]),
      ]);
}

/// Fields left from another body type, each with a way to drop it, so a type
/// change never leaves a definition the server refuses.
class MisplacedFields extends StatelessWidget {
  const MisplacedFields({required this.fields, required this.onRemove, required this.onRemoveAll, super.key});

  final List<MisplacedField> fields;
  final void Function(String field) onRemove;
  final void Function() onRemoveAll;

  @override
  Widget build(BuildContext context) => dom.div(
        classes: 'animus-npc-misplaced',
        attributes: const <String, String>{'role': 'group', 'aria-label': 'Fields this body cannot show'},
        <Widget>[
          for (final MisplacedField field in fields)
            dom.div(key: ValueKey<String>('misplaced:${field.field}'), classes: 'animus-row', <Widget>[
              dom.span(classes: 'animus-gui-problem is-error', <Widget>[
                dom.code(<Widget>[Component.text(field.field)]),
                Component.text(' ${field.reason}'),
              ]),
              Button.ghost(label: 'Remove ${field.field}', size: ButtonSize.small, onPressed: () => onRemove(field.field)),
            ]),
          if (fields.length > 1) Button.ghost(label: 'Remove all ${fields.length}', size: ButtonSize.small, onPressed: onRemoveAll),
        ],
      );
}
