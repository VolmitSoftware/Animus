import 'dart:async';
import 'dart:convert';

import 'package:animus_editor/logic/shared_schemas.dart';
import 'package:animus_editor/services/server/connection_status.dart';
import 'package:animus_editor/services/server/error_copy.dart';
import 'package:animus_editor/services/server/local_network.dart';
import 'package:animus_editor/services/server/pairing.dart';
import 'package:animus_editor/services/server/server_connection.dart';
import 'package:animus_editor/services/server/server_pairing.dart';
import 'package:animus_editor/services/server/server_profiles.dart';
import 'package:animus_editor/services/server/server_proof.dart';
import 'package:animus_editor/services/server/transport.dart';
import 'package:animus_editor/state/server_hub.dart';
import 'package:test/test.dart';

import 'server_connection_test.dart' show Waits, profile, settle, token;
import '../tool/fake_server/identity.dart';
import 'support/fake_transport.dart';

const ApiResponse _tokenAnswer = ApiResponse(200, <String, Object?>{
  'token': 'anm_tok_0001AbCd.ZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXp7fH1-f4CBgoM',
  'tokenId': 'tok_0001AbCd',
  'scopes': <Object?>['read'],
  'expiresAt': '2030-01-01T00:00:00Z',
  'fingerprint': fakeFingerprint,
});

ApiResponse _refuse(String code) => ApiResponse(errorStatusFor(code), <String, Object?>{'error': <String, Object?>{'code': code, 'message': 'server words'}});

int errorStatusFor(String code) => switch (code) {
      'pairing-invalid' => 401,
      'origin-not-allowed' => 403,
      'pairing-expired' => 410,
      _ => 500,
    };

