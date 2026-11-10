/// Fetches surface tiles and block slices from the paired server, lazily
/// and a few at a time. Every answer is kept in a [TerrainCache] under its
/// place; the cached copy's hash goes out as `If-None-Match`, so an
/// unchanged tile comes back as a bodyless 304 and the cached copy is used.
/// A place is asked for once per session; later calls answer from memory.
library;

import 'dart:async';
import 'dart:collection';
import 'dart:convert';

import '../../logic/terrain/terrain_data.dart';
import '../server/transport.dart';

/// Where answers are kept between sessions: IndexedDB in the browser.
abstract interface class TerrainCache {
  Future<String?> read(String key);

  Future<void> write(String key, String text);
}

/// A cache that lasts as long as the tab.
final class MemoryTerrainCache implements TerrainCache {
  final Map<String, String> entries = <String, String>{};

  @override
  Future<String?> read(String key) async => entries[key];

  @override
  Future<void> write(String key, String text) async => entries[key] = text;
}

/// Where a spawned NPC stands now.
typedef TerrainLivePosition = ({String world, double x, double y, double z});

/// Sends one authorized request; throws when the connection cannot.
typedef TerrainSend = Future<ApiResponse> Function(ApiRequest request);

final class TerrainClient {
  TerrainClient({required this.server, required this.send, required this.cache, this.parallel = 4});

  /// Keys cache entries, so two servers never share a tile.
  final String server;
  final TerrainSend send;
  final TerrainCache cache;
  final int parallel;

  final Map<String, Future<Map<String, Object?>?>> _asked = <String, Future<Map<String, Object?>?>>{};
  final Queue<void Function()> _waiting = Queue<void Function()>();
  int _running = 0;

  /// Requests sent, answered 200 and answered 304, for the browser checks.
  int sent = 0;
  int fresh = 0;
  int unchanged = 0;

  /// Worlds the server lists; null until it has said. A world outside the
  /// list, or one the server answered 404 for, is never asked about.
  Set<String>? worlds;
  final Set<String> _missing = <String>{};

  bool _asks(String world) => !_missing.contains(world) && (worlds == null || worlds!.contains(world));

  /// The surface tile [tx], [tz] of [world], or null when neither the
  /// server nor the cache has it.
  Future<TerrainSurfaceTile?> surface(String world, int tx, int tz) async {
    if (!_asks(world)) return null;
    final Map<String, Object?>? json = await _get('surface|$world|$tx|$tz',
        '/v1/terrain/surface?world=${Uri.encodeQueryComponent(world)}&tx=$tx&tz=$tz');
    return json == null ? null : TerrainSurfaceTile.fromJson(json);
  }

  /// The slice of chunk [cx], [cz] from [y] up [height] blocks.
  Future<TerrainSlice?> slice(String world, int cx, int cz, int y, int height) async {
    if (!_asks(world)) return null;
    final Map<String, Object?>? json = await _get('blocks|$world|$cx|$cz|$y|$height',
        '/v1/terrain/blocks?world=${Uri.encodeQueryComponent(world)}&cx=$cx&cz=$cz&y=$y&height=$height');
    return json == null ? null : TerrainSlice.fromJson(json);
  }

  /// Where the spawned NPC [npc] (its id, such as town/guard) stands now:
  /// its world name and position, or null when it is not active or the
  /// server cannot say. Never cached; the server reads live positions at
  /// most once a second.
  Future<TerrainLivePosition?> position(String npc) async {
    final ApiResponse response = await send(ApiRequest('GET', '/v1/terrain/position?npc=${Uri.encodeQueryComponent(npc)}'));
    final Object? body = response.body;
    if (!response.ok || body is! Map<String, Object?> || body['active'] != true) return null;
    final Object? world = body['world'], x = body['x'], y = body['y'], z = body['z'];
    if (world is! String || x is! num || y is! num || z is! num) return null;
    return (world: world, x: x.toDouble(), y: y.toDouble(), z: z.toDouble());
  }

  Future<Map<String, Object?>?> _get(String place, String path) => _asked.putIfAbsent(place, () => _slot(() => _fetch('$server|$place', path, place.split('|')[1])));

  Future<T> _slot<T>(Future<T> Function() work) {
    final Completer<T> done = Completer<T>();
    void start() {
      _running++;
      work().then(done.complete, onError: done.completeError).whenComplete(() {
        _running--;
        if (_waiting.isNotEmpty) _waiting.removeFirst()();
      });
    }

    if (_running < parallel) {
      start();
    } else {
      _waiting.add(start);
    }
    return done.future;
  }

  Future<Map<String, Object?>?> _fetch(String key, String path, String world) async {
    if (!_asks(world)) return null;
    Map<String, Object?>? cached;
    try {
      final String? text = await cache.read(key);
      final Object? decoded = text == null ? null : jsonDecode(text);
      cached = decoded is Map<String, Object?> ? decoded : null;
    } on Object {
      cached = null;
    }
    final Object? hash = cached?['hash'];
    try {
      sent++;
      final ApiResponse response = await send(ApiRequest('GET', path, headers: <String, String>{if (hash is String) 'If-None-Match': '"$hash"'}));
      if (response.status == 304 && cached != null) {
        unchanged++;
        return cached;
      }
      if (response.status == 404) _missing.add(world);
      final Object? body = response.body;
      if (!response.ok || body is! Map<String, Object?>) return cached;
      fresh++;
      unawaited(cache.write(key, jsonEncode(body)).catchError((Object _) {}));
      return body;
    } on Object {
      // Offline or refused: the copy from an earlier session, if any.
      _asked.remove(key.substring(server.length + 1));
      return cached;
    }
  }
}
