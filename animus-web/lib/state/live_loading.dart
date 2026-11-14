part of 'live_session.dart';

/// Reading server documents into the workspace and following the server's
/// changes to them.
extension LiveLoading on LiveSession {
  /// Reads again the documents the last load could not.
  Future<void> retryFailedLoads() async {
    final LoadReport? report = loadReport;
    if (report == null || report.failed.isEmpty) return;
    loadReport = null;
    await ensureLoaded(<DocumentRef>[
      for (final String key in report.failed)
        if (parseDocumentKey(key) case (final DocumentKind kind, final String id)) DocumentRef(kind, id),
    ]);
  }

  /// Reads the server documents among [refs] that are not in the workspace
  /// yet, ahead of the background fill: a document being opened, the
  /// documents a bulk action or a preview needs. Waits for the connection.
  /// A load of more than one document ends with a [loadReport].
  Future<LoadResult> ensureLoaded(Iterable<DocumentRef> refs) async {
    final ServerBinding? server = binding;
    final List<String> keys = <String>[
      for (final DocumentRef ref in refs)
        if (store.document(ref.kind, ref.id) == null && server?.bases[ref.key] == null) ref.key,
    ];
    if (keys.isEmpty) return const LoadResult();
    if (!owned) return LoadResult(failed: keys, reason: const ErrorCopy('Live editing runs in another tab.', 'Use that tab, or close it.'));
    if (!online) {
      _waitingOnline.addAll(keys);
      _libraryChanged();
      await (_onlineSignal ??= Completer<void>()).future;
      _waitingOnline.removeAll(keys);
    }
    final LoadResult result = await _loader.load(keys);
    if (keys.length > 1) {
      loadReport = LoadReport(result.loaded.length, result.failed, result.reason);
      if (result.failed.isNotEmpty) {
        _notice(NoticeLevel.warning, 'Loaded ${result.loaded.length} of ${countOf(keys.length, 'document')}; ${result.failed.length} failed. ${result.reason?.cause ?? ''}'.trim());
      }
    }
    _libraryChanged();
    return result;
  }

  /// Lands one batch-read answer: documents not here yet join the workspace
  /// with their server base; a copy being refreshed is replaced while it is
  /// still unchanged here, or marked stale once it is not.
  Future<void> _deliver(List<FetchedDocument> documents) async {
    final ServerBinding? server = binding;
    if (server == null) return;
    final List<DocumentChange> changes = <DocumentChange>[];
    final Map<String, ServerBase> bases = <String, ServerBase>{};
    for (final FetchedDocument document in documents) {
      final CatalogEntry? listed = _catalog[document.key];
      if (listed == null || listed.hash != document.base.hash) {
        _catalog.upsert(CatalogEntry(document.kind, document.id, document.base.path, document.base.revision, document.base.hash, summary: document.summary));
      }
      final bool here = store.document(document.kind, document.id) != null || server.bases.containsKey(document.key);
      if (here && !_refreshing.contains(document.key)) continue;
      if (here && server.bases[document.key]?.hash == document.base.hash) {
        stale.remove(document.key);
        continue;
      }
      if (here && stateOf(document.kind, document.id) != DraftState.clean && !_replacing.contains(document.key)) {
        stale.add(document.key);
        continue;
      }
      bases[document.key] = document.base;
      changes.add(DocumentChange(document.kind, document.id, prettyJson(document.base.source), path: document.base.path));
      if (document.diagnostics.isNotEmpty) _diagnose(document.key, document.diagnostics);
      stale.remove(document.key);
    }
    if (changes.isNotEmpty) {
      await store.commit(
        documents: changes,
        server: (ServerBinding binding) => binding.copyWith(bases: <String, ServerBase>{...binding.bases, ...bases}),
        undoable: false,
      );
      _refreshed += changes.where((DocumentChange change) => _refreshing.contains(documentKey(change.kind, change.id))).length;
    }
  }

