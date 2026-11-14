/// Where an NPC goes, as the terrain views load it: points and the straight
/// legs between them. [terrainPathPlan] turns a path into the chunk slices
/// within a radius of it, each at the height of the path beside it, and
/// knows which block columns that radius covers.
library;

import 'dart:math' as math;

import '../npc/route_model.dart';
import 'terrain_data.dart';

/// One place on a path, in blocks.
typedef TerrainPathPoint = ({double x, double y, double z});

/// One chunk a view draws: the chunk and its lowest layer drawn.
typedef TerrainPathChunk = ({int cx, int cz, int y});

/// The most chunk slices one view asks for along a path.
const int terrainPathMaxChunks = 256;

/// Layers a view draws below and above the path.
const int terrainPathBelow = 16;
const int terrainPathAbove = 16;

/// Slices start on this grid and reach one step past the layers drawn, so
/// a path that moves a few blocks up or down (a live NPC on a hill, a
/// dragged route point) reuses the slices it already has.
const int terrainSliceStep = 16;

/// The slice to ask for to draw [chunk]: its lowest layer rounded down to
/// [terrainSliceStep], tall enough to hold every layer drawn.
({int y, int height}) terrainSliceFor(TerrainPathChunk chunk) =>
    (y: terrainFloorDiv(chunk.y, terrainSliceStep) * terrainSliceStep, height: terrainPathBelow + terrainPathAbove + terrainSliceStep);

final class TerrainPath {
  TerrainPath({required this.points, this.legs = const <(int, int)>[]});

  TerrainPath.point(double x, double y, double z) : this(points: <TerrainPathPoint>[(x: x, y: y, z: z)]);

  /// In path order; the first is where loading starts.
  final List<TerrainPathPoint> points;

  /// Indices into [points] joined by a straight leg.
  final List<(int, int)> legs;

  /// Equal for equal paths.
  late final String signature = <String>[
    for (final TerrainPathPoint point in points) '${point.x},${point.y},${point.z}',
    for (final (int a, int b) in legs) '$a-$b',
  ].join('|');
}

/// The NPC at [home] and [live] (where it is now) when known, the placed
/// points of [spec] and the links it draws, and the NPC's walk from home to
/// the first point when the route walks in order.
TerrainPath terrainRoutePath(RouteSpec? spec, {(double, double, double)? home, (double, double, double)? live}) {
  final List<TerrainPathPoint> points = <TerrainPathPoint>[
    if (home != null) (x: home.$1, y: home.$2, z: home.$3),
    if (live != null) (x: live.$1, y: live.$2, z: live.$3),
  ];
  final List<(int, int)> legs = <(int, int)>[];
  if (spec != null) {
    final List<RouteEntry> nodes = spec.nodes;
    final Map<int, int> at = <int, int>{};
    for (int index = 0; index < nodes.length; index++) {
      if (nodes[index] case final RouteNode node) {
        at[index] = points.length;
        points.add((x: node.x, y: node.y, z: node.z));
      }
    }
    final int? first = at[0];
    if (home != null && (spec.order == 'sequence' || spec.order == 'pingpong') && first != null) legs.add((0, first));
    for (final (int a, int b) in <(int, int)>[...spec.links, ...spec.junctionLinks]) {
      final int? from = at[a], to = at[b];
      if (from != null && to != null) legs.add((from, to));
    }
  }
  return TerrainPath(points: points, legs: legs);
}

/// The slices a path needs within [radius] blocks, and which columns that
/// radius covers.
final class TerrainPathPlan {
  TerrainPathPlan._(this.radius, this.chunks, this.truncated, this._pieces, this._near);

  final int radius;

  /// In load order, at most the cap given to [terrainPathPlan].
  final List<TerrainPathChunk> chunks;

  /// True when the path needs more chunks than the cap.
  final bool truncated;

  final List<_Piece> _pieces;
  final Map<(int, int), List<int>> _near;

