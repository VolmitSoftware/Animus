/// Native waypoint routes (`modules["animus:navigation"]` inline, or a
/// `routes/<id>.json` document), read for drawing and edited as JSON. The
/// rules are those of wiki animus/04-npc-definitions "Initial movement and routes".
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
const List<String> routeFields = <String>['mode', 'speed', 'order', 'loop', 'waitTicks', 'points', 'guides', 'linkDistance', 'wander', 'seed', 'startOffset', 'jitter'];

/// A wait in ticks: a fixed count, or `[min, max]` drawn per arrival.
class RouteWait {
  const RouteWait(this.min, this.max);

  final int min;
  final int max;

  /// An integer or a two-integer list; anything else is null.
  static RouteWait? fromJson(Object? value) {
    final int? fixed = asInt(value);
    if (fixed != null) return RouteWait(fixed, fixed);
    final List<Object?> range = asList(value);
    final int? low = range.length == 2 ? asInt(range[0]) : null, high = range.length == 2 ? asInt(range[1]) : null;
    return low == null || high == null ? null : RouteWait(low, high);
  }

  bool get ranged => min != max;

  @override
  String toString() => ranged ? '$min-$max' : '$min';

  @override
  bool operator ==(Object other) => other is RouteWait && other.min == min && other.max == max;

  @override
  int get hashCode => Object.hash(min, max);
}

/// One entry of `points` (a position or a recording step) or of `guides`.
sealed class RouteEntry {
  const RouteEntry({this.waitTicks, this.guide = false});

  /// The point's own wait, or null for the route's.
  final RouteWait? waitTicks;
  final bool guide;

  /// A step when it names a `recording` (never for a guide), a position when
  /// it has x, y and z, else [RouteOther], so every entry keeps its number.
  static RouteEntry fromJson(Object? value, {bool guide = false}) {
    final JsonMap map = asMap(value);
    if (!guide && map.containsKey('recording')) return RouteStep.fromJson(map);
    return RouteNode.fromJson(map, guide: guide) ?? RouteOther(guide: guide, waitTicks: RouteWait.fromJson(map['waitTicks']));
  }
}

/// An entry that is neither a position nor a step, such as a point missing
/// its y. It keeps its number and is not drawn.
class RouteOther extends RouteEntry {
  const RouteOther({super.guide, super.waitTicks});
}

/// A point or guide at a position.
class RouteNode extends RouteEntry {
  const RouteNode(this.x, this.y, this.z, {super.waitTicks, super.guide});

  final double x;
  final double y;
  final double z;

  static RouteNode? fromJson(Object? value, {bool guide = false}) {
    final JsonMap map = asMap(value);
    final double? x = asDouble(map['x']), y = asDouble(map['y']), z = asDouble(map['z']);
    if (x == null || y == null || z == null) return null;
    return RouteNode(x, y, z, waitTicks: RouteWait.fromJson(map['waitTicks']), guide: guide);
  }

  double distanceTo(RouteNode other) {
    final double dx = x - other.x, dy = y - other.y, dz = z - other.z;
    return math.sqrt(dx * dx + dy * dy + dz * dz);
  }
}

/// A point that plays ticks [fromTick] to [toTick] of `recordings/<recording>.json`
/// once; it has no position of its own and starts at the segment's first frame.
class RouteStep extends RouteEntry {
  const RouteStep(this.recording, {this.fromTick = 0, this.toTick, super.waitTicks});

  final String recording;
  final int fromTick;

  /// Null for the recording's durationTicks.
  final int? toTick;

  static RouteStep fromJson(JsonMap map) => RouteStep(
        asString(map['recording']) ?? '',
        fromTick: asInt(map['fromTick']) ?? 0,
        toTick: asInt(map['toTick']),
        waitTicks: RouteWait.fromJson(map['waitTicks']),
      );
}

/// Recording ids as the runtime accepts them.
final RegExp routeRecordingId = RegExp(r'^[a-z0-9][a-z0-9_-]{0,63}$');

