import 'dart:convert';

import 'package:animus_editor/logic/gui/gui_json.dart';
import 'package:animus_editor/logic/npc/combat_forms.dart';
import 'package:animus_editor/logic/npc/combat_presets.dart';
import 'package:animus_editor/logic/npc/combat_settings.dart';
import 'package:animus_editor/logic/npc/health_settings.dart';
import 'package:animus_editor/model/animus_document.dart';
import 'package:test/test.dart';

JsonMap _targets(List<Object?> categories) => <String, Object?>{'categories': categories};

/// A combat module and a health module with a value at every key.
JsonMap _everything() => <String, Object?>{
      combatModule: <String, Object?>{
        'enabled': true,
        'targets': <String, Object?>{
          'categories': <Object?>['monsters'],
          'entityTypes': <Object?>['minecraft:zombie'],
          'patterns': <String, Object?>{'names': <Object?>['^Bandit']},
          'events': <String, Object?>{'kinds': <Object?>['player_hurts_npc']},
        },
        'ignores': <String, Object?>{'players': <Object?>['Notch']},
        'avoids': <String, Object?>{'categories': <Object?>['animals']},
        'targeting': <String, Object?>{'scanRange': 24, 'lineOfSight': 'field_of_view', 'fightBack': false},
        'avoidance': <String, Object?>{
          'range': 8,
          'returnPoint': <String, Object?>{'world': 'world', 'x': 1, 'y': 64, 'z': 2},
        },
        'squad': <String, Object?>{'name': 'north', 'alertRange': 20},
        'guard': <String, Object?>{'player': '3c1e5a70-2b4d-4e8f-9a61-0d7c2f4b8e11', 'assist': true},
        'pursuit': <String, Object?>{'speed': 5, 'region': 'spawn'},
        'melee': <String, Object?>{
          'damage': 4,
          'weaponDamage': <String, Object?>{'minecraft:iron_sword': 6},
        },
        'ranged': <String, Object?>{
          'enabled': true,
          'weapon': 'potion',
          'effect': <String, Object?>{'id': 'minecraft:poison', 'amplifier': 1},
        },
        'greetings': <String, Object?>{'hostile': 'Halt!', 'cooldownTicks': 200},
        'targetableByMobs': true,
        'victimDrops': false,
        'preservedSettings': <String, Object?>{'citizens': 'sentinel'},
        'mystery': 3,
      },
      healthModule: <String, Object?>{
        'killable': true,
        'maximumHealth': 40,
        'armor': 0.25,
        'sources': <Object?>['players', 'fire'],
        'death': <String, Object?>{
          'drops': <Object?>[
            <String, Object?>{
              'item': <String, Object?>{'id': 'minecraft:bread'},
              'chance': 0.5,
            },
          ],
          'experience': 5,
        },
        'respawn': <String, Object?>{
          'afterDeath': 'respawn',
          'delayTicks': 200,
          'points': <Object?>[
            <String, Object?>{'world': 'world', 'x': 0, 'y': 64, 'z': 0},
          ],
        },
      },
    };

