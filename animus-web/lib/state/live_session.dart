import 'dart:async';

import '../logic/change_sets.dart';
import '../logic/gui/gui_json.dart';
import '../logic/inspect/json_outline.dart' show parseJsonPath;
import '../logic/json_merge.dart';
import '../logic/plural.dart';
import '../logic/suggest/completion_sources.dart';
import '../model/animus_document.dart';
import '../model/document_kind.dart';
import '../model/server_binding.dart';
import '../services/server/connection_status.dart';
import '../services/server/error_copy.dart';
import '../services/server/page_activity.dart';
import '../services/server/relay_limits.dart';
import '../services/server/server_connection.dart';
import '../services/server/transport.dart';
import '../services/terrain/terrain_worlds.dart';
import 'catalog_entry.dart';
import 'document_loader.dart';
import 'editor_store.dart';
import 'library.dart';
import 'live_lines.dart';
import 'save_state.dart';
import 'server_catalog.dart';
import 'session_owner.dart';
import 'workspace_repository.dart';

part 'live_changes.dart';
part 'live_loading.dart';

/// A document of the server, by kind and id.
class DocumentRef {
  const DocumentRef(this.kind, this.id);

  final DocumentKind kind;
  final String id;

  String get key => documentKey(kind, id);
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
        'expired' => 'timed out. Preview again to continue',
        'player-left' => 'ended: your player left the server',
        _ => 'ended',
      };

  /// The live bar's line about hidden live NPCs, or null when the preview hides none or has ended.
  String? get hiddenLine => running && hidden.isNotEmpty ? 'Hidden while previewing: ${hidden.join(', ')}' : null;
}

enum NoticeLevel { info, success, warning, error }

class LiveNotice {
  const LiveNotice(this.level, this.message, this.at, {this.connection = false, this.toast = true});

  final NoticeLevel level;
  final String message;
  final DateTime at;

  /// About the connection itself; dropped from the log once it is back.
  final bool connection;

  /// Also shown as a toast; false for what the save bar already says
  /// (applied, undone, a check that passed on the way to an apply).
  final bool toast;
}

/// The end of a load of many documents, kept until the next one or until
/// dismissed: how many arrived and how many could not be read.
class LoadReport {
  const LoadReport(this.loaded, this.failed, this.reason);

  final int loaded;
  final List<String> failed;
  final ErrorCopy? reason;
}

typedef LiveListener = void Function();

/// Live editing of one server workspace: the server's catalog as the
/// workspace's library, documents read on demand and in the background,
/// server validation, viewer-private previews, applying change sets with
/// revisions, conflicts and their merge, change status after a reconnect,
/// undo through the server, and following every change on the server.
class LiveSession implements ServerLibrary {
  LiveSession({
    required this.store,
    required this.connection,
    required this.serverId,
    SessionOwner? owner,
    String Function()? newKey,
    DateTime Function()? now,
    this.backgroundFill = true,
    this.typingPause = const Duration(milliseconds: 1500),
  })  : owner = owner ?? SoleOwner(),
        _newKey = newKey ?? newIdempotencyKey,
        _now = now ?? DateTime.now {
    _loader = DocumentLoader(send: _send, deliver: _deliver, quiet: _quiet, onChange: _loaderChanged);
  }

  final EditorStore store;
  final ServerConnection connection;
  final String serverId;

  /// Whether this tab runs live editing for the server; see [SessionOwner].
  final SessionOwner owner;

  /// Reads every catalog document in the background once online.
  final bool backgroundFill;

  /// Background reads wait this long after the last edit.
  final Duration typingPause;
  final String Function() _newKey;
  final DateTime Function() _now;
  final List<LiveListener> _listeners = <LiveListener>[];
  final List<StreamSubscription<Object?>> _subscriptions = <StreamSubscription<Object?>>[];
  final StreamController<LiveNotice> _notices = StreamController<LiveNotice>.broadcast(sync: true);
  final StreamController<List<DocumentRef>> _arrivals = StreamController<List<DocumentRef>>.broadcast(sync: true);
  late final DocumentLoader _loader;
  final ServerCatalog _catalog = ServerCatalog();
  int _libraryVersion = 0;
  bool _bulk = false;