  /// Whether the column at block [x], [z] is within the radius of the path.
  bool covers(int x, int z) {
    final List<int>? near = _near[(terrainFloorDiv(x, 16), terrainFloorDiv(z, 16))];
    if (near == null) return false;
    final double cx = x + 0.5, cz = z + 0.5, limit = radius * radius.toDouble();
    for (final int index in near) {
      if (_pieces[index].distanceSquared(cx, cz) <= limit) return true;
    }
    return false;
  }

  /// Which columns of chunk [cx], [cz] are covered, as a short string, so
  /// a chunk whose cut did not change keeps its mesh.
  String mask(int cx, int cz) {
    final List<int> words = List<int>.filled(8, 0);
    for (int index = 0; index < 256; index++) {
      if (covers(cx * 16 + index % 16, cz * 16 + index ~/ 16)) words[index ~/ 32] |= 1 << (index % 32);
    }
    return words.map((int word) => word.toRadixString(36)).join('.');
  }
}

/// A point (both ends equal) or a leg, in x/z, with y at each end.
final class _Piece {
  _Piece(this.ax, this.ay, this.az, this.bx, this.by, this.bz);

  final double ax, ay, az, bx, by, bz;

  double get length => math.sqrt((bx - ax) * (bx - ax) + (bz - az) * (bz - az));

  /// How far along (0 to 1) the nearest place to [x], [z] is.
  double along(double x, double z) {
    final double dx = bx - ax, dz = bz - az, square = dx * dx + dz * dz;
    if (square == 0) return 0;
    return (((x - ax) * dx + (z - az) * dz) / square).clamp(0, 1).toDouble();
  }

  double distanceSquared(double x, double z) {
    final double t = along(x, z), ex = ax + t * (bx - ax) - x, ez = az + t * (bz - az) - z;
    return ex * ex + ez * ez;
  }

  double yAt(double t) => ay + t * (by - ay);

  /// The squared distance to the 16-block square from [minX], [minZ].
  double toChunk(double minX, double minZ) {
    final double maxX = minX + 16, maxZ = minZ + 16;
    double best = math.min(_toSquare(ax, az, minX, minZ, maxX, maxZ), _toSquare(bx, bz, minX, minZ, maxX, maxZ));
    if (best == 0 || (ax == bx && az == bz)) return best;
    if (_crosses(minX, minZ, maxX, maxZ)) return 0;
    for (final (double x, double z) in <(double, double)>[(minX, minZ), (maxX, minZ), (minX, maxZ), (maxX, maxZ)]) {
      best = math.min(best, distanceSquared(x, z));
    }
    return best;
  }

  bool _crosses(double minX, double minZ, double maxX, double maxZ) {
    double t0 = 0, t1 = 1;
    final double dx = bx - ax, dz = bz - az;
    final List<double> p = <double>[-dx, dx, -dz, dz], q = <double>[ax - minX, maxX - ax, az - minZ, maxZ - az];
    for (int i = 0; i < 4; i++) {
      if (p[i] == 0) {
        if (q[i] < 0) return false;
        continue;
      }
      final double t = q[i] / p[i];
      if (p[i] < 0) {
        t0 = math.max(t0, t);
      } else {
        t1 = math.min(t1, t);
      }
      if (t0 > t1) return false;
    }
    return true;
  }

  static double _toSquare(double x, double z, double minX, double minZ, double maxX, double maxZ) {
    final double dx = math.max(math.max(minX - x, 0), x - maxX), dz = math.max(math.max(minZ - z, 0), z - maxZ);
    return dx * dx + dz * dz;
  }
}

