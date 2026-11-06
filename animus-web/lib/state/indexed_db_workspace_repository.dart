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

  /// Undo and redo steps, one record per step (version 2).
  static const String undoStore = 'undo';

  final web.IDBDatabase _database;

  /// Opens the database, or returns null when IndexedDB is unavailable
  /// (private windows in some browsers, or storage switched off).
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

  /// Completes [done] when [transaction] commits. Otherwise fails it with
  /// [refusal]'s exception when there is one (a generation conflict), or a
  /// [WorkspaceWriteFailure] naming the browser's reason.
  static void _settle(web.IDBTransaction transaction, Completer<void> done, String what, {Exception? Function()? refusal}) {
    void fail() {
      if (done.isCompleted) return;
      final Exception? refused = refusal?.call();
      if (refused != null) {
        done.completeError(refused);
        return;
      }
      final String name = transaction.error?.name ?? 'AbortError';
      done.completeError(WorkspaceWriteFailure('IndexedDB $what: $name', quota: name == 'QuotaExceededError'));
    }

    transaction.oncomplete = ((web.Event _) {
      if (!done.isCompleted) done.complete();
    }).toJS;
    transaction.onabort = ((web.Event _) => fail()).toJS;
    transaction.onerror = ((web.Event _) => fail()).toJS;
  }

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
  Future<void> save(WorkspaceSnapshot snapshot) {
    final Completer<void> done = Completer<void>();
    final web.IDBTransaction transaction;
    try {
      transaction = _database.transaction(store.toJS, 'readwrite');
    } on Object catch (failure) {
      return Future<void>.error(WorkspaceWriteFailure('IndexedDB save: $failure'));
    }
    // One readwrite transaction reads the stored generation and writes, so a
    // second tab cannot interleave between the check and the put.
    final web.IDBObjectStore objects = transaction.objectStore(store);
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
    _settle(transaction, done, 'save', refusal: () => conflict);
    return done.future;
  }

  @override
  Future<void> delete(String id) async {
    await _complete(_store('readwrite').delete(id.toJS));
  }

  /// History keys are `<workspace><stack mark><12-digit sequence>`, with
  /// U+0001 marking undo steps and U+0002 redo steps, so a key range lists
  /// one workspace's steps on one stack in order.
  static String _mark(HistoryStack stack) => stack == HistoryStack.undo ? '\u0001' : '\u0002';

  static web.IDBKeyRange _historyRange(String id, HistoryStack stack) =>
      web.IDBKeyRange.bound('$id${_mark(stack)}'.toJS, '$id${_mark(stack)}￿'.toJS);

  static String _historyKey(String id, HistoryStack stack, int sequence) => '$id${_mark(stack)}${sequence.toString().padLeft(12, '0')}';

  static int _sequenceOf(String key) => int.tryParse(key.substring(key.length - 12)) ?? 0;

  /// Runs [work] with the stack's keys inside one readwrite transaction.
  /// Everything happens in request callbacks, so the transaction stays
  /// active between the read and the writes.
  Future<void> _historyWrite(String id, HistoryStack stack, void Function(web.IDBObjectStore objects, List<String> keys) work) {
    final web.IDBTransaction transaction = _database.transaction(undoStore.toJS, 'readwrite');
    final web.IDBObjectStore objects = transaction.objectStore(undoStore);
    final Completer<void> done = Completer<void>();
    final web.IDBRequest read = objects.getAllKeys(_historyRange(id, stack));
    read.onsuccess = ((web.Event _) {
      final List<String> keys = <String>[for (final JSAny? key in (read.result! as JSArray<JSAny?>).toDart) (key! as JSString).toDart];
      work(objects, keys);
    }).toJS;
    _settle(transaction, done, 'history write');
    return done.future;
  }

  @override
  Future<List<HistoryStep>> loadHistory(String id, HistoryStack stack) async {
    final JSAny? result =
        await _complete(_database.transaction(undoStore.toJS, 'readonly').objectStore(undoStore).getAll(_historyRange(id, stack)));
    final List<HistoryStep> steps = <HistoryStep>[];
    for (final JSAny? record in (result! as JSArray<JSAny?>).toDart) {
      final String? text = _text(record);
      if (text == null) continue;
      try {
        steps.add(HistoryStep(_label(record), WorkspaceSnapshot.decode(text)));
      } on FormatException {
        continue;
      }
    }
    return steps;
  }

  @override
  Future<void> pushHistory(String id, HistoryStack stack, HistoryStep step, int limit) {
    final String text = step.snapshot.encode();
    return _historyWrite(id, stack, (web.IDBObjectStore objects, List<String> keys) {
      final int next = keys.isEmpty ? 1 : _sequenceOf(keys.last) + 1;
      objects.put(<String, Object?>{'key': _historyKey(id, stack, next), 'label': step.label, 'text': text}.jsify()! as JSObject);
      for (int index = 0; index < keys.length + 1 - limit; index++) {
        objects.delete(keys[index].toJS);
      }
    });
  }

  @override
  Future<void> popHistory(String id, HistoryStack stack) => _historyWrite(id, stack, (web.IDBObjectStore objects, List<String> keys) {
        if (keys.isNotEmpty) objects.delete(keys.last.toJS);
      });

  @override
  Future<void> clearHistory(String id, HistoryStack stack) => _historyWrite(id, stack, (web.IDBObjectStore objects, List<String> keys) {
        objects.delete(_historyRange(id, stack));
      });

  static JSObject _record(WorkspaceSnapshot snapshot) =>
      <String, Object?>{'id': snapshot.id, 'generation': snapshot.generation, 'text': snapshot.encode()}.jsify()! as JSObject;

  /// Steps saved before redo existed carry no label.
  static String _label(JSAny? record) {
    final Object? value = record == null || record.isUndefinedOrNull ? null : (record as JSObject).dartify();
    return value is Map && value['label'] is String ? value['label'] as String : 'an earlier change';
  }

  static String? _text(JSAny? record) {
    if (record == null || record.isUndefinedOrNull) return null;
    final Object? value = (record as JSObject).dartify();
    if (value is Map && value['text'] is String) return value['text'] as String;
    return null;
  }
}
