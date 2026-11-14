import 'dart:async';

import 'package:animus_editor/services/server/connection_status.dart';
import 'package:animus_editor/services/server/error_copy.dart';
import 'package:animus_editor/services/server/request_pacer.dart';
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

/// The handshake budget in these tests, apart from every backoff, probe
/// and overlap wait.
const Duration handshakeBudget = Duration(seconds: 7);

/// Waits held by the connection until a test releases them. Handshake
/// budgets are held apart in [budgets], so [pending] holds only the backoff,
/// probe and overlap waits.
class Waits {
  final List<(Duration, Completer<void>)> pending = <(Duration, Completer<void>)>[];
  final List<Completer<void>> budgets = <Completer<void>>[];

  Future<void> call(Duration duration) {
    final Completer<void> completer = Completer<void>();
    if (duration == handshakeBudget) {
      budgets.add(completer);
    } else {
      pending.add((duration, completer));
    }
    return completer.future;
  }

  /// Runs out every handshake budget started so far.
  void expireBudgets() {
    for (final Completer<void> budget in budgets) {
      if (!budget.isCompleted) budget.complete();
    }
    budgets.clear();
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
  late DateTime clock;

  ServerConnection make({ServerProfile serverProfile = profile, ServerToken? withToken, TransportFactory? open, bool Function()? visible}) {
    final ServerConnection made = ServerConnection(
      profile: serverProfile,
      token: withToken ?? token,
      open: open ?? network.open,
      delay: waits.call,
      handshakeTimeout: handshakeBudget,
      visible: visible ?? () => true,
      now: () => clock,
    );
    made.statuses.listen((ConnectionStatus status) => phases.add(status.phase));
    return made;
  }

  setUp(() {
    network = FakeNetwork(serverHandler());
    waits = Waits();
    phases = <ConnectionPhase>[];
    clock = DateTime.utc(2026, 11, 4, 12);
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

  test('should back off instead of reopening at once when the server refuses an event stream it opened', () async {
    await connection.connect();
    expect(connection.status.path, ConnectionPath.direct);
    await network.lastStream.end('rate-limited');
    await settle();
    expect(connection.status.phase, ConnectionPhase.reconnecting);
    expect(connection.status.attempt, 1);
    expect(waits.durations, <Duration>[const Duration(seconds: 1)]);
    expect(network.streams, hasLength(1), reason: 'no new stream before the wait');
    waits.releaseAll();
    await settle();
    expect(connection.status.phase, ConnectionPhase.online);
    await network.lastStream.end('rate-limited');
    await settle();
    expect(connection.status.attempt, 2, reason: 'a refusal right after going online does not reset the backoff');
    expect(network.streams, hasLength(2));
  });

  test('should wait out the server hint when the relay refuses the event stream', () async {
    network.down.add(ConnectionPath.direct);
    network.refuseStreamWith = const StreamRefused(429, 'rate-limited', retryAfter: Duration(seconds: 60));
    await connection.connect();
    expect(connection.status.phase, ConnectionPhase.reconnecting);
    expect(waits.durations, <Duration>[const Duration(seconds: 60)]);
  });

  test('should keep at most $maxRequestsInFlight requests in flight and send the rest as answers arrive', () async {
    network.handler = serverHandler(rest: (ApiRequest request) => const ApiResponse(200, <String, Object?>{}));
    await connection.connect();
    final List<Completer<void>> held = <Completer<void>>[];
    network.gate = (ConnectionPath path, ApiRequest request) {
      if (request.path != '/v1/catalog') return Future<void>.value();
      final Completer<void> answer = Completer<void>();
      held.add(answer);
      return answer.future;
    };
    final List<Future<ApiResponse>> answers = <Future<ApiResponse>>[
      for (int index = 0; index < maxRequestsInFlight + 4; index++) connection.request(const ApiRequest('GET', '/v1/catalog')),
    ];
    await settle();
    expect(held, hasLength(maxRequestsInFlight));
    held.first.complete();
    await settle();
    expect(held, hasLength(maxRequestsInFlight + 1));
    while (held.any((Completer<void> answer) => !answer.isCompleted)) {
      for (final Completer<void> answer in held.toList()) {
        if (!answer.isCompleted) answer.complete();
      }
      await settle();
    }
    expect((await Future.wait(answers)).every((ApiResponse answer) => answer.ok), isTrue);
    expect(held, hasLength(maxRequestsInFlight + 4));
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
    expect(waits.pending.single.$1, const Duration(seconds: 1));
    waits.releaseAll();
    await settle();
    expect(waits.pending.single.$1, const Duration(seconds: 2));
    network.down.clear();
    connection.retryNow();
    await settle();
    expect(connection.status.phase, ConnectionPhase.online);
  });

  test('should say the server did not answer when both paths are down', () async {
    network.down.addAll(ConnectionPath.values);
    await connection.connect();
    expect(connection.status.phase, ConnectionPhase.reconnecting);
    expect(connection.status.message, startsWith('Server did not answer on either path.'));
    expect(connection.status.message, isNot(contains('Relay')));
  });

  test('should name the path when a server paired with one path does not answer', () async {
    connection = make(serverProfile: const ServerProfile(fingerprint: fakeFingerprint, serverKey: fakeServerKey, name: 'Town', relayUrl: 'ws://127.0.0.1:9797'));
    network.down.add(ConnectionPath.relay);
    await connection.connect();
    expect(connection.status.message, isNot(contains('either path')), reason: 'one path names its own failure');
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

  test('should retry within 1 to 5 seconds for the first minute, then back off to 30 seconds', () {
    expect(<int>[for (int attempt = 1; attempt <= 12; attempt++) reconnectDelay(attempt, away: const Duration(seconds: 20)).inSeconds],
        <int>[1, 1, 2, 2, 3, 3, 4, 5, 5, 5, 5, 5]);
    expect(<int>[for (final int seconds in <int>[61, 95, 125, 300]) reconnectDelay(20, away: Duration(seconds: seconds)).inSeconds], <int>[10, 20, 30, 30]);
  });

  test('should stay subscribed on the relay while the server restarts and reconnect the moment it is back', () async {
    network.down.add(ConnectionPath.direct);
    await connection.connect();
    expect(connection.status.path, ConnectionPath.relay);
    final int relays = network.opened.where((ConnectionPath path) => path == ConnectionPath.relay).length;
    await network.serverLeaves();
    await settle();
    expect(connection.status.phase, ConnectionPhase.reconnecting);
    expect(connection.status.message, 'Server restarting. Reconnecting.');
    expect(network.transports.where((FakeTransport transport) => transport.path == ConnectionPath.relay && !transport.closed), hasLength(1),
        reason: 'the relay socket stays open');
    waits.releaseAll();
    await settle();
    expect(connection.status.phase, ConnectionPhase.reconnecting);
    expect(network.opened.where((ConnectionPath path) => path == ConnectionPath.relay).length, relays, reason: 'no new relay socket per retry');
    network.serverReturns();
    await settle();
    expect(connection.status.phase, ConnectionPhase.online, reason: 'online without waiting out the backoff');
    expect(network.lastStream.isOpen, isTrue);
  });

  test('should say the server is restarting when both paths go quiet after being online', () async {
    await connection.connect();
    network.down.addAll(ConnectionPath.values);
    await network.lastStream.end();
    await settle();
    expect(connection.status.phase, ConnectionPhase.reconnecting);
    expect(connection.status.message, 'Server restarting. Reconnecting.');
  });

  test('should send no counter on a read-only batch read', () async {
    network.handler = serverHandler(rest: (ApiRequest request) => const ApiResponse(200, <String, Object?>{}));
    await connection.connect();
    await connection.request(const ApiRequest('POST', '/v1/documents/read', body: <String, Object?>{'refs': <Object?>[]}, counted: false));
    final ApiRequest read = network.sent.last.$2;
    expect(read.headers['Authorization'], 'Bearer anm_tok.secret');
    expect(read.headers.containsKey('X-Animus-Counter'), isFalse);
  });

  test('should retry a rate-limited read after the server hint, at most five times, and return the last answer', () async {
    int refusals = 3;
    network.handler = serverHandler(rest: (ApiRequest request) {
      if (refusals-- > 0) {
        return const ApiResponse(429, <String, Object?>{'error': <String, Object?>{'code': 'rate-limited', 'message': 'slow', 'retryAfterMillis': 50}});
      }
      return const ApiResponse(200, <String, Object?>{'documents': <Object?>[]});
    });
    // Retry waits pass at once; the handshake budget never runs out.
    connection = ServerConnection(
      profile: profile,
      token: token,
      open: network.open,
      delay: (Duration wait) => wait == handshakeBudget ? Completer<void>().future : Future<void>.value(),
      handshakeTimeout: handshakeBudget,
    );
    await connection.connect();
    final ApiResponse answer = await connection.requestRetrying(const ApiRequest('GET', '/v1/catalog'));
    expect(answer.ok, isTrue);
    refusals = 100;
    final ApiResponse refused = await connection.requestRetrying(const ApiRequest('GET', '/v1/catalog'));
    expect(refused.errorCode, 'rate-limited');
    expect(refused.retryAfter, const Duration(milliseconds: 50));
    expect(network.sent.where(((ConnectionPath, ApiRequest) sent) => sent.$2.path == '/v1/catalog'), hasLength(4 + 6));
  });

  test('should let a burst of 38 requests go at once, pace the rest under 20 a second and wait out a refusal', () async {
    final RequestPacer pacer = RequestPacer();
    final Stopwatch watch = Stopwatch()..start();
    for (int index = 0; index < 38; index++) {
      await pacer.take();
    }
    expect(watch.elapsedMilliseconds, lessThan(40));
    await pacer.take();
    expect(watch.elapsedMilliseconds, greaterThanOrEqualTo(40), reason: 'the 39th waits for a token');
    pacer.hold(const Duration(milliseconds: 150));
    final int held = watch.elapsedMilliseconds;
    await pacer.take();
    expect(watch.elapsedMilliseconds - held, greaterThanOrEqualTo(140));
  });

  test('should give background requests the full rate while nothing else waits, never the last 12 tokens', () async {
    final RequestPacer pacer = RequestPacer();
    final Stopwatch watch = Stopwatch()..start();
    for (int index = 0; index < 26; index++) {
      await pacer.take(background: true);
    }
    expect(watch.elapsedMilliseconds, lessThan(40), reason: 'the burst less the reserve goes at once');
    int taken = 0;
    final Stopwatch more = Stopwatch()..start();
    while (more.elapsedMilliseconds < 500) {
      await pacer.take(background: true);
      taken++;
    }
    expect(taken, inInclusiveRange(7, 12), reason: 'then the full 18 a second');
    final Stopwatch rest = Stopwatch()..start();
    for (int index = 0; index < 12; index++) {
      await pacer.take();
    }
    expect(rest.elapsedMilliseconds, lessThan(40), reason: 'the reserve: a burst of reads or an Apply goes at once');
  });

  test('should send waiting requests first and keep background requests to half the rate meanwhile', () async {
    final RequestPacer pacer = RequestPacer();
    for (int index = 0; index < 26; index++) {
      await pacer.take(background: true);
    }
    int background = 0;
    bool loading = true;
    final Future<void> load = () async {
      while (loading) {
        await pacer.take(background: true);
        background++;
      }
    }();
    await Future<void>.delayed(const Duration(milliseconds: 200));
    final Stopwatch watch = Stopwatch()..start();
    final List<Future<void>> rest = <Future<void>>[for (int index = 0; index < 25; index++) pacer.take()];
    await Future.wait(rest.take(12));
    expect(watch.elapsedMilliseconds, lessThan(60), reason: 'the first twelve go from the reserve');
    final int before = background;
    await Future.wait(rest);
    final double seconds = watch.elapsedMilliseconds / 1000;
    loading = false;
    await load;
    expect(seconds, lessThan(2.2), reason: 'the other 13 get at least half the rate');
    expect(background - before, inInclusiveRange(1, (seconds * 9).ceil() + 2), reason: 'background keeps going at half the rate, no more');
  });

  test('should keep background requests to 4 in flight, so a request of the rest goes past them', () async {
    network.handler = serverHandler(rest: (ApiRequest request) => const ApiResponse(200, <String, Object?>{}));
    await connection.connect();
    final List<Completer<void>> held = <Completer<void>>[];
    network.gate = (ConnectionPath path, ApiRequest request) {
      if (!request.background) return Future<void>.value();
      final Completer<void> answer = Completer<void>();
      held.add(answer);
      return answer.future;
    };
    final List<Future<ApiResponse>> loads = <Future<ApiResponse>>[
      for (int index = 0; index < 10; index++) connection.request(ApiRequest('GET', '/v1/terrain/blocks?cx=$index', background: true)),
    ];
    await settle();
    expect(held, hasLength(4));
    expect((await connection.request(const ApiRequest('GET', '/v1/catalog')).timeout(const Duration(seconds: 1))).ok, isTrue);
    while (held.any((Completer<void> answer) => !answer.isCompleted)) {
      for (final Completer<void> answer in held.toList()) {
        if (!answer.isCompleted) answer.complete();
      }
      await settle();
    }
    expect((await Future.wait(loads)).every((ApiResponse answer) => answer.ok), isTrue);
  });

  group('handshake budget', () {
    const ServerProfile relayOnly = ServerProfile(fingerprint: fakeFingerprint, serverKey: fakeServerKey, name: 'Town', relayUrl: 'ws://127.0.0.1:9797');

    test('should give up on a relay whose hello never answers and go online over the direct path after the budget', () async {
      connection = make(serverProfile: profile.copyWith(lastPath: ConnectionPath.relay));
      network.gate = (ConnectionPath path, ApiRequest request) =>
          path == ConnectionPath.relay && request.path == '/v1/hello' ? Completer<void>().future : Future<void>.value();
      final Future<void> firstRound = connection.connect();
      await settle();
      expect(connection.status.phase, ConnectionPhase.connecting);
      expect(connection.status.path, ConnectionPath.relay);
      expect(waits.budgets, hasLength(1));
      waits.expireBudgets();
      await firstRound;
      expect(connection.status.phase, ConnectionPhase.online);
      expect(connection.status.path, ConnectionPath.direct);
      expect(network.transports.firstWhere((FakeTransport transport) => transport.path == ConnectionPath.relay).closed, isTrue);
      expect(connection.profile.lastPath, ConnectionPath.direct);
    });

    test('should close a path that opens after its budget ran out and blame the relay', () async {
      final Completer<ServerTransport> opening = Completer<ServerTransport>();
      connection = make(serverProfile: relayOnly, open: (ConnectionPath path, String url) => opening.future);
      await Future.wait(<Future<void>>[connection.connect(), settle().then((void _) => waits.expireBudgets())]);
      expect(connection.status.phase, ConnectionPhase.reconnecting);
      expect(connection.status.message, transportCopy('relay-unreachable').text);
      final FakeTransport late = FakeRelayTransport(network);
      opening.complete(late);
      await settle();
      expect(late.closed, isTrue);
      expect(network.streams, isEmpty);
      expect(connection.status.phase, ConnectionPhase.reconnecting);
    });

    test('should not cut a path that finished its handshake inside the budget', () async {
      await connection.connect();
      waits.expireBudgets();
      await settle();
      expect(connection.status.phase, ConnectionPhase.online);
      expect(network.lastStream.isOpen, isTrue);
    });

    test('should not go online when the handshake finishes after a disconnect', () async {
      final Completer<void> held = Completer<void>();
      network.gate = (ConnectionPath path, ApiRequest request) => request.path == '/v1/capabilities' ? held.future : Future<void>.value();
      unawaited(connection.connect());
      await settle();
      await connection.disconnect();
      held.complete();
      await settle();
      expect(connection.status.phase, ConnectionPhase.offline);
      expect(network.streams, isEmpty);
      expect(network.transports.every((FakeTransport transport) => transport.closed), isTrue);
    });

    test('should keep a parked relay open when the budget runs out after the server left it', () async {
      connection = make(serverProfile: relayOnly);
      network.serverOnRelay = false;
      await connection.connect();
      expect(connection.status.message, transportCopy('server-offline').text);
      waits.expireBudgets();
      await settle();
      expect(network.transports.single.closed, isFalse, reason: 'the parked relay hears the server come back');
    });
  });

  group('backoff while the page is visible', () {
    test('should cap the late backoff at 5 seconds while visible and keep 30 seconds while hidden', () {
      expect(<int>[for (final int seconds in <int>[61, 95, 125, 300]) reconnectDelay(20, away: Duration(seconds: seconds), visible: true).inSeconds], <int>[5, 5, 5, 5]);
      expect(reconnectDelay(3, away: const Duration(seconds: 20), visible: true), const Duration(seconds: 2), reason: 'the first minute is unchanged');
    });

    test('should read the page each time it waits', () async {
      bool visible = true;
      connection = make(visible: () => visible);
      network.down.addAll(ConnectionPath.values);
      await connection.connect();
      clock = clock.add(const Duration(minutes: 2));
      waits.releaseAll();
      await settle();
      expect(waits.durations, <Duration>[const Duration(seconds: 5)]);
      visible = false;
      waits.releaseAll();
      await settle();
      expect(waits.durations, <Duration>[const Duration(seconds: 30)]);
    });
  });

  group('revoking the pairing', () {
    ApiResponse revokes(ApiRequest request) {
      if (request.method != 'DELETE' || request.path != '/v1/token') return const ApiResponse(404, <String, Object?>{});
      // The server ends the event stream of the token it just revoked.
      network.lastStream.emit(<String, Object?>{'type': 'closing', 'reason': 'token-revoked'});
      unawaited(network.lastStream.end('token-revoked'));
      return const ApiResponse(204, null);
    }

    test('should send one uncounted DELETE /v1/token and say nothing of the stream the server then closes', () async {
      network.handler = serverHandler(rest: revokes);
      await connection.connect();
      final List<ConnectionStatus> heard = <ConnectionStatus>[];
      final List<Map<String, Object?>> events = <Map<String, Object?>>[];
      connection.statuses.listen(heard.add);
      connection.events.listen(events.add);
      expect(await connection.revoke(), isTrue);
      await settle();
      final List<ApiRequest> deletes = <ApiRequest>[for (final (ConnectionPath _, ApiRequest request) in network.sent) if (request.method == 'DELETE') request];
      expect(deletes, hasLength(1));
      expect(deletes.single.path, '/v1/token');
      expect(deletes.single.headers['Authorization'], 'Bearer anm_tok.secret');
      expect(deletes.single.headers.containsKey('X-Animus-Counter'), isFalse);
      expect(heard, isEmpty, reason: 'no pairing-revoked status reaches the session');
      expect(events, isEmpty);
      expect(waits.durations, <Duration>[const Duration(seconds: 3)], reason: 'the answer\'s timeout and no reconnect wait');
    });

    test('should count a stream the server closed as revoked when the answer itself was lost', () async {
      network.handler = serverHandler(rest: revokes);
      network.dropAfter = (ApiRequest request) => request.path == '/v1/token';
      await connection.connect();
      expect(await connection.revoke(), isTrue);
    });

    test('should say an older server that does not know the request did not revoke', () async {
      await connection.connect();
      expect(await connection.revoke(), isFalse);
    });

    test('should send nothing while not online', () async {
      network.down.addAll(ConnectionPath.values);
      await connection.connect();
      final int sent = network.sent.length;
      expect(await connection.revoke(), isFalse);
      expect(network.sent, hasLength(sent));
      expect(waits.pending.length, 1, reason: 'the backoff wait was let go, and no new one started');
    });

    test('should stop waiting for the answer after the timeout', () async {
      await connection.connect();
      network.gate = (ConnectionPath path, ApiRequest request) => request.path == '/v1/token' ? Completer<void>().future : Future<void>.value();
      final Future<bool> revoking = connection.revoke();
      await settle();
      expect(waits.release(const Duration(seconds: 3)), 1);
      expect(await revoking, isFalse);
    });
  });
}
