/// A list part that rebuilds when the library rows it shows move
/// ([EditorStore.libraryChanges]) or the counts change
/// ([EditorStore.countChanges]), so a keystroke in one document leaves the
/// other kinds' lists and the nav alone. Pair it with a `shouldRebuild`
/// that ignores the frame's rebuilds on every store change.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';

import '../state/library.dart';

mixin LibraryWatch<T extends StatefulWidget> on State<T> {
  final List<LibraryChanges> _watched = <LibraryChanges>[];

  /// Listens to [sources] in place of whatever it listened to before.
  void watchLibrary(Iterable<LibraryChanges> sources) {
    _unwatch();
    _watched.addAll(sources);
    for (final LibraryChanges source in _watched) {
      source.addListener(_libraryMoved);
    }
  }

  void _unwatch() {
    for (final LibraryChanges source in _watched) {
      source.removeListener(_libraryMoved);
    }
    _watched.clear();
  }

  void _libraryMoved() {
    if (mounted) setState(() {});
  }

  @override
  void dispose() {
    _unwatch();
    super.dispose();
  }
}
