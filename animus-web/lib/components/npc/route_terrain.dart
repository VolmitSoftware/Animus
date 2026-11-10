/// Terrain under the route grid: the surface tiles under the grid, drawn
/// as images under the lines, and what the editor knows there for snapping
/// placed points onto a standable block and flagging points and legs.
/// Tiles load lazily: the whole grid while it needs at most
/// [routeTerrainMaxTiles], else the tiles along the route (the NPC, every
/// point and the legs between them), at most [routeTerrainMaxTiles].
library;

import 'dart:async';
import 'dart:js_interop';

import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;
import 'package:web/web.dart' as web;

import '../../logic/gui/gui_json.dart';
import '../../logic/npc/route_model.dart';
import '../../logic/terrain/terrain_data.dart';
import '../../logic/terrain/terrain_path.dart';
import '../../logic/terrain/terrain_snap.dart';
import '../../logic/terrain/terrain_volume.dart';
import '../../mc/terrain/terrain_tile_image.dart';
import '../../services/terrain/terrain_client.dart';
import '../../services/terrain/terrain_link.dart';
import '../terrain/terrain_assets.dart';

/// The most tiles the grid loads.
const int routeTerrainMaxTiles = 64;

/// How far beside the route a wide grid loads terrain, blocks.
const int routeTerrainCorridor = 24;

final Map<String, String> _imageUrls = <String, String>{};

/// A PNG data URL of [tile], made once per tile hash.
String _imageOf(TerrainSurfaceTile tile, TerrainAssets assets) => _imageUrls.putIfAbsent(tile.hash, () {
      final web.HTMLCanvasElement canvas = web.HTMLCanvasElement()
        ..width = terrainTileSize
        ..height = terrainTileSize;
      final web.CanvasRenderingContext2D ctx = canvas.getContext('2d')! as web.CanvasRenderingContext2D;
      final web.ImageData image = ctx.createImageData(terrainTileSize.toJS, terrainTileSize);
      image.data.toDart.setAll(0, terrainTileRgba(tile, assets.colorOf));
      ctx.putImageData(image, 0, 0);
      return canvas.toDataURL('image/png');
    });

/// The route editor's terrain: surface tiles in view, plus the block window
/// of the 3D view when it is open.
final class RouteTerrain implements TerrainLookup {
  RouteTerrain(this._changed) {
    TerrainLink.addListener(_linkChanged);
  }

  final void Function() _changed;
  final Map<(int, int), TerrainSurfaceTile> _tiles = <(int, int), TerrainSurfaceTile>{};
  final Set<(int, int)> _asked = <(int, int)>{};
  String? _covered;
  TerrainAssets? _assets;
  String? _world;
  TerrainClient? _client = TerrainLink.client;

  /// Block states from the 3D view, which win over the surface.
  TerrainVolume? volume;

  /// True when the grid is too wide to fill and terrain loads along the
  /// route only.
  bool alongPath = false;

  /// True when even the route needs more than [routeTerrainMaxTiles].
  bool truncated = false;

  void dispose() => TerrainLink.removeListener(_linkChanged);

  void _linkChanged() {
    final TerrainClient? client = TerrainLink.client;
    if (!identical(client, _client)) {
      _client = client;
      _asked.clear();
      _covered = null;
    }
    _changed();
  }

  bool get available => TerrainLink.client != null && _world != null;

  /// Starts loading the tiles under [bounds] in [world], or along [path]
  /// when the grid is wider than the cap.
  void cover(String? world, RouteBounds bounds, TerrainPath path) {
    if (world != _world) {
      _world = world;
      _tiles.clear();
      _asked.clear();
      _covered = null;
      volume = null;
    }
    final TerrainClient? client = TerrainLink.client;
    if (client == null || world == null) return;
    final String key = '${bounds.minX},${bounds.minZ},${bounds.maxX},${bounds.maxZ}|${path.signature}';
    if (key == _covered) return;
    _covered = key;
    final ({List<(int, int)> tiles, bool alongPath, bool truncated}) grid =
        terrainGridTiles(path, bounds.minX, bounds.minZ, bounds.maxX, bounds.maxZ, radius: routeTerrainCorridor, maxTiles: routeTerrainMaxTiles);
    alongPath = grid.alongPath;
    truncated = grid.truncated;
    for (final (int tx, int tz) in grid.tiles) {
      if (!_asked.add((tx, tz))) continue;
      unawaited(() async {
        final TerrainAssets assets = _assets ??= await TerrainAssets.load();
        final TerrainSurfaceTile? tile = await client.surface(world, tx, tz);
        if (tile == null || world != _world) return;
        _imageOf(tile, assets);
        _tiles[(tx, tz)] = tile;
        _changed();
      }().catchError((Object _) {}));
    }
  }

  @override
  TerrainColumn? column(int x, int z) => _tiles[(terrainFloorDiv(x, terrainTileSize), terrainFloorDiv(z, terrainTileSize))]?.at(x, z);

  @override
  String? state(int x, int y, int z) => volume?.stateAt(x, y, z);

  /// The standable y for a point placed at [x], [z], near [nearY]; null
  /// without terrain there.
  int? snap(double x, double z, double nearY) => available ? terrainSnapY(this, x.floor(), z.floor(), nearY.round()) : null;

