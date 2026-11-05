import 'dart:async';
import 'dart:math' as math;

import 'pairing.dart';
import 'server_profiles.dart';
import 'transport.dart';

enum ConnectionPhase {
  /// Not connected and not trying.
  offline,

  /// Trying a path.
  connecting,

  /// Requests and the event stream work.
  online,

  /// Lost or never reached; waiting to try again.
  reconnecting,

  /// The token is missing, expired or revoked: pair again.
  needsPairing,

  /// A path answered with another server's fingerprint. Nothing more is
  /// tried until the user acts.
  refused,
}

class ConnectionStatus {
  const ConnectionStatus(this.phase, {this.path, this.attempt = 0, this.retryAt, this.message, this.hello});

  final ConnectionPhase phase;
  final ConnectionPath? path;

  /// Failed attempts since the last time the connection was online.
  final int attempt;
  final DateTime? retryAt;
  final String? message;

  /// The last `hello-response` from the server.
  final Map<String, Object?>? hello;

  bool get online => phase == ConnectionPhase.online;

  String get label => switch (phase) {
        ConnectionPhase.offline => 'Offline',
        ConnectionPhase.connecting => 'Connecting${path == null ? '' : ' (${path!.label.toLowerCase()})'}',
        ConnectionPhase.online => 'Online (${path!.label.toLowerCase()})',
        ConnectionPhase.reconnecting => 'Reconnecting',
        ConnectionPhase.needsPairing => 'Pair again',
        ConnectionPhase.refused => 'Refused',
      };
}

/// The request needs a connection that is not online.
class NotConnected implements Exception {
  const NotConnected(this.status);

  final ConnectionStatus status;

  @override
  String toString() => 'Not connected: ${status.message ?? status.label}';
}

typedef Delay = Future<void> Function(Duration duration);

Future<void> realDelay(Duration duration) => Future<void>.delayed(duration);

/// Error codes after which the token is no use.
const Set<String> tokenErrorCodes = <String>{'unauthorized', 'token-expired', 'token-revoked'};

/// Wait before retry [attempt] (1-based): 1 s doubling to [maximum].
Duration reconnectDelay(int attempt, {Duration maximum = const Duration(seconds: 30)}) {
  final int seconds = math.min(maximum.inSeconds, 1 << math.min(attempt - 1, 16));
  return Duration(seconds: math.max(1, seconds));
}

/// One server's connection. Tries every path of the profile (the last good
/// one first, the other first after a loss), pins the fingerprint on every
/// `hello`, keeps the event stream open, and reconnects with backoff.
class ServerConnection {
  ServerConnection({
    required this._profile,
    required this.open,
    this.token,
    this.delay = realDelay,
    DateTime Function()? now,
  }) : _now = now ?? DateTime.now;

  final TransportFactory open;
  final Delay delay;
  final DateTime Function() _now;
  ServerToken? token;

  ServerProfile _profile;
  ServerTransport? _transport;
  EventSubscription? _subscription;
  ConnectionStatus _status = const ConnectionStatus(ConnectionPhase.offline);
  Map<String, Object?>? _hello;
  Map<String, Object?>? _capabilities;
  bool _running = false;
  Completer<void>? _lost;
  Completer<void>? _wake;
  ConnectionPath? _lostPath;
  int _counter = 0;

  final StreamController<ConnectionStatus> _statuses = StreamController<ConnectionStatus>.broadcast(sync: true);
  final StreamController<Map<String, Object?>> _events = StreamController<Map<String, Object?>>.broadcast(sync: true);
  final StreamController<ServerProfile> _profiles = StreamController<ServerProfile>.broadcast(sync: true);

  ServerProfile get profile => _profile;
  ConnectionStatus get status => _status;
  Stream<ConnectionStatus> get statuses => _statuses.stream;

  /// Server events while online.
  Stream<Map<String, Object?>> get events => _events.stream;

  /// The profile after a path worked (its [ServerProfile.lastPath]).
  Stream<ServerProfile> get profileChanges => _profiles.stream;

  Map<String, Object?>? get hello => _hello;
  Map<String, Object?>? get capabilities => _capabilities;

  void _emit(ConnectionStatus status) {
    _status = status;
    _statuses.add(status);
  }

  /// Starts connecting; returns once the first attempt round ended.
  Future<void> connect() {
    if (_running) {
      _wake?.complete();
      return Future<void>.value();
    }
    if (token == null) {
      _emit(const ConnectionStatus(ConnectionPhase.needsPairing, message: 'No token for this server in this browser.'));
      return Future<void>.value();
    }
    _running = true;
    final Completer<void> firstRound = Completer<void>();
    unawaited(_loop(firstRound));
    return firstRound.future;
  }

