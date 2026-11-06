import 'dart:async';

import '../logic/change_sets.dart';
import '../logic/gui/gui_json.dart';
import '../logic/json_merge.dart';
import '../model/animus_document.dart';
import '../model/document_kind.dart';
import '../model/server_binding.dart';
import '../services/server/error_copy.dart';
import '../services/server/server_connection.dart';
import '../services/server/transport.dart';
import 'editor_store.dart';
import 'session_owner.dart';
import 'workspace_repository.dart';

/// One `documentVersion` from the catalog.
class CatalogEntry {
  const CatalogEntry(this.kind, this.id, this.path, this.revision, this.hash);

  final DocumentKind kind;
  final String id;
  final String path;
  final int revision;
  final String hash;

  String get key => documentKey(kind, id);

  static CatalogEntry? fromJson(Object? json) {
    if (json is! Map<String, Object?>) return null;
    final DocumentKind? kind = DocumentKind.parse('${json['kind']}');
    if (kind == null || json['id'] is! String || json['revision'] is! num || json['hash'] is! String) return null;
    return CatalogEntry(kind, json['id']! as String, '${json['path']}', (json['revision']! as num).toInt(), json['hash']! as String);
  }
}

/// A document whose draft and the server's version both moved: the
/// three-way merge and the side chosen for each conflicting field.
class ConflictView {
  ConflictView({required this.key, required this.kind, required this.id, required this.base, required this.mine, required this.server, required this.merge});

  final String key;
  final DocumentKind kind;
  final String id;
  final ServerBase? base;

  /// The draft, or [absentValue] when it was deleted here.
  final Object? mine;

  /// The server's version now; null when the server deleted it.
  final ServerBase? server;
  final MergeResult merge;
  final Map<String, MergeSide> choices = <String, MergeSide>{};
}

class PreviewState {
  const PreviewState(this.id, this.state, this.expiresAt, this.npcs, this.hidden);

  final String id;

  /// running, ended, expired or player-left.
  final String state;
  final DateTime? expiresAt;
  final List<String> npcs;

  /// Live NPCs a candidate stands in for: hidden from the viewer, who neither sees nor hears them while it runs.
  final List<String> hidden;

  bool get running => state == 'running';

  /// The state in plain words, for the live bar.
  String get stateLine => switch (state) {
        'running' => 'running',
        'expired' => 'reached its time limit and ended. Preview again to keep looking',
        'player-left' => 'ended because your player left the server. Join again, then preview',
        _ => 'ended',
      };

  /// The live bar's line about hidden live NPCs, or null when the preview hides none or has ended.
  String? get hiddenLine => running && hidden.isNotEmpty
      ? 'Live NPC${hidden.length == 1 ? '' : 's'} hidden for you while previewing: ${hidden.join(', ')}'
      : null;
}

enum NoticeLevel { info, success, warning, error }

class LiveNotice {
  const LiveNotice(this.level, this.message, this.at);

  final NoticeLevel level;
  final String message;
  final DateTime at;
}

typedef LiveListener = void Function();

/// Live editing of one server workspace: opening documents from the
/// catalog, server validation, viewer-private previews, applying change sets
/// with revisions, conflicts and their merge, change status after a
/// reconnect, undo through the server, and refreshes from the event stream.
class LiveSession {
  LiveSession({
    required this.store,
    required this.connection,
    required this.serverId,
    SessionOwner? owner,
    String Function()? newKey,
    DateTime Function()? now,
  })  : owner = owner ?? SoleOwner(),
        _newKey = newKey ?? newIdempotencyKey,
        _now = now ?? DateTime.now;

  final EditorStore store;
  final ServerConnection connection;
  final String serverId;

  /// Whether this tab runs live editing for the server; see [SessionOwner].
  final SessionOwner owner;
  final String Function() _newKey;
  final DateTime Function() _now;
  final List<LiveListener> _listeners = <LiveListener>[];
  final List<StreamSubscription<Object?>> _subscriptions = <StreamSubscription<Object?>>[];
  final StreamController<LiveNotice> _notices = StreamController<LiveNotice>.broadcast(sync: true);

  List<CatalogEntry> catalog = const <CatalogEntry>[];
  bool catalogLoaded = false;

  /// Why the last catalog load failed, until one succeeds.
  ErrorCopy? catalogProblem;
  final Map<String, List<Diagnostic>> serverDiagnostics = <String, List<Diagnostic>>{};
  final Map<String, ConflictView> conflicts = <String, ConflictView>{};

  /// Drafts edited here whose server version changed since they were opened.
  final Set<String> stale = <String>{};
  PreviewState? preview;

  /// `GET /v1/schemas` from the server (generated schemas with its loaded
  /// modules), once connected.
  Map<String, Object?>? schemas;

  /// What is running now, for the UI to disable buttons and say so.
  String? busy;
  final List<LiveNotice> log = <LiveNotice>[];
  ConnectionPhase _lastPhase = ConnectionPhase.offline;

  Stream<LiveNotice> get notices => _notices.stream;

  void addListener(LiveListener listener) => _listeners.add(listener);

  void removeListener(LiveListener listener) => _listeners.remove(listener);

  void _changed() {
    for (final LiveListener listener in List<LiveListener>.of(_listeners)) {
      listener();
    }
  }

