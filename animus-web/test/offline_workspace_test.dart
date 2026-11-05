import 'dart:math' as math;

import 'package:animus_editor/logic/gui/gui_json.dart';
import 'package:animus_editor/logic/offline/new_documents.dart';
import 'package:animus_editor/logic/offline/starter_workspace.dart';
import 'package:animus_editor/logic/shared_schemas.dart';
import 'package:animus_editor/model/animus_document.dart';
import 'package:animus_editor/model/document_kind.dart';
import 'package:animus_editor/state/editor_store.dart';
import 'package:animus_editor/state/workspace_repository.dart';
import 'package:animus_editor/state/workspace_route.dart';
import 'package:test/test.dart';

void main() {
  late MemoryWorkspaceRepository repository;
  late EditorStore store;
  int next = 0;

  setUp(() {
    repository = MemoryWorkspaceRepository();
    next = 0;
    store = EditorStore(repository: repository, schemas: SharedSchemas.embedded, newId: () => 'ws-${++next}');
  });

  test('should route to the New page, a kind\'s guided step and the sync page', () {
    for (final EditorRoute route in <EditorRoute>[
      const NewDocumentRoute('ws-1'),
      const NewDocumentRoute('ws-1', DocumentKind.npc),
      const NewDocumentRoute('ws-1', DocumentKind.textAnimation),
      const SyncRoute('ws-1'),
    ]) {
      expect(EditorRoute.parse(route.format()), route, reason: route.format());
    }
  });

  test('should create a blank named workspace and a starter one', () async {
    final WorkspaceSnapshot blank = await store.createWorkspace('Sketches');
    expect(blank.documents, isEmpty);
    expect(store.workspace?.name, 'Sketches');
    final WorkspaceSnapshot starter = await store.openFiles('Example packs', starterWorkspaceFiles());
    expect(starter.documents.where((AnimusDocument document) => !document.valid), isEmpty);
    expect(store.workspaces.map((WorkspaceSummary summary) => summary.name), <String>['Example packs', 'Sketches']);
  });

  test('should rename, duplicate and delete a workspace, and restore the deleted one', () async {
    final WorkspaceSnapshot starter = await store.openFiles('Starter', starterWorkspaceFiles());
    await store.renameWorkspace(starter.id, 'Town');
    expect(store.workspace?.name, 'Town');
    final WorkspaceSnapshot? copy = await store.duplicateWorkspace(starter.id, 'Town copy');
    expect(copy!.documents.length, starter.documents.length);
    expect(copy.server, isNull);
    await store.renameWorkspace(copy.id, 'Harbour');
    expect((await repository.load(copy.id))!.name, 'Harbour');
    await store.deleteWorkspace(starter.id);
    expect(store.workspace, isNull);
    expect(store.workspaces.map((WorkspaceSummary summary) => summary.name), <String>['Harbour']);
    expect(store.deleted?.name, 'Town');
    final WorkspaceSnapshot? restored = await store.restoreDeleted();
    expect(restored!.documents.length, starter.documents.length);
    expect(store.workspaces.map((WorkspaceSummary summary) => summary.name), <String>['Harbour', 'Town']);
  });

  test('should keep undo steps across a reload of the workspace', () async {
    final WorkspaceSnapshot blank = await store.createWorkspace('Sketches');
    final JsonMap npc = newNpc(const NpcSeed(name: 'Baker', group: 'town'), uuid: newUuid(math.Random(1)));
    await store.commit(documents: <DocumentChange>[DocumentChange(DocumentKind.npc, 'town/baker', prettyJson(npc), path: newDocumentPath(DocumentKind.npc, 'town/baker'))]);
    await store.commit(documents: <DocumentChange>[const DocumentChange(DocumentKind.npc, 'town/baker', null)]);
    expect(store.workspace!.documents, isEmpty);

    final EditorStore reloaded = EditorStore(repository: repository, schemas: SharedSchemas.embedded);
    expect(await reloaded.openWorkspace(blank.id), isTrue);
    expect(reloaded.canUndo, isTrue);
    await reloaded.undo();
    expect(reloaded.document(DocumentKind.npc, 'town/baker')?.valid, isTrue);
    await reloaded.undo();
    expect(reloaded.workspace!.documents, isEmpty);
    expect(reloaded.canUndo, isFalse);
    expect(await repository.loadUndo(blank.id), isEmpty);
  });

  test('should drop the undo steps of a deleted workspace and cap them', () async {
    final WorkspaceSnapshot blank = await store.createWorkspace('Sketches');
    for (int index = 0; index < EditorStore.undoLimit + 5; index++) {
      await store.commit(documents: <DocumentChange>[
        DocumentChange(DocumentKind.pack, 'p$index', prettyJson(newPack('p$index')), path: newDocumentPath(DocumentKind.pack, 'p$index')),
      ]);
    }
    expect((await repository.loadUndo(blank.id)).length, EditorStore.undoLimit);
    expect(store.document(DocumentKind.pack, 'p3')?.path, 'packs/p3/pack.json');
    await store.deleteWorkspace(blank.id);
    expect(await repository.loadUndo(blank.id), isEmpty);
  });
}