  /// Every document the server lists, by kind then path.
  List<CatalogEntry> get catalog => _catalog.entries;

  @override
  bool get catalogLoaded => _catalog.loaded;

  /// Why the last catalog load failed, until one succeeds.
  ErrorCopy? get catalogProblem => _catalog.problem;

  /// Online and the catalog not read yet: the lists and counts are not
  /// known, so nothing can be called up to date.
  bool get reading => owned && online && !_catalog.loaded && _catalog.problem == null;
  final Map<String, List<Diagnostic>> serverDiagnostics = <String, List<Diagnostic>>{};

  /// Each document's JSON as the server last checked it, so an edit drops
  /// the server diagnostics at the paths it changed.
  final Map<String, Object?> _checked = <String, Object?>{};
  final Map<String, ConflictView> conflicts = <String, ConflictView>{};

  /// Documents changed here whose server version changed since they were
  /// loaded: the next apply needs a merge.
  final Set<String> stale = <String>{};
  PreviewState? preview;

  /// `GET /v1/schemas` from the server (generated schemas with its loaded
  /// modules), once connected.
  Map<String, Object?>? schemas;

  /// `GET /v1/lookups` (registries, commands, PlaceholderAPI) for
  /// completion; null until loaded or from a server without it.
  ServerLookups? lookups;

  /// What is running now, for the UI to disable buttons and say so.
  String? busy;
  final List<LiveNotice> log = <LiveNotice>[];

  /// The newest notice when the user last moved to another page.
  LiveNotice? _seen;

  /// The newest notice while it is news: from when it arrives until the
  /// user moves to another page ([markLogSeen]); the log keeps it after.
  LiveNotice? get freshNotice => log.isEmpty || identical(log.last, _seen) ? null : log.last;

  void markLogSeen() {
    if (log.isNotEmpty) _seen = log.last;
  }

  ConnectionPhase _lastPhase = ConnectionPhase.offline;

  Stream<LiveNotice> get notices => _notices.stream;

  /// Documents the server gained while paired, one list per piece of news.
  Stream<List<DocumentRef>> get arrivals => _arrivals.stream;

  void addListener(LiveListener listener) => _listeners.add(listener);

  void removeListener(LiveListener listener) => _listeners.remove(listener);

  void _changed() {
    for (final LiveListener listener in List<LiveListener>.of(_listeners)) {
      listener();
    }
  }

  /// The library rows may have moved: lists and this session's listeners.
  void _libraryChanged() {
    _libraryVersion++;
    store.libraryChanged();
    _changed();
  }

  void _notice(NoticeLevel level, String message, {bool connection = false, bool toast = true}) {
    if (_disposed) return;
    final LiveNotice notice = LiveNotice(level, message, _now(), connection: connection, toast: toast);
    log.add(notice);
    if (log.length > 30) log.removeAt(0);
    _notices.add(notice);
    _changed();
  }

  void start() {
    store.attachServer(this);
    store.addListener(_storeChanged);
    // The connection may be online already (pairing connects before the
    // workspace opens): a later loss must still be heard as one.
    _lastPhase = connection.status.phase;
    _subscriptions.add(connection.statuses.listen(_status));
    _subscriptions.add(connection.events.listen((Map<String, Object?> event) => unawaited(_event(event))));
    // A tab that opens while another holds the session has waited from the start.
    _waited = owner.waiting;
    _subscriptions.add(owner.changes.listen(_ownership));
    if (owned) _connect();
  }

  /// Only the owning tab talks to the server: it connects (or, already
  /// online, syncs); a waiting tab holds no connection or stream.
  void _connect() {
    if (connection.status.online) {
      unawaited(_online());
    } else if (connection.status.phase == ConnectionPhase.offline) {
      unawaited(connection.connect());
    }
  }

