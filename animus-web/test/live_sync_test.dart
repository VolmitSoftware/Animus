import 'dart:async';

import 'package:animus_editor/logic/gui/gui_json.dart';
import 'package:animus_editor/logic/shared_schemas.dart';
import 'package:animus_editor/model/animus_document.dart';
import 'package:animus_editor/model/document_kind.dart';
import 'package:animus_editor/model/server_binding.dart';
import 'package:animus_editor/services/server/server_connection.dart';
import 'package:animus_editor/services/server/transport.dart';
import 'package:animus_editor/state/catalog_entry.dart';
import 'package:animus_editor/state/editor_store.dart';
import 'package:animus_editor/state/live_session.dart';
import 'package:animus_editor/state/save_state.dart';
import 'package:test/test.dart';

import 'live_session_test.dart' show HeldRepository, MiniServer;
import 'server_connection_test.dart' show Waits, profile, settle, token;
import 'support/fake_transport.dart';

void main() {
  late MiniServer server;
  late FakeNetwork network;
  late ServerConnection connection;
  late EditorStore store;
  late LiveSession session;
  int keys = 0;

  setUp(() async {
    server = MiniServer()
      ..put(DocumentKind.npc, 'town/guard', <String, Object?>{'schemaVersion': 1, 'id': 'guard', 'group': 'town', 'name': 'Guard', 'spawned': true})
      ..put(DocumentKind.npc, 'town/baker', <String, Object?>{'schemaVersion': 1, 'id': 'baker', 'group': 'town', 'name': 'Baker'});
    network = FakeNetwork(serverHandler(rest: (ApiRequest request) => server.handle(request)));
    connection = ServerConnection(profile: profile, token: token, open: network.open, delay: Waits().call);
    store = EditorStore(repository: HeldRepository(), schemas: SharedSchemas.embedded, newId: () => 'ws-1');
    await store.openServerWorkspace('server-${profile.id}', 'Town', ServerBinding(serverId: profile.id, fingerprint: profile.fingerprint));
    session = LiveSession(store: store, connection: connection, serverId: profile.id, newKey: () => 'key-${(keys++).toString().padLeft(20, '0')}');
    session.start();
    await connection.connect();
    await settle();
    await session.ensureLoaded(<DocumentRef>[for (final CatalogEntry entry in session.catalog) DocumentRef(entry.kind, entry.id)]);
  });

  tearDown(() async {
    await session.dispose();
    await connection.dispose();
  });

  Map<String, Object?> json(String id) => store.document(DocumentKind.npc, id)!.json! as Map<String, Object?>;

  Future<void> edit(String id, Map<String, Object?> Function(Map<String, Object?> json) change) async {
    final AnimusDocument document = store.document(DocumentKind.npc, id)!;
    await store.editSource(DocumentKind.npc, id, prettyJson(change(Map<String, Object?>.of(document.json! as Map<String, Object?>))));
  }

  /// Answers every change set with [response] instead of applying it.
  void refuseChanges(ApiResponse response) {
    final FakeHandler inner = network.handler;
    network.handler = (ApiRequest request) async => request.path == '/v1/changes' ? response : inner(request);
  }

  /// The server changes [id] the way a console edit does, and says so.
  void consoleEdit(String id, Map<String, Object?> Function(Map<String, Object?> source) change) {
    final Map<String, Object?> source = Map<String, Object?>.of(server.documents['npc:$id']!['source']! as Map<String, Object?>)..remove('revision');
    server.put(DocumentKind.npc, id, change(source));
    network.lastStream.emit(<String, Object?>{'type': 'catalog', 'documents': <Object?>[server.version(server.documents['npc:$id']!)]});
  }

  Map<String, Object?> stored(String id) => server.documents['npc:$id']!['source']! as Map<String, Object?>;

  group('undo after a change on the server', () {
    test('should take back only the editor\'s own change, and apply keeps the console\'s', () async {
      await edit('town/guard', (Map<String, Object?> json) => json..['name'] = 'Captain');
      expect(await session.apply(), isTrue);
      consoleEdit('town/guard', (Map<String, Object?> source) => source..['spawned'] = false);
      await settle();
      expect(json('town/guard')['spawned'], isFalse, reason: 'the refresh landed');
      final HistoryMove? undone = await store.undo();
      expect(undone?.refused, isNull);
      expect(json('town/guard')['name'], 'Guard');
      expect(json('town/guard')['spawned'], isFalse);
      expect(await session.apply(), isTrue);
      expect(stored('town/guard')['name'], 'Guard');
      expect(stored('town/guard')['spawned'], isFalse, reason: 'the console\'s despawn survives');
    });

    test('should refuse to undo a field the server changed too, and write nothing', () async {
      await edit('town/guard', (Map<String, Object?> json) => json..['name'] = 'Captain');
      expect(await session.apply(), isTrue);
      consoleEdit('town/guard', (Map<String, Object?> source) => source..['name'] = 'Sentry');
      await settle();
      final HistoryMove? undone = await store.undo();
      expect(undone?.refused, "Can't undo: Sentry changed on the server.");
      expect(json('town/guard')['name'], 'Sentry');
      expect(session.drafts, isEmpty, reason: 'nothing to apply');
    });
  });

  group('a cached server copy', () {
    test('should be dropped when a newer server change arrives, so Take server takes the newest', () async {
      await edit('town/guard', (Map<String, Object?> json) => json..['name'] = 'Captain');
      consoleEdit('town/guard', (Map<String, Object?> source) => source..['name'] = 'Sentry');
      await settle();
      await session.merge('npc:town/guard');
      expect((session.conflicts['npc:town/guard']!.server!.source! as Map<String, Object?>)['name'], 'Sentry');
      consoleEdit('town/guard', (Map<String, Object?> source) => source..['name'] = 'Warden');
      await settle();
      expect(session.conflicts.containsKey('npc:town/guard'), isFalse, reason: 'the old copy is gone');
      expect(session.stale, contains('npc:town/guard'), reason: 'still listed as changed on the server');
      await session.merge('npc:town/guard');
      expect((session.conflicts['npc:town/guard']!.server!.source! as Map<String, Object?>)['name'], 'Warden');
      await session.resolve('npc:town/guard', dropDraft: true);
      expect(json('town/guard')['name'], 'Warden');
      expect(session.drafts, isEmpty);
    });

    test('should be kept while the server copy it holds is still the newest', () async {
      await edit('town/guard', (Map<String, Object?> json) => json..['name'] = 'Captain');
      consoleEdit('town/guard', (Map<String, Object?> source) => source..['name'] = 'Sentry');
      await settle();
      await session.merge('npc:town/guard');
      final ConflictView view = session.conflicts['npc:town/guard']!;
      network.lastStream.emit(<String, Object?>{'type': 'catalog', 'documents': <Object?>[server.version(server.documents['npc:town/guard']!)]});
      await settle();
      expect(session.conflicts['npc:town/guard'], same(view));
    });
  });

  group('server changes during an apply', () {
    /// Holds each change set's answer after the server wrote it, while the
    /// server sends its own event and then [during] runs, as a console edit
    /// made in the meantime does.
    void holdAnswer(Future<void> Function() during) {
      final FakeHandler inner = network.handler;
      network.handler = (ApiRequest request) async {
        final ApiResponse response = await inner(request);
        if (request.path != '/v1/changes' || !response.ok) return response;
        network.lastStream.emit(<String, Object?>{'type': 'catalog', 'documents': <Object?>[server.version(server.documents['npc:town/guard']!)]});
        await settle();
        await during();
        return response;
      };
    }

    test('should refresh a copy the server changed again while the apply was in flight', () async {
      await edit('town/guard', (Map<String, Object?> json) => json..['name'] = 'Captain');
      holdAnswer(() async {
        consoleEdit('town/guard', (Map<String, Object?> source) => source..['spawned'] = false);
        await settle();
      });
      expect(await session.apply(), isTrue);
      await settle();
      expect(json('town/guard')['name'], 'Captain');
      expect(json('town/guard')['spawned'], isFalse, reason: 'the console edit made during the apply shows at once');
      expect(session.drafts, isEmpty);
      expect(store.workspace!.server!.bases['npc:town/guard']!.hash, server.documents['npc:town/guard']!['hash']);
    });

    test('should mark a copy edited during the apply as changed on the server', () async {
      await edit('town/guard', (Map<String, Object?> json) => json..['name'] = 'Captain');
      holdAnswer(() async {
        consoleEdit('town/guard', (Map<String, Object?> source) => source..['spawned'] = false);
        await settle();
        unawaited(edit('town/guard', (Map<String, Object?> json) => json..['type'] = 'minecraft:villager'));
      });
      expect(await session.apply(), isTrue);
      await store.settled();
      await settle();
      expect(session.stale, contains('npc:town/guard'));
      expect(json('town/guard')['type'], 'minecraft:villager', reason: 'the edit here is kept');
    });

    test('should take its own write in flight as no news', () async {
      await edit('town/guard', (Map<String, Object?> json) => json..['name'] = 'Captain');
      holdAnswer(() async {});
      final int reads = server.batchReads;
      expect(await session.apply(), isTrue);
      await settle();
      expect(server.batchReads, reads, reason: 'nothing read again');
      expect(session.stale, isEmpty);
      expect(session.log.map((LiveNotice notice) => notice.message), isNot(contains(startsWith('Updated'))));
    });
  });

  group('a revision that goes backwards', () {
    /// Another editor's apply, then its undo on an older server, which wrote
    /// the old file back with its old revision.
    Map<String, Object?> applyThenOldUndo(String name) {
      final Map<String, Object?> before = Map<String, Object?>.of(stored('town/guard'))..remove('revision');
      consoleEdit('town/guard', (Map<String, Object?> source) => source..['name'] = name);
      return before;
    }

    void oldUndo(Map<String, Object?> before, int revision) {
      server.put(DocumentKind.npc, 'town/guard', before, revision: revision);
      network.lastStream.emit(<String, Object?>{'type': 'catalog', 'documents': <Object?>[server.version(server.documents['npc:town/guard']!)]});
    }

    String? listedHash() => session.catalog.firstWhere((CatalogEntry entry) => entry.key == 'npc:town/guard').hash;

    test('should keep an open second editor in step with the server', () async {
      final Map<String, Object?> before = applyThenOldUndo('Captain');
      await settle();
      expect(json('town/guard')['name'], 'Captain');
      oldUndo(before, 1);
      await settle();
      expect(json('town/guard')['name'], 'Guard', reason: 'the undo shows although its revision is lower');
      expect(store.workspace!.server!.bases['npc:town/guard']!.hash, server.documents['npc:town/guard']!['hash']);
      // The next write reuses revision 2 with other content.
      consoleEdit('town/guard', (Map<String, Object?> source) => source..['name'] = 'Sentry');
      await settle();
      expect(server.documents['npc:town/guard']!['revision'], 2);
      expect(json('town/guard')['name'], 'Sentry', reason: 'a reused revision with a new hash is news');
      expect(listedHash(), server.documents['npc:town/guard']!['hash']);
      expect(session.drafts, isEmpty);
    });

    test('should keep the second editor in step through a catalog read that predates the undo', () async {
      final Map<String, Object?> before = applyThenOldUndo('Captain');
      await settle();
      final FakeHandler inner = network.handler;
      network.handler = (ApiRequest request) async {
        final ApiResponse response = await inner(request);
        if (request.path != '/v1/catalog') return response;
        network.handler = inner;
        // The page is read, then the undo lands before the page reaches the editor.
        oldUndo(before, 1);
        await settle();
        return response;
      };
      await session.loadCatalog();
      await settle();
      expect(json('town/guard')['name'], 'Guard');
      expect(store.workspace!.server!.bases['npc:town/guard']!.hash, server.documents['npc:town/guard']!['hash']);
      expect(listedHash(), server.documents['npc:town/guard']!['hash'], reason: 'the list row follows the server too');
    });

    test('should mark a draft changed on the server and list the server copy', () async {
      final Map<String, Object?> before = applyThenOldUndo('Captain');
      await settle();
      await edit('town/guard', (Map<String, Object?> json) => json..['spawned'] = false);
      oldUndo(before, 1);
      await settle();
      expect(session.stale, contains('npc:town/guard'));
      expect(listedHash(), server.documents['npc:town/guard']!['hash']);
    });
  });

  group('a change set held after a failed answer', () {
    test('should be retired by a later apply of the same documents', () async {
      await edit('town/guard', (Map<String, Object?> json) => json..['name'] = 'Captain');
      final FakeHandler inner = network.handler;
      int failures = 1;
      network.handler = (ApiRequest request) async {
        if (request.path == '/v1/changes' && failures-- > 0) {
          return const ApiResponse(500, <String, Object?>{'error': <String, Object?>{'code': 'internal', 'message': 'boom'}});
        }
        return inner(request);
      };
      expect(await session.apply(), isFalse);
      expect(store.workspace!.server!.pending, hasLength(1), reason: 'kept to ask about on the next connect');
      expect(await session.apply(), isTrue);
      expect(store.workspace!.server!.pending, isEmpty);
      consoleEdit('town/guard', (Map<String, Object?> source) => source..['spawned'] = false);
      await settle();
      expect(json('town/guard')['spawned'], isFalse, reason: 'no longer waiting on an answer');
      await session.recoverPending();
      expect(session.log.map((LiveNotice notice) => notice.message), isNot(contains(contains('never reached the server'))));
    });
  });

  group('the relay frame limit', () {
    test('should refuse an apply, a check and a preview too large for the relay before sending', () async {
      network.down.add(ConnectionPath.direct);
      await connection.disconnect();
      await connection.connect();
      await settle();
      expect(connection.status.path, ConnectionPath.relay);
      await edit('town/guard', (Map<String, Object?> json) => json..['notes'] = 'x' * (1024 * 1024));
      final int sent = network.sent.length;
      expect(await session.apply(), isFalse);
      expect(session.log.last.message, 'Too large for the relay. Connect directly, or apply fewer documents.');
      expect(session.saveState.reason, 'Too large for the relay.');
      expect(store.workspace!.server!.pending, isEmpty, reason: 'nothing recorded as sent');
      expect(await session.validate(), isFalse);
      expect(session.log.last.message, 'Too large for the relay. Connect directly, or check fewer documents.');
      await session.startPreview(focus: <String>{'npc:town/guard'});
      expect(session.log.last.message, 'Too large for the relay. Connect directly, or preview fewer documents.');
      expect(network.sent.length, sent, reason: 'nothing went over the relay');
    });

    test('should send the same change set over the direct path', () async {
      await edit('town/guard', (Map<String, Object?> json) => json..['notes'] = 'x' * (1024 * 1024));
      expect(await session.apply(), isTrue);
    });
  });

  group('a refused scope', () {
    test('should name the missing permission and the pair command, and offer no Retry', () async {
      await edit('town/guard', (Map<String, Object?> json) => json..['name'] = 'Captain');
      refuseChanges(const ApiResponse(403, <String, Object?>{
        'error': <String, Object?>{'code': 'scope-missing', 'message': 'shop runs commands', 'scope': 'apply:commands'},
      }));
      expect(await session.apply(), isFalse);
      final SaveState state = session.saveState;
      expect(state.phase, SavePhase.failed);
      expect(state.reason, 'This pairing lacks the Run commands permission.');
      expect(state.retry, isFalse);
      expect(session.log.last.message, contains('/animus editor pair scopes=read,apply:commands'));
      expect(store.workspace!.server!.pending, isEmpty, reason: 'a refused set is dropped');
      expect(session.drafts.single.key, 'npc:town/guard', reason: 'the change stays');
    });

    test('should name no permission when an older server leaves the scope out', () async {
      await edit('town/guard', (Map<String, Object?> json) => json..['name'] = 'Captain');
      refuseChanges(const ApiResponse(403, <String, Object?>{'error': <String, Object?>{'code': 'scope-missing', 'message': 'no'}}));
      expect(await session.apply(), isFalse);
      expect(session.saveState.retry, isFalse);
      expect(session.log.last.message, isNot(contains('scopes=')));
    });

    test('should still offer Retry after a refusal a retry can fix', () async {
      await edit('town/guard', (Map<String, Object?> json) => json..['name'] = 'Captain');
      refuseChanges(const ApiResponse(400, <String, Object?>{'error': <String, Object?>{'code': 'invalid-request', 'message': 'no'}}));
      expect(await session.apply(), isFalse);
      expect(session.saveState.phase, SavePhase.failed);
      expect(session.saveState.retry, isTrue);
    });
  });
}