  void _notice(NoticeLevel level, String message) {
    final LiveNotice notice = LiveNotice(level, message, _now());
    log.add(notice);
    if (log.length > 30) log.removeAt(0);
    _notices.add(notice);
    _changed();
  }

  void start() {
    _subscriptions.add(connection.statuses.listen(_status));
    _subscriptions.add(connection.events.listen((Map<String, Object?> event) => unawaited(_event(event))));
    // A tab that opens while another holds the session has waited from the start.
    _waited = owner.waiting;
    _subscriptions.add(owner.changes.listen(_ownership));
    if (connection.status.online && owned) unawaited(_online());
  }

  /// The tab may edit live: it owns the session for this server.
  bool get owned => owner.owned;

  /// Another tab of this browser holds the session, so this one waits.
  bool get waiting => owner.waiting;

  static const String otherTab = 'Live editing for this server runs in another tab of this browser. Use that tab, or close it to edit here.';

  bool _waited = false;

  void _ownership(bool owned) {
    if (owned) {
      // Said only after waiting on another tab, not on every first start.
      if (_waited) _notice(NoticeLevel.info, 'This tab now runs live editing for this server.');
      _waited = false;
      if (connection.status.online) unawaited(_online());
    } else {
      _waited = _waited || owner.waiting;
      _changed();
    }
  }

  /// Stops the session: a preview it started ends (it shows nothing once the
  /// editor moved away), and another tab may take over.
  Future<void> dispose() async {
    for (final StreamSubscription<Object?> subscription in _subscriptions) {
      await subscription.cancel();
    }
    _subscriptions.clear();
    final PreviewState? running = preview;
    if (owned && running != null && running.running && connection.status.online) {
      try {
        await connection.request(ApiRequest('DELETE', '/v1/previews/${Uri.encodeComponent(running.id)}'));
      } on Object {
        // The server ends it at its expiry or when the stream closes.
      }
    }
    await owner.release();
    await _notices.close();
  }

  ServerBinding? get binding {
    final ServerBinding? server = store.workspace?.server;
    return server != null && server.serverId == serverId ? server : null;
  }

  bool get online => connection.status.online;

  List<DraftChange> get drafts {
    final WorkspaceSnapshot? workspace = store.workspace;
    final ServerBinding? server = binding;
    if (workspace == null || server == null) return const <DraftChange>[];
    return pendingDrafts(workspace.documents, server);
  }

  DraftState stateOf(DocumentKind kind, String id) {
    final String key = documentKey(kind, id);
    return draftState(store.document(kind, id), binding?.bases[key]);
  }

  void _status(ConnectionStatus status) {
    final ConnectionPhase previous = _lastPhase;
    _lastPhase = status.phase;
    if (status.online && previous != ConnectionPhase.online) {
      if (owned) unawaited(_online());
    } else if (!status.online && previous == ConnectionPhase.online) {
      // One notice per loss; the closing event that caused it adds none.
      _notice(status.phase == ConnectionPhase.needsPairing ? NoticeLevel.error : NoticeLevel.warning,
          '${status.message ?? 'The connection to the server was lost.'} Drafts stay in this browser.');
    }
    _changed();
  }

  /// After every (re)connect: what happened to unanswered change sets, and
  /// what changed on the server meanwhile.
  Future<void> _online() async {
    if (schemas == null) {
      final ApiResponse? response = await _call(const ApiRequest('GET', '/v1/schemas'), 'load the server schemas');
      final Object? body = response?.body;
      if (response != null && response.ok && body is Map<String, Object?> && body['schemas'] is Map<String, Object?>) {
        schemas = body['schemas']! as Map<String, Object?>;
      }
    }
    await recoverPending();
    await loadCatalog();
    final ServerBinding? server = binding;
    if (server == null) return;
    final Map<String, CatalogEntry> byKey = <String, CatalogEntry>{for (final CatalogEntry entry in catalog) entry.key: entry};
    final List<String> moved = <String>[
      for (final MapEntry<String, ServerBase> base in server.bases.entries)
        if (byKey[base.key]?.hash != base.value.hash) base.key,
    ];
    if (moved.isNotEmpty) await _absorb(moved);
  }

  Future<ApiResponse?> _call(ApiRequest request, String what) async {
    try {
      return await connection.request(request);
    } on NotConnected catch (offline) {
      _notice(NoticeLevel.error, 'Could not $what. ${offline.status.message ?? 'The server is not connected. Connect from the server page, then try again.'}');
    } on TransportFailure catch (failure) {
      _notice(NoticeLevel.error, 'Could not $what. ${failure.message}');
    }
    return null;
  }

  Future<T?> _run<T>(String label, Future<T?> Function() task) async {
    if (!owned) {
      _notice(NoticeLevel.warning, waiting ? otherTab : 'Live editing is still starting in this tab. Try again in a moment.');
      return null;
    }
    busy = label;
    _changed();
    try {
      return await task();
    } finally {
      busy = null;
      _changed();
    }
  }

  static String documentPathOf(DocumentKind kind, String id) =>
      '/v1/documents/${kind.wireName}/${id.split('/').map(Uri.encodeComponent).join('/')}';

