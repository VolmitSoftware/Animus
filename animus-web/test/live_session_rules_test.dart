import 'package:animus_editor/logic/change_sets.dart';
import 'package:animus_editor/logic/gui/gui_json.dart';
import 'package:animus_editor/logic/shared_schemas.dart';
import 'package:animus_editor/model/animus_document.dart';
import 'package:animus_editor/model/document_kind.dart';
import 'package:animus_editor/model/server_binding.dart';
import 'package:animus_editor/services/server/connection_status.dart';
import 'package:animus_editor/services/server/error_copy.dart';
import 'package:animus_editor/services/server/server_connection.dart';
import 'package:animus_editor/services/server/transport.dart';
import 'package:animus_editor/state/editor_store.dart';
import 'package:animus_editor/state/live_lines.dart';
import 'package:animus_editor/state/live_session.dart';
import 'package:animus_editor/state/workspace_records.dart';
import 'package:animus_editor/state/workspace_repository.dart';
import 'package:test/test.dart';

import 'live_session_test.dart' show MiniServer;
import 'server_connection_test.dart' show Waits, profile, settle, token;
import 'support/fake_transport.dart';
import 'support/manual_owner.dart';

/// A repository that can refuse saves the way a second tab's write makes it.
class ContestedRepository extends MemoryWorkspaceRepository {
  bool refuse = false;

  @override
  Future<void> save(WorkspaceSnapshot snapshot, {WorkspaceSnapshot? previous, Set<String>? changed}) async {
    if (refuse) throw WorkspaceConflict(snapshot.id, snapshot.generation + 1, snapshot.generation);
    await super.save(snapshot, previous: previous, changed: changed);
  }
}

