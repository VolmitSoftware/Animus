import 'dart:async';
import 'dart:collection';
import 'dart:convert';
import 'dart:io';

import 'package:animus_relay/src/relay_broker.dart';
import 'package:animus_relay/src/relay_frame.dart';
import 'package:animus_relay/src/relay_limits.dart';
import 'package:animus_relay/src/relay_sessions.dart';
import 'package:animus_relay/src/relay_sink.dart';
import 'package:animus_relay/src/server_identity.dart';
import 'package:shelf/shelf.dart';
import 'package:shelf/shelf_io.dart' as shelf_io;
import 'package:shelf_web_socket/shelf_web_socket.dart';
import 'package:web_socket_channel/web_socket_channel.dart';

/// The browser origin the hosted editor uses (`animus-editor.web.app`).
const List<String> defaultAllowedAppOrigins = <String>[
  'https://animus-editor.web.app',
];

/// Close code sent after a `relay-error` frame.
const int policyCloseCode = 4008;

/// A [RelaySink] over one WebSocket with a bounded send queue.
final class _ChannelSink implements RelaySink {
  final WebSocketChannel _channel;
  final int _maximumQueued;
  final Queue<String> _queue = Queue<String>();
  bool _draining = false;
  bool _closing = false;
  bool _closed = false;

  _ChannelSink(this._channel, this._maximumQueued);

  @override
  void send(String frame) {
    if (_closing || _closed) return;
    if (_queue.length >= _maximumQueued) {
      closeWithError(RelayErrorCode.queueFull, 'send queue is full');
      return;
    }
    _queue.add(frame);
    _drain();
  }

  @override
  void closeWithError(String code, String message) {
    if (_closing || _closed) return;
    _closing = true;
    if (_queue.length >= _maximumQueued) _queue.clear();
    _queue.add(jsonEncode(RelayFrames.error(code, message)));
    _drain();
  }

  void markClosed() {
    _closed = true;
    _closing = true;
    _queue.clear();
  }

  void _drain() {
    if (_draining || _closed) return;
    unawaited(_drainQueue());
  }

  /// Sends one frame at a time, so a slow reader fills [_queue] instead of an
  /// unbounded socket buffer.
  Future<void> _drainQueue() async {
    _draining = true;
    try {
      while (_queue.isNotEmpty && !_closed) {
        await _channel.sink.addStream(
          Stream<String>.value(_queue.removeFirst()),
        );
      }
    } on Object {
      markClosed();
    } finally {
      _draining = false;
    }
    if (_closing && !_closed && _queue.isEmpty) {
      _closed = true;
      await _channel.sink.close(policyCloseCode, 'relay policy');
      return;
    }
    if (_queue.isNotEmpty && !_closed) _drain();
  }
}

