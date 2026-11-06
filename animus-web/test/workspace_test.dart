import 'dart:convert';
import 'dart:typed_data';

import 'package:animus_editor/logic/shared_schemas.dart';
import 'package:animus_editor/model/animus_document.dart';
import 'package:animus_editor/model/document_kind.dart';
import 'package:animus_editor/state/editor_store.dart';
import 'package:animus_editor/state/workspace_coordination.dart';
import 'package:animus_editor/state/workspace_repository.dart';
import 'package:animus_editor/state/workspace_route.dart';
import 'package:test/test.dart';

import 'fixtures.dart';

/// A repository whose saves fail with [refusal] while it is set.
class RefusingRepository extends MemoryWorkspaceRepository {
  Object? refusal;

  @override
  Future<void> save(WorkspaceSnapshot snapshot) async {
    final Object? refused = refusal;
    if (refused != null) throw refused;
    return super.save(snapshot);
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
        const PairRoute('ANM1.eyJ2IjoxfQ'),
        const ServerRoute('sha256:ab', <String>['npcs', 'town/guard']),
      ];
      for (final EditorRoute route in routes) {
        expect(EditorRoute.parse(route.format()), route, reason: route.format());
      }
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

    test('should redo an undone edit and say what each step did', () async {
      final WorkspaceSnapshot snapshot = await openExport();
      final AnimusDocument greeter = store.document(DocumentKind.npc, 'examples/greeter')!;
      await store.commit(documents: <DocumentChange>[DocumentChange(DocumentKind.npc, greeter.id, '${greeter.source} ')], label: 'rename examples/greeter');
      expect(store.undoLabel, 'rename examples/greeter');
      expect(await store.undo(), 'rename examples/greeter');
      expect(store.document(DocumentKind.npc, greeter.id)!.source, greeter.source);
      expect(store.canRedo, isTrue);
      expect(store.redoLabel, 'rename examples/greeter');
      final EditorStore reloaded = EditorStore(repository: repository, schemas: SharedSchemas.embedded);
      await reloaded.openWorkspace(snapshot.id);
      expect(reloaded.redoLabel, 'rename examples/greeter');
      expect(await store.redo(), 'rename examples/greeter');
      expect(store.document(DocumentKind.npc, greeter.id)!.source, '${greeter.source} ');
      expect(store.canRedo, isFalse);
      expect(await store.redo(), isNull);
      await store.undo();
      await store.editSource(DocumentKind.npc, greeter.id, '${greeter.source}  ');
      expect(store.canRedo, isFalse, reason: 'a new edit drops the redo steps');
      expect(await repository.loadHistory(snapshot.id, HistoryStack.redo), isEmpty);
    });

    test('should describe a change that has no label', () {
      bool none(DocumentChange _) => false;
      bool all(DocumentChange _) => true;
      expect(describeChanges(const <DocumentChange>[DocumentChange(DocumentKind.npc, 'a/b', null)], const <String>[], all), 'delete a/b');
      expect(describeChanges(const <DocumentChange>[DocumentChange(DocumentKind.npc, 'a/b', '{}')], const <String>[], all), 'edit a/b');
      expect(describeChanges(const <DocumentChange>[DocumentChange(DocumentKind.npc, 'a/b', '{}')], const <String>[], none), 'create a/b');
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

    test('should keep unknown fields when a workspace is stored and loaded', () async {
      final Map<String, Uint8List> files = <String, Uint8List>{
        'npcs/a/b.json': Uint8List.fromList(utf8.encode('{"futureField": {"x": 1}}')),
      };
      final WorkspaceSnapshot snapshot = await store.openFiles('One', files);
      final WorkspaceSnapshot loaded = (await repository.load(snapshot.id))!;
      expect(loaded.documents.single.source, '{"futureField": {"x": 1}}');
      expect((loaded.documents.single.json! as Map<String, Object?>)['futureField'], <String, Object?>{'x': 1});
    });
  });
}
