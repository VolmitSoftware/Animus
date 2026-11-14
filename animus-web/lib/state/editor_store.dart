import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import '../logic/data_folder.dart';
import '../logic/gui/gui_json.dart';
import '../logic/rig_validation.dart';
import '../logic/npc/skin_edits.dart' show skinDocumentLabel;
import '../logic/schema_upgrades.dart';
import '../logic/shared_schemas.dart';
import '../logic/skin_label.dart';
import '../model/animus_document.dart';
import '../model/display_name.dart';
import '../model/document_kind.dart';
import '../model/document_summary.dart';
import '../model/server_binding.dart';
import '../logic/plural.dart';
import 'library.dart';
import 'workspace_coordination.dart';
import 'workspace_records.dart';
import 'workspace_repository.dart';

part 'store_changes.dart';
part 'store_history.dart';
part 'store_library.dart';
part 'store_tabs.dart';

typedef StoreListener = void Function();

/// The problem shown when another tab saved first and this tab reloaded.
const String reloadedFromOtherTab = 'Another tab changed this workspace, so this tab reloaded it. Your last edit here was not kept.';

/// The problem shown when another tab deleted the open workspace.
const String deletedInOtherTab = 'Another tab deleted this workspace. That tab can restore it until it closes.';

/// The sentence for a save the browser refused: the cause and what to do.
String saveFailureMessage(Object failure) {
  if (failure is WorkspaceWriteFailure && failure.quota) {
    return 'Not saved: the browser is out of storage space for this site. Free space or export a zip, then try again.';
  }
  final String reason = failure is WorkspaceWriteFailure ? failure.reason : '$failure';
  return 'Not saved: the browser refused to write ($reason). Export a zip, then try again.';
}

/// The few words the save bar shows for a refused save.
String saveFailureReason(Object failure) =>
    failure is WorkspaceWriteFailure && failure.quota ? 'browser storage is full' : 'the browser refused to store it';

/// A picked folder or zip that holds no Animus documents; nothing was saved.
class NoDocumentsFound implements Exception {
  const NoDocumentsFound(this.message);

  /// What was picked and what to pick instead.
  final String message;

  @override
  String toString() => message;
}

/// Document state for the open workspace. Connection state lives elsewhere.
class EditorStore {
  EditorStore({
    required this.repository,
    required this.schemas,
    String Function()? newId,
    this.events = const SilentWorkspaceEvents(),
    this.locks = const UnsharedWorkspaceLocks(),
    this.persistent = true,
    this.onWorkspaceCreated,
  }) : _newId = newId ?? _timeId {
    events.listen(_heard);
  }

  final WorkspaceRepository repository;
  final SharedSchemas schemas;
  final String Function() _newId;

  /// Save and delete notices to and from the other tabs.
  final WorkspaceEvents events;

  /// The per-workspace lock behind [ownedByThisTab].
  final WorkspaceLocks locks;
  final List<StoreListener> _listeners = <StoreListener>[];
  final List<HistoryStep> _undo = <HistoryStep>[];
  final List<HistoryStep> _redo = <HistoryStep>[];

  /// False when the repository forgets everything on close (no IndexedDB),
  /// which the shell says in a banner.
  final bool persistent;

  /// Called after a workspace is created, so the browser can be asked to
  /// keep this site's storage.
  final void Function()? onWorkspaceCreated;

  /// Undo (and redo) steps kept per workspace, in memory and in the
  /// repository.
  static const int undoLimit = historyLimit;

  WorkspaceSnapshot? _workspace;

  /// The open workspace as it is stored now: what the next save writes the
  /// difference from. Behind [_workspace] while an edit is held unsaved.
  WorkspaceSnapshot? _stored;
  WorkspaceSnapshot? _deleted;
  Future<void> _queue = Future<void>.value();
  int _pending = 0;
  String? _coalesce;

  /// The last commit queued and not started yet, while later keystrokes of
  /// the same field may join it.
  _QueuedCommit? _waiting;

  /// Tells listeners of an edit whose write is still out; see [showDelay].
  Timer? _showTimer;

  /// How long an edit's write may take before listeners hear of the edit
  /// anyway. Normally they hear once, when it is stored, so the write starts
  /// before the screen redraws.
  static const Duration showDelay = Duration(milliseconds: 50);

