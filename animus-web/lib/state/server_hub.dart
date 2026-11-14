import 'dart:async';

import '../logic/shared_schemas.dart';
import '../model/server_binding.dart';
import '../services/server/connection_status.dart';
import '../services/server/error_copy.dart';
import '../services/server/page_origin.dart' if (dart.library.js_interop) '../services/server/page_origin_web.dart';
import '../services/server/pairing.dart';
import '../services/server/server_connection.dart';
import '../services/server/server_pairing.dart';
import '../services/server/server_profiles.dart';
import '../services/server/transport.dart';
import '../model/display_name.dart' show closing;

typedef HubListener = void Function();

/// Paired servers and their connections, kept apart from document state.
/// Opens each path through [open], which in the browser is fetch, WebSocket
/// and the relay socket.
///
/// The saved list is shared by every tab: each change re-reads it before
/// writing, and another tab's change reloads it here.
class ServerHub {
  ServerHub({
    required this.profiles,
    required this.open,
    required this.schemas,
    this.onForget,
    this.delay = realDelay,
    this.pageOrigin = currentPageOrigin,
    DateTime Function()? now,
  }) : _now = now ?? DateTime.now {
    _servers = profiles.load();
    profiles.changes.listen((void _) => _reload());
  }

  final ServerProfileStore profiles;

  /// Called with a forgotten server's id, to remove its live workspace.
  final Future<void> Function(String serverId)? onForget;

  /// Opens a path for a server fingerprint.
  final Future<ServerTransport> Function(ConnectionPath path, String url, String fingerprint) open;
  final SharedSchemas schemas;
  final Delay delay;

  /// The page's origin, read when pairing: an https page may be kept from a
  /// plain http server on the local network.
  final String? Function() pageOrigin;
  final DateTime Function() _now;

  List<ServerProfile> _servers = const <ServerProfile>[];

  /// Set when the browser refused to store the last pairing or server list.
  bool storageRefused = false;
  final Map<String, ServerConnection> _connections = <String, ServerConnection>{};
  final List<HubListener> _listeners = <HubListener>[];

  List<ServerProfile> get servers => _servers;

  void addListener(HubListener listener) => _listeners.add(listener);

  void removeListener(HubListener listener) => _listeners.remove(listener);

  void _changed() {
    for (final HubListener listener in List<HubListener>.of(_listeners)) {
      listener();
    }
  }

  ServerProfile? server(String id) {
    for (final ServerProfile profile in _servers) {
      if (profile.id == id) return profile;
    }
    return null;
  }

  /// The workspace id of a server's live workspace.
  static String workspaceIdOf(String serverId) => 'server-$serverId';

  ServerBinding bindingFor(ServerProfile profile) => ServerBinding(serverId: profile.id, fingerprint: profile.fingerprint);

  /// The connection for a saved server, made on first use.
  ServerConnection connection(String id) {
    final ServerConnection? existing = _connections[id];
    if (existing != null) return existing;
    final ServerProfile profile = server(id)!;
    final ServerConnection connection = ServerConnection(
      profile: profile,
      token: profiles.token(profile),
      open: (ConnectionPath path, String url) => open(path, url, profile.fingerprint),
      delay: delay,
      now: _now,
    );
    connection.statuses.listen((ConnectionStatus status) {
      if (status.phase == ConnectionPhase.needsPairing) profiles.forgetToken(connection.profile);
      if (status.online) {
        _probes.remove(id);
        final Object? name = status.hello?['serverName'];
        if (name is String) _rename(id, name);
      }
      _changed();
    });
    connection.profileChanges.listen((ServerProfile next) => _store(next));
    _connections[id] = connection;
    return connection;
  }

  /// Reachability of saved servers without a live connection, by id.
  final Map<String, ConnectionStatus> _probes = <String, ConnectionStatus>{};

  /// Checks every saved server that has no live connection with one
  /// `GET /v1/hello` (no token, no event stream), so the start page shows
  /// Online, Not reachable or Checking without opening a workspace. A
  /// server whose hello carries a name takes it.
  void probeServers() {
    for (final ServerProfile profile in _servers) {
      final ServerConnection? live = _connections[profile.id];
      if (live != null && live.status.phase != ConnectionPhase.offline) continue;
      if (_probes[profile.id]?.phase == ConnectionPhase.connecting) continue;
      _probes[profile.id] = const ConnectionStatus(ConnectionPhase.connecting, probed: true);
      unawaited(probeServer(profile, (ConnectionPath path, String url) => open(path, url, profile.fingerprint)).then(
        ((ConnectionPath, String?)? found) {
          _probes[profile.id] = found == null
              ? ConnectionStatus(ConnectionPhase.offline, probed: true, problem: transportCopy('no-answer'))
              : ConnectionStatus(ConnectionPhase.online, path: found.$1, probed: true);
          if (found?.$2 != null) _rename(profile.id, found!.$2!);
          _changed();
        },
      ));
    }
    _changed();
  }

  /// Gives a saved server the name it reports, keeping the one it has when
  /// the server sends none.
  void _rename(String id, String name) {
    final ServerProfile? profile = server(id);
    final String trimmed = name.trim();
    if (profile == null || trimmed.isEmpty || profile.name == trimmed) return;
    _store(profile.copyWith(name: trimmed));
  }

