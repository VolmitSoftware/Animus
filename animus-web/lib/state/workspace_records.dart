/// How a workspace is stored: a small header (name, generation, the server
/// binding without its bases), one record per document key holding the
/// document and its server base, and one record per resource. A commit that
/// changes one document writes the header and that one record.
library;

import 'dart:convert';

import '../model/animus_document.dart';
import '../model/server_binding.dart';

/// The stored layout the header names: split records (version 4). Versions
/// 1 to 3 were one record holding the whole workspace as JSON text.
const int splitLayoutVersion = 4;

/// One workspace as its records came back from storage.
class StoredWorkspace {
  const StoredWorkspace(this.header, this.documents, this.resources);

  final Map<String, Object?> header;

  /// Document records by `kind:id`.
  final Map<String, Map<String, Object?>> documents;

  /// Resource bytes as base64, by data-folder path.
  final Map<String, String> resources;
}

/// The records one save writes, with null removing a record.
class RecordWrite {
  const RecordWrite({
    required this.header,
    this.documents = const <String, Map<String, Object?>?>{},
    this.resources = const <String, String?>{},
    this.replace = false,
  });

  final Map<String, Object?> header;
  final Map<String, Map<String, Object?>?> documents;
  final Map<String, String?> resources;

  /// Removes every document and resource record the write does not name.
  final bool replace;
}

/// Undo and redo, kept beside the workspace.
enum HistoryStack { undo, redo }

/// Steps kept per stack, in memory and in storage.
const int historyLimit = 50;

/// Raw record storage under the repository: IndexedDB in the browser,
/// memory in tests and where the browser keeps nothing. Every call works on
/// whole records; the repository decides which ones change.
abstract class WorkspaceRecords {
  /// Every stored header. A header from the single-record layout carries
  /// the whole workspace as `text`.
  Future<List<Map<String, Object?>>> headers();

  Future<StoredWorkspace?> read(String id);

  /// The header and the document records of [keys] that exist, for a tab
  /// following another tab's small save.
  Future<StoredWorkspace?> readSome(String id, Iterable<String> keys, {bool resources = false});

  /// Writes [write] only if the stored header is at [expected] (0: absent),
  /// checked and written in one transaction. Throws [WorkspaceConflict]
  /// otherwise and [WorkspaceWriteFailure] when the browser refuses.
  Future<void> write(String id, int expected, RecordWrite write);

  Future<void> remove(String id);

  /// The stack's step records, oldest first.
  Future<List<Map<String, Object?>>> history(String id, HistoryStack stack);

  /// Adds [step] on top of [stack] and drops the oldest beyond [limit].
  Future<void> pushHistory(String id, HistoryStack stack, Map<String, Object?> step, int limit);

  /// Replaces the top step of [stack].
  Future<void> replaceTop(String id, HistoryStack stack, Map<String, Object?> step);

  Future<void> popHistory(String id, HistoryStack stack);

  Future<void> clearHistory(String id, HistoryStack stack);
}

/// Another tab (or an older copy) saved the workspace first.
class WorkspaceConflict implements Exception {
  const WorkspaceConflict(this.id, this.storedGeneration, this.attemptedGeneration);

  final String id;
  final int storedGeneration;
  final int attemptedGeneration;

  @override
  String toString() => 'Workspace $id is at generation $storedGeneration; this save expected ${attemptedGeneration - 1}';
}

/// The browser would not write the workspace (out of space, storage
/// switched off, a broken database).
class WorkspaceWriteFailure implements Exception {
  const WorkspaceWriteFailure(this.reason, {this.quota = false});

  /// What the browser said, such as `QuotaExceededError`.
  final String reason;

  /// True when the browser is out of storage space for this site.
  final bool quota;

  @override
  String toString() => 'The browser refused to write the workspace: $reason';
}

/// A document record: the document (absent when deleted here) and the
/// server base it started from. The base keeps its source only while the
/// document differs from it; an unchanged document is its own base.
Map<String, Object?> documentRecord(AnimusDocument? document, ServerBase? base) => <String, Object?>{
      if (document != null) 'document': document.toJson(),
      if (base != null)
        'base': <String, Object?>{
          'revision': base.revision,
          'hash': base.hash,
          'path': base.path,
          if (document == null || !jsonEquals(document.json, base.source)) 'source': base.source,
        },
    };

