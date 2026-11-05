import 'dart:convert';

import 'package:animus_editor/logic/change_sets.dart';
import 'package:animus_editor/logic/data_folder.dart';
import 'package:animus_editor/logic/gui/gui_json.dart';
import 'package:animus_editor/logic/offline/new_documents.dart';
import 'package:animus_editor/logic/offline/sync_plan.dart';
import 'package:animus_editor/logic/shared_schemas.dart';
import 'package:animus_editor/model/animus_document.dart';
import 'package:animus_editor/model/document_kind.dart';
import 'package:animus_editor/model/server_binding.dart';
import 'package:crypto/crypto.dart';
import 'package:test/test.dart';

AnimusDocument document(DocumentKind kind, String id, Object? json) =>
    validateSource(DocumentLocation(kind, id), newDocumentPath(kind, id), prettyJson(json), SharedSchemas.embedded);

String hashOf(String id) => 'sha256:${sha256.convert(utf8.encode(id))}';

ServerBase base(DocumentKind kind, String id, Object? json, {int revision = 4}) =>
    ServerBase(revision: revision, hash: hashOf(id), path: newDocumentPath(kind, id), source: json);

void main() {
  final JsonMap baker = newNpc(const NpcSeed(name: 'Baker', group: 'town'), uuid: '00000000-0000-4000-8000-000000000001');
  final JsonMap guard = newNpc(const NpcSeed(name: 'Guard', group: 'town'), uuid: '00000000-0000-4000-8000-000000000002');
  final JsonMap greeter = behaviorFromTemplate(BehaviorTemplate.greeter, 'town:greeter');
  final List<AnimusDocument> local = <AnimusDocument>[
    document(DocumentKind.npc, 'town/baker', baker),
    document(DocumentKind.npc, 'town/guard', guard),
    document(DocumentKind.behavior, 'town:greeter', greeter),
  ];

  group('push', () {
    final Map<String, ServerBase> server = <String, ServerBase>{
      'npc:town/guard': base(DocumentKind.npc, 'town/guard', <String, Object?>{...guard, 'name': 'Captain'}),
      'behavior:town:greeter': base(DocumentKind.behavior, 'town:greeter', greeter),
      'npc:town/elsewhere': base(DocumentKind.npc, 'town/elsewhere', baker),
    };
    final PushPlan plan = planPush(local, server);

    test('should sort local documents into new, identical and conflicting', () {
      expect(plan.where(SyncState.added).map((PushEntry entry) => entry.key), <String>['npc:town/baker']);
      expect(plan.where(SyncState.same).map((PushEntry entry) => entry.key), <String>['behavior:town:greeter']);
      expect(plan.where(SyncState.conflict).map((PushEntry entry) => entry.key), <String>['npc:town/guard']);
      expect(differingFields(plan.where(SyncState.conflict).single.document.json, server['npc:town/guard']!.source), <String>['name']);
    });

    test('should send new documents by default and conflicts only when chosen', () {
      expect(pushSelection(plan, const <String, PushChoice>{}).map((PushEntry entry) => entry.key), <String>['npc:town/baker']);
      final List<PushEntry> chosen = pushSelection(plan, const <String, PushChoice>{'npc:town/guard': PushChoice.send, 'npc:town/baker': PushChoice.skip});
      expect(chosen.map((PushEntry entry) => entry.key), <String>['npc:town/guard']);
    });

    test('should base new documents on nothing and overwrites on the compared server copy', () {
      final List<Map<String, Object?>> changes = pushChanges(pushSelection(plan, const <String, PushChoice>{'npc:town/guard': PushChoice.send}));
      expect(changes, hasLength(2));
      expect(changes.first, <String, Object?>{'kind': 'npc', 'id': 'town/baker', 'baseRevision': null, 'baseHash': null, 'source': baker});
      expect(changes.last['baseRevision'], 4);
      expect(changes.last['baseHash'], hashOf('town/guard'));
      expect(asMap(changes.last['source'])['name'], 'Guard');
      for (final Map<String, Object?> change in changes) {
        expect(SharedSchemas.embedded.validateMessage('change', change), isEmpty);
      }
      expect(SharedSchemas.embedded.validateMessage('change-set', <String, Object?>{'idempotencyKey': newIdempotencyKey(), 'documents': changes}), isEmpty);
    });

    test('should refuse to send a document that is not a JSON object', () {
      final AnimusDocument broken = validateSource(const DocumentLocation(DocumentKind.npc, 'town/x'), 'npcs/town/x.json', '[1', SharedSchemas.embedded);
      final PushPlan withBroken = planPush(<AnimusDocument>[broken], const <String, ServerBase>{});
      expect(withBroken.entries.single.problem, isNotNull);
      expect(pushSelection(withBroken, const <String, PushChoice>{'npc:town/x': PushChoice.send}), isEmpty);
    });

    test('should carry the stored revision back into pushed documents', () {
      final List<PushEntry> selection = pushSelection(plan, const <String, PushChoice>{});
      const ChangeApplied applied = ChangeApplied('k', 'c1', <AppliedDocument>[AppliedDocument(DocumentKind.npc, 'town/baker', 2, 'new')]);
      expect(asMap(pushedSources(selection, applied)['npc:town/baker'])['revision'], 2);
    });
  });

  group('pull', () {
    final JsonMap market = newGuiDocument(DocumentKind.shop, 'town:market');
    final Map<String, ServerBase> server = <String, ServerBase>{
      'npc:town/baker': base(DocumentKind.npc, 'town/baker', baker),
      'npc:town/guard': base(DocumentKind.npc, 'town/guard', <String, Object?>{...guard, 'spawned': false}),
      'shop:town:market': base(DocumentKind.shop, 'town:market', market),
    };
    final PullPlan plan = planPull(server, local);

    test('should sort server documents into new, identical and conflicting', () {
      expect(plan.where(SyncState.added).map((PullEntry entry) => entry.key), <String>['shop:town:market']);
      expect(plan.where(SyncState.same).map((PullEntry entry) => entry.key), <String>['npc:town/baker']);
      expect(plan.where(SyncState.conflict).map((PullEntry entry) => entry.key), <String>['npc:town/guard']);
    });

    test('should take new documents and keep local edits unless told to take the server copy', () {
      expect(pullWrites(plan, const <String, PullChoice>{}).map((({DocumentKind kind, String id, String source, String path}) write) => write.id), <String>['town:market']);
      final List<({DocumentKind kind, String id, String source, String path})> writes =
          pullWrites(plan, const <String, PullChoice>{'npc:town/guard': PullChoice.take, 'shop:town:market': PullChoice.keep});
      expect(writes.single.id, 'town/guard');
      expect(writes.single.path, 'npcs/town/guard.json');
      expect(writes.single.source, prettyJson(<String, Object?>{...guard, 'spawned': false}));
    });
  });
}
