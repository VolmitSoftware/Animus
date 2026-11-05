/// Syncing a local workspace with a paired server once one is connected:
/// comparing documents with the server's catalog, validating a push on the
/// server, sending it as one change set, and pulling server documents in.
/// The workspace stays local; nothing is bound to the server.
library;

import '../logic/change_sets.dart';
import '../logic/gui/gui_json.dart';
import '../logic/offline/sync_plan.dart';
import '../model/animus_document.dart';
import '../model/document_kind.dart';
import '../model/server_binding.dart';
import '../services/server/server_connection.dart';
import '../services/server/transport.dart';
import 'editor_store.dart';
import 'live_session.dart';
import 'workspace_repository.dart';

/// A request that did not get an answer it could use.
class SyncFailure implements Exception {
  const SyncFailure(this.message);

  final String message;

  @override
  String toString() => message;
}

/// The server's answer to a push.
sealed class PushResult {
  const PushResult();
}

class PushApplied extends PushResult {
  const PushApplied(this.changeId, this.count);

  final String changeId;
  final int count;
}

/// The server's copy of some documents moved after the compare; nothing was
/// written.
class PushConflicted extends PushResult {
  const PushConflicted(this.keys);

  final List<String> keys;
}

/// The server's validators refused documents; nothing was written.
class PushRefused extends PushResult {
  const PushRefused(this.diagnostics);

  final Map<String, List<Diagnostic>> diagnostics;
}

class WorkspaceSync {
  WorkspaceSync({required this.store, required this.connection, String Function()? newKey}) : _newKey = newKey ?? newIdempotencyKey;

  final EditorStore store;
  final ServerConnection connection;
  final String Function() _newKey;

  Future<ApiResponse> _call(ApiRequest request, String what) async {
    try {
      return await connection.request(request);
    } on NotConnected catch (offline) {
      throw SyncFailure('Could not $what: ${offline.status.message ?? 'the server is not connected'}.');
    } on TransportFailure catch (failure) {
      throw SyncFailure('Could not $what: ${failure.message}.');
    }
  }

  /// Every document the server lists.
  Future<List<CatalogEntry>> catalog() async {
    final List<CatalogEntry> entries = <CatalogEntry>[];
    String? after;
    for (int page = 0; page < 64; page++) {
      final ApiResponse response = await _call(
        ApiRequest('GET', after == null ? '/v1/catalog' : '/v1/catalog?after=${Uri.encodeQueryComponent(after)}'),
        'load the server catalog',
      );
      final Object? body = response.body;
      if (!response.ok || body is! Map<String, Object?>) throw SyncFailure('The catalog failed: ${response.errorMessage}');
      for (final Object? raw in asList(body['documents'])) {
        final CatalogEntry? entry = CatalogEntry.fromJson(raw);
        if (entry != null) entries.add(entry);
      }
      after = body['next'] as String?;
      if (after == null) break;
    }
    return entries;
  }

  /// The server's copies of [keys] (missing ones are left out).
  Future<Map<String, ServerBase>> fetch(Iterable<String> keys) async {
    final Map<String, ServerBase> copies = <String, ServerBase>{};
    for (final String key in keys) {
      final (DocumentKind, String)? parsed = parseDocumentKey(key);
      if (parsed == null) continue;
      final ApiResponse response = await _call(ApiRequest('GET', LiveSession.documentPathOf(parsed.$1, parsed.$2)), 'read ${parsed.$2}');
      if (response.status == 404) continue;
      final Object? body = response.body;
      if (!response.ok || body is! Map<String, Object?>) throw SyncFailure('Reading ${parsed.$2} failed: ${response.errorMessage}');
      copies[key] = ServerBase(revision: (body['revision']! as num).toInt(), hash: '${body['hash']}', path: '${body['path']}', source: body['source']);
    }
    return copies;
  }

