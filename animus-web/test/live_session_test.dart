import 'dart:convert';

import 'package:animus_editor/logic/change_sets.dart';
import 'package:animus_editor/logic/gui/gui_json.dart';
import 'package:animus_editor/logic/json_merge.dart';
import 'package:animus_editor/logic/shared_schemas.dart';
import 'package:animus_editor/model/animus_document.dart';
import 'package:animus_editor/model/document_kind.dart';
import 'package:animus_editor/model/server_binding.dart';
import 'package:animus_editor/services/server/server_connection.dart';
import 'package:animus_editor/services/server/transport.dart';
import 'package:animus_editor/state/editor_store.dart';
import 'package:animus_editor/state/live_session.dart';
import 'package:animus_editor/state/workspace_repository.dart';
import 'package:crypto/crypto.dart';
import 'package:test/test.dart';

import 'server_connection_test.dart' show Waits, profile, settle, token;
import 'support/fake_transport.dart';

/// A tiny editor-v1 server over [FakeNetwork]: stored documents with
/// revisions and hashes, all-or-nothing applies, idempotency and undo.
class MiniServer {
  final Map<String, Map<String, Object?>> documents = <String, Map<String, Object?>>{};
  final Map<String, Map<String, Object?>> results = <String, Map<String, Object?>>{};
  final Map<String, Map<String, Map<String, Object?>?>> receipts = <String, Map<String, Map<String, Object?>?>>{};
  int applied = 0;
  String? previewId;

  void put(DocumentKind kind, String id, Map<String, Object?> source, {int? revision}) {
    final String key = documentKey(kind, id);
    final int next = revision ?? (((documents[key]?['revision'] as int?) ?? 0) + 1);
    final Map<String, Object?> stored = <String, Object?>{...source, if (revisionedKinds.contains(kind)) 'revision': next};
    documents[key] = <String, Object?>{
      'kind': kind.wireName,
      'id': id,
      'path': '${kind.folder}/$id.json',
      'revision': next,
      'hash': 'sha256:${sha256.convert(utf8.encode(jsonEncode(stored)))}',
      'source': stored,
    };
  }

  Map<String, Object?> version(Map<String, Object?> document) =>
      <String, Object?>{for (final String field in <String>['kind', 'id', 'path', 'revision', 'hash']) field: document[field]};

