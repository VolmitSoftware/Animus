import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:animus_editor/logic/bbmodel_import.dart';
import 'package:animus_editor/logic/rig_edit.dart';
import 'package:test/test.dart';

/// The shared pack fixture the server's importer is checked against too.
void main() {
  test('should import the shared Blockbench fixture into exactly its golden files', () {
    const String root = '../schema/fixtures/pack';
    final BbmodelImport imported = importBbmodel(File('$root/robot.bbmodel').readAsBytesSync(), rigId: 'town:robot');
    final Map<String, List<int>> produced = <String, List<int>>{
      'rigs/town/robot.json': utf8.encode(encodeDocument(imported.rig)),
      for (final JsonMap clip in imported.animations)
        'animations/${(clip['id']! as String).replaceFirst(':', '/')}.json': utf8.encode(encodeDocument(clip)),
      for (final MapEntry<String, Uint8List> resource in imported.resources.entries) resource.key: resource.value,
    };
    final Directory golden = Directory('$root/robot');
    final List<String> stored = <String>[
      for (final FileSystemEntity file in golden.listSync(recursive: true))
        if (file is File) file.path.substring(golden.path.length + 1).replaceAll(r'\', '/'),
    ]..sort();
    expect(stored, produced.keys.toList()..sort(), reason: 'run dart run tool/write_pack_fixture.dart after changing the import');
    for (final String path in stored) {
      expect(File('${golden.path}/$path').readAsBytesSync(), produced[path], reason: path);
    }
  });
}
