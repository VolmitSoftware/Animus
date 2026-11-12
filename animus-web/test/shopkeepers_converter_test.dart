import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:animus_editor/logic/citizens/citizens_converter.dart' show importOutputHash;
import 'package:animus_editor/logic/citizens/java_json.dart';
import 'package:animus_editor/logic/citizens/java_lang.dart';
import 'package:animus_editor/logic/shopkeepers/shopkeepers_converter.dart';
import 'package:animus_editor/logic/shopkeepers/shopkeepers_items.dart';
import 'package:archive/archive.dart';
import 'package:test/test.dart';

import 'fixtures.dart';

const String _root = 'shopkeepers';

Map<String, Object?> _index() => readFixture('$_root/index.json')! as Map<String, Object?>;

String _source(String name) => File('${schemaDirectory.path}/fixtures/$_root/$name').readAsStringSync();

String? _optional(Map<String, Object?> definition, String key) => definition[key] == null ? null : _source(definition[key]! as String);

final DateTime _fixed = DateTime(2026, 5, 1);

void main() {
  group('shared Shopkeepers golden fixtures', () {
    for (final Object? entry in _index()['cases']! as List<Object?>) {
      final Map<String, Object?> definition = entry! as Map<String, Object?>;
      final String name = definition['name']! as String;
      test('$name converts to the committed staged tree byte for byte', () {
        final ShopkeepersPreview preview = convertShopkeepers(_source(definition['source']! as String),
            config: _optional(definition, 'config'), citizens: _optional(definition, 'citizens'));
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
        expect(preview.shops, report['shops']);
        expect(preview.npcs, report['npcs']);
      });
    }

    final List<File> invalid = Directory('${schemaDirectory.path}/fixtures/$_root/${_index()['invalid']}')
        .listSync()
        .whereType<File>()
        .where((File file) => file.path.endsWith('.yml'))
        .toList()
      ..sort((File a, File b) => a.path.compareTo(b.path));
    test('invalid inputs exist', () => expect(invalid, isNotEmpty));
    for (final File file in invalid) {
      test('${file.uri.pathSegments.last} is rejected', () {
        expect(() => convertShopkeepers(file.readAsStringSync()), throwsA(isA<ShopkeepersImportException>()));
      });
    }
  });

  group('bundle', () {
    test('unzips the save, its config and the staged import in path order', () {
      final String save = _source('player.yml');
      final String config = _source('player-config.yml');
      final ShopkeepersPreview preview = convertShopkeepers(save, config: config);
      final Map<String, Uint8List> bundle = preview.bundle();
      expect(bundle['imports/shopkeepers/save.yml'], utf8.encode(save));
      expect(bundle['imports/shopkeepers/config.yml'], utf8.encode(config));
      expect(bundle.keys.where((String path) => path.startsWith('imports/shopkeepers/${preview.id}/')).length, preview.files.length);
      final Archive archive = ZipDecoder().decodeBytes(shopkeepersBundleZip(preview));
      final List<String> names = <String>[for (final ArchiveFile file in archive.files) file.name];
      expect(names, List<String>.of(names)..sort());
      expect(names, contains('imports/shopkeepers/${preview.id}/report.json'));
      expect(shopkeepersBundleZip(preview, modified: _fixed), shopkeepersBundleZip(convertShopkeepers(save, config: config), modified: _fixed));
      expect(convertShopkeepers(_source('minimal.yml')).bundle().keys, isNot(contains('imports/shopkeepers/config.yml')));
    });

    test('aggregate counts', () {
      final ShopkeepersPreview preview = convertShopkeepers(_source('admin.yml'), citizens: _source('admin-citizens.yml'));
      expect(preview.shops, 6);
      expect(preview.tradeOffers + preview.staticOffers + preview.skippedOffers, greaterThan(0));
      expect(preview.linksUnchecked, 0);
    });
  });

  group('Java semantics', () {
    test('item ids and integers', () {
      expect(shopkeepersItemId(' STONE\u0001'), 'minecraft:stone');
      expect(shopkeepersItemId('air'), isNull);
      expect(shopkeepersItemId('Foo:Bar'), 'foo:bar');
      expect(shopkeepersInteger(const JDouble(3.0)), 3);
      expect(shopkeepersInteger(const JDouble(3.5)), isNull);
      expect(shopkeepersInteger(const JDouble(double.nan)), isNull);
      expect(shopkeepersInteger(JInt(BigInt.parse('2147483648'))), isNull);
      expect(shopkeepersInteger(const JString('3')), isNull);
    });

    test('strip and trim', () {
      expect(javaStrip('  a b 　'), 'a b');
      expect(javaStrip(' a'), ' a');
      expect(javaTrim('\u0001 a  '), 'a  ');
    });

    test('a component that is not SNBT text keeps the components in imported', () {
      final ShopkeepersItem item = convertShopkeepersItem(JObject()
        ..addString('id', 'minecraft:stone')
        ..add('components', JObject()..addInt('minecraft:max_stack_size', 3)))!;
      expect(item.exact, isFalse);
      expect(item.item.components, isNull);
      expect(item.item.imported!.toString(), '{"source":"Shopkeepers","unconverted":{"components":{"minecraft:max_stack_size":3}}}');
    });
  });
}
