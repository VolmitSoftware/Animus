/// Bulk edits on selected NPCs. Each operation returns every document it
/// writes, so the browser commits them as one change set (one undo step,
/// one server apply), and says which NPCs it left alone and why.
library;

import '../../model/document_kind.dart';
import '../../state/editor_store.dart';
import '../gui/gui_json.dart';
import 'group_model.dart';
import 'npc_catalog.dart';
import 'npc_paths.dart';

/// What a bulk operation would write.
class NpcBulkResult {
  const NpcBulkResult(this.changes, this.skipped, this.changed);

  final List<DocumentChange> changes;

  /// NPC key to the reason it was left alone.
  final Map<String, String> skipped;

  /// How many NPCs change (a move writes two documents for one NPC).
  final int changed;
}

NpcBulkResult _each(List<NpcEntry> entries, JsonMap? Function(NpcEntry entry, Map<String, String> skipped) edit) {
  final List<DocumentChange> changes = <DocumentChange>[];
  final Map<String, String> skipped = <String, String>{};
  for (final NpcEntry entry in entries) {
    final JsonMap? next = edit(entry, skipped);
    if (next == null) continue;
    changes.add(DocumentChange(DocumentKind.npc, entry.key, prettyJson(next)));
  }
  return NpcBulkResult(changes, skipped, changes.length);
}

JsonMap _copy(NpcEntry entry) => asMap(deepCopy(entry.json));

/// Adds [tag] to every NPC that lacks it.
NpcBulkResult addTag(List<NpcEntry> entries, String tag) {
  if (!npcTagPattern.hasMatch(tag)) {
    return NpcBulkResult(const <DocumentChange>[], <String, String>{for (final NpcEntry entry in entries) entry.key: 'Tags use 1-32 of a-z, 0-9, _ and -.'}, 0);
  }
  return _each(entries, (NpcEntry entry, Map<String, String> skipped) {
    final List<String> tags = entry.tags;
    if (tags.contains(tag)) {
      skipped[entry.key] = 'Already tagged $tag.';
      return null;
    }
    if (tags.length >= npcTagLimit) {
      skipped[entry.key] = 'Already has $npcTagLimit tags.';
      return null;
    }
    return _copy(entry)..['tags'] = <String>[...tags, tag];
  });
}

/// Removes [tag] from every NPC that has it.
NpcBulkResult removeTag(List<NpcEntry> entries, String tag) => _each(entries, (NpcEntry entry, Map<String, String> skipped) {
  final List<String> tags = entry.tags;
  if (!tags.contains(tag)) {
    skipped[entry.key] = 'Not tagged $tag.';
    return null;
  }
  final List<String> rest = <String>[...tags]..remove(tag);
  final JsonMap next = _copy(entry);
  if (rest.isEmpty) {
    next.remove('tags');
  } else {
    next['tags'] = rest;
  }
  return next;
});

/// The two document changes that move [entry] to [folder]: the old file
/// goes, the new one keeps the definition (and its uuid) with `group` set to
/// the folder. On a server workspace the next apply sends them as one move.
List<DocumentChange> _moveChanges(NpcEntry entry, String folder) {
  final String key = npcIdIn(folder, entry.localId);
  final JsonMap next = _copy(entry)..['group'] = folder;
  return <DocumentChange>[
    DocumentChange(DocumentKind.npc, entry.key, null),
    DocumentChange(DocumentKind.npc, key, prettyJson(next), path: documentPath(DocumentKind.npc, key)),
  ];
}

/// Moves NPCs to `npcs/<folder>/` (the empty folder is `npcs/` itself): the
/// definition's `group` and its file follow, the id stays. [taken] holds
/// every NPC key in the workspace.
NpcBulkResult moveToFolder(List<NpcEntry> entries, String folder, Set<String> taken) {
  final String? problem = npcFolderProblem(folder);
  if (problem != null) {
    return NpcBulkResult(const <DocumentChange>[], <String, String>{for (final NpcEntry entry in entries) entry.key: problem}, 0);
  }
  final List<DocumentChange> changes = <DocumentChange>[];
  final Map<String, String> skipped = <String, String>{};
  final Set<String> claimed = <String>{...taken};
  for (final NpcEntry entry in entries) {
    if (entry.folder == folder) {
      skipped[entry.key] = 'Already in npcs/${folder.isEmpty ? '' : '$folder/'}.';
      continue;
    }
    final String key = npcIdIn(folder, entry.localId);
    if (claimed.contains(key)) {
      skipped[entry.key] = '$key already exists.';
      continue;
    }
    claimed
      ..remove(entry.key)
      ..add(key);
    changes.addAll(_moveChanges(entry, folder));
  }
  return NpcBulkResult(changes, skipped, changes.length ~/ 2);
}

