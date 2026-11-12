/// Terrain for one stage: whether it shows, its radius, the blocks loaded
/// along a path (a place by default) and the line under the stage that
/// says what is missing, drawn on the stage.
/// Only without a paired server does the stage keep its grass patch
/// silently; with one, the note says why the blocks are not there.
library;

import 'dart:async';
import 'dart:js_interop';
import 'dart:math' as math;

import 'package:web/web.dart' as web;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/terrain/terrain_path.dart';
import '../../mc/scene/mc_camera.dart';
import '../../mc/scene/mc_math.dart';
import '../../mc/scene/mc_scene.dart';
import '../../services/terrain/terrain_client.dart';
import '../../services/terrain/terrain_link.dart';
import 'terrain_controls.dart';
import 'terrain_window.dart';

final class StageTerrain {
  StageTerrain(this._changed, {this.radius = 16, this.keyPrefix = 'terrain'}) {
    TerrainLink.addListener(_linkChanged);
  }

  final void Function() _changed;
  final String keyPrefix;
  final TerrainWindow _window = TerrainWindow();
  bool on = true;
  int radius;
  TerrainWindowResult? result;
  bool loading = false;
  String? _failure;
  String? _place;
  (String, McVec3, TerrainPath?)? _at;
  TerrainClient? _client = TerrainLink.client;

  void dispose() {
    TerrainLink.removeListener(_linkChanged);
    _window.cancel();
  }

  /// A new or lost client reloads the window; other status changes only
  /// redraw the note.
  void _linkChanged() {
    final TerrainClient? client = TerrainLink.client;
    if (!identical(client, _client)) {
      _client = client;
      _place = null;
      _again();
    }
    _changed();
  }

  void _again() {
    final (String, McVec3, TerrainPath?)? at = _at;
    if (at != null) show(at.$1, at.$2, path: at.$3);
  }

  /// Loads the blocks along [path] in [world] (the place [origin] when
  /// null) when they are not loaded yet; meshes are relative to [origin].
  void show(String? world, McVec3 origin, {TerrainPath? path}) {
    _at = world == null ? null : (world, origin, path);
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
    if (place == _place) return;
    _place = place;
    loading = true;
    _failure = null;
    unawaited(_window.load(client, world, terrainPathPlan(along, radius: radius), origin: origin, keyPrefix: keyPrefix).then((TerrainWindowResult? loaded) {
      if (loaded == null) return;
      result = loaded;
      loading = false;
      _changed();
    }, onError: (Object error) {
      web.console.warn('terrain: $error'.toJS);
      _failure = 'Terrain failed to load: $error';
      loading = false;
      _place = null;
      _changed();
    }));
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

  /// What the stage says about its terrain, or null.
  String? get note {
    if (!on) return null;
    final String absence = TerrainLink.absence;
    if (absence.isNotEmpty) return absence;
    if (_failure != null) return _failure;
    final TerrainWindowResult? shown = result;
    if (loading && shown == null) return 'Loading terrain.';
    if (shown == null) return null;
    final List<String> parts = <String>[
      if (shown.unloaded > 0) '${shown.unloaded} of ${shown.chunks} chunks are not loaded on the server, as no player is near them, and show empty',
      if (shown.forbidden > 0) '${shown.forbidden} of ${shown.chunks} chunks are outside the terrain radius of this token',
      if (shown.truncated) 'the path is longer than one view loads, so terrain shows along its first ${shown.chunks} chunks',
      if (!shown.fullStates) 'this site\'s model pack has no blockstates.json, so stairs, slabs and fences show their default shape',
    ];
    if (parts.isEmpty) return null;
    final String text = parts.join('; ');
    return '${text[0].toUpperCase()}${text.substring(1)}.';
  }

  /// The toggle and radius; nothing for a workspace with no server.
  Widget controls(String id) => !TerrainLink.attached ? const Component.fragment(<Component>[]) : TerrainControls(
        id: id,
        on: on,
        radius: radius,
        disabled: TerrainLink.client == null,
        onToggle: (bool value) {
          on = value;
          _place = null;
          _again();
          _changed();
        },
        onRadius: (int value) {
          radius = value;
          _again();
          _changed();
        },
      );
}