  Future<void> loadCatalog() async {
    catalogProblem = null;
    final List<CatalogEntry> entries = <CatalogEntry>[];
    String? after;
    for (int page = 0; page < 64; page++) {
      final ApiResponse? response = await _call(
        ApiRequest('GET', after == null ? '/v1/catalog' : '/v1/catalog?after=${Uri.encodeQueryComponent(after)}'),
        'load the catalog',
      );
      if (response == null) {
        catalogProblem = connection.status.problem ?? transportCopy('dropped');
        _changed();
        return;
      }
      final Object? body = response.body;
      if (!response.ok || body is! Map<String, Object?>) {
        catalogProblem = response.problem;
        _notice(NoticeLevel.error, 'Could not load the catalog. ${response.problem.text}');
        _changed();
        return;
      }
      for (final Object? raw in (body['documents'] as List<Object?>?) ?? const <Object?>[]) {
        final CatalogEntry? entry = CatalogEntry.fromJson(raw);
        if (entry != null) entries.add(entry);
      }
      after = body['next'] as String?;
      if (after == null) break;
    }
    catalog = entries;
    catalogLoaded = true;
    _changed();
  }

  /// Fetches one document: its base, or null when the server has none.
  Future<(ServerBase?, List<Diagnostic>)?> _fetch(DocumentKind kind, String id) async {
    final ApiResponse? response = await _call(ApiRequest('GET', documentPathOf(kind, id)), 'read $id');
    if (response == null) return null;
    if (response.status == 404) return (null, const <Diagnostic>[]);
    final Object? body = response.body;
    if (!response.ok || body is! Map<String, Object?> || body['revision'] is! num) {
      _notice(NoticeLevel.error, 'Could not read ${kind.label.toLowerCase()} $id. ${response.problem.text}');
      return null;
    }
    return (
      ServerBase(revision: (body['revision']! as num).toInt(), hash: '${body['hash']}', path: '${body['path']}', source: body['source']),
      diagnosticsOf(body['diagnostics']),
    );
  }

  /// Adds documents from the catalog to the workspace. A document already
  /// here keeps its draft.
  Future<void> openDocuments(List<CatalogEntry> entries) => _run<void>('Opening', () async {
        final List<DocumentChange> changes = <DocumentChange>[];
        final Map<String, ServerBase> bases = <String, ServerBase>{};
        for (final CatalogEntry entry in entries) {
          if (store.document(entry.kind, entry.id) != null) continue;
          final (ServerBase?, List<Diagnostic>)? fetched = await _fetch(entry.kind, entry.id);
          final ServerBase? base = fetched?.$1;
          if (base == null) continue;
          bases[entry.key] = base;
          changes.add(DocumentChange(entry.kind, entry.id, prettyJson(base.source), path: base.path));
          if (fetched!.$2.isNotEmpty) serverDiagnostics[entry.key] = fetched.$2;
        }
        if (changes.isEmpty) return;
        await store.commit(
          documents: changes,
          server: (ServerBinding binding) => binding.copyWith(bases: <String, ServerBase>{...binding.bases, ...bases}),
          undoable: false,
        );
        _notice(NoticeLevel.success, 'Opened ${changes.length} document${changes.length == 1 ? '' : 's'} from the server.');
      });

  final Set<String> _skinsAsked = <String>{};

  /// Adds the skin document an opened NPC names, once per session and
  /// without a notice, so its preview can read the model and texture URL.
  /// Waits for the connection; a transport failure lets a later call retry.
  Future<void> openSkin(String hash) async {
    if (!online || store.document(DocumentKind.skin, hash) != null || !_skinsAsked.add(hash)) return;
    final (ServerBase?, List<Diagnostic>)? fetched = await _fetch(DocumentKind.skin, hash);
    if (fetched == null) _skinsAsked.remove(hash);
    final ServerBase? base = fetched?.$1;
    if (base == null || store.document(DocumentKind.skin, hash) != null) return;
    await store.commit(
      documents: <DocumentChange>[DocumentChange(DocumentKind.skin, hash, prettyJson(base.source), path: base.path)],
      server: (ServerBinding binding) => binding.copyWith(bases: <String, ServerBase>{...binding.bases, documentKey(DocumentKind.skin, hash): base}),
      undoable: false,
    );
  }

  List<Map<String, Object?>> _changes(List<DraftChange> drafts) =>
      <Map<String, Object?>>[for (final DraftChange draft in drafts) if (draft.change != null) draft.change!];

  /// Validates every changed draft (or [only]) on the server.
  Future<bool> validate({Set<String>? only}) async =>
      await _run<bool>('Validating', () async {
        final ServerBinding? server = binding;
        final WorkspaceSnapshot? workspace = store.workspace;
        if (server == null || workspace == null) return false;
        final List<DraftChange> drafts = _withFocus(pendingDrafts(workspace.documents, server, only: only), only);
        final List<Map<String, Object?>> changes = _changes(drafts);
        if (changes.isEmpty) {
          _notice(NoticeLevel.info, 'Nothing to validate.');
          return true;
        }
        final ApiResponse? response = await _call(ApiRequest('POST', '/v1/validate', body: <String, Object?>{'documents': changes}), 'validate');
        if (response == null) return false;
        final Object? body = response.body;
        if (!response.ok || body is! Map<String, Object?>) {
          _notice(NoticeLevel.error, 'Could not validate. ${response.problem.text}');
          return false;
        }
        for (final DraftChange draft in drafts) {
          serverDiagnostics.remove(draft.key);
        }
        int errors = 0;
        for (final Object? raw in (body['documents'] as List<Object?>?) ?? const <Object?>[]) {
          if (raw is! Map<String, Object?>) continue;
          final List<Diagnostic> diagnostics = diagnosticsOf(raw['diagnostics']);
          if (diagnostics.isNotEmpty) serverDiagnostics['${raw['kind']}:${raw['id']}'] = diagnostics;
          errors += diagnostics.where((Diagnostic diagnostic) => diagnostic.severity == DiagnosticSeverity.error).length;
        }
        final bool valid = body['valid'] == true;
        _notice(valid ? NoticeLevel.success : NoticeLevel.warning, validationLine(valid, changes.length, errors));
        return valid;
      }) ??
      false;

