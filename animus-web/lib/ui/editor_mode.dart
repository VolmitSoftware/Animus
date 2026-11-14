/// Simple and Advanced. Simple shows the common tasks; Advanced shows
/// everything. Pure Dart, so the parsing is unit tested.
library;

enum EditorMode {
  simple('Simple'),
  advanced('Advanced');

  const EditorMode(this.label);

  /// The word the Mode switch shows.
  final String label;
}

/// The localStorage key the mode is kept under, per browser.
const String modeStorageKey = 'animus.mode';

/// A stored value as a mode; anything unknown (or nothing) is Simple.
EditorMode parseMode(String? stored) => stored == EditorMode.advanced.name ? EditorMode.advanced : EditorMode.simple;
