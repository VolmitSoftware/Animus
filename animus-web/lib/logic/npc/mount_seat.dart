/// The `animus:mount` and `animus:seat` modules as the Movement tab edits
/// them: what the NPC rides (another NPC, or a real entity by type or UUID)
/// and whether players sit on top of it. Pure Dart.
library;

import '../gui/gui_json.dart';

const String mountModuleKey = 'animus:mount';
const String seatModuleKey = 'animus:seat';
const String ridingModuleKey = 'animus:riding';

/// What a mount module rides.
enum MountKind { none, npc, entity }

/// `MountDeclaration.UUID_FORM`.
final RegExp _uuid = RegExp(r'^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$');

/// Real vehicles offered by name; Advanced takes any entity type or a UUID.
const List<String> commonVehicles = <String>[
  'minecraft:horse',
  'minecraft:donkey',
  'minecraft:mule',
  'minecraft:camel',
  'minecraft:pig',
  'minecraft:strider',
  'minecraft:llama',
  'minecraft:oak_boat',
  'minecraft:minecart',
  'minecraft:happy_ghast',
];

/// Whether [entity] names one exact entity rather than the nearest of a type.
bool isEntityUuid(String entity) => _uuid.hasMatch(entity);

/// What [module] rides; a module naming neither field counts as an NPC mount.
MountKind mountKindOf(Object? module) => switch (module) {
      null => MountKind.none,
      final Map<String, Object?> map when map.containsKey('entity') => MountKind.entity,
      _ => MountKind.npc,
    };

/// [module] riding [kind] instead: the other target's fields go, `enabled`
/// stays; null rides nothing. [vehicle] is the NPC an NPC mount starts on.
JsonMap? withMountKind(Object? module, MountKind kind, {String vehicle = ''}) {
  if (kind == MountKind.none) return null;
  final JsonMap current = asMap(module);
  if (mountKindOf(module) == kind) return current;
  final JsonMap next = <String, Object?>{
    for (final MapEntry<String, Object?> entry in current.entries)
      if (entry.key != 'vehicle' && entry.key != 'entity' && entry.key != 'range') entry.key: entry.value,
  };
  next['enabled'] ??= true;
  if (kind == MountKind.npc) {
    next['vehicle'] = vehicle;
  } else {
    next['entity'] = commonVehicles.first;
  }
  return next;
}

/// [module] riding [entity]; a UUID is never searched within a range.
JsonMap withMountEntity(JsonMap module, String entity) => <String, Object?>{
      for (final MapEntry<String, Object?> entry in module.entries)
        if (entry.key != 'range' || !isEntityUuid(entity)) entry.key: entry.value,
      'entity': entity,
    };

/// Whether players sit on an NPC with this seat module; without one, the
/// server's `[seats] defaultEnabled` decides.
bool seatOn(Object? module) => module is Map<String, Object?> && module['enabled'] != false;

/// Whether `animus:riding` lets players ride the NPC (`RideSettings.parse`).
bool ridingOn(JsonMap modules) => asMap(modules[ridingModuleKey])['enabled'] == true;

/// [module] turned [on] or off, its other settings kept. Turned on beside
/// [riding], a seat without a click of its own takes sneak-right, since a
/// plain right-click mounts the NPC.
JsonMap seatSwitched(Object? module, {required bool on, required bool riding}) => <String, Object?>{
      ...asMap(module),
      'enabled': on,
      if (on && riding && !asMap(module).containsKey('click')) 'click': 'sneak-right',
    };