  /// The changed drafts, plus the focused documents even when unchanged (a
  /// preview of an NPC as stored is still a preview).
  List<DraftChange> _withFocus(List<DraftChange> drafts, Set<String>? only) {
    if (only == null) return drafts;
    final ServerBinding? server = binding;
    final List<DraftChange> result = List<DraftChange>.of(drafts);
    for (final String key in only) {
      if (result.any((DraftChange draft) => draft.key == key)) continue;
      final (DocumentKind, String)? parsed = parseDocumentKey(key);
      final ServerBase? base = server?.bases[key];
      final AnimusDocument? document = parsed == null ? null : store.document(parsed.$1, parsed.$2);
      if (parsed == null || base == null || document == null || document.json is! Map<String, Object?>) continue;
      result.add(DraftChange(key, parsed.$1, parsed.$2, DraftState.clean, <String, Object?>{
        'kind': parsed.$1.wireName,
        'id': parsed.$2,
        'baseRevision': base.revision,
        'baseHash': base.hash,
        'source': document.json,
      }));
    }
    return result;
  }

  /// Shows the changed drafts (and [focus]) to the token's player only, or
  /// replaces the running preview.
  Future<void> startPreview({Set<String>? focus}) => _run<void>('Previewing', () async {
        final ServerBinding? server = binding;
        final WorkspaceSnapshot? workspace = store.workspace;
        if (server == null || workspace == null) return;
        final List<DraftChange> drafts = _withFocus(pendingDrafts(workspace.documents, server), focus);
        final List<Map<String, Object?>> changes = <Map<String, Object?>>[
          for (final Map<String, Object?> change in _changes(drafts))
            if (change['delete'] != true) change,
        ];
        if (changes.isEmpty) {
          _notice(NoticeLevel.info, 'Open or change an NPC to preview it.');
          return;
        }
        final PreviewState? running = preview;
        final ApiResponse? response = await _call(
          ApiRequest('POST', '/v1/previews', body: <String, Object?>{
            if (running != null && running.running) 'previewId': running.id,
            'documents': changes,
          }),
          'start the preview',
        );
        if (response == null) return;
        final Object? body = response.body;
        if (!response.ok || body is! Map<String, Object?>) {
          final List<Diagnostic> diagnostics = _errorDiagnostics(response);
          if (diagnostics.isNotEmpty && drafts.length == 1) serverDiagnostics[drafts.first.key] = diagnostics;
          if (response.errorCode == 'not-found') preview = null;
          _notice(NoticeLevel.error, 'Could not preview. ${response.problem.text}');
          return;
        }
        preview = PreviewState(
          '${body['previewId']}',
          'running',
          DateTime.tryParse('${body['expiresAt']}'),
          <String>[for (final Object? id in (body['npcs'] as List<Object?>?) ?? const <Object?>[]) '$id'],
          <String>[for (final Object? id in (body['hidden'] as List<Object?>?) ?? const <Object?>[]) '$id'],
        );
        for (final Object? raw in (body['documents'] as List<Object?>?) ?? const <Object?>[]) {
          if (raw is Map<String, Object?>) serverDiagnostics['${raw['kind']}:${raw['id']}'] = diagnosticsOf(raw['diagnostics']);
        }
        _notice(NoticeLevel.success, 'Previewing ${preview!.npcs.length} NPC${preview!.npcs.length == 1 ? '' : 's'} for your player only.');
      });

  List<Diagnostic> _errorDiagnostics(ApiResponse response) {
    final Object? body = response.body;
    if (body is! Map<String, Object?> || body['error'] is! Map<String, Object?>) return const <Diagnostic>[];
    return diagnosticsOf((body['error']! as Map<String, Object?>)['diagnostics']);
  }

  Future<void> endPreview() => _run<void>('Ending preview', () async {
        final PreviewState? running = preview;
        if (running == null) return;
        final ApiResponse? response = await _call(ApiRequest('DELETE', '/v1/previews/${Uri.encodeComponent(running.id)}'), 'end the preview');
        if (response == null) return;
        // 404: the server already ended it (expired, or the player left).
        if (!response.ok && response.status != 404) {
          _notice(NoticeLevel.error, 'The preview is still running. ${response.problem.text}');
          return;
        }
        // The preview event may have ended it (and said so) already.
        final bool announced = !(preview?.running ?? false);
        preview = PreviewState(running.id, 'ended', running.expiresAt, running.npcs, running.hidden);
        if (!announced) _notice(NoticeLevel.info, 'The preview ended.');
      });

