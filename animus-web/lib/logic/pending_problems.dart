/// Problems an edit makes by adding an empty field the user is about to
/// fill: a new required capability argument, a new conversation line. They
/// are pending, not errors, until the field loses focus, so the header list
/// and the field itself stay quiet while the user types.
library;

import '../model/animus_document.dart';

class PendingProblems {
  bool _armed = false;
  String? _field;
  List<Diagnostic>? _seen;
  Set<String> _held = <String>{};
  final List<void Function()> _listeners = <void Function()>[];

  /// The element id of the field being filled, while its problems are held.
  String? get field => _field;

  /// The next edit adds an empty field at element [field]: the problems
  /// that edit adds are held until [release].
  void expect(String field) {
    _armed = true;
    _field = field;
    _held = <String>{};
  }

  /// [current] without the held problems. The first new list after
  /// [expect] that adds paths decides which are held: those [current] has
  /// and the list before it did not. A list that adds none (an earlier
  /// keystroke landing first) leaves it waiting.
  List<Diagnostic> visible(List<Diagnostic> current) {
    final List<Diagnostic>? seen = _seen;
    if (_armed && seen != null && !identical(seen, current)) {
      final Set<String> before = <String>{for (final Diagnostic diagnostic in seen) diagnostic.path};
      _held = <String>{for (final Diagnostic diagnostic in current) if (!before.contains(diagnostic.path)) diagnostic.path};
      _armed = _held.isEmpty;
    }
    _seen = current;
    if (_held.isEmpty) return current;
    return <Diagnostic>[for (final Diagnostic diagnostic in current) if (!_isHeld(diagnostic.path)) diagnostic];
  }

  bool _isHeld(String path) => _held.any((String held) => path == held || path.startsWith('$held.') || path.startsWith('$held['));

  /// The field lost focus: its problems show from now on.
  void release() {
    if (_field == null && !_armed && _held.isEmpty) return;
    _armed = false;
    _field = null;
    _held = <String>{};
    for (final void Function() listener in List<void Function()>.of(_listeners)) {
      listener();
    }
  }

  void addListener(void Function() listener) => _listeners.add(listener);

  void removeListener(void Function() listener) => _listeners.remove(listener);
}
