/// Small immutable JSON helpers the GUI editors share: typed reads, path
/// strings in the validator's `$.a.b[0]` form, and copy-on-write updates
/// that keep every key the editor does not touch.
library;

import 'dart:convert';

import '../json_merge.dart';

typedef JsonMap = Map<String, Object?>;
typedef JsonList = List<Object?>;

/// One step of a path: a [String] key or an [int] index.
typedef JsonStep = Object;

JsonMap asMap(Object? value) => value is Map<String, Object?> ? value : const <String, Object?>{};

JsonList asList(Object? value) => value is List<Object?> ? value : const <Object?>[];

String? asString(Object? value) => value is String ? value : null;

int? asInt(Object? value) => value is num && value == value.roundToDouble() ? value.toInt() : null;

double? asDouble(Object? value) => value is num ? value.toDouble() : null;

final RegExp _plainKey = RegExp(r'^[A-Za-z_][A-Za-z0-9_]*$');

/// `$.keys.X` or `$.keys["#"]`, as the schema validator writes paths.
String childPath(String path, String key) => _plainKey.hasMatch(key) ? '$path.$key' : '$path["$key"]';

String indexPath(String path, int index) => '$path[$index]';

String formatPath(List<JsonStep> steps) {
  String path = r'$';
  for (final JsonStep step in steps) {
    path = step is int ? indexPath(path, step) : childPath(path, step as String);
  }
  return path;
}

Object? deepCopy(Object? value) {
  if (value is Map<String, Object?>) {
    return <String, Object?>{for (final MapEntry<String, Object?> entry in value.entries) entry.key: deepCopy(entry.value)};
  }
  if (value is List<Object?>) return <Object?>[for (final Object? item in value) deepCopy(item)];
  return value;
}

Object? readIn(Object? root, List<JsonStep> steps) {
  Object? current = root;
  for (final JsonStep step in steps) {
    if (step is int) {
      if (current is! List<Object?> || step < 0 || step >= current.length) return null;
      current = current[step];
    } else {
      if (current is! Map<String, Object?>) return null;
      current = current[step as String];
    }
  }
  return current;
}

/// An edit to a document, run on the document as it is when the edit is
/// saved.
typedef JsonUpdate = Object? Function(Object? latest);

/// Writes [value] at [steps] of [shown], the document the editor drew, as an
/// edit on the latest document: the parts [shown] and the latest differ in
/// keep the latest's, so a quick edit saved in between is not lost
/// ([rebaseJson]). Empty [steps] replace the whole document that way.
JsonUpdate writeAt(Object? shown, List<JsonStep> steps, Object? value) =>
    (Object? latest) => rebaseJson(shown, writeIn(shown, steps, value), latest, written: steps);

/// [root] with the value at [steps] replaced by [value]; a null [value]
/// removes a map key (or a list item). Missing maps along the way are created.
Object? writeIn(Object? root, List<JsonStep> steps, Object? value) {
  if (steps.isEmpty) return value;
  final JsonStep head = steps.first;
  final List<JsonStep> rest = steps.sublist(1);
  if (head is int) {
    final JsonList list = <Object?>[...asList(root)];
    if (head < 0 || head > list.length) return root;
    final Object? next = writeIn(head < list.length ? list[head] : null, rest, value);
    if (head == list.length) {
      if (next != null) list.add(next);
    } else if (next == null && rest.isEmpty) {
      list.removeAt(head);
    } else {
      list[head] = next;
    }
    return list;
  }
  final String key = head as String;
  final JsonMap map = <String, Object?>{...asMap(root)};
  final Object? next = writeIn(map[key], rest, value);
  if (next == null && rest.isEmpty) {
    map.remove(key);
  } else {
    map[key] = next;
  }
  return map;
}

/// [map] with [key] renamed to [to] in place, keeping key order.
JsonMap renameKey(JsonMap map, String key, String to) => <String, Object?>{
      for (final MapEntry<String, Object?> entry in map.entries) entry.key == key ? to : entry.key: entry.value,
    };

/// A list with the item at [from] moved to [to].
JsonList moveItem(JsonList list, int from, int to) {
  final JsonList out = <Object?>[...list];
  if (from < 0 || from >= out.length || to < 0 || to >= out.length) return out;
  final Object? item = out.removeAt(from);
  out.insert(to, item);
  return out;
}

const Map<String, String> _acronyms = <String, String>{'id': 'ID', 'uuid': 'UUID', 'url': 'URL', 'gui': 'GUI', 'npc': 'NPC', 'npcs': 'NPCs'};

/// A field key as a label: `waitTicks` and `wait_ticks` read "Wait ticks".
/// Namespaced keys (`animus:combat`) and one-letter axes stay as written.
String humanizeKey(String key) {
  if (key.contains(':') || key.length < 2) return key;
  final List<String> words = <String>[
    for (final String part in key.replaceAllMapped(RegExp(r'([a-z0-9])([A-Z])'), (Match m) => '${m[1]} ${m[2]}').split(RegExp(r'[\s._-]+')))
      if (part.isNotEmpty) part.toLowerCase(),
  ];
  if (words.isEmpty) return key;
  final List<String> shown = <String>[for (final String word in words) _acronyms[word] ?? word];
  final String first = shown.first;
  shown[0] = first == words.first ? '${first[0].toUpperCase()}${first.substring(1)}' : first;
  return shown.join(' ');
}

/// The editor's file format: two-space indent and a final newline.
String prettyJson(Object? value) => '${const JsonEncoder.withIndent('  ').convert(value)}\n';