  /// Sends every changed draft as one change set. Nothing is written unless
  /// all of them still match their server base. True when the server applied
  /// it; the change set is only sent once its pending record is stored, so a
  /// lost answer can always be asked for again.
  Future<bool> apply({String? message}) async =>
      await _run<bool>('Applying', () async {
        final ServerBinding? server = binding;
        final WorkspaceSnapshot? workspace = store.workspace;
        if (server == null || workspace == null) return false;
        final List<DraftChange> drafts = pendingDrafts(workspace.documents, server);
        final List<DraftChange> blocked = <DraftChange>[for (final DraftChange draft in drafts) if (draft.problem != null) draft];
        if (blocked.isNotEmpty) {
          _notice(NoticeLevel.error, 'Fix ${blocked.map((DraftChange draft) => draft.id).join(', ')} first: ${blocked.first.problem}');
          return false;
        }
        if (drafts.isEmpty) {
          _notice(NoticeLevel.info, 'No changes to apply.');
          return false;
        }
        final String key = _newKey();
        final PendingChange pending = PendingChange(
          idempotencyKey: key,
          documents: <String>[for (final DraftChange draft in drafts) draft.key],
          sentAt: _now().toUtc(),
          message: message,
        );
        if (!await _recordPending(pending)) return false;
        final Map<String, ({String path, Object? source})> sent = <String, ({String path, Object? source})>{
          for (final DraftChange draft in drafts)
            if (draft.change?['source'] != null)
              draft.key: (path: store.document(draft.kind, draft.id)?.path ?? documentPath(draft.kind, draft.id), source: draft.change!['source']),
        };
        bool applied = false;
        await _settling(key, () async {
          final ApiResponse? response = await _call(
            ApiRequest('POST', '/v1/changes', body: <String, Object?>{
              'idempotencyKey': key,
              'documents': _changes(drafts),
              if (message != null && message.isNotEmpty) 'message': message,
            }),
            'apply',
          );
          if (response != null) applied = await _outcome(response, pending, sent);
        });
        return applied;
      }) ??
      false;

  /// Stores [pending] in the workspace and reports whether it is there now.
  /// The store keeps the other tab's copy when two tabs write at once; a
  /// change set whose record was lost that way is never sent.
  Future<bool> _recordPending(PendingChange pending) async {
    await store.commit(server: (ServerBinding binding) => binding.copyWith(pending: <PendingChange>[...binding.pending, pending]), undoable: false);
    if (_pendingOf(pending.idempotencyKey) != null) return true;
    _notice(NoticeLevel.error, 'Nothing was sent: this browser could not store the change record, so its answer could not be recovered. '
        'Reload this tab and try again.');
    return false;
  }

  /// Keys whose answer this session is reading now; their `change` events
  /// are left to that answer.
  final Set<String> _answering = <String>{};

  Future<void> _settling(String key, Future<void> Function() work) async {
    _answering.add(key);
    try {
      await work();
    } finally {
      _answering.remove(key);
    }
  }

  /// Settles [pending] from the server's answer. True when it applied.
  Future<bool> _outcome(ApiResponse response, PendingChange pending, Map<String, ({String path, Object? source})>? sent) async {
    final ChangeOutcome? outcome = ChangeOutcome.parse(response.body);
    final int count = switch (outcome) {
      ChangeConflicted(:final List<ServerConflict> conflicts) => conflicts.length,
      ChangeInvalid(:final Map<String, List<Diagnostic>> diagnostics) => diagnostics.length,
      _ => 0,
    };
    final String documents = '$count document${count == 1 ? '' : 's'}';
    switch (outcome) {
      case final ChangeApplied applied:
        await _applied(applied, pending, sent);
        return true;
      case final ChangeConflicted conflicted when pending.undoOf != null:
        // The documents moved on after that apply: undoing it would throw
        // the later edit away, so the server kept them. There is no draft of
        // the undo to merge; refresh the clean copies instead.
        await _dropPending(pending.idempotencyKey);
        _notice(NoticeLevel.warning, 'Undo stopped: $documents changed on the server after that apply, so nothing was written. '
            'Edit ${conflicted.conflicts.map((ServerConflict conflict) => conflict.id).join(', ')} by hand to go back.');
        await _absorb(<String>[for (final ServerConflict conflict in conflicted.conflicts) conflict.key]);
      case final ChangeConflicted conflicted:
        await _dropPending(pending.idempotencyKey);
        _conflicts(conflicted.conflicts);
        _notice(NoticeLevel.warning, 'Apply stopped: $documents changed on the server, so nothing was written. Merge each one below, then apply again.');
      case final ChangeInvalid invalid:
        await _dropPending(pending.idempotencyKey);
        serverDiagnostics.addAll(invalid.diagnostics);
        _notice(NoticeLevel.error, 'The server refused the change set because $documents have errors, so nothing was written. Fix the marked fields, then apply again.');
      case null when response.errorCode == 'counter-replayed':
        // A browser resends a request whose connection closed before the
        // answer, under the same counter. The first copy may have applied:
        // ask by the key rather than report a refusal.
        return await _check(pending) ?? false;
      case null:
        if (response.status >= 500 || response.errorCode == 'busy' || response.errorCode == 'rate-limited') {
          _notice(NoticeLevel.warning, '${response.problem.cause} The change set is kept; the editor asks what became of it on the next connect.');
          return false;
        }
        await _dropPending(pending.idempotencyKey);
        _notice(NoticeLevel.error, 'The server refused the change set. ${response.problem.text}');
    }
    return false;
  }

