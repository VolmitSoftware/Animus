/// Live-editing ownership across tabs through the Web Locks API: the tab that
/// holds the lock named for a server runs its live session, and a waiting tab
/// takes over when the owner closes or switches away.
library;

import 'dart:async';
import 'dart:js_interop';

import 'package:web/web.dart' as web;

import '../../state/session_owner.dart';

class WebLockOwner implements SessionOwner {
  /// Queues for the exclusive lock `animus-live-<serverId>`.
  WebLockOwner(String serverId) {
    try {
      web.window.navigator.locks.request(
        'animus-live-$serverId',
        web.LockOptions(mode: 'exclusive'),
        ((web.Lock? lock) {
          if (_released) return Future<void>.value().toJS;
          _set(true);
          return _held.future.toJS;
        }).toJS,
      );
    } on Object {
      // No Web Locks (an old browser): this tab owns the session alone.
      _set(true);
    }
  }

  final Completer<void> _held = Completer<void>();
  final StreamController<bool> _changes = StreamController<bool>.broadcast(sync: true);
  bool _owned = false;
  bool _released = false;

  void _set(bool value) {
    if (value == _owned) return;
    _owned = value;
    _changes.add(value);
  }

  @override
  bool get owned => _owned;

  @override
  Stream<bool> get changes => _changes.stream;

  @override
  Future<void> release() async {
    _released = true;
    if (!_held.isCompleted) _held.complete();
    _set(false);
    await _changes.close();
  }
}