/// A route as the runtime reads it.
class RouteSpec {
  RouteSpec(JsonMap json)
    : order = routeOrders.contains(json['order']) ? json['order']! as String : 'sequence',
      loop = json['loop'] == true,
      mode = routeModes.contains(json['mode']) ? json['mode']! as String : 'walk',
      waitTicks = RouteWait.fromJson(json['waitTicks']) ?? const RouteWait(20, 20),
      linkDistance = asDouble(json['linkDistance']) ?? routeDefaultLinkDistance,
      wanderRadius = asInt(asMap(json['wander'])['radius']) ?? 25,
      wanderHeight = asInt(asMap(json['wander'])['height']) ?? 3,
      points = <RouteEntry>[for (final Object? point in asList(json['points'])) RouteEntry.fromJson(point)],
      guides = <RouteEntry>[for (final Object? guide in asList(json['guides'])) RouteEntry.fromJson(guide, guide: true)];

  final String order;
  final bool loop;
  final String mode;
  final RouteWait waitTicks;
  final double linkDistance;
  final int wanderRadius;
  final int wanderHeight;

  /// Positions and recording steps, in walking order.
  final List<RouteEntry> points;

  /// Guides; each is a [RouteNode] unless it is missing a coordinate.
  final List<RouteEntry> guides;

  /// Points then guides, the numbering the `waypoint` trigger reports; a
  /// recording step is numbered like any point.
  List<RouteEntry> get nodes => <RouteEntry>[...points, ...guides];

  /// The node numbered [number] when it has a position, else null.
  RouteNode? positionOf(int number) {
    final List<RouteEntry> all = nodes;
    if (number < 0 || number >= all.length) return null;
    final RouteEntry entry = all[number];
    return entry is RouteNode ? entry : null;
  }

  /// Every node with a position, for bounds and drawing.
  List<RouteNode> get placed => <RouteNode>[for (final RouteEntry entry in nodes) if (entry is RouteNode) entry];

