/// The one source every list of documents reads: a local workspace's
/// documents, or a server workspace's catalog with each entry's summary,
/// overlaid with the documents loaded here. A paired server's lists are
/// complete as soon as its catalog arrives, before any document is read.
///
/// Rows are kept per document and per kind: an edit rebuilds only the rows
/// of the documents it changed, and only their kind's list moves.
library;

import '../logic/change_sets.dart';
import '../logic/diagnostic_text.dart';
import '../model/display_name.dart';
import '../logic/npc/skin_edits.dart' show skinDocumentLabel;
import '../logic/skin_label.dart';
import '../model/animus_document.dart';
import '../model/document_kind.dart';
import '../model/document_summary.dart';
import '../model/server_binding.dart';
import 'catalog_entry.dart';
import 'workspace_repository.dart';

/// One row of a list.
class LibraryEntry {
  LibraryEntry({
    required this.kind,
    required this.id,
    required this.path,
    required this.name,
    required this.summary,
    this.revision,
    this.loaded = true,
    this.loading = false,
    this.draft = DraftState.clean,
    this.stale = false,
    this.problems = 0,
  }) : sortKey = '${naturalKey(name)}\u0000${naturalKey(id)}';

  final DocumentKind kind;
  final String id;
  final String path;

  /// Plain text to show; the id when the document has no visible name.
  final String name;
  final DocumentSummary summary;

  /// The server revision; null in a local workspace and for a document
  /// made here.
  final int? revision;

  /// The whole document is in this workspace. A server document is read
  /// when it is opened (or by the background fill); until then the row
  /// shows the catalog summary.
  final bool loaded;

  /// Being read from the server now.
  final bool loading;

  /// How it stands against the server: clean, modified, created, deleted or
  /// moved. Always clean in a local workspace.
  final DraftState draft;

  /// Changed here and not applied yet.
  bool get changed => draft != DraftState.clean;

  /// Changed on the server since it was loaded here, while also changed
  /// here: the next apply needs a merge.
  final bool stale;

  /// Errors on it, local and from the server.
  final int problems;

  String get key => documentKey(kind, id);

  /// Sorts names as people read them: case folded, numbers by value
  /// (`citizens-7` before `citizens-12`), then by id.
  final String sortKey;
}

/// [text] lower-cased with every digit run padded to 12 digits.
String naturalKey(String text) =>
    text.toLowerCase().replaceAllMapped(RegExp(r'\d+'), (Match digits) => digits.group(0)!.padLeft(12, '0'));

/// The server side of a server workspace's library, kept by its live session.
abstract class ServerLibrary {
  /// Raised whenever something below changed.
  int get libraryVersion;

  /// The full catalog has arrived since the session started.
  bool get catalogLoaded;

  /// Catalog entries of [kind], in catalog order.
  List<CatalogEntry> catalogOf(DocumentKind kind);

  bool isStale(String key);

  bool isLoading(String key);

  /// Error diagnostics the server reported for [key].
  List<Diagnostic> serverErrors(String key);

  /// Keys that are stale or carry server errors.
  Iterable<String> get attentionKeys;

  /// How the loaded document [kind] [id] stands against the server.
  DraftState stateOf(DocumentKind kind, String id);

  /// Keys NPCs were moved away from here and not applied yet: the library
  /// lists and counts each such NPC once, at its new key.
  Set<String> get movedFrom;
}

/// Listeners told when one part of the library may have changed: one
/// kind's rows (`EditorStore.libraryChanges`) or the counts
/// (`EditorStore.countChanges`). Same shape as a jaspr `Listenable`.
class LibraryChanges {
  final List<void Function()> _listeners = <void Function()>[];

  bool get hasListeners => _listeners.isNotEmpty;

  void addListener(void Function() listener) => _listeners.add(listener);

  void removeListener(void Function() listener) => _listeners.remove(listener);

  void notify() {
    for (final void Function() listener in List<void Function()>.of(_listeners)) {
      listener();
    }
  }
}

/// What moved in the library since its listeners last heard.
class LibraryDelta {
  const LibraryDelta(this.kinds, {this.counts = false});

  static const LibraryDelta none = LibraryDelta(<DocumentKind>{});

  /// Kinds whose rows may differ.
  final Set<DocumentKind> kinds;

  /// A document joined or left a list, so a count may differ.
  final bool counts;
}

/// Builds and caches the rows of every kind. [sync] compares the workspace
/// with the one it saw last: a new workspace, a server change or a moved
/// session starts over; otherwise only the documents and bases that are
/// different objects lose their rows.
class Library {
  WorkspaceSnapshot? _workspace;
  ServerLibrary? _live;
  int _serverVersion = -1;

