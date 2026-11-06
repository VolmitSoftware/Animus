import 'dart:typed_data';

import '../logic/data_folder.dart';
import '../logic/rig_validation.dart';
import '../logic/shared_schemas.dart';
import '../model/animus_document.dart';
import '../model/document_kind.dart';
import '../model/server_binding.dart';
import 'workspace_repository.dart';

typedef StoreListener = void Function();

/// One document written by [EditorStore.commit]: new text, or removal when
/// [source] is null.
class DocumentChange {
  const DocumentChange(this.kind, this.id, this.source, {this.path});

  final DocumentKind kind;
  final String id;
  final String? source;

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

/// Document state for the open workspace. Connection state lives elsewhere.
class EditorStore {
  EditorStore({required this.repository, required this.schemas, String Function()? newId})
      : _newId = newId ?? _timeId;

  final WorkspaceRepository repository;
  final SharedSchemas schemas;
  final String Function() _newId;
  final List<StoreListener> _listeners = <StoreListener>[];
  final List<WorkspaceSnapshot> _undo = <WorkspaceSnapshot>[];

  /// Undo steps kept per workspace, in memory and in the repository.
  static const int undoLimit = 50;

  WorkspaceSnapshot? _workspace;
  WorkspaceSnapshot? _deleted;
  Future<void> _queue = Future<void>.value();
  String? _coalesce;
  List<WorkspaceSummary> _workspaces = const <WorkspaceSummary>[];
  List<String> _ignored = const <String>[];
  String? _problem;

  WorkspaceSnapshot? get workspace => _workspace;
  List<WorkspaceSummary> get workspaces => _workspaces;

  /// Paths in the last opened folder that are not documents.
  List<String> get ignored => _ignored;

  /// The last load or save failure, for the shell to show.
  String? get problem => _problem;
  bool get canUndo => _undo.isNotEmpty;

  void addListener(StoreListener listener) => _listeners.add(listener);

  void removeListener(StoreListener listener) => _listeners.remove(listener);

  void _changed() {
    for (final StoreListener listener in List<StoreListener>.of(_listeners)) {
      listener();
    }
  }

  Future<void> refreshWorkspaces() async {
    _workspaces = await repository.list();
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
      await _enqueue(() => _save(current!, current.next(name: trimmed), recordUndo: false));
    } else {
      final WorkspaceSnapshot? stored = await repository.load(id);
      if (stored == null) return;
      await repository.save(stored.next(name: trimmed));
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
    await refreshWorkspaces();
    return copy;
  }

  /// Puts the last deleted workspace back, with a fresh generation.
  Future<WorkspaceSnapshot?> restoreDeleted() async {
    final WorkspaceSnapshot? deleted = _deleted;
    if (deleted == null) return null;
    final WorkspaceSnapshot restored = WorkspaceSnapshot(
      id: deleted.id,
      name: deleted.name,
      generation: 1,
      documents: deleted.documents,
      resources: deleted.resources,
      server: deleted.server,
    );
    await repository.save(restored);
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
    _workspace = snapshot;
    _ignored = result.ignored;
    _undo.clear();
    _coalesce = null;
    _problem = null;
    _workspaces = await repository.list();
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
    }
    if (_workspace?.id != id) {
      _workspace = snapshot;
      _ignored = const <String>[];
      _undo.clear();
      _coalesce = null;
    }
    _problem = null;
    _workspaces = await repository.list();
    _changed();
    return snapshot;
  }

  /// Opens a stored workspace with its saved undo steps; returns false when
  /// it does not exist.
  Future<bool> openWorkspace(String id) async {
    if (_workspace?.id == id) return true;
    final WorkspaceSnapshot? snapshot = await repository.load(id);
    final List<WorkspaceSnapshot> steps = snapshot == null ? const <WorkspaceSnapshot>[] : await repository.loadUndo(id);
    _workspace = snapshot;
    _ignored = const <String>[];
    _undo
      ..clear()
      ..addAll(steps);
    _coalesce = null;
    _problem = snapshot == null ? 'Workspace $id is not in this browser.' : null;
    _changed();
    return snapshot != null;
  }

  void closeWorkspace() {
    _workspace = null;
    _undo.clear();
    _changed();
  }

  /// Deletes a workspace and its undo steps. [restoreDeleted] brings the
  /// last one back while this page is open.
  Future<void> deleteWorkspace(String id) async {
    _deleted = await loadWorkspace(id);
    await repository.delete(id);
    await repository.clearUndo(id);
    if (_workspace?.id == id) closeWorkspace();
    await refreshWorkspaces();
  }

  AnimusDocument? document(DocumentKind kind, String id) {
    for (final AnimusDocument document in _workspace?.documents ?? const <AnimusDocument>[]) {
      if (document.kind == kind && document.id == id) return document;
    }
    return null;
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
  Future<void> commit({
    List<DocumentChange> documents = const <DocumentChange>[],
    Map<String, Uint8List?> resources = const <String, Uint8List?>{},
    String? coalesce,
    ServerBinding Function(ServerBinding binding)? server,
    bool undoable = true,
  }) => _enqueue(() async {
    final WorkspaceSnapshot? current = _workspace;
    if (current == null) return;
    final ServerBinding? binding = current.server == null || server == null ? current.server : server(current.server!);
    final List<AnimusDocument> next = List<AnimusDocument>.of(current.documents);
    final Set<String> touched = <String>{};
    bool changed = false;
    for (final DocumentChange change in documents) {
      final int index = next.indexWhere((AnimusDocument document) => document.kind == change.kind && document.id == change.id);
      final String? source = change.source;
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
    final bool merge = coalesce != null && coalesce == _coalesce && _undo.isNotEmpty;
    await _save(current, current.next(documents: next, resources: nextResources, server: binding), recordUndo: undoable && !merge);
    _coalesce = undoable ? coalesce : null;
  });

  Future<void> undo() => _enqueue(() async {
    final WorkspaceSnapshot? current = _workspace;
    if (current == null || _undo.isEmpty) return;
    final WorkspaceSnapshot previous = _undo.removeLast();
    _coalesce = null;
    await _persistUndo(() => repository.popUndo(current.id));
    await _save(current, current.next(documents: previous.documents, resources: previous.resources), recordUndo: false);
  });

  /// Undo steps are a convenience: a failed write to them never blocks the
  /// edit itself.
  Future<void> _persistUndo(Future<void> Function() write) async {
    try {
      await write();
    } on Object {
      return;
    }
  }

  Future<void> _enqueue(Future<void> Function() task) {
    final Future<void> run = _queue.then((void _) => task());
    _queue = run.then<void>((void _) {}, onError: (Object _) {});
    return run;
  }

  Future<void> _save(WorkspaceSnapshot current, WorkspaceSnapshot next, {bool recordUndo = true}) async {
    try {
      await repository.save(next);
    } on WorkspaceConflict catch (conflict) {
      _problem = 'Another tab changed this workspace (generation ${conflict.storedGeneration}). Reloaded its copy; your last edit was not saved.';
      _workspace = await repository.load(current.id);
      _undo.clear();
      await _persistUndo(() => repository.clearUndo(current.id));
      _changed();
      return;
    }
    if (recordUndo) {
      _undo.add(current);
      if (_undo.length > undoLimit) _undo.removeAt(0);
      await _persistUndo(() => repository.pushUndo(current.id, current, undoLimit));
    }
    _workspace = next;
    _problem = null;
    _changed();
  }

  static String _timeId() => 'ws-${DateTime.now().toUtc().microsecondsSinceEpoch.toRadixString(36)}';
}