  /// Links to draw between [nodes] numbers: the walking order for sequence
  /// and pingpong (closing the loop when `loop`), every pair within
  /// `linkDistance` for random, nothing for wander. A recording step has no
  /// position, so no link is drawn to or from it.
  List<(int, int)> get links {
    final List<RouteEntry> all = nodes;
    bool placedPair((int, int) pair) => all[pair.$1] is RouteNode && all[pair.$2] is RouteNode;
    switch (order) {
      case 'sequence':
        return <(int, int)>[
          for (int index = 1; index < points.length; index++) (index - 1, index),
          if (loop && points.length > 2) (points.length - 1, 0),
        ].where(placedPair).toList();
      case 'pingpong':
        return <(int, int)>[for (int index = 1; index < points.length; index++) (index - 1, index)].where(placedPair).toList();
      case 'random':
        final List<(int, int)> found = <(int, int)>[];
        for (int a = 0; a < all.length; a++) {
          final RouteEntry first = all[a];
          if (first is! RouteNode) continue;
          for (int b = a + 1; b < all.length; b++) {
            final RouteEntry second = all[b];
            if (second is! RouteNode) continue;
            if (first.distanceTo(second) <= linkDistance && (first.x - second.x).abs() <= 32 && (first.z - second.z).abs() <= 32 && (first.y - second.y).abs() <= 8) {
              found.add((a, b));
            }
          }
        }
        return found;
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
    for (int index = 0; index < points.length; index++) {
      final RouteEntry point = points[index];
      if (point is! RouteStep) continue;
      if (order == 'wander') found.add(('points[$index].recording', 'a recording step is not allowed with order wander'));
      if (!routeRecordingId.hasMatch(point.recording)) found.add(('points[$index].recording', 'expected 1-64 lowercase letters, digits, _ or -'));
      final int? toTick = point.toTick;
      if (toTick != null && point.fromTick >= toTick) found.add(('points[$index].fromTick', 'must be below toTick'));
    }
    if (mode == 'walk') {
      final List<RouteEntry> all = nodes;
      for (int index = 0; index < all.length; index++) {
        final RouteEntry node = all[index];
        if (node is RouteNode && node.y != node.y.roundToDouble()) {
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

/// The route with node [index] of [key] moved to x and z, keeping its other
/// fields. A recording step has no position and is left alone.
JsonMap moveRouteNode(JsonMap route, String key, int index, double x, double z) {
  final List<Object?> list = List<Object?>.of(asList(route[key]));
  if (index < 0 || index >= list.length || (key == 'points' && asMap(list[index]).containsKey('recording'))) return route;
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

/// The route with a recording step appended.
JsonMap addRouteStep(JsonMap route, String recording) => <String, Object?>{
      ...route,
      'points': <Object?>[...asList(route['points']), <String, Object?>{'recording': recording}],
    };

/// The route with [field] of node [index] of [key] set, or removed when
/// [value] is null; every other key keeps its place.
JsonMap setRouteNodeField(JsonMap route, String key, int index, String field, Object? value) {
  final List<Object?> list = List<Object?>.of(asList(route[key]));
  if (index < 0 || index >= list.length) return route;
  final JsonMap next = <String, Object?>{...asMap(list[index])};
  if (value == null) {
    next.remove(field);
  } else {
    next[field] = value;
  }
  list[index] = next;
  return <String, Object?>{...route, key: list};
}

const List<String> _positionFields = <String>['x', 'y', 'z', 'jitter'];
const List<String> _stepFields = <String>['recording', 'fromTick', 'toTick', 'playback'];

/// The route with point [index] made a step playing [recording]: x, y, z and
/// jitter go, `recording` takes the place of the first coordinate, and the
/// wait, weight, next and any other key stay where they were.
JsonMap routePointToStep(JsonMap route, int index, String recording) =>
    _convertPoint(route, index, _positionFields, <String, Object?>{'recording': recording});

/// The route with step [index] made a position at x, y and z: the recording
/// fields go and the coordinates take the place of `recording`.
JsonMap routeStepToPosition(JsonMap route, int index, double x, double y, double z) =>
    _convertPoint(route, index, _stepFields, _point(x, y, z));

JsonMap _convertPoint(JsonMap route, int index, List<String> drop, JsonMap insert) {
  final List<Object?> list = List<Object?>.of(asList(route['points']));
  if (index < 0 || index >= list.length) return route;
  final JsonMap next = <String, Object?>{};
  bool placed = false;
  for (final MapEntry<String, Object?> entry in asMap(list[index]).entries) {
    if (drop.contains(entry.key)) {
      if (!placed) next.addAll(insert);
      placed = true;
    } else if (!insert.containsKey(entry.key)) {
      next[entry.key] = entry.value;
    }
  }
  list[index] = placed ? next : <String, Object?>{...insert, ...next};
  return <String, Object?>{...route, 'points': list};
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

/// The route with node [index] of [key] moved by [dx] and [dz] blocks.
JsonMap nudgeRouteNode(JsonMap route, String key, int index, double dx, double dz) {
  final List<Object?> list = asList(route[key]);
  if (index < 0 || index >= list.length) return route;
  final JsonMap node = asMap(list[index]);
  return moveRouteNode(route, key, index, (asDouble(node['x']) ?? 0) + dx, (asDouble(node['z']) ?? 0) + dz);
}

/// The x and z step an arrow key moves a focused point: a quarter block, or
/// a whole block with Shift. North (negative z) is up. Null for other keys.
(double, double)? routeNudge(String key, {required bool shift}) {
  final double step = shift ? 1 : 0.25;
  return switch (key) {
    'ArrowLeft' => (-step, 0.0),
    'ArrowRight' => (step, 0.0),
    'ArrowUp' => (0.0, -step),
    'ArrowDown' => (0.0, step),
    _ => null,
  };
}
