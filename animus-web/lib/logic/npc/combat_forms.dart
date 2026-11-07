/// Edits behind the combat editor's typed forms: the advanced target rules
/// from the NPC schema, locations placed on the grid (avoidance.returnPoint,
/// respawn.locations), the ranged weapon and its potion effect, and death
/// drops. Each keeps keys it does not edit.
library;

import '../gui/gui_json.dart';
import '../inspect/schema_fields.dart';
import 'combat_settings.dart';
import 'route_model.dart' show snapBlock;

/// Rules edited in "Advanced rules", in the order shown.
const List<String> combatAdvancedRules = <String>['patterns', 'items', 'permissions', 'groups', 'effects', 'all', 'together', 'events', 'chat'];

/// The schema of rule section [section]: `ignoringRules` for ignores,
/// `seekingRules` for targets and avoids.
Map<String, Object?> combatRuleNode(SchemaView npc, String section) =>
    npc.resolve(<String, Object?>{r'$ref': section == 'ignores' ? r'#/$defs/ignoringRules' : r'#/$defs/seekingRules'});

/// The advanced rules [section] accepts, typed from the NPC schema.
List<FieldSpec> combatAdvancedRuleFields(SchemaView npc, String section) {
  final Map<String, FieldSpec> fields = <String, FieldSpec>{
    for (final FieldSpec field in npc.fields(combatRuleNode(npc, section), const <String, Object?>{})) field.key: field,
  };
  return <FieldSpec>[
    for (final String key in combatAdvancedRules)
      if (fields[key] case final FieldSpec field when section != 'ignores' || !combatScanRules.contains(key)) field,
  ];
}

bool _empty(Object? value) => value == null || (value is Map<String, Object?> && value.isEmpty) || (value is List<Object?> && value.isEmpty);

/// [rules] with [key] set to [value]; null or an empty map or list removes it.
JsonMap withCombatRule(JsonMap rules, String key, Object? value) => _empty(value) ? _without(rules, key) : <String, Object?>{...rules, key: value};

JsonMap _without(JsonMap map, String key) => <String, Object?>{
      for (final MapEntry<String, Object?> entry in map.entries)
        if (entry.key != key) entry.key: entry.value,
    };

num _clean(double value) => value == value.roundToDouble() ? value.toInt() : value;

/// The x and z of a location, or null when either is not a number.
(double, double)? combatLocationXZ(Object? point) {
  final JsonMap map = asMap(point);
  final double? x = asDouble(map['x']), z = asDouble(map['z']);
  return x == null || z == null ? null : (x, z);
}

/// A location to add: at the NPC's block, else two blocks east of [near],
/// else 0 64 0 in `world`.
JsonMap newCombatLocation({JsonMap? near, (double, double, double)? origin}) {
  if (origin != null) {
    return <String, Object?>{'world': 'world', 'x': _clean(origin.$1.floorToDouble()), 'y': _clean(origin.$2.floorToDouble()), 'z': _clean(origin.$3.floorToDouble())};
  }
  final (double, double)? at = combatLocationXZ(near);
  if (near != null && at != null) {
    return <String, Object?>{'world': asString(near['world']) ?? 'world', 'x': _clean(at.$1 + 2), 'y': near['y'] is num ? near['y'] : 64, 'z': _clean(at.$2)};
  }
  return <String, Object?>{'world': 'world', 'x': 0, 'y': 64, 'z': 0};
}

/// [point] moved to the block at [x], [z]; world, height and other keys stay.
JsonMap placeCombatLocation(JsonMap point, double x, double z) => <String, Object?>{...point, 'x': _clean(snapBlock(x)), 'z': _clean(snapBlock(z))};

/// [point] moved by [dx], [dz] blocks.
JsonMap nudgeCombatLocation(JsonMap point, double dx, double dz) {
  final (double, double) at = combatLocationXZ(point) ?? (0, 0);
  return <String, Object?>{...point, 'x': _clean(at.$1 + dx), 'z': _clean(at.$2 + dz)};
}

/// [point] with [key] set, or removed when [value] is null or empty text.
JsonMap withLocationField(JsonMap point, String key, Object? value) =>
    value == null || value == '' ? _without(point, key) : <String, Object?>{...point, key: value};

/// [ranged] with [weapon] (null for the default arrow): a potion gets an
/// effect when it has none, any other weapon loses it.
JsonMap withRangedWeapon(JsonMap ranged, String? weapon) {
  JsonMap next = weapon == null ? _without(ranged, 'weapon') : <String, Object?>{...ranged, 'weapon': weapon};
  if (weapon == 'potion') {
    if (!next.containsKey('effect')) {
      next = <String, Object?>{
        ...next,
        'effect': <String, Object?>{'id': 'minecraft:instant_health'},
      };
    }
  } else {
    next = _without(next, 'effect');
  }
  return next;
}

/// [ranged] with effect field [key] set, or removed when [value] is null.
JsonMap withRangedEffect(JsonMap ranged, String key, Object? value) =>
    <String, Object?>{...ranged, 'effect': withLocationField(asMap(ranged['effect']), key, value)};

JsonMap newCombatDrop([String item = 'minecraft:iron_ingot']) => <String, Object?>{'item': item, 'amount': 1, 'chance': 1};

/// [drops] with drop [index]'s [key] set, or removed when [value] is null;
/// unchanged when there is no such drop.
List<Object?> withDropField(List<Object?> drops, int index, String key, Object? value) {
  if (index < 0 || index >= drops.length) return drops;
  return <Object?>[...drops]..[index] = withLocationField(asMap(drops[index]), key, value);
}