  /// The document changes of commits not yet in [_workspace] (queued, or
  /// waiting on the browser's storage), oldest first; [shown] lays them over.
  final List<List<DocumentChange>> _unsettled = <List<DocumentChange>>[];
  int _unsettledVersion = 0;
  final Map<String, ({AnimusDocument base, int version, AnimusDocument shown})> _shownCache =
      <String, ({AnimusDocument base, int version, AnimusDocument shown})>{};
  List<WorkspaceSummary> _workspaces = const <WorkspaceSummary>[];
  bool _listed = false;
  String? _listProblem;
  List<String> _ignored = const <String>[];
  List<String> _upgraded = const <String>[];
  String? _problem;
  bool _unsaved = false;
  bool _gone = false;
  bool _owned = false;
  bool _heldElsewhere = false;
  String? _lock;

  WorkspaceSnapshot? get workspace => _workspace;
  List<WorkspaceSummary> get workspaces => _workspaces;

  final Library _library = Library();
  ServerLibrary? _server;
  int _libraryRevision = 0;
  final Map<DocumentKind, LibraryChanges> _kindChanges = <DocumentKind, LibraryChanges>{};
  final LibraryChanges _countChanges = LibraryChanges();
  Map<DocumentKind, int>? _toldCounts;
  int? _toldOwnRoutes;

  /// False until the workspace list has loaded once.
  bool get workspacesListed => _listed;

  /// Why the workspace list could not be read, when it could not.
  String? get listProblem => _listProblem;

  /// Paths in the last opened folder that are not documents.
  List<String> get ignored => _ignored;

  /// NPC and group files of the workspace opened last that were below the
  /// current schema and were upgraded as it opened, so an export or a send
  /// writes the current version.
  List<String> get upgraded => _upgraded;

  /// The last load or save failure, for the shell to show.
  String? get problem => _problem;

  /// True while the open workspace holds an edit the browser refused to
  /// store; [saveAgain] retries it.
  bool get hasUnsavedEdit => _unsaved;

  /// Why the held edit was not stored, in a few words.
  String? get unsavedReason => _unsaved ? _unsavedReason : null;
  String? _unsavedReason;

  /// True while a commit is queued or being stored.
  bool get saving => _pending > 0;

  /// Raised by every edit, undo and redo (not by server refreshes), so a
  /// later state can tell an edit happened since.
  int get editCount => _editCount;
  int _editCount = 0;

  /// When the user last edited; background reads wait a moment after it.
  DateTime? get lastEditAt => _lastEditAt;
  DateTime? _lastEditAt;

  /// True while a commit is queued or an edit is held only in memory: the
  /// page asks before it closes.
  bool get hasPendingWork => _pending > 0 || _unsaved || _codeDrafts.isNotEmpty;

  /// Code view text not yet applied to the draft, by `kind:id`, kept while
  /// the user looks at other documents of the open workspace.
  final Map<String, String> _codeDrafts = <String, String>{};

  static String _draftKey(DocumentKind kind, String id) => '${kind.wireName}:$id';

  String? codeDraft(DocumentKind kind, String id) => _codeDrafts[_draftKey(kind, id)];

  bool hasCodeDraft(DocumentKind kind, String id) => _codeDrafts.containsKey(_draftKey(kind, id));

  /// Keeps [text] as the document's unapplied Code draft, or drops it when
  /// null. Listeners hear only when a draft starts or ends, not per key.
  void setCodeDraft(DocumentKind kind, String id, String? text) {
    final String key = _draftKey(kind, id);
    final bool had = _codeDrafts.containsKey(key);
    if (text == null) {
      _codeDrafts.remove(key);
    } else {
      _codeDrafts[key] = text;
    }
    if (had != (text != null)) _changed();
  }

  /// True when this tab holds the open workspace's lock. Only the owning tab
  /// should talk to a server for it (send change sets, resume them after a
  /// reload); another tab with the same workspace open waits and takes over
  /// when the owner closes.
  bool get ownedByThisTab => _owned;

  /// True while another tab holds the open workspace's lock and this one
  /// waits for it (not merely before the first answer).
  bool get heldByAnotherTab => _heldElsewhere;

  bool get canUndo => _undo.isNotEmpty;
  bool get canRedo => _redo.isNotEmpty;

  /// What Undo and Redo would revert or repeat, such as `rename town:baker`.
  String? get undoLabel => _undo.isEmpty ? null : _undo.last.label;
  String? get redoLabel => _redo.isEmpty ? null : _redo.last.label;