  ApiResponse handle(ApiRequest request) {
    final Uri uri = Uri.parse(request.path);
    final List<String> segments = uri.pathSegments;
    if (request.method == 'GET' && uri.path == '/v1/catalog') {
      return ApiResponse(200, <String, Object?>{'documents': <Object?>[for (final Map<String, Object?> document in documents.values) version(document)], 'next': null});
    }
    if (request.method == 'GET' && segments.length >= 4 && segments[1] == 'documents') {
      final Map<String, Object?>? document = documents['${segments[2]}:${segments.sublist(3).join('/')}'];
      return document == null
          ? const ApiResponse(404, <String, Object?>{'error': <String, Object?>{'code': 'not-found', 'message': 'none'}})
          : ApiResponse(200, document);
    }
    if (request.method == 'POST' && uri.path == '/v1/validate') {
      final List<Object?> changes = (request.body! as Map<String, Object?>)['documents']! as List<Object?>;
      final List<Object?> results = <Object?>[];
      bool valid = true;
      for (final Object? raw in changes) {
        final Map<String, Object?> change = raw! as Map<String, Object?>;
        final Object? name = (change['source'] as Map<String, Object?>?)?['name'];
        final bool bad = name is String && name.length > 16;
        valid &= !bad;
        results.add(<String, Object?>{
          'kind': change['kind'],
          'id': change['id'],
          'diagnostics': <Object?>[if (bad) <String, Object?>{'severity': 'error', 'path': r'$.name', 'message': 'name is longer than 16'}],
        });
      }
      return ApiResponse(200, <String, Object?>{'valid': valid, 'documents': results});
    }
    if (request.method == 'POST' && uri.path == '/v1/previews') {
      previewId = 'prev_000000000001';
      return ApiResponse(200, <String, Object?>{
        'previewId': previewId,
        'viewer': '00000000-0000-0000-0000-000000000001',
        'expiresAt': '2026-11-04T12:15:00Z',
        'npcs': <Object?>[
          for (final Object? raw in (request.body! as Map<String, Object?>)['documents']! as List<Object?>)
            if ((raw! as Map<String, Object?>)['kind'] == 'npc') (raw as Map<String, Object?>)['id'],
        ],
        'hidden': <Object?>[
          for (final Object? raw in (request.body! as Map<String, Object?>)['documents']! as List<Object?>)
            if ((raw! as Map<String, Object?>)['kind'] == 'npc') (raw as Map<String, Object?>)['id'],
        ],
      });
    }
    if (request.method == 'POST' && uri.path == '/v1/changes') return _apply(request.body! as Map<String, Object?>);
    if (request.method == 'GET' && segments.length == 3 && segments[1] == 'changes') {
      final Map<String, Object?>? result = results[segments[2]];
      return ApiResponse(200, <String, Object?>{'idempotencyKey': segments[2], 'status': result == null ? 'unknown' : result['status'], 'result': ?result});
    }
    if (request.method == 'POST' && segments.length == 4 && segments[3] == 'undo') {
      final String key = (request.body! as Map<String, Object?>)['idempotencyKey']! as String;
      final Map<String, Map<String, Object?>?> receipt = receipts[segments[2]]!;
      final List<Object?> written = <Object?>[];
      receipt.forEach((String documentKey, Map<String, Object?>? previous) {
        final (DocumentKind, String) parsed = parseDocumentKey(documentKey)!;
        if (previous == null) {
          documents.remove(documentKey);
          written.add(<String, Object?>{'kind': parsed.$1.wireName, 'id': parsed.$2, 'revision': 0, 'hash': null});
        } else {
          put(parsed.$1, parsed.$2, Map<String, Object?>.of(previous['source']! as Map<String, Object?>)..remove('revision'));
          written.add(<String, Object?>{'kind': parsed.$1.wireName, 'id': parsed.$2, 'revision': documents[documentKey]!['revision'], 'hash': documents[documentKey]!['hash']});
        }
      });
      final Map<String, Object?> result = <String, Object?>{'status': 'applied', 'changeId': 'chg_${(++applied).toString().padLeft(8, '0')}', 'idempotencyKey': key, 'documents': written};
      results[key] = result;
      return ApiResponse(200, result);
    }
    return const ApiResponse(404, <String, Object?>{'error': <String, Object?>{'code': 'not-found', 'message': 'no'}});
  }

