/// Syncing a local workspace with a paired server once one is connected:
/// comparing documents with the server's catalog, validating a push on the
/// server, sending it as one change set, and pulling server documents in.
/// The workspace stays local; nothing is bound to the server.
library;

import 'dart:convert';

import '../logic/change_sets.dart';
import '../logic/gui/gui_json.dart';
import '../logic/offline/sync_plan.dart';
import '../model/animus_document.dart';
import '../model/document_kind.dart';
import '../model/server_binding.dart';
import '../services/server/server_connection.dart';
import '../services/server/server_profiles.dart';
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

/// The server has no record of the push: it never arrived, nothing was
/// written, and it can be sent again.
class PushLost extends PushResult {
  const PushLost();
}

/// The server is still writing the push.
class PushWaiting extends PushResult {
  const PushWaiting();
}

/// A push sent from this browser whose answer has not been read yet. Kept in
/// localStorage, so a reload or a lost answer asks the server
/// (`GET /v1/changes/{key}`) instead of sending it blind a second time.
class PendingPush {
  const PendingPush({required this.serverId, required this.idempotencyKey, required this.documents, required this.sentAt});

  final String serverId;
  final String idempotencyKey;
  final List<String> documents;
  final DateTime sentAt;

  Map<String, Object?> toJson() => <String, Object?>{
        'serverId': serverId,
        'idempotencyKey': idempotencyKey,
        'documents': documents,
        'sentAt': sentAt.toUtc().toIso8601String(),
      };

  static PendingPush? fromJson(Object? json) {
    if (json is! Map<String, Object?> || json['serverId'] is! String || json['idempotencyKey'] is! String || json['documents'] is! List<Object?>) {
      return null;
    }
    return PendingPush(
      serverId: json['serverId']! as String,
      idempotencyKey: json['idempotencyKey']! as String,
      documents: <String>[for (final Object? key in json['documents']! as List<Object?>) '$key'],
      sentAt: DateTime.tryParse('${json['sentAt']}') ?? DateTime.fromMillisecondsSinceEpoch(0, isUtc: true),
    );
  }
}

class WorkspaceSync {
  WorkspaceSync({required this.store, required this.connection, required this.serverId, required this.records, String Function()? newKey})
      : _newKey = newKey ?? newIdempotencyKey;

  final EditorStore store;
  final ServerConnection connection;
  final String serverId;

  /// Where unanswered pushes are kept (localStorage in the browser).
  final KeyValueStore records;
  final String Function() _newKey;

  static String recordKey(String workspaceId) => 'animus.push.$workspaceId';

  Future<ApiResponse> _call(ApiRequest request, String what) async {
    try {
      return await connection.request(request);
    } on NotConnected catch (offline) {
      throw SyncFailure('Could not $what. ${offline.status.message ?? 'The server is not connected. Connect it above, then try again.'}');
    } on TransportFailure catch (failure) {
      throw SyncFailure('Could not $what. ${failure.message}');
    }
  }

  /// The open workspace's unanswered push to this server, if any.
  PendingPush? get pending {
    final WorkspaceSnapshot? workspace = store.workspace;
    if (workspace == null) return null;
    final String? text = records.read(recordKey(workspace.id));
    if (text == null) return null;
    try {
      final PendingPush? record = PendingPush.fromJson(jsonDecode(text));
      return record != null && record.serverId == serverId ? record : null;
    } on FormatException {
      return null;
    }
  }

  void _clear() {
    final WorkspaceSnapshot? workspace = store.workspace;
    if (workspace != null) records.remove(recordKey(workspace.id));
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
      if (!response.ok || body is! Map<String, Object?>) throw SyncFailure('Could not load the server catalog. ${response.problem.text}');
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
      if (!response.ok || body is! Map<String, Object?> || body['revision'] is! num) {
        throw SyncFailure('Could not read ${parsed.$2} from the server. ${response.problem.text}');
      }
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
    if (!response.ok || body is! Map<String, Object?>) throw SyncFailure('Could not validate. ${response.problem.text}');
    return <String, List<Diagnostic>>{
      for (final Object? raw in asList(body['documents']))
        if (diagnosticsOf(asMap(raw)['diagnostics']).isNotEmpty) '${asMap(raw)['kind']}:${asMap(raw)['id']}': diagnosticsOf(asMap(raw)['diagnostics']),
    };
  }

