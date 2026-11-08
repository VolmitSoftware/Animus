/// The NPC health module `modules["animus:health"]`: health, armor, death,
/// drops and respawn, with or without combat AI. Its field lists and the
/// edits behind its editor. A combat NPC without it cannot be hurt.
library;

import '../gui/gui_json.dart';

const String healthModule = 'animus:health';

/// Keys the module object accepts.
const List<String> healthKeys = <String>[
  'killable',
  'maximumHealth',
  'armor',
  'armorFromEquipment',
  'invulnerableTicks',
  'knockback',
  'regenerate',
  'regenerateTicks',
  'sources',
  'attackerPermission',
  'showHealth',
  'protectFromRange',
  'protectedFromIgnored',
  'death',
  'respawn',
  'dead',
];

/// What may hurt the NPC: attacks, then the environment, which counts only when listed.
const List<String> healthSources = <String>['players', 'mobs', 'npcs', 'projectiles', 'fire', 'lava', 'fall'];

/// The sources a module without `sources` takes: every attack and nothing from the environment.
const List<String> healthAttackSources = <String>['players', 'mobs', 'npcs', 'projectiles'];

const List<String> healthAfterDeath = <String>['respawn', 'permadeath', 'delete'];
const List<String> healthOrders = <String>['in_order', 'random', 'nearest'];

bool _empty(Object? value) => (value is Map<String, Object?> && value.isEmpty) || (value is List<Object?> && value.isEmpty);

/// [module] with [value] at [steps]; null or an empty map or list removes the
/// key, and so does every map it leaves empty above it.
JsonMap withHealthField(JsonMap module, List<JsonStep> steps, Object? value) {
  Object? next = writeIn(module, steps, steps.last is String && _empty(value) ? null : value);
  for (int depth = steps.length - 1; depth >= 1; depth--) {
    final List<JsonStep> prefix = steps.sublist(0, depth);
    if (prefix.last is! String || !_empty(readIn(next, prefix))) break;
    next = writeIn(next, prefix, null);
  }
  return asMap(next);
}

/// The sources that may hurt the NPC.
Set<String> healthSourcesOn(JsonMap module) =>
    module.containsKey('sources') ? <String>{for (final Object? source in asList(module['sources'])) ?asString(source)} : healthAttackSources.toSet();

/// [module] with [source] on or off; every attack and nothing else on again removes `sources`.
JsonMap withHealthSource(JsonMap module, String source, bool on) {
  final Set<String> chosen = healthSourcesOn(module);
  if (on) {
    chosen.add(source);
  } else {
    chosen.remove(source);
  }
  final bool plain = chosen.length == healthAttackSources.length && healthAttackSources.every(chosen.contains);
  return withHealthField(module, const <JsonStep>['sources'], plain ? null : <String>[
    for (final String name in healthSources)
      if (chosen.contains(name)) name,
  ]);
}

JsonMap newHealthDrop([String item = 'minecraft:iron_ingot']) => <String, Object?>{
  'item': <String, Object?>{'id': item},
};

/// [drops] with drop [index]'s item field [key] set; null, empty text or a
/// count of 1 removes it. Unchanged when there is no such drop.
List<Object?> withHealthDropItem(List<Object?> drops, int index, String key, Object? value) {
  if (index < 0 || index >= drops.length) return drops;
  final JsonMap drop = asMap(drops[index]);
  final bool clear = value == null || value == '' || (key == 'count' && value == 1);
  final JsonMap item = asMap(writeIn(asMap(drop['item']), <JsonStep>[key], clear ? null : value));
  return <Object?>[...drops]..[index] = <String, Object?>{...drop, 'item': item};
}

/// [module] alive again: `dead` removed.
JsonMap revivedHealth(JsonMap module) => <String, Object?>{
  for (final MapEntry<String, Object?> entry in module.entries)
    if (entry.key != 'dead') entry.key: entry.value,
};
