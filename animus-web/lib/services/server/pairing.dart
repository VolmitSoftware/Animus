import 'dart:convert';
import 'dart:typed_data';

import 'package:crypto/crypto.dart';

import '../../logic/json_schema.dart';
import '../../logic/shared_schemas.dart';

/// A decoded `ANM1.` pairing code (`pairing-payload` in editor-v1.schema.json).
/// The secret is single use; everything else is pinned into the saved server.
class PairingPayload {
  const PairingPayload({
    required this.fingerprint,
    required this.serverKey,
    required this.tokenId,
    required this.secret,
    required this.expiresAt,
    this.directUrl,
    this.relayUrl,
    this.label,
    this.serverName,
  });

  final String? directUrl;
  final String? relayUrl;

  /// `sha256:` and the hex SHA-256 of [serverKey]'s 32 raw bytes.
  final String fingerprint;
  final String serverKey;
  final String tokenId;
  final String secret;
  final DateTime expiresAt;
  final String? label;
  final String? serverName;

  /// A short stable id for routes and storage: the first 16 hex digits of
  /// the fingerprint.
  String get serverId => serverIdOf(fingerprint);
}

String serverIdOf(String fingerprint) => fingerprint.substring('sha256:'.length, 'sha256:'.length + 16);

class PairingCodeError implements Exception {
  const PairingCodeError(this.message);

  final String message;

  @override
  String toString() => message;
}

const String pairingPrefix = 'ANM1.';

/// Takes a pasted code or link (`.../#/pair/ANM1...`) and returns the code.
String extractPairingCode(String text) {
  final String trimmed = text.trim();
  final int marker = trimmed.indexOf('#/pair/');
  final String code = marker >= 0 ? trimmed.substring(marker + '#/pair/'.length) : trimmed;
  final int end = code.indexOf(RegExp(r'[\s?&]'));
  return end < 0 ? code : code.substring(0, end);
}

/// Decodes and checks a pairing code: the prefix, unpadded base64url, the
/// payload schema, and that the fingerprint is the server key's SHA-256,
/// which pins the server for every later connection.
PairingPayload decodePairingCode(String code, SharedSchemas schemas) {
  if (!code.startsWith(pairingPrefix)) throw const PairingCodeError('A pairing code starts with ANM1.');
  final String body = code.substring(pairingPrefix.length);
  if (body.isEmpty || !RegExp(r'^[A-Za-z0-9_-]+$').hasMatch(body)) {
    throw const PairingCodeError('The pairing code is not unpadded base64url.');
  }
  final Object? json;
  try {
    json = jsonDecode(utf8.decode(base64Url.decode(base64Url.normalize(body))));
  } on FormatException {
    throw const PairingCodeError('The pairing code does not hold JSON.');
  }
  final List<SchemaError> errors = schemas.validateMessage('pairing-payload', json);
  if (errors.isNotEmpty) throw PairingCodeError('The pairing code is invalid: ${errors.first.path} ${errors.first.message}');
  final Map<String, Object?> payload = json! as Map<String, Object?>;
  final String serverKey = payload['serverKey']! as String;
  final String fingerprint = payload['fingerprint']! as String;
  if (fingerprintOf(serverKey) != fingerprint) {
    throw const PairingCodeError('The pairing code fingerprint does not match its server key.');
  }
  final DateTime? expires = DateTime.tryParse(payload['expiresAt']! as String);
  if (expires == null) throw const PairingCodeError('The pairing code expiry is not a time.');
  return PairingPayload(
    directUrl: payload['directUrl'] as String?,
    relayUrl: payload['relayUrl'] as String?,
    fingerprint: fingerprint,
    serverKey: serverKey,
    tokenId: payload['tokenId']! as String,
    secret: payload['secret']! as String,
    expiresAt: expires.toUtc(),
    label: payload['label'] as String?,
    serverName: payload['serverName'] as String?,
  );
}

/// `sha256:<hex>` of a base64url raw key.
String fingerprintOf(String serverKey) {
  final Uint8List bytes = base64Url.decode(base64Url.normalize(serverKey));
  return 'sha256:${sha256.convert(bytes)}';
}