  /// [route] with node [index] of [key] at [x], [z] and, when the terrain
  /// there is known, on its standable block.
  JsonMap placed(JsonMap route, String key, int index, double x, double z) {
    final JsonMap moved = moveRouteNode(route, key, index, x, z);
    final RouteSpec spec = RouteSpec(route);
    final List<RouteEntry> entries = key == 'guides' ? spec.guides : spec.points;
    final RouteEntry? node = index < entries.length ? entries[index] : null;
    final int? y = node is RouteNode ? snap(x, z, node.y) : null;
    return y == null ? moved : setRouteNodeField(moved, key, index, 'y', y);
  }

  /// SVG images of the loaded tiles inside [bounds], one block per pixel.
  List<Component> images(RouteBounds bounds) {
    final TerrainAssets? assets = _assets;
    if (assets == null || !available) return const <Component>[];
    return <Component>[
      for (final TerrainSurfaceTile tile in _tiles.values)
        if (tile.minX < bounds.maxX && tile.minX + terrainTileSize > bounds.minX && tile.minZ < bounds.maxZ && tile.minZ + terrainTileSize > bounds.minZ)
          Component.element(
            tag: 'image',
            classes: 'animus-route-terrain',
            attributes: <String, String>{
              'href': _imageOf(tile, assets),
              'x': '${tile.minX}',
              'y': '${tile.minZ}',
              'width': '$terrainTileSize',
              'height': '$terrainTileSize',
              'preserveAspectRatio': 'none',
              'data-tile': '${tile.tx},${tile.tz}',
            },
          ),
    ];
  }

  /// Each placed point's problem, by its index in `points` or `guides`.
  Map<(String, int), TerrainIssue> issues(RouteSpec spec) {
    if (!available) return const <(String, int), TerrainIssue>{};
    final Map<(String, int), TerrainIssue> out = <(String, int), TerrainIssue>{};
    for (final (String key, List<RouteEntry> entries) in <(String, List<RouteEntry>)>[('points', spec.points), ('guides', spec.guides)]) {
      for (int index = 0; index < entries.length; index++) {
        if (entries[index] case final RouteNode node) {
          final TerrainIssue? issue = terrainCheckPoint(this, node.x.floor(), node.y.round(), node.z.floor());
          if (issue != null) out[(key, index)] = issue;
        }
      }
    }
    return out;
  }

  /// Links whose straight walk over the surface is too steep, with where.
  Map<(int, int), (int, int, int)> steepLinks(RouteSpec spec) {
    if (!available || spec.order == 'wander') return const <(int, int), (int, int, int)>{};
    return <(int, int), (int, int, int)>{
      for (final (int a, int b) in spec.links)
        if (terrainSteepStep(this, spec.positionOf(a)!.x.floor(), spec.positionOf(a)!.z.floor(), spec.positionOf(b)!.x.floor(), spec.positionOf(b)!.z.floor())
            case final (int, int, int) step)
          (a, b): step,
    };
  }

  /// The grid's line about its terrain, or null.
  String? note() {
    if (_world == null) return null;
    final String absence = TerrainLink.absence;
    if (absence.isNotEmpty) return absence;
    if (truncated) return 'Terrain shows along the start of the route and at every point: the whole route needs more than $routeTerrainMaxTiles tiles.';
    if (alongPath) return 'Terrain shows along the route: the grid is too wide to fill.';
    return null;
  }
}

Component _problem(String label, String text) => dom.li(
      classes: 'animus-gui-problem is-warning',
      attributes: const <String, String>{'data-terrain-issue': ''},
      <Component>[dom.strong(<Component>[Component.text(label)]), Component.text(' $text')],
    );

/// The flagged points and steep legs, listed under the grid.
Component routeTerrainProblems(Map<(String, int), TerrainIssue> issues, Map<(int, int), (int, int, int)> steep) =>
    dom.ul(classes: 'animus-route-problems', attributes: const <String, String>{'aria-label': 'Terrain problems'}, <Component>[
      for (final MapEntry<(String, int), TerrainIssue> issue in issues.entries)
        _problem('${issue.key.$1 == 'guides' ? 'Guide' : 'Point'} ${issue.key.$2}', issue.value.text),
      for (final MapEntry<(int, int), (int, int, int)> link in steep.entries)
        _problem(
          'Leg ${link.key.$1} to ${link.key.$2}',
          link.value.$3 > 0 ? 'climbs ${link.value.$3} blocks at ${link.value.$1}, ${link.value.$2}' : 'drops ${-link.value.$3} blocks at ${link.value.$1}, ${link.value.$2}',
        ),
    ]);

/// Where the 3D view centres: the picked point, the first point, the NPC.
(double, double, double) routeFocus(RouteSpec spec, (String, int)? picked, (double, double, double)? origin) {
  if (picked != null) {
    final List<RouteEntry> entries = picked.$1 == 'guides' ? spec.guides : spec.points;
    final RouteEntry? entry = picked.$2 < entries.length ? entries[picked.$2] : null;
    if (entry is RouteNode) return (entry.x, entry.y, entry.z);
  }
  final RouteNode? first = spec.placed.firstOrNull;
  if (first != null) return (first.x, first.y, first.z);
  return origin ?? (0, 64, 0);
}
