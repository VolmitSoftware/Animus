import 'dart:io';
import 'dart:typed_data';

import 'package:animus_editor/logic/citizens/citizens_converter.dart';
import 'package:animus_editor/logic/citizens/citizens_review.dart';
import 'package:animus_editor/logic/citizens/converted_workspace.dart';
import 'package:animus_editor/logic/data_folder.dart';
import 'package:animus_editor/logic/offline/kind_purpose.dart';
import 'package:animus_editor/logic/offline/sync_step.dart';
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

  group('converter next step', () {
    ConverterStep step({bool citizens = false, bool citizensDone = false, bool shops = false, bool shopsDone = false, bool denizen = false, bool denizenDone = false, String? state}) =>
        converterNextStep(
          citizensChosen: citizens,
          citizensConverted: citizensDone,
          shopsChosen: shops,
          shopsConverted: shopsDone,
          denizenChosen: denizen,
          denizenConverted: denizenDone,
          importState: state,
        );

    test('converts what is chosen first, then opens the output', () {
      expect(step(), ConverterStep.none);
      expect(step(citizens: true, shops: true), ConverterStep.convertCitizens);
      expect(step(citizens: true, citizensDone: true, shops: true), ConverterStep.convertShops);
      expect(step(denizen: true), ConverterStep.convertDenizen);
      expect(step(citizens: true, citizensDone: true), ConverterStep.openWorkspace);
    });

    test('a staged server import leads until applied', () {
      expect(step(citizens: true, citizensDone: true, state: 'staged'), ConverterStep.applyImport);
      expect(step(citizens: true, citizensDone: true, state: 'rolled-back'), ConverterStep.applyImport);
      expect(step(citizens: true, citizensDone: true, state: 'applied'), ConverterStep.openWorkspace);
    });
  });

  group('sync next step', () {
    SyncStep step({bool compared = false, int selected = 0, bool validated = false, int pullSelected = 0, bool pullCompared = false, int writes = 0}) =>
        syncNextStep(pushCompared: compared, pushSelected: selected, validated: validated, pullSelected: pullSelected, pullCompared: pullCompared, pullWrites: writes);

    test('push runs compare, validate, push', () {
      expect(step(), SyncStep.comparePush);
      expect(step(compared: true), SyncStep.none);
      expect(step(compared: true, selected: 2), SyncStep.validate);
      expect(step(compared: true, selected: 2, validated: true), SyncStep.push);
    });

    test('a started pull leads', () {
      expect(step(compared: true, selected: 2, pullSelected: 1), SyncStep.comparePull);
      expect(step(pullSelected: 1, pullCompared: true, writes: 1), SyncStep.pull);
      expect(step(pullSelected: 1, pullCompared: true), SyncStep.comparePush);
    });
  });

  test('every kind has a one-sentence list purpose', () {
    for (final DocumentKind kind in DocumentKind.values) {
      final String purpose = kindListPurpose(kind);
      expect(purpose, endsWith('.'), reason: kind.name);
      expect('.'.allMatches(purpose).length, 1, reason: kind.name);
    }
    expect(kindMadeElsewhere(DocumentKind.recording), contains('/animus record start'));
    expect(kindMadeElsewhere(DocumentKind.shop), isNull);
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