  ApiResponse _apply(Map<String, Object?> body) {
    final String key = body['idempotencyKey']! as String;
    if (results.containsKey(key)) return ApiResponse(results[key]!['status'] == 'applied' ? 200 : 409, results[key]);
    final List<Map<String, Object?>> changes = <Map<String, Object?>>[for (final Object? raw in body['documents']! as List<Object?>) raw! as Map<String, Object?>];
    final List<Object?> conflicts = <Object?>[];
    for (final Map<String, Object?> change in changes) {
      final Map<String, Object?>? current = documents['${change['kind']}:${change['id']}'];
      if (current?['hash'] != change['baseHash']) {
        conflicts.add(<String, Object?>{
          'kind': change['kind'],
          'id': change['id'],
          'baseRevision': change['baseRevision'],
          'baseHash': change['baseHash'],
          'current': current == null ? null : <String, Object?>{'revision': current['revision'], 'hash': current['hash'], 'source': current['source']},
        });
      }
    }
    if (conflicts.isNotEmpty) {
      final Map<String, Object?> result = <String, Object?>{'status': 'conflict', 'idempotencyKey': key, 'conflicts': conflicts};
      results[key] = result;
      return ApiResponse(409, result);
    }
    final String changeId = 'chg_${(++applied).toString().padLeft(8, '0')}';
    final Map<String, Map<String, Object?>?> receipt = <String, Map<String, Object?>?>{};
    final List<Object?> written = <Object?>[];
    for (final Map<String, Object?> change in changes) {
      final DocumentKind kind = DocumentKind.parse(change['kind']! as String)!;
      final String id = change['id']! as String;
      final String documentKey = '${kind.wireName}:$id';
      receipt[documentKey] = documents[documentKey];
      if (change['delete'] == true) {
        final int revision = documents.remove(documentKey)!['revision']! as int;
        written.add(<String, Object?>{'kind': kind.wireName, 'id': id, 'revision': revision, 'hash': null});
      } else {
        put(kind, id, Map<String, Object?>.of(change['source']! as Map<String, Object?>)..remove('revision'));
        written.add(<String, Object?>{'kind': kind.wireName, 'id': id, 'revision': documents[documentKey]!['revision'], 'hash': documents[documentKey]!['hash']});
      }
    }
    receipts[changeId] = receipt;
    final Map<String, Object?> result = <String, Object?>{'status': 'applied', 'changeId': changeId, 'idempotencyKey': key, 'documents': written};
    results[key] = result;
    return ApiResponse(200, result);
  }
}

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
    store = EditorStore(repository: MemoryWorkspaceRepository(), schemas: SharedSchemas.embedded, newId: () => 'ws-1');
    await store.openServerWorkspace('server-${profile.id}', 'Town', ServerBinding(serverId: profile.id, fingerprint: profile.fingerprint));
    session = LiveSession(store: store, connection: connection, serverId: profile.id, newKey: () => 'key-${(keys++).toString().padLeft(20, '0')}');
    session.start();
    await connection.connect();
    await settle();
  });

  tearDown(() async {
    await session.dispose();
    await connection.dispose();
  });

  Future<void> edit(String id, Map<String, Object?> Function(Map<String, Object?> json) change) async {
    final AnimusDocument document = store.document(DocumentKind.npc, id)!;
    await store.editSource(DocumentKind.npc, id, prettyJson(change(Map<String, Object?>.of(document.json! as Map<String, Object?>))));
  }

  test('should load the catalog when online and open documents with their server base', () async {
    expect(session.catalog.map((CatalogEntry entry) => entry.id), <String>['town/guard', 'town/baker']);
    await session.openDocuments(session.catalog);
    expect(store.workspace!.documents.length, 2);
    expect(store.workspace!.server!.bases['npc:town/guard']!.revision, 1);
    expect(session.stateOf(DocumentKind.npc, 'town/guard'), DraftState.clean);
    expect(store.document(DocumentKind.npc, 'town/guard')!.path, 'npcs/town/guard.json');
  });

  test('should open the skin document an NPC names once, quietly, with its server base', () async {
    final String hash = 'ab' * 32;
    final Map<String, Object?> skin = <String, Object?>{'signature': 'sig', 'skinName': 'x' * 36, 'textureRaw': 'e30='};
    server.put(DocumentKind.skin, hash, skin);
    final List<LiveNotice> notices = <LiveNotice>[];
    session.notices.listen(notices.add);
    final int before = network.sent.length;
    await Future.wait(<Future<void>>[session.openSkin(hash), session.openSkin(hash)]);
    await session.openSkin(hash);
    expect(store.document(DocumentKind.skin, hash)!.json, skin);
    expect(store.workspace!.server!.bases['skin:$hash'], isNotNull);
    expect(network.sent.length - before, 1, reason: 'one GET for the skin');
    expect(notices, isEmpty);
    await session.openSkin('cd' * 32);
    expect(store.document(DocumentKind.skin, 'cd' * 32), isNull, reason: 'the server has no such skin');
  });

  test('should apply a change set and move the base to the new revision', () async {
    await session.openDocuments(session.catalog);
    await edit('town/guard', (Map<String, Object?> json) => json..['name'] = 'Captain');
    expect(session.drafts.single.state, DraftState.modified);
    await session.apply(message: 'rename');
    expect(server.documents['npc:town/guard']!['revision'], 2);
    expect((server.documents['npc:town/guard']!['source']! as Map<String, Object?>)['name'], 'Captain');
    expect(store.workspace!.server!.bases['npc:town/guard']!.revision, 2);
    expect(store.workspace!.server!.bases['npc:town/guard']!.hash, server.documents['npc:town/guard']!['hash']);
    expect(session.drafts, isEmpty);
    expect(store.workspace!.server!.history.single.changeId, 'chg_00000001');
    expect(store.workspace!.server!.pending, isEmpty);
  });

  test('should show server diagnostics from validation per document', () async {
    await session.openDocuments(session.catalog);
    await edit('town/guard', (Map<String, Object?> json) => json..['name'] = 'A very long name indeed');
    expect(await session.validate(), isFalse);
    expect(session.serverDiagnostics['npc:town/guard']!.single.path, r'$.name');
  });

  test('should start a preview with the focused NPC even when unchanged', () async {
    await session.openDocuments(session.catalog);
    await session.startPreview(focus: <String>{'npc:town/baker'});
    expect(session.preview!.running, isTrue);
    expect(session.preview!.npcs, <String>['town/baker']);
    network.lastStream.emit(<String, Object?>{'type': 'preview', 'previewId': server.previewId, 'state': 'player-left'});
    await settle();
    expect(session.preview!.state, 'player-left');
  });

  test('should report the live NPCs a running preview hides and clear them when it ends', () async {
    await session.openDocuments(session.catalog);
    await session.startPreview(focus: <String>{'npc:town/baker'});
    expect(session.preview!.hidden, <String>['town/baker']);
    expect(session.preview!.hiddenLine, 'Live NPC hidden for you while previewing: town/baker');
    await session.endPreview();
    expect(session.preview!.running, isFalse);
    expect(session.preview!.hiddenLine, isNull);
  });

  test('should stop on a conflict, merge non-overlapping fields and apply on the server revision', () async {
    await session.openDocuments(session.catalog);
    await edit('town/guard', (Map<String, Object?> json) => json..['name'] = 'Captain');
    server.put(DocumentKind.npc, 'town/guard', <String, Object?>{'schemaVersion': 1, 'id': 'guard', 'group': 'town', 'name': 'Guard', 'spawned': false});
    await session.apply();
    expect(server.documents['npc:town/guard']!['revision'], 2, reason: 'nothing written');
    final ConflictView view = session.conflicts['npc:town/guard']!;
    expect(view.server!.revision, 2);
    expect(view.merge.clean, isTrue);
    await session.resolve('npc:town/guard');
    final Map<String, Object?> draft = store.document(DocumentKind.npc, 'town/guard')!.json! as Map<String, Object?>;
    expect(draft['name'], 'Captain');
    expect(draft['spawned'], isFalse);
    expect(store.workspace!.server!.bases['npc:town/guard']!.revision, 2);
    await session.apply();
    expect(server.documents['npc:town/guard']!['revision'], 3);
    expect(session.conflicts, isEmpty);
  });

  test('should let the user pick a side for a field both changed', () async {
    await session.openDocuments(session.catalog);
    await edit('town/guard', (Map<String, Object?> json) => json..['name'] = 'Mine');
    server.put(DocumentKind.npc, 'town/guard', <String, Object?>{'schemaVersion': 1, 'id': 'guard', 'group': 'town', 'name': 'Theirs', 'spawned': true});
    await session.apply();
    expect(session.conflicts['npc:town/guard']!.merge.conflicts.single.path, r'$.name');
    session.choose('npc:town/guard', r'$.name', MergeSide.server);
    await session.resolve('npc:town/guard');
    expect((store.document(DocumentKind.npc, 'town/guard')!.json! as Map<String, Object?>)['name'], 'Theirs');
    expect(session.drafts, isEmpty, reason: 'taking the server side leaves nothing to apply');
  });

  test('should learn after a dropped connection that the change set applied', () async {
    await session.openDocuments(session.catalog);
    await edit('town/guard', (Map<String, Object?> json) => json..['name'] = 'Captain');
    network.dropAfter = (ApiRequest request) => request.path == '/v1/changes';
    await session.apply();
    expect(store.workspace!.server!.pending, isNotEmpty);
    await settle();
    expect(connection.status.path, ConnectionPath.relay);
    await settle();
    expect(store.workspace!.server!.pending, isEmpty);
    expect(store.workspace!.server!.bases['npc:town/guard']!.revision, 2);
    expect(session.drafts, isEmpty);
    expect(session.log.map((LiveNotice notice) => notice.message), contains(startsWith('Applied 1 document')));
  });

  test('should settle a change set still being written at reconnect from its change event', () async {
    await session.openDocuments(session.catalog);
    await edit('town/guard', (Map<String, Object?> json) => json..['name'] = 'Captain');
    ApiRequest? held;
    network.handler = serverHandler(rest: (ApiRequest request) {
      if (request.method == 'POST' && request.path == '/v1/changes' && held == null) {
        // Queued on the server's file lane; the answer is lost with the connection.
        held = request;
        return const ApiResponse(503, <String, Object?>{'error': <String, Object?>{'code': 'busy', 'message': 'lost'}});
      }
      if (request.path.startsWith('/v1/changes/') && server.results.isEmpty) {
        return ApiResponse(200, <String, Object?>{'idempotencyKey': request.path.split('/').last, 'status': 'pending'});
      }
      return server.handle(request);
    });
    network.dropAfter = (ApiRequest request) => request.path == '/v1/changes';
    await session.apply();
    await settle();
    expect(connection.status.online, isTrue);
    expect(session.log.map((LiveNotice notice) => notice.message), contains(contains('still being written')));
    expect(store.workspace!.server!.pending, isNotEmpty);
    final ApiResponse finished = server.handle(held!);
    network.lastStream.emit(<String, Object?>{'type': 'change', 'result': finished.body});
    await settle();
    expect(store.workspace!.server!.pending, isEmpty);
    expect(store.workspace!.server!.bases['npc:town/guard']!.revision, 2);
    expect(session.drafts, isEmpty);
    expect(server.applied, 1, reason: 'the change set was never sent twice');
  });

  test('should explain an event stream closed at its limit, reconnect and refetch what changed meanwhile', () async {
    await session.openDocuments(session.catalog);
    final int catalogLoads = network.sent.where(((ConnectionPath, ApiRequest) sent) => sent.$2.path == '/v1/catalog').length;
    // Events the editor never read: the server dropped them with the stream.
    server.put(DocumentKind.npc, 'town/baker', <String, Object?>{'schemaVersion': 1, 'id': 'baker', 'group': 'town', 'name': 'Night Baker'});
    final FakeSubscription stream = network.lastStream;
    stream.emit(<String, Object?>{'type': 'closing', 'reason': 'limit'});
    await stream.end('limit');
    await settle();
    final List<String> messages = <String>[for (final LiveNotice notice in session.log) notice.message];
    expect(messages.where((String message) => message.contains('fell too far behind')), hasLength(1), reason: 'one notice per close');
    expect(connection.status.online, isTrue);
    expect(network.streams.length, greaterThan(1), reason: 'a new event stream is open');
    expect(network.sent.where(((ConnectionPath, ApiRequest) sent) => sent.$2.path == '/v1/catalog').length, greaterThan(catalogLoads));
    expect((store.document(DocumentKind.npc, 'town/baker')!.json! as Map<String, Object?>)['name'], 'Night Baker');
    expect(session.stateOf(DocumentKind.npc, 'town/baker'), DraftState.clean);
  });

  test('should report a change set that never reached the server after a reconnect', () async {
    await session.openDocuments(session.catalog);
    await edit('town/guard', (Map<String, Object?> json) => json..['name'] = 'Captain');
    network.down.add(ConnectionPath.direct);
    await session.apply();
    await settle();
    expect(connection.status.online, isTrue);
    expect(store.workspace!.server!.pending, isEmpty);
    expect(session.drafts.single.state, DraftState.modified);
    expect(session.log.map((LiveNotice notice) => notice.message), contains(contains('never reached the server')));
  });

  test('should refresh a clean document and mark an edited one stale on catalog events', () async {
    await session.openDocuments(session.catalog);
    await edit('town/guard', (Map<String, Object?> json) => json..['name'] = 'Captain');
    server
      ..put(DocumentKind.npc, 'town/guard', <String, Object?>{'schemaVersion': 1, 'id': 'guard', 'group': 'town', 'name': 'Sentry'})
      ..put(DocumentKind.npc, 'town/baker', <String, Object?>{'schemaVersion': 1, 'id': 'baker', 'group': 'town', 'name': 'Master Baker'});
    network.lastStream.emit(<String, Object?>{
      'type': 'catalog',
      'documents': <Object?>[server.version(server.documents['npc:town/guard']!), server.version(server.documents['npc:town/baker']!)],
    });
    await settle();
    expect((store.document(DocumentKind.npc, 'town/baker')!.json! as Map<String, Object?>)['name'], 'Master Baker');
    expect(session.stateOf(DocumentKind.npc, 'town/baker'), DraftState.clean);
    expect(session.stale, <String>{'npc:town/guard'});
    expect((store.document(DocumentKind.npc, 'town/guard')!.json! as Map<String, Object?>)['name'], 'Captain');
    await session.merge('npc:town/guard');
    expect(session.conflicts['npc:town/guard']!.merge.conflicts.single.path, r'$.name');
  });

  test('should undo an applied change set through the server', () async {
    await session.openDocuments(session.catalog);
    await edit('town/guard', (Map<String, Object?> json) => json..['name'] = 'Captain');
    await session.apply();
    final String changeId = store.workspace!.server!.history.single.changeId;
    await session.undo(changeId);
    expect((server.documents['npc:town/guard']!['source']! as Map<String, Object?>)['name'], 'Guard');
    expect((store.document(DocumentKind.npc, 'town/guard')!.json! as Map<String, Object?>)['name'], 'Guard');
    expect(store.workspace!.server!.history.first.undone, isTrue);
    expect(session.drafts, isEmpty);
  });

  test('should send deletions and creations in one change set', () async {
    await session.openDocuments(session.catalog);
    await store.commit(documents: <DocumentChange>[
      const DocumentChange(DocumentKind.npc, 'town/baker', null),
      DocumentChange(DocumentKind.npc, 'town/smith', prettyJson(<String, Object?>{'schemaVersion': 1, 'id': 'smith', 'group': 'town', 'name': 'Smith'})),
    ]);
    expect(session.drafts.map((DraftChange draft) => draft.state), unorderedEquals(<DraftState>[DraftState.created, DraftState.deleted]));
    await session.apply();
    expect(server.documents.containsKey('npc:town/baker'), isFalse);
    expect(server.documents.containsKey('npc:town/smith'), isTrue);
    expect(store.workspace!.server!.bases.keys, unorderedEquals(<String>['npc:town/guard', 'npc:town/smith']));
    expect(session.drafts, isEmpty);
  });

  test('should persist the binding with the workspace', () async {
    await session.openDocuments(session.catalog);
    final WorkspaceSnapshot decoded = WorkspaceSnapshot.decode(store.workspace!.encode());
    expect(decoded.server!.bases['npc:town/guard']!.hash, store.workspace!.server!.bases['npc:town/guard']!.hash);
  });
}