  /// Reads the server's version of [keys] and lays it over each copy still
  /// unchanged here (every copy with [replace]); a copy changed here is
  /// marked stale. Returns how many it replaced.
  Future<int> _refresh(List<String> keys, {bool replace = false}) async {
    if (keys.isEmpty) return 0;
    _refreshing.addAll(keys);
    if (replace) _replacing.addAll(keys);
    final int before = _refreshed;
    try {
      await _loader.load(keys);
    } finally {
      _refreshing.removeAll(keys);
      _replacing.removeAll(keys);
    }
    return _refreshed - before;
  }

  /// Removes the copies of documents the server deleted, while they are
  /// unchanged here.
  Future<void> _removeGone(List<String> keys) async {
    final List<String> clean = <String>[
      for (final String key in keys)
        if (parseDocumentKey(key) case (final DocumentKind kind, final String id) when stateOf(kind, id) == DraftState.clean) key,
    ];
    if (clean.isEmpty) return;
    final Set<String> removed = clean.toSet();
    await store.commit(
      documents: <DocumentChange>[
        for (final String key in clean)
          if (parseDocumentKey(key) case (final DocumentKind kind, final String id)) DocumentChange(kind, id, null),
      ],
      server: (ServerBinding binding) => binding.copyWith(
        bases: <String, ServerBase>{
          for (final MapEntry<String, ServerBase> base in binding.bases.entries)
            if (!removed.contains(base.key)) base.key: base.value,
        },
      ),
      undoable: false,
    );
  }

  void _startFill() {
    if (!backgroundFill || !owned) return;
    final ServerBinding? server = binding;
    if (server == null) return;
    _loader.fill(<String>[
      for (final CatalogEntry entry in _catalog.entries)
        if (store.document(entry.kind, entry.id) == null && !server.bases.containsKey(entry.key)) entry.key,
    ]);
  }

  /// Compares each loaded document's base with the catalog: an unchanged
  /// copy takes the server's newer version (or goes, when the server
  /// deleted it); a changed one is marked stale. Returns the copies read
  /// again, the copies removed and the drafts newly marked stale.
  Future<({int refreshed, int removed, int stale})> _reconcile() async {
    final ServerBinding? server = binding;
    if (server == null) return (refreshed: 0, removed: 0, stale: 0);
    final Set<String> inFlight = _inFlight(server);
    // News held for change sets settled since is in the catalog just read.
    _held.removeWhere((String key, CatalogEntry? _) => !inFlight.contains(key));
    final List<String> refresh = <String>[];
    final List<String> gone = <String>[];
    int staleDrafts = 0;
    for (final MapEntry<String, ServerBase> base in server.bases.entries) {
      final CatalogEntry? entry = _catalog[base.key];
      if (inFlight.contains(base.key)) {
        // Checked when the change set's answer lands.
        if (entry != null) _held[base.key] = entry;
        continue;
      }
      _forgetOldConflict(base.key, entry?.hash);
      if (entry?.hash == base.value.hash) continue;
      final (DocumentKind, String)? parsed = parseDocumentKey(base.key);
      if (parsed == null) continue;
      if (stateOf(parsed.$1, parsed.$2) == DraftState.clean) {
        (entry == null ? gone : refresh).add(base.key);
      } else if (stale.add(base.key)) {
        staleDrafts++;
      }
    }
    await _removeGone(gone);
    final int refreshed = await _refresh(refresh);
    _libraryChanged();
    return (refreshed: refreshed, removed: gone.length, stale: staleDrafts);
  }

