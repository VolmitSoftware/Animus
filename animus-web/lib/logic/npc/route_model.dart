/// Native waypoint routes (`modules["animus:navigation"]` inline, or a
/// `routes/<id>.json` document), read for drawing and edited as JSON. The
/// rules are those of docs/runtime.md "Initial movement and routes".
library;

import 'dart:math' as math;

import '../gui/gui_json.dart';

const String navigationModule = 'animus:navigation';

const List<String> routeOrders = <String>['sequence', 'pingpong', 'random', 'wander'];
const List<String> routeModes = <String>['walk', 'swim', 'fly'];

const int routeMaxPoints = 256;
const int routeMaxWanderCentres = 64;
const int routeMaxGuides = 1024;
const int routeMaxCoordinates = 1280;
const int routeMaxWait = 12000;
const double routeDefaultLinkDistance = 32;

/// Fields a route keeps, in the module or in its route document.
const List<String> routeFields = <String>['mode', 'speed', 'order', 'loop', 'waitTicks', 'points', 'guides', 'linkDistance', 'wander'];

/// One point or guide.
class RouteNode {
  const RouteNode(this.x, this.y, this.z, {this.waitTicks, this.guide = false});

  final double x;
  final double y;
  final double z;
  final int? waitTicks;
  final bool guide;

  static RouteNode? fromJson(Object? value, {bool guide = false}) {
    final JsonMap map = asMap(value);
    final double? x = asDouble(map['x']), y = asDouble(map['y']), z = asDouble(map['z']);
    if (x == null || y == null || z == null) return null;
    return RouteNode(x, y, z, waitTicks: asInt(map['waitTicks']), guide: guide);
  }

  double distanceTo(RouteNode other) {
    final double dx = x - other.x, dy = y - other.y, dz = z - other.z;
    return math.sqrt(dx * dx + dy * dy + dz * dz);
  }
}

/// A route as the runtime reads it.
class RouteSpec {
  RouteSpec(JsonMap json)
    : order = routeOrders.contains(json['order']) ? json['order']! as String : 'sequence',
      loop = json['loop'] == true,
      mode = routeModes.contains(json['mode']) ? json['mode']! as String : 'walk',
      waitTicks = asInt(json['waitTicks']) ?? 20,
      linkDistance = asDouble(json['linkDistance']) ?? routeDefaultLinkDistance,
      wanderRadius = asInt(asMap(json['wander'])['radius']) ?? 25,
      wanderHeight = asInt(asMap(json['wander'])['height']) ?? 3,
      points = <RouteNode>[for (final Object? point in asList(json['points'])) ?RouteNode.fromJson(point)],
      guides = <RouteNode>[for (final Object? guide in asList(json['guides'])) ?RouteNode.fromJson(guide, guide: true)];

  final String order;
  final bool loop;
  final String mode;
  final int waitTicks;
  final double linkDistance;
  final int wanderRadius;
  final int wanderHeight;
  final List<RouteNode> points;
  final List<RouteNode> guides;

  /// Points then guides, the numbering the `waypoint` trigger reports.
  List<RouteNode> get nodes => <RouteNode>[...points, ...guides];

  /// Links to draw between [nodes] indices: the walking order for sequence
  /// and pingpong (closing the loop when `loop`), every pair within
  /// `linkDistance` for random, nothing for wander.
  List<(int, int)> get links {
    switch (order) {
      case 'sequence':
        return <(int, int)>[for (int index = 1; index < points.length; index++) (index - 1, index), if (loop && points.length > 2) (points.length - 1, 0)];
      case 'pingpong':
        return <(int, int)>[for (int index = 1; index < points.length; index++) (index - 1, index)];
      case 'random':
        final List<RouteNode> all = nodes;
        return <(int, int)>[
          for (int a = 0; a < all.length; a++)
            for (int b = a + 1; b < all.length; b++)
              if (all[a].distanceTo(all[b]) <= linkDistance &&
                  (all[a].x - all[b].x).abs() <= 32 &&
                  (all[a].z - all[b].z).abs() <= 32 &&
                  (all[a].y - all[b].y).abs() <= 8)
                (a, b),
        ];
      default:
        return const <(int, int)>[];
    }
  }

  /// Problems the runtime refuses, worded like its field errors, by field.
  List<(String, String)> problems() {
    final List<(String, String)> found = <(String, String)>[];
    final int pointLimit = order == 'wander' ? routeMaxWanderCentres : routeMaxPoints;
    if (points.length > pointLimit) found.add(('points', 'at most $pointLimit points for order $order'));
    if (order != 'wander' && points.isEmpty) found.add(('points', 'needs at least one point'));
    if (guides.isNotEmpty && order != 'random') found.add(('guides', 'guides are for order random only'));
    if (guides.length > routeMaxGuides) found.add(('guides', 'at most $routeMaxGuides guides'));
    if (points.length + guides.length > routeMaxCoordinates) found.add(('points', 'points and guides together allow at most $routeMaxCoordinates'));
    if (mode == 'walk') {
      for (int index = 0; index < nodes.length; index++) {
        final RouteNode node = nodes[index];
        if (node.y != node.y.roundToDouble()) {
          found.add((node.guide ? 'guides[${index - points.length}].y' : 'points[$index].y', 'walking routes need whole-block heights'));
        }
      }
    }
    return found;
  }
}

