import 'dart:convert';

import 'package:animus_editor/components/offline/new_document_kinds.dart';
import 'package:animus_editor/components/offline/send_plan.dart';
import 'package:animus_editor/logic/data_folder.dart';
import 'package:animus_editor/logic/gui/gui_json.dart';
import 'package:animus_editor/logic/offline/new_documents.dart';
import 'package:animus_editor/logic/offline/sync_plan.dart';
import 'package:animus_editor/logic/shared_schemas.dart';
import 'package:animus_editor/model/animus_document.dart';
import 'package:animus_editor/model/document_kind.dart';
import 'package:animus_editor/model/server_binding.dart';
import 'package:animus_editor/ui/editor_mode.dart';
import 'package:crypto/crypto.dart';
import 'package:test/test.dart';

AnimusDocument document(DocumentKind kind, String id, Object? json) =>
    validateSource(DocumentLocation(kind, id), newDocumentPath(kind, id), prettyJson(json), SharedSchemas.embedded);

ServerBase base(DocumentKind kind, String id, Object? json) =>
    ServerBase(revision: 4, hash: 'sha256:${sha256.convert(utf8.encode(id))}', path: newDocumentPath(kind, id), source: json);

Diagnostic problem(DiagnosticSeverity severity) => Diagnostic(severity, r'$.name', 'Too long.');

void main() {
  final JsonMap baker = newNpc(const NpcSeed(name: 'Baker', group: 'town'), uuid: '00000000-0000-4000-8000-000000000001');
  final JsonMap guard = newNpc(const NpcSeed(name: 'Guard', group: 'town'), uuid: '00000000-0000-4000-8000-000000000002');
  final JsonMap greeter = behaviorFromTemplate(BehaviorTemplate.greeter, 'town:greeter');
  final PushPlan plan = planPush(
    <AnimusDocument>[
      document(DocumentKind.npc, 'town/baker', baker),
      document(DocumentKind.npc, 'town/guard', guard),
      document(DocumentKind.behavior, 'town:greeter', greeter),
    ],
    <String, ServerBase>{
      'npc:town/guard': base(DocumentKind.npc, 'town/guard', <String, Object?>{...guard, 'name': 'Captain'}),
      'behavior:town:greeter': base(DocumentKind.behavior, 'town:greeter', greeter),
    },
  );

  group('sendSelection', () {
    test('should send new documents and keep changed server copies by default', () {
      expect(sendSelection(plan, replaceChanged: false).map((PushEntry entry) => entry.key), <String>['npc:town/baker']);
    });

    test('should send changed documents too when Use mine is picked', () {
      expect(sendSelection(plan, replaceChanged: true).map((PushEntry entry) => entry.key), <String>['npc:town/baker', 'npc:town/guard']);
    });

    test('should follow a document picked or left out one by one over the group choice', () {
      final List<PushEntry> picked = sendSelection(plan, replaceChanged: false, picked: const <String, bool>{'npc:town/baker': false, 'npc:town/guard': true});
      expect(picked.map((PushEntry entry) => entry.key), <String>['npc:town/guard']);
    });

    test('should never send a document the server already has the same', () {
      expect(sendSelection(plan, replaceChanged: true, picked: const <String, bool>{'behavior:town:greeter': true}).map((PushEntry entry) => entry.key),
          isNot(contains('behavior:town:greeter')));
    });
  });

  group('lines', () {
    test('should name known fields in plain words', () {
      expect(differenceLine(<String>['name']), 'Name differs');
      expect(differenceLine(<String>['name', 'position']), 'Name and position differ');
      expect(differenceLine(<String>['name', 'position', 'type']), 'Name, position and body differ');
    });

    test('should count fields it has no word for', () {
      expect(differenceLine(<String>['name', 'navigation']), '2 settings differ');
      expect(differenceLine(<String>['name', 'position', 'skin', 'equipment']), '4 settings differ');
    });

    test('should sum up a compare', () {
      expect(compareLine(plan), '1 new, 1 changed, 1 same');
    });

    test('should count only errors among the server problems', () {
      expect(errorCount(<String, List<Diagnostic>>{
        'npc:a': <Diagnostic>[problem(DiagnosticSeverity.error), problem(DiagnosticSeverity.warning)],
        'npc:b': <Diagnostic>[problem(DiagnosticSeverity.error)],
      }), 2);
    });

    test('should say apply and the server documents in failure sentences', () {
      expect(plainSyncProblem('Could not load the server catalog. Timed out.'), "Could not read the server's documents. Timed out.");
      expect(plainSyncProblem('An earlier push has no answer yet. Press Check the earlier push.'), 'An earlier apply has no answer yet. Check it first.');
      expect(plainSyncProblem('The push went out but its answer did not arrive (lost). Press Check the earlier push once connected.'),
          'The changes went out but the answer did not arrive (lost). Check it once connected.');
    });
  });

  group('new page kinds', () {
    test('should offer the Simple kinds in Simple and every creatable kind in Advanced', () {
      expect(newPageKinds(EditorMode.simple), simpleNewKinds);
      expect(newPageKinds(EditorMode.advanced), creatableKinds);
    });

    test('should keep a kind the address names that the mode does not list', () {
      expect(newPageKinds(EditorMode.simple, current: DocumentKind.rig), <DocumentKind>[...simpleNewKinds, DocumentKind.rig]);
    });

  });
}
