/// Live-editing ownership across tabs through the Web Locks API: the tab that
/// holds the lock named for a server runs its live session, and a waiting tab
/// takes over when the owner closes or switches away.
library;

import 'dart:async';
import 'dart:js_interop';

import 'package:web/web.dart' as web;

import '../../state/session_owner.dart';

class WebLockOwner implements SessionOwner {
  /// Asks for the exclusive lock `animus-live-<serverId>`: at once when it is
  /// free, else after saying another tab holds it and queueing for it.
  WebLockOwner(String serverId) : _name = 'animus-live-$serverId' {
    try {
      _request(ifAvailable: true);
    } on Object {
      // No Web Locks (an old browser): this tab owns the session alone.
      _set(owned: true, waiting: false);
    }
  }

  final String _name;
  final Completer<void> _held = Completer<void>();
  final StreamController<bool> _changes = StreamController<bool>.broadcast(sync: true);
  bool _owned = false;
  bool _waiting = false;
  bool _released = false;

  void _request({required bool ifAvailable}) {
    web.window.navigator.locks.request(
      _name,
      web.LockOptions(mode: 'exclusive', ifAvailable: ifAvailable),
      ((web.Lock? lock) {
        if (_released) return Future<void>.value().toJS;
        if (lock == null) {
          // Another tab holds it: say so, and wait in line.
          _set(owned: false, waiting: true);
          _request(ifAvailable: false);
          return Future<void>.value().toJS;
        }
        _set(owned: true, waiting: false);
        return _held.future.toJS;
      }).toJS,
    );
  }

  void _set({required bool owned, required bool waiting}) {
    final bool changed = owned != _owned || waiting != _waiting;
    _owned = owned;
    _waiting = waiting;
    if (changed && !_changes.isClosed) _changes.add(owned);
  }

  @override
  bool get owned => _owned;

  @override
  bool get waiting => _waiting;

  @override
  Stream<bool> get changes => _changes.stream;

  @override
  Future<void> release() async {
    _released = true;
    if (!_held.isCompleted) _held.complete();
    _set(owned: false, waiting: false);
    await _changes.close();
  }
}