void main() {
  late MiniServer server;
  late FakeNetwork network;
  late ServerConnection connection;
  late ContestedRepository repository;
  late EditorStore store;
  late ManualOwner owner;
  late LiveSession session;

  /// Answers before [server] when it returns a response.
  ApiResponse? Function(ApiRequest request)? override;
  int keys = 0;

  setUp(() async {
    override = null;
    server = MiniServer()
      ..put(DocumentKind.npc, 'town/guard', <String, Object?>{'schemaVersion': 1, 'id': 'guard', 'group': 'town', 'name': 'Guard'});
    network = FakeNetwork(serverHandler(rest: (ApiRequest request) => override?.call(request) ?? server.handle(request)));
    connection = ServerConnection(profile: profile, token: token, open: network.open, delay: Waits().call);
    repository = ContestedRepository();
    store = EditorStore(repository: repository, schemas: SharedSchemas.embedded, newId: () => 'ws-1');
    await store.openServerWorkspace('server-${profile.id}', 'Town', ServerBinding(serverId: profile.id, fingerprint: profile.fingerprint));
    owner = ManualOwner();
    session = LiveSession(store: store, connection: connection, serverId: profile.id, owner: owner, newKey: () => 'key-${(keys++).toString().padLeft(20, '0')}');
    session.start();
    await connection.connect();
    await settle();
  });

  tearDown(() async {
    await session.dispose();
    await connection.dispose();
  });

  Future<void> rename(String name) async {
    final AnimusDocument document = store.document(DocumentKind.npc, 'town/guard')!;
    await store.editSource(DocumentKind.npc, 'town/guard', prettyJson(<String, Object?>{...document.json! as Map<String, Object?>, 'name': name}));
  }

  List<String> log() => <String>[for (final LiveNotice notice in session.log) notice.message];

  int sentTo(String method, String path) => network.sent.where(((ConnectionPath, ApiRequest) sent) => sent.$2.method == method && sent.$2.path.startsWith(path)).length;

  test('should leave a tab that does not own the session read-only and unconnected, then take over', () async {
    await rename('Captain');
    owner.owned = false;
    await settle();
    expect(connection.status.phase, ConnectionPhase.offline, reason: 'a waiting tab holds no connection or event stream');
    expect(network.streams.where(((ConnectionPath, FakeSubscription) stream) => stream.$2.isOpen), isEmpty);
    expect(await session.apply(), isFalse);
    expect(sentTo('POST', '/v1/changes'), 0);
    expect(log().last, LiveSession.otherTab);
    owner.owned = true;
    await settle();
    expect(log(), contains('This tab now runs live editing for this server.'));
    expect(await session.apply(), isTrue);
  });

  test('should never send a change set whose pending record was not stored', () async {
    await rename('Captain');
    repository.refuse = true;
    expect(await session.apply(), isFalse);
    expect(sentTo('POST', '/v1/changes'), 0);
    expect(log().last, startsWith('Nothing was sent'));
  });

  test('should keep a preview running when ending it fails, and end it when the server already did', () async {
    await session.startPreview(focus: <String>{'npc:town/guard'});
    override = (ApiRequest request) => request.method == 'DELETE'
        ? const ApiResponse(500, <String, Object?>{'error': <String, Object?>{'code': 'internal', 'message': 'boom'}})
        : null;
    await session.endPreview();
    expect(session.preview!.running, isTrue);
    expect(log().last, 'The preview is still running. ${serverErrorCopy['internal']!.text}');
    override = null;
    await session.endPreview();
    expect(session.preview!.running, isFalse, reason: 'a 404 means the server ended it already');
  });

  test('should end a running preview when the session is disposed', () async {
    await session.startPreview(focus: <String>{'npc:town/guard'});
    final int before = sentTo('DELETE', '/v1/previews/');
    await session.dispose();
    expect(sentTo('DELETE', '/v1/previews/'), before + 1);
    // tearDown disposes again; a second dispose must be harmless.
    session = LiveSession(store: store, connection: connection, serverId: profile.id);
  });

  test('should say when a pending change set cannot be checked, and let the user stop waiting', () async {
    await rename('Captain');
    network.dropAfter = (ApiRequest request) => request.path == '/v1/changes';
    await session.apply();
    network.dropAfter = null;
    override = (ApiRequest request) => request.path.startsWith('/v1/changes/')
        ? const ApiResponse(503, <String, Object?>{'error': <String, Object?>{'code': 'busy', 'message': 'busy'}})
        : null;
    connection.retryNow();
    await settle();
    expect(store.workspace!.server!.pending, hasLength(1));
    expect(session.pendingProblem?.cause, serverErrorCopy['busy']!.cause);
    await session.forgetPending();
    expect(store.workspace!.server!.pending, isEmpty);
    expect(session.pendingProblem, isNull);
  });

  test('should not offer the draft as Mine when an undo conflicts', () async {
    await rename('Captain');
    await session.apply();
    final String changeId = store.workspace!.server!.history.last.changeId;
    override = (ApiRequest request) => request.path.endsWith('/undo')
        ? ApiResponse(409, <String, Object?>{
            'status': 'conflict',
            'idempotencyKey': (request.body! as Map<String, Object?>)['idempotencyKey'],
            'conflicts': <Object?>[
              <String, Object?>{'kind': 'npc', 'id': 'town/guard', 'baseRevision': 2, 'baseHash': 'sha256:${'1' * 64}', 'current': null},
            ],
          })
        : null;
    await session.undo(changeId);
    expect(session.conflicts, isEmpty);
    expect(log().where((String line) => line.startsWith('Undo stopped')), hasLength(1));
    expect(store.workspace!.server!.pending, isEmpty);
  });

  test('should ask by the key when a resent apply comes back as a replayed counter', () async {
    await rename('Captain');
    override = (ApiRequest request) {
      if (request.method != 'POST' || request.path != '/v1/changes') return null;
      // The first copy applied; the browser's resend under the same counter is refused.
      server.handle(request);
      return const ApiResponse(409, <String, Object?>{'error': <String, Object?>{'code': 'counter-replayed', 'message': 'replayed'}});
    };
    expect(await session.apply(), isTrue);
    expect(store.workspace!.server!.pending, isEmpty);
    expect(log().last, startsWith('Applied 1 document'));
  });

  test('should explain a refused validation that names no error', () {
    expect(validationLine(false, 2, 0), contains('named no error'));
    expect(validationLine(false, 2, 3), startsWith('The server found 3 errors.'));
    expect(validationLine(true, 1, 0), 'The server accepts 1 document.');
  });

  test('should name an apply or undo in plain words, without the change id', () {
    const ChangeApplied applied = ChangeApplied('key-1', '56d8a4da-1042-4c3e-9e0f-1b2c3d4e5f60', <AppliedDocument>[AppliedDocument(DocumentKind.npc, 'town/baker', 3, 'abc')]);
    expect(appliedLine(applied, undo: false), 'Applied 1 document.');
    expect(appliedLine(applied, undo: true), 'Undid 1 document.');
  });

  test('should not report a spawn the connection cut short as a success', () {
    expect(spawnLine(spawn: true, total: 3, sent: 1, accepted: 1, refused: const <String>[]),
        'Spawned 1 of 3 NPCs. Connection failed with 2 unsent. Select them again after reconnecting.');
    expect(spawnLine(spawn: false, total: 2, sent: 2, accepted: 2, refused: const <String>[]), 'Despawned 2 NPCs on the server.');
    expect(spawnLine(spawn: true, total: 2, sent: 2, accepted: 1, refused: const <String>['town/a'], reason: serverErrorCopy['scope-missing']),
        startsWith('Spawned 1 of 2 NPCs. The server refused town/a: This pairing lacks a permission this needs.'));
  });

  test('should report a lost connection once, without a doubled period', () async {
    network.lastStream.emit(<String, Object?>{'type': 'closing', 'reason': 'token-revoked'});
    await network.lastStream.end('token-revoked');
    await settle();
    final List<String> lost = <String>[for (final String line in log()) if (line.contains('revoked')) line];
    expect(lost, hasLength(1));
    expect(lost.single, isNot(contains('..')));
    expect(connection.status.phase, ConnectionPhase.needsPairing);
  });
}