void main() {
  final SharedSchemas schemas = SharedSchemas.embedded;
  final PairingPayload both = fakePayload(directUrl: 'http://127.0.0.1:9797', relayUrl: 'ws://127.0.0.1:9797');

  group('pairing', () {
    test('should try the relay when the direct path refuses the pairing', () async {
      final FakeNetwork network = FakeNetwork(serverHandler(rest: (ApiRequest request) => _tokenAnswer));
      network.pathHandlers[ConnectionPath.direct] = serverHandler(rest: (ApiRequest request) => _refuse('internal'));
      final (ServerToken paired, ConnectionPath path, String? _) = await pairServer(both, network.open);
      expect(path, ConnectionPath.relay);
      expect(paired.tokenId, 'tok_0001AbCd');
    });

    test('should try the relay when the direct hello is an error, and not call it another server', () async {
      final FakeNetwork network = FakeNetwork(serverHandler(rest: (ApiRequest request) => _tokenAnswer));
      network.pathHandlers[ConnectionPath.direct] = (ApiRequest request) => _refuse('internal');
      expect((await pairServer(both, network.open)).$2, ConnectionPath.relay);
      network.handler = (ApiRequest request) => _refuse('internal');
      await expectLater(
        pairServer(both, network.open),
        throwsA(isA<PairingRefused>().having((PairingRefused refused) => refused.message, 'message', serverErrorCopy['internal']!.text)),
      );
    });

    test('should report the server refusal with its next step when every path refuses', () async {
      final FakeNetwork network = FakeNetwork(serverHandler(rest: (ApiRequest request) => _refuse('pairing-invalid')));
      await expectLater(
        pairServer(both, network.open),
        throwsA(isA<PairingRefused>()
            .having((PairingRefused refused) => refused.code, 'code', 'pairing-invalid')
            .having((PairingRefused refused) => refused.message, 'message', contains('Run /animus editor pair and open the new link.'))),
      );
    });

    test('should send the secret over the relay only after the server proves its key', () async {
      final FakeNetwork network = FakeNetwork(serverHandler(rest: (ApiRequest request) => _tokenAnswer));
      network.down.add(ConnectionPath.direct);
      expect((await pairServer(both, network.open)).$2, ConnectionPath.relay);
      final ApiRequest hello = network.sent.firstWhere(((ConnectionPath, ApiRequest) sent) => sent.$2.path == '/v1/hello').$2;
      expect(hello.headers[challengeHeader], hasLength(43));
    });

    test('should not send the secret over a relay that cannot prove the pinned key', () async {
      final Map<String, FakeHandler> relays = <String, FakeHandler>{
        'no proof': serverHandler(prove: false, rest: (ApiRequest request) => _tokenAnswer),
        'another key': serverHandler(seed: otherSeed, rest: (ApiRequest request) => _tokenAnswer),
        'bad signature': tamperedProof(serverHandler(rest: (ApiRequest request) => _tokenAnswer)),
        'relay registration prefix': serverHandler(prefix: 'animus-relay/1 ', rest: (ApiRequest request) => _tokenAnswer),
      };
      for (final MapEntry<String, FakeHandler> relay in relays.entries) {
        final FakeNetwork network = FakeNetwork(relay.value);
        await expectLater(
          pairServer(fakePayload(relayUrl: 'ws://127.0.0.1:9797'), network.open),
          throwsA(isA<PairingRefused>().having((PairingRefused refused) => refused.message, 'message', transportErrorCopy['wrong-server']!.text)),
          reason: relay.key,
        );
        expect(network.sent.map(((ConnectionPath, ApiRequest) sent) => sent.$2.path), <String>['/v1/hello'], reason: relay.key);
      }
    });

    test('should not lose an unexpected failure on a path', () async {
      final FakeNetwork network = FakeNetwork((ApiRequest request) => throw StateError('broken answer'));
      await expectLater(
        pairServer(both, network.open),
        throwsA(isA<PairingRefused>().having((PairingRefused refused) => refused.code, 'code', 'unreachable')),
      );
    });
  });

  group('pairing failures', () {
    const String securePage = 'https://animus.volmitsoftware.com';
    const Duration cut = Duration(milliseconds: 50);
    final PairingPayload directOnly = fakePayload(directUrl: 'http://127.0.0.1:9797');
    final PairingPayload relayOnly = fakePayload(relayUrl: 'ws://127.0.0.1:9797');

    Matcher fails(PairingFailure failure) => throwsA(isA<PairingRefused>()
        .having((PairingRefused refused) => refused.failure, 'failure', failure)
        .having((PairingRefused refused) => refused.message, 'message', pairingFailureCopy(failure).text));

    FakeNetwork pairs() => FakeNetwork(serverHandler(rest: (ApiRequest request) => _tokenAnswer));

    // Holds every direct request, as a Local Network Access prompt nobody answers does.
    void holdDirect(FakeNetwork network) =>
        network.gate = (ConnectionPath path, ApiRequest request) => path == ConnectionPath.direct ? Completer<void>().future : Future<void>.value();

    TransportFactory relayFailing(FakeNetwork network, String code) => (ConnectionPath path, String url) =>
        path == ConnectionPath.relay ? Future<ServerTransport>.error(TransportFailure(path, code)) : network.open(path, url);

    test('should say the browser blocked a direct path that hangs past the pairing timeout on an https page', () async {
      final FakeNetwork network = pairs();
      holdDirect(network);
      final Stopwatch watch = Stopwatch()..start();
      await expectLater(pairServer(directOnly, network.open, pageOrigin: securePage, pathTimeout: cut), fails(PairingFailure.localNetworkBlocked));
      expect(watch.elapsed, lessThan(const Duration(seconds: 2)));
      expect(network.transports.single.closed, isTrue, reason: 'the held request is cut');
      expect(network.sent.where(((ConnectionPath, ApiRequest) sent) => sent.$2.path == '/v1/pair'), isEmpty);
    });

    test('should call the same hang unreachable on an http page', () async {
      final FakeNetwork network = pairs();
      holdDirect(network);
      await expectLater(
        pairServer(directOnly, network.open, pageOrigin: 'http://localhost:8791', pathTimeout: cut),
        fails(PairingFailure.unreachable),
      );
    });

    test('should say the browser blocked a direct fetch that failed before any answer on an https page', () async {
      final FakeNetwork network = pairs()..down.add(ConnectionPath.direct);
      await expectLater(pairServer(directOnly, network.open, pageOrigin: securePage), fails(PairingFailure.localNetworkBlocked));
    });

    test('should pair over the relay when the browser blocks the direct path', () async {
      final FakeNetwork network = pairs();
      holdDirect(network);
      final (ServerToken paired, ConnectionPath path, String? _) = await pairServer(both, network.open, pageOrigin: securePage, pathTimeout: cut);
      expect(path, ConnectionPath.relay);
      expect(paired.tokenId, 'tok_0001AbCd');
    });

    test('should name the blocked direct path ahead of a relay that is also down', () async {
      final FakeNetwork network = pairs()..down.add(ConnectionPath.direct);
      await expectLater(
        pairServer(both, relayFailing(network, 'relay-unreachable'), pageOrigin: securePage),
        fails(PairingFailure.localNetworkBlocked),
      );
    });

    test('should say the relay is unavailable when a relay-only link cannot get through it', () async {
      for (final String code in <String>['relay-unreachable', 'capacity', 'handshake-failed']) {
        await expectLater(pairServer(relayOnly, relayFailing(pairs(), code), pageOrigin: securePage), fails(PairingFailure.relayUnavailable), reason: code);
      }
      final FakeNetwork away = pairs()..serverOnRelay = false;
      await expectLater(pairServer(relayOnly, away.open), fails(PairingFailure.relayUnavailable), reason: 'server not on the relay');
      expect(away.sent, isEmpty, reason: 'nothing is sent while the server is away');
      await expectLater(
        pairServer(relayOnly, (ConnectionPath path, String url) => Completer<ServerTransport>().future, pathTimeout: cut),
        fails(PairingFailure.relayUnavailable),
        reason: 'the relay socket never opened',
      );
    });

    test('should call it unreachable when no path answers and neither the browser nor the relay is to blame', () async {
      final FakeNetwork down = pairs()..down.addAll(ConnectionPath.values);
      await expectLater(pairServer(both, down.open), fails(PairingFailure.unreachable), reason: 'no page origin off the browser');
      final FakeNetwork silent = pairs()..gate = (ConnectionPath path, ApiRequest request) => Completer<void>().future;
      await expectLater(pairServer(relayOnly, silent.open, pathTimeout: cut), fails(PairingFailure.unreachable), reason: 'the relay is up, the server silent');
    });

    test('should put the server refusal ahead of a blocked direct path', () async {
      final FakeNetwork network = FakeNetwork(serverHandler(rest: (ApiRequest request) => _refuse('pairing-invalid')))..down.add(ConnectionPath.direct);
      await expectLater(pairServer(both, network.open, pageOrigin: securePage), fails(PairingFailure.used));
    });

    test('should word a used or revoked link, an expired link and a refused page address apart', () async {
      final FakeNetwork used = FakeNetwork(serverHandler(rest: (ApiRequest request) => _refuse('pairing-invalid')));
      await expectLater(pairServer(both, used.open), fails(PairingFailure.used));
      final FakeNetwork expired = FakeNetwork(serverHandler(rest: (ApiRequest request) => _refuse('pairing-expired')));
      await expectLater(pairServer(both, expired.open), fails(PairingFailure.expired));
      // The server checks the origin of every direct request, hello included.
      final FakeNetwork origin = FakeNetwork((ApiRequest request) => _refuse('origin-not-allowed'));
      await expectLater(pairServer(directOnly, origin.open, pageOrigin: securePage), fails(PairingFailure.originNotAllowed));
    });

    test('should give every pairing failure one sentence and a next step', () {
      for (final PairingFailure failure in PairingFailure.values) {
        final ErrorCopy copy = pairingFailureCopy(failure);
        expect(copy.cause, endsWith('.'), reason: failure.name);
        expect(copy.next, endsWith('.'), reason: failure.name);
        expect(PairingRefused.because(failure).failure, failure);
        expect(PairingRefused.because(failure).message, copy.text);
      }
      expect(PairingRefused('rate-limited', serverCopy('rate-limited')).failure, PairingFailure.other);
      expect(PairingRefused('pairing-expired', serverCopy('pairing-expired')).failure, PairingFailure.expired);
    });

    group('from the hub', () {
      String code({required DateTime expiresAt}) => pairingCode(
            directUrl: 'http://127.0.0.1:9797',
            fingerprint: fakeFingerprint,
            serverKey: fakeServerKey,
            tokenId: 'tok_0001AbCd',
            secret: 'ZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXp7fH1-f4CBgoM',
            expiresAt: expiresAt,
          );

      ServerHub hub(FakeNetwork network, {String? Function()? pageOrigin}) => ServerHub(
            profiles: ServerProfileStore(local: MemoryKeyValueStore(), session: MemoryKeyValueStore()),
            open: (ConnectionPath path, String url, String fingerprint) => network.open(path, url),
            schemas: schemas,
            pageOrigin: pageOrigin ?? () => null,
            now: () => DateTime.utc(2026, 11, 5),
          );

      test('should word a link that expired on this clock as expired without asking the server', () async {
        final FakeNetwork network = pairs();
        await expectLater(hub(network).pair(code(expiresAt: DateTime.utc(2026, 11, 4)), remember: false), fails(PairingFailure.expired));
        expect(network.opened, isEmpty);
      });

      test('should judge a failed direct path by the page it runs on', () async {
        final FakeNetwork network = pairs()..down.add(ConnectionPath.direct);
        final String link = code(expiresAt: DateTime.utc(2030));
        await expectLater(hub(network, pageOrigin: () => securePage).pair(link, remember: false), fails(PairingFailure.localNetworkBlocked));
        await expectLater(hub(network).pair(link, remember: false), fails(PairingFailure.unreachable));
      });
    });
  });

  group('local network blocking', () {
    test('should suspect the browser only for an https page calling plain http on a local address', () {
      const List<String> local = <String>[
        'http://127.0.0.1:9797',
        'http://localhost:9797',
        'http://[::1]:9797',
        'http://10.0.0.5',
        'http://172.16.0.1',
        'http://172.31.255.255:80',
        'http://192.168.1.20:9797/',
        'http://169.254.1.1',
        'http://minecraft.local:9797',
      ];
      for (final String url in local) {
        expect(browserMayBlock('https://animus.volmitsoftware.com', url), isTrue, reason: url);
        expect(browserMayBlock('http://localhost:8791', url), isFalse, reason: url);
        expect(browserMayBlock(null, url), isFalse, reason: url);
      }
      for (final String url in <String>['https://127.0.0.1:9797', 'http://172.32.0.1', 'http://8.8.8.8', 'http://example.com', 'http://192.169.0.1', '']) {
        expect(browserMayBlock('https://animus.volmitsoftware.com', url), isFalse, reason: url);
      }
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
    const ServerProfile other = ServerProfile(fingerprint: fakeFingerprint, serverKey: fakeServerKey, name: 'Other', relayUrl: 'wss://relay');

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
