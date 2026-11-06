/// IndexedDB storage for workspaces. Browser only.
library;

import 'dart:async';
import 'dart:js_interop';

import 'package:web/web.dart' as web;

import 'workspace_repository.dart';

class IndexedDbWorkspaceRepository implements WorkspaceRepository {
  IndexedDbWorkspaceRepository._(this._database);

  static const String databaseName = 'animus-editor';
  static const int databaseVersion = 2;
  static const String store = 'workspaces';

  /// Undo steps, one record per step (version 2).
  static const String undoStore = 'undo';

  final web.IDBDatabase _database;

  /// Opens the database, or returns null when IndexedDB is unavailable
  /// (private windows in some browsers).
  static Future<IndexedDbWorkspaceRepository?> open() async {
    try {
      final web.IDBOpenDBRequest request = web.window.indexedDB.open(databaseName, databaseVersion);
      request.onupgradeneeded = ((web.Event _) {
        final web.IDBDatabase database = request.result! as web.IDBDatabase;
        if (!database.objectStoreNames.contains(store)) {
          database.createObjectStore(store, web.IDBObjectStoreParameters(keyPath: 'id'.toJS));
        }
        if (!database.objectStoreNames.contains(undoStore)) {
          database.createObjectStore(undoStore, web.IDBObjectStoreParameters(keyPath: 'key'.toJS));
        }
      }).toJS;
      final JSAny? result = await _complete(request);
      return IndexedDbWorkspaceRepository._(result! as web.IDBDatabase);
    } on Object {
      return null;
    }
  }

  static Future<JSAny?> _complete(web.IDBRequest request) {
    final Completer<JSAny?> completer = Completer<JSAny?>();
    request.onsuccess = ((web.Event _) => completer.complete(request.result)).toJS;
    request.onerror = ((web.Event _) => completer.completeError(StateError('IndexedDB: ${request.error?.message}'))).toJS;
    return completer.future;
  }

  web.IDBObjectStore _store(String mode) => _database.transaction(store.toJS, mode).objectStore(store);

  @override
  Future<List<WorkspaceSummary>> list() async {
    final JSAny? result = await _complete(_store('readonly').getAll());
    final List<WorkspaceSummary> summaries = <WorkspaceSummary>[];
    for (final JSAny? record in (result! as JSArray<JSAny?>).toDart) {
      final String? text = _text(record);
      if (text == null) continue;
      try {
        summaries.add(summarize(WorkspaceSnapshot.decode(text)));
      } on FormatException {
        continue;
      }
    }
    summaries.sort((WorkspaceSummary a, WorkspaceSummary b) => a.name.compareTo(b.name));
    return summaries;
  }

  @override
  Future<WorkspaceSnapshot?> load(String id) async {
    final String? text = _text(await _complete(_store('readonly').get(id.toJS)));
    return text == null ? null : WorkspaceSnapshot.decode(text);
  }

  @override
  Future<void> save(WorkspaceSnapshot snapshot) async {
    // One readwrite transaction reads the stored generation and writes, so a
    // second tab cannot interleave between the check and the put.
    final web.IDBTransaction transaction = _database.transaction(store.toJS, 'readwrite');
    final web.IDBObjectStore objects = transaction.objectStore(store);
    final Completer<void> done = Completer<void>();
    WorkspaceConflict? conflict;
    final web.IDBRequest read = objects.get(snapshot.id.toJS);
    read.onsuccess = ((web.Event _) {
      final String? existing = _text(read.result);
      final int stored = existing == null ? 0 : WorkspaceSnapshot.decode(existing).generation;
      if (stored != snapshot.generation - 1) {
        conflict = WorkspaceConflict(snapshot.id, stored, snapshot.generation);
        transaction.abort();
        return;
      }
      objects.put(_record(snapshot));
    }).toJS;
    transaction.oncomplete = ((web.Event _) => done.complete()).toJS;
    transaction.onabort = ((web.Event _) {
      if (!done.isCompleted) done.completeError(conflict ?? StateError('IndexedDB save aborted'));
    }).toJS;
    transaction.onerror = ((web.Event _) {
      if (!done.isCompleted) done.completeError(conflict ?? StateError('IndexedDB save failed'));
    }).toJS;
    return done.future;
  }

