/// Native waypoint routes (`modules["animus:navigation"]` inline, or a
/// `routes/<id>.json` document), read for drawing and edited as JSON. The
/// rules are those of wiki animus/04-npc-definitions "Initial movement and routes".
library;

import 'dart:math' as math;

import '../gui/gui_json.dart';

const String navigationModule = 'animus:navigation';

const List<String> routeOrders = <String>['sequence', 'pingpong', 'random', 'wander'];
const List<String> routeModes = <String>['walk', 'swim', 'fly'];

/// Movement styles, as `MovementStyle` names them; walk is the default.
const List<String> movementStyles = <String>['walk', 'sneak', 'sprint', 'swim', 'crawl', 'fly', 'glide'];

/// The styles whose legs fly, as `MovementStyle.flies` decides.
const Set<String> flyingStyles = <String>{'fly', 'glide'};

const int routeMaxPoints = 256;
const int routeMaxWanderCentres = 64;
const int routeMaxGuides = 1024;
const int routeMaxCoordinates = 1280;
const int routeMaxWait = 12000;
const double routeDefaultLinkDistance = 32;

/// Fields a route keeps, in the module or in its route document.
const List<String> routeFields = <String>['mode', 'speed', 'order', 'loop', 'waitTicks', 'points', 'guides', 'linkDistance', 'wander', 'seed', 'startOffset', 'jitter', 'style'];

/// Route seeds and start offsets; the first of each is the default.
const List<String> routeSeeds = <String>['npc', 'random'];
const List<String> routeStartOffsets = <String>['first', 'random'];

const int routeMaxJitter = 8;
const int routeMaxWeight = 1000;
const int routeMaxJunctions = 8;

/// A whole number, or null for anything else (1.5, text, null).
int? _whole(Object? value) => value is num && value.isFinite && value == value.roundToDouble() ? value.toInt() : null;

/// Why the server refuses [value] as a `waitTicks`, worded like
/// WaypointSettings.wait without the field name, or null when it is fine.
String? routeWaitProblem(Object? value) {
  const String bounds = 'must be 0..$routeMaxWait or [min, max]';
  if (value is List<Object?>) {
    if (value.length != 2) return bounds;
    for (final Object? part in value) {
      if (part is! num) return bounds;
      if (_whole(part) == null) return 'expected whole number';
      if (part < 0 || part > routeMaxWait) return bounds;
    }
    return _whole(value[0])! > _whole(value[1])! ? 'min above max' : null;
  }
  if (value is! num) return bounds;
  if (_whole(value) == null) return 'expected whole number';
  return value < 0 || value > routeMaxWait ? bounds : null;
}

