/// A trait group's members as the group screen lists them: the NPCs whose
/// `groups` name it, and the NPCs that could join.
library;

import '../gui/gui_json.dart';
import 'group_model.dart';
import 'npc_bulk.dart';
import 'npc_catalog.dart';

/// NPCs whose `groups` list [group], in workspace order.
List<NpcEntry> groupMembers(List<NpcEntry> entries, String group) => <NpcEntry>[
  for (final NpcEntry entry in entries)
    if (npcGroups(entry.json).contains(group)) entry,
];

/// NPCs that do not list [group] and have room for one more group, by key.
List<NpcEntry> groupCandidates(List<NpcEntry> entries, String group) => <NpcEntry>[
  for (final NpcEntry entry in entries)
    if (!npcGroups(entry.json).contains(group) && npcGroups(entry.json).length < npcGroupLimit) entry,
]..sort((NpcEntry a, NpcEntry b) => a.key.compareTo(b.key));

/// Appends [group] to NPC [key]'s `groups`, the same write as the NPC
/// browser's bulk assign.
NpcBulkResult addGroupMember(List<NpcEntry> entries, Map<String, JsonMap> groups, String group, String key) =>
    changeGroup(entries, groups, group, NpcGroupTarget.ids(<String>{key}), assign: true);

/// Drops [group] from NPC [key]'s `groups`, as `/animus groups unassign` does.
NpcBulkResult removeGroupMember(List<NpcEntry> entries, Map<String, JsonMap> groups, String group, String key) =>
    changeGroup(entries, groups, group, NpcGroupTarget.ids(<String>{key}), assign: false);