void main() {
  group('who it fights', () {
    test('should read no rules as attackers only and the category lists as presets', () {
      expect(combatPresetOf(null), CombatPreset.attackers);
      expect(combatPresetOf(const <String, Object?>{}), CombatPreset.attackers);
      expect(combatPresetOf(_targets(<Object?>['monsters'])), CombatPreset.monsters);
      expect(combatPresetOf(_targets(<Object?>['players'])), CombatPreset.players);
      expect(combatPresetOf(_targets(<Object?>['monsters', 'players'])), CombatPreset.monstersAndPlayers);
      expect(combatPresetOf(_targets(<Object?>['players', 'monsters'])), CombatPreset.monstersAndPlayers);
    });

    test('should read anything else as custom', () {
      expect(combatPresetOf(_targets(<Object?>['animals'])), CombatPreset.custom);
      expect(combatPresetOf(_targets(<Object?>['players', 'players'])), CombatPreset.custom);
      expect(combatPresetOf(_targets(const <Object?>[])), CombatPreset.custom);
      expect(combatPresetOf(_targets(<Object?>['monsters', 3])), CombatPreset.custom);
      expect(combatPresetOf(<String, Object?>{..._targets(<Object?>['monsters']), 'owner': true}), CombatPreset.custom);
      expect(combatPresetOf(<String, Object?>{'entityTypes': <Object?>['minecraft:zombie']}), CombatPreset.custom);
      expect(combatPresetOf('monsters'), CombatPreset.custom);
    });

    test('should write each preset so it reads back, keeping the rest of the module in order', () {
      const JsonMap module = <String, Object?>{'enabled': true, 'targets': <String, Object?>{'owner': true}, 'melee': <String, Object?>{'damage': 4}};
      for (final CombatPreset preset in CombatPreset.values.where((CombatPreset preset) => preset != CombatPreset.custom)) {
        final JsonMap next = withCombatPreset(module, preset);
        expect(combatPresetOf(next['targets']), preset, reason: preset.name);
        expect(next['melee'], module['melee']);
        expect(next['enabled'], isTrue);
      }
      expect(withCombatPreset(module, CombatPreset.monsters).keys.toList(), <String>['enabled', 'targets', 'melee']);
      expect(withCombatPreset(module, CombatPreset.monstersAndPlayers)['targets'], _targets(<Object?>['players', 'monsters']));
      expect(withCombatPreset(module, CombatPreset.attackers).containsKey('targets'), isFalse);
      expect(withCombatPreset(module, CombatPreset.custom), same(module));
    });

    test('should label each preset in plain words', () {
      expect(<String>[for (final CombatPreset preset in CombatPreset.values) preset.label],
          <String>['Attackers only', 'Monsters', 'Players', 'Monsters and players', 'Custom']);
    });
  });

  group('switches', () {
    test('should start, stop and drop the combat module', () {
      expect(withFights(null, true), <String, Object?>{'enabled': true});
      expect(withFights(null, false), isNull);
      expect(withFights(<String, Object?>{'enabled': true}, false), isNull, reason: 'nothing else to keep');
      expect(withFights(<String, Object?>{'enabled': true, 'victimDrops': false}, false), <String, Object?>{'enabled': false, 'victimDrops': false});
      expect(withFights(<String, Object?>{'enabled': false, 'victimDrops': false}, true), <String, Object?>{'enabled': true, 'victimDrops': false});
      expect(withFights('broken', true), 'broken');
      expect(combatFights(<String, Object?>{'enabled': true}), isTrue);
      expect(combatFights(<String, Object?>{'enabled': 'yes'}), isFalse);
      expect(combatFights(null), isFalse);
    });

    test('should start, stop and drop the health module', () {
      expect(withCanBeHurt(null, true), <String, Object?>{'killable': true});
      expect(withCanBeHurt(<String, Object?>{'killable': true}, false), isNull);
      expect(withCanBeHurt(<String, Object?>{'killable': true, 'maximumHealth': 30}, false), <String, Object?>{'killable': false, 'maximumHealth': 30});
      expect(healthCanBeHurt(<String, Object?>{'killable': true}), isTrue);
      expect(healthCanBeHurt(<String, Object?>{'maximumHealth': 30}), isFalse);
    });
  });

  group('advanced settings in use', () {
    test('should count nothing for what Simple shows', () {
      expect(combatHiddenSettings(const <String, Object?>{}), isEmpty);
      expect(combatHiddenSettings(<String, Object?>{combatModule: <String, Object?>{'enabled': true}}), isEmpty);
      expect(combatHiddenSettings(<String, Object?>{
        combatModule: <String, Object?>{'enabled': true, 'targets': _targets(<Object?>['monsters'])},
        healthModule: <String, Object?>{'killable': true, 'maximumHealth': 30, 'dead': true},
        'animus:shop': <String, Object?>{'shop': 'market'},
      }), isEmpty);
    });

    test('should count each setting of a section, custom targets and other values, not a default reach', () {
      final List<List<JsonStep>> hidden = combatHiddenSettings(<String, Object?>{
        combatModule: <String, Object?>{
          'enabled': true,
          'targets': <String, Object?>{'categories': <Object?>['animals'], 'owner': true},
          'melee': <String, Object?>{'damage': 4, 'reach': 3},
          'ranged': const <String, Object?>{},
          'victimDrops': false,
        },
      });
      expect(hidden, <List<JsonStep>>[
        <JsonStep>[combatModule, 'targets', 'categories'],
        <JsonStep>[combatModule, 'targets', 'owner'],
        <JsonStep>[combatModule, 'melee', 'damage'],
        <JsonStep>[combatModule, 'victimDrops'],
      ]);
    });

    test('should count what the switches hide while off', () {
      expect(combatHiddenSettings(<String, Object?>{
        combatModule: <String, Object?>{'enabled': false, 'targets': _targets(<Object?>['monsters'])},
        healthModule: <String, Object?>{'killable': false, 'maximumHealth': 30},
      }), <List<JsonStep>>[
        <JsonStep>[combatModule, 'targets', 'categories'],
        <JsonStep>[healthModule, 'maximumHealth'],
      ]);
      expect(combatHiddenSettings(<String, Object?>{combatModule: <String, Object?>{'enabled': 'yes'}}), <List<JsonStep>>[
        <JsonStep>[combatModule, 'enabled'],
      ]);
      expect(combatHiddenSettings(<String, Object?>{combatModule: 'broken', healthModule: 3}), <List<JsonStep>>[
        <JsonStep>[combatModule],
        <JsonStep>[healthModule],
      ]);
    });

    test('should count every advanced value of a full module', () {
      final List<String> hidden = <String>[for (final List<JsonStep> steps in combatHiddenSettings(_everything())) steps.join('.')];
      expect(hidden.length, 34, reason: 'preservedSettings is an import record');
      expect(hidden, contains('animus:combat.targets.patterns'));
      expect(hidden, contains('animus:combat.mystery'));
      expect(hidden, contains('animus:health.respawn.points'));
      expect(hidden, isNot(contains('animus:health.maximumHealth')));
    });

    test('should find the problems of hidden settings, not of shown ones', () {
      final List<List<JsonStep>> hidden = combatHiddenSettings(<String, Object?>{
        combatModule: <String, Object?>{
          'enabled': true,
          'melee': <String, Object?>{'reach': 9},
          'guard': <String, Object?>{'player': 'x', 'npc': 'y'},
        },
        healthModule: <String, Object?>{'killable': true, 'maximumHealth': 9999},
      });
      Diagnostic at(String path) => Diagnostic(DiagnosticSeverity.error, path, 'wrong');
      final List<Diagnostic> all = <Diagnostic>[
        at(r'$.modules.animus:combat.melee.reach'),
        at(r'$.modules["animus:combat"].guard'),
        at(r'$.modules["animus:health"].maximumHealth'),
        at(r'$.modules.animus:combat'),
        at(r'$.name'),
      ];
      expect(hiddenProblems(hidden, all).map((Diagnostic d) => d.path), <String>[r'$.modules.animus:combat.melee.reach', r'$.modules["animus:combat"].guard']);
    });

    test('should read a trait group path under values', () {
      expect(moduleSteps(const Diagnostic(DiagnosticSeverity.error, r'$.values.modules["animus:combat"].melee.reach', 'wrong')), <JsonStep>[combatModule, 'melee', 'reach']);
      expect(moduleSteps(const Diagnostic(DiagnosticSeverity.error, r'$.values.name', 'wrong')), isNull);
    });

    test('should leave out values equal to the defaults and import records', () {
      expect(combatHiddenSettings(<String, Object?>{
        combatModule: <String, Object?>{
          'enabled': true,
          'targeting': <String, Object?>{'scanIntervalTicks': 10, 'fightBack': true, 'lineOfSight': 'required', 'scanRange': 20},
          'pursuit': <String, Object?>{'leashRange': 64, 'returnToPost': true},
          'targetableByMobs': false,
          'victimDrops': true,
          'preservedSettings': <String, Object?>{'citizens': 'sentinel'},
        },
        healthModule: <String, Object?>{'killable': true, 'knockback': true, 'invulnerableTicks': 10, 'armor': 0.5},
      }), <List<JsonStep>>[
        <JsonStep>[combatModule, 'targeting', 'scanRange'],
        <JsonStep>[healthModule, 'armor'],
      ]);
    });
  });

  group('Simple keeps what it does not show', () {
    test('should keep every advanced value byte for byte through the Simple edits', () {
      final JsonMap before = _everything();
      final List<List<JsonStep>> hidden = combatHiddenSettings(before);
      JsonMap modules = before;
      JsonMap put(String key, Object? value) => <String, Object?>{...modules, key: value}..removeWhere((String _, Object? v) => v == null);
      modules = put(combatModule, withFights(modules[combatModule], false));
      modules = put(combatModule, withFights(modules[combatModule], true));
      modules = put(healthModule, withCanBeHurt(modules[healthModule], false));
      modules = put(healthModule, withCanBeHurt(modules[healthModule], true));
      modules = put(healthModule, withModuleField(asMap(modules[healthModule]), const <JsonStep>['maximumHealth'], 55));
      for (final List<JsonStep> steps in hidden) {
        expect(jsonEncode(readIn(modules, steps)), jsonEncode(readIn(before, steps)), reason: steps.join('.'));
      }
      expect(asMap(modules[healthModule])['maximumHealth'], 55);
      expect(jsonEncode(asMap(modules[combatModule]).keys.toList()), jsonEncode(asMap(before[combatModule]).keys.toList()), reason: 'key order kept');
    });

    test('should keep the other target rules when a preset is picked over custom only by choice', () {
      final JsonMap combat = asMap(_everything()[combatModule]);
      expect(combatPresetOf(combat['targets']), CombatPreset.custom);
      final JsonMap monsters = withCombatPreset(combat, CombatPreset.monsters);
      for (final String key in combat.keys.where((String key) => key != 'targets')) {
        expect(jsonEncode(monsters[key]), jsonEncode(combat[key]), reason: key);
      }
    });
  });
}
