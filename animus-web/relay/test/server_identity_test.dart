import 'package:animus_relay/animus_relay.dart';
import 'package:test/test.dart';

import 'support.dart';

void main() {
  final Map<String, Object?> handshake =
      readFixture('editor/relay-handshake.json')! as Map<String, Object?>;

  test('should derive the pairing fixture fingerprint from its raw key', () {
    final Map<String, Object?> pairing =
        readFixture('editor/pairing.json')! as Map<String, Object?>;
    final List<int> key = (pairing['serverKeyBytes']! as List<Object?>)
        .cast<int>();
    expect(
      fingerprintOf(key),
      'sha256:630dcd2966c4336691125448bbb25b4ff412a49c732db2c8abc1b8581bd710dd',
    );
  });

  test('should reproduce the handshake golden from its seed', () async {
    final TestServer server = await TestServer.create(
      (handshake['seedBytes']! as List<Object?>).cast<int>(),
    );
    expect(server.serverKey, handshake['serverKey']);
    expect(server.fingerprint, handshake['fingerprint']);
    expect(
      await server.sign(handshake['nonce']! as String),
      handshake['signature'],
    );
  });

  test('should accept the golden signature and refuse altered ones', () async {
    final ChallengeVerifier verifier = ChallengeVerifier();
    final String nonce = handshake['nonce']! as String;
    final String key = handshake['serverKey']! as String;
    final String signature = handshake['signature']! as String;
    expect(
      await verifier.verify(nonce: nonce, serverKey: key, signature: signature),
      isTrue,
    );
    final String other = signature.startsWith('A')
        ? 'B${signature.substring(1)}'
        : 'A${signature.substring(1)}';
    expect(
      await verifier.verify(nonce: nonce, serverKey: key, signature: other),
      isFalse,
    );
    expect(
      await verifier.verify(
        nonce: verifier.issueNonce(),
        serverKey: key,
        signature: signature,
      ),
      isFalse,
    );
    expect(
      await verifier.verify(
        nonce: nonce,
        serverKey: 'short',
        signature: signature,
      ),
      isFalse,
    );
  });

  test('should issue distinct 32-byte nonces', () {
    final ChallengeVerifier verifier = ChallengeVerifier();
    final String first = verifier.issueNonce();
    expect(first, hasLength(43));
    expect(decodeBase64Url(first), hasLength(32));
    expect(verifier.issueNonce(), isNot(first));
  });
}