  /// The tab may edit live: it owns the session for this server.
  bool get owned => owner.owned;

  /// Another tab of this browser holds the session, so this one waits.
  bool get waiting => owner.waiting;

  /// Asks the tab that holds the session to hand it to this one.
  void takeOver() => store.takeOver();

  static const String otherTab = 'Live editing runs in another tab. Use that tab, or close it.';

  bool _waited = false;

  bool _disposed = false;

  void _ownership(bool owned) {
    if (_disposed) return;
    if (owned) {
      // Said only after waiting on another tab, not on every first start.
      if (_waited) _notice(NoticeLevel.info, 'This tab now runs live editing for this server.');
      _waited = false;
      _connect();
    } else {
      _waited = _waited || owner.waiting;
      _loader.clearBackground();
      // Handed to another tab: that tab connects. A workspace closed here
      // keeps the server's connection for the start page and imports.
      if (owner.waiting) unawaited(connection.disconnect());
      _changed();
    }
  }

  /// Stops the session: a preview it started ends (it shows nothing once the
  /// editor moved away), and another tab may take over.
  Future<void> dispose() async {
    _disposed = true;
    store.removeListener(_storeChanged);
    store.detachServer(this);
    _loader.stop();
    final Completer<void>? signal = _onlineSignal;
    _onlineSignal = null;
    signal?.complete();
    if (_bulk) PageActivity.endBulk();
    _bulk = false;
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
    await _arrivals.close();
  }

  ServerBinding? get binding {
    final ServerBinding? server = store.workspace?.server;
    return server != null && server.serverId == serverId ? server : null;
  }

  bool get online => connection.status.online;

  // ---- Library -------------------------------------------------------------

  @override
  int get libraryVersion => _libraryVersion;

  @override
  List<CatalogEntry> catalogOf(DocumentKind kind) => _catalog.ofKind(kind);

  @override
  bool isStale(String key) => stale.contains(key);

  @override
  bool isLoading(String key) => _loader.isLoading(key) || _waitingOnline.contains(key);

  @override
  List<Diagnostic> serverErrors(String key) => <Diagnostic>[
        for (final Diagnostic diagnostic in serverDiagnostics[key] ?? const <Diagnostic>[])
          if (diagnostic.severity == DiagnosticSeverity.error) diagnostic,
      ];

  @override
  Iterable<String> get attentionKeys => <String>{
        ...stale,
        for (final String key in serverDiagnostics.keys)
          if (serverErrors(key).isNotEmpty) key,
      };

  /// The multi-document load in progress (loaded n of m), or null.
  LoadProgress? get loading => _loader.progress;

  /// How the last load of many documents ended, until dismissed.
  LoadReport? loadReport;

  void dismissLoadReport() {
    loadReport = null;
    _changed();
  }

  final Set<String> _waitingOnline = <String>{};
  Completer<void>? _onlineSignal;

  Future<ApiResponse> _send(ApiRequest request) => connection.requestRetrying(request);

  bool _quiet() {
    final DateTime? edited = store.lastEditAt;
    return PageActivity.hidden || (edited != null && DateTime.now().difference(edited) < typingPause);
  }

  /// The load progress the library last heard, as (done, total, failed).
  (int, int, int)? _heardProgress;

  void _loaderChanged() {
    final LoadProgress? progress = _loader.progress;
    final bool bulk = progress != null && progress.total > 1 && progress.running;
    if (bulk != _bulk) {
      _bulk = bulk;
      bulk ? PageActivity.beginBulk() : PageActivity.endBulk();
    }
    // The loader reports every pump, also while it waits out typing; rows
    // move only when documents join, finish or fail.
    final (int, int, int)? heard = progress == null ? null : (progress.done, progress.total, progress.failed);
    if (heard == _heardProgress) return;
    _heardProgress = heard;
    _libraryChanged();
  }

  /// Keys whose loaded copy is being replaced by the server's newer one.
  final Set<String> _refreshing = <String>{};

