import 'dart:typed_data';

import '../logic/data_folder.dart';
import '../logic/shared_schemas.dart';
import '../model/animus_document.dart';
import '../model/document_kind.dart';
import 'workspace_repository.dart';

typedef StoreListener = void Function();

/// Document state for the open workspace. Connection state lives elsewhere.
class EditorStore {
  EditorStore({required this.repository, required this.schemas, String Function()? newId})
      : _newId = newId ?? _timeId;

  final WorkspaceRepository repository;
  final SharedSchemas schemas;
  final String Function() _newId;
  final List<StoreListener> _listeners = <StoreListener>[];
  final List<WorkspaceSnapshot> _undo = <WorkspaceSnapshot>[];

  static const int undoLimit = 100;

  WorkspaceSnapshot? _workspace;
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

  /// Imports a picked folder or zip as a new workspace and opens it.
  Future<WorkspaceSnapshot> openFiles(String name, Map<String, Uint8List> files) async {
    final DataFolderResult result = openDataFolder(files, schemas);
    final WorkspaceSnapshot snapshot = WorkspaceSnapshot(id: _newId(), name: name, generation: 1, documents: result.documents);
    await repository.save(snapshot);
    _workspace = snapshot;
    _ignored = result.ignored;
    _undo.clear();
    _problem = null;
    _workspaces = await repository.list();
    _changed();
    return snapshot;
  }

  /// Opens a stored workspace; returns false when it does not exist.
  Future<bool> openWorkspace(String id) async {
    if (_workspace?.id == id) return true;
    final WorkspaceSnapshot? snapshot = await repository.load(id);
    _workspace = snapshot;
    _ignored = const <String>[];
    _undo.clear();
    _problem = snapshot == null ? 'Workspace $id is not in this browser.' : null;
    _changed();
    return snapshot != null;
  }

  void closeWorkspace() {
    _workspace = null;
    _undo.clear();
    _changed();
  }

  Future<void> deleteWorkspace(String id) async {
    await repository.delete(id);
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
  Future<void> editSource(DocumentKind kind, String id, String source) async {
    final WorkspaceSnapshot? current = _workspace;
    final AnimusDocument? existing = document(kind, id);
    if (current == null || existing == null || existing.source == source) return;
    final AnimusDocument edited = validateSource(DocumentLocation(kind, id), existing.path, source, schemas);
    await _save(current, current.next(documents: <AnimusDocument>[
      for (final AnimusDocument document in current.documents) identical(document, existing) ? edited : document,
    ]));
  }

  Future<void> undo() async {
    final WorkspaceSnapshot? current = _workspace;
    if (current == null || _undo.isEmpty) return;
    final WorkspaceSnapshot previous = _undo.removeLast();
    await _save(current, current.next(documents: previous.documents), recordUndo: false);
  }

  Future<void> _save(WorkspaceSnapshot current, WorkspaceSnapshot next, {bool recordUndo = true}) async {
    try {
      await repository.save(next);
    } on WorkspaceConflict catch (conflict) {
      _problem = 'Another tab changed this workspace (generation ${conflict.storedGeneration}). Reloaded its copy; your last edit was not saved.';
      _workspace = await repository.load(current.id);
      _undo.clear();
      _changed();
      return;
    }
    if (recordUndo) {
      _undo.add(current);
      if (_undo.length > undoLimit) _undo.removeAt(0);
    }
    _workspace = next;
    _problem = null;
    _changed();
  }

  static String _timeId() => 'ws-${DateTime.now().toUtc().microsecondsSinceEpoch.toRadixString(36)}';
}