/// The document and base of a record; a base without source shares the
/// document's parsed JSON.
(AnimusDocument?, ServerBase?) readDocumentRecord(Map<String, Object?> record) {
  final Object? stored = record['document'];
  final AnimusDocument? document = stored is Map<String, Object?> ? AnimusDocument.fromJson(stored) : null;
  final Object? base = record['base'];
  if (base is! Map<String, Object?>) return (document, null);
  return (
    document,
    ServerBase(
      revision: (base['revision']! as num).toInt(),
      hash: base['hash']! as String,
      path: base['path']! as String,
      source: base.containsKey('source') ? base['source'] : document?.json,
    ),
  );
}

/// Records in memory: for tests and browsers without IndexedDB. Records are
/// kept as JSON text, so nothing stored shares objects with the editor.
class MemoryWorkspaceRecords implements WorkspaceRecords {
  final Map<String, String> _headers = <String, String>{};
  final Map<String, Map<String, String>> _documents = <String, Map<String, String>>{};
  final Map<String, Map<String, String>> _resources = <String, Map<String, String>>{};
  final Map<String, List<String>> _history = <String, List<String>>{};

  static String _key(String id, HistoryStack stack) => '${stack.name}:$id';

  @override
  Future<List<Map<String, Object?>>> headers() async =>
      <Map<String, Object?>>[for (final String text in _headers.values) jsonDecode(text) as Map<String, Object?>];

  @override
  Future<StoredWorkspace?> read(String id) async {
    final String? header = _headers[id];
    if (header == null) return null;
    return StoredWorkspace(
      jsonDecode(header) as Map<String, Object?>,
      <String, Map<String, Object?>>{
        for (final MapEntry<String, String> entry in (_documents[id] ?? const <String, String>{}).entries)
          entry.key: jsonDecode(entry.value) as Map<String, Object?>,
      },
      Map<String, String>.of(_resources[id] ?? const <String, String>{}),
    );
  }

  @override
  Future<StoredWorkspace?> readSome(String id, Iterable<String> keys, {bool resources = false}) async {
    final String? header = _headers[id];
    if (header == null) return null;
    final Map<String, String> stored = _documents[id] ?? const <String, String>{};
    return StoredWorkspace(
      jsonDecode(header) as Map<String, Object?>,
      <String, Map<String, Object?>>{
        for (final String key in keys)
          if (stored[key] != null) key: jsonDecode(stored[key]!) as Map<String, Object?>,
      },
      resources ? Map<String, String>.of(_resources[id] ?? const <String, String>{}) : const <String, String>{},
    );
  }

  @override
  Future<void> write(String id, int expected, RecordWrite write) async {
    final String? existing = _headers[id];
    final int stored = existing == null ? 0 : ((jsonDecode(existing) as Map<String, Object?>)['generation']! as num).toInt();
    if (stored != expected) throw WorkspaceConflict(id, stored, (write.header['generation']! as num).toInt());
    _headers[id] = jsonEncode(write.header);
    final Map<String, String> documents = write.replace ? <String, String>{} : (_documents[id] ?? <String, String>{});
    write.documents.forEach((String key, Map<String, Object?>? record) => record == null ? documents.remove(key) : documents[key] = jsonEncode(record));
    _documents[id] = documents;
    final Map<String, String> resources = write.replace ? <String, String>{} : (_resources[id] ?? <String, String>{});
    write.resources.forEach((String path, String? data) => data == null ? resources.remove(path) : resources[path] = data);
    _resources[id] = resources;
  }

  @override
  Future<void> remove(String id) async {
    _headers.remove(id);
    _documents.remove(id);
    _resources.remove(id);
  }

  @override
  Future<List<Map<String, Object?>>> history(String id, HistoryStack stack) async => <Map<String, Object?>>[
        for (final String text in _history[_key(id, stack)] ?? const <String>[]) jsonDecode(text) as Map<String, Object?>,
      ];

  @override
  Future<void> pushHistory(String id, HistoryStack stack, Map<String, Object?> step, int limit) async {
    final List<String> steps = _history.putIfAbsent(_key(id, stack), () => <String>[])..add(jsonEncode(step));
    while (steps.length > limit) {
      steps.removeAt(0);
    }
  }

  @override
  Future<void> replaceTop(String id, HistoryStack stack, Map<String, Object?> step) async {
    final List<String>? steps = _history[_key(id, stack)];
    if (steps == null || steps.isEmpty) return;
    steps[steps.length - 1] = jsonEncode(step);
  }

  @override
  Future<void> popHistory(String id, HistoryStack stack) async {
    final List<String>? steps = _history[_key(id, stack)];
    if (steps != null && steps.isNotEmpty) steps.removeLast();
  }

  @override
  Future<void> clearHistory(String id, HistoryStack stack) async {
    _history.remove(_key(id, stack));
  }
}