/// The chunk slices within [radius] blocks of [path], at most [maxChunks]:
/// around every point first, in path order, so a long leg cannot crowd
/// out a later point, then along each leg from its start in steps of half
/// a chunk.
TerrainPathPlan terrainPathPlan(TerrainPath path, {required int radius, int maxChunks = terrainPathMaxChunks}) {
  final List<_Piece> pieces = <_Piece>[
    for (final TerrainPathPoint point in path.points) _Piece(point.x, point.y, point.z, point.x, point.y, point.z),
    for (final (int a, int b) in path.legs)
      if (a >= 0 && b >= 0 && a < path.points.length && b < path.points.length)
        _Piece(path.points[a].x, path.points[a].y, path.points[a].z, path.points[b].x, path.points[b].y, path.points[b].z),
  ];
  final List<TerrainPathChunk> chunks = <TerrainPathChunk>[];
  final Map<(int, int), List<int>> near = <(int, int), List<int>>{};
  final double limit = radius * radius.toDouble();
  final int reach = terrainFloorDiv(radius + 8, 16) + 1;
  bool truncated = false;
  for (int index = 0; index < pieces.length && !truncated; index++) {
    final _Piece piece = pieces[index];
    final int steps = math.max(1, (piece.length / 8).ceil());
    for (int step = 0; step <= steps && !truncated; step++) {
      final double t = step / steps;
      final int sx = terrainFloorDiv((piece.ax + t * (piece.bx - piece.ax)).floor(), 16), sz = terrainFloorDiv((piece.az + t * (piece.bz - piece.az)).floor(), 16);
      for (int cz = sz - reach; cz <= sz + reach && !truncated; cz++) {
        for (int cx = sx - reach; cx <= sx + reach; cx++) {
          if (piece.toChunk(cx * 16.0, cz * 16.0) > limit) continue;
          final List<int>? known = near[(cx, cz)];
          if (known != null) {
            if (!known.contains(index)) known.add(index);
            continue;
          }
          if (chunks.length >= maxChunks) {
            truncated = true;
            break;
          }
          final double y = piece.yAt(piece.along(cx * 16 + 8.0, cz * 16 + 8.0));
          near[(cx, cz)] = <int>[index];
          chunks.add((cx: cx, cz: cz, y: y.floor() - terrainPathBelow));
        }
      }
    }
  }
  return TerrainPathPlan._(radius, List<TerrainPathChunk>.unmodifiable(chunks), truncated, pieces, near);
}

/// The surface tiles a top-down grid loads: every tile of the area from
/// [minX], [minZ] to [maxX], [maxZ] when that is at most [maxTiles], else
/// the tiles within [radius] blocks of [path] in path order, at most
/// [maxTiles]. [alongPath] says the grid is too wide to fill; [truncated]
/// that the path needs more tiles still.
({List<(int, int)> tiles, bool alongPath, bool truncated}) terrainGridTiles(TerrainPath path, double minX, double minZ, double maxX, double maxZ,
    {required int radius, required int maxTiles}) {
  final int tx0 = terrainFloorDiv(minX.floor(), terrainTileSize), tx1 = terrainFloorDiv(maxX.floor(), terrainTileSize);
  final int tz0 = terrainFloorDiv(minZ.floor(), terrainTileSize), tz1 = terrainFloorDiv(maxZ.floor(), terrainTileSize);
  if ((tx1 - tx0 + 1) * (tz1 - tz0 + 1) <= maxTiles) {
    return (tiles: <(int, int)>[for (int tz = tz0; tz <= tz1; tz++) for (int tx = tx0; tx <= tx1; tx++) (tx, tz)], alongPath: false, truncated: false);
  }
  final TerrainPathPlan plan = terrainPathPlan(path, radius: radius, maxChunks: maxTiles * terrainTileChunks * terrainTileChunks);
  final List<(int, int)> tiles = <(int, int)>[];
  final Set<(int, int)> seen = <(int, int)>{};
  bool truncated = plan.truncated;
  for (final TerrainPathChunk chunk in plan.chunks) {
    final (int, int) tile = (terrainFloorDiv(chunk.cx, terrainTileChunks), terrainFloorDiv(chunk.cz, terrainTileChunks));
    if (!seen.add(tile)) continue;
    if (tiles.length >= maxTiles) {
      truncated = true;
      break;
    }
    tiles.add(tile);
  }
  return (tiles: tiles, alongPath: true, truncated: truncated);
}
