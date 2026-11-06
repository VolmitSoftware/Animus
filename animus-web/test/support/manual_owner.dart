import 'dart:async';

import 'package:animus_editor/state/session_owner.dart';

/// Ownership switched by hand, for tests of the two-tab rules.
class ManualOwner implements SessionOwner {
  ManualOwner({bool owned = true}) : _owned = owned; // ignore: prefer_initializing_formals

  bool _owned;
  final StreamController<bool> _changes = StreamController<bool>.broadcast(sync: true);

  @override
  bool get owned => _owned;

  set owned(bool value) {
    if (value == _owned) return;
    _owned = value;
    _changes.add(value);
  }

  @override
  Stream<bool> get changes => _changes.stream;

  @override
  Future<void> release() async => owned = false;
}
