import 'dart:convert';

import 'package:animus_editor/services/server/server_proof.dart' show editorProofPrefix, proofHeader, serverKeyHeader;
import 'package:cryptography/cryptography.dart';

import 'protocol.dart';

/// Seed of the fake server's Ed25519 key, made up for tests.
final List<int> fakeSeed = List<int>.generate(32, (int index) => 0xa0 + index);

/// The public key of [fakeSeed], raw bytes in base64url without padding.
const String fakeServerKey = 'T9CZzNR9eJPf6ewkQU7LDZtUICMqrTDZHEZb4zy-ZcQ';

/// `sha256:` and the hex SHA-256 of [fakeServerKey]'s bytes.
const String fakeFingerprint = 'sha256:a18112b0b7b4225ff30527e0f7cf7a1e3742f632b03b65f4542703c3a5654dc9';

/// The fake server's identity: the Ed25519 key pair of [fakeSeed].
class FakeIdentity {
  String get serverKey => fakeServerKey;

  String get fingerprint => fakeFingerprint;

  /// Hello response headers that answer [challenge].
  Future<Map<String, String>> prove(String challenge) => proofHeaders(fakeSeed, challenge);
}

/// The key and signature headers of the key pair from [seed], signing
/// [prefix] and [challenge].
Future<Map<String, String>> proofHeaders(List<int> seed, String challenge, {String prefix = editorProofPrefix}) async {
  final Ed25519 ed25519 = Ed25519();
  final SimpleKeyPair pair = await ed25519.newKeyPairFromSeed(seed);
  final SimplePublicKey key = await pair.extractPublicKey();
  final Signature signature = await ed25519.sign(utf8.encode('$prefix$challenge'), keyPair: pair);
  return <String, String>{serverKeyHeader: base64UrlNoPadding(key.bytes), proofHeader: base64UrlNoPadding(signature.bytes)};
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
