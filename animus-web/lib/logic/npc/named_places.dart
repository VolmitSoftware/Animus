/// An NPC's `anchors` (named spots `animus:navigate` and `animus:teleport`
/// go to) and `poses` (named facings `animus:pose` turns to) as the Basics
/// tab edits them: one row per name, added where the NPC stands or faces.
/// Every edit keeps the order of the names. Pure Dart.
library;

import '../gui/gui_json.dart';

/// `^[a-z0-9][a-z0-9_-]{0,31}$`, the schema's property names for both maps.
final RegExp _placeName = RegExp(r'^[a-z0-9][a-z0-9_-]{0,31}$');

/// [base], or [base]-2, -3 and so on, whichever [map] does not hold.
String _free(JsonMap map, String base) {
  if (!map.containsKey(base)) return base;
  int number = 2;
  while (map.containsKey('$base-$number')) {
    number++;
  }
  return '$base-$number';
}

/// [anchors] with a new anchor at [at], the NPC's position.
JsonMap withNewAnchor(Object? anchors, ({num x, num y, num z}) at) {
  final JsonMap map = asMap(anchors);
  return <String, Object?>{...map, _free(map, 'anchor'): <String, Object?>{'x': at.x, 'y': at.y, 'z': at.z}};
}

/// [facings] with a new named facing at [yaw], the NPC's facing.
JsonMap withNewFacing(Object? facings, num yaw) {
  final JsonMap map = asMap(facings);
  return <String, Object?>{...map, _free(map, 'facing'): <String, Object?>{'yaw': yaw}};
}

/// Why [name] cannot replace [current] in [map], or null when it can.
String? placeNameProblem(String name, Object? map, String current) {
  if (!_placeName.hasMatch(name)) return 'Use 1-32 lowercase letters, digits, _ or -, starting with a letter or digit.';
  if (name != current && asMap(map).containsKey(name)) return 'Another one is already called $name.';
  return null;
}

/// [map] with [from] renamed [to] where it stood; null when [from] is gone.
JsonMap? renamedPlace(Object? map, String from, String to) {
  final JsonMap places = asMap(map);
  if (!places.containsKey(from)) return null;
  return <String, Object?>{for (final MapEntry<String, Object?> entry in places.entries) entry.key == from ? to : entry.key: entry.value};
}

/// [map] with [field] of place [name] set to [value], or removed when null.
JsonMap? withPlaceValue(Object? map, String name, String field, Object? value) {
  final JsonMap places = asMap(map);
  if (!places.containsKey(name)) return null;
  final JsonMap place = <String, Object?>{
    for (final MapEntry<String, Object?> entry in asMap(places[name]).entries)
      if (entry.key != field) entry.key: entry.value,
  };
  if (value != null) place[field] = value;
  return <String, Object?>{for (final MapEntry<String, Object?> entry in places.entries) entry.key: entry.key == name ? _ordered(asMap(entry.value), place) : entry.value};
}

/// [next] with its keys in [before]'s order, new keys last.
JsonMap _ordered(JsonMap before, JsonMap next) => <String, Object?>{
      for (final String key in before.keys)
        if (next.containsKey(key)) key: next[key],
      for (final MapEntry<String, Object?> entry in next.entries)
        if (!before.containsKey(entry.key)) entry.key: entry.value,
    };

/// [map] without place [name]; null once no place is left.
JsonMap? withoutPlace(Object? map, String name) {
  final JsonMap rest = <String, Object?>{
    for (final MapEntry<String, Object?> entry in asMap(map).entries)
      if (entry.key != name) entry.key: entry.value,
  };
  return rest.isEmpty ? null : rest;
}
