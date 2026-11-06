import 'dart:async';

import 'editor_store.dart';

/// Which tab of this browser runs live editing for one server.
///
/// Two tabs on the same server workspace would both absorb server changes and
/// record applies into the one stored binding, and the later write would
/// throw the other away. Only the owning tab's [LiveSession] reads events,
/// refreshes drafts and sends change sets; the others wait and say so.
abstract class SessionOwner {
  bool get owned;

  /// Known to be held by another tab (not merely not granted yet).
  bool get waiting;

  /// Emits the new value whenever [owned] changes.
  Stream<bool> get changes;

  /// Gives up ownership, so a waiting tab takes over.
  Future<void> release();
}

/// Ownership that follows the store's workspace lock: a server has one
/// workspace, so the tab that owns it ([EditorStore.ownedByThisTab]) runs
/// live editing, and a waiting tab takes over when the owner closes it or
/// switches away. The store holds and releases the lock itself.
class StoreOwner implements SessionOwner {
  StoreOwner(this._store)
      : _owned = _store.ownedByThisTab,
        _waiting = _store.heldByAnotherTab {
    _store.addListener(_heard);
  }

  final EditorStore _store;
  final StreamController<bool> _changes = StreamController<bool>.broadcast(sync: true);
  bool _owned;
  bool _waiting;

  /// Emits [owned] whenever ownership or waiting changes, as the session
  /// expects from any owner.
  void _heard() {
    final bool owned = _store.ownedByThisTab, waiting = _store.heldByAnotherTab;
    if (owned == _owned && waiting == _waiting) return;
    _owned = owned;
    _waiting = waiting;
    if (!_changes.isClosed) _changes.add(owned);
  }

  @override
  bool get owned => _store.ownedByThisTab;

  @override
  bool get waiting => _store.heldByAnotherTab;

  @override
  Stream<bool> get changes => _changes.stream;

  @override
  Future<void> release() async {
    _store.removeListener(_heard);
    await _changes.close();
  }
}

/// Ownership without contention: tests, and browsers with no Web Locks.
class SoleOwner implements SessionOwner {
  SoleOwner();

  @override
  bool get owned => true;

  @override
  bool get waiting => false;

  @override
  Stream<bool> get changes => const Stream<bool>.empty();

  @override
  Future<void> release() async {}
}