  void addListener(StoreListener listener) => _listeners.add(listener);

  void removeListener(StoreListener listener) => _listeners.remove(listener);

  void _changed() {
    _showTimer?.cancel();
    _showTimer = null;
    _libraryRevision++;
    _library.sync(_workspace, _server);
    for (final StoreListener listener in List<StoreListener>.of(_listeners)) {
      listener();
    }
    _tellLibrary();
  }

  /// True once after another tab deleted the open workspace, so the app can
  /// go home.
  bool takeWorkspaceGone() {
    final bool gone = _gone;
    _gone = false;
    return gone;
  }

  void dismissProblem() {
    if (_problem == null || _unsaved) return;
    _problem = null;
    _changed();
  }

  Future<void> refreshWorkspaces() async {
    try {
      _workspaces = await repository.list();
      _listProblem = null;
    } on Object catch (failure) {
      _listProblem = 'Could not read the workspaces ($failure). Reload the page.';
    }
    _listed = true;
    _changed();
  }

  /// The workspace deleted last in this page, which [restoreDeleted] brings
  /// back.
  WorkspaceSnapshot? get deleted => _deleted;

  /// A stored workspace without opening it (for export and copies from the
  /// list), with its NPCs and groups at the current schema.
  Future<WorkspaceSnapshot?> loadWorkspace(String id) async {
    if (_workspace?.id == id) return _workspace;
    final WorkspaceSnapshot? stored = await repository.load(id);
    if (stored == null || stored.server != null) return stored;
    final (List<AnimusDocument> documents, List<String> upgraded) = _upgradedDocuments(stored.documents);
    return upgraded.isEmpty
        ? stored
        : WorkspaceSnapshot(id: stored.id, name: stored.name, generation: stored.generation, documents: documents, resources: stored.resources);
  }

  /// A new, empty local workspace, opened.
  Future<WorkspaceSnapshot> createWorkspace(String name) => openFiles(name, const <String, Uint8List>{});

  Future<void> renameWorkspace(String id, String name) async {
    final String trimmed = name.trim();
    if (trimmed.isEmpty) return;
    final WorkspaceSnapshot? current = _workspace;
    if (current?.id == id) {
      await _enqueue(() => _save(current!, current.next(name: trimmed)));
    } else {
      final WorkspaceSnapshot? stored = await repository.load(id);
      if (stored == null) return;
      await repository.save(stored.next(name: trimmed), previous: stored);
      events.announce(WorkspaceEvent(WorkspaceEventKind.saved, id, generation: stored.generation + 1, keys: const <String>[]));
    }
    await refreshWorkspaces();
  }

  /// Copies a workspace's documents and resources into a new local
  /// workspace (a copy of a server workspace is not bound to the server).
  Future<WorkspaceSnapshot?> duplicateWorkspace(String id, String name) async {
    final WorkspaceSnapshot? source = await loadWorkspace(id);
    if (source == null) return null;
    final WorkspaceSnapshot copy = WorkspaceSnapshot(
      id: _newId(),
      name: name.trim().isEmpty ? '${source.name} copy' : name.trim(),
      generation: 1,
      documents: source.documents,
      resources: source.resources,
    );
    await repository.save(copy);
    events.announce(WorkspaceEvent(WorkspaceEventKind.saved, copy.id, generation: 1));
    await refreshWorkspaces();
    return copy;
  }

  /// Puts the last deleted workspace back, with a fresh generation.
  Future<WorkspaceSnapshot?> restoreDeleted() async {
    final WorkspaceSnapshot? deleted = _deleted;
    if (deleted == null) return null;
    final WorkspaceSnapshot restored = deleted.atGeneration(1);
    await repository.save(restored);
    events.announce(WorkspaceEvent(WorkspaceEventKind.saved, restored.id, generation: 1));
    _deleted = null;
    await refreshWorkspaces();
    return restored;
  }