  /// Compares every document of the open workspace with the server.
  Future<PushPlan> comparePush() async {
    final WorkspaceSnapshot? workspace = store.workspace;
    if (workspace == null) throw const SyncFailure('Open a workspace first.');
    final Set<String> listed = <String>{for (final CatalogEntry entry in await catalog()) entry.key};
    final Iterable<String> shared = <String>[
      for (final AnimusDocument document in workspace.documents)
        if (listed.contains(documentKey(document.kind, document.id))) documentKey(document.kind, document.id),
    ];
    return planPush(workspace.documents, await fetch(shared));
  }

  /// The server's validators on [selection]; diagnostics by key, empty when
  /// it accepts all of them.
  Future<Map<String, List<Diagnostic>>> validate(List<PushEntry> selection) async {
    if (selection.isEmpty) return const <String, List<Diagnostic>>{};
    final ApiResponse response = await _call(ApiRequest('POST', '/v1/validate', body: <String, Object?>{'documents': pushChanges(selection)}), 'validate');
    final Object? body = response.body;
    if (!response.ok || body is! Map<String, Object?>) throw SyncFailure('Validation failed: ${response.errorMessage}');
    return <String, List<Diagnostic>>{
      for (final Object? raw in asList(body['documents']))
        if (diagnosticsOf(asMap(raw)['diagnostics']).isNotEmpty) '${asMap(raw)['kind']}:${asMap(raw)['id']}': diagnosticsOf(asMap(raw)['diagnostics']),
    };
  }

  /// Sends [selection] as one change set. When it applies, the local
  /// documents take the revision the server stored, so the next compare
  /// finds them the same.
  Future<PushResult> push(List<PushEntry> selection, {String? message}) async {
    if (selection.isEmpty) throw const SyncFailure('Nothing is selected to push.');
    final String key = _newKey();
    final ApiResponse response = await _call(
      ApiRequest('POST', '/v1/changes', body: <String, Object?>{
        'idempotencyKey': key,
        'documents': pushChanges(selection),
        if (message != null && message.trim().isNotEmpty) 'message': message.trim(),
      }),
      'push',
    );
    final ChangeOutcome? outcome = ChangeOutcome.parse(response.body);
    switch (outcome) {
      case final ChangeApplied applied:
        final Map<String, Object?> stored = pushedSources(selection, applied);
        await store.commit(documents: <DocumentChange>[
          for (final PushEntry entry in selection)
            if (stored.containsKey(entry.key) && store.document(entry.document.kind, entry.document.id)?.source == entry.document.source)
              DocumentChange(entry.document.kind, entry.document.id, prettyJson(stored[entry.key])),
        ], undoable: false);
        return PushApplied(applied.changeId, applied.documents.length);
      case final ChangeConflicted conflicted:
        return PushConflicted(<String>[for (final ServerConflict conflict in conflicted.conflicts) conflict.key]);
      case final ChangeInvalid invalid:
        return PushRefused(invalid.diagnostics);
      case null:
        throw SyncFailure('The server refused the change set: ${response.errorMessage}');
    }
  }

  /// Compares the server's copies of [entries] with the open workspace.
  Future<PullPlan> comparePull(List<CatalogEntry> entries) async {
    final WorkspaceSnapshot? workspace = store.workspace;
    if (workspace == null) throw const SyncFailure('Open a workspace first.');
    return planPull(await fetch(<String>[for (final CatalogEntry entry in entries) entry.key]), workspace.documents);
  }

  /// Writes the chosen server copies into the workspace as one undo step.
  Future<int> pull(PullPlan plan, Map<String, PullChoice> choices) async {
    final List<({DocumentKind kind, String id, String source, String path})> writes = pullWrites(plan, choices);
    if (writes.isEmpty) return 0;
    await store.commit(documents: <DocumentChange>[
      for (final ({DocumentKind kind, String id, String source, String path}) write in writes)
        DocumentChange(write.kind, write.id, write.source, path: write.path),
    ]);
    return writes.length;
  }
}
