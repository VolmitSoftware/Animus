/// Trait groups in the editor: the workspace's group documents, an NPC's
/// effective definition with the source of each value ([mergeGroups], the
/// same merge as the server's `GroupMerge`), and the browser-side checks the
/// server's `GroupDocument` makes on a group's values.
library;

import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../gui/gui_json.dart';
import '../shared_schemas.dart';
import 'group_merge.dart';

/// Group ids as the server accepts them.
final RegExp groupIdPattern = RegExp(r'^[a-z0-9][a-z0-9_-]{0,63}$');

/// At most this many groups on one NPC.
const int npcGroupLimit = 16;

/// Every group document of the workspace by id, in workspace order.
Map<String, JsonMap> workspaceGroups(List<AnimusDocument> documents) => <String, JsonMap>{
      for (final AnimusDocument document in documents)
        if (document.kind == DocumentKind.group && document.json is Map<String, Object?>) document.id: asMap(document.json),
    };

/// The `groups` an NPC lists, in merge order.
List<String> npcGroups(JsonMap npc) => jsonStrings(npc['groups']);

/// The strings of a JSON list, other items left out.
List<String> jsonStrings(Object? value) => <String>[for (final Object? item in asList(value)) if (item is String) item];

/// One line of the Effective view: a top-level field or a `modules.<id>`
/// path, its merged value and the group id or `npc` that set it last.
class EffectiveRow {
  const EffectiveRow(this.path, this.value, this.source);

  final String path;
  final Object? value;
  final String source;
}

/// An NPC's effective definition: its own fields over the groups it lists.
class NpcEffective {
  const NpcEffective(this.json, this.provenance, this.groups, this.missing);

  final JsonMap json;

  /// Path to source, as [GroupMergeResult.provenance].
  final Map<String, String> provenance;

  /// The groups listed, in order.
  final List<String> groups;

  /// Listed groups the workspace does not have; they are left out of the merge.
  final List<String> missing;

  /// Who set [path]; fields no one set (identity) come from the NPC.
  String sourceOf(String path) => provenance[path] ?? groupMergeNpc;

  /// Top-level fields in the effective order, with `modules` split into one
  /// row per module. Identity and state fields, which only the NPC sets, and
  /// `groups` itself are left out.
  List<EffectiveRow> get rows => <EffectiveRow>[
        for (final MapEntry<String, Object?> field in json.entries)
          if (field.key == 'modules' && field.value is Map<String, Object?>)
            for (final MapEntry<String, Object?> module in asMap(field.value).entries)
              EffectiveRow('modules.${module.key}', module.value, sourceOf('modules.${module.key}'))
          else if (!groupRefusedFields.contains(field.key))
            EffectiveRow(field.key, field.value, sourceOf(field.key)),
      ];
}

/// The effective definition of [npc] over [groups], or null when it lists none.
NpcEffective? effectiveNpc(JsonMap npc, Map<String, JsonMap> groups) {
  final List<String> listed = npcGroups(npc);
  if (listed.isEmpty) return null;
  final List<JsonMap> found = <JsonMap>[
    for (final String id in listed)
      if (groups[id] case final JsonMap group) <String, Object?>{...group, 'id': id},
  ];
  final GroupMergeResult result = mergeGroups(npc, found);
  return NpcEffective(result.effective, result.provenance, listed, <String>[
    for (final String id in listed)
      if (!groups.containsKey(id)) id,
  ]);
}

/// Warnings for listed groups the workspace does not have. The server refuses
/// an NPC naming a group it does not know, so these are worth a look before
/// an apply; the server may still have the group.
List<Diagnostic> npcGroupDiagnostics(JsonMap npc, Set<String> known) {
  final List<String> listed = npcGroups(npc);
  return <Diagnostic>[
    for (int index = 0; index < listed.length; index++)
      if (!known.contains(listed[index]))
        Diagnostic(
          DiagnosticSeverity.warning,
          '\$.groups[$index]',
          'unknown group ${listed[index]}: not in this workspace, and the server refuses it unless it has groups/${listed[index]}.json',
          code: 'group-unknown',
        ),
  ];
}

/// A group's values checked as NPC fields, the way `GroupDocument.validateValues`
/// does: the values go into a probe definition (an armor stand body when they
/// set `armorStand`, a player otherwise) and the NPC schema's errors come back
/// under `$.values`. Refused identity keys and `skins` are the group schema's
/// to report.
List<Diagnostic> groupValueDiagnostics(Object? json, SharedSchemas schemas) {
  if (json is! Map<String, Object?>) return const <Diagnostic>[];
  final Object? values = json['values'];
  if (values is! Map<String, Object?>) return const <Diagnostic>[];
  final JsonMap probe = <String, Object?>{
    'schemaVersion': 1,
    'revision': 1,
    'uuid': '00000000-0000-4000-8000-000000000000',
    'id': 'group-probe',
    'group': 'groups',
    'name': 'Group',
    'type': values.containsKey('armorStand') ? 'minecraft:armor_stand' : 'minecraft:player',
    'spawned': false,
    'position': <String, Object?>{'world': 'world', 'x': 0, 'y': 0, 'z': 0, 'yaw': 0, 'pitch': 0},
    for (final MapEntry<String, Object?> entry in values.entries)
      if (!groupRefusedFields.contains(entry.key) && entry.key != 'skins') entry.key: entry.value,
  };
  return <Diagnostic>[
    for (final Diagnostic problem in schemas.validate(DocumentKind.npc, probe))
      if (problem.path.startsWith(r'$.'))
        Diagnostic(problem.severity, '\$.values.${problem.path.substring(2)}', problem.message, code: 'group-value')
      else
        Diagnostic(problem.severity, r'$.values', problem.message, code: 'group-value'),
  ];
}
