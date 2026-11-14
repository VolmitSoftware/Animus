/// Pieces the quest page's tabs share: how they write a field, the
/// workspace NPCs a quest can name, labelled rows tied to their document
/// path, the NPC picker and the item field.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:web/web.dart' as web;

import '../../logic/diagnostic_text.dart';
import '../../logic/gui/gui_json.dart';
import '../../logic/gui/gui_text.dart';
import '../../logic/npc/npc_paths.dart';
import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../../model/document_summary.dart';
import '../../services/browser.dart' show navigate;
import '../../state/library.dart';
import '../../state/workspace_route.dart';
import '../../ui/advanced_in_use.dart';
import '../../ui/alert.dart';
import '../gui/gui_fields.dart';
import '../mc/item_picker.dart';
import '../select/animus_select.dart';
import '../../ui/field.dart';
import '../../ui/number_input.dart';
import '../gui/gui_stack.dart';

/// Writes [value] at [steps] of the quest; null removes the field.
typedef QuestWrite = void Function(List<JsonStep> steps, Object? value, {String? coalesce});

/// The NPCs of the workspace by their `id` field, which quests name.
class QuestNpcs {
  QuestNpcs(Iterable<AnimusDocument> documents) {
    _read(documents);
  }

  QuestNpcs._();

  static List<AnimusDocument> _lastNpcs = const <AnimusDocument>[];
  static List<LibraryEntry> _lastLibrary = const <LibraryEntry>[];
  static QuestNpcs? _last;

  /// The NPCs of [documents], then those of [library] not read yet (a
  /// server lists them before they load), built again only when an NPC
  /// changed (a keystroke in the quest reuses the last answer).
  static QuestNpcs of(List<AnimusDocument> documents, {List<LibraryEntry> library = const <LibraryEntry>[]}) {
    final List<AnimusDocument> npcs = <AnimusDocument>[for (final AnimusDocument document in documents) if (document.kind == DocumentKind.npc) document];
    final QuestNpcs? last = _last;
    if (last != null && identical(library, _lastLibrary) && npcs.length == _lastNpcs.length) {
      bool same = true;
      for (int index = 0; index < npcs.length && same; index++) {
        same = identical(npcs[index], _lastNpcs[index]);
      }
      if (same) return last;
    }
    _lastNpcs = npcs;
    _lastLibrary = library;
    return _last = (QuestNpcs._().._read(npcs, library: library));
  }

  void _read(Iterable<AnimusDocument> documents, {List<LibraryEntry> library = const <LibraryEntry>[]}) {
    for (final AnimusDocument document in documents) {
      if (document.kind != DocumentKind.npc) continue;
      final JsonMap json = asMap(document.json);
      final String id = asString(json['id']) ?? npcLocalIdOf(document.id);
      if (_documents.containsKey(id)) continue;
      _documents[id] = document.id;
      final String name = guiPlainText(asString(json['name']) ?? '').trim();
      names[id] = name.isEmpty ? id : name;
      final JsonMap position = asMap(json['position']);
      final String world = asString(position['world']) ?? '';
      final List<String> xyz = <String>[for (final String axis in const <String>['x', 'y', 'z']) if (position[axis] case final num value) '${value.round()}'];
      if (xyz.length == 3) places[id] = '${world.isEmpty ? '' : '$world '}${xyz.join(', ')}';
    }
    // NPCs a server lists that are not read yet.
    for (final LibraryEntry entry in library) {
      if (entry.kind != DocumentKind.npc || entry.loaded) continue;
      final String id = npcLocalIdOf(entry.id);
      if (_documents.containsKey(id)) continue;
      _documents[id] = entry.id;
      names[id] = entry.name;
      final DocumentSummary summary = entry.summary;
      if (summary.x != null && summary.y != null && summary.z != null) {
        places[id] = '${summary.world == null ? '' : '${summary.world} '}${summary.x!.round()}, ${summary.y!.round()}, ${summary.z!.round()}';
      }
    }
    final Map<String, int> seen = <String, int>{};
    for (final String name in names.values) {
      seen[name] = (seen[name] ?? 0) + 1;
    }
    for (final MapEntry<String, String> entry in names.entries) {
      if (seen[entry.value]! > 1) _shared.add(entry.key);
    }
  }

