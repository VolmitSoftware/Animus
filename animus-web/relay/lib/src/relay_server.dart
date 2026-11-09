import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:animus_relay/src/frame_guard.dart';
import 'package:animus_relay/src/relay_broker.dart';
import 'package:animus_relay/src/relay_channel_sink.dart';
import 'package:animus_relay/src/relay_frame.dart';
import 'package:animus_relay/src/relay_limits.dart';
import 'package:animus_relay/src/relay_sessions.dart';
import 'package:animus_relay/src/server_identity.dart';
import 'package:shelf/shelf.dart';
import 'package:shelf/shelf_io.dart' as shelf_io;
import 'package:stream_channel/stream_channel.dart';
import 'package:web_socket_channel/io.dart';
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
  final _AddressCounts agentsByAddress = _AddressCounts();
  final _AddressCounts appsByAddress = _AddressCounts();

  void releaseAgent(String address) {
    agentConnections--;
    agentsByAddress.remove(address);
  }

  void releaseApp(String address) {
    appConnections--;
    appsByAddress.remove(address);
  }

  Handler agentHandler(String address) => _upgrade(limits, (
    WebSocketChannel channel,
  ) {
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
        releaseAgent(address);
        sink.markClosed();
        session.onDone();
      },
      onError: (Object error) {},
      cancelOnError: false,
    );
  }, () => releaseAgent(address));

  Handler appHandler(String origin, String clientAddress) => _upgrade(limits, (
    WebSocketChannel channel,
  ) {
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
          sink.closeWithError(RelayErrorCode.invalidFrame, 'text frames only');
          return;
        }
        session.onText(message);
      },
      onDone: () {
        releaseApp(clientAddress);
        sink.markClosed();
        session.onDone();
      },
      onError: (Object error) {},
      cancelOnError: false,
    );
  }, () => releaseApp(clientAddress));

  FutureOr<Response> handler(Request request) {
    final String path = request.url.path;
    final String? origin = request.headers['origin'];
    if (path == 'agent') {
      if (origin != null) return _json(403, 'servers send no Origin');
      final String address = clientAddress(request);
      if (agentConnections >= limits.maxAgents * 2 ||
          agentsByAddress[address] >= limits.maxAgentConnectionsPerAddress) {
        return _json(503, 'too many connections');
      }
      agentConnections++;
      agentsByAddress.add(address);
      return agentHandler(address)(request);
    }
    if (path == 'app') {
      if (origin == null || !origins.contains(origin.toLowerCase())) {
        return _json(403, 'origin not allowed');
      }
      final String address = clientAddress(request);
      if (appConnections >= limits.maxApps ||
          appsByAddress[address] >= limits.maxAppConnectionsPerAddress) {
        return _json(503, 'too many connections');
      }
      appConnections++;
      appsByAddress.add(address);
      return appHandler(origin.toLowerCase(), address)(request);
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

/// The caller's address: the last `X-Forwarded-For` entry, which Cloud Run's
/// front end appends, else the socket peer. Earlier entries come from the
/// client and are ignored. An entry that is not an IP address counts as
/// missing.
String clientAddress(Request request) {
  final String? forwarded = request.headers['x-forwarded-for'];
  if (forwarded != null) {
    final InternetAddress? last = InternetAddress.tryParse(
      forwarded.split(',').last.trim(),
    );
    if (last != null) return last.address;
  }
  final Object? info = request.context['shelf.io.connection_info'];
  return info is HttpConnectionInfo ? info.remoteAddress.address : 'unknown';
}

/// Open connections per address. Entries leave at zero, so the map holds
/// only addresses with a live connection.
final class _AddressCounts {
  final Map<String, int> _counts = <String, int>{};

  int operator [](String address) => _counts[address] ?? 0;

  void add(String address) => _counts[address] = this[address] + 1;

  void remove(String address) {
    final int left = this[address] - 1;
    if (left <= 0) {
      _counts.remove(address);
    } else {
      _counts[address] = left;
    }
  }
}

/// A WebSocket upgrade handler whose socket sits behind a [GuardedSocket], so
/// a message announcing more than `maxFrameBytes` closes the connection
/// before its payload is buffered. Origin checks happen before this. The
/// caller counts the connection first; [onRefused] undoes that when the
/// request is not a valid upgrade.
Handler _upgrade(
  RelayLimits limits,
  void Function(WebSocketChannel channel) onConnection,
  void Function() onRefused,
) => (Request request) {
  final String? connection = request.headers['connection'];
  final String? key = request.headers['sec-websocket-key'];
  if (request.method != 'GET' ||
      request.headers['upgrade']?.toLowerCase() != 'websocket' ||
      connection == null ||
      !connection
          .toLowerCase()
          .split(',')
          .map((String token) => token.trim())
          .contains('upgrade')) {
    onRefused();
    return _json(404, 'WebSocket connections only');
  }
  if (request.headers['sec-websocket-version'] != '13' ||
      key == null ||
      request.protocolVersion != '1.1') {
    onRefused();
    return _json(400, 'not a WebSocket 13 upgrade');
  }
  request.hijack((StreamChannel<List<int>> channel) {
    final Socket socket = channel.sink as Socket;
    socket.add(
      utf8.encode(
        'HTTP/1.1 101 Switching Protocols\r\n'
        'Upgrade: websocket\r\n'
        'Connection: Upgrade\r\n'
        'Sec-WebSocket-Accept: ${WebSocketChannel.signKey(key)}\r\n\r\n',
      ),
    );
    final WebSocket webSocket = WebSocket.fromUpgradedSocket(
      GuardedSocket(socket, limits.maxFrameBytes),
      serverSide: true,
    )..pingInterval = limits.pingInterval;
    onConnection(IOWebSocketChannel(webSocket));
  });
};

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
