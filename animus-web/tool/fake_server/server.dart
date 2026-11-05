import 'dart:async';
import 'dart:io';

import 'api.dart';
import 'control.dart';
import 'direct.dart';
import 'documents.dart';
import 'events.dart';
import 'identity.dart';
import 'previews.dart';
import 'protocol.dart';
import 'relay.dart';
import 'tokens.dart';

export 'api.dart' show FakeSettings;

/// Which paths a pairing code carries.
enum PairPaths { both, direct, relay }

/// An in-memory editor-v1 server for local development and tests: the direct
/// API at `/animus/v1/`, a relay emulation at `/app` and test controls at
/// `/__fake/`, all on one loopback port. Nothing is written to disk.
class FakeServer {
  FakeServer._(this.settings, this._http) : api = FakeApi(settings) {
    direct = DirectPath(this);
    relay = RelayPath(this);
    control = ControlPath(this);
    _housekeeping = Timer.periodic(const Duration(seconds: 1), (Timer timer) => _tick());
    _http.listen(_serve);
  }

  static Future<FakeServer> start(FakeSettings settings, {int port = 9797}) async {
    final HttpServer http = await HttpServer.bind(InternetAddress.loopbackIPv4, port);
    http.autoCompress = false;
    return FakeServer._(settings, http);
  }

  final FakeSettings settings;
  final FakeApi api;
  final HttpServer _http;
  late final DirectPath direct;
  late final RelayPath relay;
  late final ControlPath control;
  late final Timer _housekeeping;

  DateTime? _directDownUntil;
  DateTime? _relayDownUntil;
  DateTime? _offlineUntil;
  bool _announcedOffline = false;

  int get port => _http.port;

  String get directUrl => 'http://127.0.0.1:$port';

  /// The contract's relayUrl is wss://; local development uses ws:// on loopback.
  String get relayUrl => 'ws://127.0.0.1:$port';

  FakeIdentity get identity => api.identity;

  bool get directDown => _until(_directDownUntil);

  bool get relayDown => _until(_relayDownUntil);

  /// The relay is up but reports the server as disconnected.
  bool get offline => _until(_offlineUntil);

  static bool _until(DateTime? until) => until != null && now().isBefore(until);

  /// Mints a pairing secret and its code and link.
  Map<String, Object?> pair([PairPaths paths = PairPaths.both, List<String> extraScopes = const <String>[]]) {
    final Pairing pairing = api.tokens.pair(extraScopes);
    final String code = pairingCode(
      directUrl: paths == PairPaths.relay ? null : directUrl,
      relayUrl: paths == PairPaths.direct ? null : relayUrl,
      fingerprint: identity.fingerprint,
      serverKey: identity.serverKey,
      tokenId: pairing.tokenId,
      secret: pairing.secret,
      expiresAt: pairing.expires,
      label: 'fake',
      serverName: settings.serverName,
    );
    final String origin = settings.allowedOrigins.isEmpty ? 'http://127.0.0.1:8098' : settings.allowedOrigins.first;
    return <String, Object?>{'code': code, 'link': '$origin/#/pair/$code'};
  }

  /// Makes [path] unreachable for [duration], closing its open sockets now.
  void drop(String path, Duration duration) {
    final DateTime until = now().add(duration);
    if (path == 'direct' || path == 'all') {
      _directDownUntil = until;
      api.events.dropWhere((EventStream stream) => stream.path == StreamPath.direct);
      direct.closeSockets();
    }
    if (path == 'relay' || path == 'all') {
      _relayDownUntil = until;
      api.events.dropWhere((EventStream stream) => stream.path == StreamPath.relay);
      relay.closeSockets();
    }
  }

  /// Keeps the relay up while the server is gone from it for [duration].
  void goOffline(Duration duration) {
    _offlineUntil = now().add(duration);
    _announcedOffline = true;
    api.events.dropWhere((EventStream stream) => stream.path == StreamPath.relay);
    relay.announce(false);
  }

  void restore() {
    _directDownUntil = null;
    _relayDownUntil = null;
    _offlineUntil = null;
    _onlineAgain();
  }

  void reset() {
    restore();
    api.reset();
  }

  void _onlineAgain() {
    if (!_announcedOffline) return;
    _announcedOffline = false;
    relay.announce(true);
  }

  void _tick() {
    api.housekeeping();
    if (_announcedOffline && !offline) _onlineAgain();
  }

  Map<String, Object?> state() => <String, Object?>{
        'port': port,
        'fingerprint': identity.fingerprint,
        'paths': <String, Object?>{
          'direct': directDown ? 'down' : 'up',
          'relay': relayDown ? 'down' : 'up',
          'server': offline ? 'offline' : 'online',
        },
        'tokens': <Object?>[for (final FakeToken token in api.tokens.tokens) token.state],
        'revokedTokens': api.tokens.revoked,
        'pendingPairings': api.tokens.pendingPairings,
        'previews': <Object?>[for (final Preview preview in api.previews.running) preview.state],
        'appliedChanges': api.changes.appliedChangeIds,
        'idempotencyKeys': api.changes.statuses,
        'streams': <String, Object?>{
          'direct': api.events.openFor(StreamPath.direct),
          'relay': api.events.openFor(StreamPath.relay),
        },
        'relayConnections': relay.connections,
        'documents': <Object?>[for (final StoredDocument document in api.documents.list()) document.version],
      };

  Future<void> _serve(HttpRequest request) async {
    try {
      final String path = request.uri.path;
      if (path == '/__fake' || path.startsWith('/__fake/')) {
        await control.serve(request);
      } else if (path == '/app') {
        await relay.serve(request);
      } else {
        await direct.serve(request);
      }
    } on Object {
      try {
        request.response.statusCode = 500;
        await request.response.close();
      } catch (_) {
        // The client already left.
      }
    }
  }

  Future<void> close() async {
    _housekeeping.cancel();
    api.events.closeAll('server-stopping');
    direct.closeSockets();
    relay.closeSockets();
    await _http.close(force: true);
  }
}
