// The workspace store on the browser's own IndexedDB at a real server's
// scale (1200 documents): what one keystroke and one undo cost, how many
// records a commit writes, and the move from the single-record layout an
// older editor left (database version 2). Runs in Chrome only:
//
//   CHROME_EXECUTABLE=<Chrome or Chrome for Testing> dart test -p chrome test/workspace_storage_browser_test.dart
@TestOn('browser')
library;

import 'dart:async';
import 'dart:convert';
import 'dart:js_interop';

import 'package:animus_editor/logic/gui/gui_json.dart';
import 'package:animus_editor/logic/shared_schemas.dart';
import 'package:animus_editor/model/animus_document.dart';
import 'package:animus_editor/model/document_kind.dart';
import 'package:animus_editor/model/server_binding.dart';
import 'package:animus_editor/state/editor_store.dart';
import 'package:animus_editor/state/indexed_db_workspace_records.dart';
import 'package:animus_editor/state/workspace_records.dart';
import 'package:animus_editor/state/workspace_repository.dart';
import 'package:test/test.dart';
import 'package:web/web.dart' as web;

/// Records that pass every call to IndexedDB and note each write.
class CountedRecords implements WorkspaceRecords {
  CountedRecords(this.inner);

  final WorkspaceRecords inner;
  final List<RecordWrite> writes = <RecordWrite>[];

  @override
  Future<List<Map<String, Object?>>> headers() => inner.headers();

  @override
  Future<StoredWorkspace?> read(String id) => inner.read(id);

  @override
  Future<StoredWorkspace?> readSome(String id, Iterable<String> keys, {bool resources = false}) => inner.readSome(id, keys, resources: resources);

  @override
  Future<void> write(String id, int expected, RecordWrite write) async {
    await inner.write(id, expected, write);
    writes.add(write);
  }

  @override
  Future<void> remove(String id) => inner.remove(id);

  @override
  Future<List<Map<String, Object?>>> history(String id, HistoryStack stack) => inner.history(id, stack);

  @override
  Future<void> pushHistory(String id, HistoryStack stack, Map<String, Object?> step, int limit) => inner.pushHistory(id, stack, step, limit);

  @override
  Future<void> replaceTop(String id, HistoryStack stack, Map<String, Object?> step) => inner.replaceTop(id, stack, step);

  @override
  Future<void> popHistory(String id, HistoryStack stack) => inner.popHistory(id, stack);

  @override
  Future<void> clearHistory(String id, HistoryStack stack) => inner.clearHistory(id, stack);
}

/// An NPC of about 5 KB, like an imported Citizens NPC.
Map<String, Object?> npc(int index) => <String, Object?>{
      'schemaVersion': 2,
      'revision': 1,
      'uuid': '00000000-0000-4000-8000-${index.toString().padLeft(12, '0')}',
      'id': 'citizens-$index',
      'group': 'world',
      'name': '&6Citizen $index',
      'type': 'minecraft:player',
      'spawned': true,
      'position': <String, Object?>{'world': 'world', 'x': index * 1.5, 'y': 64, 'z': -index * 0.5, 'yaw': 0, 'pitch': 0},
      'tags': <String>['town', 'imported'],
      'behaviors': <String>['citizens:greeting-$index'],
      'description': List<String>.generate(60, (int line) => 'Imported line $line of citizen $index.').join(' '),
    };

/// Completes on the first [type] event of [target].
Future<void> fired(web.EventTarget target, String type) {
  final Completer<void> done = Completer<void>();
  target.addEventListener(type, ((web.Event _) {
    if (!done.isCompleted) done.complete();
  }).toJS);
  return done.future;
}

Future<void> deleteDatabase() => fired(web.window.indexedDB.deleteDatabase(IndexedDbWorkspaceRecords.databaseName), 'success');

double median(List<double> values) => (List<double>.of(values)..sort())[values.length ~/ 2];

