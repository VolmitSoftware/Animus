import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:animus_editor/logic/gui/gui_json.dart';
import 'package:animus_editor/logic/shared_schemas.dart';
import 'package:animus_editor/model/animus_document.dart';
import 'package:animus_editor/model/document_kind.dart';
import 'package:animus_editor/state/editor_store.dart';
import 'package:animus_editor/state/session_owner.dart';
import 'package:animus_editor/state/workspace_coordination.dart';
import 'package:animus_editor/state/workspace_records.dart';
import 'package:animus_editor/state/workspace_repository.dart';
import 'package:animus_editor/state/workspace_route.dart';
import 'package:test/test.dart';

import 'fixtures.dart';

/// A repository whose saves fail with [refusal] while it is set.
class RefusingRepository extends MemoryWorkspaceRepository {
  Object? refusal;

  @override
  Future<void> save(WorkspaceSnapshot snapshot, {WorkspaceSnapshot? previous, Set<String>? changed}) async {
    final Object? refused = refusal;
    if (refused != null) throw refused;
    return super.save(snapshot, previous: previous, changed: changed);
  }
}

/// A repository whose saves wait while [hold] is pending, so commits queue.
class SlowRepository extends MemoryWorkspaceRepository {
  Completer<void>? hold;

  @override
  Future<void> save(WorkspaceSnapshot snapshot, {WorkspaceSnapshot? previous, Set<String>? changed}) async {
    await hold?.future;
    return super.save(snapshot, previous: previous, changed: changed);
  }
}

