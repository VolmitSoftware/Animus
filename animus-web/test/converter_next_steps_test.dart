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

    test('leads with the NPC and names the field: the saves.yml key in Advanced, the trait in Simple', () {
      const String op = 'citizens-3: /traits/commandtrait/commands/4/op: the command ran as an operator player; it now runs from the console';
      expect(plainWarning(op, names), 'NPC #3 (Baker), traits.commandtrait.commands.4.op: The command ran as an operator player; it now runs from the console');
      expect(plainWarning(op, names, advanced: false), 'NPC #3 (Baker), commandtrait trait: The command ran as an operator player; it now runs from the console');
      expect(plainWarning('citizens-7: /waypoints: 2 points in another world or without usable coordinates were left out', names),
          'NPC #7, waypoints: 2 points in another world or without usable coordinates were left out');
      expect(plainWarning('citizens-0: /traits/dropstrait/drops/2: not an item; not converted', const <String, String>{'0': 'Toll Keeper'}, advanced: false),
          'NPC #0 (Toll Keeper), dropstrait trait: Not an item; not converted');
    });

    test('names the field of an NPC that did not convert: every key in Advanced, the setting in Simple', () {
      const String issue = r'citizens-15: $.position.world: expected nonblank string; $.position.x: expected finite coordinate';
      const Map<String, String> sentinel = <String, String>{'15': 'Not Sentinel'};
      expect(plainWarning(issue, sentinel), 'NPC #15 (Not Sentinel), position.world: Expected nonblank string; position.x: expected finite coordinate');
      expect(plainWarning(issue, sentinel, advanced: false), 'NPC #15 (Not Sentinel), position: Expected nonblank string');
      expect(plainWarning(r'citizens-9: $.uuid: duplicate NPC UUID', const <String, String>{}, advanced: false), 'NPC #9, uuid: Duplicate NPC UUID');
      expect(plainWarning('citizens-9: the save has no world', const <String, String>{}, advanced: false), 'NPC #9: The save has no world');
    });

    test('names the field of a reference warning written without a colon', () {
      expect(plainWarning('citizens-4: /traits/sentinel/guardedNPC NPC reference missing', const <String, String>{'4': 'Watch'}, advanced: false),
          'NPC #4 (Watch), sentinel trait: NPC reference missing');
      expect(plainWarning('citizens-4: /traits/sentinel/guardedNPC NPC reference missing', const <String, String>{'4': 'Watch'}),
          'NPC #4 (Watch), traits.sentinel.guardedNPC: NPC reference missing');
    });

    test('counts one point as one point', () {
      expect(plainWarning('citizens-3: /waypoints: 1 points in another world or without usable coordinates were left out', names),
          'NPC #3 (Baker), waypoints: 1 point in another world or without usable coordinates was left out');
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