  Future<void> _dropPending(String key) => store.commit(
        server: (ServerBinding binding) => binding.copyWith(
          pending: <PendingChange>[for (final PendingChange change in binding.pending) if (change.idempotencyKey != key) change],
        ),
        undoable: false,
      );

  Future<void> _applied(ChangeApplied applied, PendingChange pending, Map<String, ({String path, Object? source})>? sent) async {
    if (sent == null || pending.undoOf != null) {
      // No local copy of what was written (a recovered or undone change
      // set): take the server's versions.
      await store.commit(server: (ServerBinding binding) => recordApplied(binding, applied, const <String, ({String path, Object? source})>{}, undoOf: pending.undoOf, at: _now().toUtc()), undoable: false);
      await _absorb(<String>[for (final AppliedDocument document in applied.documents) document.key], replace: true);
    } else {
      final Map<String, ({String path, Object? source})> stored = <String, ({String path, Object? source})>{};
      final List<DocumentChange> documents = <DocumentChange>[];
      for (final AppliedDocument document in applied.documents) {
        final ({String path, Object? source})? copy = sent[document.key];
        if (copy == null || document.hash == null) continue;
        final Object? source = withServerRevision(document.kind, copy.source, document.revision);
        stored[document.key] = (path: copy.path, source: source);
        final AnimusDocument? draft = store.document(document.kind, document.id);
        if (draft != null && jsonEquals(draft.json, copy.source)) documents.add(DocumentChange(document.kind, document.id, prettyJson(source)));
      }
      await store.commit(
        documents: documents,
        server: (ServerBinding binding) => recordApplied(binding, applied, stored, at: _now().toUtc()),
        undoable: false,
      );
    }
    for (final AppliedDocument document in applied.documents) {
      serverDiagnostics.remove(document.key);
      stale.remove(document.key);
      conflicts.remove(document.key);
    }
    _notice(NoticeLevel.success,
        '${pending.undoOf == null ? 'Applied' : 'Undid'} ${applied.documents.length} document${applied.documents.length == 1 ? '' : 's'} (change ${applied.changeId}).');
  }

  /// Undoes an applied change set through the server's rollback receipt.
  Future<void> undo(String changeId) => _run<void>('Undoing', () async {
        final ServerBinding? server = binding;
        if (server == null) return;
        final AppliedChange? change = server.history.where((AppliedChange entry) => entry.changeId == changeId).firstOrNull;
        final String key = _newKey();
        final PendingChange pending = PendingChange(idempotencyKey: key, documents: change?.documents ?? const <String>[], sentAt: _now().toUtc(), undoOf: changeId);
        if (!await _recordPending(pending)) return;
        await _settling(key, () async {
          final ApiResponse? response = await _call(
            ApiRequest('POST', '/v1/changes/${Uri.encodeComponent(changeId)}/undo', body: <String, Object?>{'idempotencyKey': key}),
            'undo',
          );
          if (response != null) await _outcome(response, pending, null);
        });
      });

  /// Spawns or despawns NPCs (`group/id`) on the server, one request each
  /// with the same checks as the commands; needs the `spawn` scope. Returns
  /// how many the server accepted and says which it refused.
  Future<int> spawnNpcs(List<String> ids, {required bool spawn}) async =>
      await _run<int>(spawn ? 'Spawning' : 'Despawning', () async {
        int accepted = 0;
        int sent = 0;
        final List<String> refused = <String>[];
        ErrorCopy? reason;
        for (final String id in ids) {
          final ApiResponse? response = await _call(
            ApiRequest('POST', '/v1/npcs/${Uri.encodeComponent(id)}/${spawn ? 'spawn' : 'despawn'}'),
            spawn ? 'spawn $id' : 'despawn $id',
          );
          // The connection failed: _call said so, and the rest are not sent.
          if (response == null) break;
          sent++;
          if (response.ok) {
            accepted++;
          } else {
            refused.add(id);
            reason ??= response.problem;
          }
        }
        _notice(refused.isEmpty && sent == ids.length ? NoticeLevel.success : NoticeLevel.warning,
            spawnLine(spawn: spawn, total: ids.length, sent: sent, accepted: accepted, refused: refused, reason: reason));
        return accepted;
      }) ??
      0;

  /// Why the last status check of an unanswered change set failed.
  ErrorCopy? pendingProblem;

  /// Asks the server what became of every unanswered change set.
  Future<void> recoverPending() async {
    final ServerBinding? server = binding;
    if (server == null || !owned) return;
    pendingProblem = null;
    for (final PendingChange pending in List<PendingChange>.of(server.pending)) {
      if (await _check(pending) == null) return;
    }
  }

