import 'package:animus_editor/logic/citizens/definition_validation.dart';
import 'package:animus_editor/logic/citizens/java_json.dart';
import 'package:animus_editor/logic/inspect/inspect_schemas.dart';
import 'package:animus_editor/logic/inspect/schema_fields.dart';
import 'package:animus_editor/logic/npc/npc_attributes.dart';
import 'package:animus_editor/logic/select/select_model.dart';
import 'package:animus_editor/mc/npc/npc_look.dart';
import 'package:test/test.dart';

void main() {
  final SchemaView npc = InspectSchemas.embedded.npc;

  group('attribute table from the schema', () {
    test('lists bare ids with ranges, vanilla defaults and notes', () {
      final List<AttributeSpec> specs = attributeSpecs(npc);
      expect(specs.length, greaterThan(20));
      expect(specs.where((AttributeSpec spec) => spec.id.contains(':')), isEmpty);
      final AttributeSpec scale = attributeTable(npc)['scale']!;
      expect(scale.minimum, 0.0625);
      expect(scale.maximum, 16);
      expect(scale.defaultValue, 1);
      expect(scale.range, '0.0625..16');
      expect(scale.description, contains('Body size'));
      expect(attributeTable(npc).keys, containsAll(<String>['gravity', 'movement_speed', 'max_health']));
    });

    test('knows which bodies carry each attribute', () {
      expect(attributeTable(npc)['scale']!.bodies, contains('minecraft:horse'));
      expect(attributeTable(npc)['attack_damage']!.bodies, containsAll(<String>{'minecraft:frog', 'minecraft:player', 'minecraft:wolf'}));
      expect(attributeTable(npc)['attack_damage']!.bodies, isNot(contains('minecraft:villager')));
      expect(attributeTable(npc)['scale']!.carriedBy('minecraft:horse'), isTrue);
      expect(attributeTable(npc)['luck']!.carriedBy('minecraft:horse'), isFalse);
      expect(attributeTable(npc)['luck']!.carriedBy(null), isTrue);
    });

    test('the add dropdown groups every attribute but the Combat tab ones and orders it for the body', () {
      final List<SelectOption> horse = attributeOptions(attributeSpecs(npc), <String, Object?>{'scale': 2}, 'minecraft:horse');
      expect(horse.length, attributeSpecs(npc).length - combatHomeAttributes.length);
      expect(horse.map((SelectOption option) => option.value), isNot(anyElement(isIn(combatHomeAttributes))), reason: 'max health and armor live in Combat');
      expect(attributeTable(npc).keys, containsAll(combatHomeAttributes));
      expect(<String?>{for (final SelectOption option in horse) option.group}.toList(),
          <String>['Size and movement', 'Combat', 'Senses', 'Other', 'Player only']);
      final SelectOption scale = horse.firstWhere((SelectOption option) => option.value == 'scale');
      expect(scale.disabled, isTrue);
      expect(scale.description, '0.0625 to 16, vanilla 1');
      expect(scale.label, 'Size');
      expect(scale.keywords, contains('scale'));
      expect(horse.first.value, 'scale');
      final SelectOption damage = horse.firstWhere((SelectOption option) => option.value == 'attack_damage');
      expect(damage.disabled, isFalse);
      expect(damage.description, endsWith('no effect on this body'));
      final List<String> combat = <String>[for (final SelectOption option in horse) if (option.group == 'Combat') option.value];
      expect(combat.indexOf('knockback_resistance'), lessThan(combat.indexOf('attack_damage')), reason: 'carried attributes come first');
      expect(horse.where((SelectOption option) => option.group == 'Player only').map((SelectOption option) => option.value), containsAll(<String>['luck', 'attack_speed']));
      final List<SelectOption> player = attributeOptions(attributeSpecs(npc), const <String, Object?>{}, 'minecraft:player');
      expect(<String?>{for (final SelectOption option in player) option.group}.toList(),
          <String>['Size and movement', 'Combat', 'Senses', 'Player only', 'Other']);
      expect(player.where((SelectOption option) => option.description!.contains('no effect')).map((SelectOption option) => option.value), containsAll(<String>['flying_speed', 'spawn_reinforcements']));
    });

    test('every attribute in the schema has a label in words, its id kept for search', () {
      for (final AttributeSpec spec in attributeSpecs(npc)) {
        final String label = attributeLabels[spec.id] ?? '';
        expect(label, isNotEmpty, reason: '${spec.id} has a label');
        expect(label, matches(RegExp(r'^[A-Z][a-z -]*$')), reason: '${spec.id}: "$label" is sentence case words');
        expect(label.split(' ').length, lessThanOrEqualTo(3), reason: '${spec.id}: "$label"');
      }
      expect(attributeLabels.keys.toSet().difference(attributeTable(npc).keys.toSet()), isEmpty, reason: 'no label for an id the schema dropped');
      expect(attributeLabel('max_health'), 'Max health');
      expect(attributeLabel('minecraft:movement_speed'), 'Speed');
      expect(attributeLabel('new_thing'), 'New thing');
    });

    test('names the attributes a module setting overrides', () {
      expect(attributeOverrides(const <String, Object?>{}), isEmpty);
      expect(attributeOverrides(<String, Object?>{
        'animus:health': <String, Object?>{'killable': true, 'maximumHealth': 40, 'armorFromEquipment': true},
        'animus:combat': <String, Object?>{
          'melee': <String, Object?>{'damage': 4, 'cooldownTicks': 10},
          'targeting': <String, Object?>{'scanRange': 20},
        },
      }).keys, unorderedEquals(<String>['max_health', 'armor', 'armor_toughness', 'attack_damage', 'attack_speed', 'follow_range']));
      expect(attributeOverrides(<String, Object?>{
        'animus:health': <String, Object?>{'maximumHealth': 40},
      })['max_health'], 'Not used: Combat sets max health');
    });

    test('reads an attribute value or its vanilla default', () {
      expect(attributeValue(<String, Object?>{'minecraft:max_health': 40}, 'max_health'), 40);
      expect(attributeValue(<String, Object?>{'max_health': 0}, 'max_health'), 20, reason: 'out of range');
      expect(attributeValue(null, 'max_health'), 20);
      expect(attributeValue(null, 'not_an_attribute'), 0);
    });

    test('a schema without attributes lists none', () {
      expect(attributeSpecs(SchemaView(const <String, Object?>{'type': 'object'})), isEmpty);
    });

    test('checks values against the range', () {
      final AttributeSpec scale = attributeTable(npc)['scale']!;
      expect(scale.problem(2), isNull);
      expect(scale.problem(17), 'expected 0.0625..16 for scale');
      expect(scale.problem(0), 'expected 0.0625..16 for scale');
      expect(scale.problem('big'), 'expected a number');
    });
  });

  group('writing', () {
    test('ids lose the minecraft namespace', () {
      expect(bareAttributeId('minecraft:scale'), 'scale');
      expect(bareAttributeId('scale'), 'scale');
      expect(bareAttributes(<String, Object?>{'minecraft:scale': 2, 'gravity': 0.01}), <String, Object?>{'scale': 2, 'gravity': 0.01});
    });

    test('a set writes the bare id and replaces the namespaced spelling', () {
      expect(withAttribute(null, 'scale', 1.5), <String, Object?>{'scale': 1.5});
      expect(withAttribute(<String, Object?>{'minecraft:scale': 2, 'gravity': 0.01}, 'scale', 3), <String, Object?>{'gravity': 0.01, 'scale': 3});
    });

    test('a reset removes the attribute and drops an empty field', () {
      expect(withAttribute(<String, Object?>{'minecraft:scale': 2, 'gravity': 0.01}, 'scale', null), <String, Object?>{'gravity': 0.01});
      expect(withAttribute(<String, Object?>{'scale': 2}, 'minecraft:scale', null), isNull);
    });
  });

  group('body scale', () {
    test('reads scale bare or namespaced and falls back to 1', () {
      expect(definitionScale(<String, Object?>{'attributes': <String, Object?>{'scale': 2}}), 2);
      expect(definitionScale(<String, Object?>{'attributes': <String, Object?>{'minecraft:scale': 0.5}}), 0.5);
      expect(definitionScale(<String, Object?>{'attributes': <String, Object?>{'scale': 99}}), 1);
      expect(definitionScale(const <String, Object?>{}), 1);
      expect(NpcLook.fromDefinition(<String, Object?>{'type': 'minecraft:player', 'attributes': <String, Object?>{'scale': 2}}).scale, 2);
    });
  });

  group('definition validation', () {
    JObject definition(Map<String, JElement> attributes) {
      final JObject position = JObject()..addString('world', 'world');
      for (final String key in const <String>['x', 'y', 'z', 'yaw', 'pitch']) {
        position.addInt(key, 0);
      }
      final JObject values = JObject();
      for (final MapEntry<String, JElement> entry in attributes.entries) {
        values.add(entry.key, entry.value);
      }
      return JObject()
        ..addInt('schemaVersion', 2)
        ..addInt('revision', 1)
        ..addString('uuid', '00000000-0000-4000-8000-000000000000')
        ..addString('id', 'probe')
        ..addString('group', 'probes')
        ..addString('name', 'Probe')
        ..addString('type', 'minecraft:player')
        ..addBool('spawned', false)
        ..add('position', position)
        ..add('attributes', values);
    }

    test('movement styles name their field like MovementSettings', () {
      JObject movement(String style, String onLand, JObject auto) => JObject()
        ..addString('style', style)
        ..addString('onLand', onLand)
        ..add('auto', auto);
      JObject withMovement(JObject value) => definition(<String, JElement>{})..add('movement', value);
      expect(validateDefinition(withMovement(movement('sneak', 'avoid', JObject()..addBool('swim', false)..addBool('crawl', true)))), isEmpty);
      expect(validateDefinition(withMovement(movement('run', 'fly', JObject()..addInt('swim', 1)..addBool('dive', true)))), <String>[
        r'$.movement.style: expected walk, sneak, sprint, swim, crawl, fly, glide',
        r'$.movement.onLand: expected walk or avoid',
        r'$.movement.auto.dive: unknown field',
        r'$.movement.auto.swim: expected boolean',
      ]);
      expect(validateDefinition(withMovement(JObject()..addString('style', 'fly'))), isEmpty);
      expect(validateDefinition(withMovement(JObject()..addBool('fly', true))), <String>[r'$.movement.fly: unknown field']);
    });

    test('accepts known attributes in range, either spelling', () {
      expect(validateDefinition(definition(<String, JElement>{'scale': JInt.of(2), 'minecraft:gravity': JInt.of(0)})), isEmpty);
    });

    test('reports unknown ids, non-numbers, out of range and doubles as NpcAttributes does', () {
      expect(validateDefinition(definition(<String, JElement>{
        'wings': JInt.of(1),
        'gravity': const JString('low'),
        'scale': JInt.of(20),
      })), <String>[
        r'$.attributes.wings: unknown attribute wings; expected a vanilla attribute id such as scale, gravity or movement_speed',
        r'$.attributes.gravity: expected a number',
        r'$.attributes.scale: expected 0.0625..16 for scale',
      ]);
      expect(validateDefinition(definition(<String, JElement>{'scale': JInt.of(2), 'minecraft:scale': JInt.of(3)})), <String>[
        r'$.attributes.minecraft:scale: scale is set twice',
      ]);
    });
  });
}
