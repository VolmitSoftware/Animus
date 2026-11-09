import 'dart:async';
import 'dart:convert';
import 'dart:math' as math;

import 'error_copy.dart';
import 'pairing.dart';
import 'server_profiles.dart';
import 'server_proof.dart';
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
  const ConnectionStatus(this.phase, {this.path, this.attempt = 0, this.retryAt, this.problem, this.hello});

  final ConnectionPhase phase;
  final ConnectionPath? path;

  /// Failed attempts since the last time the connection was online.
  final int attempt;
  final DateTime? retryAt;

  /// Why the connection is not online, in plain words.
  final ErrorCopy? problem;

  String? get message => problem?.text;

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

/// Waits between direct-path probes while on the relay: the last one repeats.
const List<Duration> defaultProbeIntervals = <Duration>[Duration(seconds: 15), Duration(seconds: 30), Duration(seconds: 60)];

/// One server's connection. Tries every path of the profile (the last good
/// one first, the other first after a loss), pins the fingerprint on every
/// `hello`, keeps the event stream open, and reconnects with backoff. While
/// online over the relay with a direct path paired, it probes the direct path
/// every [probeIntervals] and moves back to it without going offline.
class ServerConnection {
  ServerConnection({
    required this._profile,
    required this.open,
    this.token,
    this.delay = realDelay,
    this.probeIntervals = defaultProbeIntervals,
    this.overlap = const Duration(seconds: 2),
    DateTime Function()? now,
  })  : assert(probeIntervals.isNotEmpty),
        _now = now ?? DateTime.now;

  final TransportFactory open;
  final Delay delay;

  /// Waits before each direct-path probe while on the relay; the last repeats.
  final List<Duration> probeIntervals;

  /// How long a recovered direct event stream runs beside the relay stream
  /// before the switch commits.
  final Duration overlap;
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

  /// Bumped whenever the online path changes or is lost; a probe that sees
  /// another value than it started with gives up.
  int _session = 0;
  Completer<void>? _probeWake;
  _EventOverlap? _overlap;

  /// Requests still waiting for an answer, per transport.
  final Map<ServerTransport, int> _inFlight = <ServerTransport, int>{};

  /// Transports replaced by a switch, closed once their requests answered.
  final Set<ServerTransport> _retired = <ServerTransport>{};

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