  /// Asks the server about one unanswered change set and settles it. True
  /// when it applied; null when the connection failed on the way.
  Future<bool?> _check(PendingChange pending) async {
    final ApiResponse? response = await _call(ApiRequest('GET', '/v1/changes/${Uri.encodeComponent(pending.idempotencyKey)}'), 'check a change set');
    if (response == null) return null;
    final Object? body = response.body;
    if (!response.ok || body is! Map<String, Object?>) {
      pendingProblem = response.problem;
      _notice(NoticeLevel.warning, 'Could not check a change set sent earlier. ${response.problem.cause} Press Check again, or Stop waiting to drop it.');
      return false;
    }
    // A change event may have settled it while the status was on its way.
    if (_pendingOf(pending.idempotencyKey) == null) return false;
    switch (body['status']) {
      case 'applied' || 'conflict' || 'invalid':
        return _outcome(ApiResponse(200, body['result']), pending, null);
      case 'unknown':
        await _dropPending(pending.idempotencyKey);
        _notice(NoticeLevel.warning, 'A change set sent before the connection dropped never reached the server. Your drafts are unchanged; apply again.');
      default:
        _notice(NoticeLevel.info, 'A change set is still being written on the server; it settles when the server reports it.');
    }
    return false;
  }

  /// Asks again about every unanswered change set, from the live bar.
  Future<void> checkPending() => _run<void>('Checking', recoverPending);

  /// Gives up on unanswered change sets whose status cannot be read. The
  /// drafts stay; a later apply sends them again against the current bases.
  Future<void> forgetPending() => _run<void>('Forgetting', () async {
        await store.commit(server: (ServerBinding binding) => binding.copyWith(pending: const <PendingChange>[]), undoable: false);
        pendingProblem = null;
        _notice(NoticeLevel.info, 'Stopped waiting for the server. Your drafts are unchanged; compare them with the server before applying again.');
      });

  PendingChange? _pendingOf(String key) {
    for (final PendingChange change in binding?.pending ?? const <PendingChange>[]) {
      if (change.idempotencyKey == key) return change;
    }
    return null;
  }

  /// A `change` event for a change set whose answer was lost settles it
  /// like that answer would have.
  Future<void> _changeEvent(Object? result) async {
    if (result is! Map<String, Object?>) return;
    final Object? key = result['idempotencyKey'];
    if (key is! String || _answering.contains(key)) return;
    final PendingChange? pending = _pendingOf(key);
    if (pending == null) return;
    await _settling(key, () => _outcome(ApiResponse(200, result), pending, null));
  }

  void _conflicts(List<ServerConflict> found) {
    final ServerBinding? server = binding;
    for (final ServerConflict conflict in found) {
      final ServerBase? base = server?.bases[conflict.key];
      final AnimusDocument? draft = store.document(conflict.kind, conflict.id);
      final Object? mine = draft == null ? absentValue : draft.json;
      final ServerBase? current = conflict.current == null
          ? null
          : ServerBase(revision: conflict.current!.revision, hash: conflict.current!.hash, path: base?.path ?? draft?.path ?? documentPath(conflict.kind, conflict.id), source: conflict.current!.source);
      conflicts[conflict.key] = ConflictView(
        key: conflict.key,
        kind: conflict.kind,
        id: conflict.id,
        base: base,
        mine: mine,
        server: current,
        merge: mergeJson(base == null ? absentValue : base.source, mine, current == null ? absentValue : current.source),
      );
    }
    _changed();
  }

  /// Fetches the server's version of [keys]. A clean draft (or every draft
  /// when [replace]) takes it; an edited one is marked stale for a merge.
  Future<void> _absorb(List<String> keys, {bool replace = false}) async {
    final List<DocumentChange> documents = <DocumentChange>[];
    final Map<String, ServerBase?> bases = <String, ServerBase?>{};
    for (final String key in keys) {
      final (DocumentKind, String)? parsed = parseDocumentKey(key);
      if (parsed == null) continue;
      final (ServerBase?, List<Diagnostic>)? fetched = await _fetch(parsed.$1, parsed.$2);
      if (fetched == null) return;
      final ServerBase? current = fetched.$1;
      // Our own apply already moved the base there.
      if (current != null && binding?.bases[key]?.hash == current.hash) {
        stale.remove(key);
        continue;
      }
      final DraftState state = stateOf(parsed.$1, parsed.$2);
      if (state == DraftState.clean || replace) {
        bases[key] = current;
        documents.add(DocumentChange(parsed.$1, parsed.$2, current == null ? null : prettyJson(current.source), path: current?.path));
        stale.remove(key);
      } else if (binding?.bases[key]?.hash != current?.hash) {
        stale.add(key);
      }
    }
    if (documents.isEmpty) {
      _changed();
      return;
    }
    await store.commit(
      documents: documents,
      server: (ServerBinding binding) {
        final Map<String, ServerBase> next = Map<String, ServerBase>.of(binding.bases);
        bases.forEach((String key, ServerBase? base) => base == null ? next.remove(key) : next[key] = base);
        return binding.copyWith(bases: next);
      },
      undoable: false,
    );
    _notice(NoticeLevel.info, 'Refreshed ${documents.length} document${documents.length == 1 ? '' : 's'} changed on the server.');
  }

  /// Builds the merge view for a stale draft from the server's version now.
  Future<void> merge(String key) => _run<void>('Merging', () async {
        final (DocumentKind, String)? parsed = parseDocumentKey(key);
        if (parsed == null) return;
        final (ServerBase?, List<Diagnostic>)? fetched = await _fetch(parsed.$1, parsed.$2);
        if (fetched == null) return;
        final ServerBase? current = fetched.$1;
        _conflicts(<ServerConflict>[ServerConflict(parsed.$1, parsed.$2, current)]);
      });