  /// Keys whose server copy is taken whatever the draft (a recovered apply).
  final Set<String> _replacing = <String>{};
  int _refreshed = 0;

  // ---- Drafts --------------------------------------------------------------

  List<AnimusDocument>? _draftDocuments;
  ServerBinding? _draftBinding;
  List<DraftChange> _drafts = const <DraftChange>[];

  /// Every document changed here and not applied, worked out once per
  /// change of the workspace's documents or binding.
  List<DraftChange> get drafts {
    final WorkspaceSnapshot? workspace = store.workspace;
    final ServerBinding? server = binding;
    if (workspace == null || server == null) return const <DraftChange>[];
    if (identical(workspace.documents, _draftDocuments) && identical(server, _draftBinding)) return _drafts;
    final Map<String, AnimusDocument> byKey = workspace.byKey;
    final Map<String, String> moved = _movesOf(workspace.documents, server);
    final List<AnimusDocument> edited = <AnimusDocument>[];
    final Set<String> changed = <String>{};
    for (final MapEntry<String, AnimusDocument> entry in byKey.entries) {
      if (!moved.containsKey(entry.key) && _stateOfKey(entry.key, entry.value, server.bases[entry.key]) == DraftState.clean) continue;
      changed.add(entry.key);
      edited.add(entry.value);
    }
    for (final String key in server.bases.keys) {
      if (!byKey.containsKey(key)) changed.add(key);
    }
    _draftDocuments = workspace.documents;
    _draftBinding = server;
    // Only the changed documents go in: the rest are clean, and [changed] limits the deletions.
    _drafts = changed.isEmpty ? const <DraftChange>[] : pendingDrafts(edited, server, only: changed, moves: moved);
    return _drafts;
  }

  List<AnimusDocument>? _movesDocuments;
  ServerBinding? _movesBinding;
  Map<String, String> _moves = const <String, String>{};

  /// [draftMoves] for the workspace as it is, worked out once per change of
  /// its documents or binding: the sidebar asks for every document's state.
  Map<String, String> _movesOf(List<AnimusDocument> documents, ServerBinding server) {
    if (!identical(documents, _movesDocuments) || !identical(server, _movesBinding)) {
      _movesDocuments = documents;
      _movesBinding = server;
      // A move leaves its old key's base without a document; with none such, nothing moved.
      final WorkspaceSnapshot? workspace = store.workspace;
      final Map<String, AnimusDocument> byKey = workspace != null && identical(workspace.documents, documents) ? workspace.byKey : const <String, AnimusDocument>{};
      final bool gone = server.bases.keys.any((String key) => !byKey.containsKey(key));
      _moves = gone ? draftMoves(documents, server) : const <String, String>{};
    }
    return _moves;
  }

  @override
  Set<String> get movedFrom => <String>{...moves.values};

  /// NPCs moved in this browser since their server base: the new key to the
  /// key the server has them at. The next apply sends each as one move.
  Map<String, String> get moves {
    final WorkspaceSnapshot? workspace = store.workspace;
    final ServerBinding? server = binding;
    return workspace == null || server == null ? const <String, String>{} : _movesOf(workspace.documents, server);
  }

  /// Draft states by key, kept while the document and its base are the
  /// same objects: comparing a document with its base reads all of it.
  final Map<String, (AnimusDocument?, ServerBase?, DraftState)> _states = <String, (AnimusDocument?, ServerBase?, DraftState)>{};

  @override
  DraftState stateOf(DocumentKind kind, String id) {
    final String key = documentKey(kind, id);
    if (moves.containsKey(key)) return DraftState.moved;
    return _stateOfKey(key, store.document(kind, id), binding?.bases[key]);
  }

  DraftState _stateOfKey(String key, AnimusDocument? document, ServerBase? base) {
    final (AnimusDocument?, ServerBase?, DraftState)? cached = _states[key];
    if (cached != null && identical(cached.$1, document) && identical(cached.$2, base)) return cached.$3;
    final DraftState state = draftState(document, base);
    _states[key] = (document, base, state);
    return state;
  }