  /// Keys of the workspace's documents and server bases, by kind.
  final Map<DocumentKind, Set<String>> _local = <DocumentKind, Set<String>>{};

  /// Catalog entries by key, per kind, read once per server version.
  final Map<DocumentKind, Map<String, CatalogEntry>> _catalogs = <DocumentKind, Map<String, CatalogEntry>>{};

  /// Rows by key, kept until their document, base or the server side moves.
  final Map<String, LibraryEntry> _entries = <String, LibraryEntry>{};
  final Map<DocumentKind, List<LibraryEntry>> _rows = <DocumentKind, List<LibraryEntry>>{};
  final Map<DocumentKind, Map<String, LibraryEntry>> _byId = <DocumentKind, Map<String, LibraryEntry>>{};
  final Map<DocumentKind, int> _revisions = <DocumentKind, int>{};
  Map<DocumentKind, int>? _counts;

  /// Workspace keys whose document has error diagnostics.
  final Set<String> _errors = <String>{};
  List<LibraryEntry>? _attention;

  /// Summaries of loaded documents, kept while the document is the same
  /// object.
  final Expando<DocumentSummary> _summaries = Expando<DocumentSummary>('summary');

  /// Raised whenever the rows of [kind] may have changed.
  int revisionOf(DocumentKind kind) => _revisions[kind] ?? 0;

  /// Kinds moved, and whether a count may have, since [takeDelta] last ran.
  final Set<DocumentKind> _movedKinds = <DocumentKind>{};
  bool _movedCounts = false;

  /// What moved since the last call, for the listeners of those parts.
  LibraryDelta takeDelta() {
    if (_movedKinds.isEmpty && !_movedCounts) return LibraryDelta.none;
    final LibraryDelta delta = LibraryDelta(Set<DocumentKind>.of(_movedKinds), counts: _movedCounts);
    _movedKinds.clear();
    _movedCounts = false;
    return delta;
  }

  /// Brings the cache up to [workspace] and [server].
  void sync(WorkspaceSnapshot? workspace, ServerLibrary? server) {
    final ServerLibrary? live = workspace?.server == null ? null : server;
    final WorkspaceSnapshot? previous = _workspace;
    final int version = live?.libraryVersion ?? -1;
    if (previous == null || workspace == null || previous.id != workspace.id || !identical(live, _live) || version != _serverVersion) {
      if (previous == null && workspace == null) return;
      _reset(workspace, live, version);
      return;
    }
    _workspace = workspace;
    if (identical(previous.documents, workspace.documents) && identical(previous.server?.bases, workspace.server?.bases)) return;
    _moved(changedKeys(previous, workspace));
  }

  /// Drops the rows of [keys]: their server side moved (diagnostics an edit
  /// made stale).
  void invalidate(Iterable<String> keys) => _moved(keys);

  void _reset(WorkspaceSnapshot? workspace, ServerLibrary? live, int version) {
    _workspace = workspace;
    _live = live;
    _serverVersion = version;
    _local.clear();
    _catalogs.clear();
    _entries.clear();
    _rows.clear();
    _byId.clear();
    _errors.clear();
    _counts = null;
    _ownRoutes = null;
    _attention = null;
    _wearersBySkin = null;
    _movedKinds.addAll(DocumentKind.values);
    _movedCounts = true;
    for (final DocumentKind kind in DocumentKind.values) {
      _revisions[kind] = revisionOf(kind) + 1;
    }
    if (workspace == null) return;
    for (final AnimusDocument document in workspace.documents) {
      final String key = documentKey(document.kind, document.id);
      _local.putIfAbsent(document.kind, () => <String>{}).add(key);
      if (_errorsOf(document) > 0) _errors.add(key);
    }
    for (final String key in workspace.server?.bases.keys ?? const <String>[]) {
      final (DocumentKind, String)? parsed = parseDocumentKey(key);
      if (parsed != null) _local.putIfAbsent(parsed.$1, () => <String>{}).add(key);
    }
  }

