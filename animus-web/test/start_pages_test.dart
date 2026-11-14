// The plain-Dart parts of the start page, the pair page and the Review
// sheet: the Continue list, the first steps, pairing problems, permissions
// in words, change lines and discards.
import 'package:animus_editor/components/offline/start_rows.dart';
import 'package:animus_editor/components/server/pair_copy.dart';
import 'package:animus_editor/components/server/review_changes.dart';
import 'package:animus_editor/services/server/scope_words.dart';
import 'package:animus_editor/logic/change_sets.dart';
import 'package:animus_editor/model/document_kind.dart';
import 'package:animus_editor/model/server_binding.dart';
import 'package:animus_editor/services/server/error_copy.dart';
import 'package:animus_editor/services/server/server_pairing.dart';
import 'package:animus_editor/services/server/server_profiles.dart';
import 'package:animus_editor/state/editor_store.dart';
import 'package:animus_editor/state/workspace_repository.dart';
import 'package:test/test.dart';

ServerProfile server(String name, String hex) =>
    ServerProfile(fingerprint: 'sha256:${hex * 64}'.substring(0, 'sha256:'.length + 64), serverKey: 'key', name: name, directUrl: 'http://127.0.0.1:9797');

void main() {
  group('continueRows', () {
    test('should list each paired server once, with its workspace, before local workspaces', () {
      final ServerProfile town = server('Town', 'a');
      final ServerProfile hub = server('Hub', 'b');
      final List<WorkspaceSummary> workspaces = <WorkspaceSummary>[
        WorkspaceSummary('server-${town.id}', 'Town', 3, 1169, serverId: town.id),
        const WorkspaceSummary('local-1', 'Example packs', 1, 25),
        const WorkspaceSummary('server-gone', 'Old server', 1, 4, serverId: 'gone'),
      ];
      final List<ContinueRow> rows = continueRows(<ServerProfile>[town, hub], workspaces);
      expect(rows.map((ContinueRow row) => row.name), <String>['Town', 'Hub', 'Example packs', 'Old server']);
      expect(rows[0].workspace?.documentCount, 1169);
      expect(rows[1].workspace, isNull, reason: 'a server paired but never opened still shows');
      expect(rows.where((ContinueRow row) => row.name == 'Town'), hasLength(1), reason: 'a server is never listed twice');
      expect(rows[3].orphan, isTrue);
      expect(rows[2].orphan, isFalse);
    });

    test('should count documents in words', () {
      expect(documentsLine(1), '1 document');
      expect(documentsLine(496), '496 documents');
    });
  });

  group('firstSteps', () {
    test('should tick each step from the workspace and add Apply only on a server', () {
      final List<FirstStep> local = firstSteps(openedNpc: true, changed: false);
      expect(local.map((FirstStep step) => step.label), <String>['Pick an NPC', 'Change something']);
      expect(local.map((FirstStep step) => step.done), <bool>[true, false]);
      final List<FirstStep> live = firstSteps(openedNpc: true, changed: true, applied: true);
      expect(live.last.label, 'Apply');
      expect(live.every((FirstStep step) => step.done), isTrue);
    });
  });

  group('pairProblemOf', () {
    test('should ask for a new link when the link is spent', () {
      expect(pairProblemOf('pairing-invalid', pageOrigin: 'http://127.0.0.1:8870').newLink, isTrue);
      expect(pairProblemOf('pairing-expired', pageOrigin: 'http://127.0.0.1:8870').title, 'This link expired.');
      expect(notALink.newLink, isTrue);
      expect(noLink.newLink, isTrue);
      expect(noLink.title, isNot(notALink.title), reason: 'a page opened without a link is not told its link is wrong');
    });

    test('should name the page address when the server refuses it', () {
      final PairProblem problem = pairProblemOf('origin-not-allowed', pageOrigin: 'https://animus.example');
      expect(problem.next, contains('https://animus.example'));
      expect(problem.next, contains('allowedOrigins'));
      expect(problem.newLink, isFalse, reason: 'the same link works once the address is allowed');
    });

    test('should point at the browser when an https page cannot reach a local server', () {
      final PairProblem blocked = pairProblemOf('unreachable', pageOrigin: 'https://animus.example', directUrl: 'http://127.0.0.1:9797');
      expect(blocked.title, contains('browser blocked'));
      final PairProblem down = pairProblemOf('unreachable', pageOrigin: 'http://127.0.0.1:8870', directUrl: 'http://127.0.0.1:9797');
      expect(down.title, 'The server did not answer.');
      final PairProblem remote = pairProblemOf('no-answer', pageOrigin: 'https://animus.example', directUrl: 'https://mc.example:9797');
      expect(remote.title, 'The server did not answer.');
    });

    test('should word the typed failures the state lane reports', () {
      final PairProblem blocked = pairProblemOf('local-network-blocked', pageOrigin: 'https://animus.example', directUrl: 'http://127.0.0.1:9797');
      expect(blocked.title, contains('browser blocked'));
      expect(blocked.newLink, isFalse);
      expect(pairProblemOf('relay-unavailable', pageOrigin: 'https://animus.example').title, 'The server did not answer.');
      expect(pairProblemOf('pairing-invalid', pageOrigin: 'https://animus.example').title, 'This link was already used.');
    });

    test('should read a refusal through one adapter', () {
      final PairProblem problem = pairProblemFor(PairingRefused('origin-not-allowed', serverCopy('origin-not-allowed')), pageOrigin: 'https://animus.example');
      expect(problem.title, "The server does not accept this editor's address.");
      expect(problem.next, contains('https://animus.example'));
      final PairProblem unknown = pairProblemFor(PairingRefused('internal', serverCopy('internal')), pageOrigin: 'https://animus.example');
      expect(unknown.title, serverCopy('internal').cause);
    });

    test('should word each typed pairing failure', () {
      PairProblem of(PairingFailure failure, {String origin = 'http://127.0.0.1:8870', String? direct}) =>
          pairProblemFor(PairingRefused.because(failure), pageOrigin: origin, directUrl: direct);
      expect(of(PairingFailure.expired).title, 'This link expired.');
      expect(of(PairingFailure.used).newLink, isTrue);
      expect(of(PairingFailure.originNotAllowed, origin: 'https://animus.example').next, contains('https://animus.example'));
      expect(of(PairingFailure.localNetworkBlocked).title, contains('browser blocked'), reason: 'blocked whatever the page address');
      expect(of(PairingFailure.unreachable).title, 'The server did not answer.');
      expect(of(PairingFailure.unreachable, origin: 'https://animus.example', direct: 'http://127.0.0.1:9797').title, contains('browser blocked'));
      expect(of(PairingFailure.relayUnavailable).title, 'The server did not answer.');
      expect(of(PairingFailure.wrongServer).title, 'A different server answered.');
      expect(of(PairingFailure.other).title, pairingFailureCopy(PairingFailure.other).cause);
      expect(pairProblemFor(PairingRefused('rate-limited', serverCopy('rate-limited')), pageOrigin: 'http://x').title, 'The server is busy.',
          reason: 'other codes keep their own words');
    });

    test('should fall back to the server copy for other codes', () {
      final PairProblem other = pairProblemOf('internal', pageOrigin: 'http://x', copy: serverCopy('internal'));
      expect(other.title, serverCopy('internal').cause);
      expect(other.next, serverCopy('internal').next);
    });

    test('should name the browser for the server list', () {
      expect(browserName('Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36'), 'Chrome on macOS');
      expect(browserName('Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:132.0) Gecko/20100101 Firefox/132.0'), 'Firefox on Windows');
      expect(browserName('Mozilla/5.0 (Windows NT 10.0) AppleWebKit/537.36 Chrome/131.0 Safari/537.36 Edg/131.0'), 'Edge on Windows');
      expect(browserName('curl/8'), 'Web editor');
    });

    test('should know local addresses', () {
      for (final String local in <String>['http://127.0.0.1:9797', 'http://localhost', 'http://192.168.1.4:9797', 'http://10.0.0.2', 'http://172.20.1.1']) {
        expect(isLocalAddress(local), isTrue, reason: local);
      }
      for (final String remote in <String>['https://mc.example', 'http://172.40.1.1', 'http://8.8.8.8']) {
        expect(isLocalAddress(remote), isFalse, reason: remote);
      }
      expect(isLocalAddress(null), isFalse);
    });
  });

  group('scopeSentences', () {
    test('should name every permission in words, unknown ones as they are', () {
      expect(scopeSentences(<String>['spawn', 'read', 'apply:gui', 'later:thing']), <String>['See files', 'Edit shops and GUIs', 'Spawn and despawn', 'later:thing']);
      expect(scopeWord('import'), 'Import');
    });
  });

  group('changeLine', () {
    DraftChange draft(DraftState state, {String? from}) => DraftChange('npc:town/baker', DocumentKind.npc, 'town/baker', state, null, from: from);

    test('should say what changed in a few words', () {
      expect(changeLine(draft(DraftState.created)), 'New');
      expect(changeLine(draft(DraftState.deleted)), 'Deleted');
      expect(changeLine(draft(DraftState.moved, from: 'baker')), 'Moved from baker');
      expect(changeLine(draft(DraftState.modified), base: <String, Object?>{'name': 'a', 'type': 'player'}, mine: <String, Object?>{'name': 'b', 'type': 'zombie'}),
          'Changed name and body');
      expect(changeLine(draft(DraftState.modified), base: <String, Object?>{'a': 1}, mine: <String, Object?>{'a': 2, 'skinLayers': 1, 'c': 1, 'd': 1}),
          'Changed a, c and 2 more');
      expect(fieldWord('skinLayers'), 'skin layers');
    });
  });

  group('discardOf', () {
    const ServerBase base = ServerBase(revision: 3, hash: 'sha256:x', path: 'npcs/town/baker.json', source: <String, Object?>{'name': 'Baker'});
    const ServerBinding binding = ServerBinding(serverId: 's', fingerprint: 'f', bases: <String, ServerBase>{'npc:town/baker': base});

    test('should put an edited or deleted document back as the server has it', () {
      for (final DraftState state in <DraftState>[DraftState.modified, DraftState.deleted]) {
        final List<DocumentChange>? changes = discardOf(DraftChange('npc:town/baker', DocumentKind.npc, 'town/baker', state, null), binding);
        expect(changes, hasLength(1));
        expect(changes!.single.source, contains('"Baker"'));
        expect(changes.single.path, 'npcs/town/baker.json');
      }
    });

    test('should remove a new document and move a moved one back', () {
      expect(discardOf(const DraftChange('npc:new', DocumentKind.npc, 'new', DraftState.created, null), binding)!.single.source, isNull);
      final List<DocumentChange> back = discardOf(const DraftChange('npc:market/baker', DocumentKind.npc, 'market/baker', DraftState.moved, null, from: 'town/baker'), binding)!;
      expect(back.map((DocumentChange change) => (change.id, change.source == null)), <(String, bool)>[('market/baker', true), ('town/baker', false)]);
    });

    test('should refuse when the server copy is not stored here', () {
      expect(discardOf(const DraftChange('npc:other', DocumentKind.npc, 'other', DraftState.modified, null), binding), isNull);
    });
  });
}
