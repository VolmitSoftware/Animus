/// IndexedDB storage for workspace records. Browser only.
///
/// Database `animus-editor`, version 3:
/// - `workspaces` (keyPath `id`): one header per workspace; a header an
///   older editor left holds the whole workspace as `text`.
/// - `documents` (keyPath `key`): `<workspace>\u0001<kind:id>`, the record as
///   JSON `text`.
/// - `resources` (keyPath `key`): `<workspace>\u0001<path>`, base64 `data`.
/// - `undo` (keyPath `key`): undo and redo steps, one record per step.
library;

import 'dart:async';
import 'dart:convert';
import 'dart:js_interop';

import 'package:web/web.dart' as web;

import 'workspace_records.dart';

class IndexedDbWorkspaceRecords implements WorkspaceRecords {
  IndexedDbWorkspaceRecords._(this._database);

  static const String databaseName = 'animus-editor';
  static const int databaseVersion = 3;
  static const String headerStore = 'workspaces';
  static const String documentStore = 'documents';
  static const String resourceStore = 'resources';
  static const String undoStore = 'undo';

  final web.IDBDatabase _database;

  /// Opens the database, or returns null when IndexedDB is unavailable
  /// (private windows in some browsers, or storage switched off).
  static Future<IndexedDbWorkspaceRecords?> open() async {
    try {
      final web.IDBOpenDBRequest request = web.window.indexedDB.open(databaseName, databaseVersion);
      request.onupgradeneeded = ((web.Event _) {
        final web.IDBDatabase database = request.result! as web.IDBDatabase;
        for (final String store in <String>[headerStore, undoStore]) {
          if (!database.objectStoreNames.contains(store)) {
            database.createObjectStore(store, web.IDBObjectStoreParameters(keyPath: (store == headerStore ? 'id' : 'key').toJS));
          }
        }
        for (final String store in <String>[documentStore, resourceStore]) {
          if (!database.objectStoreNames.contains(store)) database.createObjectStore(store, web.IDBObjectStoreParameters(keyPath: 'key'.toJS));
        }
      }).toJS;
      final web.IDBDatabase database = (await _complete(request))! as web.IDBDatabase;
      // A newer editor in another tab upgrades the database; this tab lets go.
      database.onversionchange = ((web.Event _) => database.close()).toJS;
      return IndexedDbWorkspaceRecords._(database);
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

  static String _within(String id, String key) => '$id\u0001$key';

  /// Every record key of workspace [id] in a split store.
  static web.IDBKeyRange _all(String id) => web.IDBKeyRange.bound('$id\u0001'.toJS, '$id\u0001￿'.toJS);

  static Map<String, Object?>? _object(JSAny? record) {
    if (record == null || record.isUndefinedOrNull) return null;
    final Object? value = (record as JSObject).dartify();
    return value is Map ? <String, Object?>{for (final MapEntry<Object?, Object?> entry in value.entries) '${entry.key}': entry.value} : null;
  }

  /// A header record's JSON: stored as `text` beside its `id`. A record from
  /// the single-record layout keeps its `text` as the workspace itself.
  static Map<String, Object?>? _header(JSAny? record) {
    final Map<String, Object?>? raw = _object(record);
    if (raw == null) return null;
    final Object? text = raw['text'];
    if (raw['header'] is String) return jsonDecode(raw['header']! as String) as Map<String, Object?>;
    if (text is String) return <String, Object?>{'id': raw['id'], 'generation': raw['generation'], 'text': text};
    return null;
  }

  static JSObject _headerRecord(String id, Map<String, Object?> header) =>
      <String, Object?>{'id': id, 'generation': header['generation'], 'header': jsonEncode(header)}.jsify()! as JSObject;

  static Map<String, Object?> _text(JSAny? record) => jsonDecode(_object(record)!['text']! as String) as Map<String, Object?>;

  static String _suffix(String id, JSAny? record) => (_object(record)!['key']! as String).substring(id.length + 1);

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
  Future<List<Map<String, Object?>>> headers() async {
    final JSAny? result = await _complete(_database.transaction(headerStore.toJS, 'readonly').objectStore(headerStore).getAll());
    return <Map<String, Object?>>[
      for (final JSAny? record in (result! as JSArray<JSAny?>).toDart)
        if (_header(record) case final Map<String, Object?> header) header,
    ];
  }

  @override
  Future<StoredWorkspace?> read(String id) async {
    final web.IDBTransaction transaction = _database.transaction(<JSString>[headerStore.toJS, documentStore.toJS, resourceStore.toJS].toJS, 'readonly');
    final Future<JSAny?> header = _complete(transaction.objectStore(headerStore).get(id.toJS));
    final Future<JSAny?> documents = _complete(transaction.objectStore(documentStore).getAll(_all(id)));
    final Future<JSAny?> resources = _complete(transaction.objectStore(resourceStore).getAll(_all(id)));
    final Map<String, Object?>? stored = _header(await header);
    final List<JSAny?> documentRecords = ((await documents)! as JSArray<JSAny?>).toDart;
    final List<JSAny?> resourceRecords = ((await resources)! as JSArray<JSAny?>).toDart;
    if (stored == null) return null;
    return StoredWorkspace(
      stored,
      <String, Map<String, Object?>>{for (final JSAny? record in documentRecords) _suffix(id, record): _text(record)},
      <String, String>{for (final JSAny? record in resourceRecords) _suffix(id, record): _object(record)!['data']! as String},
    );
  }

  @override
  Future<StoredWorkspace?> readSome(String id, Iterable<String> keys, {bool resources = false}) async {
    final web.IDBTransaction transaction = _database.transaction(<JSString>[headerStore.toJS, documentStore.toJS, resourceStore.toJS].toJS, 'readonly');
    final Future<JSAny?> header = _complete(transaction.objectStore(headerStore).get(id.toJS));
    final web.IDBObjectStore documents = transaction.objectStore(documentStore);
    final Map<String, Future<JSAny?>> reads = <String, Future<JSAny?>>{
      for (final String key in keys) key: _complete(documents.get(_within(id, key).toJS)),
    };
    final Future<JSAny?>? resourceRead = resources ? _complete(transaction.objectStore(resourceStore).getAll(_all(id))) : null;
    final Map<String, Object?>? stored = _header(await header);
    final Map<String, Map<String, Object?>> found = <String, Map<String, Object?>>{};
    for (final MapEntry<String, Future<JSAny?>> read in reads.entries) {
      final JSAny? record = await read.value;
      if (record != null && !record.isUndefinedOrNull) found[read.key] = _text(record);
    }
    final List<JSAny?> resourceRecords = resourceRead == null ? const <JSAny?>[] : ((await resourceRead)! as JSArray<JSAny?>).toDart;
    if (stored == null) return null;
    return StoredWorkspace(stored, found, <String, String>{
      for (final JSAny? record in resourceRecords) _suffix(id, record): _object(record)!['data']! as String,
    });
  }

  @override
  Future<void> write(String id, int expected, RecordWrite write) {
    final Completer<void> done = Completer<void>();
    final web.IDBTransaction transaction;
    try {
      transaction = _database.transaction(<JSString>[headerStore.toJS, documentStore.toJS, resourceStore.toJS].toJS, 'readwrite');
    } on Object catch (failure) {
      return Future<void>.error(WorkspaceWriteFailure('IndexedDB save: $failure'));
    }
    // One readwrite transaction reads the stored generation and writes, so a
    // second tab cannot interleave between the check and the puts. Every
    // request is made from a request callback, keeping the transaction alive.
    final web.IDBObjectStore headers = transaction.objectStore(headerStore);
    final web.IDBObjectStore documents = transaction.objectStore(documentStore);
    final web.IDBObjectStore resources = transaction.objectStore(resourceStore);
    WorkspaceConflict? conflict;
    final web.IDBRequest read = headers.get(id.toJS);
    read.onsuccess = ((web.Event _) {
      final Map<String, Object?>? existing = _header(read.result);
      final int stored = existing == null ? 0 : (existing['generation']! as num).toInt();
      if (stored != expected) {
        conflict = WorkspaceConflict(id, stored, (write.header['generation']! as num).toInt());
        transaction.abort();
        return;
      }
      if (write.replace) {
        documents.delete(_all(id));
        resources.delete(_all(id));
      }
      headers.put(_headerRecord(id, write.header));
      write.documents.forEach((String key, Map<String, Object?>? record) {
        if (record == null) {
          documents.delete(_within(id, key).toJS);
        } else {
          documents.put(<String, Object?>{'key': _within(id, key), 'text': jsonEncode(record)}.jsify()! as JSObject);
        }
      });
      write.resources.forEach((String path, String? data) {
        if (data == null) {
          resources.delete(_within(id, path).toJS);
        } else {
          resources.put(<String, Object?>{'key': _within(id, path), 'data': data}.jsify()! as JSObject);
        }
      });
    }).toJS;
    _settle(transaction, done, 'save', refusal: () => conflict);
    return done.future;
  }

  @override
  Future<void> remove(String id) {
    final Completer<void> done = Completer<void>();
    final web.IDBTransaction transaction =
        _database.transaction(<JSString>[headerStore.toJS, documentStore.toJS, resourceStore.toJS].toJS, 'readwrite');
    transaction.objectStore(headerStore).delete(id.toJS);
    transaction.objectStore(documentStore).delete(_all(id));
    transaction.objectStore(resourceStore).delete(_all(id));
    _settle(transaction, done, 'delete');
    return done.future;
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

  /// A step record: its label and the step as JSON `text`. A step an older
  /// editor stored keeps its `text` as a whole workspace and no `step` mark.
  static JSObject _stepRecord(String key, Map<String, Object?> step) =>
      <String, Object?>{'key': key, 'step': true, 'text': jsonEncode(step)}.jsify()! as JSObject;

  @override
  Future<List<Map<String, Object?>>> history(String id, HistoryStack stack) async {
    final JSAny? result =
        await _complete(_database.transaction(undoStore.toJS, 'readonly').objectStore(undoStore).getAll(_historyRange(id, stack)));
    return <Map<String, Object?>>[
      for (final JSAny? record in (result! as JSArray<JSAny?>).toDart)
        if (_object(record) case final Map<String, Object?> raw)
          raw['step'] == true ? jsonDecode(raw['text']! as String) as Map<String, Object?> : <String, Object?>{'label': raw['label'], 'text': raw['text']},
    ];
  }

  @override
  Future<void> pushHistory(String id, HistoryStack stack, Map<String, Object?> step, int limit) =>
      _historyWrite(id, stack, (web.IDBObjectStore objects, List<String> keys) {
        final int next = keys.isEmpty ? 1 : _sequenceOf(keys.last) + 1;
        objects.put(_stepRecord(_historyKey(id, stack, next), step));
        for (int index = 0; index < keys.length + 1 - limit; index++) {
          objects.delete(keys[index].toJS);
        }
      });

  @override
  Future<void> replaceTop(String id, HistoryStack stack, Map<String, Object?> step) =>
      _historyWrite(id, stack, (web.IDBObjectStore objects, List<String> keys) {
        if (keys.isNotEmpty) objects.put(_stepRecord(keys.last, step));
      });

  @override
  Future<void> popHistory(String id, HistoryStack stack) => _historyWrite(id, stack, (web.IDBObjectStore objects, List<String> keys) {
        if (keys.isNotEmpty) objects.delete(keys.last.toJS);
      });

  @override
  Future<void> clearHistory(String id, HistoryStack stack) => _historyWrite(id, stack, (web.IDBObjectStore objects, List<String> keys) {
        objects.delete(_historyRange(id, stack));
      });
}
