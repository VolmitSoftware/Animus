/// The paired server's world names from `/v1/lookups`, shared between the
/// live session (which reads lookups) and the terrain link (which asks for
/// tiles). Pure Dart so the session stays testable off the browser.
abstract final class TerrainWorlds {
  static Set<String>? _names;
  static final List<void Function(Set<String>?)> _listeners = <void Function(Set<String>?)>[];

  /// The known worlds; null while unknown, so every world may be asked.
  static Set<String>? get names => _names;

  /// Sets the server's worlds; an empty list means unknown.
  static void update(List<String> names) {
    _names = names.isEmpty ? null : names.toSet();
    for (final void Function(Set<String>?) listener in List<void Function(Set<String>?)>.of(_listeners)) {
      listener(_names);
    }
  }

  static void clear() => update(const <String>[]);

  static void listen(void Function(Set<String>?) listener) => _listeners.add(listener);
}
