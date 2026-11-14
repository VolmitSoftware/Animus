part of 'editor_store.dart';

/// What Undo or Redo did: the step's [label], or, with [refused], why it put
/// nothing back.
class HistoryMove {
  const HistoryMove(this.label, {this.refused});

  final String label;

  /// Set when a document the step touched changed on the server since, in a
  /// field the step changes too; nothing was written and the step is gone.
  final String? refused;
}

/// Undo and redo, kept per workspace in memory and in the repository. A step
/// takes back only its own change: a document a server refresh changed since
/// keeps that change, and one the refresh changed in the same fields is left
/// alone.
extension StoreHistory on EditorStore {
  /// Goes back one step; null when there was nothing to undo.
  Future<HistoryMove?> undo() => _enqueue(() => _travel(_undo, HistoryStack.undo, _redo, HistoryStack.redo, 'undo'));

  /// Repeats the last undone step; null when there was nothing to redo.
  Future<HistoryMove?> redo() => _enqueue(() => _travel(_redo, HistoryStack.redo, _undo, HistoryStack.undo, 'redo'));

  Future<HistoryMove?> _travel(List<HistoryStep> from, HistoryStack fromStack, List<HistoryStep> to, HistoryStack toStack, String verb) async {
    final WorkspaceSnapshot? current = _workspace;
    if (current == null || from.isEmpty) return null;
    final HistoryStep step = from.removeLast();
    _coalesce = null;
    await _persistHistory(() => repository.popHistory(current.id, fromStack));
    final StepResult result = step.applyTo(
      current,
      (AnimusDocument like, Object? json) => validateSource(DocumentLocation(like.kind, like.id), like.path, prettyJson(json), schemas),
    );
    if (result.conflicts.isNotEmpty) {
      _changed();
      return HistoryMove(step.label, refused: "Can't $verb: ${_namesOf(result.conflicts)} changed on the server.");
    }
    final WorkspaceSnapshot after = current.next(documents: result.documents, resources: result.resources);
    final bool saved = await _save(current, after);
    if (!saved) return null;
    _editCount++;
    _lastEditAt = DateTime.now();
    final HistoryStep back = HistoryStep.between(step.label, current, after);
    to.add(back);
    if (to.length > EditorStore.undoLimit) to.removeAt(0);
    await _persistHistory(() => repository.pushHistory(current.id, toStack, back, EditorStore.undoLimit));
    return HistoryMove(step.label);
  }

  /// The documents at [keys] as the library names them.
  String _namesOf(List<String> keys) => namesLine(<String>[
        for (final String key in keys)
          if (parseDocumentKey(key) case (final DocumentKind kind, final String id)) libraryEntry(kind, id)?.name ?? displayName(kind, id, null),
      ]);

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
}
