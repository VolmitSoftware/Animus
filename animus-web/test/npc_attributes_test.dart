import 'package:animus_editor/logic/citizens/definition_validation.dart';
import 'package:animus_editor/logic/citizens/java_json.dart';
import 'package:animus_editor/logic/inspect/inspect_schemas.dart';
import 'package:animus_editor/logic/inspect/schema_fields.dart';
import 'package:animus_editor/logic/npc/npc_attributes.dart';
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
        ..addInt('schemaVersion', 1)
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
