part of 'editor_store.dart';

/// The library every list reads ([Library]), and who hears it move: the
/// store's own listeners on every change, and per kind or for the counts
/// only when those moved.
extension StoreLibrary on EditorStore {
  /// The live session's side of the open server workspace's library: its
  /// catalog, loading and stale documents. Set by the session as it starts,
  /// without telling listeners: the session's first catalog does.
  void attachServer(ServerLibrary server) {
    _server = server;
    _libraryRevision++;
  }

  void detachServer(ServerLibrary server) {
    if (!identical(_server, server)) return;
    _server = null;
    _libraryRevision++;
  }

  /// Tells listeners the library moved without a document change (the
  /// catalog arrived, a document started loading, a server copy changed).
  /// With [keys], only those documents' rows are read again.
  void libraryChanged({Iterable<String>? keys}) {
    if (keys != null) _library.invalidate(keys);
    _changed();
  }

  /// Raised by every change listeners hear, so a list can tell whether its
  /// rows may have moved.
  int get libraryRevision => _libraryRevision;

  /// Raised only when the rows of [kind] may have moved: an edit of one
  /// document leaves every other kind's revision alone.
  int libraryRevisionOf(DocumentKind kind) {
    _library.sync(_workspace, _server);
    return _library.revisionOf(kind);
  }

  /// Every document of [kind] a list should show, sorted for reading: the
  /// documents of a local workspace, or the whole server catalog (with what
  /// is loaded here laid over it) for a server workspace. The same list
  /// object comes back until a row of [kind] changes.
  List<LibraryEntry> library(DocumentKind kind) => _library.entries(kind, _workspace, _server);

  /// How many documents of each kind the library holds. The same map comes
  /// back until a document joins or leaves a list.
  Map<DocumentKind, int> libraryCounts() => _library.counts(_workspace, _server);

  /// NPCs with a route of their own, which Movement counts beside route
  /// documents and recordings.
  int libraryOwnRoutes() => _library.ownRoutes(_workspace, _server);

  /// The row of one document, or null when the library has none.
  LibraryEntry? libraryEntry(DocumentKind kind, String id) => _library.entry(kind, id, _workspace, _server);

  /// Documents with errors ([LibraryEntry.problems]) or changed on the
  /// server ([LibraryEntry.stale]), by kind then reading order. Kept
  /// between edits; an edit reads again only the documents it touched.
  List<LibraryEntry> libraryAttention() => _library.attention(_workspace, _server);

  /// Told when the rows of [kind] may have moved, and only then.
  LibraryChanges libraryChanges(DocumentKind kind) => _kindChanges.putIfAbsent(kind, LibraryChanges.new);

  /// Told when [libraryCounts] changed.
  LibraryChanges get countChanges => _countChanges;

  /// Tells the per-kind and count listeners what moved since they last heard.
  void _tellLibrary() {
    final LibraryDelta delta = _library.takeDelta();
    for (final DocumentKind kind in delta.kinds) {
      _kindChanges[kind]?.notify();
    }
    if (!delta.counts || !_countChanges.hasListeners) return;
    final Map<DocumentKind, int> counts = libraryCounts();
    final int ownRoutes = libraryOwnRoutes();
    final Map<DocumentKind, int>? told = _toldCounts;
    final int? toldRoutes = _toldOwnRoutes;
    _toldCounts = counts;
    _toldOwnRoutes = ownRoutes;
    if (told != null && toldRoutes == ownRoutes && DocumentKind.values.every((DocumentKind kind) => told[kind] == counts[kind])) return;
    _countChanges.notify();
  }
}
