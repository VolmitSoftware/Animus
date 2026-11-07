import 'dart:async';
import 'dart:typed_data';

import '../logic/data_folder.dart';
import '../logic/gui/gui_json.dart';
import '../logic/rig_validation.dart';
import '../logic/shared_schemas.dart';
import '../model/animus_document.dart';
import '../model/document_kind.dart';
import '../model/server_binding.dart';
import 'workspace_coordination.dart';
import 'workspace_repository.dart';

typedef StoreListener = void Function();

/// One document written by [EditorStore.commit]: new text, removal when
/// [source] is null, or an [update] of an existing document.
class DocumentChange {
  const DocumentChange(this.kind, this.id, this.source, {this.path}) : update = null;

  /// An edit run on the document's JSON when the commit's turn comes, after
  /// every commit queued before it, and written back as formatted JSON. An
  /// edit worked out from what an editor shows goes this way, so it cannot
  /// overwrite a quicker edit that was still being saved. Skipped when the
  /// document is gone by then.
  const DocumentChange.update(this.kind, this.id, JsonUpdate this.update)
      : source = null,
        path = null;

  final DocumentKind kind;
  final String id;
  final String? source;
  final JsonUpdate? update;

  /// Where a new document lives; defaults to [documentPath].
  final String? path;
}

/// Where a new document of [kind] with [id] lives in the data folder.
String documentPath(DocumentKind kind, String id) {
  if (kind == DocumentKind.serverBehaviors) return DocumentKind.serverBehaviorsPath;
  final int colon = id.indexOf(':');
  final String relative = colon < 0 ? id : '${id.substring(0, colon)}/${id.substring(colon + 1)}';
  return '${kind.folder}/$relative.json';
}

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

