import 'dart:async';

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
