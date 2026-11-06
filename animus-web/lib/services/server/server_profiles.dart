import 'dart:async';
import 'dart:convert';

import 'pairing.dart';
import 'transport.dart';

/// String storage: localStorage and sessionStorage in the browser, a map in
/// tests.
abstract class KeyValueStore {
  String? read(String key);

  /// Stores [value]. False when the browser refused it, so it lasts only as
  /// long as this page.
  bool write(String key, String value);

  void remove(String key);

  /// Keys another tab changed (null: everything may have changed).
  Stream<String?> get changes;
}

class MemoryKeyValueStore implements KeyValueStore {
  MemoryKeyValueStore({this.refuseWrites = false});

  final Map<String, String> values = <String, String>{};

  /// Acts like a private window that refuses storage.
  final bool refuseWrites;
  final StreamController<String?> _changes = StreamController<String?>.broadcast(sync: true);

  @override
  String? read(String key) => values[key];

  @override
  bool write(String key, String value) {
    values[key] = value;
    return !refuseWrites;
  }

  @override
  void remove(String key) => values.remove(key);

  @override
  Stream<String?> get changes => _changes.stream;

  /// What another tab sharing this storage does: write [key] and say so.
  void writeFromOtherTab(String key, String? value) {
    if (value == null) {
      values.remove(key);
    } else {
      values[key] = value;
    }
    _changes.add(key);
  }
}

/// A token from `POST /v1/pair`.
class ServerToken {
  const ServerToken({required this.token, required this.tokenId, required this.scopes, required this.expiresAt, this.groups});

  final String token;
  final String tokenId;
  final List<String> scopes;
  final List<String>? groups;
  final DateTime expiresAt;

  bool has(String scope) => scopes.contains(scope);

  bool get canApply => scopes.any((String scope) => scope.startsWith('apply:'));

  Map<String, Object?> toJson() => <String, Object?>{
        'token': token,
        'tokenId': tokenId,
        'scopes': scopes,
        if (groups != null) 'groups': groups,
        'expiresAt': expiresAt.toUtc().toIso8601String(),
      };

  static ServerToken? fromJson(Object? json) {
    if (json is! Map<String, Object?> || json['token'] is! String || json['tokenId'] is! String) return null;
    final DateTime? expires = DateTime.tryParse('${json['expiresAt']}');
    if (expires == null) return null;
    return ServerToken(
      token: json['token']! as String,
      tokenId: json['tokenId']! as String,
      scopes: <String>[for (final Object? scope in (json['scopes'] as List<Object?>?) ?? const <Object?>[]) if (scope is String) scope],
      groups: json['groups'] is List<Object?> ? <String>[for (final Object? group in json['groups']! as List<Object?>) '$group'] : null,
      expiresAt: expires.toUtc(),
    );
  }
}

/// A paired server: where to reach it and the pinned key. The token is kept
/// apart, in localStorage only when [remember] is set.
class ServerProfile {
  const ServerProfile({
    required this.fingerprint,
    required this.serverKey,
    required this.name,
    this.directUrl,
    this.relayUrl,
    this.remember = false,
    this.lastPath,
  });

  final String fingerprint;
  final String serverKey;
  final String name;
  final String? directUrl;
  final String? relayUrl;
  final bool remember;

  /// The path that last worked, tried first on the next connect.
  final ConnectionPath? lastPath;

  String get id => serverIdOf(fingerprint);

  /// Paths in the order to try: the last good one first, then direct before
  /// the relay.
  List<(ConnectionPath, String)> get paths {
    final List<(ConnectionPath, String)> paths = <(ConnectionPath, String)>[
      if (directUrl != null) (ConnectionPath.direct, directUrl!),
      if (relayUrl != null) (ConnectionPath.relay, relayUrl!),
    ];
    if (lastPath == ConnectionPath.relay && paths.length == 2) return paths.reversed.toList();
    return paths;
  }