  /// Reads [keys] now without storing them: the server's current copies
  /// for a merge. Ones the server does not have (`not-found`) are left out;
  /// null when the read failed or any key was refused or left unread, so an
  /// unreadable copy is never taken for a deletion.
  Future<Map<String, FetchedDocument>?> _readNow(List<String> keys) async {
    final ApiResponse? response = await _call(
      ApiRequest('POST', '/v1/documents/read', body: <String, Object?>{
        'refs': <Object?>[
          for (final String key in keys)
            if (parseDocumentKey(key) case (final DocumentKind kind, final String id)) <String, Object?>{'kind': kind.wireName, 'id': id},
        ],
      }, counted: false),
      'read the server copy',
      retry: true,
    );
    final Object? body = response?.body;
    if (response == null || !response.ok || body is! Map<String, Object?>) {
      if (response != null) _notice(NoticeLevel.error, 'Could not read the server copy. ${response.problem.text}');
      return null;
    }
    final Map<String, FetchedDocument> read = <String, FetchedDocument>{
      for (final Object? raw in (body['documents'] as List<Object?>?) ?? const <Object?>[])
        if (FetchedDocument.fromJson(raw) case final FetchedDocument document) document.key: document,
    };
    final Set<String> deleted = <String>{
      for (final Object? raw in (body['errors'] as List<Object?>?) ?? const <Object?>[])
        if (raw is Map<String, Object?> && raw['code'] == 'not-found') '${raw['kind']}:${raw['id']}',
    };
    final String? unread = keys.where((String key) => !read.containsKey(key) && !deleted.contains(key)).firstOrNull;
    if (unread != null) {
      final Object? refusal = ((body['errors'] as List<Object?>?) ?? const <Object?>[])
          .whereType<Map<String, Object?>>()
          .where((Map<String, Object?> error) => '${error['kind']}:${error['id']}' == unread)
          .firstOrNull?['code'];
      _notice(NoticeLevel.error, 'Could not read the server copy. ${serverCopy(refusal is String ? refusal : 'too-large').text}');
      return null;
    }
    return read;
  }

  /// A write from elsewhere to a document the last apply wrote: that apply
  /// is no longer the server's latest word on it, so the bar stops offering
  /// to undo it.
  void _forgetApplyOf(String key) {
    if (_lastApply?.documents.contains(key) ?? false) _lastApply = null;
  }

  /// A merge view built from an older server copy than [hash] (null: the
  /// server deleted it) is dropped, so Compare and Take server read the
  /// server's copy now; the document stays listed as changed on the server.
  void _forgetOldConflict(String key, String? hash) {
    final ConflictView? view = conflicts[key];
    if (view == null || view.server?.hash == hash) return;
    conflicts.remove(key);
    stale.add(key);
  }

  /// Documents in a change set not answered yet.
  Set<String> _inFlight(ServerBinding server) => <String>{for (final PendingChange change in server.pending) ...change.documents};

  /// A document changed or went on the server, whatever changed it: the
  /// library takes the new summary at once; a loaded copy unchanged here is
  /// read again (one batch read per event), one changed here is marked
  /// stale before anyone applies, and a deleted one goes. News of a document
  /// in a change set still in flight waits for its answer ([_recheckHeld]).
  Future<void> _catalogEvent(Map<String, Object?> event) async {
    final ServerBinding? server = binding;
    if (server == null) return;
    final Set<String> inFlight = _inFlight(server);
    final _Heard heard = _Heard(stale.length);
    for (final Object? raw in (event['documents'] as List<Object?>?) ?? const <Object?>[]) {
      final CatalogEntry? entry = CatalogEntry.fromJson(raw);
      if (entry == null) continue;
      _catalog.upsert(entry);
      if (_ownWrite(entry.key, entry.hash)) continue;
      _forgetApplyOf(entry.key);
      _forgetOldConflict(entry.key, entry.hash);
      final bool spawnedHere = _ownSpawn(entry.key);
      if (inFlight.contains(entry.key)) {
        _held[entry.key] = entry;
      } else {
        _sortNews(heard, entry.key, entry, server, spawnedHere: spawnedHere);
      }
    }
    for (final Object? raw in (event['deleted'] as List<Object?>?) ?? const <Object?>[]) {
      if (raw is! Map<String, Object?>) continue;
      final String key = '${raw['kind']}:${raw['id']}';
      _catalog.remove(key);
      if (_ownWrite(key, null)) continue;
      _forgetApplyOf(key);
      _forgetOldConflict(key, null);
      if (inFlight.contains(key)) {
        _held[key] = null;
      } else {
        _sortNews(heard, key, null, server);
      }
    }
    await _landNews(heard);
  }