/// Starts the relay: `/agent` for servers, `/app` for browsers and
/// `GET /healthz` with connection counts.
///
/// Browser handshakes must carry an `Origin` equal to one of
/// [allowedAppOrigins]. Server handshakes must carry none, since servers are not
/// browsers.
Future<HttpServer> startRelayServer({
  required int port,
  String address = '0.0.0.0',
  RelayLimits limits = const RelayLimits(),
  Iterable<String> allowedAppOrigins = defaultAllowedAppOrigins,
  ChallengeVerifier? verifier,
}) async {
  final RelayBroker broker = RelayBroker(limits: limits);
  final ChallengeVerifier challenges = verifier ?? ChallengeVerifier();
  final Set<String> origins = normalizeOrigins(allowedAppOrigins);
  int agentConnections = 0;
  int appConnections = 0;

  Handler agentHandler() => webSocketHandler((
    WebSocketChannel channel,
    String? protocol,
  ) {
    agentConnections++;
    final _ChannelSink sink = _ChannelSink(
      channel,
      limits.maxAgentOutboundQueue,
    );
    final AgentSession session = AgentSession(
      broker: broker,
      verifier: challenges,
      sink: sink,
    );
    Future<void> processing = Future<void>.value();
    channel.stream.listen(
      (Object? message) {
        if (message is! String) {
          session.onDone();
          sink.closeWithError(RelayErrorCode.invalidFrame, 'text frames only');
          return;
        }
        processing = processing.then((void _) => session.onText(message));
      },
      onDone: () {
        agentConnections--;
        sink.markClosed();
        session.onDone();
      },
      onError: (Object error) {},
      cancelOnError: false,
    );
  }, pingInterval: limits.pingInterval);

  Handler appHandler(String origin, String? clientAddress) => webSocketHandler(
    (WebSocketChannel channel, String? protocol) {
      appConnections++;
      final _ChannelSink sink = _ChannelSink(
        channel,
        limits.maxAppOutboundQueue,
      );
      final AppSession session = AppSession(
        broker: broker,
        sink: sink,
        origin: origin,
        address: clientAddress,
      );
      channel.stream.listen(
        (Object? message) {
          if (message is! String) {
            session.onDone();
            sink.closeWithError(
              RelayErrorCode.invalidFrame,
              'text frames only',
            );
            return;
          }
          session.onText(message);
        },
        onDone: () {
          appConnections--;
          sink.markClosed();
          session.onDone();
        },
        onError: (Object error) {},
        cancelOnError: false,
      );
    },
    allowedOrigins: origins,
    pingInterval: limits.pingInterval,
  );

  final Handler agent = agentHandler();

  FutureOr<Response> handler(Request request) {
    final String path = request.url.path;
    final String? origin = request.headers['origin'];
    if (path == 'agent') {
      if (origin != null) return _json(403, 'servers send no Origin');
      if (agentConnections >= limits.maxAgents * 2) {
        return _json(503, 'too many connections');
      }
      return agent(request);
    }
    if (path == 'app') {
      if (origin == null || !origins.contains(origin.toLowerCase())) {
        return _json(403, 'origin not allowed');
      }
      if (appConnections >= limits.maxApps) {
        return _json(503, 'too many connections');
      }
      return appHandler(origin.toLowerCase(), _clientAddress(request))(request);
    }
    if (path == 'healthz' && request.method == 'GET') {
      return Response.ok(
        jsonEncode(<String, Object?>{
          'status': 'ok',
          'protocol': relayProtocol,
          'servers': broker.onlineCount,
          'apps': broker.appCount,
          'pending': broker.pendingCount,
          'streams': broker.streamCount,
        }),
        headers: _jsonHeaders,
      );
    }
    return _json(404, 'not found');
  }

  return shelf_io.serve(handler, address, port, poweredByHeader: null);
}

const Map<String, String> _jsonHeaders = <String, String>{
  HttpHeaders.contentTypeHeader: 'application/json; charset=utf-8',
  HttpHeaders.cacheControlHeader: 'no-store',
};

Response _json(int status, String message) => Response(
  status,
  body: jsonEncode(<String, Object?>{'error': message}),
  headers: _jsonHeaders,
);

/// The first `X-Forwarded-For` entry (Cloud Run sets it), else the peer.
String? _clientAddress(Request request) {
  final String? forwarded = request.headers['x-forwarded-for'];
  if (forwarded != null && forwarded.trim().isNotEmpty) {
    final String first = forwarded.split(',').first.trim();
    if (first.isNotEmpty && first.length <= 64) return first;
  }
  final Object? info = request.context['shelf.io.connection_info'];
  return info is HttpConnectionInfo ? info.remoteAddress.address : null;
}

/// Exact `scheme://host[:port]` origins, lower-cased. Throws [ArgumentError]
/// for anything else, including wildcards and paths.
Set<String> normalizeOrigins(Iterable<String> origins) {
  final Set<String> normalized = <String>{};
  for (final String raw in origins) {
    final String origin = raw.trim();
    if (origin.isEmpty) continue;
    final Uri? uri = Uri.tryParse(origin);
    if (uri == null ||
        !uri.hasAuthority ||
        uri.host.isEmpty ||
        uri.host.contains('*') ||
        (uri.scheme != 'https' && uri.scheme != 'http') ||
        uri.userInfo.isNotEmpty ||
        (uri.path.isNotEmpty && uri.path != '/') ||
        uri.hasQuery ||
        uri.hasFragment) {
      throw ArgumentError.value(raw, 'allowedAppOrigins');
    }
    normalized.add('${uri.scheme}://${uri.authority}'.toLowerCase());
  }
  if (normalized.isEmpty) {
    throw ArgumentError.value(origins, 'allowedAppOrigins', 'is empty');
  }
  return Set<String>.unmodifiable(normalized);
}