  /// Imports a picked folder or zip as a new workspace and opens it. With
  /// [requireDocuments], a pick without documents throws [NoDocumentsFound]
  /// and saves nothing; [zip] says how it was picked, for that message.
  Future<WorkspaceSnapshot> openFiles(String name, Map<String, Uint8List> files, {bool requireDocuments = false, bool zip = false}) async {
    final DataFolderResult result = openDataFolder(files, schemas);
    if (requireDocuments && result.documents.isEmpty) throw NoDocumentsFound(emptyPickProblem(name, files, zip: zip));
    final WorkspaceSnapshot snapshot = WorkspaceSnapshot(
      id: _newId(),
      name: name,
      generation: 1,
      documents: result.documents,
      resources: result.resources,
    );
    await repository.save(snapshot);
    events.announce(WorkspaceEvent(WorkspaceEventKind.saved, snapshot.id, generation: 1));
    onWorkspaceCreated?.call();
    _opened(snapshot);
    _ignored = result.ignored;
    _upgraded = result.upgraded;
    _problem = null;
    await _claim(snapshot.id);
    _workspaces = await repository.list();
    _listed = true;
    _changed();
    return snapshot;
  }

  /// Opens the workspace for a paired server, creating it empty the first
  /// time, with its saved undo and redo steps. Its documents are the ones
  /// read from the server so far; the library lists the whole catalog. A
  /// workspace named before the server said its name takes [name].
  Future<WorkspaceSnapshot> openServerWorkspace(String id, String name, ServerBinding binding) async {
    WorkspaceSnapshot? snapshot = _workspace?.id == id ? _workspace : await repository.load(id);
    if (snapshot == null) {
      snapshot = WorkspaceSnapshot(id: id, name: name, generation: 1, documents: const <AnimusDocument>[], server: binding);
      await repository.save(snapshot);
      events.announce(WorkspaceEvent(WorkspaceEventKind.saved, id, generation: 1));
    }
    if (_workspace?.id != id) {
      _opened(snapshot);
      _ignored = const <String>[];
      _upgraded = const <String>[];
      await _loadHistory(id);
      await _claim(id);
    }
    _problem = null;
    if (snapshot.name != name && name.trim().isNotEmpty) {
      final WorkspaceSnapshot named = snapshot;
      await _enqueue(() => _save(named, named.next(name: name)));
    }
    _workspaces = await repository.list();
    _listed = true;
    _changed();
    return _workspace ?? snapshot;
  }

  /// Opens a stored workspace with its saved undo and redo steps; returns
  /// false when it does not exist.
  Future<bool> openWorkspace(String id) async {
    if (_workspace?.id == id) return true;
    final WorkspaceSnapshot? snapshot = await repository.load(id);
    _opened(snapshot);
    _ignored = const <String>[];
    _upgraded = const <String>[];
    if (snapshot != null) await _loadHistory(id);
    _problem = snapshot == null ? 'There is no workspace "$id" in this browser.' : null;
    if (snapshot != null) {
      await _claim(id);
      await _upgrade(snapshot);
    } else {
      _release();
    }
    _changed();
    return snapshot != null;
  }

  /// Makes [snapshot], as stored, the open workspace with no history yet.
  void _opened(WorkspaceSnapshot? snapshot) {
    _workspace = snapshot;
    _stored = snapshot;
    _codeDrafts.clear();
    _resetHistory();
    _unsaved = false;
  }

  /// Upgrades a local workspace's NPCs and groups below the current schema
  /// and stores the result, outside the undo history.
  Future<void> _upgrade(WorkspaceSnapshot snapshot) async {
    if (snapshot.server != null) return;
    final (List<AnimusDocument> documents, List<String> upgraded) = _upgradedDocuments(snapshot.documents);
    if (upgraded.isEmpty) return;
    _upgraded = upgraded;
    await _save(snapshot, snapshot.next(documents: documents));
  }

  /// [documents] with each NPC and group below the current schema upgraded
  /// ([upgradeSource]) and validated again, and the paths that were.
  (List<AnimusDocument>, List<String>) _upgradedDocuments(List<AnimusDocument> documents) {
    final List<String> upgraded = <String>[];
    final List<AnimusDocument> next = <AnimusDocument>[];
    for (final AnimusDocument document in documents) {
      final SchemaUpgrade? upgrade = upgradeSource(document.kind, document.source);
      if (upgrade != null) upgraded.add(document.path);
      next.add(upgrade == null ? document : validateSource(DocumentLocation(document.kind, document.id), document.path, upgrade.source, schemas));
    }
    return (next, upgraded);
  }

  void closeWorkspace() {
    _opened(null);
    _upgraded = const <String>[];
    _release();
    _changed();
  }

