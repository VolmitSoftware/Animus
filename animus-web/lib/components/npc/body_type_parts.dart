/// Inspector parts that follow the NPC's body type: the settings only that
/// type has (`horse`, `sheep`, `villager`, `player` and the rest) as typed
/// fields from the NPC schema, the fields another body left behind, and the
/// note that stands in for the skin picker.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import '../../logic/gui/gui_json.dart';
import '../../logic/inspect/named_ids.dart' show bodiesInWords;
import '../../logic/inspect/schema_fields.dart';
import '../../logic/npc/npc_appearance.dart';
import '../../model/animus_document.dart';
import '../../ui/action_row.dart';
import '../../ui/field.dart';
import '../gui/gui_fields.dart';
import '../inspect/inspect_fields.dart';
import 'npc_parts.dart';
import '../../ui/stack.dart';
import '../../ui/problem_text.dart';

/// Headings for each body type's own settings; the rest use the body's name.
const Map<String, String> _titles = <String, String>{
  'dragon': 'Ender dragon',
  'player': 'Player',
  'zombieVillager': 'Zombie villager',
  'magmaCube': 'Magma cube',
  'tropicalFish': 'Tropical fish',
  'snowGolem': 'Snow golem',
  'polarBear': 'Polar bear',
  'copperGolem': 'Copper golem',
  'itemFrame': 'Item frame',
  'glowItemFrame': 'Glow item frame',
  'fallingBlock': 'Falling block',
  'endCrystal': 'End crystal',
  'blockDisplay': 'Block display',
  'itemDisplay': 'Item display',
  'textDisplay': 'Text display',
  'droppedItem': 'Dropped item',
};

String _title(String field) => _titles[field] ?? '${field[0].toUpperCase()}${field.substring(1)}';

/// The settings field of [type], edited with the schema's typed fields;
/// nothing for bodies without settings.
class BodySettingsEditor extends StatelessWidget {
  const BodySettingsEditor({required this.type, required this.doc, required this.schema, required this.onChanged, this.diagnostics = const <Diagnostic>[], this.heading = true, super.key});

  final String type;

  /// The definition fields.
  final JsonMap doc;
  final SchemaView schema;

  /// The settings field and its new value, null to remove it.
  final void Function(String field, Object? value) onChanged;
  final List<Diagnostic> diagnostics;

  /// Whether the settings sit under their own heading ("Villager").
  final bool heading;

  @override
  Widget build(BuildContext context) {
    final String? field = bodySettingsField(type);
    if (field == null) return const dom.div(<Widget>[]);
    final JsonMap value = asMap(doc[field]);
    final Map<String, Object?> node = schema.at(<JsonStep>[field], <String, Object?>{'type': type, field: value}) ?? const <String, Object?>{};
    if (node.isEmpty) return const dom.div(<Widget>[]);
    final String title = _title(field);
    return dom.div(attributes: <String, String>{'role': 'group', 'aria-label': '$title settings', 'data-settings': field}, <Widget>[
      if (heading) NpcHeading(title, tip: switch (asString(node['description'])) { final String tip => bodiesInWords(tip), null => null }),
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
  Widget build(BuildContext context) => Field(
        label: 'Skin',
        problems: diagnosticsAt(diagnostics, r'$.skin'),
        child: mutedText('Only player bodies wear skins.'),
      );
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
            ActionRow(
              key: ValueKey<String>('misplaced:${field.field}'),
              label: 'Left over ${field.field}',
              leading: <Widget>[
                problemText(field.reason),
              ],
              secondary: <RowAction>[RowAction('Remove ${field.field}', () => onRemove(field.field))],
            ),
          if (fields.length > 1) ActionRow(label: 'Left over fields', secondary: <RowAction>[RowAction('Remove all ${fields.length}', onRemoveAll)]),
        ],
      );
}
