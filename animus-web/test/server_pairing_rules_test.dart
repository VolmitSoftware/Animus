import 'dart:convert';

import 'package:animus_editor/logic/shared_schemas.dart';
import 'package:animus_editor/services/server/error_copy.dart';
import 'package:animus_editor/services/server/pairing.dart';
import 'package:animus_editor/services/server/server_connection.dart';
import 'package:animus_editor/services/server/server_profiles.dart';
import 'package:animus_editor/services/server/transport.dart';
import 'package:animus_editor/state/server_hub.dart';
import 'package:test/test.dart';

import 'fixtures.dart';
import 'server_connection_test.dart' show Waits, profile, settle, token;
import 'support/fake_transport.dart';

const ApiResponse _tokenAnswer = ApiResponse(200, <String, Object?>{
  'token': 'anm_tok_0001AbCd.ZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXp7fH1-f4CBgoM',
  'tokenId': 'tok_0001AbCd',
  'scopes': <Object?>['read'],
  'expiresAt': '2030-01-01T00:00:00Z',
  'fingerprint': fixtureFingerprint,
});

ApiResponse _refuse(String code) => ApiResponse(errorStatusFor(code), <String, Object?>{'error': <String, Object?>{'code': code, 'message': 'server words'}});

int errorStatusFor(String code) => code == 'pairing-invalid' ? 401 : 500;

