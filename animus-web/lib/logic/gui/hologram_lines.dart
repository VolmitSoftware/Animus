/// Hologram rows as core's HologramDocument reads them: a string is a text
/// row, null an empty one, and an object holds exactly one of `text`,
/// `item`, `head`, `block` or `entity` beside `show` and `scale`. Every
/// writer here keeps the keys it does not change.
library;

import 'gui_json.dart';

/// The row kinds, in the order the editor offers them.
const List<String> hologramLineKinds = <String>['text', 'item', 'head', 'block', 'entity'];

/// Keys a row object may carry besides its kind.
const List<String> hologramLineExtras = <String>['show', 'scale'];

/// The kind of [line]: text for strings, null and objects without a kind.
String hologramLineKind(Object? line) {
  if (line is! Map<String, Object?>) return 'text';
  for (final String kind in hologramLineKinds) {
    if (line.containsKey(kind)) return kind;
  }
  return 'text';
}

/// The value a fresh row of [kind] starts with.
Object? hologramLineDefault(String kind) => switch (kind) {
      'item' => <String, Object?>{'type': 'item', 'item': 'minecraft:diamond'},
      'head' => '{{ viewer.name }}',
      'block' => 'minecraft:stone',
      'entity' => 'minecraft:pig',
      _ => '&fText',
    };

/// A new row of [kind].
Object? newHologramLine(String kind) => kind == 'text' ? hologramLineDefault(kind) : <String, Object?>{kind: hologramLineDefault(kind)};

/// [line] with its kind's value set to [value], keeping `show`, `scale` and
/// any other key. A plain text row stays a string.
Object? withHologramLineValue(Object? line, Object? value) {
  final String kind = hologramLineKind(line);
  if (kind == 'text' && line is! Map<String, Object?>) return value;
  return <String, Object?>{...asMap(line), kind: value};
}

/// [line] with the row field [key] (`show` or `scale`) set, or removed for
/// null. A string row becomes an object to carry it.
Object? withHologramLineField(Object? line, String key, Object? value) {
  final JsonMap object = line is Map<String, Object?> ? <String, Object?>{...line} : <String, Object?>{'text': line ?? ''};
  if (value == null) {
    object.remove(key);
  } else {
    object[key] = value;
  }
  return object;
}

/// [line] turned into a row of [kind], keeping `show`, `scale` and the
/// other row keys; the old kind's value goes.
Object? withHologramLineKind(Object? line, String kind) {
  if (hologramLineKind(line) == kind) return line;
  final JsonMap kept = <String, Object?>{
    if (line is Map<String, Object?>)
      for (final MapEntry<String, Object?> entry in line.entries)
        if (!hologramLineKinds.contains(entry.key)) entry.key: entry.value,
  };
  if (kind == 'text' && kept.isEmpty) return hologramLineDefault(kind);
  return <String, Object?>{kind: hologramLineDefault(kind), ...kept};
}

/// [value] as a position or angle field shows it: two decimals, so an anchor
/// copied from a player's exact spot reads -22.41. The document keeps every
/// digit until the field is edited.
num? shownCoordinate(num? value) => value == null ? null : (value * 100).round() / 100;
