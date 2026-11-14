@TestOn('vm')
library;

import 'dart:math' as math;

import 'package:animus_editor/logic/npc/route_model.dart';
import 'package:animus_editor/logic/terrain/terrain_data.dart';
import 'package:animus_editor/logic/terrain/terrain_path.dart';
import 'package:animus_editor/logic/terrain/terrain_volume.dart';
import 'package:test/test.dart';

TerrainSlice _slice(int cx, int cz, int y, int height, String state) => TerrainSlice.fromJson(<String, Object?>{
      'world': 'world',
      'cx': cx,
      'cz': cz,
      'y': y,
      'height': height,
      'status': 'ok',
      'hash': 'sha256:${'0' * 64}',
      'palette': <String>[state],
      'bits': 0,
      'data': '',
    })!;

Set<(int, int)> _chunks(TerrainPathPlan plan) => <(int, int)>{for (final TerrainPathChunk chunk in plan.chunks) (chunk.cx, chunk.cz)};

void main() {
  group('plan', () {
    test('a lone point asks the chunks within the radius, 32 layers around its y', () {
      final TerrainPathPlan plan = terrainPathPlan(TerrainPath.point(0.5, 64, 0.5), radius: 16);
      expect(_chunks(plan), <(int, int)>{for (int cz = -1; cz <= 1; cz++) for (int cx = -1; cx <= 1; cx++) if (cx != 1 || cz != 1) (cx, cz)}, reason: 'chunk 1, 1 starts 22 blocks away');
      expect(plan.chunks.every((TerrainPathChunk chunk) => chunk.y == 48), isTrue);
      expect(plan.truncated, isFalse);
      expect(plan.covers(16, 0), isTrue);
      expect(plan.covers(17, 0), isFalse, reason: 'past the radius');
      expect(plan.covers(12, 12), isFalse, reason: 'the corner of the square is past the radius');
    });

    test('a far route asks every chunk along its legs, nearest the start first, at the height of the leg', () {
      final TerrainPath path = TerrainPath(
        points: const <TerrainPathPoint>[(x: 0, y: 64, z: 0), (x: -400, y: 64, z: 0), (x: -400, y: 104, z: -300)],
        legs: const <(int, int)>[(0, 1), (1, 2)],
      );
      final TerrainPathPlan plan = terrainPathPlan(path, radius: 8);
      final Set<(int, int)> chunks = _chunks(plan);
      expect(chunks, contains((-13, 0)), reason: 'halfway along the first leg');
      expect(chunks, contains((-25, -10)), reason: 'halfway along the second leg');
      expect(chunks, contains((-25, -19)), reason: 'the last point');
      expect(chunks, isNot(contains((-13, -10))), reason: 'nothing between the legs');
      expect(plan.chunks.first.cx.abs() <= 1 && plan.chunks.first.cz.abs() <= 1, isTrue, reason: 'the start loads first');
      final TerrainPathChunk middle = plan.chunks.firstWhere((TerrainPathChunk chunk) => chunk.cx == -25 && chunk.cz == -10);
      expect(middle.y, inInclusiveRange(64 + 18 - 16 - 2, 64 + 22 - 16 + 2), reason: 'the leg climbs 40 blocks over 300');
      expect(plan.covers(-200, 0), isTrue);
      expect(plan.covers(-200, 9), isFalse);
      expect(plan.truncated, isFalse);
    });

    test('a path longer than the cap keeps every point and the start of the leg, and says so', () {
      final TerrainPath path = TerrainPath(points: const <TerrainPathPoint>[(x: 0, y: 64, z: 0), (x: 5000, y: 64, z: 0)], legs: const <(int, int)>[(0, 1)]);
      final TerrainPathPlan plan = terrainPathPlan(path, radius: 16, maxChunks: 40);
      expect(plan.chunks, hasLength(40));
      expect(plan.truncated, isTrue);
      expect(_chunks(plan), containsAll(<(int, int)>[(0, 0), (312, 0), (5, 0)]));
      expect(_chunks(plan), isNot(contains((200, 0))), reason: 'the leg is cut off past the cap');
    });

    test('a slice starts on the slice grid and holds the 32 layers drawn, for every height', () {
      for (int y = -70; y < 330; y++) {
        final TerrainPathChunk chunk = terrainPathPlan(TerrainPath.point(0.5, y + 0.5, 0.5), radius: 16).chunks.first;
        final ({int y, int height}) slice = terrainSliceFor(chunk);
        expect(slice.y % terrainSliceStep, 0, reason: 'path at $y');
        expect(slice.y, lessThanOrEqualTo(chunk.y), reason: 'path at $y');
        expect(slice.y + slice.height, greaterThanOrEqualTo(chunk.y + terrainPathBelow + terrainPathAbove), reason: 'path at $y');
        expect(slice.height, lessThanOrEqualTo(terrainSliceMaxHeight));
      }
    });

    test('an NPC walking over low hills asks for few new slices', () {
      final Set<String> asked = <String>{};
      for (int step = 0; step < 40; step++) {
        final double x = 0.5 + 10 * math.cos(step * 0.35), z = 0.5 + 10 * math.sin(step * 0.35), y = 65.0 + step % 4;
        final TerrainPathPlan plan = terrainPathPlan(terrainRoutePath(null, home: (0.5, 64, 0.5), live: (x, y, z)), radius: 16);
        for (final TerrainPathChunk chunk in plan.chunks) {
          final ({int y, int height}) slice = terrainSliceFor(chunk);
          asked.add('${chunk.cx},${chunk.cz},${slice.y},${slice.height}');
        }
      }
      expect(asked.length, lessThanOrEqualTo(16), reason: 'the chunks around home and the walk, each asked once');
    });

    test('equal paths plan to the same signature, a moved point to another', () {
      final TerrainPath a = TerrainPath(points: const <TerrainPathPoint>[(x: 0, y: 64, z: 0), (x: 40, y: 64, z: 0)], legs: const <(int, int)>[(0, 1)]);
      final TerrainPath b = TerrainPath(points: const <TerrainPathPoint>[(x: 0, y: 64, z: 0), (x: 40, y: 64, z: 0)], legs: const <(int, int)>[(0, 1)]);
      final TerrainPath c = TerrainPath(points: const <TerrainPathPoint>[(x: 0, y: 64, z: 0), (x: 41, y: 64, z: 0)], legs: const <(int, int)>[(0, 1)]);
      expect(a.signature, b.signature);
      expect(a.signature, isNot(c.signature));
    });
  });

  group('grid tiles', () {
    test('a small grid loads every tile it shows', () {
      final ({List<(int, int)> tiles, bool alongPath, bool truncated}) grid = terrainGridTiles(TerrainPath.point(0, 64, 0), -20, -20, 100, 30, radius: 24, maxTiles: 49);
      expect(grid.tiles.toSet(), <(int, int)>{(-1, -1), (0, -1), (1, -1), (-1, 0), (0, 0), (1, 0)});
      expect(grid.alongPath, isFalse);
    });

    test('a grid wider than the cap loads the tiles along the route, far ends included', () {
      final TerrainPath path = TerrainPath(
        points: const <TerrainPathPoint>[(x: 0, y: 64, z: 0), (x: -900, y: 64, z: 0), (x: -900, y: 64, z: -900)],
        legs: const <(int, int)>[(0, 1), (1, 2)],
      );
      final ({List<(int, int)> tiles, bool alongPath, bool truncated}) grid = terrainGridTiles(path, -903, -903, 3, 3, radius: 24, maxTiles: 64);
      expect(grid.alongPath, isTrue);
      expect(grid.truncated, isFalse);
      expect(grid.tiles, containsAll(<(int, int)>[(0, 0), (-8, 0), (-15, 0), (-15, -8), (-15, -15)]));
      expect(grid.tiles, isNot(contains((-8, -8))), reason: 'nothing between the legs');
      expect(grid.tiles.length, lessThan(64));
    });

    test('a route that needs more tiles than the cap says so', () {
      final TerrainPath path = TerrainPath(points: const <TerrainPathPoint>[(x: 0, y: 64, z: 0), (x: 20000, y: 64, z: 0)], legs: const <(int, int)>[(0, 1)]);
      final ({List<(int, int)> tiles, bool alongPath, bool truncated}) grid = terrainGridTiles(path, -3, -10000, 20003, 10000, radius: 24, maxTiles: 64);
      expect(grid.tiles, hasLength(64));
      expect(grid.truncated, isTrue);
      expect(grid.tiles, contains((312, 0)), reason: 'the far point still loads');
    });
  });

  group('route path', () {
    test('the NPC, every placed point and the links it walks, the first leg from the NPC', () {
      final RouteSpec spec = RouteSpec(<String, Object?>{
        'points': <Object?>[
          <String, Object?>{'x': -300, 'y': 64, 'z': 0},
          <String, Object?>{'recording': 'wave'},
          <String, Object?>{'x': -300, 'y': 70, 'z': -300},
        ],
      });
      final TerrainPath path = terrainRoutePath(spec, home: (0.5, 64, 0.5));
      expect(path.points.first, (x: 0.5, y: 64.0, z: 0.5));
      expect(path.points, contains((x: -300.0, y: 70.0, z: -300.0)));
      final Set<(int, int)> chunks = _chunks(terrainPathPlan(path, radius: 8));
      expect(chunks, contains((-10, 0)), reason: 'from the NPC to the first point');
      expect(chunks, isNot(contains((-19, -10))), reason: 'no link runs through a recording step');
    });

    test('a live position joins the path as its own point', () {
      final TerrainPath path = terrainRoutePath(null, home: (0, 64, 0), live: (900, 80, 900));
      expect(_chunks(terrainPathPlan(path, radius: 8)), contains((56, 56)));
    });
  });

  group('sparse volume', () {
    test('columns of different chunks keep their own layers, and cut-off columns stay unknown', () {
      final TerrainVolume volume = TerrainVolume()
        ..add(_slice(0, 0, 60, 8, 'minecraft:stone'))
        ..add(_slice(-20, 5, 100, 4, 'minecraft:sand'), keep: (int x, int z) => x < -310);
      expect(volume.stateAt(3, 60, 3), 'minecraft:stone');
      expect(volume.stateAt(3, 68, 3), isNull);
      expect(volume.stateAt(-320, 101, 85), 'minecraft:sand');
      expect(volume.stateAt(-305, 101, 85), isNull, reason: 'outside the kept columns');
      expect(volume.stateAt(-320, 60, 85), isNull);
      expect(volume.chunks.toSet(), <(int, int)>{(0, 0), (-20, 5)});
    });

    test('a box volume clips its slices as before', () {
      final TerrainVolume volume = TerrainVolume.of(-4, 60, -4, 8, 8, 8, <TerrainSlice>[_slice(-1, -1, 56, 16, 'minecraft:stone')]);
      expect(volume.stateAt(-1, 60, -1), 'minecraft:stone');
      expect(volume.stateAt(-1, 59, -1), isNull);
      expect(volume.stateAt(-5, 60, -1), isNull);
      expect(volume.stateAt(-1, 68, -1), isNull);
    });
  });
}
