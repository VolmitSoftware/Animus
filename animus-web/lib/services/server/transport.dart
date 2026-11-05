import 'dart:async';

/// How the browser reaches a server: the plugin's own listener, or the relay
/// the plugin connects out to.
enum ConnectionPath {
  direct('Direct'),
  relay('Relay');

  const ConnectionPath(this.label);

  final String label;
}

class ApiRequest {
  const ApiRequest(this.method, this.path, {this.headers = const <String, String>{}, this.body});

  /// GET, POST or DELETE.
  final String method;

  /// `/v1/...`, with the query.
  final String path;
  final Map<String, String> headers;

  /// A JSON value, or null for no body.
  final Object? body;

  ApiRequest withHeaders(Map<String, String> extra) =>
      ApiRequest(method, path, headers: <String, String>{...headers, ...extra}, body: body);
}

class ApiResponse {
  const ApiResponse(this.status, this.body, {this.headers = const <String, String>{}});

  final int status;

  /// The decoded JSON body, or null.
  final Object? body;
  final Map<String, String> headers;

  bool get ok => status >= 200 && status < 300;

  /// The `error-response` code, when the body is one.
  String? get errorCode {
    final Object? value = body;
    if (value is Map<String, Object?> && value['error'] is Map<String, Object?>) {
      final Object? code = (value['error']! as Map<String, Object?>)['code'];
      return code is String ? code : null;
    }
    return null;
  }

  String get errorMessage {
    final Object? value = body;
    if (value is Map<String, Object?> && value['error'] is Map<String, Object?>) {
      final Object? message = (value['error']! as Map<String, Object?>)['message'];
      if (message is String) return message;
    }
    return 'HTTP $status';
  }
}

/// The path could not carry the request: a network failure, a closed relay
/// socket, a server the relay reports offline, or a timeout. Distinct from an
/// HTTP error answer, which arrives as an [ApiResponse].
class TransportFailure implements Exception {
  const TransportFailure(this.path, this.message);

  final ConnectionPath path;
  final String message;

  @override
  String toString() => '${path.label}: $message';
}

/// The server refused to open the event stream (a relay error answer).
class StreamRefused implements Exception {
  const StreamRefused(this.status, this.code);

  final int status;
  final String code;

  @override
  String toString() => 'event stream refused: $status $code';
}

/// One open `GET /v1/events` stream.
abstract class EventSubscription {
  /// Event frames (the `event` definition). Done when the stream closes; an
  /// error closes it because the path failed.
  Stream<Map<String, Object?>> get events;

  /// Why the server closed the stream, when it said (`4000 + status` and the
  /// error code on the direct path, the `closing` event otherwise).
  String? get closeReason;

  Future<void> cancel();
}

/// One way to a server. Implementations: the browser's fetch and WebSocket
/// (direct), a relay socket (relay), and in-memory fakes in tests.
abstract class ServerTransport {
  ConnectionPath get path;

  /// Sends one request. Throws [TransportFailure] when the path fails.
  Future<ApiResponse> send(ApiRequest request);

  /// Opens the event stream with the bearer [token].
  Future<EventSubscription> subscribe(String token);

  /// Closes the path and fails everything still open on it.
  Future<void> close();
}

typedef TransportFactory = Future<ServerTransport> Function(ConnectionPath path, String url);
