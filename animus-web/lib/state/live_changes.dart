part of 'live_session.dart';

/// How the last apply or server undo ended, for the save bar: what it
/// wrote, or why it did not, while no edit has been made since.
class _ApplyRecord {
  const _ApplyRecord(this.edits, {this.count = 0, this.failure, this.retry = true, this.changeId, this.undo = false, this.documents = const <String>{}});

  /// [EditorStore.editCount] when it ended.
  final int edits;
  final int count;
  final String? failure;

  /// Sending the same change set again could go through.
  final bool retry;

  /// The change set the server wrote; null when it did not go through.
  final String? changeId;

  /// It undid an earlier apply rather than applying drafts.
  final bool undo;

  /// The documents it wrote (`kind:id`): a write from elsewhere to one of
  /// them makes it old news.
  final Set<String> documents;
}

/// The save bar's reason for a change set too large for the relay.
const String _relayFailure = 'Too large for the relay.';

/// Validation, previews, change sets, their recovery and merges.
extension LiveChanges on LiveSession {
  /// What went wrong with [response], in words; a missing permission comes
  /// with the pair command that adds it to this token's own.
  ErrorCopy _problemOf(ApiResponse response) =>
      response.errorCode == 'scope-missing' ? scopeMissingCopy(response.missingScope, held: connection.token?.scopes) : response.problem;

  /// True, after saying so, when [body] goes over the relay and does not fit
  /// in one frame; [verb] is what fewer documents would let the user do.
  bool _tooLargeForRelay(Object? body, String verb) {
    if (connection.status.path != ConnectionPath.relay || fitsRelayFrame(body)) return false;
    _notice(NoticeLevel.error, tooLargeForRelay(verb));
    return true;
  }

  List<Map<String, Object?>> _changes(List<DraftChange> drafts) =>
      <Map<String, Object?>>[for (final DraftChange draft in drafts) if (draft.change != null) draft.change!];

  /// Validates every changed draft (or [only]) on the server. [quiet] when
  /// it runs on the way to an apply: a pass is logged, not toasted.
  Future<bool> validate({Set<String>? only, bool quiet = false}) async =>
      await _run<bool>('Validating', () async {
        await store.settled();
        final ServerBinding? server = binding;
        final WorkspaceSnapshot? workspace = store.workspace;
        if (server == null || workspace == null) return false;
        final List<DraftChange> drafts = _withFocus(only == null ? this.drafts : pendingDrafts(workspace.documents, server, only: only), only);
        final List<Map<String, Object?>> changes = _changes(drafts);
        if (changes.isEmpty) {
          _notice(NoticeLevel.info, 'Nothing to validate.');
          return true;
        }
        final Map<String, Object?> request = <String, Object?>{'documents': changes};
        if (_tooLargeForRelay(request, 'check')) {
          // On the way to an apply, the bar says why nothing was sent.
          if (quiet) _lastApply = _ApplyRecord(store.editCount, failure: _relayFailure);
          return false;
        }
        final ApiResponse? response = await _call(ApiRequest('POST', '/v1/validate', body: request), 'validate');
        if (response == null) return false;
        final Object? body = response.body;
        if (!response.ok || body is! Map<String, Object?>) {
          _notice(NoticeLevel.error, 'Could not validate. ${_problemOf(response).text}');
          return false;
        }
        for (final DraftChange draft in drafts) {
          serverDiagnostics.remove(draft.key);
        }
        int errors = 0;
        for (final Object? raw in (body['documents'] as List<Object?>?) ?? const <Object?>[]) {
          if (raw is! Map<String, Object?>) continue;
          final List<Diagnostic> diagnostics = diagnosticsOf(raw['diagnostics']);
          if (diagnostics.isNotEmpty) _diagnose('${raw['kind']}:${raw['id']}', diagnostics);
          errors += diagnostics.where((Diagnostic diagnostic) => diagnostic.severity == DiagnosticSeverity.error).length;
        }
        final bool valid = body['valid'] == true;
        _notice(valid ? NoticeLevel.success : NoticeLevel.warning, validationLine(valid, changes.length, errors), toast: !(valid && quiet));
        _libraryChanged();
        return valid;
      }) ??
      false;