  /// Time left on the token, checked against this computer's clock; null
  /// without a token.
  Duration? get tokenLeft => token?.expiresAt.difference(_now().toUtc());

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
      _emit(ConnectionStatus(ConnectionPhase.needsPairing, problem: transportCopy('no-token')));
      return Future<void>.value();
    }
    if (!token!.expiresAt.isAfter(_now().toUtc())) {
      _needsPairing(serverCopy('token-expired'));
      return Future<void>.value();
    }
    _running = true;
    final Completer<void> firstRound = Completer<void>();
    unawaited(_loop(firstRound).catchError((Object error) {
      // The loop itself failed: say so, and start it again after a wait.
      _running = false;
      if (!firstRound.isCompleted) firstRound.complete();
      final Duration wait = reconnectDelay(1);
      _emit(ConnectionStatus(ConnectionPhase.reconnecting, retryAt: _now().add(wait), problem: transportCopy('dropped'), hello: _hello));
      unawaited(delay(wait).then((void _) {
        if (!_running && token != null && _status.phase == ConnectionPhase.reconnecting) unawaited(connect());
      }));
    }));
    return firstRound.future;
  }

  /// Skips the wait before the next attempt.
  void retryNow() {
    final Completer<void>? wake = _wake;
    if (wake != null && !wake.isCompleted) wake.complete();
  }

  /// Skips the wait before the next direct-path probe (the browser came back
  /// online or the tab became visible). Does nothing unless on the relay.
  void probeNow() {
    final Completer<void>? wake = _probeWake;
    if (wake != null && !wake.isCompleted) wake.complete();
  }

  void _endSession() {
    _session++;
    _overlap = null;
    probeNow();
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
    _endSession();
    await subscription?.cancel();
    await transport?.close();
  }

  Future<void> _loop(Completer<void> firstRound) async {
    int attempt = 0;
    while (_running) {
      ErrorCopy? problem;
      bool wasOnline = false;
      for (final (ConnectionPath path, String url) in _order()) {
        if (!_running) break;
        _emit(ConnectionStatus(ConnectionPhase.connecting, path: path, attempt: attempt, hello: _hello));
        final ErrorCopy? result = await _attempt(path, url);
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
      _emit(ConnectionStatus(ConnectionPhase.reconnecting, attempt: attempt, retryAt: _now().add(wait), problem: problem, hello: _hello));
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
  /// Any failure, not only a [TransportFailure], ends as a problem.
  Future<ErrorCopy?> _attempt(ConnectionPath path, String url) async {
    ServerTransport? transport;
    try {
      transport = await open(path, url);
      final (ApiResponse hello, bool proven) = await _greet(transport, _profile.fingerprint);
      final Object? body = hello.body;
      if (!hello.ok || body is! Map<String, Object?>) {
        await transport.close();
        return hello.problem;
      }
      if (body['fingerprint'] != _profile.fingerprint || !proven) {
        await transport.close();
        _running = false;
        final ErrorCopy wrong = transportCopy('wrong-server');
        _emit(ConnectionStatus(ConnectionPhase.refused, path: path, problem: wrong));
        return wrong;
      }
      if (!speaksProtocol(body)) {
        await transport.close();
        _running = false;
        final ErrorCopy protocol = serverCopy('unsupported-protocol');
        _emit(ConnectionStatus(ConnectionPhase.refused, path: path, problem: protocol));
        return protocol;
      }
      _hello = body;
      final ApiResponse capabilities = await transport.send(_authorized(const ApiRequest('GET', '/v1/capabilities')));
      if (_tokenRefused(capabilities) || !capabilities.ok) {
        await transport.close();
        return capabilities.problem;
      }
      _capabilities = capabilities.body is Map<String, Object?> ? capabilities.body! as Map<String, Object?> : null;
      final EventSubscription subscription;
      try {
        subscription = await transport.subscribe(token!.token);
      } on StreamRefused catch (refused) {
        await transport.close();
        final ErrorCopy problem = serverCopy(refused.code, status: refused.status);
        if (tokenErrorCodes.contains(refused.code)) _needsPairing(problem);
        return problem;
      }
      _transport = transport;
      _subscription = subscription;
      _lost = Completer<void>();
      _session++;
      subscription.events.listen(
        (Map<String, Object?> event) => _deliver(path, event),
        onError: (Object _) {},
        onDone: () => _streamEnded(transport!, subscription),
      );
      _rememberPath(path);
      _emit(ConnectionStatus(ConnectionPhase.online, path: path, hello: _hello));
      if (path == ConnectionPath.relay && _profile.directUrl != null) unawaited(_probeLoop(_session, _profile.directUrl!));
      return null;
    } on TransportFailure catch (failure) {
      await transport?.close();
      return failure.copy;
    } on Object {
      // A malformed answer or a bug: reported like a dropped path, then retried.
      await transport?.close();
      return transportCopy('dropped');
    }
  }

  void _rememberPath(ConnectionPath path) {
    if (_profile.lastPath == path) return;
    _profile = _profile.copyWith(lastPath: path);
    _profiles.add(_profile);
  }

  void _deliver(ConnectionPath from, Map<String, Object?> event) {
    final _EventOverlap? overlap = _overlap;
    if (overlap != null && !overlap.accept(from, event)) return;
    _events.add(event);
  }

  /// Probes the direct path with backoff for as long as [session] is the
  /// relay session, and switches to it once a probe succeeds.
  Future<void> _probeLoop(int session, String url) async {
    int failures = 0;
    while (_running && _session == session) {
      final Completer<void> wake = Completer<void>();
      _probeWake = wake;
      await Future.any(<Future<void>>[delay(probeIntervals[math.min(failures, probeIntervals.length - 1)]), wake.future]);
      if (identical(_probeWake, wake)) _probeWake = null;
      if (!_running || _session != session) return;
      if (await _probe(session, url)) return;
      failures++;
    }
  }

  /// One probe of the direct path while the relay session [session] carries
  /// everything. Nothing it does touches the relay session: a failure closes
  /// only the probe's own transport. Returns whether the switch committed.
  ///
  /// The order keeps requests and events whole: hello must carry the pinned
  /// fingerprint, capabilities must accept the token, then the direct event
  /// stream opens beside the relay stream for [overlap]. Only when it is still
  /// open after that (a refused event-auth closes it with 4000 + status) do
  /// new requests go direct and the relay stream close. Requests already sent
  /// over the relay keep their transport until they answer.
  Future<bool> _probe(int session, String url) async {
    ServerTransport? direct;
    EventSubscription? stream;
    bool committed = false;
    final Completer<void> ended = Completer<void>();
    bool stale() => !_running || _session != session || token == null;
    try {
      direct = await open(ConnectionPath.direct, url);
      if (stale()) return false;
      final ApiResponse hello = await direct.send(const ApiRequest('GET', '/v1/hello'));
      final Object? body = hello.body;
      if (stale() || !hello.ok || body is! Map<String, Object?> || body['fingerprint'] != _profile.fingerprint || !speaksProtocol(body)) return false;
      final ApiResponse capabilities = await direct.send(_authorized(const ApiRequest('GET', '/v1/capabilities')));
      if (stale() || !capabilities.ok) return false;
      stream = await direct.subscribe(token!.token);
      if (stale()) return false;
      final ServerTransport opened = direct;
      final EventSubscription subscription = stream;
      final _EventOverlap overlapping = _EventOverlap();
      _overlap = overlapping;
      subscription.events.listen(
        (Map<String, Object?> event) => _deliver(ConnectionPath.direct, event),
        onError: (Object _) {},
        onDone: () {
          if (committed) {
            _streamEnded(opened, subscription);
          } else if (!ended.isCompleted) {
            ended.complete();
          }
        },
      );
      final Completer<void> wake = Completer<void>();
      _probeWake = wake;
      await Future.any(<Future<void>>[delay(overlap), ended.future, wake.future]);
      if (identical(_probeWake, wake)) _probeWake = null;
      if (ended.isCompleted || stale()) {
        if (identical(_overlap, overlapping)) _overlap = null;
        return false;
      }
      committed = true;
      _commit(opened, subscription, body, capabilities, overlapping);
      return true;
    } on TransportFailure {
      return false;
    } on StreamRefused {
      return false;
    } on Object {
      return false;
    } finally {
      if (!committed) {
        await stream?.cancel();
        await direct?.close();
      }
    }
  }

  void _commit(ServerTransport direct, EventSubscription stream, Map<String, Object?> hello, ApiResponse capabilities,
      _EventOverlap overlapping) {
    final ServerTransport? relay = _transport;
    final EventSubscription? relayStream = _subscription;
    _transport = direct;
    _subscription = stream;
    _hello = hello;
    if (capabilities.body is Map<String, Object?>) _capabilities = capabilities.body! as Map<String, Object?>;
    _session++;
    _rememberPath(ConnectionPath.direct);
    _emit(ConnectionStatus(ConnectionPhase.online, path: ConnectionPath.direct, hello: _hello));
    unawaited(relayStream?.cancel());
    if (relay != null) {
      _retired.add(relay);
      _closeIfIdle(relay);
    }
    // Relay frames can trail the direct ones; keep matching them for one
    // more overlap so a late direct copy of a relay event is still dropped.
    unawaited(delay(overlap).then((void _) {
      if (identical(_overlap, overlapping)) _overlap = null;
    }));
  }

  void _closeIfIdle(ServerTransport transport) {
    if ((_inFlight[transport] ?? 0) > 0 || !_retired.remove(transport)) return;
    unawaited(transport.close());
  }

  void _streamEnded(ServerTransport transport, EventSubscription subscription) {
    if (!identical(_subscription, subscription)) return;
    final String? reason = subscription.closeReason;
    final String? tokenCode = reason == null ? null : tokenErrorCodes.where(reason.contains).firstOrNull;
    if (tokenCode != null) {
      _needsPairing(serverCopy(tokenCode));
      return;
    }
    final String? closing = reason == null ? null : closingCopy.keys.where(reason.contains).firstOrNull;
    final ErrorCopy? path = transportErrorCopy[reason] ?? relayErrorCopy[reason];
    _connectionLost(transport.path, closing != null ? closingCopy[closing]! : path ?? transportCopy('stream-closed'));
  }

  void _needsPairing(ErrorCopy problem) {
    _running = false;
    token = null;
    unawaited(_drop());
    final Completer<void>? lost = _lost;
    if (lost != null && !lost.isCompleted) lost.complete();
    _emit(ConnectionStatus(ConnectionPhase.needsPairing, problem: problem, hello: _hello));
  }

  void _connectionLost(ConnectionPath path, ErrorCopy problem) {
    if (_transport == null || _transport!.path != path) return;
    _lostPath = path;
    unawaited(_drop());
    _emit(ConnectionStatus(ConnectionPhase.reconnecting, path: path, problem: problem, hello: _hello));
    final Completer<void>? lost = _lost;
    if (lost != null && !lost.isCompleted) lost.complete();
  }

  bool _tokenRefused(ApiResponse response) {
    if (response.status != 401 || !tokenErrorCodes.contains(response.errorCode)) return false;
    _needsPairing(response.problem);
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
    _inFlight[transport] = (_inFlight[transport] ?? 0) + 1;
    try {
      final ApiResponse response = await transport.send(_authorized(request));
      _tokenRefused(response);
      return response;
    } on TransportFailure catch (failure) {
      if (identical(_transport, transport)) _connectionLost(transport.path, failure.copy);
      rethrow;
    } finally {
      final int left = _inFlight[transport]! - 1;
      if (left == 0) {
        _inFlight.remove(transport);
      } else {
        _inFlight[transport] = left;
      }
      _closeIfIdle(transport);
    }
  }

  /// Exchanges a pairing code's secret for a token over the first path that
  /// answers as the pinned server. A path that fails or refuses does not stop
  /// the next one; when every path fails, the server's own refusal is
  /// reported ahead of a path that could not be reached.
  static Future<(ServerToken, ConnectionPath)> pair(PairingPayload payload, TransportFactory open, {String? client}) async {
    PairingRefused? refusal;
    ErrorCopy? problem;
    for (final (ConnectionPath path, String url) in <(ConnectionPath, String)>[
      if (payload.directUrl != null) (ConnectionPath.direct, payload.directUrl!),
      if (payload.relayUrl != null) (ConnectionPath.relay, payload.relayUrl!),
    ]) {
      ServerTransport? transport;
      try {
        transport = await open(path, url);
        final (ApiResponse hello, bool proven) = await _greet(transport, payload.fingerprint);
        final Object? body = hello.body;
        if (!hello.ok || body is! Map<String, Object?>) {
          problem ??= hello.problem;
          continue;
        }
        if (body['fingerprint'] != payload.fingerprint || !proven) {
          problem = transportCopy('wrong-server');
          continue;
        }
        final ApiResponse response = await transport.send(ApiRequest('POST', '/v1/pair', body: <String, Object?>{
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
          problem = transportCopy('wrong-server');
          continue;
        }
        return (token, path);
      } on TransportFailure catch (failure) {
        problem ??= failure.copy;
      } on Object {
        problem ??= transportCopy('dropped');
      } finally {
        await transport?.close();
      }
    }
    throw refusal ?? PairingRefused('unreachable', problem ?? transportCopy('no-path'));
  }

  /// Sends `GET /v1/hello` and says whether the answer proves the server
  /// pinned by [fingerprint]. A relay can echo any fingerprint, so there the
  /// server must sign a fresh challenge; the direct path needs no proof.
  static Future<(ApiResponse, bool)> _greet(ServerTransport transport, String fingerprint) async {
    if (transport.path == ConnectionPath.direct) return (await transport.send(const ApiRequest('GET', '/v1/hello')), true);
    final String challenge = newChallenge();
    final ApiResponse hello = await transport.send(ApiRequest('GET', '/v1/hello', headers: <String, String>{challengeHeader: challenge}));
    return (hello, hello.ok && await verifyServerProof(hello.headers, challenge: challenge, fingerprint: fingerprint));
  }

  Future<void> dispose() async {
    await disconnect();
    await _statuses.close();
    await _events.close();
    await _profiles.close();
  }
}

/// Pairing did not produce a token: [code] is the server's `error-code`, or
/// `unreachable` when no path answered as the paired server.
class PairingRefused implements Exception {
  const PairingRefused(this.code, this.copy);

  final String code;
  final ErrorCopy copy;

  String get message => copy.text;

  @override
  String toString() => message;
}

/// Whether a `hello-response` offers editor protocol 1.
bool speaksProtocol(Map<String, Object?> hello) {
  final Object? protocols = hello['protocols'];
  return hello['protocol'] == 1 || (protocols is List<Object?> && protocols.contains(1));
}

/// De-duplicates events while the relay and direct streams both run.
///
/// Event frames carry no sequence number or cursor (schema `event`), so the
/// two copies of one event are matched by content: an event is delivered
/// from whichever stream brings it first, and the other stream's identical
/// copy is dropped when it arrives. Nothing is lost, because an event only
/// one stream carries (published before the direct stream was registered) is
/// delivered as it comes. The events are state notices (catalog versions,
/// diagnostics, preview states, applied changes), so dropping a genuinely
/// repeated identical one within the overlap changes nothing for listeners.
class _EventOverlap {
  static const int _limit = 256;

  final Map<ConnectionPath, List<String>> _unmatched = <ConnectionPath, List<String>>{
    ConnectionPath.direct: <String>[],
    ConnectionPath.relay: <String>[],
  };

  /// Whether [event] from [from] is new to listeners.
  bool accept(ConnectionPath from, Map<String, Object?> event) {
    final String key = jsonEncode(_canonical(event));
    final ConnectionPath other = from == ConnectionPath.direct ? ConnectionPath.relay : ConnectionPath.direct;
    if (_unmatched[other]!.remove(key)) return false;
    final List<String> mine = _unmatched[from]!;
    mine.add(key);
    if (mine.length > _limit) mine.removeAt(0);
    return true;
  }

  /// [value] with object keys sorted, so key order does not matter.
  static Object? _canonical(Object? value) {
    if (value is Map<String, Object?>) {
      final List<String> keys = value.keys.toList()..sort();
      return <String, Object?>{for (final String key in keys) key: _canonical(value[key])};
    }
    if (value is List<Object?>) return <Object?>[for (final Object? item in value) _canonical(item)];
    return value;
  }
}