  /// The server news held while a change set had these documents in flight,
  /// checked once its answer landed: the set's own write (or anything older)
  /// is no news, a later write from elsewhere refreshes the copy or marks it
  /// stale at once.
  Future<void> _recheckHeld() async {
    final ServerBinding? server = binding;
    if (server == null || _held.isEmpty) return;
    final Set<String> inFlight = _inFlight(server);
    final _Heard heard = _Heard(stale.length);
    for (final String key in <String>[for (final String key in _held.keys) if (!inFlight.contains(key)) key]) {
      final CatalogEntry? entry = _held.remove(key);
      final ServerBase? base = server.bases[key];
      if (entry == null ? base == null : base != null && (base.hash == entry.hash || entry.revision <= base.revision)) continue;
      _sortNews(heard, key, entry, server);
    }
    await _landNews(heard);
  }

  /// Sorts the server's news of [key] ([entry], null when it was deleted)
  /// into what [heard] reads, refreshes or removes; a draft changed here
  /// is marked stale.
  void _sortNews(_Heard heard, String key, CatalogEntry? entry, ServerBinding server, {bool spawnedHere = false}) {
    final (DocumentKind, String)? parsed = parseDocumentKey(key);
    if (parsed == null) return;
    final ServerBase? base = server.bases[key];
    if (entry == null) {
      if (base == null) return;
      stateOf(parsed.$1, parsed.$2) == DraftState.clean ? heard.gone.add(key) : stale.add(key);
      return;
    }
    if (base == null && store.document(parsed.$1, parsed.$2) == null) heard.added.add(key);
    if (base == null || base.hash == entry.hash) return;
    if (stateOf(parsed.$1, parsed.$2) == DraftState.clean) {
      // A spawn from here rewrote the file: read it again without calling it news.
      (spawnedHere ? heard.quiet : heard.refresh).add(key);
    } else {
      stale.add(key);
    }
  }

  Future<void> _landNews(_Heard heard) async {
    _libraryChanged();
    // Someone else wrote what was changed here: say so, the bar
    // keeps it until Review settles it.
    final int conflicts = stale.length - heard.staleBefore;
    if (conflicts > 0) _notice(NoticeLevel.warning, '${countOf(conflicts, 'change')} ${conflicts == 1 ? 'conflicts' : 'conflict'} with the server. Review to keep yours or take theirs.');
    if (heard.added.isNotEmpty) {
      if (!_disposed) {
        _arrivals.add(<DocumentRef>[
          for (final String key in heard.added)
            if (parseDocumentKey(key) case (final DocumentKind kind, final String id)) DocumentRef(kind, id),
        ]);
      }
      // A document the server gained joins the background read, as at pairing.
      if (backgroundFill && owned) _loader.fill(heard.added);
    }
    if (heard.gone.isEmpty && heard.refresh.isEmpty && heard.quiet.isEmpty) return;
    await _removeGone(heard.gone);
    if (heard.quiet.isNotEmpty) await _refresh(heard.quiet);
    final int refreshed = heard.refresh.isEmpty ? heard.gone.length : await _refresh(heard.refresh) + heard.gone.length;
    if (refreshed > 0) _notice(NoticeLevel.info, 'Updated ${countOf(refreshed, 'file')} from the server.');
    _libraryChanged();
  }
}

/// What one batch of server news asks for: copies to read again (quietly
/// after a spawn from here), copies to remove, documents to read for the
/// first time, and how many drafts were stale before it.
class _Heard {
  _Heard(this.staleBefore);

  final int staleBefore;
  final List<String> refresh = <String>[];
  final List<String> quiet = <String>[];
  final List<String> gone = <String>[];
  final List<String> added = <String>[];
}
