import 'dart:convert';
import 'dart:typed_data';

import 'package:animus_editor/logic/citizens/denizen_folder.dart';
import 'package:animus_editor/logic/denizen/denizen_converter.dart';
import 'package:test/test.dart';

import 'fixtures.dart';

/// The picked files of a fixture folder as the browser names them: the
/// chosen folder's name first.
Map<String, String> _picked(String root, String folder) => <String, String>{
      for (final MapEntry<String, Uint8List> file in fixtureFiles(root).entries) '$folder/${file.key}': utf8.decode(file.value),
    };

void main() {
  test('should read a picked Denizen folder the way the server importer does', () {
    final DenizenFolder folder = denizenFolderOf(_picked('denizen/town/Denizen', 'Denizen'));
    expect(folder.input.scripts.keys, <String>['broken.dsc', 'crier.dsc', 'extra/again.dsc', 'town.dsc']);
    expect(folder.input.serverFlags, isNotNull);
    expect(folder.input.playerFlags.keys, <String>['00000000-0000-4000-8000-0000000000a1', 'notauuid']);
    expect(folder.skipped, isEmpty);
    expect(folder.empty, isFalse);
  });

  test('should convert a picked folder to the committed staged tree', () {
    final String citizens = utf8.decode(fixtureFiles('denizen/town')['saves.yml']!);
    final DenizenPreview preview = convertDenizen(denizenFolderOf(_picked('denizen/town/Denizen', 'Denizen'), citizens: citizens).input);
    final Map<String, Uint8List> expected = fixtureFiles('denizen/expected/town');
    expect(preview.files.keys.toSet(), expected.keys.toSet());
  });

  test('should list files it does not read and find nothing in an unrelated folder', () {
    final DenizenFolder folder = denizenFolderOf(<String, String>{
      'plugins/notes.txt': 'x',
      'plugins/Denizen/config.yml': 'x',
      'plugins/Denizen/scripts/a.dsc': 'a:\n  type: task\n',
    });
    expect(folder.skipped, <String>['plugins/Denizen/config.yml', 'plugins/notes.txt']);
    expect(folder.scripts, 1);
    expect(denizenFolderOf(<String, String>{'notes.txt': 'x'}).empty, isTrue);
  });
}
