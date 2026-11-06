/// Values without a schema, edited by kind: text, number, true/false, list,
/// object or empty. The key/value editors use these to change a value's kind
/// and to edit map entries without losing key order.
library;

import '../gui/gui_json.dart';

/// The kind of a JSON value, as the key/value editor offers it.
enum TypedKind {
  text('Text'),
  number('Number'),
  boolean('True/false'),
  list('List'),
  object('Object'),
  empty('Empty');

  const TypedKind(this.label);

  final String label;

  static TypedKind? parse(String? name) {
    for (final TypedKind kind in values) {
      if (kind.name == name) return kind;
    }
    return null;
  }
}

TypedKind typedKindOf(Object? value) => switch (value) {
      String() => TypedKind.text,
      num() => TypedKind.number,
      bool() => TypedKind.boolean,
      List<Object?>() => TypedKind.list,
      Map<String, Object?>() => TypedKind.object,
      _ => TypedKind.empty,
    };

String _text(num value) => value == value.roundToDouble() && value.abs() < 1e15 ? '${value.toInt()}' : '$value';

/// [value] as kind [to], keeping what carries over: `"12"` becomes 12, a
/// single value becomes a one-item list, a list's first item its scalar.
Object? convertTyped(Object? value, TypedKind to) {
  if (typedKindOf(value) == to) return value;
  final Object? scalar = value is List<Object?> ? (value.isEmpty ? null : value.first) : value;
  return switch (to) {
    TypedKind.text => scalar is num ? _text(scalar) : (scalar == null || scalar is Map<String, Object?> || scalar is List<Object?> ? '' : '$scalar'),
    TypedKind.number => scalar is num ? scalar : (scalar is bool ? (scalar ? 1 : 0) : num.tryParse('${scalar ?? ''}'.trim()) ?? 0),
    TypedKind.boolean => scalar is bool ? scalar : (scalar is num ? scalar != 0 : '$scalar'.trim().toLowerCase() == 'true'),
    TypedKind.list => value == null ? <Object?>[] : <Object?>[value],
    TypedKind.object => <String, Object?>{},
    TypedKind.empty => null,
  };
}

/// [map] with [key] set to [value], in its place or at the end.
JsonMap putEntry(JsonMap map, String key, Object? value) => <String, Object?>{...map, key: value};

JsonMap removeEntry(JsonMap map, String key) => <String, Object?>{
      for (final MapEntry<String, Object?> entry in map.entries)
        if (entry.key != key) entry.key: entry.value,
    };

/// Why [from] cannot be renamed to [to], or null when it can.
String? renameProblem(JsonMap map, String from, String to) {
  final String next = to.trim();
  if (next.isEmpty) return 'A key needs a name.';
  if (next != from && map.containsKey(next)) return '"$next" is already a key.';
  return null;
}

/// A key [map] does not have yet: [base], then `base2`, `base3`.
String freshKey(JsonMap map, {String base = 'key'}) {
  if (!map.containsKey(base)) return base;
  int index = 2;
  while (map.containsKey('$base$index')) {
    index++;
  }
  return '$base$index';
}

/// The entries of [value] whose keys are not [known].
JsonMap unknownEntries(JsonMap value, Set<String> known) => <String, Object?>{
      for (final MapEntry<String, Object?> entry in value.entries)
        if (!known.contains(entry.key)) entry.key: entry.value,
    };

/// [value] with its unknown entries replaced by [unknown]: kept keys stay in
/// their places, removed ones go, new ones are added at the end.
JsonMap withUnknownEntries(JsonMap value, Set<String> known, JsonMap unknown) => <String, Object?>{
      for (final MapEntry<String, Object?> entry in value.entries)
        if (known.contains(entry.key))
          entry.key: entry.value
        else if (unknown.containsKey(entry.key))
          entry.key: unknown[entry.key],
      for (final MapEntry<String, Object?> entry in unknown.entries)
        if (!value.containsKey(entry.key) && !known.contains(entry.key)) entry.key: entry.value,
    };
