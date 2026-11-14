/// The Combat tab's Simple part: whether the NPC fights, who it fights as a
/// preset, whether it can be hurt; and the settings Simple does not show,
/// which it counts ("n advanced settings"). Every edit here keeps the keys
/// it does not own.
library;

import '../../model/animus_document.dart';
import '../gui/gui_json.dart';
import '../offline/document_fixes.dart' show parseJsonPath;
import 'combat_settings.dart';
import 'health_settings.dart';

/// Who the NPC fights, as Simple offers it. [custom] is any other rule set.
enum CombatPreset {
  attackers('Attackers only'),
  monsters('Monsters'),
  players('Players'),
  monstersAndPlayers('Monsters and players'),
  custom('Custom');

  const CombatPreset(this.label);

  final String label;
}

/// The `targets.categories` each preset writes, in the schema's order.
const Map<CombatPreset, List<String>> _presetCategories = <CombatPreset, List<String>>{
  CombatPreset.monsters: <String>['monsters'],
  CombatPreset.players: <String>['players'],
  CombatPreset.monstersAndPlayers: <String>['players', 'monsters'],
};

/// The preset [targets] (the combat module's `targets`) is: no rules is
/// [CombatPreset.attackers] (it fights back and targets nothing), one
/// `categories` list of monsters and/or players a category preset, anything
/// else [CombatPreset.custom].
CombatPreset combatPresetOf(Object? targets) {
  if (targets == null) return CombatPreset.attackers;
  if (targets is! Map<String, Object?>) return CombatPreset.custom;
  if (targets.isEmpty) return CombatPreset.attackers;
  final Object? categories = targets['categories'];
  if (targets.length != 1 || categories is! List<Object?> || categories.any((Object? item) => item is! String)) return CombatPreset.custom;
  final Set<String> picked = <String>{for (final Object? item in categories) item! as String};
  if (picked.length != categories.length) return CombatPreset.custom;
  for (final MapEntry<CombatPreset, List<String>> preset in _presetCategories.entries) {
    if (preset.value.length == picked.length && preset.value.every(picked.contains)) return preset.key;
  }
  return CombatPreset.custom;
}

/// [module] with the target rules of [preset]; [CombatPreset.custom] changes nothing.
JsonMap withCombatPreset(JsonMap module, CombatPreset preset) {
  if (preset == CombatPreset.custom) return module;
  final List<String>? categories = _presetCategories[preset];
  if (categories == null) return _without(module, 'targets');
  return <String, Object?>{
    ...module,
    'targets': <String, Object?>{'categories': <Object?>[...categories]},
  };
}

JsonMap _without(JsonMap map, String key) => <String, Object?>{
      for (final MapEntry<String, Object?> entry in map.entries)
        if (entry.key != key) entry.key: entry.value,
    };

/// Whether the combat module [module] runs: only a literal `enabled: true` does.
bool combatFights(Object? module) => module is Map<String, Object?> && module['enabled'] == true;

/// Whether the health module [module] lets the NPC be hurt.
bool healthCanBeHurt(Object? module) => module is Map<String, Object?> && module['killable'] == true;

/// [module] with [flag] on or off. On starts the module when there is none.
/// Off drops a module that holds nothing else and writes false otherwise, so
/// its settings stay. A module that is not an object is left alone.
Object? _withFlag(Object? module, String flag, bool on) {
  if (module == null) return on ? <String, Object?>{flag: true} : null;
  if (module is! Map<String, Object?>) return module;
  if (!on && module.keys.every((String key) => key == flag)) return null;
  return <String, Object?>{...module, flag: on};
}

/// The combat module with fighting on or off (see [_withFlag]).
Object? withFights(Object? module, bool on) => _withFlag(module, 'enabled', on);

/// The health module with "can be hurt" on or off (see [_withFlag]).
Object? withCanBeHurt(Object? module, bool on) => _withFlag(module, 'killable', on);

/// Imported records the combat module keeps as written: bookkeeping, not
/// settings.
const List<String> combatImportRecords = <String>['preservedSettings', 'historicalStatistics', 'migrationReport'];

/// Values the server takes when a key is left out, beyond [combatRanges]
/// and [combatBooleans]: `section.key` or a root key.
const Map<String, Object> _combatDefaults = <String, Object>{
  'targeting.lineOfSight': 'required',
  'ranged.weapon': 'arrow',
  'targetableByMobs': false,
  'victimDrops': true,
};