void main() {
  setUp(deleteDatabase);

  test('should store a keystroke under 50 ms and an undo under 150 ms with 1200 documents, one record each', () async {
    final CountedRecords records = CountedRecords((await IndexedDbWorkspaceRecords.open())!);
    final WorkspaceRepository repository = WorkspaceRepository(records);
    final List<AnimusDocument> documents = <AnimusDocument>[
      for (int index = 0; index < 1200; index++)
        AnimusDocument(kind: DocumentKind.npc, id: 'world/citizens-$index', path: 'npcs/world/citizens-$index.json', source: prettyJson(npc(index)), json: npc(index), diagnostics: const <Diagnostic>[]),
    ];
    await repository.save(WorkspaceSnapshot(id: 'ws-scale', name: 'Scale', generation: 1, documents: documents));
    final EditorStore store = EditorStore(repository: repository, schemas: SharedSchemas.embedded);
    expect(await store.openWorkspace('ws-scale'), isTrue);
    expect(store.workspace!.documents, hasLength(1200));
    records.writes.clear();
    final List<double> keystrokes = <double>[];
    String name = 'Citizen';
    for (int key = 0; key < 15; key++) {
      name = '${name}x';
      final String typed = name;
      final Stopwatch watch = Stopwatch()..start();
      await store.commit(
        documents: <DocumentChange>[DocumentChange.update(DocumentKind.npc, 'world/citizens-7', (Object? json) => <String, Object?>{...json! as Map<String, Object?>, 'name': typed})],
        coalesce: 'name',
      );
      keystrokes.add(watch.elapsedMicroseconds / 1000);
    }
    expect(records.writes, hasLength(15));
    expect(records.writes.every((RecordWrite write) => write.documents.keys.single == 'npc:world/citizens-7' && !write.replace), isTrue,
        reason: 'a one-document commit writes the header and one document record');
    final Stopwatch undo = Stopwatch()..start();
    expect(await store.undo(), isNotNull);
    final double undoMs = undo.elapsedMicroseconds / 1000;
    expect((store.document(DocumentKind.npc, 'world/citizens-7')!.json! as Map<String, Object?>)['name'], '&6Citizen 7');
    // ignore: avoid_print
    print('1200 documents: keystroke median ${median(keystrokes).toStringAsFixed(1)} ms, max ${keystrokes.reduce((double a, double b) => a > b ? a : b).toStringAsFixed(1)} ms; undo ${undoMs.toStringAsFixed(1)} ms');
    expect(median(keystrokes), lessThan(50));
    expect(undoMs, lessThan(150));
    final EditorStore reopened = EditorStore(repository: WorkspaceRepository((await IndexedDbWorkspaceRecords.open())!), schemas: SharedSchemas.embedded);
    expect(await reopened.openWorkspace('ws-scale'), isTrue);
    expect((reopened.document(DocumentKind.npc, 'world/citizens-7')!.json! as Map<String, Object?>)['name'], '&6Citizen 7');
    expect(reopened.canRedo, isTrue, reason: 'the redo step survives a reload');
  });

  test('should open a database an older editor left (version 2, one record of text) and move it onto split records', () async {
    final Map<String, Object?> guard = npc(1);
    final String text = jsonEncode(<String, Object?>{
      'schemaVersion': 3,
      'id': 'server-old',
      'name': 'Old server',
      'generation': 4,
      'documents': <Object?>[
        <String, Object?>{'kind': 'npc', 'id': 'world/citizens-1', 'path': 'npcs/world/citizens-1.json', 'source': prettyJson(<String, Object?>{...guard, 'name': 'Edited'}), 'diagnostics': <Object?>[]},
      ],
      'server': ServerBinding(serverId: 'old', fingerprint: 'sha256:00', bases: <String, ServerBase>{
        'npc:world/citizens-1': ServerBase(revision: 1, hash: 'sha256:aa', path: 'npcs/world/citizens-1.json', source: guard),
      }).toJson(),
    });
    final web.IDBOpenDBRequest open = web.window.indexedDB.open(IndexedDbWorkspaceRecords.databaseName, 2);
    open.onupgradeneeded = ((web.Event _) {
      final web.IDBDatabase database = open.result! as web.IDBDatabase;
      database.createObjectStore('workspaces', web.IDBObjectStoreParameters(keyPath: 'id'.toJS));
      database.createObjectStore('undo', web.IDBObjectStoreParameters(keyPath: 'key'.toJS));
    }).toJS;
    await fired(open, 'success');
    final web.IDBDatabase old = open.result! as web.IDBDatabase;
    final web.IDBTransaction write = old.transaction('workspaces'.toJS, 'readwrite');
    write.objectStore('workspaces').put(<String, Object?>{'id': 'server-old', 'generation': 4, 'text': text}.jsify()! as JSObject);
    await fired(write, 'complete');
    old.close();

    final WorkspaceRepository repository = WorkspaceRepository((await IndexedDbWorkspaceRecords.open())!);
    final List<WorkspaceSummary> listed = await repository.list();
    expect(listed.single.name, 'Old server');
    expect(listed.single.serverId, 'old');
    final WorkspaceSnapshot loaded = (await repository.load('server-old'))!;
    expect(loaded.generation, 4);
    expect((loaded.document(DocumentKind.npc, 'world/citizens-1')!.json! as Map<String, Object?>)['name'], 'Edited');
    expect((loaded.server!.bases['npc:world/citizens-1']!.source! as Map<String, Object?>)['name'], '&6Citizen 1');
    final EditorStore store = EditorStore(repository: repository, schemas: SharedSchemas.embedded);
    expect(await store.openWorkspace('server-old'), isTrue);
    await store.commit(documents: <DocumentChange>[DocumentChange.update(DocumentKind.npc, 'world/citizens-1', (Object? json) => <String, Object?>{...json! as Map<String, Object?>, 'name': 'Again'})]);
    expect(store.problem, isNull);
    expect((await repository.load('server-old'))!.generation, 5);
  });
}
