/// The terrain source of the open workspace: a [TerrainClient] over the
/// paired server's connection while it is online and offers `terrain`,
/// else none and the stage and route grid draw without terrain. Browser
/// only: answers are cached in IndexedDB (`animus-terrain`).
library;

import 'terrain_worlds.dart';
import 'dart:async';
import 'dart:js_interop';

import 'package:web/web.dart' as web;

import '../server/server_connection.dart';
import 'terrain_client.dart';

/// IndexedDB store of terrain answers, one record per place.
final class IndexedDbTerrainCache implements TerrainCache {
  static const String _database = 'animus-terrain';
  static const String _store = 'answers';
  static Future<web.IDBDatabase?>? _opening;

  static Future<JSAny?> _complete(web.IDBRequest request) {
    final Completer<JSAny?> completer = Completer<JSAny?>();
    request.onsuccess = ((web.Event _) => completer.complete(request.result)).toJS;
    request.onerror = ((web.Event _) => completer.completeError(StateError('IndexedDB: ${request.error?.message}'))).toJS;
    return completer.future;
  }

  static Future<web.IDBDatabase?> _open() => _opening ??= () async {
        try {
          final web.IDBOpenDBRequest request = web.window.indexedDB.open(_database, 1);
          request.onupgradeneeded = ((web.Event _) {
            final web.IDBDatabase database = request.result! as web.IDBDatabase;
            if (!database.objectStoreNames.contains(_store)) database.createObjectStore(_store);
          }).toJS;
          return (await _complete(request))! as web.IDBDatabase;
        } on Object {
          return null;
        }
      }();

  @override
  Future<String?> read(String key) async {
    final web.IDBDatabase? database = await _open();
    if (database == null) return null;
    final JSAny? value = await _complete(database.transaction(_store.toJS, 'readonly').objectStore(_store).get(key.toJS));
    return value != null && value.isA<JSString>() ? (value as JSString).toDart : null;
  }

  @override
  Future<void> write(String key, String text) async {
    final web.IDBDatabase? database = await _open();
    if (database == null) return;
    await _complete(database.transaction(_store.toJS, 'readwrite').objectStore(_store).put(text.toJS, key.toJS));
  }
}

abstract final class TerrainLink {
  static ServerConnection? _connection;
  static String? _server;
  static TerrainClient? _client;
  static bool _listening = false;
  static StreamSubscription<Object?>? _statuses;
  static final List<void Function()> _listeners = <void Function()>[];

  /// Follows [connection] as the open workspace's server.
  static void attach(String server, ServerConnection connection) {
    if (!_listening) {
      _listening = true;
      TerrainWorlds.listen((Set<String>? names) => _client?.worlds = names);
    }
    if (identical(_connection, connection)) return;
    detach();
    _connection = connection;
    _server = server;
    _statuses = connection.statuses.listen((Object? _) => _notify());
    _notify();
  }

  static void detach() {
    unawaited(_statuses?.cancel());
    _statuses = null;
    _connection = null;
    _server = null;
    _client = null;
    TerrainWorlds.clear();
    _notify();
  }

  /// The client while the server is online and reads terrain, else null.
  static TerrainClient? get client {
    final ServerConnection? connection = _connection;
    if (connection == null || !connection.status.online || !_offers(connection)) return null;
    return _client ??= TerrainClient(server: _server!, send: connection.request, cache: IndexedDbTerrainCache())..worlds = TerrainWorlds.names;
  }

  /// True while the open workspace belongs to a paired server.
  static bool get attached => _connection != null;

  /// Why there is no terrain, for the stage and grid notes; empty when it
  /// is there or when no server is paired (the views stay as they were).
  static String get absence {
    final ServerConnection? connection = _connection;
    if (connection == null) return '';
    if (!connection.status.online) return 'Terrain shows once the server is connected.';
    if (!_offers(connection)) return 'This server does not share terrain. Update Animus or pair again with the terrain scope.';
    return '';
  }

  static bool _offers(ServerConnection connection) {
    final Object? features = connection.capabilities?['features'];
    final Object? terrain = features is Map<String, Object?> ? features['terrain'] : null;
    final bool available = terrain is Map<String, Object?> && terrain['available'] == true;
    final List<String> scopes = connection.token?.scopes ?? const <String>[];
    return available && scopes.contains('terrain');
  }

  static void addListener(void Function() listener) => _listeners.add(listener);

  static void removeListener(void Function() listener) => _listeners.remove(listener);

  static void _notify() {
    for (final void Function() listener in List<void Function()>.of(_listeners)) {
      listener();
    }
  }
}
