import 'dart:async';

import 'package:animus_editor/services/server/server_connection.dart';
import 'package:animus_editor/services/server/server_profiles.dart';
import 'package:animus_editor/services/server/transport.dart';
import 'package:test/test.dart';

import 'support/fake_transport.dart';

final ServerToken token = ServerToken(token: 'anm_tok.secret', tokenId: 'tok', scopes: const <String>['read'], expiresAt: DateTime.utc(2030));

const ServerProfile profile = ServerProfile(
  fingerprint: fixtureFingerprint,
  serverKey: fixtureServerKey,
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
    expect(connection.status.message, contains('not the paired server'));
    expect(network.sent.where((( ConnectionPath, ApiRequest) sent) => sent.$2.path != '/v1/hello'), isEmpty);
    expect(waits.pending, isEmpty);
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

  test('should double the reconnect wait up to 30 seconds', () {
    expect(<int>[for (int attempt = 1; attempt <= 7; attempt++) reconnectDelay(attempt).inSeconds], <int>[1, 2, 4, 8, 16, 30, 30]);
  });
}
