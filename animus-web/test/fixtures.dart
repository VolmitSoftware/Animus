import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

/// The repository's shared schema folder; tests run from animus-web/.
final Directory schemaDirectory = Directory('../schema');

Object? readJson(String pathUnderSchema) => jsonDecode(File('${schemaDirectory.path}/$pathUnderSchema').readAsStringSync());

Object? readFixture(String pathUnderFixtures) => readJson('fixtures/$pathUnderFixtures');

Map<String, Object?> fixtureIndex() => readFixture('index.json')! as Map<String, Object?>;

/// Every file under [root] (relative to schema/fixtures), by `/` path.
Map<String, Uint8List> fixtureFiles(String root) {
  final Directory directory = Directory('${schemaDirectory.path}/fixtures/$root');
  final Map<String, Uint8List> files = <String, Uint8List>{};
  for (final File file in directory.listSync(recursive: true).whereType<File>()) {
    files[file.path.substring(directory.path.length + 1).replaceAll(r'\', '/')] = file.readAsBytesSync();
  }
  return files;
}
