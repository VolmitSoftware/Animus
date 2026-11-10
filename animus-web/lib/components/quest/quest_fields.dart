/// Pieces the quest editor's parts share: how they write a field, the
/// workspace NPCs a quest can name, labelled rows tied to their document
/// path, NPC pickers and the item field.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import '../../logic/gui/gui_json.dart';
import '../../logic/gui/gui_text.dart';
import '../../logic/npc/npc_paths.dart';
import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../gui/gui_fields.dart';
import '../mc/item_picker.dart';

/// Writes [value] at [steps] of the quest; null removes the field.
typedef QuestWrite = void Function(List<JsonStep> steps, Object? value, {String? coalesce});

/// The NPCs of the workspace by their `id` field, which quests name.
class QuestNpcs {
  QuestNpcs(Iterable<AnimusDocument> documents) {
    for (final AnimusDocument document in documents) {
      if (document.kind != DocumentKind.npc) continue;
      final JsonMap json = asMap(document.json);
      final String id = asString(json['id']) ?? npcLocalIdOf(document.id);
      if (_documents.containsKey(id)) continue;
      _documents[id] = document.id;
      final String name = guiPlainText(asString(json['name']) ?? '').trim();
      if (name.isNotEmpty && name != id) labels[id] = '$name ($id)';
      names[id] = name.isEmpty ? id : name;
    }
  }

  final Map<String, String> _documents = <String, String>{};

  /// Picker labels: the NPC's name with its id.
  final Map<String, String> labels = <String, String>{};

  /// The name players see, by id.
  final Map<String, String> names = <String, String>{};

  List<String> get ids => _documents.keys.toList()..sort();

  Set<String> get idSet => _documents.keys.toSet();

  /// The workspace document of NPC [id], to open it.
  String? documentOf(String id) => _documents[id];

  String nameOf(String id) => names[id] ?? id;
}

/// The diagnostics at [path] and inside it.
List<Diagnostic> problemsAt(List<Diagnostic> all, String path) => diagnosticsAt(all, path, nested: true);

/// A labelled row whose label points at the control made by [child].
Widget questRow(String label, String path, List<Diagnostic> all, Widget Function(String id) child, {String? tip, bool nested = true}) {
  final String id = guiPathId(path);
  return GuiRow(label: label, forId: id, tip: tip, problems: diagnosticsAt(all, path, nested: nested), child: child(id));
}

/// A document path from [steps].
String questPath(List<JsonStep> steps) => formatPath(steps);

/// An NPC picker over the workspace NPCs; an id the workspace lacks stays
/// listed so it can be read and replaced.
Widget npcPicker({required String id, required String label, required String? value, required QuestNpcs npcs, required void Function(String? id) onChanged, bool allowNone = false, String noneLabel = 'None'}) =>
    GuiSelect(
      id: id,
      hiddenLabel: label,
      value: value,
      options: npcs.ids,
      labels: npcs.labels,
      allowNone: allowNone,
      noneLabel: noneLabel,
      onChanged: onChanged,
    );

/// An item (`{id, count, components}`) at [steps]: the id with icon and
/// suggestions, and the count when [count] is set.
class QuestItemField extends StatelessWidget {
  const QuestItemField({required this.steps, required this.item, required this.onWrite, required this.label, this.count = false, this.placeholder = 'minecraft:stone', super.key});

  final List<JsonStep> steps;
  final JsonMap item;
  final QuestWrite onWrite;
  final String label;
  final bool count;
  final String placeholder;

  @override
  Widget build(BuildContext context) {
    final String path = questPath(steps);
    return dom.div(classes: 'animus-quest-item', <Widget>[
      ItemPicker(
        id: guiPathId(path),
        value: asString(item['id']) ?? '',
        label: label,
        placeholder: placeholder,
        onChanged: (String value) => onWrite(<JsonStep>[...steps, 'id'], value.trim().isEmpty ? null : value.trim(), coalesce: '$path.id'),
      ),
      if (count)
        GuiNumberInput(
          value: asInt(item['count']),
          label: '$label count',
          placeholder: '1',
          digits: 3,
          onChanged: (num? value) => onWrite(<JsonStep>[...steps, 'count'], value?.toInt(), coalesce: '$path.count'),
        ),
    ]);
  }
}
