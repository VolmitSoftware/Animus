import 'package:animus_editor/logic/change_sets.dart';
import 'package:animus_editor/logic/json_merge.dart';
import 'package:animus_editor/model/animus_document.dart';
import 'package:animus_editor/model/document_kind.dart';
import 'package:animus_editor/model/server_binding.dart';
import 'package:test/test.dart';

AnimusDocument doc(DocumentKind kind, String id, Object? json) =>
    AnimusDocument(kind: kind, id: id, path: '${kind.folder}/$id.json', source: '$json', json: json, diagnostics: const <Diagnostic>[]);

void main() {
  group('mergeJson', () {
    final Map<String, Object?> base = <String, Object?>{'name': 'Guard', 'spawned': true, 'position': <String, Object?>{'x': 1, 'y': 64}, 'tags': <Object?>['a']};

    test('should take each side\'s change to different fields without a conflict', () {
      final MergeResult result = mergeJson(
        base,
        <String, Object?>{...base, 'name': 'Captain'},
        <String, Object?>{...base, 'position': <String, Object?>{'x': 5, 'y': 64}},
      );
      expect(result.clean, isTrue);
      expect(result.merged, <String, Object?>{'name': 'Captain', 'spawned': true, 'position': <String, Object?>{'x': 5, 'y': 64}, 'tags': <Object?>['a']});
    });

    test('should merge nested objects key by key', () {
      final MergeResult result = mergeJson(
        base,
        <String, Object?>{...base, 'position': <String, Object?>{'x': 2, 'y': 64}},
        <String, Object?>{...base, 'position': <String, Object?>{'x': 1, 'y': 70}},
      );
      expect(result.clean, isTrue);
      expect((result.merged! as Map<String, Object?>)['position'], <String, Object?>{'x': 2, 'y': 70});
    });

    test('should report a conflict with its path when both sides change one field differently', () {
      final MergeResult result = mergeJson(base, <String, Object?>{...base, 'name': 'Mine'}, <String, Object?>{...base, 'name': 'Theirs'});
      expect(result.conflicts.single.path, r'$.name');
      expect(result.conflicts.single.mine, 'Mine');
      expect(result.conflicts.single.server, 'Theirs');
      expect(resolveMerge(result, const <String, MergeSide>{}), containsPair('name', 'Mine'));
      expect(resolveMerge(result, const <String, MergeSide>{r'$.name': MergeSide.server}), containsPair('name', 'Theirs'));
    });

    test('should treat lists as one value', () {
      final MergeResult result = mergeJson(base, <String, Object?>{...base, 'tags': <Object?>['a', 'b']}, <String, Object?>{...base, 'tags': <Object?>['c']});
      expect(result.conflicts.single.path, r'$.tags');
    });

    test('should accept the same change on both sides once', () {
      final MergeResult result = mergeJson(base, <String, Object?>{...base, 'name': 'Same'}, <String, Object?>{...base, 'name': 'Same'});
      expect(result.clean, isTrue);
      expect((result.merged! as Map<String, Object?>)['name'], 'Same');
    });

    test('should keep deletions from one side and report delete against edit', () {
      final Map<String, Object?> mine = Map<String, Object?>.of(base)..remove('spawned');
      final MergeResult removed = mergeJson(base, mine, base);
      expect((removed.merged! as Map<String, Object?>).containsKey('spawned'), isFalse);
      final MergeResult conflicting = mergeJson(base, mine, <String, Object?>{...base, 'spawned': false});
      expect(conflicting.conflicts.single.path, r'$.spawned');
      expect(isAbsent(conflicting.conflicts.single.mine), isTrue);
      expect(resolveMerge(conflicting, const <String, MergeSide>{r'$.spawned': MergeSide.server}), containsPair('spawned', false));
      expect((resolveMerge(conflicting, const <String, MergeSide>{})! as Map<String, Object?>).containsKey('spawned'), isFalse);
    });

    test('should quote keys that are not identifiers in paths', () {
      final MergeResult result = mergeJson(
        <String, Object?>{'modules': <String, Object?>{'town:shop': 1}},
        <String, Object?>{'modules': <String, Object?>{'town:shop': 2}},
        <String, Object?>{'modules': <String, Object?>{'town:shop': 3}},
      );
      expect(result.conflicts.single.path, r'$.modules["town:shop"]');
    });
  });

  group('pendingDrafts', () {
    const ServerBase guardBase = ServerBase(revision: 7, hash: 'sha256:aa', path: 'npcs/town/guard.json', source: <String, Object?>{'name': 'Guard'});
    const ServerBase goneBase = ServerBase(revision: 2, hash: 'sha256:bb', path: 'npcs/town/gone.json', source: <String, Object?>{'name': 'Gone'});
    const ServerBinding binding = ServerBinding(serverId: 'abc', fingerprint: 'sha256:x', bases: <String, ServerBase>{'npc:town/guard': guardBase, 'npc:town/gone': goneBase});

    test('should list modified, created and deleted drafts with their bases', () {
      final List<DraftChange> drafts = pendingDrafts(<AnimusDocument>[
        doc(DocumentKind.npc, 'town/guard', <String, Object?>{'name': 'Captain'}),
        doc(DocumentKind.npc, 'town/new', <String, Object?>{'name': 'New'}),
      ], binding);
      expect(drafts.map((DraftChange draft) => draft.state), <DraftState>[DraftState.modified, DraftState.created, DraftState.deleted]);
      expect(drafts[0].change, <String, Object?>{'kind': 'npc', 'id': 'town/guard', 'baseRevision': 7, 'baseHash': 'sha256:aa', 'source': <String, Object?>{'name': 'Captain'}});
      expect(drafts[1].change!['baseRevision'], isNull);
      expect(drafts[1].change!['baseHash'], isNull);
      expect(drafts[2].change, <String, Object?>{'kind': 'npc', 'id': 'town/gone', 'baseRevision': 2, 'baseHash': 'sha256:bb', 'delete': true});
    });

    test('should leave out drafts equal to their base regardless of key order', () {
      final List<DraftChange> drafts = pendingDrafts(<AnimusDocument>[
        doc(DocumentKind.npc, 'town/guard', <String, Object?>{'name': 'Guard'}),
        doc(DocumentKind.npc, 'town/gone', <String, Object?>{'name': 'Gone'}),
      ], binding);
      expect(drafts, isEmpty);
    });

    test('should block a draft that is not a JSON object', () {
      final List<DraftChange> drafts = pendingDrafts(<AnimusDocument>[doc(DocumentKind.npc, 'town/guard', null), doc(DocumentKind.npc, 'town/gone', <String, Object?>{'name': 'Gone'})], binding);
      expect(drafts.single.problem, isNotNull);
      expect(drafts.single.change, isNull);
    });
  });

  group('recordApplied', () {
    test('should move bases to the new revisions, drop deleted ones, clear the pending entry and record history', () {
      const ServerBinding binding = ServerBinding(
        serverId: 'abc',
        fingerprint: 'sha256:x',
        bases: <String, ServerBase>{
          'npc:town/guard': ServerBase(revision: 7, hash: 'sha256:aa', path: 'npcs/town/guard.json', source: <String, Object?>{'revision': 7}),
          'npc:town/gone': ServerBase(revision: 2, hash: 'sha256:bb', path: 'npcs/town/gone.json', source: <String, Object?>{}),
        },
        pending: <PendingChange>[],
      );
      final PendingChange pending = PendingChange(idempotencyKey: 'key-0000000000000000', documents: const <String>['npc:town/guard', 'npc:town/gone'], sentAt: DateTime.utc(2026));
      final ChangeOutcome? outcome = ChangeOutcome.parse(<String, Object?>{
        'status': 'applied',
        'changeId': 'chg_00000001',
        'idempotencyKey': 'key-0000000000000000',
        'documents': <Object?>[
          <String, Object?>{'kind': 'npc', 'id': 'town/guard', 'revision': 8, 'hash': 'sha256:cc'},
          <String, Object?>{'kind': 'npc', 'id': 'town/gone', 'revision': 3, 'hash': null},
        ],
      });
      final ServerBinding next = recordApplied(
        binding.copyWith(pending: <PendingChange>[pending]),
        outcome! as ChangeApplied,
        <String, ({String path, Object? source})>{
          'npc:town/guard': (path: 'npcs/town/guard.json', source: withServerRevision(DocumentKind.npc, <String, Object?>{'revision': 7, 'name': 'Captain'}, 8)),
        },
        at: DateTime.utc(2026),
      );
      expect(next.bases.keys, <String>['npc:town/guard']);
      expect(next.bases['npc:town/guard']!.revision, 8);
      expect(next.bases['npc:town/guard']!.hash, 'sha256:cc');
      expect(next.bases['npc:town/guard']!.source, <String, Object?>{'revision': 8, 'name': 'Captain'});
      expect(next.pending, isEmpty);
      expect(next.history.single.changeId, 'chg_00000001');
      final ServerBinding undone = recordApplied(next, const ChangeApplied('key-1111111111111111', 'chg_00000002', <AppliedDocument>[]), const <String, ({String path, Object? source})>{}, undoOf: 'chg_00000001');
      expect(undone.history.first.undone, isTrue);
      expect(undone.history.last.undoOf, 'chg_00000001');
    });

    test('should not set revision on kinds the server does not revision', () {
      expect(withServerRevision(DocumentKind.menu, <String, Object?>{'a': 1}, 4), <String, Object?>{'a': 1});
    });

    test('should parse conflicts with the server version and invalid answers with diagnostics', () {
      final ChangeOutcome? conflict = ChangeOutcome.parse(<String, Object?>{
        'status': 'conflict',
        'idempotencyKey': 'k',
        'conflicts': <Object?>[
          <String, Object?>{'kind': 'npc', 'id': 'town/guard', 'baseRevision': 7, 'baseHash': 'sha256:aa', 'current': <String, Object?>{'revision': 9, 'hash': 'sha256:dd', 'source': <String, Object?>{'name': 'X'}}},
          <String, Object?>{'kind': 'npc', 'id': 'town/gone', 'baseRevision': 2, 'baseHash': 'sha256:bb', 'current': null},
        ],
      });
      expect((conflict! as ChangeConflicted).conflicts.first.current!.revision, 9);
      expect((conflict as ChangeConflicted).conflicts.last.current, isNull);
      final ChangeOutcome? invalid = ChangeOutcome.parse(<String, Object?>{
        'status': 'invalid',
        'idempotencyKey': 'k',
        'documents': <Object?>[
          <String, Object?>{'kind': 'npc', 'id': 'town/guard', 'diagnostics': <Object?>[<String, Object?>{'severity': 'error', 'path': r'$.name', 'message': 'too long'}]},
        ],
      });
      expect((invalid! as ChangeInvalid).diagnostics['npc:town/guard']!.single.path, r'$.name');
    });
  });

  test('should make 24-character base64url idempotency keys', () {
    final String key = newIdempotencyKey();
    expect(key, matches(RegExp(r'^[A-Za-z0-9_-]{24}$')));
    expect(newIdempotencyKey(), isNot(key));
  });
}
