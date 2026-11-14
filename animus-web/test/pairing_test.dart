import 'package:animus_editor/logic/shared_schemas.dart';
import 'package:animus_editor/services/server/pairing.dart';
import 'package:animus_editor/services/server/server_pairing.dart';
import 'package:animus_editor/services/server/server_profiles.dart';
import 'package:animus_editor/services/server/transport.dart';
import 'package:test/test.dart';

import 'fixtures.dart';
import '../tool/fake_server/identity.dart';
import 'support/fake_transport.dart';

void main() {
  final SharedSchemas schemas = SharedSchemas.embedded;
  final Map<String, Object?> pairing = readFixture('editor/pairing.json')! as Map<String, Object?>;

  test('should decode every valid pairing fixture and pin its fingerprint', () {
    for (final Object? raw in pairing['valid']! as List<Object?>) {
      final Map<String, Object?> entry = raw! as Map<String, Object?>;
      final Map<String, Object?> payload = entry['payload']! as Map<String, Object?>;
      final PairingPayload decoded = decodePairingCode(entry['code']! as String, schemas);
      expect(decoded.fingerprint, payload['fingerprint']);
      expect(decoded.directUrl, payload['directUrl']);
      expect(decoded.relayUrl, payload['relayUrl']);
      expect(decoded.serverId, (payload['fingerprint']! as String).substring(7, 23));
    }
  });

  test('should refuse every invalid pairing fixture', () {
    for (final Object? raw in pairing['invalid']! as List<Object?>) {
      final Map<String, Object?> entry = raw! as Map<String, Object?>;
      expect(() => decodePairingCode(entry['code']! as String, schemas), throwsA(isA<PairingCodeError>()), reason: '${entry['reason']}');
    }
  });

  test('should take the code out of a pasted link', () {
    expect(extractPairingCode('  https://animus.volmitsoftware.com/#/pair/ANM1.abc  '), 'ANM1.abc');
    expect(extractPairingCode('ANM1.abc'), 'ANM1.abc');
  });

  test('should accept a loopback ws relay and refuse a remote one', () {
    expect(schemas.validateMessage('pairing-payload', _payload('ws://127.0.0.1:9797')), isEmpty);
    expect(schemas.validateMessage('pairing-payload', _payload('ws://relay.example.net')), isNotEmpty);
  });

  test('should exchange the secret for a token over the first path with the pinned fingerprint', () async {
    final FakeNetwork network = FakeNetwork(serverHandler(rest: (ApiRequest request) {
      if (request.path != '/v1/pair') return const ApiResponse(404, null);
      return const ApiResponse(200, <String, Object?>{
        'token': 'anm_tok_0001AbCd.ZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXp7fH1-f4CBgoM',
        'tokenId': 'tok_0001AbCd',
        'scopes': <Object?>['read', 'validate'],
        'expiresAt': '2026-11-11T12:00:00Z',
        'fingerprint': fakeFingerprint,
        'admin': '0000000a-0000-4000-8000-0000000000b2',
      });
    }));
    final PairingPayload payload = fakePayload(relayUrl: 'ws://127.0.0.1:9797');
    final (ServerToken token, ConnectionPath path, String? _) = await pairServer(payload, network.open, client: 'test');
    expect(path, ConnectionPath.relay);
    expect(token.tokenId, 'tok_0001AbCd');
    expect(token.has('validate'), isTrue);
    expect(token.admin, '0000000a-0000-4000-8000-0000000000b2', reason: 'the pairing player owns NPCs made here');
    expect(ServerToken.fromJson(token.toJson())!.admin, token.admin, reason: 'kept with the stored token');
    expect(network.sent.last.$2.body, containsPair('secret', payload.secret));
  });

  test('should refuse to pair with a server that answers with another fingerprint', () async {
    final FakeNetwork network = FakeNetwork(serverHandler(fingerprint: 'sha256:${'1' * 64}'));
    final PairingPayload payload = decodePairingCode(((pairing['valid']! as List<Object?>)[0]! as Map<String, Object?>)['code']! as String, schemas);
    await expectLater(pairServer(payload, network.open), throwsA(isA<PairingRefused>()));
    expect(network.sent.where((( ConnectionPath, ApiRequest) sent) => sent.$2.path == '/v1/pair'), isEmpty);
  });

  group('ServerProfileStore', () {
    const ServerProfile remembered = ServerProfile(fingerprint: fakeFingerprint, serverKey: fakeServerKey, name: 'Town', relayUrl: 'wss://relay', remember: true);
    final ServerToken token = ServerToken(token: 'anm_x.y', tokenId: 'x', scopes: const <String>['read'], expiresAt: DateTime.utc(2030));

    test('should keep a remembered token in local storage and a session token only in the session', () {
      final MemoryKeyValueStore local = MemoryKeyValueStore();
      final ServerProfileStore store = ServerProfileStore(local: local, session: MemoryKeyValueStore());
      store.save(<ServerProfile>[remembered]);
      store.saveToken(remembered, token);
      final ServerProfileStore reopened = ServerProfileStore(local: local, session: MemoryKeyValueStore());
      expect(reopened.token(reopened.load().single)!.tokenId, 'x');
      final ServerProfile forgetful = remembered.copyWith(remember: false);
      store.save(<ServerProfile>[forgetful]);
      store.saveToken(forgetful, token);
      final ServerProfileStore later = ServerProfileStore(local: local, session: MemoryKeyValueStore());
      expect(later.token(later.load().single), isNull);
      expect(store.token(forgetful), isNotNull);
    });

    test('should drop a stored profile whose key does not hash to its fingerprint', () {
      final MemoryKeyValueStore local = MemoryKeyValueStore()
        ..write(ServerProfileStore.profilesKey, '[{"fingerprint":"sha256:${'0' * 64}","serverKey":"$fakeServerKey","name":"Evil"}]');
      expect(ServerProfileStore(local: local, session: MemoryKeyValueStore()).load(), isEmpty);
    });

    test('should try the last good path first', () {
      const ServerProfile both = ServerProfile(fingerprint: fakeFingerprint, serverKey: fakeServerKey, name: 'T', directUrl: 'https://a', relayUrl: 'wss://b');
      expect(both.paths.first.$1, ConnectionPath.direct);
      expect(both.copyWith(lastPath: ConnectionPath.relay).paths.first.$1, ConnectionPath.relay);
    });
  });
}

Map<String, Object?> _payload(String relayUrl) => <String, Object?>{
      'v': 1,
      'relayUrl': relayUrl,
      'fingerprint': fakeFingerprint,
      'serverKey': fakeServerKey,
      'tokenId': 'tok_0001AbCd',
      'secret': 'ZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXp7fH1-f4CBgoM',
      'expiresAt': '2026-11-04T12:10:00Z',
    };
