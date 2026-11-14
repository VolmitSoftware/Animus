/// The NPC health module `modules["animus:health"]`: health, armor, death,
/// drops and respawn, with or without combat AI. Its field lists and the
/// edits behind its editor. A combat NPC without it cannot be hurt.
library;

import '../gui/gui_json.dart';
import 'combat_forms.dart' show withModuleField;

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

/// The sources that may hurt the NPC.
Set<String> healthSourcesOn(JsonMap module) =>
    module.containsKey('sources') ? <String>{for (final Object? source in asList(module['sources'])) ?asString(source)} : healthAttackSources.toSet();

/// [module] with exactly [chosen] hurting it. Every attack and nothing else
/// is the default, so it removes `sources`; nothing at all is an empty list.
JsonMap withHealthSources(JsonMap module, Set<String> chosen) {
  final bool plain = chosen.length == healthAttackSources.length && healthAttackSources.every(chosen.contains);
  if (plain) return withModuleField(module, const <JsonStep>['sources'], null);
  return <String, Object?>{
    ...module,
    'sources': <String>[
      for (final String name in healthSources)
        if (chosen.contains(name)) name,
    ],
  };
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