  /// Sends [selection] as one change set. The push is recorded first, and
  /// the record is kept when the answer does not arrive, so [checkPending]
  /// asks the server what happened rather than a blind second send that
  /// would read as a conflict. When it applies, the local documents take the
  /// revision the server stored, so the next compare finds them the same.
  Future<PushResult> push(List<PushEntry> selection, {String? message}) async {
    if (selection.isEmpty) throw const SyncFailure('Nothing is selected to push. Tick the documents to send, then push.');
    final WorkspaceSnapshot? workspace = store.workspace;
    if (workspace == null) throw const SyncFailure('Open a workspace first.');
    if (pending != null) {
      throw const SyncFailure('An earlier push has no answer yet. Press Check the earlier push to ask the server what became of it.');
    }
    final PendingPush record = PendingPush(
      serverId: serverId,
      idempotencyKey: _newKey(),
      documents: <String>[for (final PushEntry entry in selection) entry.key],
      sentAt: DateTime.now().toUtc(),
    );
    if (!records.write(recordKey(workspace.id), jsonEncode(record.toJson()))) {
      records.remove(recordKey(workspace.id));
      throw const SyncFailure('Nothing was sent: this browser refused to store the push record, so a lost answer could not be recovered. '
          'Leave private browsing or free some site storage, then push again.');
    }
    final ApiResponse response;
    try {
      response = await connection.request(ApiRequest('POST', '/v1/changes', body: <String, Object?>{
        'idempotencyKey': record.idempotencyKey,
        'documents': pushChanges(selection),
        if (message != null && message.trim().isNotEmpty) 'message': message.trim(),
      }));
    } on NotConnected catch (offline) {
      _clear();
      throw SyncFailure('Nothing was sent. ${offline.status.message ?? 'The server is not connected. Connect it above, then try again.'}');
    } on TransportFailure catch (failure) {
      throw SyncFailure('The push went out but its answer did not arrive (${failure.copy.cause.toLowerCase().replaceAll(RegExp(r'\.$'), '')}). '
          'Press Check the earlier push once the server is connected to learn whether it applied.');
    }
    final ChangeOutcome? outcome = ChangeOutcome.parse(response.body);
    if (outcome == null && (response.status >= 500 || response.errorCode == 'busy' || response.errorCode == 'rate-limited')) {
      throw SyncFailure('${response.problem.cause} The push is kept; press Check the earlier push in a moment.');
    }
    _clear();
    return switch (outcome) {
      final ChangeApplied applied => await _applied(applied, selection: selection),
      final ChangeConflicted conflicted => PushConflicted(<String>[for (final ServerConflict conflict in conflicted.conflicts) conflict.key]),
      final ChangeInvalid invalid => PushRefused(invalid.diagnostics),
      null => throw SyncFailure('The server refused the push. ${response.problem.text}'),
    };
  }

  /// Asks the server about the unanswered push and settles it.
  Future<PushResult?> checkPending() async {
    final PendingPush? record = pending;
    if (record == null) return null;
    final ApiResponse response = await _call(ApiRequest('GET', '/v1/changes/${Uri.encodeComponent(record.idempotencyKey)}'), 'check the earlier push');
    final Object? body = response.body;
    if (!response.ok || body is! Map<String, Object?>) {
      throw SyncFailure('Could not check the earlier push. ${response.problem.text}');
    }
    switch (body['status']) {
      case 'unknown':
        _clear();
        return const PushLost();
      case 'applied' || 'conflict' || 'invalid':
        _clear();
        return switch (ChangeOutcome.parse(body['result'])) {
          final ChangeApplied applied => await _applied(applied),
          final ChangeConflicted conflicted => PushConflicted(<String>[for (final ServerConflict conflict in conflicted.conflicts) conflict.key]),
          final ChangeInvalid invalid => PushRefused(invalid.diagnostics),
          null => const PushLost(),
        };
      default:
        return const PushWaiting();
    }
  }

  /// Gives the pushed local documents the stored revision. With [selection]
  /// the sent sources are known; after a reload they are matched against the
  /// server's copies instead, so an edit made since is never overwritten.
  Future<PushApplied> _applied(ChangeApplied applied, {List<PushEntry>? selection}) async {
    final List<DocumentChange> changes = <DocumentChange>[];
    if (selection != null) {
      final Map<String, Object?> stored = pushedSources(selection, applied);
      for (final PushEntry entry in selection) {
        if (stored.containsKey(entry.key) && store.document(entry.document.kind, entry.document.id)?.source == entry.document.source) {
          changes.add(DocumentChange(entry.document.kind, entry.document.id, prettyJson(stored[entry.key])));
        }
      }
    } else {
      final Map<String, ServerBase> copies = await fetch(<String>[for (final AppliedDocument document in applied.documents) document.key]);
      for (final AppliedDocument document in applied.documents) {
        final AnimusDocument? local = store.document(document.kind, document.id);
        final ServerBase? copy = copies[document.key];
        if (local == null || copy == null) continue;
        final Object? revised = withServerRevision(document.kind, local.json, document.revision);
        if (jsonEquals(revised, copy.source) && !jsonEquals(revised, local.json)) {
          changes.add(DocumentChange(document.kind, document.id, prettyJson(revised)));
        }
      }
    }
    if (changes.isNotEmpty) await store.commit(documents: changes, undoable: false);
    return PushApplied(applied.changeId, applied.documents.length);
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
