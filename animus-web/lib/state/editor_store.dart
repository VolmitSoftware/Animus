import 'dart:typed_data';

import '../logic/data_folder.dart';
import '../logic/gui/gui_json.dart';
import '../logic/shared_schemas.dart';
import '../model/animus_document.dart';
import '../model/document_kind.dart';
import 'workspace_repository.dart';

typedef StoreListener = void Function();

/// Document state for the open workspace. Connection state lives elsewhere.
class EditorStore {
  EditorStore({required this.repository, required this.schemas, String Function()? newId, DateTime Function()? clock})
      : _newId = newId ?? _timeId,
        _clock = clock ?? DateTime.now;

  final WorkspaceRepository repository;
  final SharedSchemas schemas;
  final String Function() _newId;
  final DateTime Function() _clock;
  final List<StoreListener> _listeners = <StoreListener>[];
  final List<WorkspaceSnapshot> _undo = <WorkspaceSnapshot>[];

  static const int undoLimit = 100;

  /// Edits with the same coalescing key this close together share one undo
  /// step, so typing a word in a field undoes as one change.
  static const Duration coalesceWindow = Duration(milliseconds: 1200);

  String? _lastCoalesce;
  DateTime _lastEditAt = DateTime.fromMillisecondsSinceEpoch(0);

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
  Future<void> editSource(DocumentKind kind, String id, String source) => _serial(() => _editSource(kind, id, source));

  Future<void> _editSource(DocumentKind kind, String id, String source) async {
    final WorkspaceSnapshot? current = _workspace;
    final AnimusDocument? existing = document(kind, id);
    if (current == null || existing == null || existing.source == source) return;
    final AnimusDocument edited = validateSource(DocumentLocation(kind, id), existing.path, source, schemas);
    await _save(current, current.next(documents: <AnimusDocument>[
      for (final AnimusDocument document in current.documents) identical(document, existing) ? edited : document,
    ]));
  }

  /// Replaces a document's value from a visual editor: written in the
  /// editor's JSON format, revalidated and saved. Edits sharing [coalesce]
  /// within [coalesceWindow] are one undo step.
  Future<void> editJson(DocumentKind kind, String id, Object? json, {String? coalesce}) =>
      _serial(() => _editJson(kind, id, json, coalesce: coalesce));

  Future<void> _editJson(DocumentKind kind, String id, Object? json, {String? coalesce}) async {
    final WorkspaceSnapshot? current = _workspace;
    final AnimusDocument? existing = document(kind, id);
    if (current == null || existing == null) return;
    final String source = prettyJson(json);
    if (existing.source == source) return;
    final DateTime now = _clock();
    final bool merge = coalesce != null && coalesce == _lastCoalesce && now.difference(_lastEditAt) < coalesceWindow && _undo.isNotEmpty;
    _lastCoalesce = coalesce;
    _lastEditAt = now;
    final AnimusDocument edited = validateSource(DocumentLocation(kind, id), existing.path, source, schemas);
    await _save(
      current,
      current.next(documents: <AnimusDocument>[
        for (final AnimusDocument document in current.documents) identical(document, existing) ? edited : document,
      ]),
      recordUndo: !merge,
    );
  }

  /// Adds documents (new ones, or a bundle import), replacing any with the
  /// same kind and id, as one undo step. Each is validated.
  Future<void> putDocuments(List<(DocumentLocation, String, String)> incoming) => _serial(() => _putDocuments(incoming));

  Future<void> _putDocuments(List<(DocumentLocation, String, String)> incoming) async {
    final WorkspaceSnapshot? current = _workspace;
    if (current == null || incoming.isEmpty) return;
    final Map<DocumentLocation, AnimusDocument> added = <DocumentLocation, AnimusDocument>{
      for (final (DocumentLocation location, String path, String source) in incoming)
        location: validateSource(location, path, source, schemas),
    };
    final List<AnimusDocument> documents = <AnimusDocument>[
      for (final AnimusDocument document in current.documents)
        if (!added.containsKey(DocumentLocation(document.kind, document.id))) document,
      ...added.values,
    ]..sort((AnimusDocument a, AnimusDocument b) => a.path.compareTo(b.path));
    _lastCoalesce = null;
    await _save(current, current.next(documents: documents));
  }

  /// Moves a document to [newId] at [newPath] with [source] (for kinds whose
  /// JSON repeats the id), as one undo step. False when the id is taken.
  Future<bool> renameDocument(DocumentKind kind, String id, String newId, String newPath, String source) =>
      _serial(() => _renameDocument(kind, id, newId, newPath, source));

  Future<bool> _renameDocument(DocumentKind kind, String id, String newId, String newPath, String source) async {
    final WorkspaceSnapshot? current = _workspace;
    final AnimusDocument? existing = document(kind, id);
    if (current == null || existing == null || document(kind, newId) != null) return false;
    final AnimusDocument moved = validateSource(DocumentLocation(kind, newId), newPath, source, schemas);
    _lastCoalesce = null;
    await _save(current, current.next(documents: <AnimusDocument>[
      for (final AnimusDocument document in current.documents) identical(document, existing) ? moved : document,
    ]..sort((AnimusDocument a, AnimusDocument b) => a.path.compareTo(b.path))));
    return true;
  }

  /// Removes a document as one undo step.
  Future<void> removeDocument(DocumentKind kind, String id) => _serial(() => _removeDocument(kind, id));

  Future<void> _removeDocument(DocumentKind kind, String id) async {
    final WorkspaceSnapshot? current = _workspace;
    final AnimusDocument? existing = document(kind, id);
    if (current == null || existing == null) return;
    _lastCoalesce = null;
    await _save(current, current.next(documents: <AnimusDocument>[
      for (final AnimusDocument document in current.documents)
        if (!identical(document, existing)) document,
    ]));
  }

  Future<void> undo() => _serial(_undoNow);

  Future<void> _undoNow() async {
    final WorkspaceSnapshot? current = _workspace;
    if (current == null || _undo.isEmpty) return;
    final WorkspaceSnapshot previous = _undo.removeLast();
    _lastCoalesce = null;
    await _save(current, current.next(documents: previous.documents), recordUndo: false);
  }

  /// Runs edits one after another, so each saves on top of the last and two
  /// quick keystrokes never race each other into a generation conflict.
  Future<T> _serial<T>(Future<T> Function() edit) {
    final Future<T> result = _tail.then((_) => edit());
    _tail = result.then((T _) {}, onError: (Object _) {});
    return result;
  }

  Future<void> _tail = Future<void>.value();

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
