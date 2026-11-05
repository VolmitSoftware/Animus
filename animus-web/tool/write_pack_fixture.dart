/// Writes `schema/fixtures/pack/robot/` from the editor's Blockbench import of
/// `schema/fixtures/pack/robot.bbmodel`: the rig, its animations and the
/// resource pack sources. The server's importer must produce the same files;
/// `test/pack_fixture_test.dart` and the Java `RigPackContractTest` check it.
///
/// Run from animus-web/: `dart run tool/write_pack_fixture.dart`.
library;

import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:animus_editor/logic/bbmodel_import.dart';
import 'package:animus_editor/logic/rig_edit.dart';

const String fixtureRoot = '../schema/fixtures/pack';
const String rigId = 'town:robot';

void main() {
  final Uint8List source = File('$fixtureRoot/robot.bbmodel').readAsBytesSync();
  final BbmodelImport imported = importBbmodel(source, rigId: rigId);
  final Directory out = Directory('$fixtureRoot/robot');
  if (out.existsSync()) out.deleteSync(recursive: true);
  void write(String path, List<int> bytes) {
    final File file = File('${out.path}/$path');
    file.parent.createSync(recursive: true);
    file.writeAsBytesSync(bytes);
  }

  write('rigs/town/robot.json', utf8.encode(encodeDocument(imported.rig)));
  for (final JsonMap clip in imported.animations) {
    write('animations/${(clip['id']! as String).replaceFirst(':', '/')}.json', utf8.encode(encodeDocument(clip)));
  }
  for (final MapEntry<String, Uint8List> resource in imported.resources.entries) {
    write(resource.key, resource.value);
  }
  stdout.writeln('Wrote ${out.path}: ${imported.animations.length} animations, ${imported.resources.length} resources');
}