  ServerProfile copyWith({String? name, String? directUrl, String? relayUrl, bool? remember, ConnectionPath? lastPath}) => ServerProfile(
        fingerprint: fingerprint,
        serverKey: serverKey,
        name: name ?? this.name,
        directUrl: directUrl ?? this.directUrl,
        relayUrl: relayUrl ?? this.relayUrl,
        remember: remember ?? this.remember,
        lastPath: lastPath ?? this.lastPath,
      );

  Map<String, Object?> toJson() => <String, Object?>{
        'fingerprint': fingerprint,
        'serverKey': serverKey,
        'name': name,
        if (directUrl != null) 'directUrl': directUrl,
        if (relayUrl != null) 'relayUrl': relayUrl,
        'remember': remember,
        if (lastPath != null) 'lastPath': lastPath!.name,
      };

  static ServerProfile? fromJson(Object? json) {
    if (json is! Map<String, Object?> || json['fingerprint'] is! String || json['serverKey'] is! String) return null;
    final String fingerprint = json['fingerprint']! as String;
    // The pin is the point: a stored profile whose key does not hash to its
    // fingerprint was tampered with and is dropped.
    if (!RegExp(r'^sha256:[0-9a-f]{64}$').hasMatch(fingerprint) || fingerprintOf(json['serverKey']! as String) != fingerprint) return null;
    return ServerProfile(
      fingerprint: fingerprint,
      serverKey: json['serverKey']! as String,
      name: json['name'] is String ? json['name']! as String : serverIdOf(fingerprint),
      directUrl: json['directUrl'] as String?,
      relayUrl: json['relayUrl'] as String?,
      remember: json['remember'] == true,
      lastPath: ConnectionPath.values.asNameMap()[json['lastPath']],
    );
  }
}

/// Saved servers in localStorage; each token in localStorage when the user
/// asked to be remembered, otherwise in sessionStorage for this tab.
class ServerProfileStore {
  ServerProfileStore({required this.local, required this.session});

  final KeyValueStore local;
  final KeyValueStore session;

  static const String profilesKey = 'animus.servers';
  static String tokenKey(String serverId) => 'animus.token.$serverId';

  List<ServerProfile> load() {
    final String? text = local.read(profilesKey);
    if (text == null) return const <ServerProfile>[];
    try {
      final Object? json = jsonDecode(text);
      if (json is! List<Object?>) return const <ServerProfile>[];
      return <ServerProfile>[
        for (final Object? entry in json)
          if (ServerProfile.fromJson(entry) case final ServerProfile profile) profile,
      ];
    } on FormatException {
      return const <ServerProfile>[];
    }
  }

  /// False when the browser refused to store the list.
  bool save(List<ServerProfile> profiles) =>
      local.write(profilesKey, jsonEncode(<Map<String, Object?>>[for (final ServerProfile profile in profiles) profile.toJson()]));

  /// Another tab changed the saved servers.
  Stream<void> get changes => local.changes.where((String? key) => key == null || key == profilesKey);

  ServerToken? token(ServerProfile profile) {
    final String? text = session.read(tokenKey(profile.id)) ?? (profile.remember ? local.read(tokenKey(profile.id)) : null);
    if (text == null) return null;
    try {
      return ServerToken.fromJson(jsonDecode(text));
    } on FormatException {
      return null;
    }
  }

  /// False when the browser refused to keep the token, so it lasts only
  /// until this page closes.
  bool saveToken(ServerProfile profile, ServerToken token) {
    final String text = jsonEncode(token.toJson());
    final bool kept = session.write(tokenKey(profile.id), text);
    if (profile.remember) return local.write(tokenKey(profile.id), text) && kept;
    local.remove(tokenKey(profile.id));
    return kept;
  }

  void forgetToken(ServerProfile profile) {
    session.remove(tokenKey(profile.id));
    local.remove(tokenKey(profile.id));
  }
}