  /// Skips the wait before the next attempt.
  void retryNow() {
    final Completer<void>? wake = _wake;
    if (wake != null && !wake.isCompleted) wake.complete();
  }

  Future<void> disconnect() async {
    _running = false;
    retryNow();
    final Completer<void>? lost = _lost;
    if (lost != null && !lost.isCompleted) lost.complete();
    await _drop();
    _emit(const ConnectionStatus(ConnectionPhase.offline));
  }

  Future<void> _drop() async {
    final EventSubscription? subscription = _subscription;
    final ServerTransport? transport = _transport;
    _subscription = null;
    _transport = null;
    await subscription?.cancel();
    await transport?.close();
  }

  Future<void> _loop(Completer<void> firstRound) async {
    int attempt = 0;
    while (_running) {
      String? problem;
      bool wasOnline = false;
      for (final (ConnectionPath path, String url) in _order()) {
        if (!_running) break;
        _emit(ConnectionStatus(ConnectionPhase.connecting, path: path, attempt: attempt, hello: _hello));
        final String? result = await _attempt(path, url);
        if (result == null) {
          wasOnline = true;
          attempt = 0;
          if (!firstRound.isCompleted) firstRound.complete();
          await _lost!.future;
          break;
        }
        problem = result;
      }
      if (!firstRound.isCompleted) firstRound.complete();
      if (!_running) break;
      // Lost while online: fail over at once, the other path first.
      if (wasOnline) continue;
      attempt++;
      final Duration wait = reconnectDelay(attempt);
      _emit(ConnectionStatus(ConnectionPhase.reconnecting, attempt: attempt, retryAt: _now().add(wait), message: problem, hello: _hello));
      final Completer<void> wake = Completer<void>();
      _wake = wake;
      await Future.any(<Future<void>>[delay(wait), wake.future]);
      _wake = null;
    }
    _running = false;
  }

  /// Paths to try: after a loss, the other path first.
  List<(ConnectionPath, String)> _order() {
    final List<(ConnectionPath, String)> paths = _profile.paths;
    final ConnectionPath? lost = _lostPath;
    _lostPath = null;
    if (lost == null || paths.length < 2 || paths.first.$1 != lost) return paths;
    return paths.reversed.toList();
  }

  /// One attempt on one path. Returns null once online, else the problem.
  Future<String?> _attempt(ConnectionPath path, String url) async {
    ServerTransport? transport;
    try {
      transport = await open(path, url);
      final ApiResponse hello = await transport.send(const ApiRequest('GET', '/v1/hello'));
      final Object? body = hello.body;
      if (!hello.ok || body is! Map<String, Object?>) {
        await transport.close();
        return '${path.label}: hello answered ${hello.status}';
      }
      if (body['fingerprint'] != _profile.fingerprint) {
        await transport.close();
        _running = false;
        _emit(ConnectionStatus(ConnectionPhase.refused, path: path,
            message: '${path.label} path answered as ${body['fingerprint']}, not the paired server ${_profile.fingerprint}. Nothing was sent.'));
        return 'fingerprint mismatch';
      }
      if (body['protocol'] != 1 && !((body['protocols'] as List<Object?>?) ?? const <Object?>[]).contains(1)) {
        await transport.close();
        _running = false;
        _emit(ConnectionStatus(ConnectionPhase.refused, path: path, message: 'The server does not speak editor protocol 1.'));
        return 'protocol';
      }
      _hello = body;
      final ApiResponse capabilities = await transport.send(_authorized(const ApiRequest('GET', '/v1/capabilities')));
      if (_tokenRefused(capabilities)) {
        await transport.close();
        return 'token';
      }
      if (!capabilities.ok) {
        await transport.close();
        return '${path.label}: capabilities answered ${capabilities.status} ${capabilities.errorCode ?? ''}';
      }
      _capabilities = capabilities.body is Map<String, Object?> ? capabilities.body! as Map<String, Object?> : null;
      final EventSubscription subscription;
      try {
        subscription = await transport.subscribe(token!.token);
      } on StreamRefused catch (refused) {
        await transport.close();
        if (tokenErrorCodes.contains(refused.code)) {
          _needsPairing('The server refused the token (${refused.code}).');
          return 'token';
        }
        return '${path.label}: events refused (${refused.status} ${refused.code})';
      }
      _transport = transport;
      _subscription = subscription;
      _lost = Completer<void>();
      subscription.events.listen(
        _events.add,
        onError: (Object _) {},
        onDone: () => _streamEnded(transport!, subscription),
      );
      if (_profile.lastPath != path) {
        _profile = _profile.copyWith(lastPath: path);
        _profiles.add(_profile);
      }
      _emit(ConnectionStatus(ConnectionPhase.online, path: path, hello: _hello));
      return null;
    } on TransportFailure catch (failure) {
      await transport?.close();
      return failure.toString();
    }
  }