  /// The changed drafts, plus the focused documents even when unchanged (a
  /// preview of an NPC as stored is still a preview).
  List<DraftChange> _withFocus(List<DraftChange> drafts, Set<String>? only) {
    if (only == null) return drafts;
    final ServerBinding? server = binding;
    final List<DraftChange> result = List<DraftChange>.of(drafts);
    for (final String key in only) {
      if (result.any((DraftChange draft) => draft.key == key)) continue;
      final (DocumentKind, String)? parsed = parseDocumentKey(key);
      final ServerBase? base = server?.bases[key];
      final AnimusDocument? document = parsed == null ? null : store.document(parsed.$1, parsed.$2);
      if (parsed == null || base == null || document == null || document.json is! Map<String, Object?>) continue;
      result.add(DraftChange(key, parsed.$1, parsed.$2, DraftState.clean, <String, Object?>{
        'kind': parsed.$1.wireName,
        'id': parsed.$2,
        'baseRevision': base.revision,
        'baseHash': base.hash,
        'source': document.json,
      }));
    }
    return result;
  }

  /// Shows the changed drafts (and [focus]) to the token's player only, or
  /// replaces the running preview. Focused documents not read yet are read
  /// first.
  Future<void> startPreview({Set<String>? focus}) => _run<void>('Previewing', () async {
        if (focus != null) {
          await ensureLoaded(<DocumentRef>[
            for (final String key in focus)
              if (parseDocumentKey(key) case (final DocumentKind kind, final String id)) DocumentRef(kind, id),
          ]);
        }
        await store.settled();
        final ServerBinding? server = binding;
        final WorkspaceSnapshot? workspace = store.workspace;
        if (server == null || workspace == null) return;
        final List<DraftChange> drafts = _withFocus(this.drafts, focus);
        final List<Map<String, Object?>> changes = <Map<String, Object?>>[
          for (final Map<String, Object?> change in _changes(drafts))
            if (change['delete'] != true) change,
        ];
        if (changes.isEmpty) {
          _notice(NoticeLevel.info, 'Open or change an NPC to preview it.');
          return;
        }
        final PreviewState? running = preview;
        final Map<String, Object?> request = <String, Object?>{
          if (running != null && running.running) 'previewId': running.id,
          'documents': changes,
        };
        if (_tooLargeForRelay(request, 'preview')) return;
        final ApiResponse? response = await _call(ApiRequest('POST', '/v1/previews', body: request), 'start the preview');
        if (response == null) return;
        final Object? body = response.body;
        if (!response.ok || body is! Map<String, Object?>) {
          final List<Diagnostic> diagnostics = _errorDiagnostics(response);
          if (diagnostics.isNotEmpty && drafts.length == 1) _diagnose(drafts.first.key, diagnostics);
          if (response.errorCode == 'not-found') preview = null;
          _notice(NoticeLevel.error, 'Could not preview. ${_problemOf(response).text}');
          return;
        }
        preview = PreviewState(
          '${body['previewId']}',
          'running',
          DateTime.tryParse('${body['expiresAt']}'),
          <String>[for (final Object? id in (body['npcs'] as List<Object?>?) ?? const <Object?>[]) '$id'],
          <String>[for (final Object? id in (body['hidden'] as List<Object?>?) ?? const <Object?>[]) '$id'],
        );
        for (final Object? raw in (body['documents'] as List<Object?>?) ?? const <Object?>[]) {
          if (raw is Map<String, Object?>) _diagnose('${raw['kind']}:${raw['id']}', diagnosticsOf(raw['diagnostics']));
        }
        _notice(NoticeLevel.success, 'Previewing ${preview!.npcs.length} NPC${preview!.npcs.length == 1 ? '' : 's'} for your player only.');
      });

  List<Diagnostic> _errorDiagnostics(ApiResponse response) {
    final Object? body = response.body;
    if (body is! Map<String, Object?> || body['error'] is! Map<String, Object?>) return const <Diagnostic>[];
    return diagnosticsOf((body['error']! as Map<String, Object?>)['diagnostics']);
  }

  Future<void> endPreview() => _run<void>('Ending preview', () async {
        final PreviewState? running = preview;
        if (running == null) return;
        final ApiResponse? response = await _call(ApiRequest('DELETE', '/v1/previews/${Uri.encodeComponent(running.id)}'), 'end the preview');
        if (response == null) return;
        // 404: the server already ended it (expired, or the player left).
        if (!response.ok && response.status != 404) {
          _notice(NoticeLevel.error, 'The preview is still running. ${response.problem.text}');
          return;
        }
        // The preview event may have ended it (and said so) already.
        final bool announced = !(preview?.running ?? false);
        preview = PreviewState(running.id, 'ended', running.expiresAt, running.npcs, running.hidden);
        if (!announced) _notice(NoticeLevel.info, 'The preview ended.');
      });

