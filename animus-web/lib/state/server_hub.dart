import 'dart:async';

import '../logic/shared_schemas.dart';
import '../model/server_binding.dart';
import '../services/server/pairing.dart';
import '../services/server/server_connection.dart';
import '../services/server/server_profiles.dart';
import '../services/server/transport.dart';

typedef HubListener = void Function();

/// Paired servers and their connections, kept apart from document state.
/// Opens each path through [open], which in the browser is fetch, WebSocket
/// and the relay socket.
class ServerHub {
  ServerHub({required this.profiles, required this.open, required this.schemas, this.delay = realDelay, DateTime Function()? now})
      : _now = now ?? DateTime.now {
    _servers = profiles.load();
  }

  final ServerProfileStore profiles;

  /// Opens a path for a server fingerprint.
  final Future<ServerTransport> Function(ConnectionPath path, String url, String fingerprint) open;
  final SharedSchemas schemas;
  final Delay delay;
  final DateTime Function() _now;

  List<ServerProfile> _servers = const <ServerProfile>[];
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

  ConnectionStatus status(String id) => _connections[id]?.status ?? const ConnectionStatus(ConnectionPhase.offline);

  bool hasToken(String id) {
    final ServerProfile? profile = server(id);
    return profile != null && (_connections[id]?.token ?? profiles.token(profile)) != null;
  }

  void _store(ServerProfile profile) {
    _servers = <ServerProfile>[
      for (final ServerProfile existing in _servers)
        if (existing.id != profile.id) existing,
      profile,
    ]..sort((ServerProfile a, ServerProfile b) => a.name.compareTo(b.name));
    profiles.save(_servers);
    _changed();
  }

  /// Pairs with a code, saves the server and its token and connects.
  /// Returns the server id.
  Future<String> pair(String code, {required bool remember, String? client}) async {
    final PairingPayload payload = decodePairingCode(extractPairingCode(code), schemas);
    if (!payload.expiresAt.isAfter(_now().toUtc())) {
      throw const PairingRefused('pairing-expired', 'This pairing code expired. Run /animus editor pair again.');
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
    profiles.saveToken(profile, token);
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

  /// Forgets a server and its token in this browser.
  Future<void> forget(String id) async {
    final ServerConnection? connection = _connections.remove(id);
    await connection?.dispose();
    final ServerProfile? profile = server(id);
    if (profile != null) profiles.forgetToken(profile);
    _servers = <ServerProfile>[
      for (final ServerProfile existing in _servers)
        if (existing.id != id) existing,
    ];
    profiles.save(_servers);
    _changed();
  }
}
