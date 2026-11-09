import 'dart:async';

import 'package:animus_editor/services/server/server_connection.dart';
import 'package:animus_editor/services/server/server_profiles.dart';
import 'package:animus_editor/services/server/server_proof.dart';
import 'package:animus_editor/services/server/transport.dart';
import 'package:test/test.dart';

import '../tool/fake_server/identity.dart';
import 'support/fake_transport.dart';

final ServerToken token = ServerToken(token: 'anm_tok.secret', tokenId: 'tok', scopes: const <String>['read'], expiresAt: DateTime.utc(2030));

const ServerProfile profile = ServerProfile(
  fingerprint: fakeFingerprint,
  serverKey: fakeServerKey,
  name: 'Town',
  directUrl: 'http://127.0.0.1:9797',
  relayUrl: 'ws://127.0.0.1:9797',
);

/// Waits held by the connection until a test releases them.
class Waits {
  final List<(Duration, Completer<void>)> pending = <(Duration, Completer<void>)>[];

  Future<void> call(Duration duration) {
    final Completer<void> completer = Completer<void>();
    pending.add((duration, completer));
    return completer.future;
  }

  /// Releases the held waits of [duration]; returns how many.
  int release(Duration duration) {
    final List<(Duration, Completer<void>)> matching = <(Duration, Completer<void>)>[
      for (final (Duration, Completer<void>) wait in pending)
        if (wait.$1 == duration) wait,
    ];
    for (final (Duration, Completer<void>) wait in matching) {
      pending.remove(wait);
      if (!wait.$2.isCompleted) wait.$2.complete();
    }
    return matching.length;
  }

  List<Duration> get durations => <Duration>[for (final (Duration duration, Completer<void> _) in pending) duration];

  void releaseAll() {
    for (final (Duration _, Completer<void> completer) in pending) {
      if (!completer.isCompleted) completer.complete();
    }
    pending.clear();
  }
}

Future<void> settle() async {
  for (int index = 0; index < 20; index++) {
    await Future<void>.delayed(Duration.zero);
  }
}