  /// Sends every changed draft as one change set. Nothing is written unless
  /// all of them still match their server base. True when the server applied
  /// it; the change set is only sent once its pending record is stored, so a
  /// lost answer can always be asked for again.
  Future<bool> apply({String? message}) async =>
      await _run<bool>('Applying', () async {
        // Edits still being saved go too.
        await store.settled();
        final ServerBinding? server = binding;
        final WorkspaceSnapshot? workspace = store.workspace;
        if (server == null || workspace == null) return false;
        final List<DraftChange> drafts = this.drafts;
        final List<DraftChange> blocked = <DraftChange>[for (final DraftChange draft in drafts) if (draft.problem != null) draft];
        if (blocked.isNotEmpty) {
          _notice(NoticeLevel.error, 'Fix ${blocked.map((DraftChange draft) => draft.id).join(', ')} first: ${blocked.first.problem}');
          _lastApply = _ApplyRecord(store.editCount, failure: 'fix ${blocked.first.id} first');
          return false;
        }
        if (drafts.isEmpty) {
          _notice(NoticeLevel.info, 'No changes to apply.');
          return false;
        }
        final String key = _newKey();
        final Map<String, Object?> request = <String, Object?>{
          'idempotencyKey': key,
          'documents': _changes(drafts),
          if (message != null && message.isNotEmpty) 'message': message,
        };
        if (_tooLargeForRelay(request, 'apply')) {
          _lastApply = _ApplyRecord(store.editCount, failure: _relayFailure);
          return false;
        }
        final PendingChange pending = PendingChange(
          idempotencyKey: key,
          // A move's old key is in flight too: its catalog deletion settles
          // with the answer.
          documents: <String>[
            for (final DraftChange draft in drafts) ...<String>[draft.key, if (draft.from != null) documentKey(draft.kind, draft.from!)],
          ],
          sentAt: _now().toUtc(),
          message: message,
        );
        if (!await _recordPending(pending)) return false;
        final Map<String, ({String path, Object? source})> sent = <String, ({String path, Object? source})>{
          for (final DraftChange draft in drafts)
            if (draft.change?['source'] != null)
              draft.key: (path: store.document(draft.kind, draft.id)?.path ?? documentPath(draft.kind, draft.id), source: draft.change!['source']),
        };
        bool applied = false;
        await _settling(key, () async {
          final ApiResponse? response = await _call(ApiRequest('POST', '/v1/changes', body: request), 'apply');
          if (response == null) {
            _lastApply = _ApplyRecord(store.editCount, failure: 'not connected');
            return;
          }
          applied = await _outcome(response, pending, sent);
        });
        return applied;
      }) ??
      false;

  /// Stores [pending] in the workspace and reports whether it is there now.
  /// The store keeps the other tab's copy when two tabs write at once; a
  /// change set whose record was lost that way is never sent.
  Future<bool> _recordPending(PendingChange pending) async {
    await store.commit(server: (ServerBinding binding) => binding.copyWith(pending: <PendingChange>[...binding.pending, pending]), undoable: false);
    if (_pendingOf(pending.idempotencyKey) != null) return true;
    _notice(NoticeLevel.error, 'Nothing was sent: the change record could not be stored. Reload and try again.');
    return false;
  }

  Future<void> _settling(String key, Future<void> Function() work) async {
    _answering.add(key);
    try {
      await work();
    } finally {
      _answering.remove(key);
    }
  }

  /// Settles [pending] from the server's answer, then the server news held
  /// while it was in flight. True when it applied.
  Future<bool> _outcome(ApiResponse response, PendingChange pending, Map<String, ({String path, Object? source})>? sent) async {
    final bool applied = await _settle(response, pending, sent);
    await _recheckHeld();
    return applied;
  }