/// What a commit did, for "Undid: ..." when no label was given.
String describeChanges(List<DocumentChange> documents, Iterable<String> resources, bool Function(DocumentChange change) exists) {
  if (documents.length == 1) {
    final DocumentChange change = documents.single;
    if (change.source == null && change.update == null) return 'delete ${change.id}';
    return exists(change) ? 'edit ${change.id}' : 'create ${change.id}';
  }
  if (documents.isEmpty) return resources.length == 1 ? 'change ${resources.single}' : 'change ${resources.length} files';
  return 'change ${documents.length} documents';
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
  static const int undoLimit = 50;

  WorkspaceSnapshot? _workspace;
  WorkspaceSnapshot? _deleted;
  Future<void> _queue = Future<void>.value();
  int _pending = 0;
  String? _coalesce;

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
  String? _problem;
  bool _unsaved = false;
  bool _gone = false;
  bool _owned = false;
  bool _heldElsewhere = false;
  String? _lock;

  WorkspaceSnapshot? get workspace => _workspace;
  List<WorkspaceSummary> get workspaces => _workspaces;

  /// False until the workspace list has loaded once.
  bool get workspacesListed => _listed;

  /// Why the workspace list could not be read, when it could not.
  String? get listProblem => _listProblem;

  /// Paths in the last opened folder that are not documents.
  List<String> get ignored => _ignored;

  /// The last load or save failure, for the shell to show.
  String? get problem => _problem;

  /// True while the open workspace holds an edit the browser refused to
  /// store; [saveAgain] retries it.
  bool get hasUnsavedEdit => _unsaved;

  /// True while a commit is queued or an edit is held only in memory: the
  /// page asks before it closes.
  bool get hasPendingWork => _pending > 0 || _unsaved;

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
    for (final StoreListener listener in List<StoreListener>.of(_listeners)) {
      listener();
    }
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

  /// A stored workspace without opening it (for export from the list).
  Future<WorkspaceSnapshot?> loadWorkspace(String id) async => _workspace?.id == id ? _workspace : repository.load(id);

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
      await repository.save(stored.next(name: trimmed));
      events.announce(WorkspaceEvent(WorkspaceEventKind.saved, id, generation: stored.generation + 1));
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

  /// Imports a picked folder or zip as a new workspace and opens it.
  Future<WorkspaceSnapshot> openFiles(String name, Map<String, Uint8List> files) async {
    final DataFolderResult result = openDataFolder(files, schemas);
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
    _workspace = snapshot;
    _ignored = result.ignored;
    _resetHistory();
    _problem = null;
    _unsaved = false;
    await _claim(snapshot.id);
    _workspaces = await repository.list();
    _listed = true;
    _changed();
    return snapshot;
  }

  /// Opens the workspace for a paired server, creating it empty the first
  /// time. Documents join it as they are opened from the server's catalog.
  Future<WorkspaceSnapshot> openServerWorkspace(String id, String name, ServerBinding binding) async {
    WorkspaceSnapshot? snapshot = _workspace?.id == id ? _workspace : await repository.load(id);
    if (snapshot == null) {
      snapshot = WorkspaceSnapshot(id: id, name: name, generation: 1, documents: const <AnimusDocument>[], server: binding);
      await repository.save(snapshot);
      events.announce(WorkspaceEvent(WorkspaceEventKind.saved, id, generation: 1));
    }
    if (_workspace?.id != id) {
      _workspace = snapshot;
      _ignored = const <String>[];
      _resetHistory();
      _unsaved = false;
      await _claim(id);
    }
    _problem = null;
    _workspaces = await repository.list();
    _listed = true;
    _changed();
    return snapshot;
  }

  /// Opens a stored workspace with its saved undo and redo steps; returns
  /// false when it does not exist.
  Future<bool> openWorkspace(String id) async {
    if (_workspace?.id == id) return true;
    final WorkspaceSnapshot? snapshot = await repository.load(id);
    _workspace = snapshot;
    _ignored = const <String>[];
    _resetHistory();
    if (snapshot != null) await _loadHistory(id);
    _unsaved = false;
    _problem = snapshot == null ? 'There is no workspace "$id" in this browser.' : null;
    if (snapshot != null) {
      await _claim(id);
    } else {
      _release();
    }
    _changed();
    return snapshot != null;
  }

  void closeWorkspace() {
    _workspace = null;
    _resetHistory();
    _unsaved = false;
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

  AnimusDocument? document(DocumentKind kind, String id) {
    for (final AnimusDocument document in _workspace?.documents ?? const <AnimusDocument>[]) {
      if (document.kind == kind && document.id == id) return document;
    }
    return null;
  }

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
  /// same [coalesce] key (typing into one field) share one undo step.
  /// [label] says what the step did for the Undo and Redo notices, such as
  /// `rename town:baker`; without one it is worked out from the changes.
  Future<void> commit({
    List<DocumentChange> documents = const <DocumentChange>[],
    Map<String, Uint8List?> resources = const <String, Uint8List?>{},
    String? coalesce,
    ServerBinding Function(ServerBinding binding)? server,
    bool undoable = true,
    String? label,
  }) {
    final List<DocumentChange> changes = List<DocumentChange>.of(documents);
    if (changes.isNotEmpty) {
      _unsettled.add(changes);
      _unsettledVersion++;
      _changed();
    }
    return _enqueue(() async {
      try {
        await _commit(changes, resources, coalesce, server, undoable, label);
      } finally {
        if (_unsettled.contains(changes)) {
          _settle(changes);
          _changed();
        }
      }
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
    final List<AnimusDocument> next = List<AnimusDocument>.of(current.documents);
    final Set<String> touched = <String>{};
    bool changed = false;
    final String description = label ??
        describeChanges(documents, resources.keys, (DocumentChange change) => next.any((AnimusDocument d) => d.kind == change.kind && d.id == change.id));
    for (final DocumentChange change in documents) {
      final int index = next.indexWhere((AnimusDocument document) => document.kind == change.kind && document.id == change.id);
      final JsonUpdate? update = change.update;
      final Object? updated = update == null || index < 0 ? null : update(next[index].json);
      if (update != null && (index < 0 || jsonEquals(updated, next[index].json))) continue;
      final String? source = update != null ? prettyJson(updated) : change.source;
      if (source == null) {
        if (index >= 0) {
          next.removeAt(index);
          changed = true;
        }
        continue;
      }
      if (index >= 0 && next[index].source == source) continue;
      final String path = index >= 0 ? next[index].path : (change.path ?? documentPath(change.kind, change.id));
      final AnimusDocument edited = validateSource(DocumentLocation(change.kind, change.id), path, source, schemas);
      if (index >= 0) {
        next[index] = edited;
      } else {
        next.add(edited);
        next.sort((AnimusDocument a, AnimusDocument b) => a.path.compareTo(b.path));
      }
      touched.add('${change.kind.wireName}:${change.id}');
      changed = true;
    }
    final Map<String, Uint8List> nextResources = Map<String, Uint8List>.of(current.resources);
    for (final MapEntry<String, Uint8List?> resource in resources.entries) {
      changed = true;
      if (resource.value == null) {
        nextResources.remove(resource.key);
      } else {
        nextResources[resource.key] = resource.value!;
      }
    }
    if (!changed && identical(binding, current.server)) return;
    final Map<String, Object?> rigs = <String, Object?>{
      for (final AnimusDocument document in next)
        if (document.kind == DocumentKind.rig) document.id: document.json,
    };
    for (int index = 0; index < next.length; index++) {
      final AnimusDocument document = next[index];
      if (document.kind != DocumentKind.animation) continue;
      final Object? json = document.json;
      final String? rig = json is Map<String, Object?> && json['rig'] is String ? json['rig']! as String : null;
      if (!touched.contains('animation:${document.id}') && !touched.contains('rig:$rig')) continue;
      final AnimusDocument fresh = validateSource(DocumentLocation(document.kind, document.id), document.path, document.source, schemas);
      next[index] = fresh.copyWith(diagnostics: <Diagnostic>[...fresh.diagnostics, ...validateAnimationStructure(fresh.json, rigs[rig])]);
    }
    if (touched.any((String key) => key.startsWith('rig:') || key.startsWith('animation:'))) {
      final Map<String, Object?> animations = <String, Object?>{
        for (final AnimusDocument document in next)
          if (document.kind == DocumentKind.animation) document.id: document.json,
      };
      for (int index = 0; index < next.length; index++) {
        final AnimusDocument document = next[index];
        if (document.kind != DocumentKind.rig) continue;
        final AnimusDocument fresh = validateSource(DocumentLocation(document.kind, document.id), document.path, document.source, schemas);
        next[index] = fresh.copyWith(diagnostics: <Diagnostic>[...fresh.diagnostics, ...validateRigAnimations(fresh.json, animations)]);
      }
    }
    final bool merge = coalesce != null && coalesce == _coalesce && _undo.isNotEmpty;
    final bool saved = await _save(
      current,
      current.next(documents: next, resources: nextResources, server: binding),
      record: undoable && !merge ? HistoryStep(description, current) : null,
      lands: documents,
    );
    if (!saved) return;
    if (changed) await _clearRedo(current.id);
    _coalesce = undoable ? coalesce : null;
  }

  /// Completes once every commit, undo and redo asked for so far has run.
  Future<void> settled() => _enqueue(() async {});

  /// Goes back one step; returns what the step did, or null when there was
  /// nothing to undo.
  Future<String?> undo() => _enqueue(() => _travel(_undo, HistoryStack.undo, _redo, HistoryStack.redo));

  /// Repeats the last undone step; returns what it did, or null.
  Future<String?> redo() => _enqueue(() => _travel(_redo, HistoryStack.redo, _undo, HistoryStack.undo));

  Future<String?> _travel(List<HistoryStep> from, HistoryStack fromStack, List<HistoryStep> to, HistoryStack toStack) async {
    final WorkspaceSnapshot? current = _workspace;
    if (current == null || from.isEmpty) return null;
    final HistoryStep step = from.removeLast();
    _coalesce = null;
    await _persistHistory(() => repository.popHistory(current.id, fromStack));
    final bool saved = await _save(current, current.next(documents: step.snapshot.documents, resources: step.snapshot.resources));
    if (!saved) return null;
    final HistoryStep back = HistoryStep(step.label, current);
    to.add(back);
    if (to.length > undoLimit) to.removeAt(0);
    await _persistHistory(() => repository.pushHistory(current.id, toStack, back, undoLimit));
    return step.label;
  }

  /// Tries again to store an edit the browser refused.
  Future<void> saveAgain() => _enqueue(() async {
    final WorkspaceSnapshot? current = _workspace;
    if (current == null || !_unsaved) return;
    await _save(current, current.next());
  });

  /// History is a convenience: a failed write to it never blocks the edit.
  Future<void> _persistHistory(Future<void> Function() write) async {
    try {
      await write();
    } on Object {
      return;
    }
  }

  Future<void> _clearRedo(String id) async {
    if (_redo.isEmpty) return;
    _redo.clear();
    await _persistHistory(() => repository.clearHistory(id, HistoryStack.redo));
  }

  void _resetHistory() {
    _undo.clear();
    _redo.clear();
    _coalesce = null;
  }

  Future<void> _loadHistory(String id) async {
    try {
      _undo.addAll(await repository.loadHistory(id, HistoryStack.undo));
      _redo.addAll(await repository.loadHistory(id, HistoryStack.redo));
    } on Object {
      _resetHistory();
    }
  }

  Future<T> _enqueue<T>(Future<T> Function() task) {
    _pending++;
    final Future<T> run = _queue.then((void _) => task());
    _queue = run.then<void>((T _) {}, onError: (Object _) {}).whenComplete(() => _pending--);
    return run;
  }

  /// Saves [next] over [current]; true when the edit stands (stored, or kept
  /// in memory after the browser refused it), false when another tab won and
  /// this tab reloaded.
  /// [lands] are the commit's changes, settled as [next] (or the reloaded
  /// copy) takes their place.
  Future<bool> _save(WorkspaceSnapshot current, WorkspaceSnapshot next, {HistoryStep? record, List<DocumentChange>? lands}) async {
    try {
      await repository.save(next);
    } on WorkspaceConflict {
      if (lands != null) _settle(lands);
      await _reload(current.id, lost: true);
      return false;
    } on Object catch (failure) {
      // The edit stays on screen at the stored generation, so the next save
      // (or Save again) writes it on top of the stored copy.
      _keep(current, next.atGeneration(current.generation), record);
      if (lands != null) _settle(lands);
      _unsaved = true;
      _problem = saveFailureMessage(failure);
      _changed();
      return true;
    }
    _keep(current, next, record);
    if (lands != null) _settle(lands);
    if (record != null) await _persistHistory(() => repository.pushHistory(current.id, HistoryStack.undo, record, undoLimit));
    _unsaved = false;
    _problem = null;
    events.announce(WorkspaceEvent(WorkspaceEventKind.saved, next.id, generation: next.generation));
    _changed();
    return true;
  }

  void _keep(WorkspaceSnapshot current, WorkspaceSnapshot next, HistoryStep? record) {
    if (record != null) {
      _undo.add(record);
      if (_undo.length > undoLimit) _undo.removeAt(0);
    }
    _workspace = next;
  }

  /// Replaces the open workspace with the stored copy and its history.
  /// [lost] says an edit made here was dropped.
  Future<void> _reload(String id, {required bool lost}) async {
    final WorkspaceSnapshot? stored = await repository.load(id);
    if (_workspace?.id != id) return;
    if (stored == null) {
      _workspaceDeletedElsewhere();
      return;
    }
    final bool hadUnsaved = _unsaved;
    _workspace = stored;
    _resetHistory();
    await _loadHistory(id);
    _unsaved = false;
    if (lost || hadUnsaved) _problem = reloadedFromOtherTab;
    _changed();
  }

  void _workspaceDeletedElsewhere() {
    _workspace = null;
    _resetHistory();
    _unsaved = false;
    _release();
    _gone = true;
    _problem = deletedInOtherTab;
  }

  void _heard(WorkspaceEvent event) {
    final WorkspaceSnapshot? open = _workspace;
    if (open == null || open.id != event.workspaceId) {
      if (open == null) unawaited(refreshWorkspaces());
      return;
    }
    switch (event.kind) {
      case WorkspaceEventKind.saved:
        if (event.generation <= open.generation) return;
        unawaited(_enqueue(() => _reload(open.id, lost: false)));
      case WorkspaceEventKind.deleted:
        unawaited(_enqueue(() async {
          if (_workspace?.id != event.workspaceId) return;
          _workspaceDeletedElsewhere();
          _changed();
        }));
    }
  }

  /// Takes the open workspace's lock, or waits for it in the background.
  Future<void> _claim(String id) async {
    final String name = workspaceLockName(id);
    if (_lock == name) return;
    _release();
    _lock = name;
    _owned = await locks.tryHold(name);
    if (_owned || _lock != name) return;
    _heldElsewhere = true;
    unawaited(locks.hold(name).then((void _) {
      if (_lock != name) {
        locks.release(name);
        return;
      }
      _owned = true;
      _heldElsewhere = false;
      _changed();
    }));
  }

  void _release() {
    final String? lock = _lock;
    _lock = null;
    _owned = false;
    _heldElsewhere = false;
    if (lock != null) locks.release(lock);
  }

  static String _timeId() => 'ws-${DateTime.now().toUtc().microsecondsSinceEpoch.toRadixString(36)}';
}
