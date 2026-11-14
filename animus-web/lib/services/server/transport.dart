import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'error_copy.dart';

/// How the browser reaches a server: the plugin's own listener, or the relay
/// the plugin connects out to.
enum ConnectionPath {
  direct('Direct'),
  relay('Relay');

  const ConnectionPath(this.label);

  final String label;
}

class ApiRequest {
  const ApiRequest(this.method, this.path, {this.headers = const <String, String>{}, this.body, this.bytes, this.counted = true, this.background = false});

  /// GET, POST or DELETE.
  final String method;

  /// `/v1/...`, with the query.
  final String path;
  final Map<String, String> headers;

  /// A JSON value, or null for no body.
  final Object? body;

  /// A file upload instead of [body]: the raw bytes on the direct path, a
  /// base64 JSON string over the relay (whose frames carry JSON).
  final Uint8List? bytes;

  /// A POST or DELETE carries `X-Animus-Counter` unless it only reads (the
  /// batch read), which may run several at once in any order.
  final bool counted;

  /// A bulk read that yields to every other request (terrain): it takes the
  /// connection's whole rate only while nothing else waits.
  final bool background;

  ApiRequest withHeaders(Map<String, String> extra) =>
      ApiRequest(method, path, headers: <String, String>{...headers, ...extra}, body: body, bytes: bytes, counted: counted, background: background);

  /// The JSON a relay frame carries for this request's body.
  Object? get relayBody => bytes == null ? body : base64Encode(bytes!);
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

  /// What went wrong and what to do next, from the error code (or the
  /// status when the body has none). A missing permission is named when the
  /// server says which.
  ErrorCopy get problem => errorCode == 'scope-missing' ? scopeMissingCopy(missingScope) : serverCopy(errorCode, status: status);

  /// The permission a `scope-missing` refusal lacks (`error.scope`), when
  /// the server names it.
  String? get missingScope {
    final Object? value = body;
    if (value is Map<String, Object?> && value['error'] is Map<String, Object?>) {
      final Object? scope = (value['error']! as Map<String, Object?>)['scope'];
      if (scope is String && scope.isNotEmpty) return scope;
    }
    return null;
  }

  /// How long the server asked to wait before retrying a `rate-limited` or
  /// `busy` answer: `retryAfterMillis`, else `Retry-After`; null otherwise.
  Duration? get retryAfter {
    if (status != 429 && status != 503) return null;
    final Object? value = body;
    if (value is Map<String, Object?> && value['error'] is Map<String, Object?>) {
      final Object? millis = (value['error']! as Map<String, Object?>)['retryAfterMillis'];
      if (millis is num && millis >= 0) return Duration(milliseconds: millis.toInt());
    }
    final int? seconds = int.tryParse(headers['Retry-After'] ?? headers['retry-after'] ?? '');
    return seconds == null ? null : Duration(seconds: seconds);
  }

  /// The `error-response` message: the server's own words, shown under the
  /// plain-words [problem] where they add detail.
  String? get serverMessage {
    final Object? value = body;
    if (value is Map<String, Object?> && value['error'] is Map<String, Object?>) {
      final Object? message = (value['error']! as Map<String, Object?>)['message'];
      if (message is String && message.isNotEmpty) return message;
    }
    return null;
  }
}

/// The path could not carry the request: a network failure, a closed relay
/// socket, a server the relay reports offline, or a timeout. Distinct from an
/// HTTP error answer, which arrives as an [ApiResponse].
class TransportFailure implements Exception {
  const TransportFailure(this.path, this.code, [this.detail]);

  final ConnectionPath path;

  /// A key of [transportErrorCopy] or [relayErrorCopy].
  final String code;

  /// What the browser reported, for the console only.
  final String? detail;

  ErrorCopy get copy => transportCopy(code);

  /// The plain-words cause and next action.
  String get message => copy.text;

  @override
  String toString() => '${path.label}: $code${detail == null ? '' : ' ($detail)'}';
}

/// The server refused to open the event stream (a relay error answer).
class StreamRefused implements Exception {
  const StreamRefused(this.status, this.code, {this.retryAfter});

  final int status;
  final String code;

  /// The server's wait before trying again, on a `rate-limited` or `busy` refusal.
  final Duration? retryAfter;

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

/// A path that knows whether the server is connected behind it: the relay.
/// While the server is away the path stays open, so the editor hears the
/// moment it is back instead of polling.
abstract class RelayLink {
  /// The server is connected to the relay now.
  bool get serverOnline;

  /// The socket to the relay is gone.
  bool get isClosed;

  /// Completes when the server is back on the relay, or the socket closes.
  Future<void> get serverBack;
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