  /// Deletes a workspace and its history. [restoreDeleted] brings the last
  /// one back while this page is open.
  Future<void> deleteWorkspace(String id) async {
    _deleted = await loadWorkspace(id);
    await repository.delete(id);
    await repository.clearHistory(id, HistoryStack.undo);
    await repository.clearHistory(id, HistoryStack.redo);
    events.announce(WorkspaceEvent(WorkspaceEventKind.deleted, id));
    if (_workspace?.id == id) closeWorkspace();
    await refreshWorkspaces();
  }

  AnimusDocument? document(DocumentKind kind, String id) => _workspace?.document(kind, id);

  /// The document as the editor should draw it: [document] with every
  /// commit asked for so far applied, even those still waiting their turn
  /// to be stored. Typing faster than the browser stores never shows (or
  /// lets a Code view start from) text older than the last keystroke.
  /// Removal is not shown before it lands.
  AnimusDocument? shown(DocumentKind kind, String id) {
    final AnimusDocument? stored = document(kind, id);
    if (stored == null || _unsettled.isEmpty) return stored;
    final String key = '${kind.wireName}:$id';
    final ({AnimusDocument base, int version, AnimusDocument shown})? cached = _shownCache[key];
    if (cached != null && identical(cached.base, stored) && cached.version == _unsettledVersion) return cached.shown;
    AnimusDocument current = stored;
    for (final List<DocumentChange> changes in _unsettled) {
      for (final DocumentChange change in changes) {
        if (change.kind != kind || change.id != id) continue;
        final JsonUpdate? update = change.update;
        final Object? updated = update?.call(current.json);
        if (update != null && jsonEquals(updated, current.json)) continue;
        final String? source = update != null ? prettyJson(updated) : change.source;
        if (source == null || source == current.source) continue;
        current = validateSource(DocumentLocation(kind, id), current.path, source, schemas);
      }
    }
    _shownCache[key] = (base: stored, version: _unsettledVersion, shown: current);
    return current;
  }

  void _settle(List<DocumentChange> changes) {
    if (!_unsettled.remove(changes)) return;
    _unsettledVersion++;
    if (_unsettled.isEmpty) _shownCache.clear();
  }

  /// Replaces a document's text, revalidates it and saves the workspace.
  Future<void> editSource(DocumentKind kind, String id, String source) {
    if (document(kind, id) == null) return Future<void>.value();
    return commit(documents: <DocumentChange>[DocumentChange(kind, id, source)]);
  }

  /// Writes, adds or removes documents and resources as one undo step, and
  /// revalidates them with the animations that depend on changed rigs.
  /// Commits run one at a time in call order. Successive commits with the
  /// same [coalesce] key (typing into one field) share one undo step, and
  /// those queued behind a write still out share one write.
  /// [label] says what the step did for the Undo and Redo notices, such as
  /// `rename town:baker`; without one it is worked out from the changes.
  ///
  /// [shown] has the edit at once. Listeners hear of it when it is
  /// stored, so its write starts before the screen redraws, or after
  /// [showDelay] while the write is still out.
  Future<void> commit({
    List<DocumentChange> documents = const <DocumentChange>[],
    Map<String, Uint8List?> resources = const <String, Uint8List?>{},
    String? coalesce,
    ServerBinding Function(ServerBinding binding)? server,
    bool undoable = true,
    String? label,
  }) {
    final bool joins = coalesce != null && documents.isNotEmpty && resources.isEmpty && server == null;
    final _QueuedCommit? waiting = _waiting;
    if (joins && waiting != null && waiting.coalesce == coalesce && waiting.undoable == undoable && waiting.label == label) {
      waiting.changes.addAll(documents);
      _unsettledVersion++;
      _showSoon();
      return waiting.done;
    }
    final List<DocumentChange> changes = List<DocumentChange>.of(documents);
    if (changes.isNotEmpty) {
      _unsettled.add(changes);
      _unsettledVersion++;
      _showSoon();
    }
    late final _QueuedCommit queued;
    final Future<void> done = _enqueue(() async {
      if (identical(_waiting, queued)) _waiting = null;
      try {
        await _commit(changes, resources, coalesce, server, undoable, label);
      } finally {
        if (_unsettled.contains(changes)) {
          _settle(changes);
          _changed();
        }
      }
    });
    queued = _QueuedCommit(changes, coalesce, undoable, label, done);
    if (joins) _waiting = queued;
    return done;
  }

