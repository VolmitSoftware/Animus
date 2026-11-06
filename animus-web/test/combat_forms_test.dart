import 'package:animus_editor/logic/gui/gui_json.dart';
import 'package:animus_editor/logic/inspect/inspect_schemas.dart';
import 'package:animus_editor/logic/inspect/schema_fields.dart';
import 'package:animus_editor/logic/npc/combat_forms.dart';
import 'package:animus_editor/logic/npc/combat_settings.dart';
import 'package:animus_editor/model/animus_document.dart';
import 'package:test/test.dart';

List<String> _paths(Object? module) => <String>[for (final Diagnostic diagnostic in validateCombat(module)) diagnostic.path];

void main() {
  final SchemaView npc = InspectSchemas.embedded.npc;

  group('combat target rules', () {
    test('should offer every advanced rule the section allows, from the NPC schema', () {
      List<String> keys(String section) => <String>[for (final FieldSpec field in combatAdvancedRuleFields(npc, section)) field.key];
      expect(keys('targets'), <String>['patterns', 'items', 'permissions', 'groups', 'effects', 'all', 'together', 'events', 'chat']);
      expect(keys('avoids'), keys('targets'));
      expect(keys('ignores'), <String>['patterns', 'items', 'permissions', 'groups', 'effects', 'all']);
    });

    test('should type each advanced rule as the schema does', () {
      final Map<String, FieldSpec> fields = <String, FieldSpec>{for (final FieldSpec field in combatAdvancedRuleFields(npc, 'targets')) field.key: field};
      expect(fields['patterns']!.kind, FieldKind.object);
      expect(fields['patterns']!.schema['properties'], isA<Map<String, Object?>>());
      expect(fields['events']!.kind, FieldKind.object);
      expect(fields['all']!.kind, FieldKind.array);
      expect(fields['together']!.kind, FieldKind.array);
      expect(fields['chat']!.kind, FieldKind.array);
      expect(npc.resolve(fields['all']!.schema['items'])['properties'], isA<Map<String, Object?>>());
    });

    test('should write a rule and read it back, removing an emptied one', () {
      JsonMap rules = <String, Object?>{'categories': <Object?>['monsters']};
      rules = withCombatRule(rules, 'patterns', <String, Object?>{
        'names': <Object?>['^Bandit'],
      });
      rules = withCombatRule(rules, 'events', <String, Object?>{
        'kinds': <Object?>['player_hurts_npc'],
        'playerAttacks': <String, Object?>{'categories': <Object?>['players']},
      });
      rules = withCombatRule(rules, 'all', <Object?>[
        <String, Object?>{'categories': <Object?>['players'], 'healthBelow': 50},
      ]);
      expect(readIn(rules, <JsonStep>['patterns', 'names', 0]), '^Bandit');
      expect(readIn(rules, <JsonStep>['events', 'playerAttacks', 'categories', 0]), 'players');
      expect(_paths(<String, Object?>{'targets': rules}), isEmpty);
      rules = withCombatRule(rules, 'patterns', const <String, Object?>{});
      rules = withCombatRule(rules, 'all', const <Object?>[]);
      rules = withCombatRule(rules, 'events', null);
      expect(rules, <String, Object?>{'categories': <Object?>['monsters']});
    });
  });

  group('combat locations', () {
    test('should start a location at the NPC, else after the last one', () {
      expect(newCombatLocation(origin: (10.6, 70, -3.2)), <String, Object?>{'world': 'world', 'x': 10, 'y': 70, 'z': -4});
      expect(newCombatLocation(near: <String, Object?>{'world': 'nether', 'x': 5, 'y': 40, 'z': 5}), <String, Object?>{'world': 'nether', 'x': 7, 'y': 40, 'z': 5});
      expect(newCombatLocation(), <String, Object?>{'world': 'world', 'x': 0, 'y': 64, 'z': 0});
    });

    test('should place and nudge a location on the grid, keeping world and height', () {
      const JsonMap point = <String, Object?>{'world': 'world', 'x': 1, 'y': 65, 'z': 2, 'note': 'kept'};
      expect(placeCombatLocation(point, 4.4, -2.6), <String, Object?>{'world': 'world', 'x': 4, 'y': 65, 'z': -3, 'note': 'kept'});
      expect(nudgeCombatLocation(point, 0.25, -1), <String, Object?>{'world': 'world', 'x': 1.25, 'y': 65, 'z': 1, 'note': 'kept'});
      expect(combatLocationXZ(point), (1.0, 2.0));
      expect(combatLocationXZ(<String, Object?>{'x': 'a', 'z': 1}), isNull);
    });

    test('should round-trip the return point and respawn locations through the checks', () {
      final JsonMap point = withLocationField(newCombatLocation(), 'world', 'world_nether');
      final JsonMap module = <String, Object?>{
        'avoidance': <String, Object?>{'returnPoint': placeCombatLocation(point, 12, 30)},
        'respawn': <String, Object?>{
          'locations': <Object?>[newCombatLocation(), placeCombatLocation(newCombatLocation(), -8, 8)],
        },
      };
      expect(_paths(module), isEmpty);
      expect(readIn(module, <JsonStep>['avoidance', 'returnPoint', 'world']), 'world_nether');
      expect(_paths(<String, Object?>{
        'avoidance': <String, Object?>{'returnPoint': withLocationField(point, 'x', null)},
      }), <String>[r'$.modules.animus:combat.avoidance.returnPoint.x']);
    });
  });

  group('combat ranged', () {
    test('should give a potion an effect and drop it for any other weapon', () {
      final JsonMap potion = withRangedWeapon(const <String, Object?>{'damage': 4}, 'potion');
      expect(potion, <String, Object?>{
        'damage': 4,
        'weapon': 'potion',
        'effect': <String, Object?>{'id': 'minecraft:instant_health'},
      });
      expect(withRangedWeapon(potion, 'trident'), <String, Object?>{'damage': 4, 'weapon': 'trident'});
      expect(withRangedWeapon(potion, null), <String, Object?>{'damage': 4});
    });

    test('should write the effect fields and read them back', () {
      JsonMap ranged = withRangedWeapon(const <String, Object?>{}, 'potion');
      ranged = withRangedEffect(ranged, 'id', 'minecraft:poison');
      ranged = withRangedEffect(ranged, 'amplifier', 2);
      ranged = withRangedEffect(ranged, 'durationTicks', 100);
      expect(ranged['effect'], <String, Object?>{'id': 'minecraft:poison', 'amplifier': 2, 'durationTicks': 100});
      expect(_paths(<String, Object?>{'ranged': ranged}), isEmpty);
      ranged = withRangedEffect(ranged, 'amplifier', null);
      expect(ranged['effect'], <String, Object?>{'id': 'minecraft:poison', 'durationTicks': 100});
    });
  });

  group('combat death drops', () {
    test('should add, edit and clear drop fields', () {
      List<Object?> drops = <Object?>[newCombatDrop()];
      drops = withDropField(drops, 0, 'item', 'minecraft:gold_ingot');
      drops = withDropField(drops, 0, 'amount', 3);
      drops = withDropField(drops, 0, 'chance', 0.5);
      expect(drops, <Object?>[
        <String, Object?>{'item': 'minecraft:gold_ingot', 'amount': 3, 'chance': 0.5},
      ]);
      expect(_paths(<String, Object?>{'death': <String, Object?>{'drops': drops}}), isEmpty);
      drops = withDropField(drops, 0, 'chance', null);
      expect(drops.first, <String, Object?>{'item': 'minecraft:gold_ingot', 'amount': 3});
      expect(withDropField(drops, 4, 'amount', 1), drops);
    });
  });
}
