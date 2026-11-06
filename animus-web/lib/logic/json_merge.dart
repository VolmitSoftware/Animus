import 'dart:math' as math;

import '../model/server_binding.dart';

/// Which side a conflicting field keeps.
enum MergeSide { mine, server }

/// Marks a key one side does not have.
class _Absent {
  const _Absent();
}

const Object absentValue = _Absent();

bool isAbsent(Object? value) => value is _Absent;

/// One field both sides changed differently. Lists merge whole, so a list
/// two people both edited is one conflict.
class MergeConflict {
  const MergeConflict(this.segments, this.base, this.mine, this.server);

  /// Object keys from the document root.
  final List<String> segments;

  /// Each side's value, or [absentValue].
  final Object? base;
  final Object? mine;
  final Object? server;

  String get path => jsonPathOf(segments);
}

class MergeResult {
  const MergeResult(this.merged, this.conflicts);

  /// The merged document, with each conflict holding the user's value until
  /// [resolveMerge] says otherwise.
  final Object? merged;
  final List<MergeConflict> conflicts;

  bool get clean => conflicts.isEmpty;
}

final RegExp _plainKey = RegExp(r'^[A-Za-z_][A-Za-z0-9_]*$');

/// `$.a.b`, quoting keys that are not identifiers: `$["town:greeter"]`.
String jsonPathOf(List<String> segments) {
  final StringBuffer path = StringBuffer(r'$');
  for (final String segment in segments) {
    if (_plainKey.hasMatch(segment)) {
      path.write('.$segment');
    } else {
      path.write('["${segment.replaceAll(r'\', r'\\').replaceAll('"', r'\"')}"]');
    }
  }
  return path.toString();
}

/// Three-way merge of decoded JSON documents: [base] is the server version
/// the draft started from, [mine] the draft, [server] the server's version
/// now. A field only one side changed takes that change; a field both
/// changed the same way takes it once; anything else is a conflict.
MergeResult mergeJson(Object? base, Object? mine, Object? server) {
  final List<MergeConflict> conflicts = <MergeConflict>[];
  final Object? merged = _merge(<String>[], base, mine, server, conflicts);
  return MergeResult(merged, conflicts);
}

Object? _merge(List<String> at, Object? base, Object? mine, Object? server, List<MergeConflict> conflicts) {
  if (_same(mine, server)) return mine;
  if (_same(base, mine)) return server;
  if (_same(base, server)) return mine;
  if (mine is Map<String, Object?> && server is Map<String, Object?> && (base is Map<String, Object?> || isAbsent(base))) {
    final Map<String, Object?> baseMap = base is Map<String, Object?> ? base : const <String, Object?>{};
    final Map<String, Object?> result = <String, Object?>{};
    final List<String> keys = <String>[
      ...mine.keys,
      for (final String key in server.keys)
        if (!mine.containsKey(key)) key,
    ];
    for (final String key in keys) {
      final Object? value = _merge(
        <String>[...at, key],
        baseMap.containsKey(key) ? baseMap[key] : absentValue,
        mine.containsKey(key) ? mine[key] : absentValue,
        server.containsKey(key) ? server[key] : absentValue,
        conflicts,
      );
      if (!isAbsent(value)) result[key] = value;
    }
    return result;
  }
  conflicts.add(MergeConflict(List<String>.unmodifiable(at), base, mine, server));
  return mine;
}

bool _same(Object? a, Object? b) {
  if (isAbsent(a) || isAbsent(b)) return isAbsent(a) && isAbsent(b);
  return jsonEquals(a, b);
}

/// The merged document with each conflict set to the chosen side (mine when
/// [choices] has no entry). A side that removed the field removes it.
Object? resolveMerge(MergeResult result, Map<String, MergeSide> choices) {
  Object? document = _copy(result.merged);
  for (final MergeConflict conflict in result.conflicts) {
    final MergeSide side = choices[conflict.path] ?? MergeSide.mine;
    final Object? value = side == MergeSide.mine ? conflict.mine : conflict.server;
    if (conflict.segments.isEmpty) {
      document = isAbsent(value) ? null : _copy(value);
      continue;
    }
    Object? parent = document;
    for (final String segment in conflict.segments.sublist(0, conflict.segments.length - 1)) {
      parent = parent is Map<String, Object?> ? parent[segment] : null;
    }
    if (parent is! Map<String, Object?>) continue;
    if (isAbsent(value)) {
      parent.remove(conflict.segments.last);
    } else {
      parent[conflict.segments.last] = _copy(value);
    }
  }
  return document;
}

