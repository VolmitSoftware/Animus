/// Trait group merge, the same rules as the server's `GroupMerge`: groups
/// apply in list order, then the NPC's own fields. Objects merge key by key,
/// recursively; `behaviors` and `tags` append without duplicates; other arrays
/// and scalars replace. A group's `skins` pool sets one `skin` picked by a
/// stable hash of the NPC UUID, which the NPC's own `skin` still overrides.
/// Shared cases: schema/fixtures/groups/cases.
library;

import 'dart:convert';

import 'package:crypto/crypto.dart';

/// The provenance source of the NPC's own fields.
const String groupMergeNpc = 'npc';

/// Identity and state fields a group may not set; they only come from the NPC.
const Set<String> groupRefusedFields = <String>{
  'schemaVersion', 'revision', 'uuid', 'id', 'group', 'name', 'type', 'spawned', 'position', 'owner', 'migration', 'groups',
};

const Set<String> _appended = <String>{'behaviors', 'tags'};

/// The effective definition and, for top-level fields and `modules.<id>`
/// paths, the last source that set them: a group id or `npc`.
class GroupMergeResult {
  const GroupMergeResult(this.effective, this.provenance);

  final Map<String, Object?> effective;
  final Map<String, String> provenance;
}

/// Merges [npc] over [groups] (whole group documents, in the NPC's order).
GroupMergeResult mergeGroups(Map<String, Object?> npc, List<Map<String, Object?>> groups) {
  final String uuid = npc['uuid'] is String ? npc['uuid']! as String : '';
  final Map<String, Object?> merged = <String, Object?>{};
  final Map<String, String> provenance = <String, String>{};
  for (final Map<String, Object?> group in groups) {
    final Object? values = group['values'];
    if (values is Map<String, Object?>) _apply(merged, values, group['id']! as String, uuid, provenance);
  }
  _apply(merged, npc, groupMergeNpc, uuid, provenance);
  final Map<String, Object?> ordered = <String, Object?>{
    for (final String key in npc.keys) key: merged[key],
    for (final MapEntry<String, Object?> entry in merged.entries) if (!npc.containsKey(entry.key)) entry.key: entry.value,
  };
  final List<String> paths = provenance.keys.toList()..sort();
  return GroupMergeResult(ordered, <String, String>{for (final String path in paths) path: provenance[path]!});
}

/// The skin a pool gives an NPC: the first four bytes of SHA-256 over the UUID
/// text, unsigned, modulo the pool size.
String pickSkin(String uuid, List<String> skins) {
  final List<int> digest = sha256.convert(utf8.encode(uuid)).bytes;
  final int value = (digest[0] << 24) | (digest[1] << 16) | (digest[2] << 8) | digest[3];
  return skins[value % skins.length];
}

void _apply(Map<String, Object?> target, Map<String, Object?> source, String label, String uuid, Map<String, String> provenance) {
  for (final MapEntry<String, Object?> entry in source.entries) {
    final String key = entry.key;
    final Object? value = entry.value;
    if (key == 'skins' && label != groupMergeNpc && value is List<Object?>) {
      target['skin'] = pickSkin(uuid, value.cast<String>());
      provenance['skin'] = label;
      continue;
    }
    final Object? current = target[key];
    if (_appended.contains(key) && value is List<Object?> && current is List<Object?>) {
      for (final Object? item in value) {
        if (!current.any((Object? existing) => _same(existing, item))) current.add(_copy(item));
      }
    } else if (value is Map<String, Object?> && current is Map<String, Object?>) {
      _deep(current, value);
    } else {
      target[key] = _copy(value);
    }
    if (groupRefusedFields.contains(key)) continue;
    if (key == 'modules' && value is Map<String, Object?>) {
      for (final String module in value.keys) {
        provenance['modules.$module'] = label;
      }
    } else {
      provenance[key] = label;
    }
  }
}

void _deep(Map<String, Object?> target, Map<String, Object?> source) {
  for (final MapEntry<String, Object?> entry in source.entries) {
    final Object? current = target[entry.key];
    if (entry.value is Map<String, Object?> && current is Map<String, Object?>) {
      _deep(current, entry.value! as Map<String, Object?>);
    } else {
      target[entry.key] = _copy(entry.value);
    }
  }
}

bool _same(Object? a, Object? b) {
  if (a is Map<String, Object?> && b is Map<String, Object?>) {
    return a.length == b.length && a.entries.every((MapEntry<String, Object?> entry) => b.containsKey(entry.key) && _same(entry.value, b[entry.key]));
  }
  if (a is List<Object?> && b is List<Object?>) {
    if (a.length != b.length) return false;
    for (int i = 0; i < a.length; i++) {
      if (!_same(a[i], b[i])) return false;
    }
    return true;
  }
  return a == b;
}

Object? _copy(Object? value) {
  if (value is Map<String, Object?>) return <String, Object?>{for (final MapEntry<String, Object?> entry in value.entries) entry.key: _copy(entry.value)};
  if (value is List<Object?>) return <Object?>[for (final Object? item in value) _copy(item)];
  return value;
}