  Future<bool> _settle(ApiResponse response, PendingChange pending, Map<String, ({String path, Object? source})>? sent) async {
    final ChangeOutcome? outcome = ChangeOutcome.parse(response.body);
    final int count = switch (outcome) {
      ChangeConflicted(:final List<ServerConflict> conflicts) => conflicts.length,
      ChangeInvalid(:final Map<String, List<Diagnostic>> diagnostics) => diagnostics.length,
      _ => 0,
    };
    final String documents = countOf(count, 'document');
    switch (outcome) {
      case final ChangeApplied applied:
        await _applied(applied, pending, sent);
        _lastApply = _ApplyRecord(
          store.editCount,
          count: applied.documents.length,
          changeId: applied.changeId,
          undo: pending.undoOf != null,
          documents: <String>{for (final AppliedDocument document in applied.documents) '${document.kind.wireName}:${document.id}'},
        );
        return true;
      case final ChangeConflicted conflicted when pending.undoOf != null:
        // The documents moved on after that apply: undoing it would throw
        // the later edit away, so the server kept them. There is no draft of
        // the undo to merge; refresh the clean copies instead.
        await _dropPending(pending.idempotencyKey);
        _notice(NoticeLevel.warning, 'Undo stopped: $documents changed on the server since. Nothing was written. '
            'Edit ${conflicted.conflicts.map((ServerConflict conflict) => conflict.id).join(', ')} by hand.');
        _lastApply = _ApplyRecord(store.editCount, failure: '$documents changed on the server');
        await _refresh(<String>[for (final ServerConflict conflict in conflicted.conflicts) conflict.key]);
      case final ChangeConflicted conflicted:
        await _dropPending(pending.idempotencyKey);
        _conflicts(conflicted.conflicts);
        _notice(NoticeLevel.warning, 'Apply stopped: $documents changed on the server. Merge below, then apply again.');
        _lastApply = _ApplyRecord(store.editCount, failure: '$documents changed on the server');
      case final ChangeInvalid invalid:
        await _dropPending(pending.idempotencyKey);
        invalid.diagnostics.forEach(_diagnose);
        _notice(NoticeLevel.error, 'Not applied: $documents have errors. Fix the marked fields, then apply again.');
        _lastApply = _ApplyRecord(store.editCount, failure: '$documents have errors');
        _libraryChanged();
      case null when response.errorCode == 'counter-replayed':
        // A browser resends a request whose connection closed before the
        // answer, under the same counter. The first copy may have applied:
        // ask by the key rather than report a refusal.
        return await _check(pending) ?? false;
      case null:
        if (response.status >= 500 || response.errorCode == 'busy' || response.errorCode == 'rate-limited') {
          _notice(NoticeLevel.warning, '${response.problem.cause} Change set kept; checked on next connect.');
          _lastApply = _ApplyRecord(store.editCount, failure: response.problem.cause);
          return false;
        }
        final ErrorCopy problem = _problemOf(response);
        await _dropPending(pending.idempotencyKey);
        _notice(NoticeLevel.error, 'The server refused the change set. ${problem.text}');
        _lastApply = _ApplyRecord(store.editCount, failure: problem.cause, retry: response.errorCode != 'scope-missing');
    }
    return false;
  }

  Future<void> _dropPending(String key) => store.commit(
        server: (ServerBinding binding) => binding.copyWith(
          pending: <PendingChange>[for (final PendingChange change in binding.pending) if (change.idempotencyKey != key) change],
        ),
        undoable: false,
      );

  Future<void> _applied(ChangeApplied applied, PendingChange pending, Map<String, ({String path, Object? source})>? sent) async {
    for (final AppliedDocument document in applied.documents) {
      _written[document.key] = document.hash;
      if (document.from != null) _written[documentKey(document.kind, document.from!)] = null;
    }
    if (sent == null || pending.undoOf != null) {
      // No local copy of what was written (a recovered or undone change
      // set): take the server's versions. What it removed (a delete, or the
      // id a move left) is gone there, so the local copy goes without a
      // read that could only answer not found.
      final List<DocumentChange> gone = <DocumentChange>[
        for (final AppliedDocument document in applied.documents) ...<DocumentChange>[
          if (document.hash == null && store.document(document.kind, document.id) != null) DocumentChange(document.kind, document.id, null),
          if (document.from != null && store.document(document.kind, document.from!) != null) DocumentChange(document.kind, document.from!, null),
        ],
      ];
      await store.commit(
        documents: gone,
        server: (ServerBinding binding) => recordApplied(binding, applied, const <String, ({String path, Object? source})>{}, undoOf: pending.undoOf, at: _now().toUtc()),
        undoable: false,
      );
      await _refresh(<String>[
        for (final AppliedDocument document in applied.documents)
          if (document.hash != null) document.key,
      ], replace: true);
    } else {
      final Map<String, ({String path, Object? source})> stored = <String, ({String path, Object? source})>{};
      final List<DocumentChange> documents = <DocumentChange>[];
      for (final AppliedDocument document in applied.documents) {
        final ({String path, Object? source})? copy = sent[document.key];
        if (copy == null || document.hash == null) continue;
        final Object? source = withServerRevision(document.kind, copy.source, document.revision);
        stored[document.key] = (path: copy.path, source: source);
        // Laid over the draft, so an edit made during the apply stays.
        documents.add(DocumentChange.update(document.kind, document.id, (Object? latest) => rebaseJson(copy.source, source, latest)));
      }
      await store.commit(
        documents: documents,
        server: (ServerBinding binding) => recordApplied(binding, applied, stored, at: _now().toUtc()),
        undoable: false,
      );
    }
    for (final AppliedDocument document in applied.documents) {
      serverDiagnostics.remove(document.key);
      stale.remove(document.key);
      conflicts.remove(document.key);
    }
    _notice(NoticeLevel.success, appliedLine(applied, undo: pending.undoOf != null), toast: false);
    _libraryChanged();
  }

