import 'dart:async';

import 'package:animus_editor/services/server/transport.dart';

const String fixtureFingerprint = 'sha256:630dcd2966c4336691125448bbb25b4ff412a49c732db2c8abc1b8581bd710dd';
const String fixtureServerKey = 'AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8';

typedef FakeHandler = ApiResponse Function(ApiRequest request);

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
    if (closed || network.down.contains(path)) throw TransportFailure(path, 'down');
    final ApiResponse response = network.handler(request);
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
    if (closed || network.down.contains(path)) throw TransportFailure(path, 'down');
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
  final Set<ConnectionPath> down = <ConnectionPath>{};
  final List<(ConnectionPath, ApiRequest)> sent = <(ConnectionPath, ApiRequest)>[];
  final List<(ConnectionPath, FakeSubscription)> streams = <(ConnectionPath, FakeSubscription)>[];
  final List<ConnectionPath> opened = <ConnectionPath>[];
  String? refuseStream;

  /// When it matches a request, the path drops after the server handled it.
  bool Function(ApiRequest request)? dropAfter;

  Future<ServerTransport> open(ConnectionPath path, String url) async {
    opened.add(path);
    if (down.contains(path)) throw TransportFailure(path, 'unreachable');
    return FakeTransport(path, this);
  }

  FakeSubscription get lastStream => streams.last.$2;
}

/// Answers hello and capabilities like the fixtures; [rest] answers the rest.
FakeHandler serverHandler({String fingerprint = fixtureFingerprint, ApiResponse Function(ApiRequest request)? rest}) {
  return (ApiRequest request) {
    if (request.path == '/v1/hello') {
      return ApiResponse(200, <String, Object?>{
        'protocol': 1,
        'fingerprint': fingerprint,
        'animus': '1.0.0',
        'platform': 'paper',
        'minecraft': '26.3',
      });
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
