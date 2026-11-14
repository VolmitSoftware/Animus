import 'dart:io';
import 'dart:typed_data';

import 'package:animus_editor/logic/citizens/citizens_converter.dart';
import 'package:animus_editor/logic/citizens/citizens_review.dart';
import 'package:animus_editor/logic/citizens/converted_workspace.dart';
import 'package:animus_editor/logic/data_folder.dart';
import 'package:animus_editor/logic/shared_schemas.dart';
import 'package:animus_editor/model/animus_document.dart';
import 'package:animus_editor/model/document_kind.dart';
import 'package:test/test.dart';

import 'fixtures.dart';

void main() {
  group('converted workspace', () {
    test('opens the Citizens staged tree at the data-folder root as NPC documents', () {
      final CitizensPreview preview = convertCitizens(File('${schemaDirectory.path}/fixtures/citizens/runtime.yml').readAsStringSync(), const <String, String>{'old_town': 'town'});
      final Map<String, Uint8List> files = convertedWorkspaceFiles(citizens: preview);
      expect(files.keys.any((String path) => path.startsWith('imports/')), isFalse);
      final DataFolderResult opened = openDataFolder(files, SharedSchemas.embedded);
      final int staged = preview.files.keys.where((String path) => path.startsWith('npcs/')).length;
      expect(staged, greaterThan(0));
      expect(opened.documents.where((AnimusDocument document) => document.kind == DocumentKind.npc).length, staged);
      expect(opened.documents.where((AnimusDocument document) => document.kind == DocumentKind.behavior), isNotEmpty);
      expect(opened.ignored, contains('report.json'));
    });

    test('names the workspace after what was converted', () {
      expect(convertedWorkspaceName(citizens: true, shops: false, denizen: true), 'Citizens, Denizen import');
      expect(convertedWorkspaceName(citizens: false, shops: false, denizen: false), 'Converted');
    });
  });

  group('readable migration warnings', () {
    const Map<String, String> names = <String, String>{'3': 'Baker'};

    test('leads with the NPC and drops the JSON pointer', () {
      expect(plainWarning('citizens-3: /traits/commandtrait/commands/4/op: the command ran as an operator player; it now runs from the console', names),
          'NPC #3 (Baker): The command ran as an operator player; it now runs from the console');
      expect(plainWarning('citizens-7: /waypoints: 2 points in another world or without usable coordinates were left out', names),
          'NPC #7: 2 points in another world or without usable coordinates were left out');
    });

    test('counts one point as one point', () {
      expect(plainWarning('citizens-3: /waypoints: 1 points in another world or without usable coordinates were left out', names),
          'NPC #3 (Baker): 1 point in another world or without usable coordinates was left out');
    });

    test('keeps a warning about the whole save as written', () {
      expect(plainWarning('lookclose/realistic: this save only ever writes false', names), 'lookclose/realistic: this save only ever writes false');
    });

    test('names every converted NPC by its Citizens id', () {
      final CitizensPreview preview = convertCitizens(File('${schemaDirectory.path}/fixtures/citizens/runtime.yml').readAsStringSync(), const <String, String>{});
      final Map<String, String> found = citizensNames(preview);
      expect(found, isNotEmpty);
      expect(found.keys.every((String key) => int.tryParse(key) != null), isTrue);
    });
  });
}
