import 'dart:convert';
import 'dart:math';
import 'dart:typed_data';

import 'package:animus_editor/logic/shared_schemas.dart';
import 'package:crypto/crypto.dart';

/// Which path a request arrived on. The relay checks origins on its own
/// browser handshake, so only direct requests are origin-checked per request.
enum Transport { direct, relay }

/// A transport-neutral `/animus/v1` request, as EditorRequest is in Java.
class FakeRequest {
  FakeRequest({
    required this.transport,
    required this.method,
    required this.target,
    required Map<String, String> headers,
    this.body,
    this.address = '127.0.0.1',
  }) : headers = <String, String>{
          for (final MapEntry<String, String> entry in headers.entries) entry.key.toLowerCase(): entry.value,
        };

  final Transport transport;
  final String method;

  /// `/v1/...` with the query string, still percent-encoded.
  final String target;
  final Map<String, String> headers;

  /// The decoded JSON body, or null when there is none.
  final Object? body;
  final String address;

  String get route {
    final int question = target.indexOf('?');
    return question < 0 ? target : target.substring(0, question);
  }

  String? header(String name) => headers[name.toLowerCase()];

  /// The decoded query string; repeated keys keep the first value.
  Map<String, String> get query {
    final Map<String, String> values = <String, String>{};
    final int question = target.indexOf('?');
    if (question < 0) return values;
    for (final String pair in target.substring(question + 1).split('&')) {
      if (pair.isEmpty) continue;
      final int equals = pair.indexOf('=');
      try {
        final String key = Uri.decodeQueryComponent(equals < 0 ? pair : pair.substring(0, equals));
        final String value = equals < 0 ? '' : Uri.decodeQueryComponent(pair.substring(equals + 1));
        values.putIfAbsent(key, () => value);
      } on FormatException {
        throw ApiError('invalid-request', 'The query string is not URL encoded.');
      }
    }
    return values;
  }
}

/// A response: a status, extra headers and a JSON body (null for none).
class FakeResponse {
  const FakeResponse(this.status, {this.body, this.headers = const <String, String>{}});

  final int status;
  final Object? body;
  final Map<String, String> headers;

  FakeResponse withHeaders(Map<String, String> extra) =>
      FakeResponse(status, body: body, headers: <String, String>{...headers, ...extra});
}

/// A refused request: an `error-code` and the status `errorStatus` gives it.
class ApiError implements Exception {
  ApiError(this.code, this.message, {this.diagnostics, this.retryAfterMillis});

  final String code;
  final String message;
  final List<Map<String, Object?>>? diagnostics;
  final int? retryAfterMillis;

  int get status => errorStatus(code);

  Map<String, Object?> get body => <String, Object?>{
        'error': <String, Object?>{
          'code': code,
          'message': message.length > 2048 ? message.substring(0, 2048) : message,
          if (diagnostics != null) 'diagnostics': diagnostics,
          if (retryAfterMillis != null) 'retryAfterMillis': retryAfterMillis,
        },
      };

  FakeResponse toResponse() => FakeResponse(
        status,
        body: body,
        headers: retryAfterMillis == null
            ? const <String, String>{}
            : <String, String>{'Retry-After': '${max(1, (retryAfterMillis! + 999) ~/ 1000)}'},
      );

  @override
  String toString() => 'ApiError($code: $message)';
}

ApiError invalid(String message) => ApiError('invalid-request', message);

Map<String, Object?> get endpointTable => SharedSchemas.embedded.endpoints;

/// The HTTP status of an error code, from editor-v1.endpoints.json.
int errorStatus(String code) {
  final Map<String, Object?> statuses = endpointTable['errorStatus']! as Map<String, Object?>;
  final Object? status = statuses[code];
  if (status is! int) throw StateError('editor-v1.endpoints.json has no status for $code');
  return status;
}

Map<String, Object?> get endpointLimits => endpointTable['limits']! as Map<String, Object?>;

int limit(String name) => endpointLimits[name]! as int;

