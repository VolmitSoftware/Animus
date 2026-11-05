import 'dart:convert';

import 'protocol.dart';

/// The fake server's identity: the raw Ed25519 public key of
/// schema/fixtures/editor/pairing.json (bytes 0..31). The fake never signs,
/// so no private key exists.
class FakeIdentity {
  FakeIdentity() : serverKeyBytes = List<int>.generate(32, (int index) => index);

  final List<int> serverKeyBytes;

  String get serverKey => base64UrlNoPadding(serverKeyBytes);

  /// `sha256:630dcd...`, the fingerprint in pairing codes and hello.
  String get fingerprint => 'sha256:${sha256Hex(serverKeyBytes)}';
}

/// A pairing code: `ANM1.` and the payload JSON in base64url without padding,
/// fields in the contract's order.
String pairingCode({
  String? directUrl,
  String? relayUrl,
  required String fingerprint,
  required String serverKey,
  required String tokenId,
  required String secret,
  required DateTime expiresAt,
  String? label,
  String? serverName,
}) {
  final Map<String, Object?> payload = <String, Object?>{
    'v': 1,
    'directUrl': ?directUrl,
    'relayUrl': ?relayUrl,
    'fingerprint': fingerprint,
    'serverKey': serverKey,
    'tokenId': tokenId,
    'secret': secret,
    'expiresAt': instant(expiresAt),
    'label': ?label,
    'serverName': ?serverName,
  };
  return '${pairingDefaults['prefix']! as String}${base64UrlNoPadding(utf8.encode(jsonEncode(payload)))}';
}