  /// The browser came back online or the tab became visible: a connection
  /// on the relay probes the direct path now, and one waiting to reconnect
  /// tries again now instead of waiting out its backoff.
  void wake() {
    for (final ServerConnection connection in _connections.values) {
      connection.probeNow();
      if (connection.status.phase == ConnectionPhase.reconnecting) connection.retryNow();
    }
  }

  /// Where a saved server stands: its live connection, or else the last
  /// reachability check.
  ConnectionStatus status(String id) {
    final ConnectionStatus? live = _connections[id]?.status;
    if (live != null && live.phase != ConnectionPhase.offline) return live;
    return _probes[id] ?? live ?? const ConnectionStatus(ConnectionPhase.offline);
  }

  bool hasToken(String id) {
    final ServerProfile? profile = server(id);
    return profile != null && (_connections[id]?.token ?? profiles.token(profile)) != null;
  }

  /// Another tab saved or forgot a server.
  void _reload() {
    _servers = profiles.load();
    for (final String id in _connections.keys.toList()) {
      if (server(id) == null) unawaited(_connections.remove(id)!.dispose());
    }
    _changed();
  }

  void _store(ServerProfile profile) {
    _servers = <ServerProfile>[
      for (final ServerProfile existing in profiles.load())
        if (existing.id != profile.id) existing,
      profile,
    ]..sort((ServerProfile a, ServerProfile b) => a.name.compareTo(b.name));
    if (!profiles.save(_servers)) storageRefused = true;
    _changed();
  }

  /// Pairs with a code, saves the server and its token and connects.
  /// Returns the server id.
  Future<String> pair(String code, {required bool remember, String? client}) async {
    final PairingPayload payload = decodePairingCode(extractPairingCode(code), schemas);
    if (!payload.expiresAt.isAfter(_now().toUtc())) throw PairingRefused.because(PairingFailure.expired);
    final (ServerToken token, ConnectionPath path, String? named) = await pairServer(
      payload,
      (ConnectionPath path, String url) => open(path, url, payload.fingerprint),
      client: client,
      pageOrigin: pageOrigin(),
    );
    final ServerProfile? known = server(payload.serverId);
    final ServerProfile profile = ServerProfile(
      fingerprint: payload.fingerprint,
      serverKey: payload.serverKey,
      name: payload.serverName ?? named ?? known?.name ?? payload.label ?? 'Server ${payload.serverId.substring(0, 8)}',
      directUrl: payload.directUrl,
      relayUrl: payload.relayUrl,
      remember: remember,
      lastPath: path,
    );
    final ServerConnection? previous = _connections.remove(profile.id);
    await previous?.dispose();
    storageRefused = false;
    if (!profiles.saveToken(profile, token)) storageRefused = true;
    _store(profile);
    unawaited(connection(profile.id).connect());
    return profile.id;
  }

  Future<void> connect(String id) async {
    final ServerConnection connection = this.connection(id);
    connection.token ??= profiles.token(connection.profile);
    await connection.connect();
  }

  Future<void> disconnect(String id) async => _connections[id]?.disconnect();

  /// The id of [id]'s token while it has not expired.
  String? _tokenId(String id) {
    final ServerProfile? profile = server(id);
    if (profile == null) return null;
    final ServerToken? token = _connections[id]?.token ?? profiles.token(profile);
    return token != null && token.expiresAt.isAfter(_now().toUtc()) ? token.tokenId : null;
  }

  /// The confirm text for forgetting [id], true to where the server stands
  /// now: online, it is asked to end the pairing; not reachable, the
  /// pairing stays valid until it expires.
  String forgetPrompt(String id) {
    const String lost = 'Changes not applied are lost.';
    if (_tokenId(id) == null) return lost;
    return status(id).online ? 'Ends the pairing on the server. $lost' : 'Not reachable now, so the pairing stays valid until it expires. $lost';
  }

  /// The line after forgetting [name]; [stillValid] is what [forget] returned.
  static String forgotLine(String name, String? stillValid) =>
      stillValid == null ? 'Forgot ${closing(name)}' : 'Forgot ${closing(name)} Its pairing ends when it expires.';

  /// Forgets a server in this browser: its token, its saved entry and its
  /// live workspace with any changes in it. While the pairing is valid the
  /// server is asked to revoke it, briefly and quietly (no notice of the
  /// pairing ending), connecting first when it is not connected (the start
  /// page); a refusal, an older server or no answer still forgets here.
  /// Returns the id of a token the server still accepts, or null when it
  /// ended the pairing or there was none.
  Future<String?> forget(String id) async {
    final String? tokenId = _tokenId(id);
    ServerConnection? connection = _connections[id];
    bool revoked = false;
    if (tokenId != null) {
      if (connection == null || !connection.status.online) {
        await Future.any(<Future<void>>[connect(id), delay(const Duration(seconds: 4))]);
        connection = _connections[id];
      }
      revoked = connection != null && await connection.revoke();
    }
    _connections.remove(id);
    await connection?.dispose();
    final ServerProfile? profile = server(id);
    if (profile != null) profiles.forgetToken(profile);
    _servers = <ServerProfile>[
      for (final ServerProfile existing in profiles.load())
        if (existing.id != id) existing,
    ];
    if (!profiles.save(_servers)) storageRefused = true;
    await onForget?.call(id);
    _changed();
    return revoked ? null : tokenId;
  }
}