Map<String, Object?> get pairingDefaults => endpointTable['pairing']! as Map<String, Object?>;

/// The scope that applies documents of a kind wire name.
String applyScope(String kind) =>
    ((endpointTable['kinds']! as Map<String, Object?>)[kind]! as Map<String, Object?>)['applyScope']! as String;

final RegExp documentIdPattern = RegExp(r'^[a-z0-9_.:/-]{1,512}$');
final RegExp hashPattern = RegExp(r'^sha256:[0-9a-f]{64}$');
final RegExp keyPattern = RegExp(r'^[A-Za-z0-9_-]{16,128}$');
final RegExp tokenIdPattern = RegExp(r'^[A-Za-z0-9_-]{8,64}$');
final RegExp secretPattern = RegExp(r'^[A-Za-z0-9_-]{43}$');
final RegExp objectIdPattern = RegExp(r'^[A-Za-z0-9_-]{8,64}$');
final RegExp uuidPattern = RegExp(r'^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$');

final Random _random = Random.secure();

/// [bytes] random bytes as base64url without padding.
String randomText(int bytes) {
  final Uint8List value = Uint8List(bytes);
  for (int index = 0; index < bytes; index++) {
    value[index] = _random.nextInt(256);
  }
  return base64UrlNoPadding(value);
}

String base64UrlNoPadding(List<int> bytes) => base64Url.encode(bytes).replaceAll('=', '');

/// A random version 4 UUID, lower case.
String randomUuid() {
  final Uint8List bytes = Uint8List(16);
  for (int index = 0; index < 16; index++) {
    bytes[index] = _random.nextInt(256);
  }
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  final String hex = bytes.map((int byte) => byte.toRadixString(16).padLeft(2, '0')).join();
  return '${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}';
}

String sha256Hex(List<int> bytes) => sha256.convert(bytes).toString();

String sha256Text(String text) => sha256Hex(utf8.encode(text));

/// Constant-time comparison of two ASCII strings.
bool sameText(String a, String b) {
  if (a.length != b.length) return false;
  int difference = 0;
  for (int index = 0; index < a.length; index++) {
    difference |= a.codeUnitAt(index) ^ b.codeUnitAt(index);
  }
  return difference == 0;
}

/// Instants as the schema writes them: UTC, whole seconds.
String instant(DateTime time) {
  final DateTime utc = time.toUtc();
  String two(int value) => value.toString().padLeft(2, '0');
  return '${utc.year.toString().padLeft(4, '0')}-${two(utc.month)}-${two(utc.day)}T${two(utc.hour)}:${two(utc.minute)}:${two(utc.second)}Z';
}

DateTime now() => DateTime.now().toUtc();

/// Pretty JSON with a trailing newline, the stored form of applied documents.
String prettyJson(Object? value) => '${const JsonEncoder.withIndent('  ').convert(value)}\n';

/// A deep copy of decoded JSON.
Object? copyJson(Object? value) {
  if (value is Map<String, Object?>) {
    return <String, Object?>{for (final MapEntry<String, Object?> entry in value.entries) entry.key: copyJson(entry.value)};
  }
  if (value is List<Object?>) return <Object?>[for (final Object? element in value) copyJson(element)];
  return value;
}

/// The object's `revision` as a non-negative integer, or 0.
int revisionOf(String? content) {
  if (content == null) return 0;
  try {
    final Object? json = jsonDecode(content);
    if (json is! Map<String, Object?>) return 0;
    final Object? revision = json['revision'];
    if (revision is int) return max(0, revision);
    if (revision is double && revision == revision.roundToDouble()) return max(0, revision.toInt());
    return 0;
  } on FormatException {
    return 0;
  }
}

/// The stored document parsed, or an empty object when it is not one JSON object.
Map<String, Object?> sourceOf(String content) {
  try {
    final Object? json = jsonDecode(content);
    return json is Map<String, Object?> ? json : <String, Object?>{};
  } on FormatException {
    return <String, Object?>{};
  }
}