  /// What the save bar shows for this server workspace.
  SaveState get saveState {
    if (busy == 'Applying' || busy == 'Undoing') return const SaveState(SavePhase.applying);
    final int count = drafts.length;
    if (!online) return SaveState(SavePhase.offline, count: count);
    final _ApplyRecord? last = _lastApply;
    final bool current = last != null && last.edits == store.editCount;
    if (current && last.failure != null && count > 0) return SaveState(SavePhase.failed, count: count, reason: last.failure, retry: last.retry);
    if (count > 0) return SaveState(SavePhase.changes, count: count);
    if (current && last.failure == null && last.undo) return SaveState(SavePhase.undone, count: last.count);
    if (current && last.failure == null && undoableChange != null) return SaveState(SavePhase.applied, count: last.count);
    return const SaveState(SavePhase.upToDate);
  }

  /// The change set the save bar's Undo takes back: the last apply, while
  /// nothing was edited, undone or reloaded since and the server's history
  /// still holds it.
  String? get undoableChange {
    final _ApplyRecord? last = _lastApply;
    if (last == null || last.failure != null || last.undo || last.edits != store.editCount) return null;
    final String? id = last.changeId;
    final bool held = binding?.history.any((AppliedChange change) => change.changeId == id && !change.undone) ?? false;
    return held ? id : null;
  }

  _ApplyRecord? _lastApply;

  /// Why the last status check of an unanswered change set failed.
  ErrorCopy? pendingProblem;

  /// Keys whose answer this session is reading now; their `change` events
  /// are left to that answer.
  final Set<String> _answering = <String>{};

  /// The latest server news (null: deleted) of documents in a change set
  /// still in flight, checked once its answer lands.
  final Map<String, CatalogEntry?> _held = <String, CatalogEntry?>{};

  /// The hash each document had after this editor's last apply or undo
  /// (null after a delete). A catalog event reporting that same write is not
  /// a change from elsewhere; the first event for a key uses up its entry.
  final Map<String, String?> _written = <String, String?>{};

  /// NPCs this editor just spawned or despawned, and when: the server
  /// rewrote their files, and the catalog event that follows within
  /// [_spawnEcho] is this editor's own doing.
  final Map<String, DateTime> _spawnedHere = <String, DateTime>{};
  static const Duration _spawnEcho = Duration(seconds: 10);

  /// Whether a catalog event for [key] answers this editor's own spawn,
  /// using up the record of it either way.
  bool _ownSpawn(String key) {
    final DateTime? at = _spawnedHere.remove(key);
    return at != null && _now().difference(at) < _spawnEcho;
  }

  // ---- Connection ----------------------------------------------------------

  void _status(ConnectionStatus status) {
    final ConnectionPhase previous = _lastPhase;
    _lastPhase = status.phase;
    if (status.online && previous != ConnectionPhase.online) {
      // Failures that only said the connection was missing are moot now;
      // the line saying it was lost stays as history.
      log.removeWhere((LiveNotice notice) => notice.connection && notice.level == NoticeLevel.error);
      final Completer<void>? signal = _onlineSignal;
      _onlineSignal = null;
      signal?.complete();
      if (owned) unawaited(_online());
    } else if (!status.online && previous == ConnectionPhase.online) {
      // What the last apply did is not news after the server was away.
      _lastApply = null;
      // One notice per loss; the closing event that caused it adds none.
      _notice(status.phase == ConnectionPhase.needsPairing ? NoticeLevel.error : NoticeLevel.warning, '${status.message ?? 'Connection lost.'} Changes are kept.',
          connection: status.phase != ConnectionPhase.needsPairing);
    }
    _changed();
  }

  /// Whether a catalog load has finished since this session started; the
  /// next connect after it is a resync and says what it found.
  bool _synced = false;