void main() {
  final SharedSchemas schemas = SharedSchemas.embedded;
  final Map<String, Object?> pairing = readFixture('editor/pairing.json')! as Map<String, Object?>;
  final PairingPayload both = decodePairingCode(((pairing['valid']! as List<Object?>)[1]! as Map<String, Object?>)['code']! as String, schemas);

  group('pairing', () {
    test('should try the relay when the direct path refuses the pairing', () async {
      final FakeNetwork network = FakeNetwork(serverHandler(rest: (ApiRequest request) => _tokenAnswer));
      network.pathHandlers[ConnectionPath.direct] = serverHandler(rest: (ApiRequest request) => _refuse('internal'));
      final (ServerToken paired, ConnectionPath path) = await ServerConnection.pair(both, network.open);
      expect(path, ConnectionPath.relay);
      expect(paired.tokenId, 'tok_0001AbCd');
    });

    test('should try the relay when the direct hello is an error, and not call it another server', () async {
      final FakeNetwork network = FakeNetwork(serverHandler(rest: (ApiRequest request) => _tokenAnswer));
      network.pathHandlers[ConnectionPath.direct] = (ApiRequest request) => _refuse('internal');
      expect((await ServerConnection.pair(both, network.open)).$2, ConnectionPath.relay);
      network.handler = (ApiRequest request) => _refuse('internal');
      await expectLater(
        ServerConnection.pair(both, network.open),
        throwsA(isA<PairingRefused>().having((PairingRefused refused) => refused.message, 'message', serverErrorCopy['internal']!.text)),
      );
    });

    test('should report the server refusal with its next step when every path refuses', () async {
      final FakeNetwork network = FakeNetwork(serverHandler(rest: (ApiRequest request) => _refuse('pairing-invalid')));
      await expectLater(
        ServerConnection.pair(both, network.open),
        throwsA(isA<PairingRefused>()
            .having((PairingRefused refused) => refused.code, 'code', 'pairing-invalid')
            .having((PairingRefused refused) => refused.message, 'message', contains('Run /animus editor pair on the server again'))),
      );
    });

    test('should not lose an unexpected failure on a path', () async {
      final FakeNetwork network = FakeNetwork((ApiRequest request) => throw StateError('broken answer'));
      await expectLater(
        ServerConnection.pair(both, network.open),
        throwsA(isA<PairingRefused>().having((PairingRefused refused) => refused.code, 'code', 'unreachable')),
      );
    });
  });

  group('connection', () {
    test('should ask to pair again without a request when the token expired on this clock', () async {
      final FakeNetwork network = FakeNetwork(serverHandler());
      final ServerConnection connection = ServerConnection(
        profile: profile,
        token: ServerToken(token: 'anm_tok.secret', tokenId: 'tok', scopes: const <String>['read'], expiresAt: DateTime.utc(2026, 11, 1)),
        open: network.open,
        delay: Waits().call,
        now: () => DateTime.utc(2026, 11, 4),
      );
      await connection.connect();
      expect(connection.status.phase, ConnectionPhase.needsPairing);
      expect(connection.status.message, serverErrorCopy['token-expired']!.text);
      expect(network.opened, isEmpty);
      await connection.dispose();
    });

    test('should count the days left on the token', () async {
      final ServerConnection connection = ServerConnection(
        profile: profile,
        token: token,
        open: FakeNetwork(serverHandler()).open,
        now: () => DateTime.utc(2029, 12, 30),
      );
      expect(connection.tokenLeft, const Duration(days: 2));
      await connection.dispose();
    });

    test('should turn any failure during an attempt into a retry with plain words', () async {
      bool broken = true;
      final FakeNetwork network = FakeNetwork((ApiRequest request) {
        if (broken) throw StateError('a bug');
        return serverHandler()(request);
      });
      final Waits waits = Waits();
      final ServerConnection connection = ServerConnection(profile: profile, token: token, open: network.open, delay: waits.call);
      await connection.connect();
      expect(connection.status.phase, ConnectionPhase.reconnecting);
      expect(connection.status.message, transportErrorCopy['dropped']!.text);
      broken = false;
      waits.releaseAll();
      await settle();
      expect(connection.status.phase, ConnectionPhase.online);
      await connection.dispose();
    });
  });

  group('saved servers', () {
    const ServerProfile other = ServerProfile(fingerprint: fixtureFingerprint, serverKey: fixtureServerKey, name: 'Other', relayUrl: 'wss://relay');

    ServerHub hub(MemoryKeyValueStore local, {List<String>? forgotten}) => ServerHub(
          profiles: ServerProfileStore(local: local, session: MemoryKeyValueStore()),
          open: (ConnectionPath path, String url, String fingerprint) => FakeNetwork(serverHandler()).open(path, url),
          schemas: schemas,
          onForget: (String id) async => forgotten?.add(id),
        );

    test('should remove the live workspace and the entry when forgetting a server', () async {
      final MemoryKeyValueStore local = MemoryKeyValueStore();
      ServerProfileStore(local: local, session: MemoryKeyValueStore()).save(<ServerProfile>[profile]);
      final List<String> forgotten = <String>[];
      final ServerHub servers = hub(local, forgotten: forgotten);
      await servers.forget(profile.id);
      expect(forgotten, <String>[profile.id]);
      expect(servers.servers, isEmpty);
      expect(jsonDecode(local.read(ServerProfileStore.profilesKey)!), isEmpty);
    });

    test('should reload the list when another tab changes it', () async {
      final MemoryKeyValueStore local = MemoryKeyValueStore();
      final ServerHub servers = hub(local);
      int changes = 0;
      servers.addListener(() => changes++);
      local.writeFromOtherTab(ServerProfileStore.profilesKey, jsonEncode(<Object?>[other.toJson()]));
      expect(servers.servers.single.name, 'Other');
      expect(changes, 1);
    });

    test('should re-read the list before forgetting, keeping what another tab saved', () async {
      final MemoryKeyValueStore local = MemoryKeyValueStore();
      final ServerHub servers = hub(local);
      // The other tab writes, but this tab misses the event (its own write).
      local.values[ServerProfileStore.profilesKey] = jsonEncode(<Object?>[other.toJson()]);
      await servers.forget('no-such-server');
      expect(servers.servers.single.name, 'Other');
    });

    test('should say when the browser refused to store a token', () {
      final ServerProfileStore store = ServerProfileStore(local: MemoryKeyValueStore(refuseWrites: true), session: MemoryKeyValueStore(refuseWrites: true));
      expect(store.saveToken(profile, token), isFalse);
      expect(ServerProfileStore(local: MemoryKeyValueStore(), session: MemoryKeyValueStore()).saveToken(profile, token), isTrue);
    });
  });
}
