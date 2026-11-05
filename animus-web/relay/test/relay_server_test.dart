import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:animus_relay/animus_relay.dart';
import 'package:test/test.dart';
import 'package:web_socket_channel/io.dart';

import 'support.dart';

const String _origin = 'http://127.0.0.1:8098';

/// One test WebSocket with a frame queue.
final class _Peer {
  final IOWebSocketChannel channel;
  final StreamQueueLite frames;

  _Peer(this.channel) : frames = StreamQueueLite(channel.stream);

  void send(Map<String, Object?> frame) => channel.sink.add(jsonEncode(frame));

  void sendText(String text) => channel.sink.add(text);

  Future<Map<String, Object?>> next() async =>
      jsonDecode(await frames.next() as String) as Map<String, Object?>;

  Future<void> close() => channel.sink.close();
}

/// A minimal queue over a single-subscription stream.
final class StreamQueueLite {
  final List<Object?> _buffer = <Object?>[];
  final List<Completer<Object?>> _waiters = <Completer<Object?>>[];
  bool done = false;

  StreamQueueLite(Stream<Object?> stream) {
    stream.listen(
      (Object? value) {
        if (_waiters.isNotEmpty) {
          _waiters.removeAt(0).complete(value);
        } else {
          _buffer.add(value);
        }
      },
      onDone: () {
        done = true;
        for (final Completer<Object?> waiter in _waiters) {
          waiter.completeError(StateError('closed'));
        }
        _waiters.clear();
      },
      onError: (Object error) {},
    );
  }

  Future<Object?> next() {
    if (_buffer.isNotEmpty) return Future<Object?>.value(_buffer.removeAt(0));
    if (done) return Future<Object?>.error(StateError('closed'));
    final Completer<Object?> waiter = Completer<Object?>();
    _waiters.add(waiter);
    return waiter.future.timeout(const Duration(seconds: 5));
  }
}