Object? _copy(Object? value) {
  if (value is Map<String, Object?>) {
    return <String, Object?>{for (final MapEntry<String, Object?> entry in value.entries) entry.key: _copy(entry.value)};
  }
  if (value is List<Object?>) return <Object?>[for (final Object? item in value) _copy(item)];
  return value;
}

/// [mine], an edit worked out from [shown], laid over [latest], the document
/// as it stands now: whatever [mine] left as [shown] takes [latest]'s value,
/// so an edit saved after [shown] was drawn is kept. Lists merge item by item
/// when all three are the same length, otherwise as one splice each; where
/// both changed the same place, [mine] wins. The value at [written] (object
/// keys and list indexes from the root) is [mine]'s even when it equals
/// [shown]'s, so writing a field back to what was shown still counts.
Object? rebaseJson(Object? shown, Object? mine, Object? latest, {List<Object>? written}) {
  final Object? rebased = _rebase(shown, mine, latest, written);
  return isAbsent(rebased) ? null : rebased;
}

Object? _rebase(Object? shown, Object? mine, Object? latest, List<Object>? written) {
  if (written != null && written.isEmpty && mine is! Map<String, Object?> && mine is! List<Object?>) return mine;
  if (written == null && _same(shown, mine)) return latest;
  if (_same(shown, latest) || _same(mine, latest)) return mine;
  final Object? step = written == null || written.isEmpty ? null : written.first;
  List<Object>? rest(Object at) => step == at ? written!.sublist(1) : null;
  if (mine is Map<String, Object?> && latest is Map<String, Object?>) {
    final Map<String, Object?> before = shown is Map<String, Object?> ? shown : const <String, Object?>{};
    final Map<String, Object?> result = <String, Object?>{};
    for (final String key in <String>[...mine.keys, for (final String key in latest.keys) if (!mine.containsKey(key)) key]) {
      final Object? value = _rebase(
        before.containsKey(key) ? before[key] : absentValue,
        mine.containsKey(key) ? mine[key] : absentValue,
        latest.containsKey(key) ? latest[key] : absentValue,
        rest(key),
      );
      if (!isAbsent(value)) result[key] = value;
    }
    return result;
  }
  if (mine is List<Object?> && latest is List<Object?> && shown is List<Object?>) {
    if (mine.length == shown.length && latest.length == shown.length) {
      return <Object?>[for (int index = 0; index < mine.length; index++) _rebase(shown[index], mine[index], latest[index], rest(index))];
    }
    return _spliced(shown, latest, mine) ?? mine;
  }
  return mine;
}

/// One side's change to a list: [shown]'s items from `start` to `end` became
/// `items`.
typedef _Splice = ({int start, int end, List<Object?> items});

_Splice _splice(List<Object?> shown, List<Object?> other) {
  final int shorter = math.min(shown.length, other.length);
  int prefix = 0;
  while (prefix < shorter && _same(shown[prefix], other[prefix])) {
    prefix++;
  }
  int suffix = 0;
  while (prefix + suffix < shorter && _same(shown[shown.length - 1 - suffix], other[other.length - 1 - suffix])) {
    suffix++;
  }
  return (start: prefix, end: shown.length - suffix, items: other.sublist(prefix, other.length - suffix));
}

/// Both sides' splices applied to [shown], [latest]'s first where they meet,
/// or null when they touch the same items.
List<Object?>? _spliced(List<Object?> shown, List<Object?> latest, List<Object?> mine) {
  final _Splice theirs = _splice(shown, latest), ours = _splice(shown, mine);
  if (theirs.start < ours.end && ours.start < theirs.end) return null;
  final bool theirsFirst = theirs.start < ours.start || (theirs.start == ours.start && theirs.end <= ours.end);
  final _Splice first = theirsFirst ? theirs : ours, second = theirsFirst ? ours : theirs;
  return <Object?>[
    ...shown.sublist(0, first.start),
    ...first.items,
    ...shown.sublist(first.end, second.start),
    ...second.items,
    ...shown.sublist(second.end),
  ];
}