String? _rangeProblem(Object? value, int min, int max, String unit) {
  if (value is! num) return 'must be $min..$max$unit';
  if (_whole(value) == null) return 'expected whole number';
  return value < min || value > max ? 'must be $min..$max$unit' : null;
}

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

  /// The JSON a route stores: a count, or `[min, max]` for a range.
  Object toJson() => ranged ? <int>[min, max] : min;

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
    : _json = json,
      order = routeOrders.contains(json['order']) ? json['order']! as String : 'sequence',
      loop = json['loop'] == true,
      mode = routeModes.contains(json['mode']) ? json['mode']! as String : 'walk',
      waitTicks = RouteWait.fromJson(json['waitTicks']) ?? const RouteWait(20, 20),
      linkDistance = asDouble(json['linkDistance']) ?? routeDefaultLinkDistance,
      wanderRadius = asInt(asMap(json['wander'])['radius']) ?? 25,
      wanderHeight = asInt(asMap(json['wander'])['height']) ?? 3,
      points = <RouteEntry>[for (final Object? point in asList(json['points'])) RouteEntry.fromJson(point)],
      guides = <RouteEntry>[for (final Object? guide in asList(json['guides'])) RouteEntry.fromJson(guide, guide: true)];

  final JsonMap _json;
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
        final List<Object?> raw = asList(_json['points']);
        bool branches(int index) => index < raw.length && asMap(raw[index]).containsKey('next');
        return <(int, int)>[
          for (int index = 1; index < points.length; index++)
            if (!branches(index - 1)) (index - 1, index),
          if (loop && points.length > 2 && !branches(points.length - 1)) (points.length - 1, 0),
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

  /// A sequence route's junctions, from each point with `next` to each
  /// listed point that is in range; both ends must have a position.
  List<(int, int)> get junctionLinks {
    if (order != 'sequence') return const <(int, int)>[];
    final List<Object?> raw = asList(_json['points']);
    return <(int, int)>[
      for (int index = 0; index < raw.length && index < points.length; index++)
        for (final Object? junction in asList(asMap(raw[index])['next']))
          if (_whole(asMap(junction)['to']) case final int to when to >= 0 && to < points.length && to != index && points[index] is RouteNode && points[to] is RouteNode)
            (index, to),
    ];
  }

  /// Problems the runtime refuses, worded like its field errors, by field.
  /// [ownStyle] is the NPC's own `movement.style` for an inline route: a
  /// flying style lets its unstyled points hang in the air.
  List<(String, String)> problems({String? ownStyle}) {
    final List<(String, String)> found = <(String, String)>[];
    void check(String field, String? problem) {
      if (problem != null) found.add((field, problem));
    }

    final JsonMap json = _json;
    if (json.containsKey('waitTicks')) check('waitTicks', routeWaitProblem(json['waitTicks']));
    if (json.containsKey('seed') && !routeSeeds.contains(json['seed'])) found.add(('seed', 'must be npc or random'));
    if (json.containsKey('startOffset') && !routeStartOffsets.contains(json['startOffset'])) found.add(('startOffset', 'must be first or random'));
    if (json.containsKey('jitter')) {
      check('jitter', order == 'wander' ? 'is not allowed with order wander; wander.radius spreads its goals' : _rangeProblem(json['jitter'], 0, routeMaxJitter, ' blocks'));
    }
    if (json.containsKey('loop') && order != 'sequence') found.add(('loop', 'is only allowed with order sequence'));
    if (json.containsKey('style') && !movementStyles.contains(json['style'])) found.add(('style', _styleProblem));
    final List<Object?> raw = asList(json['points']);
    for (int index = 0; index < raw.length; index++) {
      found.addAll(_pointProblems(asMap(raw[index]), 'points[$index]', raw.length));
    }
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
      final Object? routeStyle = json['style'] ?? ownStyle;
      for (int index = 0; index < all.length; index++) {
        final RouteEntry node = all[index];
        final Object? legStyle = node.guide || index >= raw.length ? routeStyle : (asMap(raw[index])['style'] ?? routeStyle);
        if (flyingStyles.contains(legStyle)) continue;
        if (node is RouteNode && node.y != node.y.roundToDouble()) {
          found.add((node.guide ? 'guides[${index - points.length}].y' : 'points[$index].y', 'walking routes need whole-block heights'));
        }
      }
    }
    return found;
  }

  /// A point's own wait, jitter, weight and junctions, checked as
  /// WaypointSettings checks them against the route's order.
  List<(String, String)> _pointProblems(JsonMap point, String field, int count) {
    final List<(String, String)> found = <(String, String)>[];
    void check(String at, String? problem) {
      if (problem != null) found.add((at, problem));
    }

    final bool step = point.containsKey('recording');
    if (point.containsKey('waitTicks')) check('$field.waitTicks', routeWaitProblem(point['waitTicks']));
    if (point.containsKey('jitter')) {
      check(
        '$field.jitter',
        step ? 'a recording step has no jitter' : (order == 'wander' ? 'is not allowed with order wander' : _rangeProblem(point['jitter'], 0, routeMaxJitter, ' blocks')),
      );
    }
    if (point.containsKey('style')) {
      check('$field.style', step ? 'a recording step plays its recorded movement; it has no style' : (movementStyles.contains(point['style']) ? null : _styleProblem));
    }
    if (point.containsKey('weight')) {
      check('$field.weight', order != 'random' ? 'is only allowed with order random' : _rangeProblem(point['weight'], 1, routeMaxWeight, ''));
    }
    if (point.containsKey('speed')) {
      check('$field.speed', step ? 'requires a position point' : (order == 'wander' ? 'is not allowed with order wander' : _speedProblem(point['speed'])));
    }
    if (point.containsKey('teleport')) check('$field.teleport', step ? 'requires a position point' : (order == 'wander' ? 'is not allowed with order wander' : _teleportProblem(point['teleport'])));
    if (!point.containsKey('next')) return found;
    if (order != 'sequence') return found..add(('$field.next', 'is only allowed with order sequence'));
    final Object? next = point['next'];
    if (next is! List<Object?> || next.isEmpty || next.length > routeMaxJunctions) {
      return found..add(('$field.next', 'requires 1..$routeMaxJunctions {"to": index, "weight": 1..$routeMaxWeight} entries'));
    }
    for (int index = 0; index < next.length; index++) {
      final String entry = '$field.next[$index]';
      final Object? junction = next[index];
      if (junction is! Map<String, Object?>) {
        found.add((entry, 'expected object'));
        continue;
      }
      for (final String key in junction.keys) {
        if (key != 'to' && key != 'weight') found.add(('$entry.$key', 'unknown field'));
      }
      if (!junction.containsKey('to')) {
        found.add(('$entry.to', 'required'));
      } else {
        final int? to = _whole(junction['to']);
        if (to == null) {
          found.add(('$entry.to', 'expected whole number'));
        } else if (to < 0 || to >= count) {
          found.add(('$entry.to', 'out of range'));
        }
      }
      if (junction.containsKey('weight')) check('$entry.weight', _rangeProblem(junction['weight'], 1, routeMaxWeight, ''));
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
  if (key == 'points') _remapJunctions(list, (int to) => to == index ? null : (to > index ? to - 1 : to));
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

const List<String> _positionFields = <String>['x', 'y', 'z', 'jitter', 'style', 'speed', 'teleport'];

String? _speedProblem(Object? value) => value is num && value >= 1 && value <= 5 ? null : 'must be 1..5 blocks/second';

/// What WaypointSettings refuses in a point's teleport: x, y and z, an optional yaw and pitch, nothing else.
String? _teleportProblem(Object? value) {
  if (value is! Map<String, Object?>) return 'expected {"x", "y", "z"} with an optional yaw and pitch';
  for (final String key in value.keys) {
    if (!const <String>['x', 'y', 'z', 'yaw', 'pitch', 'world'].contains(key)) return 'unknown field $key';
  }
  for (final String axis in const <String>['x', 'y', 'z']) {
    final Object? coordinate = value[axis];
    if (coordinate is! num || coordinate.abs() > 29999984) return 'needs $axis within the world';
  }
  final Object? yaw = value['yaw'];
  if (yaw != null && (yaw is! num || yaw < -360 || yaw > 360)) return 'yaw must be -360..360 degrees';
  final Object? world = value['world'];
  if (world != null && (world is! String || !RegExp(r'^[^\s/\\]{1,64}$').hasMatch(world))) return 'world must be a world name';
  final Object? pitch = value['pitch'];
  if (pitch != null && (pitch is! num || pitch < -90 || pitch > 90)) return 'pitch must be -90..90 degrees';
  return null;
}

/// The runtime's words for a style it does not know.
final String _styleProblem = 'must be ${movementStyles.join(', ')}';
const List<String> _stepFields = <String>['recording', 'fromTick', 'toTick', 'playback'];

/// The route with point [index] made a step playing [recording]: x, y, z,
/// jitter and style go, `recording` takes the place of the first coordinate, and the
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
  final List<Object?> moved = List<Object?>.of(moveItem(list, from, to));
  _remapJunctions(moved, (int old) {
    if (old == from) return to;
    if (from < to && old > from && old <= to) return old - 1;
    if (to < from && old >= to && old < from) return old + 1;
    return old;
  });
  return <String, Object?>{...route, 'points': moved};
}

/// Rewrites every junction target in [points] through [map]; a target it
/// maps to null goes, and an emptied `next` is removed.
void _remapJunctions(List<Object?> points, int? Function(int to) map) {
  for (int index = 0; index < points.length; index++) {
    final JsonMap point = asMap(points[index]);
    if (!point.containsKey('next')) continue;
    final List<Object?> kept = <Object?>[];
    for (final Object? junction in asList(point['next'])) {
      final JsonMap entry = asMap(junction);
      final int? to = _whole(entry['to']);
      if (to == null) {
        kept.add(junction);
        continue;
      }
      final int? mapped = map(to);
      if (mapped != null) kept.add(<String, Object?>{...entry, 'to': mapped});
    }
    final JsonMap next = <String, Object?>{...point};
    if (kept.isEmpty) {
      next.remove('next');
    } else {
      next['next'] = kept;
    }
    points[index] = next;
  }
}

/// The route with a junction appended to point [index]'s `next`, leading
/// to the first other point it does not list yet.
JsonMap addRouteJunction(JsonMap route, int index) {
  final List<Object?> points = asList(route['points']);
  if (index < 0 || index >= points.length) return route;
  final List<Object?> next = asList(asMap(points[index])['next']);
  final Set<int?> listed = <int?>{for (final Object? junction in next) _whole(asMap(junction)['to'])};
  int to = 0;
  for (int candidate = 0; candidate < points.length; candidate++) {
    if (candidate != index && !listed.contains(candidate)) {
      to = candidate;
      break;
    }
  }
  return setRouteNodeField(route, 'points', index, 'next', <Object?>[...next, <String, Object?>{'to': to}]);
}

const Object _keep = Object();

/// The route with junction [junction] of point [index] changed: [to] when
/// given, and [weight] set, or removed when null, when given.
JsonMap setRouteJunction(JsonMap route, int index, int junction, {int? to, Object? weight = _keep}) {
  final List<Object?> points = asList(route['points']);
  if (index < 0 || index >= points.length) return route;
  final List<Object?> next = List<Object?>.of(asList(asMap(points[index])['next']));
  if (junction < 0 || junction >= next.length) return route;
  final JsonMap entry = <String, Object?>{...asMap(next[junction])};
  if (to != null) entry['to'] = to;
  if (!identical(weight, _keep)) {
    if (weight == null) {
      entry.remove('weight');
    } else {
      entry['weight'] = weight;
    }
  }
  next[junction] = entry;
  return setRouteNodeField(route, 'points', index, 'next', next);
}

/// The route without junction [junction] of point [index]; an emptied
/// `next` is removed.
JsonMap removeRouteJunction(JsonMap route, int index, int junction) {
  final List<Object?> points = asList(route['points']);
  if (index < 0 || index >= points.length) return route;
  final List<Object?> next = List<Object?>.of(asList(asMap(points[index])['next']));
  if (junction < 0 || junction >= next.length) return route;
  next.removeAt(junction);
  return setRouteNodeField(route, 'points', index, 'next', next.isEmpty ? null : next);
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
  if (order == 'wander') next.remove('jitter');
  bool refused(String field) => switch (field) {
        'weight' => order != 'random',
        'next' => order != 'sequence',
        'jitter' || 'speed' || 'teleport' => order == 'wander',
        _ => false,
      };
  if (next['points'] is List<Object?>) {
    next['points'] = <Object?>[
      for (final Object? point in asList(next['points']))
        if (point is Map<String, Object?>)
          <String, Object?>{
            for (final MapEntry<String, Object?> entry in point.entries)
              if (!refused(entry.key)) entry.key: entry.value,
          }
        else
          point,
    ];
  }
  return next;
}

/// Where an NPC's `animus:navigation` module takes its movement from.
enum NavigationSource { points, route, recording }

/// How a navigation recording plays; `loop` is the default.
const List<String> navigationPlaybacks = <String>['once', 'loop', 'pingpong'];

/// The route fields RecordingSource refuses beside `recording`, in its order.
const List<String> _recordingConflicts = <String>['points', 'mode', 'speed', 'order', 'loop', 'waitTicks', 'guides', 'linkDistance', 'wander', 'seed', 'startOffset', 'jitter', 'style'];

NavigationSource navigationSource(JsonMap module) => module.containsKey('recording')
    ? NavigationSource.recording
    : (module.containsKey('route') ? NavigationSource.route : NavigationSource.points);

/// The module playing [recording]: `route` and every route field go, the
/// rest (enabled, reviewAccepted, unknown keys) stay. A null [playback]
/// leaves it out, which plays `loop`.
JsonMap setNavigationRecording(JsonMap module, String recording, {String? playback}) => <String, Object?>{
      for (final MapEntry<String, Object?> entry in module.entries)
        if (entry.key != 'route' && entry.key != 'recording' && entry.key != 'playback' && !_recordingConflicts.contains(entry.key)) entry.key: entry.value,
      'recording': recording,
      'playback': ?playback,
    };

/// What RecordingSource refuses in an enabled module, by field under
/// `modules.animus:navigation`.
List<(String, String)> navigationProblems(JsonMap module) {
  if (module['enabled'] != true) return const <(String, String)>[];
  if (!module.containsKey('recording')) {
    return module.containsKey('playback') ? const <(String, String)>[('playback', 'requires recording')] : const <(String, String)>[];
  }
  final Object? recording = module['recording'];
  if (recording is! String || !routeRecordingId.hasMatch(recording)) {
    return const <(String, String)>[('recording', 'expected 1-64 lowercase letters, digits, _ or -')];
  }
  if (module.containsKey('route')) return const <(String, String)>[('recording', 'cannot be combined with route')];
  for (final String conflicting in _recordingConflicts) {
    if (module.containsKey(conflicting)) return <(String, String)>[('recording', 'cannot be combined with $conflicting')];
  }
  if (module.containsKey('playback') && !navigationPlaybacks.contains(module['playback'])) {
    return const <(String, String)>[('playback', 'expected once, loop or pingpong')];
  }
  return const <(String, String)>[];
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