  /// After every (re)connect: what happened to unanswered change sets, the
  /// whole catalog, and what changed on the server meanwhile.
  Future<void> _online() async {
    final bool resync = _synced;
    if (resync) {
      busy = 'Resyncing';
      _changed();
    }
    try {
      if (schemas == null) {
        final ApiResponse? response = await _call(const ApiRequest('GET', '/v1/schemas'), 'load the server schemas');
        final Object? body = response?.body;
        if (response != null && response.ok && body is Map<String, Object?> && body['schemas'] is Map<String, Object?>) {
          schemas = body['schemas']! as Map<String, Object?>;
        }
      }
      if (lookups == null) await _loadLookups();
      await recoverPending();
      if (!await _readCatalog() || binding == null) return;
      _synced = true;
      final ({int refreshed, int removed, int stale}) found = await _reconcile();
      if (resync) {
        _notice(found.stale > 0 ? NoticeLevel.warning : NoticeLevel.info, resyncLine(refreshed: found.refreshed + found.removed, stale: found.stale));
      }
      _startFill();
    } finally {
      if (resync) {
        busy = null;
        _changed();
      }
    }
  }

  /// Quiet: completion falls back to the bundled lists without it.
  Future<void> _loadLookups() async {
    try {
      final ApiResponse response = await connection.request(const ApiRequest('GET', '/v1/lookups'));
      if (response.ok && response.body is Map<String, Object?>) {
        lookups = ServerLookups.fromJson(response.body);
        TerrainWorlds.update(lookups!.registries[RegistryKind.world] ?? const <String>[]);
        _changed();
      }
    } on Object {
      // An older server or a dropped connection; keep the bundled lists.
    }
  }

  Future<ApiResponse?> _call(ApiRequest request, String what, {bool retry = false}) async {
    try {
      return await (retry ? connection.requestRetrying(request) : connection.request(request));
    } on NotConnected catch (offline) {
      _notice(NoticeLevel.error, 'Could not $what. ${offline.status.message ?? 'Not connected. Connect from the server page.'}', connection: true);
    } on TransportFailure catch (failure) {
      _notice(NoticeLevel.error, 'Could not $what. ${failure.message}', connection: true);
    }
    return null;
  }