  void _moved(Iterable<String> keys) {
    final WorkspaceSnapshot? workspace = _workspace;
    if (workspace == null) return;
    final Set<DocumentKind> kinds = <DocumentKind>{};
    bool counts = false;
    for (final String key in keys) {
      final (DocumentKind, String)? parsed = parseDocumentKey(key);
      if (parsed == null) continue;
      final DocumentKind kind = parsed.$1;
      kinds.add(kind);
      final AnimusDocument? document = workspace.byKey[key];
      if (kind == DocumentKind.npc && _wearersBySkin != null && _wearerMoved(key, parsed.$2, document)) {
        _wearersBySkin = null;
        _entries.removeWhere((String cached, LibraryEntry _) => cached.startsWith('${DocumentKind.skin.wireName}:'));
        kinds.add(DocumentKind.skin);
      }
      _entries.remove(key);
      final bool here = document != null || (workspace.server?.bases.containsKey(key) ?? false);
      final Set<String> local = _local.putIfAbsent(kind, () => <String>{});
      final bool joined = here ? local.add(key) : local.remove(key);
      if (joined && !_catalogOf(kind).containsKey(key)) counts = true;
      if (document != null && _errorsOf(document) > 0) {
        _errors.add(key);
      } else {
        _errors.remove(key);
      }
    }
    if (kinds.isEmpty) return;
    for (final DocumentKind kind in kinds) {
      _rows.remove(kind);
      _byId.remove(kind);
      _revisions[kind] = revisionOf(kind) + 1;
    }
    _attention = null;
    _movedKinds.addAll(kinds);
    final int? ownRoutes = _ownRoutes;
    if (ownRoutes != null && kinds.contains(DocumentKind.npc)) {
      // An NPC can gain or lose its own route without joining or leaving.
      final int now = _countOwnRoutes();
      _ownRoutes = now;
      if (now != ownRoutes) _movedCounts = true;
    }
    if (counts) {
      _counts = null;
      _movedCounts = true;
    }
  }

  Map<String, CatalogEntry> _catalogOf(DocumentKind kind) {
    final ServerLibrary? live = _live;
    if (live == null) return const <String, CatalogEntry>{};
    return _catalogs[kind] ??= <String, CatalogEntry>{for (final CatalogEntry entry in live.catalogOf(kind)) entry.key: entry};
  }

  DocumentSummary _summaryOf(AnimusDocument document) => _summaries[document] ??= summarizeDocument(document.kind, document.id, document.json);

  static int _errorsOf(AnimusDocument? document) =>
      document?.diagnostics.where((Diagnostic diagnostic) => diagnostic.severity == DiagnosticSeverity.error).length ?? 0;

  /// Errors on [document] and those [server] adds, each path and message
  /// once: the count the document's own page shows.
  static int _problemsOf(AnimusDocument? document, List<Diagnostic> server) {
    final List<Diagnostic> local = <Diagnostic>[
      for (final Diagnostic diagnostic in document?.diagnostics ?? const <Diagnostic>[])
        if (diagnostic.severity == DiagnosticSeverity.error) diagnostic,
    ];
    return groupRepeats(withoutRepeats(<Diagnostic>[...local, ...serverOnly(local, server)])).length;
  }

  /// Every row of [kind], sorted by [LibraryEntry.sortKey].
  List<LibraryEntry> entries(DocumentKind kind, WorkspaceSnapshot? workspace, ServerLibrary? server) {
    sync(workspace, server);
    return _rows[kind] ??= _build(kind);
  }

  /// The row of [kind] [id], or null.
  LibraryEntry? entry(DocumentKind kind, String id, WorkspaceSnapshot? workspace, ServerLibrary? server) {
    final List<LibraryEntry> rows = entries(kind, workspace, server);
    return (_byId[kind] ??= <String, LibraryEntry>{for (final LibraryEntry row in rows) row.id: row})[id];
  }

  /// How many rows each kind has, kept until a document joins or leaves.
  Map<DocumentKind, int> counts(WorkspaceSnapshot? workspace, ServerLibrary? server) {
    sync(workspace, server);
    return _counts ??= Map<DocumentKind, int>.unmodifiable(<DocumentKind, int>{
      for (final DocumentKind kind in DocumentKind.values) kind: _countOf(kind),
    });
  }

  int _countOf(DocumentKind kind) {
    final List<LibraryEntry>? rows = _rows[kind];
    if (rows != null) return rows.length;
    return _keysOf(kind).length;
  }

  /// The keys [kind] lists: the catalog's and the workspace's, without the
  /// places NPCs moved away from.
  Set<String> _keysOf(DocumentKind kind) {
    final Set<String> keys = <String>{..._catalogOf(kind).keys, ...?_local[kind]};
    final Set<String>? moved = kind == DocumentKind.npc ? _live?.movedFrom : null;
    if (moved != null && moved.isNotEmpty) keys.removeAll(moved);
    return keys;
  }

  /// NPCs with a route of their own (points in their navigation, no route
  /// document): Movement lists them beside the route documents.
  int ownRoutes(WorkspaceSnapshot? workspace, ServerLibrary? server) {
    sync(workspace, server);
    return _ownRoutes ??= _countOwnRoutes();
  }

  int? _ownRoutes;

