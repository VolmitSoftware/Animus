/// Terrain for one stage: whether it shows, its radius, the window loaded
/// around a place and the line under the stage that says what is missing.
/// Without a paired server that shares terrain, the stage keeps its grass
/// patch.
library;

import 'dart:async';
import 'dart:js_interop';

import 'package:web/web.dart' as web;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/jaspr.dart' show Component;

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
  String? _place;
  (String, double, double, double)? _at;
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
      final (String, double, double, double)? at = _at;
      if (at != null) show(at.$1, at.$2, at.$3, at.$4);
    }
    _changed();
  }

  /// Loads the window around [world] [x], [y], [z] when it is not loaded yet.
  void show(String? world, double x, double y, double z) {
    _at = world == null ? null : (world, x, y, z);
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
    final String place = '$world|$x|$y|$z|$radius';
    if (place == _place) return;
    _place = place;
    loading = true;
    unawaited(_window.load(client, world, x, y, z, radius: radius, origin: McVec3(x, y, z), keyPrefix: keyPrefix).then((TerrainWindowResult? loaded) {
      if (loaded == null) return;
      result = loaded;
      loading = false;
      _changed();
    }, onError: (Object error) {
      web.console.warn('terrain: $error'.toJS);
      loading = false;
      _place = null;
      _changed();
    }));
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
    final TerrainWindowResult? shown = result;
    if (loading && shown == null) return 'Loading terrain.';
    if (shown == null) return null;
    final List<String> parts = <String>[
      if (shown.unloaded > 0) '${shown.unloaded} of ${shown.chunks} chunks are not loaded on the server and show empty',
      if (shown.forbidden > 0) '${shown.forbidden} of ${shown.chunks} chunks are outside the terrain radius of this token',
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
          final (String, double, double, double)? at = _at;
          if (at != null) show(at.$1, at.$2, at.$3, at.$4);
          _changed();
        },
        onRadius: (int value) {
          radius = value;
          final (String, double, double, double)? at = _at;
          if (at != null) show(at.$1, at.$2, at.$3, at.$4);
          _changed();
        },
      );
}
