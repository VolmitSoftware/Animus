import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:animus_editor/logic/citizens/citizens_converter.dart';
import 'package:animus_editor/logic/citizens/citizens_review.dart';
import 'package:animus_editor/logic/citizens/definition_validation.dart';
import 'package:animus_editor/logic/citizens/java_json.dart';
import 'package:animus_editor/logic/citizens/java_lang.dart';
import 'package:animus_editor/logic/citizens/sentinel.dart';
import 'package:animus_editor/logic/citizens/snake_yaml.dart';
import 'package:animus_editor/state/workspace_route.dart';
import 'package:archive/archive.dart';
import 'package:test/test.dart';

import 'fixtures.dart';

const String _root = 'citizens';

Map<String, Object?> _index() => readFixture('$_root/index.json')! as Map<String, Object?>;

String _source(String name) => File('${schemaDirectory.path}/fixtures/$_root/$name').readAsStringSync();

void main() {
  group('shared Citizens golden fixtures', () {
    for (final Object? entry in _index()['cases']! as List<Object?>) {
      final Map<String, Object?> definition = entry! as Map<String, Object?>;
      final String name = definition['name']! as String;
      test('$name converts to the committed staged tree byte for byte', () {
        final Map<String, String> worlds = <String, String>{
          for (final MapEntry<String, Object?> world in (definition['worldMap']! as Map<String, Object?>).entries) world.key: world.value! as String,
        };
        final CitizensPreview preview = convertCitizens(_source(definition['source']! as String), worlds);
        final Map<String, Uint8List> expected = fixtureFiles('$_root/expected/$name');
        final Map<String, Uint8List> actual = <String, Uint8List>{
          for (final MapEntry<String, String> file in preview.files.entries) file.key: javaUtf8(file.value),
        };
        expect(actual.keys.toList()..sort(), expected.keys.toList()..sort());
        for (final String path in expected.keys) {
          expect(utf8.decode(actual[path]!), utf8.decode(expected[path]!), reason: '$name: $path');
          expect(actual[path], expected[path], reason: '$name: $path bytes');
        }
        expect(preview.outputHash(), importOutputHash(expected));
        final Map<String, Object?> report = jsonDecode(utf8.decode(expected['report.json']!)) as Map<String, Object?>;
        expect(preview.id, report['importId']);
        expect(preview.records, report['records']);
        expect(preview.valid, report['valid']);
      });
    }

    final List<File> invalid = Directory('${schemaDirectory.path}/fixtures/$_root/invalid')
        .listSync()
        .whereType<File>()
        .where((File file) => file.path.endsWith('.yml'))
        .toList()
      ..sort((File a, File b) => a.path.compareTo(b.path));
    test('invalid inputs exist', () => expect(invalid, isNotEmpty));
    for (final File file in invalid) {
      test('${file.uri.pathSegments.last} is rejected', () {
        expect(() => convertCitizens(file.readAsStringSync(), const <String, String>{}), throwsA(isA<CitizensImportException>()));
      });
    }
  });

  group('bundle', () {
    test('unzips into the data folder with the source copy and the staged import in path order', () {
      final CitizensPreview preview = convertCitizens(_source('absent.yml'), const <String, String>{});
      final Map<String, Uint8List> bundle = preview.bundle();
      expect(bundle['imports/saves.yml'], utf8.encode(_source('absent.yml')));
      expect(bundle.keys.where((String path) => path.startsWith('imports/${preview.id}/')).length, preview.files.length);
      final Archive archive = ZipDecoder().decodeBytes(citizensBundleZip(preview));
      final List<String> names = <String>[for (final ArchiveFile file in archive.files) file.name];
      expect(names, List<String>.of(names)..sort());
      expect(names, contains('imports/${preview.id}/report.json'));
      expect(citizensBundleZip(preview), citizensBundleZip(convertCitizens(_source('absent.yml'), const <String, String>{})));
    });

    test('world mapping lines and the review aggregate', () {
      expect(parseWorldMappings(' legacy = world \n\nOld Nether=world_nether\n'), <String, String>{'legacy': 'world', 'Old Nether': 'world_nether'});
      for (final String bad in <String>['legacy', 'a=', 'a=b\na=c']) {
        expect(() => parseWorldMappings(bad), throwsA(isA<CitizensImportException>()), reason: bad);
      }
      final CitizensReview review = CitizensReview.of(convertCitizens(_source('traits.yml'), const <String, String>{'legacy': 'world'}));
      final Map<String, Object?> aggregate = review.aggregate();
      expect(aggregate['records'], 33);
      expect((aggregate['classification']! as Map<String, int>).values.fold<int>(0, (int a, int b) => a + b), greaterThan(100));
      expect(jsonEncode(aggregate), isNot(contains('Guard Captain')));
      expect(EditorRoute.parse('#/citizens'), const CitizensRoute());
    });

    test('patrols, look-close, text and commands are counted in the review aggregate', () {
      final CitizensReview review = CitizensReview.of(convertCitizens(_source('runtime.yml'), const <String, String>{'old_town': 'town'}));
      final Map<String, Object?> aggregate = review.aggregate();
      expect(aggregate['behaviors'], 3);
      expect(aggregate['conversions'], <String, int>{
        'commandBehaviors': 1, 'lookClose': 2, 'routeGuides': 1, 'routePoints': 6, 'routes': 3, 'skinsKept': 0, 'textBehaviors': 2, //
      });
      expect(review.preview.files.keys.where((String path) => path.startsWith('behaviors/citizens/')).toList()..sort(),
          <String>['behaviors/citizens/commands-3.json', 'behaviors/citizens/text-2.json', 'behaviors/citizens/text-3.json']);
    });

    test('a mob body keeps its Citizens skin for review and the preview applies the activation rules', () {
      final CitizensPreview preview = convertCitizens(_source('traits.yml'), const <String, String>{'legacy': 'world'});
      final String path = preview.files.keys.firstWhere((String file) => file.endsWith('/citizens-32.json'));
      final Map<String, Object?> frog = jsonDecode(preview.files[path]!) as Map<String, Object?>;
      expect(frog.containsKey('skin'), isFalse);
      final Map<String, Object?> modules = frog['modules']! as Map<String, Object?>;
      expect((modules['citizens:review']! as Map<String, Object?>).containsKey('skintrait'), isTrue);
      final Map<String, Object?> report = jsonDecode(preview.files['reports/32.json']!) as Map<String, Object?>;
      expect((report['fields']! as Map<String, Object?>)['/traits/skintrait/textureRaw'], 'UNSUPPORTED_PRESERVED');
      expect((report['reasons']! as Map<String, Object?>)['/traits/skintrait/textureRaw'],
          'Citizens draws skins only on player NPCs, so this frog never showed it; kept for when the NPC becomes a player');
      expect(CitizensReview.of(preview).conversions['skinsKept'], 1);
      final JObject skinnedMob = JObject()
        ..addString('type', 'minecraft:frog')
        ..addString('skin', 'a' * 64);
      expect(activationErrors(skinnedMob), <String>[r'$.skin: textures require a player body']);
    });

    test('world mappings must be nonblank', () {
      expect(() => convertCitizens('npc: {}\n', const <String, String>{'a': ' '}), throwsA(isA<CitizensImportException>()));
    });
  });

  group('Java number formatting', () {
    test('Double.toString', () {
      final Map<double, String> cases = <double, String>{
        0: '0.0',
        1: '1.0',
        1.5: '1.5',
        100: '100.0',
        0.001: '0.001',
        0.0001: '1.0E-4',
        1e-5: '1.0E-5',
        9999999: '9999999.0',
        1e7: '1.0E7',
        123456789.123: '1.23456789123E8',
        1.7976931348623157e308: '1.7976931348623157E308',
        4.9e-324: '4.9E-324',
        0.1: '0.1',
        0.30000000000000004: '0.30000000000000004',
        2e23: '2.0E23',
        -25: '-25.0',
        43.0: '43.0',
        6.45: '6.45',
      };
      cases.forEach((double value, String text) => expect(javaDoubleToString(value), text, reason: '$value'));
      // Map keys cannot hold both zeros: 0 == -0.0.
      expect(javaDoubleToString(-0.0), '-0.0');
    });

    test('Double.parseDouble accepts Java forms', () {
      expect(javaParseDouble(' 1.5f '), 1.5);
      expect(javaParseDouble('1.'), 1.0);
      expect(javaParseDouble('.5'), 0.5);
      expect(javaParseDouble('0x1.8p1'), 3.0);
      expect(javaParseDouble('-Infinity'), double.negativeInfinity);
      expect(() => javaParseDouble('1,5'), throwsFormatException);
      expect(() => javaParseDouble('true'), throwsFormatException);
    });

    test('Math.round and casts', () {
      expect(javaRound(0.49999999999999994), BigInt.zero);
      expect(javaRound(-2.5), BigInt.from(-2));
      expect(javaRound(2.5), BigInt.from(3));
      expect(javaDoubleToLong(1e30), javaLongMax);
      expect(javaDoubleToInt(-1e30), -2147483648);
    });

    test('BigDecimal exact values', () {
      expect(JavaDecimal.parse('1.0E7')!.intValueExact(), 10000000);
      expect(JavaDecimal.parse('5.0')!.intValueExact(), 5);
      expect(JavaDecimal.parse('1.5')!.intValueExact(), isNull);
      expect(JavaDecimal.parse('2147483648')!.intValueExact(), isNull);
      expect(JavaDecimal.parse('abc'), isNull);
      expect(JavaDecimal.parse('+.5e1')!.intValueExact(), 5);
    });
  });

  group('SnakeYAML resolution', () {
    JElement value(String text) => parseCitizensYaml('v: $text\n').get('v')!;

    test('YAML 1.1 implicit types', () {
      expect(value('yes'), const JBool(true));
      expect(value('Off'), const JBool(false));
      expect(value('y'), const JString('y'));
      expect(value('017'), JInt.of(15));
      expect(value('08'), const JString('08'));
      expect(value('0o17'), const JString('0o17'));
      expect(value('0x_1F'), JInt.of(31));
      expect(value('0b101'), JInt.of(5));
      expect(value('1_000'), JInt.of(1000));
      expect(value('190:20:30'), JInt.of(685230));
      expect(value('9223372036854775808'), JInt(BigInt.parse('9223372036854775808')));
      expect((value('1e3') as JDouble).value, 1000.0);
      expect((value('1:30.5') as JDouble).value, 90.5);
      expect(value('.5').asPrimitive.asString, '0.5');
      expect(value('-0.0').asPrimitive.asString, '-0.0');
      expect(value('~'), JNull.instance);
      expect(value(''), JNull.instance);
      expect(value('"yes"'), const JString('yes'));
      expect(value('!!str 017'), const JString('017'));
      expect(value('1.2.3'), const JString('1.2.3'));
    });

    test('plain scalars fold like YAML', () {
      expect(parseCitizensYaml('v: one\n  two  three\n\n  four\n').get('v'), const JString('one two  three\nfour'));
    });

    test('numeric keys normalize to their Java text', () {
      expect(parseCitizensYaml('m: {1: a, 2.5: b, 0x10: c}\n').object('m')!.keys, <String>['1', '2.5', '16']);
    });

    test('merge keys follow SnakeYAML', () {
      final JObject merged = parseCitizensYaml('m: {<<: {a: 1, b: 2}, b: 3, c: 4}\n').object('m')!;
      expect(merged.toString(), '{"a":1,"b":3,"c":4}');
    });

    test('limits and unsupported values are refused', () {
      for (final String text in <String>['v: 2026-11-04\n', 'v: .inf\n', 'a: &x 1\nb: *x\n', '{yes: 1}\n', 'v: !foo x\n', '${'[' * 65}${']' * 65}\n']) {
        expect(() => parseCitizensYaml(text), throwsA(isA<CitizensImportException>()), reason: text);
      }
    });
  });

  group('Gson printer', () {
    test('pretty printing and escapes', () {
      final JObject object = JObject()
        ..addString('text', 'q" b\\ t\t n\n c\u0001 l  <&>=\' é')
        ..add('empty', JObject())
        ..add('list', JArray())
        ..add('nothing', null)
        ..addDouble('d', 1e7)
        ..addInt('i', 3)
        ..add('nested', JArray(<JElement>[JInt.of(1), JObject()..addBool('b', true)]));
      expect(writeJson(object), '{\n'
          '  "text": "q\\" b\\\\ t\\t n\\n c\\u0001 l\\u2028 <&>=\' é",\n'
          '  "empty": {},\n'
          '  "list": [],\n'
          '  "nothing": null,\n'
          '  "d": 1.0E7,\n'
          '  "i": 3,\n'
          '  "nested": [\n'
          '    1,\n'
          '    {\n'
          '      "b": true\n'
          '    }\n'
          '  ]\n'
          '}\n');
    });

    test('lone surrogates encode as ? like Java', () {
      expect(javaUtf8('a\uD800b'), <int>[0x61, 0x3F, 0x62]);
    });
  });

  group('Java identity helpers', () {
    test('UUID.nameUUIDFromBytes and lenient fromString', () {
      expect(nameUuid('animus:citizens:3'), matches(RegExp(r'^[0-9a-f]{8}-[0-9a-f]{4}-3[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$')));
      expect(javaUuidValue('1-1-1-1-1'), javaUuidValue('00000001-0001-0001-0001-000000000001'));
      expect(javaUuidValue('0A1B2C3D-0000-4000-8000-000000000003'), javaUuidValue('0a1b2c3d-0000-4000-8000-000000000003'));
      expect(javaUuidValue('NOT-A-UUID'), isNull);
      expect(uuidOf(BigInt.from(-1), BigInt.one), 'ffffffff-ffff-ffff-0000-000000000001');
    });

    test('name patterns', () {
      expect(validNamePattern('Grumpy.*'), isTrue);
      expect(validNamePattern('[bad'), isFalse);
      expect(validNamePattern('a{'), isFalse);
      expect(validNamePattern('a{2,3}'), isTrue);
      expect(validNamePattern('(?i)bob'), isTrue);
      expect(validNamePattern('a*+'), isTrue);
      expect(validNamePattern(''), isFalse);
    });

    test('group folders', () {
      expect(citizensGroup('World Nether', JInt.of(-1), JInt.of(600)), 'world-nether-r-1_1');
      expect(citizensGroup('__spawn__', null, null), 'spawn');
      expect(citizensGroup(r'$$$', JInt.of(0), JInt.of(0)), 'citizens');
      expect(citizensGroup('world', const JString('1'), JInt.of(0)), 'world');
      expect(citizensGroup('a' * 80, null, null).length, 48);
    });
  });
}