  void _showSoon() {
    _showTimer ??= Timer(showDelay, () {
      _showTimer = null;
      if (_unsettled.isNotEmpty) _changed();
    });
  }

  Future<void> _commit(
    List<DocumentChange> documents,
    Map<String, Uint8List?> resources,
    String? coalesce,
    ServerBinding Function(ServerBinding binding)? server,
    bool undoable,
    String? label,
  ) async {
    final WorkspaceSnapshot? current = _workspace;
    if (current == null) return;
    final ServerBinding? binding = current.server == null || server == null ? current.server : server(current.server!);
    final Map<String, AnimusDocument> byKey = Map<String, AnimusDocument>.of(current.byKey);
    final Set<String> touched = <String>{};
    final Set<String> gone = <String>{};
    bool added = false;
    bool removed = false;
    final String description = label ??
        describeChanges(documents, resources.keys, (DocumentChange change) {
          if (!byKey.containsKey(documentKey(change.kind, change.id))) return null;
          return libraryEntry(change.kind, change.id)?.name ?? displayName(change.kind, change.id, null);
        });
    for (final DocumentChange change in documents) {
      final String key = documentKey(change.kind, change.id);
      final AnimusDocument? existing = byKey[key];
      final JsonUpdate? update = change.update;
      final Object? updated = update == null || existing == null ? null : update(existing.json);
      if (update != null && (existing == null || jsonEquals(updated, existing.json))) continue;
      final String? source = update != null ? prettyJson(updated) : change.source;
      if (source == null) {
        if (existing != null) {
          byKey.remove(key);
          _codeDrafts.remove(_draftKey(change.kind, change.id));
          gone.add(key);
          removed = true;
        }
        continue;
      }
      if (existing != null && existing.source == source) continue;
      final String path = existing?.path ?? change.path ?? documentPath(change.kind, change.id);
      byKey[key] = validateSource(DocumentLocation(change.kind, change.id), path, source, schemas);
      added |= existing == null;
      touched.add(key);
    }
    final bool documentsChanged = added || removed || touched.isNotEmpty;
    bool changed = documentsChanged;
    final Map<String, Uint8List> nextResources = resources.isEmpty ? current.resources : Map<String, Uint8List>.of(current.resources);
    for (final MapEntry<String, Uint8List?> resource in resources.entries) {
      changed = true;
      if (resource.value == null) {
        nextResources.remove(resource.key);
      } else {
        nextResources[resource.key] = resource.value!;
      }
    }
    if (!changed && identical(binding, current.server)) return;
    _revalidateRigs(byKey, touched);
    final List<AnimusDocument> next = !documentsChanged
        ? current.documents
        : added || removed
            ? (byKey.values.toList()..sort((AnimusDocument a, AnimusDocument b) => a.path.compareTo(b.path)))
            : byKey.values.toList();
    // Without additions or removals byKey is in the documents' order already.
    final WorkspaceSnapshot after =
        current.next(documents: next, byKey: documentsChanged && !added && !removed ? byKey : null, resources: nextResources, server: binding);
    HistoryStep? record;
    HistoryStep? widened;
    if (undoable && changed) {
      final HistoryStep step = HistoryStep.between(description, current, after);
      if (coalesce != null && coalesce == _coalesce && _undo.isNotEmpty) {
        widened = _undo.last.widen(step);
      } else {
        record = step;
      }
    }
    final bool saved = await _save(current, after, record: record, lands: documents);
    if (!saved) return;
    if (widened != null) {
      final HistoryStep top = widened;
      _undo[_undo.length - 1] = top;
      await _persistHistory(() => repository.replaceHistoryTop(current.id, HistoryStack.undo, top));
    }
    if (changed && undoable) {
      _editCount++;
      _lastEditAt = DateTime.now();
      await _clearRedo(current.id);
    }
    if (undoable) {
      _coalesce = coalesce;
    } else if (_undo.isNotEmpty && <String>{...touched, ...gone}.any(_undo.last.documents.containsKey)) {
      // A change from outside the history to a document the coalesced step
      // holds ends that step, so the next keystroke starts from what it left.
      _coalesce = null;
    }
  }