const Map<String, Object> _healthDefaults = <String, Object>{
  'armor': 0,
  'armorFromEquipment': false,
  'invulnerableTicks': 10,
  'knockback': true,
  'regenerate': false,
  'regenerateTicks': 100,
  'protectFromRange': 0,
  'protectedFromIgnored': false,
  'showHealth': false,
};

/// Whether [value] at [section].[key] of the combat module is what the
/// server takes anyway ([section] null for a root key).
bool _combatDefault(String? section, String key, Object? value) {
  if (value is! num && value is! bool && value is! String) return false;
  if (section == null) return _combatDefaults[key] == value;
  final CombatRange? range = combatRange(section, key);
  if (range != null && range.key == key) return value is num && value == range.fallback;
  return combatBooleans[section]?[key] == value || _combatDefaults['$section.$key'] == value;
}

/// The settings of the combat and health modules in [modules] that the
/// Simple part does not show and that change the NPC, as steps under
/// `modules`, such as `['animus:combat', 'melee', 'reach']`: each key of a
/// settings object, and every other value but the two switches, a preset's
/// targets while the NPC fights, max health while it can be hurt, and
/// `dead` (its own notice). A value equal to the server's default, an
/// empty settings object and an import record count for nothing.
List<List<JsonStep>> combatHiddenSettings(JsonMap modules) {
  final List<List<JsonStep>> hidden = <List<JsonStep>>[];
  void spread(String module, String key, Object? value, bool Function(String? section, String key, Object? value) isDefault) {
    if (value is Map<String, Object?>) {
      for (final MapEntry<String, Object?> inner in value.entries) {
        if (!isDefault(key, inner.key, inner.value)) hidden.add(<JsonStep>[module, key, inner.key]);
      }
    } else if (!isDefault(null, key, value)) {
      hidden.add(<JsonStep>[module, key]);
    }
  }

  if (modules.containsKey(combatModule)) {
    final Object? combat = modules[combatModule];
    if (combat is! Map<String, Object?>) {
      hidden.add(<JsonStep>[combatModule]);
    } else {
      final bool fights = combatFights(combat);
      for (final MapEntry<String, Object?> entry in combat.entries) {
        final bool shown = switch (entry.key) {
          'enabled' => entry.value is bool,
          'targets' => fights && combatPresetOf(entry.value) != CombatPreset.custom,
          final String key => combatImportRecords.contains(key),
        };
        if (!shown) spread(combatModule, entry.key, entry.value, _combatDefault);
      }
    }
  }
  if (modules.containsKey(healthModule)) {
    final Object? health = modules[healthModule];
    if (health is! Map<String, Object?>) {
      hidden.add(<JsonStep>[healthModule]);
    } else {
      final bool hurt = healthCanBeHurt(health);
      for (final MapEntry<String, Object?> entry in health.entries) {
        final bool shown = switch (entry.key) {
          'killable' || 'dead' => entry.value is bool,
          'maximumHealth' => hurt && entry.value is num,
          _ => false,
        };
        if (!shown) spread(healthModule, entry.key, entry.value, (String? section, String key, Object? value) => section == null && _healthDefaults[key] == value);
      }
    }
  }
  return hidden;
}

bool _startsWith(List<JsonStep> steps, List<JsonStep> prefix) {
  if (prefix.length > steps.length) return false;
  for (int index = 0; index < prefix.length; index++) {
    if (steps[index] != prefix[index]) return false;
  }
  return true;
}

/// [diagnostic]'s path as steps under `modules` (an NPC's, or a trait
/// group's `values.modules`), or null outside them.
List<JsonStep>? moduleSteps(Diagnostic diagnostic) {
  List<JsonStep>? steps = parseJsonPath(diagnostic.path);
  if (steps != null && steps.isNotEmpty && steps.first == 'values') steps = steps.sublist(1);
  if (steps == null || steps.length < 2 || steps.first != 'modules') return null;
  return steps.sublist(1);
}

/// The diagnostics in [diagnostics] that sit at a setting [hidden] lists
/// (or inside one, or on the object holding it).
List<Diagnostic> hiddenProblems(List<List<JsonStep>> hidden, List<Diagnostic> diagnostics) => <Diagnostic>[
      for (final Diagnostic diagnostic in diagnostics)
        if (moduleSteps(diagnostic) case final List<JsonStep> steps)
          if (hidden.any((List<JsonStep> setting) => _startsWith(steps, setting) || (steps.length >= 2 && _startsWith(setting, steps)))) diagnostic,
    ];
