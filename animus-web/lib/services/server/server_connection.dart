import 'dart:async';
import 'dart:math' as math;

import 'connection_status.dart';
import 'error_copy.dart';
import 'event_overlap.dart';
import 'page_activity.dart';
import 'request_pacer.dart';
import 'server_pairing.dart';
import 'server_profiles.dart';
import 'transport.dart';

typedef Delay = Future<void> Function(Duration duration);

Future<void> realDelay(Duration duration) => Future<void>.delayed(duration);

/// Error codes after which the token is no use.
const Set<String> tokenErrorCodes = <String>{'unauthorized', 'token-expired', 'token-revoked'};

/// Wait before retry [attempt] (1-based) when the server has been gone for
/// [away]: 1 to 5 s through the first minute, so a restart is caught within
/// seconds, then doubling from 5 s to 30 s. While the page is [visible] the
/// late waits stay at 5 s, so a server back after a long outage shows within
/// seconds to someone looking.
Duration reconnectDelay(int attempt, {Duration away = Duration.zero, bool visible = false}) {
  const List<int> early = <int>[1, 1, 2, 2, 3, 3, 4, 5];
  if (away < const Duration(minutes: 1)) return Duration(seconds: early[math.min(attempt, early.length) - 1]);
  final int late = (away.inSeconds - 60) ~/ 30;
  return Duration(seconds: math.min(visible ? 5 : 30, 5 << math.min(late + 1, 3)));
}

/// The tab is in front, by [PageActivity].
bool pageVisible() => !PageActivity.hidden;

/// How many times a `rate-limited` or `busy` answer is retried.
const int retryLimit = 5;

/// Authorized requests one connection keeps in flight. The server allows 8
/// per token (`[editor] concurrentRequestsPerToken`) and answers the ninth
/// `429`; the rest of the 8 stay free for the direct-path probe.
const int maxRequestsInFlight = 6;

