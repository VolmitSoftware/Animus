import 'package:animus_editor/logic/gui/gui_json.dart';
import 'package:animus_editor/logic/shared_schemas.dart';
import 'package:animus_editor/model/animus_document.dart';
import 'package:animus_editor/model/document_kind.dart';
import 'package:animus_editor/state/editor_store.dart';
import 'package:animus_editor/state/workspace_records.dart';
import 'package:animus_editor/state/workspace_repository.dart';
import 'package:test/test.dart';

import 'fixtures.dart';

const String greeter = 'examples/greeter';

void main() {
  late MemoryWorkspaceRecords records;
  late MemoryWorkspaceRepository repository;
  late EditorStore store;
  late String workspaceId;

  setUp(() async {
    records = MemoryWorkspaceRecords();
    repository = MemoryWorkspaceRepository(records);
    store = EditorStore(repository: repository, schemas: SharedSchemas.embedded, newId: () => 'ws-undo');
    workspaceId = (await store.openFiles('Export', fixtureFiles('export'))).id;
  });

  Map<String, Object?> json() => store.document(DocumentKind.npc, greeter)!.json! as Map<String, Object?>;

  /// An edit made here: one undo step.
  Future<void> edit(String field, Object? value, {String? coalesce}) => store.commit(
        documents: <DocumentChange>[DocumentChange.update(DocumentKind.npc, greeter, (Object? latest) => <String, Object?>{...latest! as Map<String, Object?>, field: value})],
        coalesce: coalesce,
      );

  /// A change from elsewhere, as a server refresh lands: outside the history.
  Future<void> outside(String field, Object? value) => store.commit(
        documents: <DocumentChange>[DocumentChange(DocumentKind.npc, greeter, prettyJson(<String, Object?>{...json(), field: value}))],
        undoable: false,
      );

  test('should undo and redo plainly when nothing else changed the document', () async {
    final String before = store.document(DocumentKind.npc, greeter)!.source;
    await edit('name', 'Host');
    final HistoryMove? undone = await store.undo();
    expect(undone?.label, 'edit Greeter');
    expect(undone?.refused, isNull);
    expect(store.document(DocumentKind.npc, greeter)!.source, before);
    final HistoryMove? redone = await store.redo();
    expect(redone?.label, 'edit Greeter');
    expect(json()['name'], 'Host');
    expect(await store.redo(), isNull);
  });

  test('should undo only its own change after a refresh changed other fields', () async {
    await edit('name', 'Host');
    await outside('spawned', false);
    final HistoryMove? undone = await store.undo();
    expect(undone?.refused, isNull);
    expect(json()['name'], 'Greeter', reason: 'the edit is undone');
    expect(json()['spawned'], isFalse, reason: 'the change from elsewhere stays');
    await store.redo();
    expect(json()['name'], 'Host');
    expect(json()['spawned'], isFalse);
  });

  test('should refuse to undo and write nothing when a refresh changed the same field', () async {
    await edit('name', 'Host');
    await outside('name', 'Doorman');
    final String source = store.document(DocumentKind.npc, greeter)!.source;
    final int edits = store.editCount;
    final HistoryMove? refused = await store.undo();
    expect(refused?.refused, "Can't undo: Doorman changed on the server.");
    expect(store.document(DocumentKind.npc, greeter)!.source, source, reason: 'nothing written');
    expect(store.editCount, edits);
    expect(store.canUndo, isFalse, reason: 'the step that cannot apply is dropped');
    expect(store.canRedo, isFalse);
  });

  test('should keep the latest state of a coalesced step', () async {
    await edit('name', 'H', coalesce: 'name');
    await edit('name', 'Ho', coalesce: 'name');
    await edit('name', 'Host', coalesce: 'name');
    await outside('spawned', false);
    final HistoryMove? undone = await store.undo();
    expect(undone?.refused, isNull, reason: 'the typing is all one change of the name');
    expect(json()['name'], 'Greeter');
    expect(json()['spawned'], isFalse);
  });

  test('should merge an undo after a reload, from the stored steps', () async {
    await edit('name', 'H', coalesce: 'name');
    await edit('name', 'Host', coalesce: 'name');
    await outside('spawned', false);
    final EditorStore reopened = EditorStore(repository: repository, schemas: SharedSchemas.embedded);
    expect(await reopened.openWorkspace(workspaceId), isTrue);
    final HistoryMove? undone = await reopened.undo();
    expect(undone?.refused, isNull);
    final Map<String, Object?> now = reopened.document(DocumentKind.npc, greeter)!.json! as Map<String, Object?>;
    expect(now['name'], 'Greeter');
    expect(now['spawned'], isFalse);
  });

  test('should drop stored steps that do not say what they left', () async {
    await edit('name', 'Host');
    final AnimusDocument document = store.document(DocumentKind.npc, greeter)!;
    await records.clearHistory(workspaceId, HistoryStack.undo);
    await records.pushHistory(
      workspaceId,
      HistoryStack.undo,
      <String, Object?>{
        'label': 'edit Greeter',
        'documents': <String, Object?>{'npc:$greeter': document.toJson()},
      },
      50,
    );
    final EditorStore reopened = EditorStore(repository: repository, schemas: SharedSchemas.embedded);
    await reopened.openWorkspace(workspaceId);
    expect(reopened.canUndo, isFalse);
    expect(await records.history(workspaceId, HistoryStack.undo), isEmpty);
  });
}