  final Map<String, String> _documents = <String, String>{};

  /// The name players see, by id.
  final Map<String, String> names = <String, String>{};

  /// Where each placed NPC stands ("world 12, 64, -3"), by id.
  final Map<String, String> places = <String, String>{};

  /// Ids whose name another NPC shares.
  final Set<String> _shared = <String>{};

  /// The picker's second line for NPC [id]: its id in Advanced; in Simple
  /// where it stands, only when another NPC has the same name.
  String? detailOf(String id, {required bool advanced}) {
    if (advanced) return nameOf(id) == id ? null : id;
    return _shared.contains(id) ? places[id] : null;
  }

  /// Every NPC id, sorted.
  late final List<String> ids = _documents.keys.toList()..sort();

  late final Set<String> idSet = _documents.keys.toSet();

  /// The workspace document of NPC [id], to open it.
  String? documentOf(String id) => _documents[id];

  String nameOf(String id) => names[id] ?? id;
}

/// Opens the page of NPC [id] in the workspace the quest is open in.
void openQuestNpc(QuestNpcs npcs, String id) {
  final String? document = npcs.documentOf(id);
  final EditorRoute here = EditorRoute.parse(web.window.location.hash);
  if (document == null || here is! DocumentRoute) return;
  navigate(DocumentRoute(here.workspaceId, DocumentKind.npc, document).format());
}

/// [problem] as an alert at the top of a tab or row.
Widget questAlert(Diagnostic problem) => AppAlert(
      tone: switch (problem.severity) {
        DiagnosticSeverity.error => AlertTone.error,
        DiagnosticSeverity.warning => AlertTone.warning,
        _ => AlertTone.info,
      },
      message: diagnosticSentence(problem),
    );

/// The line a Simple tab ends with when it hides Advanced settings that
/// are set, or that have errors: the kit's one line, nothing when neither.
Widget questHidden({required int hidden, required int errors, required void Function() onShow}) =>
    AdvancedInUse(count: hidden > errors ? hidden : errors, problems: errors, onShow: onShow);

/// The diagnostics at [path] and inside it.
List<Diagnostic> problemsAt(List<Diagnostic> all, String path) => diagnosticsAt(all, path, nested: true);

/// A labelled row whose label points at the control made by [child].
Widget questRow(String label, String path, List<Diagnostic> all, Widget Function(String id) child, {String? tip, bool nested = true}) {
  final String id = guiPathId(path);
  return Field(label: label, forId: id, tip: tip, problems: diagnosticsAt(all, path, nested: nested), child: child(id));
}

/// A document path from [steps].
String questPath(List<JsonStep> steps) => formatPath(steps);

/// A searchable NPC picker over the workspace NPCs by the names players
/// see (their ids under the names in Advanced; in Simple where they stand
/// when two share a name); an id the workspace lacks stays listed so it can
/// be read and replaced. [exclude] leaves out NPCs
/// already picked elsewhere in the list.
Widget npcPicker({
  required String? id,
  required String label,
  required String? value,
  required QuestNpcs npcs,
  required void Function(String? id) onChanged,
  bool advanced = false,
  bool allowNone = false,
  String noneLabel = 'None',
  Set<String> exclude = const <String>{},
}) =>
    AnimusSelect.values(
      id: id,
      hiddenLabel: label,
      value: value,
      values: <String>[for (final String npc in npcs.ids) if (npc == value || !exclude.contains(npc)) npc],
      labels: npcs.names,
      details: <String, String>{
        for (final String npc in npcs.ids)
          if (npcs.detailOf(npc, advanced: advanced) case final String detail) npc: detail,
      },
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
    return fieldLine(<Widget>[
      ItemPicker(
        id: guiPathId(path),
        value: asString(item['id']) ?? '',
        label: label,
        placeholder: placeholder,
        onChanged: (String value) => onWrite(<JsonStep>[...steps, 'id'], value.trim().isEmpty ? null : value.trim(), coalesce: '$path.id'),
      ),
      if (count)
        NumberInput(
          value: asInt(item['count']),
          label: '$label count',
          placeholder: '1',
          digits: 3,
          onChanged: (num? value) => onWrite(<JsonStep>[...steps, 'count'], value?.toInt(), coalesce: '$path.count'),
        ),
    ]);
  }
}