/// Background requests (terrain) one connection keeps in flight, so two of
/// [maxRequestsInFlight] stay free for the rest.
const int maxBackgroundInFlight = 4;

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
    this.handshakeTimeout = const Duration(seconds: 5),
    this.visible = pageVisible,
    this.probeIntervals = defaultProbeIntervals,
    this.overlap = const Duration(seconds: 2),
    DateTime Function()? now,
  })  : assert(probeIntervals.isNotEmpty),
        _now = now ?? DateTime.now;

  final TransportFactory open;
  final Delay delay;

  /// How long one path gets to open, answer hello and capabilities and open
  /// the event stream before it is closed and the next path tried.
  final Duration handshakeTimeout;

  /// Whether the page is in front, read before each reconnect wait.
  final bool Function() visible;

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

  /// The relay path kept open while the server is away from it, so the
  /// reconnect starts the moment the relay says it is back.
  ServerTransport? _parked;

  /// When the connection was last online; null before it ever was.
  DateTime? _lastOnline;

  /// Paces authorized requests to the server's per-token rate.
  final RequestPacer _pacer = RequestPacer();

  /// Keeps at most [maxRequestsInFlight] authorized requests in flight.
  final RequestSlots _slots = RequestSlots(maxRequestsInFlight);

  /// Keeps at most [maxBackgroundInFlight] of them background requests.
  final RequestSlots _backgroundSlots = RequestSlots(maxBackgroundInFlight);

  /// The server closed the stream it had just opened (4000 + status): a
  /// refusal, so the next attempt waits instead of going at once.
  bool _streamRefused = false;

  /// Set by [revoke]: the server is being forgotten, so nothing more is said
  /// on [statuses], [events] or [profileChanges].
  bool _silent = false;

  /// The server's wait from a refused event stream, used for the next retry.
  Duration? _refusedWait;

  /// Bumped whenever the online path changes or is lost; a probe that sees
  /// another value than it started with gives up.
  int _session = 0;
  Completer<void>? _probeWake;
  EventOverlap? _overlap;

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
    if (!_silent) _statuses.add(status);
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
    _lastOnline = null;
    retryNow();
    final Completer<void>? lost = _lost;
    if (lost != null && !lost.isCompleted) lost.complete();
    await _drop();
    await _unpark();
    _emit(const ConnectionStatus(ConnectionPhase.offline));
  }

  /// Ends the online session. A relay path whose server just left stays
  /// open, parked for the reconnect.
  Future<void> _drop() async {
    final EventSubscription? subscription = _subscription;
    final ServerTransport? transport = _transport;
    _subscription = null;
    _transport = null;
    _endSession();
    await subscription?.cancel();
    if (transport is RelayLink && !(transport as RelayLink).isClosed && !(transport as RelayLink).serverOnline && _running) {
      await _unpark();
      _parked = transport;
      return;
    }
    await transport?.close();
  }

  Future<void> _unpark() async {
    final ServerTransport? parked = _parked;
    _parked = null;
    await parked?.close();
  }

  /// The parked relay path when it is still open, taken for an attempt.
  ServerTransport? _takeParked(ConnectionPath path) {
    final ServerTransport? parked = _parked;
    if (parked == null || parked.path != path) return null;
    _parked = null;
    if ((parked as RelayLink).isClosed) {
      unawaited(parked.close());
      return null;
    }
    return parked;
  }

  /// The words for a path no server answered on: once the connection has
  /// been online, the first minute of silence is a restart.
  ErrorCopy _silence(ErrorCopy problem) {
    final DateTime? online = _lastOnline;
    if (online == null || _now().difference(online) >= const Duration(minutes: 1)) return problem;
    return transportCopy('restarting');
  }

  Future<void> _loop(Completer<void> firstRound) async {
    int attempt = 0;
    final DateTime started = _now();
    while (_running) {
      ErrorCopy? problem;
      bool wasOnline = false;
      int tried = 0;
      int unanswered = 0;
      for (final (ConnectionPath path, String url) in _order()) {
        if (!_running) break;
        _emit(ConnectionStatus(ConnectionPhase.connecting, path: path, attempt: attempt, hello: _hello));
        final ErrorCopy? result = await _attempt(path, url);
        if (result == null) {
          if (!firstRound.isCompleted) firstRound.complete();
          await _lost!.future;
          if (_streamRefused) {
            // Refused right after opening: count it as a failed attempt.
            _streamRefused = false;
            problem = _status.problem;
            tried++;
          } else {
            wasOnline = true;
            attempt = 0;
          }
          break;
        }
        tried++;
        if (_noAnswer.any((String code) => identical(result, transportCopy(code)))) unanswered++;
        problem = result;
      }
      // Both paths silent points at the server, not at either path.
      if (tried > 1 && unanswered == tried) problem = transportCopy('no-answer');
      if (problem != null && unanswered == tried) problem = _silence(problem);
      if (!firstRound.isCompleted) firstRound.complete();
      if (!_running) break;
      // Lost while online: fail over at once, the other path first.
      if (wasOnline) continue;
      attempt++;
      final Duration backoff = reconnectDelay(attempt, away: _now().difference(_lastOnline ?? started), visible: visible());
      final Duration? hinted = _refusedWait;
      _refusedWait = null;
      final Duration wait = hinted != null && hinted > backoff ? hinted : backoff;
      _emit(ConnectionStatus(ConnectionPhase.reconnecting, attempt: attempt, retryAt: _now().add(wait), problem: problem, hello: _hello));
      final Completer<void> wake = Completer<void>();
      _wake = wake;
      final ServerTransport? parked = _parked;
      await Future.any(<Future<void>>[delay(wait), wake.future, if (parked != null) (parked as RelayLink).serverBack]);
      _wake = null;
    }
    _running = false;
    await _unpark();
  }

  /// Path failures that mean nothing answered at all.
  static const List<String> _noAnswer = <String>['unreachable', 'timeout', 'relay-unreachable', 'server-offline', 'server-left', 'restarting'];

  /// Paths to try: after a loss, the other path first.
  List<(ConnectionPath, String)> _order() {
    final List<(ConnectionPath, String)> paths = _profile.paths;
    final ConnectionPath? lost = _lostPath;
    _lostPath = null;
    if (lost == null || paths.length < 2 || paths.first.$1 != lost) return paths;
    return paths.reversed.toList();
  }

  /// One attempt on one path. Returns null once online, else the problem.
  /// Opening the path, hello, capabilities and the event stream share
  /// [handshakeTimeout]; a path still busy after it is closed and reported
  /// as silent, so the other path gets its turn within seconds.
  Future<ErrorCopy?> _attempt(ConnectionPath path, String url) async {
    final _Handshake shake = _Handshake(path);
    final ErrorCopy? result = await Future.any(<Future<ErrorCopy?>>[
      _handshake(shake, url),
      delay(handshakeTimeout).then((void _) => shake.expire()),
    ]);
    return shake.expired ?? result;
  }

  /// [_attempt] without its budget. Once [shake] expired, a path that opens
  /// late is closed and nothing heard after is acted on. Any failure, not
  /// only a [TransportFailure], ends as a problem.
  Future<ErrorCopy?> _handshake(_Handshake shake, String url) async {
    final ConnectionPath path = shake.path;
    ServerTransport? transport;
    try {
      final ServerTransport opened = _takeParked(path) ?? await open(path, url);
      if (shake.late) {
        await opened.close();
        return shake.expired;
      }
      transport = opened;
      shake.transport = opened;
      if (opened is RelayLink && !(opened as RelayLink).serverOnline) {
        // The relay is up and the server is not on it: keep the relay open
        // and hear the moment it comes back.
        shake.settled = true;
        transport = null;
        await _unpark();
        _parked = opened;
        return transportCopy('server-offline');
      }
      final (ApiResponse hello, bool proven) = await greetServer(opened, _profile.fingerprint);
      if (shake.late) return shake.expired;
      final Object? body = hello.body;
      if (!hello.ok || body is! Map<String, Object?>) return await _failed(shake, opened, hello.problem);
      if (body['fingerprint'] != _profile.fingerprint || !proven) {
        _running = false;
        final ErrorCopy wrong = transportCopy('wrong-server');
        _emit(ConnectionStatus(ConnectionPhase.refused, path: path, problem: wrong));
        return await _failed(shake, opened, wrong);
      }
      if (!speaksProtocol(body)) {
        _running = false;
        final ErrorCopy protocol = serverCopy('unsupported-protocol');
        _emit(ConnectionStatus(ConnectionPhase.refused, path: path, problem: protocol));
        return await _failed(shake, opened, protocol);
      }
      _hello = body;
      final ApiResponse capabilities = await opened.send(_authorized(const ApiRequest('GET', '/v1/capabilities')));
      if (shake.late) return shake.expired;
      if (_tokenRefused(capabilities) || !capabilities.ok) return await _failed(shake, opened, capabilities.problem);
      // Disconnected or forgotten meanwhile: open no stream.
      if (!_running) return await _failed(shake, opened, transportCopy('closed'));
      _capabilities = capabilities.body is Map<String, Object?> ? capabilities.body! as Map<String, Object?> : null;
      final EventSubscription subscription;
      try {
        subscription = await opened.subscribe(token!.token);
      } on StreamRefused catch (refused) {
        if (shake.late) return shake.expired;
        final ErrorCopy problem = serverCopy(refused.code, status: refused.status);
        if (tokenErrorCodes.contains(refused.code)) _needsPairing(problem);
        _refusedWait = refused.retryAfter;
        return await _failed(shake, opened, problem);
      }
      if (shake.late || !_running) {
        await subscription.cancel();
        return await _failed(shake, opened, transportCopy('closed'));
      }
      shake.settled = true;
      _transport = opened;
      _subscription = subscription;
      _lost = Completer<void>();
      _session++;
      subscription.events.listen(
        (Map<String, Object?> event) => _deliver(path, event),
        onError: (Object _) {},
        onDone: () => _streamEnded(opened, subscription),
      );
      _rememberPath(path);
      _lastOnline = _now();
      await _unpark();
      _emit(ConnectionStatus(ConnectionPhase.online, path: path, hello: _hello));
      if (path == ConnectionPath.relay && _profile.directUrl != null) unawaited(_probeLoop(_session, _profile.directUrl!));
      return null;
    } on TransportFailure catch (failure) {
      return await _failed(shake, transport, failure.copy);
    } on Object {
      // A malformed answer or a bug: reported like a dropped path, then retried.
      return await _failed(shake, transport, transportCopy('dropped'));
    }
  }

  /// Ends a handshake that failed: its budget stops, its path closes, and
  /// the budget's word stands when it ran out first.
  Future<ErrorCopy> _failed(_Handshake shake, ServerTransport? transport, ErrorCopy problem) async {
    final ErrorCopy outcome = shake.expired ?? problem;
    shake.settled = true;
    await transport?.close();
    return outcome;
  }

  void _rememberPath(ConnectionPath path) {
    if (_profile.lastPath == path) return;
    _profile = _profile.copyWith(lastPath: path);
    if (!_silent) _profiles.add(_profile);
  }

  void _deliver(ConnectionPath from, Map<String, Object?> event) {
    if (_silent) return;
    final EventOverlap? overlap = _overlap;
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
      final EventOverlap overlapping = EventOverlap();
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
      EventOverlap overlapping) {
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
    // A reason is a code, or a status and a code ("429 rate-limited"): match whole words,
    // so "rate-limited" is not read as the closing reason "limit".
    final List<String> words = reason == null ? const <String>[] : reason.split(' ');
    final String? tokenCode = tokenErrorCodes.where(words.contains).firstOrNull;
    if (tokenCode != null) {
      _needsPairing(serverCopy(tokenCode));
      return;
    }
    final String? closing = closingCopy.keys.where(words.contains).firstOrNull;
    // A direct stream closed with 4000 + status carries a server error code;
    // the relay closes with its own codes.
    final ErrorCopy? refused = closing == null && transport.path == ConnectionPath.direct ? serverErrorCopy[reason] : null;
    final ErrorCopy? path = transportErrorCopy[reason] ?? relayErrorCopy[reason];
    if (refused != null || (closing == null && _relayRefusals.contains(reason))) _streamRefused = true;
    _connectionLost(transport.path, closing != null ? closingCopy[closing]! : refused ?? path ?? transportCopy('stream-closed'));
  }

  /// Relay close reasons that refuse the stream rather than lose the path.
  static const Set<String> _relayRefusals = <String>{'rate-limited', 'capacity'};

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
    _lastOnline = _now();
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
    if (request.method != 'GET' && request.counted) {
      _counter = math.max(_counter + 1, _now().millisecondsSinceEpoch);
      headers['X-Animus-Counter'] = '$_counter';
    }
    return request.withHeaders(headers);
  }

  /// Sends an authorized request on the open path. A path failure starts a
  /// reconnect (to the other path first) and is rethrown. A
  /// [ApiRequest.background] request lets every other request go first.
  Future<ApiResponse> request(ApiRequest request) async {
    if (_transport == null || _status.phase != ConnectionPhase.online || token == null) throw NotConnected(_status);
    final RequestSlots? lane = request.background ? _backgroundSlots : null;
    await lane?.take();
    try {
      await _slots.take();
      try {
        await _pacer.take(background: request.background);
        return await _send(request);
      } finally {
        _slots.release();
      }
    } finally {
      lane?.release();
    }
  }

  Future<ApiResponse> _send(ApiRequest request) async {
    final ServerTransport? transport = _transport;
    if (transport == null || _status.phase != ConnectionPhase.online || token == null) throw NotConnected(_status);
    _inFlight[transport] = (_inFlight[transport] ?? 0) + 1;
    try {
      final ApiResponse response = await transport.send(_authorized(request));
      _tokenRefused(response);
      if (response.errorCode == 'rate-limited') _pacer.hold(response.retryAfter ?? const Duration(seconds: 1));
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

  /// [request], sent again after the server's hint while it answers
  /// `rate-limited` or `busy` with one, at most [retries] times. The last
  /// answer comes back either way, so nothing is dropped unsaid. Only for
  /// requests that are safe to repeat: reads, and the batch read.
  Future<ApiResponse> requestRetrying(ApiRequest request, {int retries = retryLimit, math.Random? jitter}) async {
    final math.Random random = jitter ?? math.Random();
    for (int attempt = 0;; attempt++) {
      final ApiResponse response = await this.request(request);
      final Duration? wait = response.retryAfter;
      if (wait == null || attempt >= retries || !(response.errorCode == 'rate-limited' || response.errorCode == 'busy')) return response;
      await delay(wait + Duration(milliseconds: 10 + random.nextInt(40) + attempt * 25));
      if (_status.phase != ConnectionPhase.online) throw NotConnected(_status);
    }
  }

  /// Ends the pairing of a server being forgotten. Stops reconnecting and
  /// says nothing more, so the event stream the server then closes is not
  /// news. When online it asks the server to revoke the token
  /// (`DELETE /v1/token`) and waits at most [timeout]. Returns whether the
  /// server no longer accepts the token: it answered, or closed the stream
  /// as revoked. An older server (404), a failure or no answer is false.
  /// Dispose after.
  Future<bool> revoke({Duration timeout = const Duration(seconds: 3)}) async {
    _silent = true;
    _running = false;
    retryNow();
    if (!_status.online || token == null) return false;
    final Future<bool> asked = request(const ApiRequest('DELETE', '/v1/token', counted: false))
        .then((ApiResponse response) => response.ok, onError: (Object _) => false);
    final bool answered = await Future.any(<Future<bool>>[asked, delay(timeout).then((void _) => false)]);
    return answered || (_status.phase == ConnectionPhase.needsPairing && identical(_status.problem, serverCopy('token-revoked')));
  }

  Future<void> dispose() async {
    await disconnect();
    await _statuses.close();
    await _events.close();
    await _profiles.close();
  }
}

/// One attempt's handshake: the path it opened and whether its budget ran
/// out before it finished.
class _Handshake {
  _Handshake(this.path);

  final ConnectionPath path;
  ServerTransport? transport;

  /// The attempt finished, online or not; the budget no longer applies.
  bool settled = false;

  /// What the attempt reports when the budget ran out first.
  ErrorCopy? expired;

  bool get late => expired != null;

  /// The budget ran out: closes the path and says which side was silent.
  Future<ErrorCopy?> expire() async {
    if (settled) return null;
    // A relay socket that never opened is the relay's fault; a silent hello is the server's.
    final ErrorCopy problem = transportCopy(path == ConnectionPath.relay && transport == null ? 'relay-unreachable' : 'timeout');
    expired = problem;
    await transport?.close();
    return problem;
  }
}