void main() {
  late FakeNetwork network;
  late Waits waits;
  late ServerConnection connection;
  late List<ConnectionPhase> phases;

  ServerConnection make({ServerProfile serverProfile = profile, ServerToken? withToken}) {
    final ServerConnection made = ServerConnection(
      profile: serverProfile,
      token: withToken ?? token,
      open: network.open,
      delay: waits.call,
      now: () => DateTime.utc(2026, 11, 4, 12),
    );
    made.statuses.listen((ConnectionStatus status) => phases.add(status.phase));
    return made;
  }

  setUp(() {
    network = FakeNetwork(serverHandler());
    waits = Waits();
    phases = <ConnectionPhase>[];
    connection = make();
  });

  tearDown(() async => connection.dispose());

  test('should go online over the direct path first when both are paired', () async {
    await connection.connect();
    expect(connection.status.phase, ConnectionPhase.online);
    expect(connection.status.path, ConnectionPath.direct);
    expect(connection.hello?['platform'], 'paper');
    expect(network.streams.single.$1, ConnectionPath.direct);
  });

  test('should fail over to the relay when the direct path is unreachable', () async {
    network.down.add(ConnectionPath.direct);
    await connection.connect();
    expect(connection.status.phase, ConnectionPhase.online);
    expect(connection.status.path, ConnectionPath.relay);
    expect(connection.profile.lastPath, ConnectionPath.relay);
  });

  test('should forward server events while online', () async {
    await connection.connect();
    final List<Map<String, Object?>> events = <Map<String, Object?>>[];
    connection.events.listen(events.add);
    network.lastStream.emit(<String, Object?>{'type': 'catalog', 'documents': <Object?>[]});
    expect(events.single['type'], 'catalog');
  });

  test('should reconnect on the other path at once when the event stream drops', () async {
    await connection.connect();
    network.down.add(ConnectionPath.direct);
    await network.lastStream.end();
    await settle();
    expect(connection.status.phase, ConnectionPhase.online);
    expect(connection.status.path, ConnectionPath.relay);
    expect(phases, containsAllInOrder(<ConnectionPhase>[ConnectionPhase.online, ConnectionPhase.reconnecting, ConnectionPhase.connecting, ConnectionPhase.online]));
  });

  test('should reconnect over the relay and open a new stream when the server closes a slow stream at its limit', () async {
    network.down.add(ConnectionPath.direct);
    await connection.connect();
    expect(connection.status.path, ConnectionPath.relay);
    final List<String?> messages = <String?>[];
    connection.statuses.listen((ConnectionStatus status) => messages.add(status.message));
    final FakeSubscription slow = network.lastStream;
    slow.emit(<String, Object?>{'type': 'closing', 'reason': 'limit'});
    await slow.end('limit');
    await settle();
    expect(messages, contains(contains('Fell behind on server events.')));
    expect(connection.status.phase, ConnectionPhase.online, reason: 'a limit close is not a pairing problem');
    expect(connection.status.path, ConnectionPath.relay);
    expect(network.streams, hasLength(2));
    expect(network.lastStream.isOpen, isTrue);
  });

  test('should back off and retry when both paths are down', () async {
    network.down.addAll(ConnectionPath.values);
    await connection.connect();
    expect(connection.status.phase, ConnectionPhase.reconnecting);
    expect(connection.status.attempt, 1);
    expect(waits.pending.single.$1, const Duration(seconds: 1));
    waits.releaseAll();
    await settle();
    expect(connection.status.attempt, 2);
    expect(waits.pending.single.$1, const Duration(seconds: 2));
    network.down.clear();
    connection.retryNow();
    await settle();
    expect(connection.status.phase, ConnectionPhase.online);
  });

  test('should refuse a path that answers with another fingerprint and stop trying', () async {
    network.handler = serverHandler(fingerprint: 'sha256:${'0' * 64}');
    await connection.connect();
    expect(connection.status.phase, ConnectionPhase.refused);
    expect(connection.status.message, contains('A different server answered'));
    expect(network.sent.where((( ConnectionPath, ApiRequest) sent) => sent.$2.path != '/v1/hello'), isEmpty);
    expect(waits.pending, isEmpty);
  });

  group('relay server proof', () {
    setUp(() => network.down.add(ConnectionPath.direct));

    Future<void> expectRefused(FakeHandler relay) async {
      network.handler = relay;
      await connection.connect();
      expect(connection.status.phase, ConnectionPhase.refused);
      expect(connection.status.message, contains('A different server answered'));
      expect(network.sent.where(((ConnectionPath, ApiRequest) sent) => sent.$2.path != '/v1/hello'), isEmpty);
      expect(network.streams, isEmpty);
    }

    test('should refuse a relay hello without a proof and send no token', () => expectRefused(serverHandler(prove: false)));

    test('should refuse a relay proof from a key that is not the pinned one', () => expectRefused(serverHandler(seed: otherSeed)));

    test('should refuse a relay proof whose signature does not verify', () => expectRefused(tamperedProof(serverHandler())));

    test('should refuse a relay proof signed over the relay registration prefix',
        () => expectRefused(serverHandler(prefix: 'animus-relay/1 ')));

    test('should go online over the relay with a valid proof of a fresh challenge', () async {
      await connection.connect();
      expect(connection.status.phase, ConnectionPhase.online);
      expect(connection.status.path, ConnectionPath.relay);
      await network.lastStream.end();
      await settle();
      final List<String?> challenges = <String?>[
        for (final (ConnectionPath path, ApiRequest request) in network.sent)
          if (path == ConnectionPath.relay && request.path == '/v1/hello') request.headers[challengeHeader],
      ];
      expect(challenges, hasLength(2));
      expect(challenges.every((String? challenge) => challenge != null && challenge.length == 43), isTrue);
      expect(challenges.first, isNot(challenges.last));
    });
  });

  test('should send no challenge and need no proof on the direct path', () async {
    network.handler = serverHandler(prove: false);
    await connection.connect();
    expect(connection.status.phase, ConnectionPhase.online);
    expect(connection.status.path, ConnectionPath.direct);
    expect(network.sent.every(((ConnectionPath, ApiRequest) sent) => !sent.$2.headers.containsKey(challengeHeader)), isTrue);
  });

  test('should ask to pair again when the server refuses the token', () async {
    connection = make(withToken: ServerToken(token: 'anm_old.secret', tokenId: 'old', scopes: const <String>['read'], expiresAt: DateTime.utc(2030)));
    await connection.connect();
    expect(connection.status.phase, ConnectionPhase.needsPairing);
    expect(connection.token, isNull);
  });

  test('should ask to pair again when the stream closes because the token was revoked', () async {
    await connection.connect();
    await network.lastStream.end('token-revoked');
    await settle();
    expect(connection.status.phase, ConnectionPhase.needsPairing);
  });

  test('should send the bearer token and a strictly increasing counter on mutating requests', () async {
    network.handler = serverHandler(rest: (ApiRequest request) => const ApiResponse(200, <String, Object?>{}));
    await connection.connect();
    await connection.request(const ApiRequest('POST', '/v1/validate', body: <String, Object?>{}));
    await connection.request(const ApiRequest('POST', '/v1/validate', body: <String, Object?>{}));
    await connection.request(const ApiRequest('GET', '/v1/catalog'));
    final List<ApiRequest> posts = <ApiRequest>[for (final (ConnectionPath _, ApiRequest request) in network.sent) if (request.method == 'POST') request];
    final int first = int.parse(posts[0].headers['X-Animus-Counter']!);
    final int second = int.parse(posts[1].headers['X-Animus-Counter']!);
    expect(second, greaterThan(first));
    expect(posts.every((ApiRequest request) => request.headers['Authorization'] == 'Bearer anm_tok.secret'), isTrue);
    final ApiRequest get = network.sent.last.$2;
    expect(get.headers.containsKey('X-Animus-Counter'), isFalse);
  });

  test('should refuse requests while offline and reconnect when a request fails on the path', () async {
    await expectLater(connection.request(const ApiRequest('GET', '/v1/catalog')), throwsA(isA<NotConnected>()));
    await connection.connect();
    network.down.add(ConnectionPath.direct);
    await expectLater(connection.request(const ApiRequest('GET', '/v1/catalog')), throwsA(isA<TransportFailure>()));
    await settle();
    expect(connection.status.path, ConnectionPath.relay);
    expect(connection.status.phase, ConnectionPhase.online);
  });

  test('should go offline and stop retrying on disconnect', () async {
    await connection.connect();
    await connection.disconnect();
    expect(connection.status.phase, ConnectionPhase.offline);
    await settle();
    expect(connection.status.phase, ConnectionPhase.offline);
  });

  group('direct recovery while on the relay', () {
    const Duration overlap = Duration(seconds: 2);

    Future<void> onRelay() async {
      network.down.add(ConnectionPath.direct);
      await connection.connect();
      expect(connection.status.path, ConnectionPath.relay);
    }

    /// Lets the next probe run against a reachable direct path.
    Future<void> probeRecovers() async {
      network.down.remove(ConnectionPath.direct);
      expect(waits.release(const Duration(seconds: 15)), 1);
      await settle();
    }

    Iterable<FakeSubscription> directStreams() => <FakeSubscription>[
          for (final (ConnectionPath path, FakeSubscription stream) in network.streams)
            if (path == ConnectionPath.direct) stream,
        ];

    test('should probe the direct path with backoff while online over the relay', () async {
      await onRelay();
      expect(waits.durations, <Duration>[const Duration(seconds: 15)]);
      final FakeSubscription relayStream = network.streamOn(ConnectionPath.relay);
      for (final Duration wait in <Duration>[const Duration(seconds: 15), const Duration(seconds: 30), const Duration(seconds: 60)]) {
        expect(waits.release(wait), 1);
        await settle();
      }
      expect(waits.durations, <Duration>[const Duration(seconds: 60)]);
      expect(network.opened.where((ConnectionPath path) => path == ConnectionPath.direct).length, 4);
      expect(connection.status.phase, ConnectionPhase.online);
      expect(connection.status.path, ConnectionPath.relay);
      expect(relayStream.isOpen, isTrue);
    });

    test('should not probe while online over the direct path', () async {
      await connection.connect();
      expect(connection.status.path, ConnectionPath.direct);
      expect(waits.pending, isEmpty);
    });

    test('should probe at once when asked', () async {
      await onRelay();
      network.down.remove(ConnectionPath.direct);
      connection.probeNow();
      await settle();
      expect(directStreams(), hasLength(1));
      expect(waits.durations, contains(overlap));
    });

    test('should switch back to direct once the direct stream outlived the overlap', () async {
      await onRelay();
      final FakeSubscription relayStream = network.streamOn(ConnectionPath.relay);
      await probeRecovers();
      expect(network.streamOn(ConnectionPath.direct).isOpen, isTrue);
      expect(connection.status.path, ConnectionPath.relay, reason: 'not committed before the overlap');
      expect(relayStream.isOpen, isTrue);
      phases.clear();
      waits.release(overlap);
      await settle();
      expect(connection.status.phase, ConnectionPhase.online);
      expect(connection.status.path, ConnectionPath.direct);
      expect(connection.profile.lastPath, ConnectionPath.direct);
      expect(phases, <ConnectionPhase>[ConnectionPhase.online], reason: 'the switch never leaves online');
      expect(relayStream.isOpen, isFalse);
      network.handler = serverHandler(rest: (ApiRequest request) => const ApiResponse(200, <String, Object?>{}));
      await connection.request(const ApiRequest('GET', '/v1/catalog'));
      expect(network.sent.last.$1, ConnectionPath.direct);
    });

    test('should finish a request sent over the relay after the switch commits', () async {
      network.handler = serverHandler(rest: (ApiRequest request) => const ApiResponse(200, <String, Object?>{'applied': true}));
      await onRelay();
      final FakeTransport relay = network.transports.lastWhere((FakeTransport transport) => transport.path == ConnectionPath.relay);
      final Completer<void> held = Completer<void>();
      network.gate = (ConnectionPath path, ApiRequest request) async {
        if (path == ConnectionPath.relay && request.path == '/v1/changes') await held.future;
      };
      final Future<ApiResponse> inFlight = connection.request(const ApiRequest('POST', '/v1/changes', body: <String, Object?>{}));
      await probeRecovers();
      waits.release(overlap);
      await settle();
      expect(connection.status.path, ConnectionPath.direct);
      expect(relay.closed, isFalse, reason: 'kept open for the request in flight');
      held.complete();
      final ApiResponse response = await inFlight;
      expect(response.ok, isTrue);
      expect(response.body, <String, Object?>{'applied': true});
      await settle();
      expect(relay.closed, isTrue);
      expect(connection.status.path, ConnectionPath.direct);
    });

    test('should deliver every event once across the overlap', () async {
      await onRelay();
      final List<String> seen = <String>[];
      connection.events.listen((Map<String, Object?> event) => seen.add(event['previewId']! as String));
      Map<String, Object?> event(String id) => <String, Object?>{'type': 'preview', 'previewId': id, 'state': 'running'};
      final FakeSubscription relayStream = network.streamOn(ConnectionPath.relay);
      relayStream.emit(event('before'));
      await probeRecovers();
      final FakeSubscription directStream = network.streamOn(ConnectionPath.direct);
      relayStream.emit(event('relay-only'));
      directStream.emit(event('direct-first'));
      relayStream.emit(event('direct-first'));
      relayStream.emit(event('relay-first'));
      directStream.emit(event('relay-first'));
      relayStream.emit(event('relay-ahead'));
      waits.release(overlap);
      await settle();
      expect(connection.status.path, ConnectionPath.direct);
      directStream.emit(event('relay-ahead'));
      directStream.emit(event('after'));
      expect(seen, <String>['before', 'relay-only', 'direct-first', 'relay-first', 'relay-ahead', 'after']);
    });

    test('should stay on the relay when the direct path answers with another fingerprint', () async {
      await onRelay();
      final FakeSubscription relayStream = network.streamOn(ConnectionPath.relay);
      network.pathHandlers[ConnectionPath.direct] = serverHandler(fingerprint: 'sha256:${'0' * 64}');
      final int sentBefore = network.sent.length;
      await probeRecovers();
      expect(connection.status.phase, ConnectionPhase.online);
      expect(connection.status.path, ConnectionPath.relay);
      expect(relayStream.isOpen, isTrue);
      final List<(ConnectionPath, ApiRequest)> probed = network.sent.sublist(sentBefore);
      expect(probed.where(((ConnectionPath, ApiRequest) sent) => sent.$1 == ConnectionPath.direct && sent.$2.path != '/v1/hello'), isEmpty);
      expect(directStreams(), isEmpty);
      expect(waits.durations, <Duration>[const Duration(seconds: 30)]);
    });

    test('should stay on the relay when the direct stream closes during the overlap', () async {
      await onRelay();
      final FakeSubscription relayStream = network.streamOn(ConnectionPath.relay);
      await probeRecovers();
      await network.streamOn(ConnectionPath.direct).end('unauthorized');
      await settle();
      expect(connection.status.phase, ConnectionPhase.online);
      expect(connection.status.path, ConnectionPath.relay);
      expect(relayStream.isOpen, isTrue);
      expect(connection.token, isNotNull);
      expect(waits.durations, contains(const Duration(seconds: 30)));
    });

    test('should keep X-Animus-Counter strictly increasing across the switch', () async {
      network.handler = serverHandler(rest: (ApiRequest request) => const ApiResponse(200, <String, Object?>{}));
      await onRelay();
      await connection.request(const ApiRequest('POST', '/v1/validate', body: <String, Object?>{}));
      await connection.request(const ApiRequest('POST', '/v1/validate', body: <String, Object?>{}));
      await probeRecovers();
      waits.release(overlap);
      await settle();
      expect(connection.status.path, ConnectionPath.direct);
      await connection.request(const ApiRequest('POST', '/v1/validate', body: <String, Object?>{}));
      final List<(ConnectionPath, int)> counters = <(ConnectionPath, int)>[
        for (final (ConnectionPath path, ApiRequest request) in network.sent)
          if (request.headers['X-Animus-Counter'] != null) (path, int.parse(request.headers['X-Animus-Counter']!)),
      ];
      expect(<ConnectionPath>[for (final (ConnectionPath path, int _) in counters) path],
          <ConnectionPath>[ConnectionPath.relay, ConnectionPath.relay, ConnectionPath.direct]);
      for (int index = 1; index < counters.length; index++) {
        expect(counters[index].$2, greaterThan(counters[index - 1].$2));
      }
    });

    test('should fail over to the relay again when the recovered direct path drops', () async {
      await onRelay();
      await probeRecovers();
      waits.release(overlap);
      await settle();
      network.down.add(ConnectionPath.direct);
      await network.streamOn(ConnectionPath.direct).end();
      await settle();
      expect(connection.status.path, ConnectionPath.relay);
      expect(waits.durations, contains(const Duration(seconds: 15)));
    });
  });

  test('should double the reconnect wait up to 30 seconds', () {
    expect(<int>[for (int attempt = 1; attempt <= 7; attempt++) reconnectDelay(attempt).inSeconds], <int>[1, 2, 4, 8, 16, 30, 30]);
  });
}