  void _streamEnded(ServerTransport transport, EventSubscription subscription) {
    if (!identical(_subscription, subscription)) return;
    final String? reason = subscription.closeReason;
    if (reason != null && tokenErrorCodes.any(reason.contains)) {
      _needsPairing('The server closed the connection: $reason.');
      return;
    }
    _connectionLost(transport.path, reason == null ? 'the event stream closed' : 'the server closed the stream: $reason');
  }

  void _needsPairing(String message) {
    _running = false;
    token = null;
    unawaited(_drop());
    final Completer<void>? lost = _lost;
    if (lost != null && !lost.isCompleted) lost.complete();
    _emit(ConnectionStatus(ConnectionPhase.needsPairing, message: message, hello: _hello));
  }

  void _connectionLost(ConnectionPath path, String message) {
    if (_transport == null || _transport!.path != path) return;
    _lostPath = path;
    unawaited(_drop());
    _emit(ConnectionStatus(ConnectionPhase.reconnecting, path: path, message: message, hello: _hello));
    final Completer<void>? lost = _lost;
    if (lost != null && !lost.isCompleted) lost.complete();
  }

  bool _tokenRefused(ApiResponse response) {
    if (response.status != 401 || !tokenErrorCodes.contains(response.errorCode)) return false;
    _needsPairing('The server refused the token (${response.errorCode}).');
    return true;
  }

  ApiRequest _authorized(ApiRequest request) {
    final Map<String, String> headers = <String, String>{'Authorization': 'Bearer ${token!.token}'};
    if (request.method != 'GET') {
      _counter = math.max(_counter + 1, _now().millisecondsSinceEpoch);
      headers['X-Animus-Counter'] = '$_counter';
    }
    return request.withHeaders(headers);
  }

  /// Sends an authorized request on the open path. A path failure starts a
  /// reconnect (to the other path first) and is rethrown.
  Future<ApiResponse> request(ApiRequest request) async {
    final ServerTransport? transport = _transport;
    if (transport == null || _status.phase != ConnectionPhase.online || token == null) throw NotConnected(_status);
    try {
      final ApiResponse response = await transport.send(_authorized(request));
      _tokenRefused(response);
      return response;
    } on TransportFailure catch (failure) {
      _connectionLost(transport.path, failure.message);
      rethrow;
    }
  }

  /// Exchanges a pairing code's secret for a token over the first path that
  /// answers with the pinned fingerprint.
  static Future<(ServerToken, ConnectionPath)> pair(PairingPayload payload, TransportFactory open, {String? client}) async {
    final List<String> problems = <String>[];
    for (final (ConnectionPath path, String url) in <(ConnectionPath, String)>[
      if (payload.directUrl != null) (ConnectionPath.direct, payload.directUrl!),
      if (payload.relayUrl != null) (ConnectionPath.relay, payload.relayUrl!),
    ]) {
      ServerTransport? transport;
      try {
        transport = await open(path, url);
        final ApiResponse hello = await transport.send(const ApiRequest('GET', '/v1/hello'));
        final Object? body = hello.body;
        if (body is! Map<String, Object?> || body['fingerprint'] != payload.fingerprint) {
          problems.add('${path.label}: answered as another server');
          continue;
        }
        final ApiResponse response = await transport.send(ApiRequest('POST', '/v1/pair', body: <String, Object?>{
          'tokenId': payload.tokenId,
          'secret': payload.secret,
          if (client != null && client.isNotEmpty) 'client': client.length > 128 ? client.substring(0, 128) : client,
        }));
        final Object? answer = response.body;
        if (!response.ok) throw PairingRefused(response.errorCode ?? 'refused', response.errorMessage);
        if (answer is! Map<String, Object?> || answer['fingerprint'] != payload.fingerprint) {
          problems.add('${path.label}: the pairing answer named another server');
          continue;
        }
        final ServerToken? token = ServerToken.fromJson(answer);
        if (token == null) {
          problems.add('${path.label}: the pairing answer has no token');
          continue;
        }
        return (token, path);
      } on TransportFailure catch (failure) {
        problems.add(failure.toString());
      } finally {
        await transport?.close();
      }
    }
    throw PairingRefused('unreachable', problems.isEmpty ? 'The pairing code names no path.' : problems.join('; '));
  }

  Future<void> dispose() async {
    await disconnect();
    await _statuses.close();
    await _events.close();
    await _profiles.close();
  }
}

class PairingRefused implements Exception {
  const PairingRefused(this.code, this.message);

  final String code;
  final String message;

  @override
  String toString() => code == 'unreachable' ? 'The server could not be reached: $message' : '$message ($code)';
}