  /// Writes the merge result as the draft and moves its base to the server's
  /// version, so the next apply sends it against that revision. With
  /// [dropDraft] the server's version (or its deletion) is taken whole.
  Future<void> resolve(String key, {bool dropDraft = false}) async {
    final ConflictView? view = conflicts[key];
    if (view == null) return;
    if (!owned) {
      _notice(NoticeLevel.warning, otherTab);
      return;
    }
    final ServerBase? current = view.server;
    final Object? merged = dropDraft ? (current?.source) : resolveMerge(view.merge, view.choices);
    final bool remove = merged == null || isAbsent(merged);
    await store.commit(
      documents: <DocumentChange>[
        DocumentChange(view.kind, view.id, remove ? null : prettyJson(merged), path: current?.path ?? view.base?.path),
      ],
      server: (ServerBinding binding) {
        final Map<String, ServerBase> next = Map<String, ServerBase>.of(binding.bases);
        if (current == null) {
          next.remove(key);
        } else {
          next[key] = current;
        }
        return binding.copyWith(bases: next);
      },
    );
    conflicts.remove(key);
    stale.remove(key);
    _notice(NoticeLevel.info, dropDraft
        ? 'Took the server version of ${view.id}.'
        : 'Merged ${view.id} onto server revision ${current?.revision ?? 'none'}. Apply to send it.');
  }

  void choose(String key, String path, MergeSide side) {
    conflicts[key]?.choices[path] = side;
    _changed();
  }

  Future<void> _event(Map<String, Object?> event) async {
    if (!owned) return;
    switch (event['type']) {
      case 'catalog':
        final ServerBinding? server = binding;
        if (server == null) return;
        // Documents in a change set still in flight settle with its answer.
        final Set<String> inFlight = <String>{for (final PendingChange change in server.pending) ...change.documents};
        final List<String> moved = <String>[];
        for (final Object? raw in (event['documents'] as List<Object?>?) ?? const <Object?>[]) {
          final CatalogEntry? entry = CatalogEntry.fromJson(raw);
          if (entry == null) continue;
          _catalogUpsert(entry);
          final ServerBase? base = server.bases[entry.key];
          if (base != null && base.hash != entry.hash && !inFlight.contains(entry.key)) moved.add(entry.key);
        }
        for (final Object? raw in (event['deleted'] as List<Object?>?) ?? const <Object?>[]) {
          if (raw is! Map<String, Object?>) continue;
          final String key = '${raw['kind']}:${raw['id']}';
          catalog = <CatalogEntry>[for (final CatalogEntry entry in catalog) if (entry.key != key) entry];
          if (server.bases.containsKey(key) && !inFlight.contains(key)) moved.add(key);
        }
        if (moved.isNotEmpty) await _absorb(moved);
        _changed();
      case 'preview':
        final PreviewState? running = preview;
        if (running != null && running.id == event['previewId']) {
          preview = PreviewState(running.id, '${event['state']}', running.expiresAt, running.npcs, running.hidden);
          if (running.running && !preview!.running) _notice(NoticeLevel.info, 'The preview ${preview!.stateLine}.');
          _changed();
        }
      case 'diagnostics':
        for (final Object? raw in (event['documents'] as List<Object?>?) ?? const <Object?>[]) {
          if (raw is Map<String, Object?>) serverDiagnostics['${raw['kind']}:${raw['id']}'] = diagnosticsOf(raw['diagnostics']);
        }
        _changed();
      case 'change':
        await _changeEvent(event['result']);
      // 'closing' ends the stream, and the connection status that follows
      // says why; a second notice here would repeat it.
    }
  }

  void _catalogUpsert(CatalogEntry entry) {
    final List<CatalogEntry> next = <CatalogEntry>[for (final CatalogEntry existing in catalog) if (existing.key != entry.key) existing, entry];
    next.sort((CatalogEntry a, CatalogEntry b) => a.path.compareTo(b.path));
    catalog = next;
  }
}

/// The live log line after a server validation.
String validationLine(bool valid, int documents, int errors) {
  if (valid) return 'The server accepts $documents document${documents == 1 ? '' : 's'}.';
  if (errors == 0) return 'The server did not accept the drafts but named no error. Check the server console, then validate again.';
  return 'The server found $errors error${errors == 1 ? '' : 's'}. Fix the marked fields, then validate again.';
}

/// The live log line after spawning or despawning a selection.
String spawnLine({required bool spawn, required int total, required int sent, required int accepted, required List<String> refused, ErrorCopy? reason}) {
  final String verb = spawn ? 'Spawned' : 'Despawned';
  final String npcs = 'NPC${total == 1 ? '' : 's'}';
  final List<String> parts = <String>[
    if (sent == total && refused.isEmpty) '$verb $accepted $npcs on the server.' else '$verb $accepted of $total $npcs.',
    if (refused.isNotEmpty) 'The server refused ${refused.take(3).join(', ')}${refused.length > 3 ? ' and ${refused.length - 3} more' : ''}: ${reason?.text ?? ''}',
    if (sent < total) 'The connection failed before ${total - sent} could be sent; select them again once it reconnects.',
  ];
  return parts.join(' ').trim();
}