/// What a folder rename or move would write, or why it cannot.
class NpcFolderEdit {
  const NpcFolderEdit(this.changes, this.moved, {this.problem});

  final List<DocumentChange> changes;

  /// How many NPCs move.
  final int moved;

  /// Why nothing is written, in the server's words where the server has
  /// some; null when the edit can go ahead.
  final String? problem;

  static NpcFolderEdit refused(String problem) => NpcFolderEdit(const <DocumentChange>[], 0, problem: problem);
}

/// Renames folder [from] to [to]: every NPC in [from] and the folders below
/// it moves along, so `town/market/baker` becomes `<to>/market/baker` for a
/// rename of `town`. All of them move or none. [entries] is every NPC of the
/// workspace.
NpcFolderEdit renameNpcFolder(List<NpcEntry> entries, String from, String to) {
  if (from.isEmpty) return NpcFolderEdit.refused('npcs/ itself cannot be renamed or moved.');
  if (to == from) return NpcFolderEdit.refused('npcs/$from/ already has that name.');
  if (to.isEmpty) return NpcFolderEdit.refused('Give the folder a name.');
  final String? problem = npcFolderProblem(to);
  if (problem != null) return NpcFolderEdit.refused(problem);
  if (npcFolderWithin(to, from)) return NpcFolderEdit.refused('npcs/$from/ cannot move into itself.');
  final Set<String> keys = <String>{for (final NpcEntry entry in entries) entry.key};
  final List<NpcEntry> inside = <NpcEntry>[
    for (final NpcEntry entry in entries)
      if (npcFolderWithin(entry.folder, from)) entry,
  ];
  final List<DocumentChange> changes = <DocumentChange>[];
  for (final NpcEntry entry in inside) {
    final String folder = '$to${entry.folder.substring(from.length)}';
    final String? deeper = npcFolderProblem(folder);
    if (deeper != null) return NpcFolderEdit.refused('${entry.key} would land in $deeper');
    final String key = npcIdIn(folder, entry.localId);
    if (keys.contains(key)) return NpcFolderEdit.refused('$key already exists.');
    changes.addAll(_moveChanges(entry, folder));
  }
  return NpcFolderEdit(changes, inside.length);
}

/// Moves folder [from] (with its subtree) into [parent]; the empty parent
/// is `npcs/` itself.
NpcFolderEdit moveNpcFolder(List<NpcEntry> entries, String from, String parent) =>
    renameNpcFolder(entries, from, npcIdIn(parent, npcFolderName(from)));

/// Switches a module on or off. A module the NPC lacks is added only when
/// `{"enabled": true}` is a whole valid setting ([creatableModules]).
NpcBulkResult setModuleEnabled(List<NpcEntry> entries, String module, bool enabled) => _each(entries, (NpcEntry entry, Map<String, String> skipped) {
  final Object? current = entry.modules[module];
  if (current == null) {
    if (!enabled) {
      skipped[entry.key] = 'Has no $module.';
      return null;
    }
    if (!creatableModules.contains(module)) {
      skipped[entry.key] = 'Has no $module to switch on; configure it on the NPC first.';
      return null;
    }
  } else if (current is! Map<String, Object?>) {
    skipped[entry.key] = '$module is not an object.';
    return null;
  } else if (current['enabled'] == enabled) {
    skipped[entry.key] = '$module is already ${enabled ? 'on' : 'off'}.';
    return null;
  }
  final JsonMap next = _copy(entry);
  final JsonMap modules = Map<String, Object?>.of(asMap(next['modules']));
  modules[module] = <String, Object?>{...asMap(current), 'enabled': enabled};
  next['modules'] = modules;
  return next;
});

/// Removes a module's settings.
NpcBulkResult removeModule(List<NpcEntry> entries, String module) => _each(entries, (NpcEntry entry, Map<String, String> skipped) {
  if (!entry.modules.containsKey(module)) {
    skipped[entry.key] = 'Has no $module.';
    return null;
  }
  final JsonMap next = _copy(entry);
  final JsonMap modules = Map<String, Object?>.of(asMap(next['modules']))..remove(module);
  if (modules.isEmpty) {
    next.remove('modules');
  } else {
    next['modules'] = modules;
  }
  return next;
});

/// Sets the definitions' `spawned` flag.
NpcBulkResult setSpawned(List<NpcEntry> entries, bool spawned) => _each(entries, (NpcEntry entry, Map<String, String> skipped) {
  if (entry.json['spawned'] == spawned) {
    skipped[entry.key] = 'Already ${spawned ? 'spawned' : 'despawned'}.';
    return null;
  }
  return _copy(entry)..['spawned'] = spawned;
});

