import 'package:animus_editor/logic/citizens/definition_validation.dart';
import 'package:animus_editor/logic/gui/gui_json.dart';
import 'package:animus_editor/logic/inspect/inspect_schemas.dart';
import 'package:animus_editor/logic/inspect/schema_fields.dart';
import 'package:animus_editor/logic/npc/health_settings.dart';
import 'package:test/test.dart';

void main() {
  group('health module edits', () {
    test('should prune empty sections when a field is cleared', () {
      final JsonMap module = <String, Object?>{
        'killable': true,
        'death': <String, Object?>{'experience': 5},
      };
      expect(withHealthField(module, const <JsonStep>['death', 'experience'], null), <String, Object?>{'killable': true});
      expect(withHealthField(module, const <JsonStep>['maximumHealth'], 40), <String, Object?>{...module, 'maximumHealth': 40});
      expect(withHealthField(module, const <JsonStep>['respawn', 'points'], <Object?>[]), module);
    });

    test('should treat missing sources as every attack and drop the key when exactly those are on again', () {
      final JsonMap off = withHealthSource(const <String, Object?>{'killable': true}, 'mobs', false);
      expect(off['sources'], <String>['players', 'npcs', 'projectiles']);
      expect(healthSourcesOn(off), <String>{'players', 'npcs', 'projectiles'});
      expect(healthSourcesOn(const <String, Object?>{}), healthAttackSources.toSet());
      expect(withHealthSource(off, 'mobs', true), <String, Object?>{'killable': true});
    });

    test('should keep environmental sources listed beside every attack', () {
      final JsonMap burning = withHealthSource(const <String, Object?>{'killable': true}, 'lava', true);
      expect(burning['sources'], <String>['players', 'mobs', 'npcs', 'projectiles', 'lava']);
      expect(healthSources, containsAll(<String>['fire', 'lava', 'fall']));
      expect(withHealthSource(burning, 'lava', false), <String, Object?>{'killable': true});
    });

    test('should keep count and components when a drop item changes', () {
      final List<Object?> drops = <Object?>[
        <String, Object?>{
          'item': <String, Object?>{'id': 'minecraft:iron_ingot', 'count': 2, 'components': '[rarity=epic]'},
          'chance': 0.5,
        },
      ];
      expect(withHealthDropItem(drops, 0, 'id', 'minecraft:gold_ingot'), <Object?>[
        <String, Object?>{
          'item': <String, Object?>{'id': 'minecraft:gold_ingot', 'count': 2, 'components': '[rarity=epic]'},
          'chance': 0.5,
        },
      ]);
      expect(asMap(asMap(withHealthDropItem(drops, 0, 'count', 1).first)['item']).containsKey('count'), isFalse);
      expect(withHealthDropItem(drops, 3, 'id', 'minecraft:stone'), drops);
      expect(newHealthDrop(), <String, Object?>{
        'item': <String, Object?>{'id': 'minecraft:iron_ingot'},
      });
    });

    test('should revive by removing dead', () {
      expect(revivedHealth(const <String, Object?>{'killable': true, 'dead': true}), <String, Object?>{'killable': true});
    });
  });

  group('health and combat', () {
    test('should name the combat sections health replaces', () {
      final JsonMap modules = <String, Object?>{
        healthModule: <String, Object?>{'killable': true},
        'animus:combat': <String, Object?>{
          'enabled': true,
          'death': <String, Object?>{'experience': 1},
          'defense': <String, Object?>{'maximumHealth': 40},
          'melee': <String, Object?>{'damage': 3},
        },
      };
      expect(healthCombatConflicts(modules), <String>['defense', 'death']);
      expect(healthCombatConflicts(<String, Object?>{'animus:combat': modules['animus:combat']}), isEmpty);
      expect(withoutReplacedCombat(asMap(modules['animus:combat'])), <String, Object?>{
        'enabled': true,
        'melee': <String, Object?>{'damage': 3},
      });
    });

    test('should refuse a definition with both, as the server does', () {
      final JsonMap npc = <String, Object?>{
        'modules': <String, Object?>{
          healthModule: <String, Object?>{'killable': true},
          'animus:combat': <String, Object?>{
            'respawn': <String, Object?>{'delayTicks': 20},
          },
        },
      };
      expect(definitionRuleErrors(npc), <String>[r'$.modules.animus:combat.respawn: animus:health replaces it; remove one']);
    });
  });

  group('health schema', () {
    test('should match the generated NPC schema keys', () {
      final SchemaView npc = InspectSchemas.embedded.npc;
      final Map<String, Object?> modules = npc.resolve(asMap(asMap(npc.root['properties'])['modules']));
      final Map<String, Object?> node = npc.resolve(asMap(asMap(modules['properties'])[healthModule]));
      expect(asMap(node['properties']).keys.toSet(), healthKeys.toSet());
      expect(asMap(asMap(asMap(node['properties'])['respawn'])['properties']).keys.toSet(), <String>{'afterDeath', 'delayTicks', 'points', 'order'});
    });
  });
}