  /// Undoes an applied change set through the server's rollback receipt.
  Future<void> undo(String changeId) => _run<void>('Undoing', () async {
        final ServerBinding? server = binding;
        if (server == null) return;
        final AppliedChange? change = server.history.where((AppliedChange entry) => entry.changeId == changeId).firstOrNull;
        final String key = _newKey();
        final PendingChange pending = PendingChange(idempotencyKey: key, documents: change?.documents ?? const <String>[], sentAt: _now().toUtc(), undoOf: changeId);
        if (!await _recordPending(pending)) return;
        await _settling(key, () async {
          final ApiResponse? response = await _call(
            ApiRequest('POST', '/v1/changes/${Uri.encodeComponent(changeId)}/undo', body: <String, Object?>{'idempotencyKey': key}),
            'undo',
          );
          if (response != null) await _outcome(response, pending, null);
        });
      });

  /// Spawns or despawns NPCs (`group/id`) on the server, one request each
  /// with the same checks as the commands; needs the `spawn` scope. A
  /// rate-limited request waits out the server's hint and goes again.
  /// Returns how many the server accepted and says which it refused.
  Future<int> spawnNpcs(List<String> ids, {required bool spawn}) async =>
      await _run<int>(spawn ? 'Spawning' : 'Despawning', () async {
        int accepted = 0;
        int sent = 0;
        final List<String> refused = <String>[];
        ErrorCopy? reason;
        for (final String id in ids) {
          // Noted before sending: the server's catalog event can come before its answer.
          final String key = documentKey(DocumentKind.npc, id);
          _spawnedHere[key] = _now();
          final ApiResponse? response = await _call(
            ApiRequest('POST', '/v1/npcs/${Uri.encodeComponent(id)}/${spawn ? 'spawn' : 'despawn'}'),
            spawn ? 'spawn $id' : 'despawn $id',
            retry: true,
          );
          // The connection failed: _call said so, and the rest are not sent.
          if (response == null) {
            _spawnedHere.remove(key);
            break;
          }
          sent++;
          if (response.ok) {
            accepted++;
          } else {
            _spawnedHere.remove(key);
            refused.add(id);
            reason ??= _problemOf(response);
          }
        }
        _notice(refused.isEmpty && sent == ids.length ? NoticeLevel.success : NoticeLevel.warning,
            spawnLine(spawn: spawn, total: ids.length, sent: sent, accepted: accepted, refused: refused, reason: reason));
        return accepted;
      }) ??
      0;

  /// Asks the server what became of every unanswered change set.
  Future<void> recoverPending() async {
    final ServerBinding? server = binding;
    if (server == null || !owned) return;
    pendingProblem = null;
    for (final PendingChange pending in List<PendingChange>.of(server.pending)) {
      if (await _check(pending) == null) return;
    }
  }

  /// Asks the server about one unanswered change set and settles it. True
  /// when it applied; null when the connection failed on the way.
  Future<bool?> _check(PendingChange pending) async {
    final ApiResponse? response = await _call(ApiRequest('GET', '/v1/changes/${Uri.encodeComponent(pending.idempotencyKey)}'), 'check a change set', retry: true);
    if (response == null) return null;
    final Object? body = response.body;
    if (!response.ok || body is! Map<String, Object?>) {
      pendingProblem = response.problem;
      _notice(NoticeLevel.warning, 'Could not check an earlier change set. ${response.problem.cause} Check again, or stop waiting.');
      return false;
    }
    // A change event may have settled it while the status was on its way.
    if (_pendingOf(pending.idempotencyKey) == null) return false;
    switch (body['status']) {
      case 'applied' || 'conflict' || 'invalid':
        return _outcome(ApiResponse(200, body['result']), pending, null);
      case 'unknown':
        await _dropPending(pending.idempotencyKey);
        _notice(NoticeLevel.warning, 'An earlier change set never reached the server. Apply again.');
      default:
        _notice(NoticeLevel.info, 'A change set is still being written on the server.');
    }
    return false;
  }