  Future<T?> _run<T>(String label, Future<T?> Function() task) async {
    if (!owned) {
      _notice(NoticeLevel.warning, waiting ? otherTab : 'Live editing is still starting. Try again shortly.');
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

  /// Reload: reads every page of the catalog again and brings the workspace
  /// in line with it, whatever events were missed. A clean copy of a
  /// document the server deleted goes and one it changed is read again; a
  /// draft of either is marked changed on the server; new documents join
  /// the background fill. One log line says what it found. Nothing is said
  /// while offline: the connection's own state says that.
  Future<void> loadCatalog() async {
    if (!online || !owned) return;
    await _run('Reloading', () async {
      final bool first = !_catalog.loaded;
      final Set<String> before = _catalog.keys.toSet();
      if (!await _readCatalog() || binding == null) return null;
      final ({int refreshed, int removed, int stale}) found = await _reconcile();
      if (!first) {
        final Set<String> after = _catalog.keys.toSet();
        _notice(
          found.stale > 0 ? NoticeLevel.warning : NoticeLevel.info,
          reloadLine(added: after.difference(before).length, removed: before.difference(after).length, refreshed: found.refreshed, stale: found.stale),
        );
      }
      _startFill();
      return null;
    });
  }

  /// The catalog read running now, which a second caller joins.
  Future<bool>? _catalogRead;

  /// Reads every page of the catalog; false when the read failed.
  Future<bool> _readCatalog() {
    final Future<bool>? running = _catalogRead;
    if (running != null) return running;
    final Future<bool> read = _fetchCatalog().whenComplete(() => _catalogRead = null);
    return _catalogRead = read;
  }

  Future<bool> _fetchCatalog() async {
    List<CatalogEntry>? entries;
    ErrorCopy? problem;
    _catalog.beginLoad();
    try {
      (entries, problem) = await ServerCatalog.fetchAll(_send);
    } on NotConnected catch (offline) {
      problem = offline.status.problem ?? transportCopy('dropped');
    } on TransportFailure catch (failure) {
      problem = failure.copy;
    }
    if (entries == null) {
      _catalog.problem = problem;
      _notice(NoticeLevel.error, 'Catalog failed. ${problem?.text ?? ''}'.trim(), connection: true);
      _libraryChanged();
      return false;
    }
    _catalog.replace(entries);
    _libraryChanged();
    return true;
  }

  /// Server diagnostics for document [key], checked against its JSON now.
  void _diagnose(String key, List<Diagnostic> diagnostics) {
    serverDiagnostics[key] = diagnostics;
    final (DocumentKind, String)? parsed = parseDocumentKey(key);
    final AnimusDocument? document = parsed == null ? null : store.document(parsed.$1, parsed.$2);
    if (document == null) {
      _checked.remove(key);
    } else {
      _checked[key] = document.json;
    }
  }

  /// Drops each server diagnostic whose path the last edit changed: the
  /// server has not seen that value, so its verdict is stale.
  void _storeChanged() {
    final List<String> changed = <String>[];
    for (final String key in List<String>.of(serverDiagnostics.keys)) {
      final (DocumentKind, String)? parsed = parseDocumentKey(key);
      final AnimusDocument? document = parsed == null ? null : store.document(parsed.$1, parsed.$2);
      if (document == null) continue;
      if (!_checked.containsKey(key)) {
        // Diagnostics that came before their document: checked as it arrived.
        _checked[key] = document.json;
        continue;
      }
      final Object? before = _checked[key];
      if (identical(before, document.json)) continue;
      _checked[key] = document.json;
      final List<Diagnostic> kept = staleServerDiagnosticsDropped(serverDiagnostics[key]!, before, document.json);
      if (kept.length == serverDiagnostics[key]!.length) continue;
      changed.add(key);
      if (kept.isEmpty) {
        serverDiagnostics.remove(key);
      } else {
        serverDiagnostics[key] = kept;
      }
    }
    if (changed.isEmpty) return;
    store.libraryChanged(keys: changed);
    _changed();
  }

  Future<void> _event(Map<String, Object?> event) async {
    if (!owned) return;
    switch (event['type']) {
      case 'catalog':
        await _catalogEvent(event);
      case 'preview':
        final PreviewState? running = preview;
        if (running != null && running.id == event['previewId']) {
          preview = PreviewState(running.id, '${event['state']}', running.expiresAt, running.npcs, running.hidden);
          if (running.running && !preview!.running) _notice(NoticeLevel.info, 'The preview ${preview!.stateLine}.');
          _changed();
        }
      case 'diagnostics':
        for (final Object? raw in (event['documents'] as List<Object?>?) ?? const <Object?>[]) {
          if (raw is Map<String, Object?>) _diagnose('${raw['kind']}:${raw['id']}', diagnosticsOf(raw['diagnostics']));
        }
        _libraryChanged();
      case 'change':
        await _changeEvent(event['result']);
      // 'closing' ends the stream, and the connection status that follows
      // says why; a second notice here would repeat it.
    }
  }

  /// Whether a catalog event's [hash] for [key] is this editor's own last
  /// write, using up the record of it either way.
  bool _ownWrite(String key, String? hash) {
    if (!_written.containsKey(key)) return false;
    return _written.remove(key) == hash;
  }
}

/// [diagnostics] less those whose path holds a different value in [after]
/// than in [before]. A path that does not parse is kept.
List<Diagnostic> staleServerDiagnosticsDropped(List<Diagnostic> diagnostics, Object? before, Object? after) => <Diagnostic>[
      for (final Diagnostic diagnostic in diagnostics)
        if (_unchangedAt(diagnostic.path, before, after)) diagnostic,
    ];

bool _unchangedAt(String path, Object? before, Object? after) {
  final List<JsonStep>? steps = parseJsonPath(path);
  return steps == null || jsonEquals(readIn(before, steps), readIn(after, steps));
}
