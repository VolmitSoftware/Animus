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
