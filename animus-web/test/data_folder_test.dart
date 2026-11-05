import 'dart:convert';
import 'dart:typed_data';

import 'package:animus_editor/logic/data_folder.dart';
import 'package:animus_editor/logic/shared_schemas.dart';
import 'package:animus_editor/model/animus_document.dart';
import 'package:animus_editor/model/document_kind.dart';
import 'package:archive/archive.dart';
import 'package:test/test.dart';

import 'fixtures.dart';

void main() {
  test('should locate documents by their data folder path', () {
    expect(locateDocument('npcs/town/guard.json'), const DocumentLocation(DocumentKind.npc, 'town/guard'));
    expect(locateDocument('behaviors/town/greet/morning.json'), const DocumentLocation(DocumentKind.behavior, 'town:greet/morning'));
    expect(locateDocument('packs/quest/pack.json'), const DocumentLocation(DocumentKind.pack, 'quest'));
    expect(locateDocument('packs/quest/behaviors/quest/giver.json'), const DocumentLocation(DocumentKind.behavior, 'quest:giver'));
    expect(locateDocument('shops/town/baker.json'), const DocumentLocation(DocumentKind.shop, 'town:baker'));
    expect(locateDocument('gui/inventories/town/menu.json'), const DocumentLocation(DocumentKind.inventory, 'town:menu'));
    expect(locateDocument('gui/menus/town/main.json'), const DocumentLocation(DocumentKind.menu, 'town/main'));
    expect(locateDocument('gui/presets.json'), const DocumentLocation(DocumentKind.presets, 'presets'));
    expect(locateDocument('rigs/town/golem.json'), const DocumentLocation(DocumentKind.rig, 'town:golem'));
    expect(locateDocument('animations/town/golem/walk.json'), const DocumentLocation(DocumentKind.animation, 'town:golem/walk'));
    for (final String ignored in <String>[
      'config.toml',
      'npcs/guard.json',
      'npcs/town/nested/guard.json',
      'npcs/Town/guard.json',
      'state/npc-definitions/last-valid/town/guard.json',
      'schemas/npc.schema.json',
      'gui/inventories/menu.json',
      'packs/quest/npcs/examples/giver.json',
    ]) {
      expect(locateDocument(ignored), isNull, reason: ignored);
    }
  });

  test('should find the data folder inside a zip with leading folders', () {
    final Map<String, Uint8List> export = fixtureFiles('export');
    final Archive archive = Archive();
    for (final MapEntry<String, Uint8List> file in export.entries) {
      archive.addFile(ArchiveFile.bytes('server/plugins/Animus/${file.key}', file.value));
    }
    archive.addFile(ArchiveFile.bytes('server/plugins/Other/npcs/x/y.json', Uint8List.fromList(utf8.encode('{}'))));
    archive.addFile(ArchiveFile.bytes('../escape.json', Uint8List.fromList(utf8.encode('{}'))));
    final Uint8List zip = Uint8List.fromList(ZipEncoder().encodeBytes(archive));
    final Map<String, Uint8List> files = filesFromZip(zip);
    expect(files.keys.where((String path) => path.contains('..')), isEmpty);
    final DataFolderResult result = openDataFolder(files, SharedSchemas.embedded);
    expect(result.prefix, 'server/plugins/Animus/');
    expect(result.documents.map((AnimusDocument document) => document.id),
        containsAll(<String>['examples/greeter', 'shop:bakery', 'shop:counter', 'greeter:greeter', 'shop:keeper', 'shop']));
    expect(result.documents, hasLength(10));
  });

  test('should report invalid JSON and id mismatches as errors', () {
    final SharedSchemas schemas = SharedSchemas.embedded;
    final AnimusDocument broken = validateSource(const DocumentLocation(DocumentKind.shop, 'town:baker'), 'shops/town/baker.json', '{"schemaVersion": 1,', schemas);
    expect(broken.valid, isFalse);
    expect(broken.diagnostics.single.code, 'json');
    final String moved = jsonEncode(readFixture('documents/shop/id-mismatch.json'));
    final AnimusDocument mismatch = validateSource(const DocumentLocation(DocumentKind.shop, 'shop:bakery'), 'shops/shop/bakery.json', moved, schemas);
    expect(mismatch.diagnostics.map((Diagnostic d) => d.code), <String>['id-mismatch']);
  });
}

