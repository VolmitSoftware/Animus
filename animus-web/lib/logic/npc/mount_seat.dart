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

/// [module] riding the NPC [vehicle]: an entity target and its range go,
/// `enabled` stays. Picking the NPC is what makes a new mount ride.
JsonMap withMountVehicle(Object? module, String vehicle) => <String, Object?>{
      for (final MapEntry<String, Object?> entry in asMap(module).entries)
        if (entry.key != 'entity' && entry.key != 'range') entry.key: entry.value,
      'vehicle': vehicle,
    };

/// [module] riding [entity]: an NPC target goes, and a UUID is never
/// searched within a range.
JsonMap withMountEntity(Object? module, String entity) => <String, Object?>{
      for (final MapEntry<String, Object?> entry in asMap(module).entries)
        if (entry.key != 'vehicle' && (entry.key != 'range' || !isEntityUuid(entity))) entry.key: entry.value,
      'entity': entity,
    };

/// Whether players sit on an NPC with this seat module; without one, the
/// server's `[seats] defaultEnabled` decides.
bool seatOn(Object? module) => module is Map<String, Object?> && module['enabled'] != false;

/// Whether `animus:riding` lets players ride the NPC (`RideSettings.parse`).
bool ridingOn(JsonMap modules) => asMap(modules[ridingModuleKey])['enabled'] == true;

/// The seat keys the switch itself writes: turned off with nothing else
/// set, the seat goes, so the server's `[seats] defaultEnabled` decides
/// again rather than an `enabled: false` that overrides it.
const Set<String> _switchKeys = <String>{'enabled', 'click'};

/// [module] turned [on] or off, its other settings kept. Turned on beside
/// [riding], a seat without a click of its own takes sneak-right, since a
/// plain right-click mounts the NPC. Turned off with no setting but the
/// switch's own, null removes it.
JsonMap? seatSwitched(Object? module, {required bool on, required bool riding}) {
  final JsonMap seat = asMap(module);
  if (!on && seat.keys.every(_switchKeys.contains)) return null;
  return <String, Object?>{
    ...seat,
    'enabled': on,
    if (on && riding && !seat.containsKey('click')) 'click': 'sneak-right',
  };
}