  /// Animations whose rig or own text changed, and rigs when any rig or
  /// animation changed, validated again against each other.
  void _revalidateRigs(Map<String, AnimusDocument> byKey, Set<String> touched) {
    if (!touched.any((String key) => key.startsWith('rig:') || key.startsWith('animation:'))) return;
    final Map<String, Object?> rigs = <String, Object?>{
      for (final AnimusDocument document in byKey.values)
        if (document.kind == DocumentKind.rig) document.id: document.json,
    };
    final Map<String, Object?> animations = <String, Object?>{};
    for (final MapEntry<String, AnimusDocument> entry in byKey.entries.toList()) {
      final AnimusDocument document = entry.value;
      if (document.kind != DocumentKind.animation) continue;
      final Object? json = document.json;
      final String? rig = json is Map<String, Object?> && json['rig'] is String ? json['rig']! as String : null;
      if (touched.contains(entry.key) || touched.contains('rig:$rig')) {
        final AnimusDocument fresh = validateSource(DocumentLocation(document.kind, document.id), document.path, document.source, schemas);
        byKey[entry.key] = fresh.copyWith(diagnostics: <Diagnostic>[...fresh.diagnostics, ...validateAnimationStructure(fresh.json, rigs[rig])]);
      }
      animations[document.id] = byKey[entry.key]!.json;
    }
    for (final MapEntry<String, AnimusDocument> entry in byKey.entries.toList()) {
      final AnimusDocument document = entry.value;
      if (document.kind != DocumentKind.rig) continue;
      final AnimusDocument fresh = validateSource(DocumentLocation(document.kind, document.id), document.path, document.source, schemas);
      byKey[entry.key] = fresh.copyWith(diagnostics: <Diagnostic>[...fresh.diagnostics, ...validateRigAnimations(fresh.json, animations)]);
    }
  }

  /// Completes once every commit, undo and redo asked for so far has run.
  /// Queues nothing, so keystrokes after it may still share a write.
  Future<void> settled() => _queue;

  /// Tries again to store an edit the browser refused.
  Future<void> saveAgain() => _enqueue(() async {
    final WorkspaceSnapshot? current = _workspace;
    if (current == null || !_unsaved) return;
    await _save(current, current.next());
  });

  Future<T> _enqueue<T>(Future<T> Function() task) {
    // Only the last queued commit may take more keystrokes.
    _waiting = null;
    _pending++;
    final Future<T> run = _queue.then((void _) => task());
    _queue = run.then<void>((T _) {}, onError: (Object _) {}).whenComplete(() => _pending--);
    return run;
  }

  /// Saves [next] over [current]; true when the edit stands (stored, or kept
  /// in memory after the browser refused it), false when another tab won and
  /// this tab reloaded. Only what differs from the stored copy is written.
  /// [lands] are the commit's changes, settled as [next] (or the reloaded
  /// copy) takes their place.
  Future<bool> _save(WorkspaceSnapshot current, WorkspaceSnapshot next, {HistoryStep? record, List<DocumentChange>? lands}) async {
    final WorkspaceSnapshot? stored = _stored?.id == next.id ? _stored : null;
    final Set<String>? changed = stored == null ? null : changedKeys(stored, next);
    try {
      await repository.save(next, previous: stored, changed: changed);
    } on WorkspaceConflict {
      if (lands != null) _settle(lands);
      await _reload(current.id, lost: true);
      return false;
    } on Object catch (failure) {
      // The edit stays on screen at the stored generation, so the next save
      // (or Save again) writes it on top of the stored copy.
      _keep(next.atGeneration(current.generation), record);
      if (lands != null) _settle(lands);
      _unsaved = true;
      _unsavedReason = saveFailureReason(failure);
      _problem = saveFailureMessage(failure);
      _changed();
      return true;
    }
    _keep(next, record);
    _stored = next;
    if (lands != null) _settle(lands);
    if (record != null) await _persistHistory(() => repository.pushHistory(current.id, HistoryStack.undo, record, undoLimit));
    _unsaved = false;
    _problem = null;
    events.announce(WorkspaceEvent(
      WorkspaceEventKind.saved,
      next.id,
      generation: next.generation,
      keys: changed?.toList(),
      resources: stored != null && changedResources(stored, next).isNotEmpty,
    ));
    _changed();
    return true;
  }

  void _keep(WorkspaceSnapshot next, HistoryStep? record) {
    if (record != null) {
      _undo.add(record);
      if (_undo.length > undoLimit) _undo.removeAt(0);
    }
    _workspace = next;
  }

  static String _timeId() => 'ws-${DateTime.now().toUtc().microsecondsSinceEpoch.toRadixString(36)}';
}
