/// IndexedDB storage for workspaces. Browser only.
library;

import 'dart:async';
import 'dart:js_interop';

import 'package:web/web.dart' as web;

import 'workspace_repository.dart';

class IndexedDbWorkspaceRepository implements WorkspaceRepository {
  IndexedDbWorkspaceRepository._(this._database);

  static const String databaseName = 'animus-editor';
  static const int databaseVersion = 1;
  static const String store = 'workspaces';

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

  static JSObject _record(WorkspaceSnapshot snapshot) =>
      <String, Object?>{'id': snapshot.id, 'generation': snapshot.generation, 'text': snapshot.encode()}.jsify()! as JSObject;

  static String? _text(JSAny? record) {
    if (record == null || record.isUndefinedOrNull) return null;
    final Object? value = (record as JSObject).dartify();
    if (value is Map && value['text'] is String) return value['text'] as String;
    return null;
  }
}