  /// Asks again about every unanswered change set, from the live bar.
  Future<void> checkPending() => _run<void>('Checking', recoverPending);

  /// Gives up on unanswered change sets whose status cannot be read. The
  /// drafts stay; a later apply sends them again against the current bases.
  Future<void> forgetPending() => _run<void>('Forgetting', () async {
        await store.commit(server: (ServerBinding binding) => binding.copyWith(pending: const <PendingChange>[]), undoable: false);
        pendingProblem = null;
        await _recheckHeld();
        _notice(NoticeLevel.info, 'Stopped waiting. Review your changes before applying again.');
      });

  PendingChange? _pendingOf(String key) {
    for (final PendingChange change in binding?.pending ?? const <PendingChange>[]) {
      if (change.idempotencyKey == key) return change;
    }
    return null;
  }

  /// A `change` event for a change set whose answer was lost settles it
  /// like that answer would have.
  Future<void> _changeEvent(Object? result) async {
    if (result is! Map<String, Object?>) return;
    final Object? key = result['idempotencyKey'];
    if (key is! String || _answering.contains(key)) return;
    final PendingChange? pending = _pendingOf(key);
    if (pending == null) return;
    await _settling(key, () => _outcome(ApiResponse(200, result), pending, null));
  }

  void _conflicts(List<ServerConflict> found) {
    final ServerBinding? server = binding;
    for (final ServerConflict conflict in found) {
      final ServerBase? base = server?.bases[conflict.key];
      final AnimusDocument? draft = store.document(conflict.kind, conflict.id);
      final Object? mine = draft == null ? absentValue : draft.json;
      final ServerBase? current = conflict.current == null
          ? null
          : ServerBase(revision: conflict.current!.revision, hash: conflict.current!.hash, path: base?.path ?? draft?.path ?? documentPath(conflict.kind, conflict.id), source: conflict.current!.source);
      conflicts[conflict.key] = ConflictView(
        key: conflict.key,
        kind: conflict.kind,
        id: conflict.id,
        base: base,
        mine: mine,
        server: current,
        merge: mergeJson(base == null ? absentValue : base.source, mine, current == null ? absentValue : current.source),
      );
    }
    _changed();
  }

  /// Builds the merge view for a stale draft from the server's version now.
  Future<void> merge(String key) => _run<void>('Merging', () async {
        final (DocumentKind, String)? parsed = parseDocumentKey(key);
        if (parsed == null) return;
        final Map<String, FetchedDocument>? read = await _readNow(<String>[key]);
        if (read == null) return;
        _conflicts(<ServerConflict>[ServerConflict(parsed.$1, parsed.$2, read[key]?.base)]);
      });

  /// Writes the merge result as the draft and moves its base to the server's
  /// version, so the next apply sends it against that revision. With
  /// [dropDraft] the server's version (or its deletion) is taken whole.
  Future<void> resolve(String key, {bool dropDraft = false}) async {
    final ConflictView? view = conflicts[key];
    if (view == null) return;
    if (!owned) {
      _notice(NoticeLevel.warning, LiveSession.otherTab);
      return;
    }
    final ServerBase? current = view.server;
    final Object? merged = dropDraft ? (current?.source) : resolveMerge(view.merge, view.choices);
    final bool remove = merged == null || isAbsent(merged);
    await store.commit(
      documents: <DocumentChange>[
        DocumentChange(view.kind, view.id, remove ? null : prettyJson(merged), path: current?.path ?? view.base?.path),
      ],
      server: (ServerBinding binding) {
        final Map<String, ServerBase> next = Map<String, ServerBase>.of(binding.bases);
        if (current == null) {
          next.remove(key);
        } else {
          next[key] = current;
        }
        return binding.copyWith(bases: next);
      },
    );
    conflicts.remove(key);
    stale.remove(key);
    final String name = store.libraryEntry(view.kind, view.id)?.name ?? view.id;
    _notice(NoticeLevel.info, dropDraft ? 'Took the server version of $name.' : 'Merged $name. Apply to send it.');
    _libraryChanged();
  }

  void choose(String key, String path, MergeSide side) {
    conflicts[key]?.choices[path] = side;
    _changed();
  }
}
