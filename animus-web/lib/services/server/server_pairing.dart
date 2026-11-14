/// Talking to a server without a connection: the reachability check of a
/// saved server, pairing with a code, and the hello that proves which
/// server answered.
library;

import 'error_copy.dart';
import 'local_network.dart';
import 'pairing.dart';
import 'server_profiles.dart';
import 'server_proof.dart';
import 'transport.dart';

/// Pairing did not produce a token: [code] is the server's `error-code`, or
/// the [PairingFailure.code] of a failure no server answered with.
class PairingRefused implements Exception {
  const PairingRefused(this.code, this.copy);

  /// Refused for [failure], in its words.
  PairingRefused.because(PairingFailure failure) : this(failure.code, pairingFailureCopy(failure));

  final String code;
  final ErrorCopy copy;

  /// The case [code] falls under, which the pairing page words apart.
  PairingFailure get failure => PairingFailure.of(code);

  String get message => copy.text;

  @override
  String toString() => message;
}

/// Whether a `hello-response` offers editor protocol 1.
bool speaksProtocol(Map<String, Object?> hello) {
  final Object? protocols = hello['protocols'];
  return hello['protocol'] == 1 || (protocols is List<Object?> && protocols.contains(1));
}

/// Asks each path of [profile] for `GET /v1/hello` without a token and
/// returns the first that answers as the pinned server, with the server's
/// name; null when none does within [timeout] each. Opens no event stream.
Future<(ConnectionPath, String?)?> probeServer(ServerProfile profile, TransportFactory open, {Duration timeout = const Duration(seconds: 10)}) async {
  for (final (ConnectionPath path, String url) in profile.paths) {
    ServerTransport? transport;
    try {
      transport = await open(path, url).timeout(timeout);
      if (transport is RelayLink && !(transport as RelayLink).serverOnline) continue;
      final (ApiResponse hello, bool proven) = await greetServer(transport, profile.fingerprint).timeout(timeout);
      final Object? body = hello.body;
      if (hello.ok && proven && body is Map<String, Object?> && body['fingerprint'] == profile.fingerprint) {
        return (path, body['serverName'] is String ? body['serverName']! as String : null);
      }
    } on Object {
      continue;
    } finally {
      await transport?.close();
    }
  }
  return null;
}

/// Exchanges a pairing code's secret for a token over the first path that
/// answers as the pinned server, and returns the server's name when it
/// sends one. A path that fails or refuses does not stop the next one.
///
/// Each path has [pathTimeout] to open and answer hello, so a direct call
/// the browser holds (an unanswered Local Network Access prompt) still
/// leaves the relay its turn. The secret goes out only after that and is
/// never cut, so an abandoned path cannot use up the code.
///
/// When no path pairs, the refusal names the first of: the server refusing
/// the secret; a path that answered without pairing (another server, or an
/// error such as `origin-not-allowed`); the browser blocking the direct
/// path from the https page at [pageOrigin], ahead of a relay that is also
/// down because the user can lift it; the relay, or the server missing from
/// it; and last, no answer at all.
Future<(ServerToken, ConnectionPath, String?)> pairServer(
  PairingPayload payload,
  TransportFactory open, {
  String? client,
  String? pageOrigin,
  Duration pathTimeout = const Duration(seconds: 8),
}) async {
  PairingRefused? refusal;
  PairingRefused? answered;
  bool blocked = false;
  bool relayDown = false;
  for (final (ConnectionPath path, String url) in <(ConnectionPath, String)>[
    if (payload.directUrl != null) (ConnectionPath.direct, payload.directUrl!),
    if (payload.relayUrl != null) (ConnectionPath.relay, payload.relayUrl!),
  ]) {
    ServerTransport? transport;
    bool cut = false;
    bool heard = false;
    Future<(ServerTransport, ApiResponse, bool)> reach() async {
      final ServerTransport opened = await open(path, url);
      if (cut) {
        await opened.close();
        throw TransportFailure(path, 'closed', 'opened after the pairing timeout');
      }
      transport = opened;
      if (opened is RelayLink && !(opened as RelayLink).serverOnline) throw TransportFailure(path, 'server-offline');
      final (ApiResponse hello, bool proven) = await greetServer(opened, payload.fingerprint);
      return (opened, hello, proven);
    }

    try {
      final (ServerTransport opened, ApiResponse hello, bool proven) = await reach().timeout(pathTimeout, onTimeout: () {
        cut = true;
        // A relay socket that never opened is the relay's fault; a silent hello is the server's.
        throw TransportFailure(path, path == ConnectionPath.relay && transport == null ? 'relay-unreachable' : 'timeout', 'no answer in $pathTimeout');
      });
      heard = true;
      final Object? body = hello.body;
      if (!hello.ok || body is! Map<String, Object?>) {
        answered ??= PairingRefused(hello.errorCode ?? codeForStatus(hello.status), hello.problem);
        continue;
      }
      if (body['fingerprint'] != payload.fingerprint || !proven) {
        answered = PairingRefused.because(PairingFailure.wrongServer);
        continue;
      }
      final ApiResponse response = await opened.send(ApiRequest('POST', '/v1/pair', body: <String, Object?>{
        'tokenId': payload.tokenId,
        'secret': payload.secret,
        if (client != null && client.isNotEmpty) 'client': client.length > 128 ? client.substring(0, 128) : client,
      }));
      final Object? answer = response.body;
      if (!response.ok) {
        refusal ??= PairingRefused(response.errorCode ?? codeForStatus(response.status), response.problem);
        continue;
      }
      final ServerToken? token = answer is Map<String, Object?> && answer['fingerprint'] == payload.fingerprint ? ServerToken.fromJson(answer) : null;
      if (token == null) {
        answered = PairingRefused.because(PairingFailure.wrongServer);
        continue;
      }
      final Object? named = (answer as Map<String, Object?>)['serverName'] ?? (body['serverName']);
      return (token, path, named is String && named.trim().isNotEmpty ? named.trim() : null);
    } on TransportFailure catch (failure) {
      final String code = failure.code;
      if (path == ConnectionPath.direct && !heard && (code == 'unreachable' || code == 'timeout') && browserMayBlock(pageOrigin, url)) blocked = true;
      // On the relay a timeout is the server's silence; the relay's own errors and a server missing from it are the relay's.
      final bool relayFault = code == 'relay-unreachable' || code == 'server-offline' || code == 'server-left' || (code != 'timeout' && relayErrorCopy.containsKey(code));
      if (path == ConnectionPath.relay && relayFault) relayDown = true;
    } on Object {
      // A malformed answer or a bug counts as no answer.
    } finally {
      await transport?.close();
    }
  }
  throw refusal ??
      answered ??
      PairingRefused.because(blocked ? PairingFailure.localNetworkBlocked : (relayDown ? PairingFailure.relayUnavailable : PairingFailure.unreachable));
}

/// Sends `GET /v1/hello` and says whether the answer proves the server
/// pinned by [fingerprint]. A relay can echo any fingerprint, so there the
/// server must sign a fresh challenge; the direct path needs no proof.
Future<(ApiResponse, bool)> greetServer(ServerTransport transport, String fingerprint) async {
  if (transport.path == ConnectionPath.direct) return (await transport.send(const ApiRequest('GET', '/v1/hello')), true);
  final String challenge = newChallenge();
  final ApiResponse hello = await transport.send(ApiRequest('GET', '/v1/hello', headers: <String, String>{challengeHeader: challenge}));
  return (hello, hello.ok && await verifyServerProof(hello.headers, challenge: challenge, fingerprint: fingerprint));
}