  int _countOwnRoutes() {
    final WorkspaceSnapshot? workspace = _workspace;
    if (workspace == null) return 0;
    final Map<String, CatalogEntry> catalog = _catalogOf(DocumentKind.npc);
    int count = 0;
    for (final String key in <String>{...catalog.keys, ...?_local[DocumentKind.npc]}) {
      final AnimusDocument? document = workspace.byKey[key];
      final DocumentSummary summary = document != null ? _summaryOf(document) : (catalog[key]?.summary ?? DocumentSummary.empty);
      if ((summary.count('points') ?? 0) > 0) count++;
    }
    return count;
  }

  /// Rows with errors or changed on the server, by kind then reading order.
  List<LibraryEntry> attention(WorkspaceSnapshot? workspace, ServerLibrary? server) {
    sync(workspace, server);
    return _attention ??= _buildAttention();
  }

  List<LibraryEntry> _buildAttention() {
    final Set<String> keys = <String>{..._errors, ...?_live?.attentionKeys};
    final List<LibraryEntry> rows = <LibraryEntry>[
      for (final String key in keys)
        if (parseDocumentKey(key) case (final DocumentKind kind, _) when _listed(kind, key))
          if (_entryOf(kind, key) case final LibraryEntry row when row.problems > 0 || row.stale) row,
    ]..sort((LibraryEntry a, LibraryEntry b) => a.kind != b.kind ? a.kind.index.compareTo(b.kind.index) : a.sortKey.compareTo(b.sortKey));
    return List<LibraryEntry>.unmodifiable(rows);
  }

  bool _listed(DocumentKind kind, String key) => (_local[kind]?.contains(key) ?? false) || _catalogOf(kind).containsKey(key);

  List<LibraryEntry> _build(DocumentKind kind) {
    if (_workspace == null) return const <LibraryEntry>[];
    final List<LibraryEntry> rows = <LibraryEntry>[for (final String key in _keysOf(kind)) _entryOf(kind, key)]
      ..sort((LibraryEntry a, LibraryEntry b) => a.sortKey.compareTo(b.sortKey));
    return List<LibraryEntry>.unmodifiable(rows);
  }

  LibraryEntry _entryOf(DocumentKind kind, String key) => _entries[key] ??= _row(kind, key);

  /// Skin id to the names of the NPCs wearing it, in reading order; worked
  /// out once from the NPC rows and dropped when one of them changes skin
  /// or name.
  Map<String, List<String>>? _wearersBySkin;

  Map<String, List<String>> _wearers() {
    final Map<String, List<String>>? known = _wearersBySkin;
    if (known != null) return known;
    final Map<String, List<String>> wearers = <String, List<String>>{};
    for (final LibraryEntry npc in _rows[DocumentKind.npc] ??= _build(DocumentKind.npc)) {
      final String? skin = npc.summary.skin;
      if (skin != null) (wearers[skin] ??= <String>[]).add(npc.name);
    }
    return _wearersBySkin = wearers;
  }

  /// Whether the NPC at [key] now wears another skin or goes by another
  /// name than its row says.
  bool _wearerMoved(String key, String id, AnimusDocument? document) {
    final LibraryEntry? row = _entries[key];
    if (row == null) return true;
    final DocumentSummary? next = document == null ? null : _summaryOf(document);
    return row.summary.skin != next?.skin || row.name != displayName(DocumentKind.npc, id, next?.name);
  }

  LibraryEntry _row(DocumentKind kind, String key) {
    final WorkspaceSnapshot workspace = _workspace!;
    final ServerLibrary? live = _live;
    final AnimusDocument? document = workspace.byKey[key];
    final ServerBase? base = workspace.server?.bases[key];
    final CatalogEntry? catalog = _catalogOf(kind)[key];
    final String id = catalog?.id ?? document?.id ?? key.substring(kind.wireName.length + 1);
    final DocumentSummary summary = document != null ? _summaryOf(document) : (catalog?.summary ?? DocumentSummary.empty);
    final DraftState draft = live == null
        ? DraftState.clean
        : document == null
            ? (base == null ? DraftState.clean : DraftState.deleted)
            : live.stateOf(kind, id);
    return LibraryEntry(
      kind: kind,
      id: id,
      path: document?.path ?? catalog?.path ?? base?.path ?? '',
      name: switch (kind) {
        DocumentKind.skin => skinLabel(id, label: skinDocumentLabel(document?.json), wornBy: _wearers()[id] ?? const <String>[]),
        _ => displayName(kind, id, summary.name),
      },
      summary: summary,
      revision: base?.revision ?? catalog?.revision,
      loaded: document != null || (live == null) || draft == DraftState.deleted,
      loading: live?.isLoading(key) ?? false,
      draft: draft,
      stale: live?.isStale(key) ?? false,
      problems: _problemsOf(document, live?.serverErrors(key) ?? const <Diagnostic>[]),
    );
  }
}
