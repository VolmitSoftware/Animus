import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'api.dart';
import 'events.dart';
import 'protocol.dart';
import 'server.dart';

/// The request's headers, lower-cased, repeated values joined.
Map<String, String> requestHeaders(HttpRequest request) {
  final Map<String, String> headers = <String, String>{};
  request.headers.forEach((String name, List<String> values) => headers[name.toLowerCase()] = values.join(', '));
  return headers;
}

String peerAddress(HttpRequest request) => request.connectionInfo?.remoteAddress.address ?? 'unknown';

/// Writes [result] as the JSON response the direct listener sends.
Future<void> writeResponse(HttpResponse response, FakeResponse result) async {
  response.statusCode = result.status;
  response.headers.set('Cache-Control', 'no-store');
  response.headers.set('Referrer-Policy', 'no-referrer');
  result.headers.forEach(response.headers.set);
  if (result.body != null) {
    response.headers.contentType = ContentType('application', 'json', charset: 'utf-8');
    response.add(utf8.encode(jsonEncode(result.body)));
  }
  await response.close();
}

/// Ends the connection without an answer, so the browser's fetch fails with
/// a network error.
Future<void> destroyConnection(HttpRequest request) async {
  try {
    final Socket socket = await request.response.detachSocket(writeHeaders: false);
    socket.destroy();
  } on Object {
    // The connection is gone either way.
  }
}

/// The direct path: `http://127.0.0.1:<port>/animus/v1/...` and the
/// `WS /animus/v1/events` stream with its event-auth first frame.
class DirectPath {
  DirectPath(this.server);

  static const String base = '/animus';
  final FakeServer server;
  final Set<WebSocket> _sockets = <WebSocket>{};

  /// Closes every direct event socket, authenticated or not.
  void closeSockets() {
    for (final WebSocket socket in _sockets.toList()) {
      socket.close(1001, 'dropped');
    }
    _sockets.clear();
  }

  Future<void> serve(HttpRequest request) async {
    if (server.directDown) return destroyConnection(request);
    final String path = request.uri.path;
    final String target = path.startsWith('$base/') ? path.substring(base.length) : '';
    if (!target.startsWith('/v1/')) {
      await request.drain<void>();
      return writeResponse(request.response, ApiError('not-found', 'The editor API is under $base/v1/.').toResponse());
    }
    final Map<String, String> headers = requestHeaders(request);
    if (WebSocketTransformer.isUpgradeRequest(request)) {
      if (request.method != 'GET' || target != '/v1/events') {
        return writeResponse(request.response,
            invalid('Only GET /animus/v1/events upgrades to a WebSocket (version 13).').toResponse());
      }
      return _events(request, headers);
    }
    final String query = request.uri.hasQuery ? '?${request.uri.query}' : '';
    final int maximum = limit(target.startsWith('/v1/imports') ? 'importBodyBytes' : 'requestBodyBytes');
    Object? body;
    ApiError? refused;
    if (request.contentLength > maximum) {
      refused = ApiError('too-large', 'Request bodies are limited to $maximum bytes here.');
      await _refuseLarge(request, refused, headers, target + query);
      return;
    }
    final BytesBuilder bytes = BytesBuilder(copy: false);
    await for (final List<int> chunk in request) {
      bytes.add(chunk);
      if (bytes.length > maximum) {
        refused = ApiError('too-large', 'Request bodies are limited to $maximum bytes here.');
        request.response.persistentConnection = false;
        break;
      }
    }
    if (refused == null && bytes.isNotEmpty && target == '/v1/imports') {
      // As the real listener does: the upload's raw bytes reach the handler as base64.
      body = base64Encode(bytes.takeBytes());
    } else if (refused == null && bytes.isNotEmpty) {
      try {
        body = jsonDecode(utf8.decode(bytes.takeBytes()));
        if (body is! Map<String, Object?>) refused = invalid('The body is not one JSON object.');
      } on FormatException catch (failure) {
        refused = invalid('The body is not one JSON object: ${failure.message}');
      }
    }
    final FakeRequest call = FakeRequest(
      transport: Transport.direct,
      method: request.method,
      target: target + query,
      headers: headers,
      body: body,
      address: peerAddress(request),
    );
    final FakeResponse result = refused == null ? server.api.handle(call) : refused.toResponse().withHeaders(server.api.cors(call));
    if (result.isDropped) return destroyConnection(request);
    await writeResponse(request.response, result);
  }

  /// Answers an oversized request and closes, without reading its body.
  Future<void> _refuseLarge(HttpRequest request, ApiError refused, Map<String, String> headers, String target) async {
    final FakeRequest call = FakeRequest(transport: Transport.direct, method: request.method, target: target, headers: headers);
    request.response.persistentConnection = false;
    await writeResponse(request.response, refused.toResponse().withHeaders(server.api.cors(call)));
  }

  Future<void> _events(HttpRequest request, Map<String, String> headers) async {
    final String address = peerAddress(request);
    final WebSocket socket = await WebSocketTransformer.upgrade(request);
    _sockets.add(socket);
    bool authenticated = false;
    void Function()? remove;
    void send(Map<String, Object?> event) {
      try {
        socket.add(jsonEncode(event));
      } on Object {
        // The browser left; onDone removes the stream.
      }
    }

    final Timer deadline = Timer(Duration(seconds: limit('eventAuthSeconds')), () {
      if (!authenticated) socket.close(4401, 'unauthorized');
    });
    socket.listen(
      (Object? data) {
        if (authenticated) return;
        authenticated = true;
        deadline.cancel();
        final String? token = _authToken(data);
        if (token == null) {
          socket.close(4400, 'invalid-request');
          return;
        }
        final FakeRequest call = FakeRequest(
          transport: Transport.direct,
          method: 'GET',
          target: '/v1/events',
          headers: <String, String>{...headers, 'authorization': 'Bearer $token'},
          address: address,
        );
        final EventsOpened opened = server.api.openEvents(call, StreamPath.direct, send, () => socket.close(1000, 'closing'));
        if (!opened.open) {
          final Object? body = opened.response.body;
          final String code = body is Map<String, Object?> && body['error'] is Map<String, Object?>
              ? (body['error']! as Map<String, Object?>)['code']! as String
              : 'refused';
          socket.close(4000 + opened.response.status, code);
          return;
        }
        remove = opened.remove;
      },
      onDone: () {
        deadline.cancel();
        remove?.call();
        _sockets.remove(socket);
      },
      onError: (Object error) {},
      cancelOnError: true,
    );
  }

  /// The token of an `event-auth` frame, or null for anything else.
  static String? _authToken(Object? data) {
    if (data is! String) return null;
    try {
      final Object? frame = jsonDecode(data);
      if (frame is! Map<String, Object?> || frame.length != 2 || frame['type'] != 'auth') return null;
      final Object? token = frame['token'];
      return token is String && token.isNotEmpty ? token : null;
    } on FormatException {
      return null;
    }
  }
}
