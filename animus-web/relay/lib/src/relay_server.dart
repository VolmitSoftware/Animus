import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:animus_relay/src/relay_broker.dart';
import 'package:animus_relay/src/relay_channel_sink.dart';
import 'package:animus_relay/src/relay_frame.dart';
import 'package:animus_relay/src/relay_limits.dart';
import 'package:animus_relay/src/relay_sessions.dart';
import 'package:animus_relay/src/server_identity.dart';
import 'package:shelf/shelf.dart';
import 'package:shelf/shelf_io.dart' as shelf_io;
import 'package:shelf_web_socket/shelf_web_socket.dart';
import 'package:web_socket_channel/web_socket_channel.dart';

/// The browser origin the hosted editor uses (`animus-editor.web.app`).
const List<String> defaultAllowedAppOrigins = <String>[
  'https://animus-editor.web.app',
];

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
    final ChannelSink sink = ChannelSink(
      channel.sink,
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
      final ChannelSink sink = ChannelSink(
        channel.sink,
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
