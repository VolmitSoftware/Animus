import 'dart:async';
import 'dart:convert';

import 'package:animus_editor/services/server/pairing.dart';
import 'package:animus_editor/services/server/server_proof.dart';
import 'package:animus_editor/services/server/transport.dart';

import '../../tool/fake_server/identity.dart';

/// Seed of a key that is not the paired server's.
final List<int> otherSeed = List<int>.generate(32, (int index) => index);

typedef FakeHandler = FutureOr<ApiResponse> Function(ApiRequest request);

/// An in-memory path: answers with [handler], or fails while [down].
class FakeTransport implements ServerTransport {
  FakeTransport(this.path, this.network);

  @override
  final ConnectionPath path;
  final FakeNetwork network;
  final List<FakeSubscription> subscriptions = <FakeSubscription>[];
  bool closed = false;

  @override
  Future<ApiResponse> send(ApiRequest request) async {
    network.sent.add((path, request));
    if (closed || network.down.contains(path)) throw TransportFailure(path, 'unreachable');
    final Future<void> Function(ConnectionPath path, ApiRequest request)? gate = network.gate;
    if (gate != null) await gate(path, request);
    if (closed || network.down.contains(path)) throw TransportFailure(path, 'unreachable');
    final ApiResponse response = await (network.pathHandlers[path] ?? network.handler)(request);
    if (network.dropAfter != null && network.dropAfter!(request)) {
      // The server handled it, then the path died before the answer arrived.
      network.dropAfter = null;
      network.down.add(path);
      for (final FakeSubscription subscription in subscriptions) {
        await subscription.end();
      }
      throw TransportFailure(path, 'dropped');
    }
    return response;
  }

  @override
  Future<EventSubscription> subscribe(String token) async {
    if (closed || network.down.contains(path)) throw TransportFailure(path, 'unreachable');
    if (network.refuseStream != null) throw StreamRefused(401, network.refuseStream!);
    final FakeSubscription subscription = FakeSubscription();
    subscriptions.add(subscription);
    network.streams.add((path, subscription));
    return subscription;
  }

  @override
  Future<void> close() async {
    closed = true;
    for (final FakeSubscription subscription in subscriptions) {
      await subscription.cancel();
    }
  }
}

class FakeSubscription implements EventSubscription {
  final StreamController<Map<String, Object?>> controller = StreamController<Map<String, Object?>>.broadcast(sync: true);
  String? reason;

  bool get isOpen => !controller.isClosed;

  @override
  Stream<Map<String, Object?>> get events => controller.stream;

  @override
  String? get closeReason => reason;

  void emit(Map<String, Object?> event) => controller.add(event);

  /// The server ended the stream, with [why] as its close reason.
  Future<void> end([String? why]) async {
    reason = why;
    if (!controller.isClosed) await controller.close();
  }

  @override
  Future<void> cancel() async {
    if (!controller.isClosed) await controller.close();
  }
}

/// Both paths of one fake server.
class FakeNetwork {
  FakeNetwork(this.handler);

  FakeHandler handler;

  /// Answers on one path instead of [handler].
  final Map<ConnectionPath, FakeHandler> pathHandlers = <ConnectionPath, FakeHandler>{};

  /// Awaited before a request is answered, to hold it in flight.
  Future<void> Function(ConnectionPath path, ApiRequest request)? gate;
  final Set<ConnectionPath> down = <ConnectionPath>{};
  final List<(ConnectionPath, ApiRequest)> sent = <(ConnectionPath, ApiRequest)>[];
  final List<(ConnectionPath, FakeSubscription)> streams = <(ConnectionPath, FakeSubscription)>[];
  final List<ConnectionPath> opened = <ConnectionPath>[];
  final List<FakeTransport> transports = <FakeTransport>[];
  String? refuseStream;

  /// When it matches a request, the path drops after the server handled it.
  bool Function(ApiRequest request)? dropAfter;

  Future<ServerTransport> open(ConnectionPath path, String url) async {
    opened.add(path);
    if (down.contains(path)) throw TransportFailure(path, 'unreachable');
    final FakeTransport transport = FakeTransport(path, this);
    transports.add(transport);
    return transport;
  }

  FakeSubscription get lastStream => streams.last.$2;

  /// The last stream opened on [path].
  FakeSubscription streamOn(ConnectionPath path) => streams.lastWhere(((ConnectionPath, FakeSubscription) stream) => stream.$1 == path).$2;
}

/// Answers hello and capabilities like the fixtures; [rest] answers the rest.
/// A challenged hello is signed by the key of [seed] over [prefix], unless
/// [prove] is false.
FakeHandler serverHandler({
  String fingerprint = fakeFingerprint,
  List<int>? seed,
  String prefix = editorProofPrefix,
  bool prove = true,
  ApiResponse Function(ApiRequest request)? rest,
}) {
  return (ApiRequest request) async {
    if (request.path == '/v1/hello') {
      final String? challenge = headerValue(request.headers, challengeHeader);
      return ApiResponse(
        200,
        <String, Object?>{
          'protocol': 1,
          'fingerprint': fingerprint,
          'animus': '1.0.0',
          'platform': 'paper',
          'minecraft': '26.3',
        },
        headers: prove && challenge != null ? await proofHeaders(seed ?? fakeSeed, challenge, prefix: prefix) : const <String, String>{},
      );
    }
    if (request.path == '/v1/capabilities') {
      if (request.headers['Authorization'] != 'Bearer anm_tok.secret') {
        return const ApiResponse(401, <String, Object?>{'error': <String, Object?>{'code': 'token-revoked', 'message': 'revoked'}});
      }
      return const ApiResponse(200, <String, Object?>{'features': <String, Object?>{}});
    }
    if (rest != null) return rest(request);
    return const ApiResponse(404, <String, Object?>{'error': <String, Object?>{'code': 'not-found', 'message': 'no'}});
  };
}

/// [inner] with one bit of the hello proof flipped, so the signature fails.
FakeHandler tamperedProof(FakeHandler inner) => (ApiRequest request) async {
      final ApiResponse response = await inner(request);
      final String? proof = response.headers[proofHeader];
      if (proof == null) return response;
      final List<int> bytes = base64Url.decode(base64Url.normalize(proof))..[0] ^= 1;
      return ApiResponse(response.status, response.body, headers: <String, String>{...response.headers, proofHeader: base64UrlNoPadding(bytes)});
    };

/// A pairing code's payload for the fake server's key.
PairingPayload fakePayload({String? directUrl, String? relayUrl}) => PairingPayload(
      fingerprint: fakeFingerprint,
      serverKey: fakeServerKey,
      tokenId: 'tok_0001AbCd',
      secret: 'ZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXp7fH1-f4CBgoM',
      expiresAt: DateTime.utc(2030),
      directUrl: directUrl,
      relayUrl: relayUrl,
    );