void main() {
  group('routes', () {
    test('should round-trip every route through its hash', () {
      final List<EditorRoute> routes = <EditorRoute>[
        const HomeRoute(),
        const WorkspaceRoute('ws-1'),
        const DocumentRoute('ws-1', DocumentKind.behavior, 'town:greet/morning'),
        const DocumentRoute('ws 2', DocumentKind.npc, 'town/guard', ViewMode.split),
        const DocumentRoute('ws-1', DocumentKind.npc, 'town/guard', ViewMode.visual, 'movement'),
        const DocumentRoute('ws-1', DocumentKind.npc, 'town/guard', ViewMode.code, 'movement'),
        const PairRoute('ANM1.eyJ2IjoxfQ'),
        const ServerRoute('sha256:ab', <String>['npcs', 'town/guard']),
      ];
      for (final EditorRoute route in routes) {
        expect(EditorRoute.parse(route.format()), route, reason: route.format());
      }
    });

    test('should give the pairing page a code-free address to replace the link with', () {
      expect(const PairRoute().format(), '#/pair');
      expect(EditorRoute.parse('#/pair'), const PairRoute());
      expect(EditorRoute.parse('#/pair/ANM1.eyJ2IjoxfQ'), const PairRoute('ANM1.eyJ2IjoxfQ'));
    });

    test('should fall back to home or the workspace for unknown routes', () {
      expect(EditorRoute.parse(''), const HomeRoute());
      expect(EditorRoute.parse('#/nowhere'), const HomeRoute());
      expect(EditorRoute.parse('#/workspace/ws-1/unknown/x'), const WorkspaceRoute('ws-1'));
      expect(EditorRoute.parse('#/workspace/ws-1/npc/a%2Fb?view=bogus'), const DocumentRoute('ws-1', DocumentKind.npc, 'a/b'));
    });
  });

  group('store', () {
    late MemoryWorkspaceRepository repository;
    late EditorStore store;
    int ids = 0;

    setUp(() {
      repository = MemoryWorkspaceRepository();
      store = EditorStore(repository: repository, schemas: SharedSchemas.embedded, newId: () => 'ws-${++ids}');
    });

    Future<WorkspaceSnapshot> openExport() => store.openFiles('Export', fixtureFiles('export'));

    test('should open a folder into a saved workspace', () async {
      final WorkspaceSnapshot snapshot = await openExport();
      expect(snapshot.generation, 1);
      expect(await repository.load(snapshot.id), isNotNull);
      expect(store.workspaces.single.documentCount, 10);
      expect(store.ignored, contains('config.toml'));
    });

    test('should revalidate an edited document and undo the edit', () async {
      final WorkspaceSnapshot snapshot = await openExport();
      final AnimusDocument broken = store.document(DocumentKind.npc, 'examples/broken')!;
      expect(broken.valid, isFalse);
      final Map<String, Object?> repaired = <String, Object?>{
        ...(broken.json! as Map<String, Object?>),
        'uuid': '00000000-0000-0000-0000-000000000001',
      };
      await store.editSource(DocumentKind.npc, 'examples/broken', jsonEncode(repaired));
      expect(store.document(DocumentKind.npc, 'examples/broken')!.valid, isTrue);
      expect(store.workspace!.generation, 2);
      expect((await repository.load(snapshot.id))!.generation, 2);
      await store.undo();
      expect(store.document(DocumentKind.npc, 'examples/broken')!.valid, isFalse);
      expect(store.workspace!.generation, 3);
    });

    test('should refuse a save from a stale tab and reload the stored copy', () async {
      final WorkspaceSnapshot snapshot = await openExport();
      // Another tab saves generation 2 first.
      await repository.save(snapshot.next(name: 'Other tab'));
      final AnimusDocument greeter = store.document(DocumentKind.npc, 'examples/greeter')!;
      await store.editSource(DocumentKind.npc, 'examples/greeter', '${greeter.source} ');
      expect(store.problem, contains('Another tab'));
      expect(store.workspace!.name, 'Other tab');
      expect(store.workspace!.generation, 2);
    });

    test('should keep both of two quick edits worked out from the document as shown', () async {
      await openExport();
      final AnimusDocument shown = store.document(DocumentKind.npc, 'examples/greeter')!;
      // The second edit is made before the first is saved, from the same
      // document the editor still shows.
      final Future<void> first = store.commit(
        documents: <DocumentChange>[DocumentChange.update(DocumentKind.npc, shown.id, writeAt(shown.json, <JsonStep>['name'], 'First'))],
        coalesce: 'name',
      );
      final Future<void> second = store.commit(
        documents: <DocumentChange>[DocumentChange.update(DocumentKind.npc, shown.id, writeAt(shown.json, <JsonStep>['spawned'], false))],
        coalesce: 'spawned',
      );
      await Future.wait(<Future<void>>[first, second]);
      final Map<String, Object?> json = store.document(DocumentKind.npc, shown.id)!.json! as Map<String, Object?>;
      expect(json['name'], 'First');
      expect(json['spawned'], isFalse);
      await store.undo();
      expect((store.document(DocumentKind.npc, shown.id)!.json! as Map<String, Object?>)['name'], 'First', reason: 'two undo steps');
      await store.undo();
      expect(store.document(DocumentKind.npc, shown.id)!.source, shown.source);
    });

    test('should show every keystroke at once while saves are still queued', () async {
      final SlowRepository slow = SlowRepository();
      final EditorStore held = EditorStore(repository: slow, schemas: SharedSchemas.embedded, newId: () => 'ws-${++ids}');
      await held.openFiles('Export', fixtureFiles('export'));
      final AnimusDocument shown = held.document(DocumentKind.npc, 'examples/greeter')!;
      final Map<String, Object?> before = shown.json! as Map<String, Object?>;
      slow.hold = Completer<void>();
      const String typed = 'Town crier';
      final List<Future<void>> saves = <Future<void>>[
        // Each keystroke is worked out from the document drawn before any of them saved.
        for (int length = 1; length <= typed.length; length++)
          held.commit(
            documents: <DocumentChange>[DocumentChange.update(DocumentKind.npc, shown.id, writeAt(shown.json, <JsonStep>['name'], typed.substring(0, length)))],
            coalesce: 'name',
          ),
      ];
      final Map<String, Object?> pending = held.shown(DocumentKind.npc, shown.id)!.json! as Map<String, Object?>;
      expect(pending['name'], typed);
      expect(<String, Object?>{...pending}..remove('name'), <String, Object?>{...before}..remove('name'), reason: 'siblings untouched');
      slow.hold!.complete();
      await Future.wait(saves);
      expect((held.document(DocumentKind.npc, shown.id)!.json! as Map<String, Object?>)['name'], typed);
      expect(held.shown(DocumentKind.npc, shown.id)!.source, held.document(DocumentKind.npc, shown.id)!.source);
    });

    test('should redo an undone edit and say what each step did', () async {
      final WorkspaceSnapshot snapshot = await openExport();
      final AnimusDocument greeter = store.document(DocumentKind.npc, 'examples/greeter')!;
      await store.commit(documents: <DocumentChange>[DocumentChange(DocumentKind.npc, greeter.id, '${greeter.source} ')], label: 'rename examples/greeter');
      expect(store.undoLabel, 'rename examples/greeter');
      expect((await store.undo())?.label, 'rename examples/greeter');
      expect(store.document(DocumentKind.npc, greeter.id)!.source, greeter.source);
      expect(store.canRedo, isTrue);
      expect(store.redoLabel, 'rename examples/greeter');
      final EditorStore reloaded = EditorStore(repository: repository, schemas: SharedSchemas.embedded);
      await reloaded.openWorkspace(snapshot.id);
      expect(reloaded.redoLabel, 'rename examples/greeter');
      expect((await store.redo())?.label, 'rename examples/greeter');
      expect(store.document(DocumentKind.npc, greeter.id)!.source, '${greeter.source} ');
      expect(store.canRedo, isFalse);
      expect(await store.redo(), isNull);
      await store.undo();
      await store.editSource(DocumentKind.npc, greeter.id, '${greeter.source}  ');
      expect(store.canRedo, isFalse, reason: 'a new edit drops the redo steps');
      expect(await repository.loadHistory(snapshot.id, HistoryStack.redo), isEmpty);
    });

    test('should describe a change that has no label by the names the library shows', () {
      String? none(DocumentChange _) => null;
      String? all(DocumentChange _) => 'Carpenter';
      expect(describeChanges(const <DocumentChange>[DocumentChange(DocumentKind.npc, 'world/citizens-102', null)], const <String>[], all), 'delete Carpenter');
      expect(describeChanges(const <DocumentChange>[DocumentChange(DocumentKind.npc, 'world/citizens-102', '{}')], const <String>[], all), 'edit Carpenter');
      expect(describeChanges(const <DocumentChange>[DocumentChange(DocumentKind.npc, 'a/b', '{"name": "Baker"}')], const <String>[], none), 'create Baker');
      expect(describeChanges(const <DocumentChange>[DocumentChange(DocumentKind.shop, 'town:fish_stall', '{}')], const <String>[], none), 'create Fish stall');
      expect(describeChanges(const <DocumentChange>[], const <String>['pack/assets/x.png'], none), 'change pack/assets/x.png');
      expect(
        describeChanges(const <DocumentChange>[DocumentChange(DocumentKind.npc, 'a/b', '{}'), DocumentChange(DocumentKind.npc, 'a/c', '{}')], const <String>[], all),
        'change 2 documents',
      );
    });

    test('should keep an edit the browser refused to store and save it again', () async {
      final RefusingRepository refusing = RefusingRepository();
      final EditorStore tab = EditorStore(repository: refusing, schemas: SharedSchemas.embedded, newId: () => 'ws-refuse');
      await tab.openFiles('Export', fixtureFiles('export'));
      final AnimusDocument greeter = tab.document(DocumentKind.npc, 'examples/greeter')!;
      refusing.refusal = const WorkspaceWriteFailure('IndexedDB save: QuotaExceededError', quota: true);
      await tab.editSource(DocumentKind.npc, greeter.id, '${greeter.source} ');
      expect(tab.document(DocumentKind.npc, greeter.id)!.source, '${greeter.source} ');
      expect(tab.problem, contains('out of storage space'));
      expect(tab.problem, contains('Free space or export a zip, then try again.'));
      expect(tab.hasUnsavedEdit, isTrue);
      expect(tab.hasPendingWork, isTrue);
      expect((await refusing.load('ws-refuse'))!.generation, 1);
      refusing.refusal = StateError('disk gone');
      await tab.saveAgain();
      expect(tab.problem, contains('disk gone'));
      refusing.refusal = null;
      await tab.saveAgain();
      expect(tab.problem, isNull);
      expect(tab.hasUnsavedEdit, isFalse);
      final WorkspaceSnapshot stored = (await refusing.load('ws-refuse'))!;
      expect(stored.generation, 2);
      expect(stored.documents.firstWhere((AnimusDocument d) => d.id == greeter.id).source, '${greeter.source} ');
    });

    test('should reload a workspace another tab saved and go home when it deletes it', () async {
      final SharedWorkspaceEvents tabs = SharedWorkspaceEvents();
      final EditorStore first = EditorStore(repository: repository, schemas: SharedSchemas.embedded, newId: () => 'ws-shared', events: tabs.tab());
      final EditorStore second = EditorStore(repository: repository, schemas: SharedSchemas.embedded, events: tabs.tab());
      final WorkspaceSnapshot snapshot = await first.openFiles('Export', fixtureFiles('export'));
      await second.openWorkspace(snapshot.id);
      final AnimusDocument greeter = first.document(DocumentKind.npc, 'examples/greeter')!;
      await first.editSource(DocumentKind.npc, greeter.id, '${greeter.source} ');
      await pumpEventQueue();
      expect(second.workspace!.generation, 2);
      expect(second.document(DocumentKind.npc, greeter.id)!.source, '${greeter.source} ');
      expect(second.problem, isNull, reason: 'an idle tab reloads without a warning');
      expect(second.canUndo, isTrue, reason: 'the reload brings the undo steps along');
      await first.deleteWorkspace(snapshot.id);
      await pumpEventQueue();
      expect(second.workspace, isNull);
      expect(second.problem, deletedInOtherTab);
      expect(second.takeWorkspaceGone(), isTrue);
      expect(second.takeWorkspaceGone(), isFalse);
    });

    test('should tell a tab that lost a race which edit was not kept', () async {
      final WorkspaceSnapshot snapshot = await openExport();
      await repository.save(snapshot.next(name: 'Other tab'));
      await store.editSource(DocumentKind.npc, 'examples/greeter', '${store.document(DocumentKind.npc, 'examples/greeter')!.source} ');
      expect(store.problem, reloadedFromOtherTab);
      expect(store.problem, isNot(contains('generation')));
    });

    test('should let one tab own a workspace and hand it over when that tab closes it', () async {
      final SharedWorkspaceLocks locks = SharedWorkspaceLocks();
      final EditorStore first = EditorStore(repository: repository, schemas: SharedSchemas.embedded, newId: () => 'ws-locked', locks: locks.tab());
      final EditorStore second = EditorStore(repository: repository, schemas: SharedSchemas.embedded, locks: locks.tab());
      final WorkspaceSnapshot snapshot = await first.openFiles('Export', fixtureFiles('export'));
      expect(first.ownedByThisTab, isTrue);
      await second.openWorkspace(snapshot.id);
      expect(second.ownedByThisTab, isFalse);
      first.closeWorkspace();
      expect(first.ownedByThisTab, isFalse);
      await pumpEventQueue();
      expect(second.ownedByThisTab, isTrue);
      second.closeWorkspace();
      expect(await locks.tab().tryHold(workspaceLockName(snapshot.id)), isTrue);
    });

    test('should run live editing in the tab that owns the workspace and hand it over', () async {
      final SharedWorkspaceLocks locks = SharedWorkspaceLocks();
      final EditorStore first = EditorStore(repository: repository, schemas: SharedSchemas.embedded, newId: () => 'ws-live', locks: locks.tab());
      final EditorStore second = EditorStore(repository: repository, schemas: SharedSchemas.embedded, locks: locks.tab());
      final WorkspaceSnapshot snapshot = await first.openFiles('Export', fixtureFiles('export'));
      await second.openWorkspace(snapshot.id);
      final StoreOwner owning = StoreOwner(first);
      final StoreOwner waiting = StoreOwner(second);
      final List<bool> heard = <bool>[];
      waiting.changes.listen(heard.add);
      expect(owning.owned, isTrue);
      expect(owning.waiting, isFalse);
      expect(waiting.owned, isFalse);
      expect(waiting.waiting, isTrue);
      first.closeWorkspace();
      await pumpEventQueue();
      expect(waiting.owned, isTrue);
      expect(waiting.waiting, isFalse);
      expect(heard, <bool>[true]);
      await owning.release();
      await waiting.release();
      second.closeWorkspace();
    });

    test('should carry tab notices as JSON and drop malformed ones', () {
      final WorkspaceEvent event = WorkspaceEvent.fromJson(
        const WorkspaceEvent(WorkspaceEventKind.saved, 'ws-1', generation: 7).toJson(),
      )!;
      expect(event.kind, WorkspaceEventKind.saved);
      expect(event.workspaceId, 'ws-1');
      expect(event.generation, 7);
      expect(WorkspaceEvent.fromJson(<String, Object?>{'kind': 'renamed', 'id': 'ws-1'}), isNull);
      expect(WorkspaceEvent.fromJson('saved'), isNull);
    });

    test('should keep unknown fields when a workspace is stored and loaded', () async {
      final Map<String, Uint8List> files = <String, Uint8List>{
        'npcs/a/b.json': Uint8List.fromList(utf8.encode('{"futureField": {"x": 1}}')),
      };
      final WorkspaceSnapshot snapshot = await store.openFiles('One', files);
      final WorkspaceSnapshot loaded = (await repository.load(snapshot.id))!;
      expect(loaded.documents.single.source, '{"futureField": {"x": 1}}');
      expect((loaded.documents.single.json! as Map<String, Object?>)['futureField'], <String, Object?>{'x': 1});
    });

    test('should keep a Code draft per document until it is applied or discarded', () async {
      await openExport();
      expect(store.hasPendingWork, isFalse);
      store.setCodeDraft(DocumentKind.npc, 'examples/greeter', '{"name": "Typed"}');
      expect(store.codeDraft(DocumentKind.npc, 'examples/greeter'), '{"name": "Typed"}');
      expect(store.codeDraft(DocumentKind.npc, 'examples/broken'), isNull);
      expect(store.hasCodeDraft(DocumentKind.npc, 'examples/greeter'), isTrue);
      expect(store.hasPendingWork, isTrue, reason: 'closing the tab asks while a Code draft is unapplied');
      store.setCodeDraft(DocumentKind.npc, 'examples/greeter', null);
      expect(store.hasCodeDraft(DocumentKind.npc, 'examples/greeter'), isFalse);
      expect(store.hasPendingWork, isFalse);
    });

    test('should drop Code drafts with their workspace and their document', () async {
      await openExport();
      store.setCodeDraft(DocumentKind.npc, 'examples/greeter', '{}');
      await store.commit(documents: <DocumentChange>[const DocumentChange(DocumentKind.npc, 'examples/greeter', null)]);
      expect(store.hasCodeDraft(DocumentKind.npc, 'examples/greeter'), isFalse);
      store.setCodeDraft(DocumentKind.npc, 'examples/broken', '{}');
      await store.createWorkspace('Other');
      expect(store.hasCodeDraft(DocumentKind.npc, 'examples/broken'), isFalse);
    });

    test('should refuse a pick with no documents and save nothing', () async {
      final Map<String, Uint8List> serverRoot = <String, Uint8List>{
        'server.properties': Uint8List(0),
        'plugins/Other/config.yml': Uint8List(0),
      };
      await expectLater(
        store.openFiles('server', serverRoot, requireDocuments: true, zip: false),
        throwsA(isA<NoDocumentsFound>().having((NoDocumentsFound refused) => refused.message, 'message', contains('server root'))),
      );
      await expectLater(
        store.openFiles('bad', const <String, Uint8List>{}, requireDocuments: true, zip: true),
        throwsA(isA<NoDocumentsFound>().having((NoDocumentsFound refused) => refused.message, 'message', contains('not a zip'))),
      );
      expect(await repository.list(), isEmpty);
      expect(store.workspace, isNull);
    });
  });
}
