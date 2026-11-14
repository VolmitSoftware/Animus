part of 'editor_store.dart';

/// Keeping tabs in step: following another tab's saves and deletes, and
/// which tab owns the open workspace.
extension StoreTabs on EditorStore {
  /// Replaces the open workspace with the stored copy and its history.
  /// [lost] says an edit made here was dropped. When [saved] names the
  /// documents another tab's save wrote and this tab holds the generation
  /// before it, only those are read.
  Future<void> _reload(String id, {required bool lost, WorkspaceEvent? saved}) async {
    final WorkspaceSnapshot? open = _workspace;
    final List<String>? keys = saved?.keys;
    WorkspaceSnapshot? stored;
    if (open != null && keys != null && !_unsaved && saved!.generation == open.generation + 1) {
      final WorkspacePatch? patch = await repository.loadPatch(id, keys, resources: saved.resources);
      stored = patch == null || patch.generation != saved.generation ? await repository.load(id) : patch.applyTo(open, keys);
    } else {
      stored = await repository.load(id);
    }
    if (_workspace?.id != id) return;
    if (stored == null) {
      _workspaceDeletedElsewhere();
      return;
    }
    final bool hadUnsaved = _unsaved;
    _workspace = stored;
    _stored = stored;
    _resetHistory();
    await _loadHistory(id);
    _unsaved = false;
    if (lost || hadUnsaved) _problem = reloadedFromOtherTab;
    _changed();
  }

  void _workspaceDeletedElsewhere() {
    _opened(null);
    _release();
    _gone = true;
    _problem = deletedInOtherTab;
  }

  void _heard(WorkspaceEvent event) {
    final WorkspaceSnapshot? open = _workspace;
    if (open == null || open.id != event.workspaceId) {
      if (open == null && event.kind != WorkspaceEventKind.takeOver) unawaited(refreshWorkspaces());
      return;
    }
    switch (event.kind) {
      case WorkspaceEventKind.saved:
        if (event.generation <= open.generation) return;
        unawaited(_enqueue(() => _reload(open.id, lost: false, saved: event)));
      case WorkspaceEventKind.deleted:
        unawaited(_enqueue(() async {
          if (_workspace?.id != event.workspaceId) return;
          _workspaceDeletedElsewhere();
          _changed();
        }));
      case WorkspaceEventKind.takeOver:
        if (_owned) unawaited(_enqueue(() => _handOver(open.id)));
    }
  }

  /// Asks the tab that owns the open workspace to hand it to this one. That
  /// tab lets go of the lock (which this tab has been waiting for) and waits
  /// for it again itself.
  void takeOver() {
    final WorkspaceSnapshot? open = _workspace;
    if (open == null || _owned) return;
    events.announce(WorkspaceEvent(WorkspaceEventKind.takeOver, open.id));
  }

  Future<void> _handOver(String id) async {
    if (_workspace?.id != id || !_owned) return;
    _release();
    _changed();
    await _claim(id);
    _changed();
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
}