  @override
  Future<void> delete(String id) async {
    await _complete(_store('readwrite').delete(id.toJS));
  }

  /// Undo keys are `<workspace>\u0001<12-digit sequence>`, so a key range
  /// lists one workspace's steps in order.
  static web.IDBKeyRange _undoRange(String id) => web.IDBKeyRange.bound('$id\u0001'.toJS, '$id\u0001￿'.toJS);

  static String _undoKey(String id, int sequence) => '$id\u0001${sequence.toString().padLeft(12, '0')}';

  static int _sequenceOf(String key) => int.tryParse(key.substring(key.lastIndexOf('\u0001') + 1)) ?? 0;

  /// Runs [work] with the workspace's undo keys inside one readwrite
  /// transaction. Everything happens in request callbacks, so the
  /// transaction stays active between the read and the writes.
  Future<void> _undoWrite(String id, void Function(web.IDBObjectStore objects, List<String> keys) work) {
    final web.IDBTransaction transaction = _database.transaction(undoStore.toJS, 'readwrite');
    final web.IDBObjectStore objects = transaction.objectStore(undoStore);
    final Completer<void> done = Completer<void>();
    final web.IDBRequest read = objects.getAllKeys(_undoRange(id));
    read.onsuccess = ((web.Event _) {
      final List<String> keys = <String>[for (final JSAny? key in (read.result! as JSArray<JSAny?>).toDart) (key! as JSString).toDart];
      work(objects, keys);
    }).toJS;
    transaction.oncomplete = ((web.Event _) => done.complete()).toJS;
    transaction.onabort = ((web.Event _) {
      if (!done.isCompleted) done.completeError(StateError('IndexedDB undo write aborted'));
    }).toJS;
    transaction.onerror = ((web.Event _) {
      if (!done.isCompleted) done.completeError(StateError('IndexedDB undo write failed'));
    }).toJS;
    return done.future;
  }

  @override
  Future<List<WorkspaceSnapshot>> loadUndo(String id) async {
    final JSAny? result = await _complete(_database.transaction(undoStore.toJS, 'readonly').objectStore(undoStore).getAll(_undoRange(id)));
    final List<WorkspaceSnapshot> steps = <WorkspaceSnapshot>[];
    for (final JSAny? record in (result! as JSArray<JSAny?>).toDart) {
      final String? text = _text(record);
      if (text == null) continue;
      try {
        steps.add(WorkspaceSnapshot.decode(text));
      } on FormatException {
        continue;
      }
    }
    return steps;
  }

  @override
  Future<void> pushUndo(String id, WorkspaceSnapshot snapshot, int limit) {
    final String text = snapshot.encode();
    return _undoWrite(id, (web.IDBObjectStore objects, List<String> keys) {
      final int next = keys.isEmpty ? 1 : _sequenceOf(keys.last) + 1;
      objects.put(<String, Object?>{'key': _undoKey(id, next), 'text': text}.jsify()! as JSObject);
      for (int index = 0; index < keys.length + 1 - limit; index++) {
        objects.delete(keys[index].toJS);
      }
    });
  }

  @override
  Future<void> popUndo(String id) => _undoWrite(id, (web.IDBObjectStore objects, List<String> keys) {
        if (keys.isNotEmpty) objects.delete(keys.last.toJS);
      });

  @override
  Future<void> clearUndo(String id) => _undoWrite(id, (web.IDBObjectStore objects, List<String> keys) {
        objects.delete(_undoRange(id));
      });

  static JSObject _record(WorkspaceSnapshot snapshot) =>
      <String, Object?>{'id': snapshot.id, 'generation': snapshot.generation, 'text': snapshot.encode()}.jsify()! as JSObject;

  static String? _text(JSAny? record) {
    if (record == null || record.isUndefinedOrNull) return null;
    final Object? value = (record as JSObject).dartify();
    if (value is Map && value['text'] is String) return value['text'] as String;
    return null;
  }
}
