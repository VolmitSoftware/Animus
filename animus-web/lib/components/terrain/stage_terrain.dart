/// Terrain for one stage: whether it shows, its radius, the blocks loaded
/// along a path (a place by default) and what the stage says about it: one
/// short line under the canvas and the details for its tip. Only without a
/// paired server does the stage keep its grass patch silently. A failed
/// load is remembered for its place and not retried until [retry], a new
/// client or another place.
library;

import 'dart:async';
import 'dart:js_interop';
import 'dart:math' as math;

import 'package:web/web.dart' as web;

import '../../logic/terrain/terrain_access.dart';
import '../../logic/terrain/terrain_path.dart';
import '../../mc/scene/mc_camera.dart';
import '../../mc/scene/mc_math.dart';
import '../../mc/scene/mc_scene.dart';
import '../../services/server/connection_status.dart';
import '../../services/terrain/terrain_client.dart';
import '../../services/terrain/terrain_link.dart';
import '../mc/mc_asset_loader.dart';
import '../shell_topbar.dart' show serverState;
import 'terrain_window.dart';

final class StageTerrain {
  StageTerrain(this._changed, {this.radius = 16, this.keyPrefix = 'terrain'}) {
    TerrainLink.addListener(_linkChanged);
    McAssetLoader.addListener(_packChanged);
  }

  final void Function() _changed;
  final String keyPrefix;
  final TerrainWindow _window = TerrainWindow();
  bool on = true;
  int radius;
  TerrainWindowResult? result;
  bool loading = false;
  String? _failure;
  bool _failedWithoutPack = false;
  String? _place;
  String? _world;
  (String, McVec3, TerrainPath?)? _at;
  TerrainClient? _client = TerrainLink.client;

  void dispose() {
    TerrainLink.removeListener(_linkChanged);
    McAssetLoader.removeListener(_packChanged);
    _window.cancel();
  }

  /// The model pack arrived after a failed load (the stage's Retry): try
  /// the terrain again.
  void _packChanged() {
    if (_failedWithoutPack && McAssetLoader.loaded != null) retry();
  }

  /// True when the stage can offer the Terrain switch: a paired server
  /// whose terrain this pairing may read.
  bool get available => TerrainLink.client != null;

  /// A new or lost client reloads the window; other status changes only
  /// redraw the line.
  void _linkChanged() {
    final TerrainClient? client = TerrainLink.client;
    if (!identical(client, _client)) {
      _client = client;
      _place = null;
      _failure = null;
      _again();
    }
    _changed();
  }

  void _again() {
    final (String, McVec3, TerrainPath?)? at = _at;
    if (at != null) show(at.$1, at.$2, path: at.$3);
  }

  /// Turns the terrain on or off.
  void toggle(bool value) {
    on = value;
    _place = null;
    _again();
    _changed();
  }

  /// Loads the terrain within [value] blocks.
  void setRadius(int value) {
    if (value == radius) return;
    radius = value;
    _again();
    _changed();
  }

  /// Forgets a failed load and tries its place again.
  void retry() {
    _failure = null;
    _failedWithoutPack = false;
    _place = null;
    _again();
    _changed();
  }

  /// Loads the blocks along [path] in [world] (the place [origin] when
  /// null) when they are not loaded yet; meshes are relative to [origin].
  void show(String? world, McVec3 origin, {TerrainPath? path}) {
    _at = world == null ? null : (world, origin, path);
    _world = world;
    final TerrainClient? client = TerrainLink.client;
    if (!on || client == null || world == null) {
      _window.cancel();
      if (result != null || loading) {
        result = null;
        loading = false;
        _place = null;
        _changed();
      }
      return;
    }
    final TerrainPath along = path ?? TerrainPath.point(origin.x, origin.y, origin.z);
    final String place = '$world|${origin.x}|${origin.y}|${origin.z}|${along.signature}|$radius';
    // The same place again, loaded, loading or failed: nothing to do. A
    // failure stays until Retry, so a missing pack is asked for once.
    if (place == _place) return;
    _place = place;
    loading = true;
    _failure = null;
    unawaited(_window
        .load(client, world, terrainPathPlan(along, radius: radius), origin: origin, keyPrefix: keyPrefix, onPart: (TerrainWindowResult part) {
      if (place != _place) return;
      result = part;
      _changed();
    }).then((TerrainWindowResult? loaded) {
      if (loaded == null || place != _place) return;
      result = loaded;
      loading = false;
      _changed();
    }, onError: (Object error) {
      if (place != _place) return;
      web.console.warn('terrain: $error'.toJS);
      _failure = '$error';
      _failedWithoutPack = McAssetLoader.failed;
      loading = false;
      _changed();
    }));
  }

  /// What the stage says about its terrain: [near] names what to stand
  /// near ("this NPC"). Null with the terrain off or nothing to say.
  TerrainLine? line({String near = 'this NPC'}) {
    // Without the model pack the stage says so itself.
    if (!on || McAssetLoader.failed) return null;
    final TerrainWindowResult? shown = result;
    final String? world = _world;
    final ConnectionStatus? status = TerrainLink.status;
    return terrainLine(
      TerrainFacts(
        absence: TerrainLink.absence,
        connection: status == null || status.online ? null : serverState(status).$1,
        failure: _failure,
        loading: loading && shown == null,
        worldUnloaded: world != null && (TerrainLink.client?.worldUnloaded(world) ?? false),
        chunks: shown?.chunks ?? 0,
        unloaded: shown?.unloaded ?? 0,
        forbidden: shown?.forbidden ?? 0,
        truncated: shown?.truncated ?? false,
        fullStates: shown?.fullStates ?? true,
      ),
      near: near,
    );
  }

  /// Where the stage camera may pan: the loaded chunks, relative to the
  /// stage origin, with room around them; null without terrain.
  McBounds? get reach {
    final TerrainWindowResult? shown = on ? result : null;
    final (String, McVec3, TerrainPath?)? at = _at;
    if (shown == null || at == null || shown.volume.chunks.isEmpty) return null;
    double minX = 0, minZ = 0, maxX = 0, maxZ = 0;
    for (final (int cx, int cz) in shown.volume.chunks) {
      minX = math.min(minX, cx * 16 - at.$2.x);
      minZ = math.min(minZ, cz * 16 - at.$2.z);
      maxX = math.max(maxX, cx * 16 + 16 - at.$2.x);
      maxZ = math.max(maxZ, cz * 16 + 16 - at.$2.z);
    }
    return McBounds(McVec3(math.min(minX, -mcCameraPivotRange), -mcCameraPivotRange * 2, math.min(minZ, -mcCameraPivotRange)),
        McVec3(math.max(maxX, mcCameraPivotRange), mcCameraPivotRange * 2, math.max(maxZ, mcCameraPivotRange)));
  }

  /// [scene] over the loaded terrain, without the grass patch.
  McScene apply(McScene scene) {
    final TerrainWindowResult? shown = on ? result : null;
    if (shown == null) return scene;
    return McScene(<McSceneNode>[...shown.nodes, ...scene.nodes], frame: scene.frame, ground: false);
  }
}
