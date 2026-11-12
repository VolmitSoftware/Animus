import 'dart:async';

import '../logic/shared_schemas.dart';
import '../model/server_binding.dart';
import '../services/server/error_copy.dart';
import '../services/server/pairing.dart';
import '../services/server/server_connection.dart';
import '../services/server/server_profiles.dart';
import '../services/server/transport.dart';

typedef HubListener = void Function();

/// Paired servers and their connections, kept apart from document state.
/// Opens each path through [open], which in the browser is fetch, WebSocket
/// and the relay socket.
///
/// The saved list is shared by every tab: each change re-reads it before
/// writing, and another tab's change reloads it here.
class ServerHub {
  ServerHub({required this.profiles, required this.open, required this.schemas, this.onForget, this.delay = realDelay, DateTime Function()? now})
      : _now = now ?? DateTime.now {
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
      _changed();
    });
    connection.profileChanges.listen((ServerProfile next) => _store(next));
    _connections[id] = connection;
    return connection;
  }

  /// Connects every saved server this browser holds a token for and that has
  /// no live connection yet, so the home page shows where each one stands.
  void connectIdle() {
    for (final ServerProfile profile in _servers) {
      if (hasToken(profile.id) && status(profile.id).phase == ConnectionPhase.offline) unawaited(connect(profile.id));
    }
  }

  /// Probes the direct path now on every connection that is on the relay.
  void probeDirect() {
    for (final ServerConnection connection in _connections.values) {
      connection.probeNow();
    }
  }

  ConnectionStatus status(String id) => _connections[id]?.status ?? const ConnectionStatus(ConnectionPhase.offline);

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
    if (!payload.expiresAt.isAfter(_now().toUtc())) {
      throw PairingRefused('pairing-expired', serverCopy('pairing-expired'));
    }
    final (ServerToken token, ConnectionPath path) = await ServerConnection.pair(
      payload,
      (ConnectionPath path, String url) => open(path, url, payload.fingerprint),
      client: client,
    );
    final ServerProfile? known = server(payload.serverId);
    final ServerProfile profile = ServerProfile(
      fingerprint: payload.fingerprint,
      serverKey: payload.serverKey,
      name: payload.serverName ?? known?.name ?? payload.label ?? 'Server ${payload.serverId.substring(0, 8)}',
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

  /// Forgets a server in this browser: its token, its saved entry and its
  /// live workspace with any drafts in it.
  Future<void> forget(String id) async {
    final ServerConnection? connection = _connections.remove(id);
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
  }
}