void main() {
  late HttpServer server;
  late Uri base;

  Future<_Peer> connect(String path, {String? origin}) async {
    final IOWebSocketChannel channel = IOWebSocketChannel.connect(
      base.resolve(path),
      headers: <String, Object>{'Origin': ?origin},
    );
    await channel.ready;
    return _Peer(channel);
  }

  Future<_Peer> registeredAgent(TestServer identity) async {
    final _Peer agent = await connect('/agent');
    final Map<String, Object?> challenge = await agent.next();
    expect(challenge['type'], 'challenge');
    expect(challenge['protocol'], relayProtocol);
    agent.sendText(await identity.register(challenge['nonce']! as String));
    expect(await agent.next(), <String, Object?>{
      'type': 'registered',
      'fingerprint': identity.fingerprint,
    });
    return agent;
  }

  setUp(() async {
    server = await startRelayServer(
      port: 0,
      address: '127.0.0.1',
      allowedAppOrigins: const <String>[_origin],
      limits: const RelayLimits(
        handshakeTimeout: Duration(milliseconds: 300),
        maxAppMessagesPerWindow: 5,
      ),
    );
    base = Uri.parse('ws://127.0.0.1:${server.port}/');
  });

  tearDown(() => server.close(force: true));

  test(
    'should relay a request, its response and a stream end to end',
    () async {
      final TestServer identity = await TestServer.create();
      final _Peer agent = await registeredAgent(identity);
      final _Peer browser = await connect('/app', origin: _origin);
      browser.send(<String, Object?>{
        'type': 'subscribe',
        'protocol': 1,
        'fingerprint': identity.fingerprint,
      });
      expect(await browser.next(), <String, Object?>{
        'type': 'subscribed',
        'fingerprint': identity.fingerprint,
        'online': true,
      });

      browser.sendText(fixtureText('editor/messages/relay-request.0.json'));
      final Map<String, Object?> forwarded = await agent.next();
      expect(forwarded['id'], 1);
      expect(forwarded['body'], <String, Object?>{'documents': <Object?>[]});
      expect(
        (forwarded['headers']! as Map<String, Object?>)['Origin'],
        _origin,
      );
      expect(
        (forwarded['headers']! as Map<String, Object?>)['X-Forwarded-For'],
        '127.0.0.1',
      );
      agent.send(<String, Object?>{
        'type': 'response',
        'id': 1,
        'status': 200,
        'body': <String, Object?>{'valid': true, 'documents': <Object?>[]},
      });
      expect(
        await browser.next(),
        readFixture('editor/messages/relay-response.0.json'),
      );

      browser.sendText(fixtureText('editor/messages/relay-request.1.json'));
      expect((await agent.next())['path'], '/v1/events');
      agent.send(<String, Object?>{
        'type': 'response',
        'id': 2,
        'status': 200,
        'body': <String, Object?>{},
      });
      expect((await browser.next())['status'], 200);
      agent.send(<String, Object?>{
        'type': 'event',
        'id': 2,
        'event': readFixture('editor/messages/event.0.json'),
      });
      expect(
        await browser.next(),
        readFixture('editor/messages/relay-event.0.json'),
      );

      final HttpClient client = HttpClient();
      final HttpClientResponse health = await (await client.getUrl(
        Uri.parse('http://127.0.0.1:${server.port}/healthz'),
      )).close();
      final Map<String, Object?> counts =
          jsonDecode(await utf8.decodeStream(health)) as Map<String, Object?>;
      client.close();
      expect(counts, containsPair('servers', 1));
      expect(counts, containsPair('streams', 1));

      await browser.close();
      expect(await agent.next(), <String, Object?>{'type': 'close', 'id': 2});
      await agent.close();
    },
  );

  test(
    'should refuse browsers without an allowed Origin and servers with one',
    () async {
      await expectLater(connect('/app'), throwsA(anything));
      await expectLater(
        connect('/app', origin: 'https://evil.example'),
        throwsA(anything),
      );
      await expectLater(connect('/agent', origin: _origin), throwsA(anything));
    },
  );

  test('should close a server that signs with another key', () async {
    final TestServer identity = await TestServer.create();
    final TestServer other = await TestServer.create();
    final _Peer agent = await connect('/agent');
    final Map<String, Object?> challenge = await agent.next();
    agent.send(<String, Object?>{
      'type': 'register',
      'protocol': 1,
      'serverKey': identity.serverKey,
      'signature': await other.sign(challenge['nonce']! as String),
    });
    expect(
      await agent.next(),
      containsPair('code', RelayErrorCode.handshakeFailed),
    );
    expect(agent.channel.closeCode, anyOf(isNull, policyCloseCode));
  });

  test('should close a server that does not register in time', () async {
    final _Peer agent = await connect('/agent');
    await agent.next();
    expect(await agent.next(), containsPair('code', RelayErrorCode.timeout));
  });

  test('should close a browser over its message rate', () async {
    final _Peer browser = await connect('/app', origin: _origin);
    browser.send(<String, Object?>{
      'type': 'subscribe',
      'protocol': 1,
      'fingerprint': 'sha256:${'c' * 64}',
    });
    expect((await browser.next())['online'], isFalse);
    for (int index = 1; index <= 6; index++) {
      browser.send(request(index));
    }
    final List<Map<String, Object?>> seen = <Map<String, Object?>>[];
    while (true) {
      final Map<String, Object?> frame = await browser.next();
      seen.add(frame);
      if (frame['type'] == 'error') break;
    }
    expect(seen.last, containsPair('code', RelayErrorCode.rateLimited));
    expect(
      seen.where((Map<String, Object?> f) => f['type'] == 'response'),
      hasLength(4),
    );
  });

  test(
    'should close a browser that sends a request before subscribing',
    () async {
      final _Peer browser = await connect('/app', origin: _origin);
      browser.send(request(1));
      expect(
        await browser.next(),
        containsPair('code', RelayErrorCode.invalidFrame),
      );
    },
  );
}