/// Bounds of what the grid shows, in blocks: x and z only.
class RouteBounds {
  const RouteBounds(this.minX, this.minZ, this.maxX, this.maxZ);

  final double minX;
  final double minZ;
  final double maxX;
  final double maxZ;

  double get width => maxX - minX;
  double get depth => maxZ - minZ;

  /// A square at least [minimum] blocks across around the nodes and the
  /// extra points (the NPC), padded by [pad] blocks.
  static RouteBounds around(Iterable<RouteNode> nodes, {List<(double, double)> extra = const <(double, double)>[], double pad = 3, double minimum = 16}) {
    double minX = double.infinity, minZ = double.infinity, maxX = -double.infinity, maxZ = -double.infinity;
    void take(double x, double z) {
      minX = math.min(minX, x);
      maxX = math.max(maxX, x);
      minZ = math.min(minZ, z);
      maxZ = math.max(maxZ, z);
    }

    for (final RouteNode node in nodes) {
      take(node.x, node.z);
    }
    for (final (double x, double z) in extra) {
      take(x, z);
    }
    if (minX == double.infinity) {
      minX = minZ = 0;
      maxX = maxZ = 0;
    }
    final double size = math.max(minimum, math.max(maxX - minX, maxZ - minZ) + pad * 2);
    final double centreX = (minX + maxX) / 2, centreZ = (minZ + maxZ) / 2;
    final double left = (centreX - size / 2).floorToDouble(), top = (centreZ - size / 2).floorToDouble();
    return RouteBounds(left, top, left + size.ceilToDouble(), top + size.ceilToDouble());
  }

  /// Grid line spacing that keeps about 8 to 32 lines across.
  double get step {
    final double span = math.max(width, depth);
    for (final double candidate in <double>[1, 2, 4, 8, 16, 32, 64, 128]) {
      if (span / candidate <= 32) return candidate;
    }
    return 256;
  }
}

JsonMap _point(double x, double y, double z) => <String, Object?>{'x': _clean(x), 'y': _clean(y), 'z': _clean(z)};

num _clean(double value) => value == value.roundToDouble() ? value.toInt() : value;

/// The route with a point (or a guide) appended.
JsonMap addRouteNode(JsonMap route, double x, double y, double z, {bool guide = false}) {
  final String key = guide ? 'guides' : 'points';
  return <String, Object?>{
    ...route,
    key: <Object?>[...asList(route[key]), _point(x, y, z)],
  };
}

/// The route with node [index] of [key] moved to x and z, keeping its other fields.
JsonMap moveRouteNode(JsonMap route, String key, int index, double x, double z) {
  final List<Object?> list = List<Object?>.of(asList(route[key]));
  if (index < 0 || index >= list.length) return route;
  list[index] = <String, Object?>{...asMap(list[index]), 'x': _clean(x), 'z': _clean(z)};
  return <String, Object?>{...route, key: list};
}

/// The route without node [index] of [key]; an emptied list is removed.
JsonMap removeRouteNode(JsonMap route, String key, int index) {
  final List<Object?> list = List<Object?>.of(asList(route[key]));
  if (index < 0 || index >= list.length) return route;
  list.removeAt(index);
  final JsonMap next = <String, Object?>{...route};
  if (list.isEmpty && key == 'guides') {
    next.remove(key);
  } else {
    next[key] = list;
  }
  return next;
}

/// The route with point [from] moved to position [to] in the walking order.
JsonMap reorderRoutePoint(JsonMap route, int from, int to) {
  final List<Object?> list = asList(route['points']);
  if (from < 0 || from >= list.length || to < 0 || to >= list.length) return route;
  return <String, Object?>{...route, 'points': moveItem(list, from, to)};
}

/// The route with [order] set and the fields that order refuses removed
/// (loop is sequence only; guides and linkDistance random only; wander
/// wander only).
JsonMap setRouteOrder(JsonMap route, String order) {
  final JsonMap next = <String, Object?>{...route, 'order': order};
  if (order != 'sequence') next.remove('loop');
  if (order != 'random') {
    next.remove('guides');
    next.remove('linkDistance');
  }
  if (order != 'wander') next.remove('wander');
  return next;
}

/// Snaps a grid position to a whole block.
double snapBlock(double value) => value.roundToDouble();