/// Deletes the definitions.
NpcBulkResult deleteNpcs(List<NpcEntry> entries) => NpcBulkResult(
  <DocumentChange>[for (final NpcEntry entry in entries) DocumentChange(DocumentKind.npc, entry.key, null)],
  const <String, String>{},
  entries.length,
);

/// Every module key used by [entries] or offered for toggling, sorted with
/// the toggleable ones first.
List<String> moduleKeys(Iterable<NpcEntry> entries) {
  final Set<String> used = <String>{for (final NpcEntry entry in entries) ...entry.modules.keys};
  return <String>[...toggleableModules, ...(used.difference(toggleableModules.toSet()).toList()..sort())];
}

/// One line for a toast: what changed and what was skipped.
String describeBulk(String action, NpcBulkResult result) {
  final int changed = result.changed;
  final String head = changed == 0 ? 'Nothing to change' : '$action $changed NPC${changed == 1 ? '' : 's'}';
  if (result.skipped.isEmpty) return '$head.';
  final Set<String> reasons = result.skipped.values.toSet();
  return '$head; skipped ${result.skipped.length}: ${reasons.take(3).join(' ')}';
}

/// Which NPCs a group assign or unassign reaches, the server's
/// `folder=`, `tag=` and `ids=` selectors.
class NpcGroupTarget {
  const NpcGroupTarget._(this.folder, this.tag, this.keys);

  /// NPCs whose folder under `npcs/` is exactly [folder] (`''` is `npcs/`).
  const NpcGroupTarget.folder(String folder) : this._(folder, null, null);

  /// NPCs whose effective tags (their own and their groups') include [tag].
  const NpcGroupTarget.tag(String tag) : this._(null, tag, null);

  /// The NPCs with these workspace keys.
  const NpcGroupTarget.ids(Set<String> keys) : this._(null, null, keys);

  final String? folder;
  final String? tag;
  final Set<String>? keys;

  bool matches(NpcEntry entry, Map<String, JsonMap> groups) {
    if (folder case final String folder) return entry.folder == folder;
    if (tag case final String tag) return jsonStrings((effectiveNpc(entry.json, groups)?.json ?? entry.json)['tags']).contains(tag);
    return keys!.contains(entry.key);
  }
}

/// Adds [group] to the end of each matching NPC's `groups` ([assign]) or
/// removes it, as `/animus groups assign` and `unassign` do. NPCs already in
/// the wanted state count as unchanged ([NpcBulkResult.skipped]). Like the
/// server, an assign of a group the workspace lacks, or one that would give
/// any NPC more than [npcGroupLimit] groups, writes nothing.
NpcBulkResult changeGroup(List<NpcEntry> entries, Map<String, JsonMap> groups, String group, NpcGroupTarget target, {required bool assign}) {
  final List<NpcEntry> matched = <NpcEntry>[
    for (final NpcEntry entry in entries)
      if (target.matches(entry, groups)) entry,
  ];
  if (assign && !groups.containsKey(group)) {
    return NpcBulkResult(const <DocumentChange>[], <String, String>{for (final NpcEntry entry in matched) entry.key: 'No group $group in this workspace.'}, 0);
  }
  final Map<String, String> full = <String, String>{
    if (assign)
      for (final NpcEntry entry in matched)
        if (!npcGroups(entry.json).contains(group) && npcGroups(entry.json).length >= npcGroupLimit) entry.key: 'Already lists $npcGroupLimit groups.',
  };
  if (full.isNotEmpty) return NpcBulkResult(const <DocumentChange>[], full, 0);
  return _each(matched, (NpcEntry entry, Map<String, String> skipped) {
    final List<String> listed = npcGroups(entry.json);
    if (listed.contains(group) == assign) {
      skipped[entry.key] = assign ? 'Already lists $group.' : 'Does not list $group.';
      return null;
    }
    final List<String> next = <String>[
      for (final String id in listed)
        if (id != group) id,
      if (assign) group,
    ];
    final JsonMap json = _copy(entry);
    if (next.isEmpty) {
      json.remove('groups');
    } else {
      json['groups'] = next;
    }
    return json;
  });
}

/// One line for a toast, worded like the server's reply: how many changed
/// and how many already were as asked, or why nothing was written.
String describeGroupChange(String group, NpcBulkResult result, {required bool assign}) {
  final bool refused = result.changes.isEmpty && result.skipped.values.any((String reason) => !reason.startsWith('Already lists $group') && !reason.startsWith('Does not list'));
  if (refused) return 'Nothing changed: ${result.skipped.values.toSet().take(2).join(' ')}';
  if (result.changed == 0 && result.skipped.isEmpty) return 'No NPC matches, so nothing changed.';
  return '${assign ? 'Assigned' : 'Unassigned'} $group: ${result.changed} changed, ${result.skipped.length} unchanged.';
}
