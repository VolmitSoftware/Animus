import 'package:animus_editor/components/offline/import_plan.dart';
import 'package:animus_editor/model/document_kind.dart';
import 'package:animus_editor/services/server/server_imports.dart';
import 'package:test/test.dart';

void main() {
  group('sources', () {
    test('browser sources are always open; server sources need an import pairing', () {
      for (final ImportSource source in <ImportSource>[ImportSource.citizens, ImportSource.shopkeepers, ImportSource.denizen]) {
        expect(source.onServer, isFalse);
        expect(sourceBlocked(source, paired: false, canImport: false), isNull);
      }
      expect(sourceBlocked(ImportSource.dtlTraders, paired: false, canImport: false), 'Pair a server first.');
      expect(sourceBlocked(ImportSource.server, paired: true, canImport: false), 'This pairing cannot import. Pair again.');
      expect(sourceBlocked(ImportSource.server, paired: true, canImport: true), isNull);
    });

    test('labels are short and say what each source gives', () {
      for (final ImportSource source in ImportSource.values) {
        expect(source.label.split(' ').length, lessThanOrEqualTo(4), reason: source.name);
        expect(source.line, endsWith('.'), reason: source.name);
      }
      expect(importSteps, <String>['Source', 'Files', 'Review', 'Result']);
    });
  });

  group('convert blocked', () {
    test('names the missing file', () {
      expect(convertBlocked(ImportSource.citizens), 'Choose saves.yml first.');
      expect(convertBlocked(ImportSource.citizens, citizens: true), isNull);
      expect(convertBlocked(ImportSource.shopkeepers, citizens: true), 'Choose save.yml first.');
      expect(convertBlocked(ImportSource.shopkeepers, shopSave: true), isNull);
      expect(convertBlocked(ImportSource.denizen), 'Choose the Denizen folder first.');
      expect(convertBlocked(ImportSource.denizen, denizen: true), isNull);
    });

    test('a server source waits for the server and refuses a file it would refuse', () {
      expect(convertBlocked(ImportSource.dtlTraders, online: true), 'Choose the dtlTraders files first.');
      expect(convertBlocked(ImportSource.dtlTraders, traders: 2), 'Waiting for the server.');
      expect(convertBlocked(ImportSource.dtlTraders, traders: 2, online: true), isNull);
      expect(convertBlocked(ImportSource.server, online: true), isNull);
      expect(convertBlocked(ImportSource.server, online: true, problems: <String>['Too large.']), 'Too large.');
      expect(convertBlocked(ImportSource.server), 'Waiting for the server.');
    });
  });

  group('what a conversion makes', () {
    test('counts documents by kind and skips reports', () {
      final Map<DocumentKind, int> counts = kindCounts(<String>[
        'npcs/world/citizens-1.json',
        'npcs/world/citizens-2.json',
        'skins/${'a' * 64}.json',
        'behaviors/citizens/greeting.json',
        'report.json',
        'records/citizens-1.json',
        'source.yml',
      ]);
      expect(counts, <DocumentKind, int>{DocumentKind.npc: 2, DocumentKind.skin: 1, DocumentKind.behavior: 1});
      expect(counts.keys.toList(), <DocumentKind>[DocumentKind.npc, DocumentKind.skin, DocumentKind.behavior]);
    });

    test('reads as a sentence', () {
      expect(createdLine(const <DocumentKind, int>{}), 'Nothing to open.');
      expect(createdLine(const <DocumentKind, int>{DocumentKind.npc: 1}), '1 NPC');
      expect(createdLine(const <DocumentKind, int>{DocumentKind.npc: 6, DocumentKind.skin: 1}), '6 NPCs and 1 skin');
      expect(createdLine(const <DocumentKind, int>{DocumentKind.npc: 6, DocumentKind.behavior: 4, DocumentKind.inventory: 2}), '6 NPCs, 4 behaviors and 2 chest GUIs');
      expect(importNoun(DocumentKind.textAnimation), 'text animation');
      expect(importNoun(DocumentKind.shop), 'shop');
    });

    test('a not-converted line for Simple keeps the record and drops the field paths', () {
      expect(simpleIssue(r'NPC #15 (Not Sentinel): $.position.world: expected nonblank string; $.position.x: expected finite coordinate'), 'NPC #15 (Not Sentinel)');
      expect(simpleIssue('NPC #9: the save has no world'), 'NPC #9: the save has no world');
    });
  });

  group('server import', () {
    ImportReview review(String state, {Map<String, Object?>? citizens, Map<String, Object?>? shops, int? npcs}) => ImportReview(
          state: state,
          lines: const <String>[],
          npcs: npcs,
          reports: <String, Map<String, Object?>>{'citizens': ?citizens, 'shopkeepers': ?shops},
        );

    test('states read without staging words', () {
      for (final String state in <String>['none', 'staged', 'applied', 'rolled-back']) {
        expect(importStateLabel(state).toLowerCase(), isNot(contains('stag')), reason: state);
      }
      expect(importStateLabel('staged'), 'Ready to install');
      expect(importStateLabel('applied'), 'Installed');
    });

    test('counts in words', () {
      expect(serverCountsLine(review('staged', citizens: <String, Object?>{'records': 6, 'valid': 6, 'skins': 6})), '6 NPCs and 6 skins');
      expect(serverCountsLine(review('staged', citizens: <String, Object?>{'records': 6, 'valid': 4, 'skins': 0})), '6 NPCs. 2 of the NPCs cannot install');
      expect(serverCountsLine(review('staged', shops: <String, Object?>{'shops': 3})), '3 shops');
      expect(serverCountsLine(review('applied', citizens: <String, Object?>{'records': 6}, npcs: 6)), '6 NPCs installed');
      expect(serverCountsLine(review('none')), 'Nothing converted yet');
    });

    test('issues read as lines, naming their record', () {
      expect(importIssueLine('A plain line'), 'A plain line');
      expect(importIssueLine(<String, Object?>{'id': 'citizens-3', 'message': 'no world'}), 'citizens-3: no world');
      expect(importIssueLine(<String, Object?>{'code': 'unknown'}), 'unknown');
      expect(importIssueLine(<String, Object?>{}), 'An issue with no description');
      expect(
        serverIssues(review('staged', citizens: <String, Object?>{'issues': <Object?>['one']}, shops: <String, Object?>{'issues': <Object?>['two']})),
        <String>['one', 'two'],
      );
    });
  });
}
