import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:animus_editor/logic/json_schema.dart';
import 'package:animus_editor/logic/shared_schemas.dart';

import 'api.dart';
import 'direct.dart';
import 'events.dart';
import 'protocol.dart';
import 'server.dart';

/// The browser side of the relay at `ws://127.0.0.1:<port>/app`, as if the
/// relay and an agent connection to this server were one process. Requests
/// reach the same [FakeApi] as the direct path, with the handshake's Origin.
class RelayPath {
  RelayPath(this.server);

  final FakeServer server;
  final Set<_RelayConnection> _connections = <_RelayConnection>{};

  int get connections => _connections.length;

  Future<void> serve(HttpRequest request) async {
    if (server.relayDown) return destroyConnection(request);
    if (!WebSocketTransformer.isUpgradeRequest(request)) {
      return writeResponse(request.response, invalid('The relay emulation at /app takes WebSocket connections only.').toResponse());
    }
    final String? origin = request.headers.value('origin');
    if (origin == null || !server.settings.allowedOrigins.contains(origin)) {
      request.response.statusCode = HttpStatus.forbidden;
      await request.response.close();
      return;
    }
    final String address = peerAddress(request);
    final WebSocket socket = await WebSocketTransformer.upgrade(request);
    final _RelayConnection connection = _RelayConnection(this, socket, origin, address);
    _connections.add(connection);
    connection.start();
  }

  void closeSockets() {
    for (final _RelayConnection connection in _connections.toList()) {
      connection.socket.close(1001, 'dropped');
    }
    _connections.clear();
  }

  /// Sends a relay `error` frame with [code] to every browser and closes them.
  int failAll(String code) {
    final List<_RelayConnection> open = _connections.toList();
    for (final _RelayConnection connection in open) {
      connection._fail(code, 'Failed on purpose by /__fake/relay-error.');
    }
    return open.length;
  }

  /// Sends `status` frames to every subscribed browser of this server.
  void announce(bool online) {
    for (final _RelayConnection connection in _connections) {
      if (connection.subscribed && connection.matched) connection.send(<String, Object?>{'type': 'status', 'online': online});
    }
  }

  void _gone(_RelayConnection connection) => _connections.remove(connection);
}

class _RelayConnection {
  _RelayConnection(this.path, this.socket, this.origin, this.address);

  final RelayPath path;
  final WebSocket socket;
  final String origin;
  final String address;
  final Map<int, void Function()> _streams = <int, void Function()>{};
  bool subscribed = false;

  /// True when the subscription names this server's fingerprint.
  bool matched = false;
  Timer? _subscribeDeadline;

  FakeServer get server => path.server;

  bool get online => matched && !server.offline;

  void start() {
    _subscribeDeadline = Timer(const Duration(seconds: 10), () {
      if (!subscribed) _fail('timeout', 'Send a subscribe frame within 10 seconds.');
    });
    socket.listen(
      _message,
      onDone: _done,
      onError: (Object error) {},
      cancelOnError: true,
    );
  }

  void send(Map<String, Object?> frame) {
    try {
      socket.add(jsonEncode(frame));
    } on Object {
      // The browser left; onDone cleans up.
    }
  }

  void _fail(String code, String message) {
    send(<String, Object?>{'type': 'error', 'code': code, 'message': message});
    socket.close(relayCloseCode, code);
  }

  static int get relayCloseCode => (endpointTable['relay']! as Map<String, Object?>)['closeCode']! as int;

  void _done() {
    _subscribeDeadline?.cancel();
    for (final void Function() remove in _streams.values.toList()) {
      remove();
    }
    _streams.clear();
    path._gone(this);
  }

  bool _valid(String definition, Object? frame) =>
      SharedSchemas.embedded.validateMessage(definition, frame).isEmpty;

  void _message(Object? data) {
    if (data is! String) return _fail('invalid-frame', 'Frames are JSON text.');
    Object? frame;
    try {
      frame = jsonDecode(data);
    } on FormatException {
      return _fail('invalid-frame', 'A frame is not JSON.');
    }
    if (frame is! Map<String, Object?>) return _fail('invalid-frame', 'A frame is one JSON object.');
    final Object? type = frame['type'];
    if (!subscribed) {
      if (type != 'subscribe') return _fail('invalid-frame', 'The first frame is subscribe.');
      if (frame['protocol'] != 1) return _fail('unsupported-protocol', 'This relay speaks protocol 1.');
      if (!_valid('relay-subscribe', frame)) return _fail('invalid-frame', 'Not a relay-subscribe frame.');
      subscribed = true;
      _subscribeDeadline?.cancel();
      final String fingerprint = frame['fingerprint']! as String;
      matched = fingerprint == server.identity.fingerprint;
      send(<String, Object?>{'type': 'subscribed', 'fingerprint': fingerprint, 'online': online});
      return;
    }
    if (type == 'close') {
      if (!_valid('relay-close', frame)) return _fail('invalid-frame', 'Not a relay-close frame.');
      _streams.remove(frame['id']! as int)?.call();
      return;
    }
    if (type != 'request') return _fail('invalid-frame', 'Browsers send request and close frames after subscribing.');
    final List<SchemaError> errors = SharedSchemas.embedded.validateMessage('relay-request', frame);
    if (errors.isNotEmpty) return _fail('invalid-frame', 'Not a relay-request frame: ${errors.first}');
    _request(frame);
  }

  void _respond(int id, FakeResponse response) {
    if (response.isDropped) return send(<String, Object?>{'type': 'close', 'id': id});
    send(<String, Object?>{
      'type': 'response',
      'id': id,
      'status': response.status,
      if (response.headers.isNotEmpty) 'headers': response.headers,
      if (response.body != null) 'body': response.body,
    });
  }

  void _request(Map<String, Object?> frame) {
    final int id = frame['id']! as int;
    if (_streams.containsKey(id)) {
      return _respond(id, invalid('Request id $id is already in use on this connection.').toResponse());
    }
    if (!online) {
      return _respond(id, ApiError('server-offline', 'The server is not connected to the relay.').toResponse());
    }
    final Map<String, String> headers = <String, String>{};
    final Object? given = frame['headers'];
    if (given is Map<String, Object?>) {
      for (final MapEntry<String, Object?> entry in given.entries) {
        final String name = entry.key.toLowerCase();
        if (name == 'forwarded' || name == 'host' || name == 'origin' || name == 'x-forwarded-for') continue;
        headers[name] = entry.value! as String;
      }
    }
    headers['origin'] = origin;
    headers['x-forwarded-for'] = address;
    final FakeRequest call = FakeRequest(
      transport: Transport.relay,
      method: frame['method']! as String,
      target: frame['path']! as String,
      headers: headers,
      body: frame['body'],
      address: address,
    );
    if (call.method == 'GET' && call.route == '/v1/events') return _stream(id, call);
    _respond(id, server.api.handle(call));
  }

  void _stream(int id, FakeRequest call) {
    bool ended = false;
    void end() {
      if (ended) return;
      ended = true;
      _streams.remove(id);
      send(<String, Object?>{'type': 'close', 'id': id});
    }

    final EventsOpened opened = server.api.openEvents(
      call,
      StreamPath.relay,
      (Map<String, Object?> event) => send(<String, Object?>{'type': 'event', 'id': id, 'event': event}),
      end,
    );
    _respond(id, opened.response);
    if (!opened.open) return;
    final void Function() remove = opened.remove!;
    _streams[id] = () {
      ended = true;
      remove();
    };
  }
}
