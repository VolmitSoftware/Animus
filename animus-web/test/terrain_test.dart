@TestOn('vm')
library;

import 'dart:convert';
import 'dart:math' as math;
import 'dart:typed_data';

import 'package:animus_editor/logic/json_schema.dart';
import 'package:animus_editor/logic/shared_schemas.dart';
import 'package:animus_editor/logic/terrain/terrain_data.dart';
import 'package:animus_editor/logic/terrain/terrain_snap.dart';
import 'package:animus_editor/logic/terrain/terrain_volume.dart';
import 'package:animus_editor/mc/terrain/terrain_tile_image.dart';
import 'package:animus_editor/services/server/transport.dart';
import 'package:animus_editor/services/terrain/terrain_client.dart';
import 'package:test/test.dart';

import '../tool/fake_server/protocol.dart';
import '../tool/fake_server/terrain.dart';

const List<FakeAnchor> _anchors = <FakeAnchor>[(world: 'world', x: 0.5, z: 0.5)];

Map<String, Object?> _answer(FakeResponse response) {
  expect(response.status, 200);
  return response.body! as Map<String, Object?>;
}

FakeRequest _get(String target, [Map<String, String> headers = const <String, String>{}]) =>
    FakeRequest(transport: Transport.direct, method: 'GET', target: target, headers: headers);

TerrainSurfaceTile _tile(int tx, int tz) => TerrainSurfaceTile.fromJson(_answer(fakeTerrainSurface(_get('/v1/terrain/surface?world=world&tx=$tx&tz=$tz'), _anchors)))!;

TerrainSlice _slice(int cx, int cz, int y, int height) =>
    TerrainSlice.fromJson(_answer(fakeTerrainBlocks(_get('/v1/terrain/blocks?world=world&cx=$cx&cz=$cz&y=$y&height=$height'), _anchors)))!;

/// The fixture world around the plaza: surface tiles and block slices.
final class _Fixture implements TerrainLookup {
  _Fixture({bool blocks = true}) : volume = blocks ? TerrainVolume.of(-32, 48, -32, 64, 32, 64, <TerrainSlice>[for (int cz = -2; cz < 2; cz++) for (int cx = -2; cx < 2; cx++) _slice(cx, cz, 48, 32)]) : null;

  final TerrainVolume? volume;
  final Map<(int, int), TerrainSurfaceTile> tiles = <(int, int), TerrainSurfaceTile>{};

  @override
  TerrainColumn? column(int x, int z) => tiles.putIfAbsent((x >> 6, z >> 6), () => _tile(x >> 6, z >> 6)).at(x, z);

  @override
  String? state(int x, int y, int z) => volume?.stateAt(x, y, z);
}

void main() {
  group('decoding', () {
    test('packed indices round trip at every width', () {
      final math.Random random = math.Random(7);
      for (int bits = 1; bits <= 12; bits++) {
        final List<int> values = <int>[for (int i = 0; i < 16 * 16 * 33; i++) random.nextInt(1 << bits)];
        expect(terrainUnpack(terrainPack(values, bits), bits, values.length), values, reason: '$bits bits');
      }
      expect(terrainBitsFor(1), 0);
      expect(terrainBitsFor(2), 1);
      expect(terrainBitsFor(17), 5);
    });

    test('a surface tile reads heights, top blocks and water depth', () {
      final TerrainSurfaceTile tile = _tile(0, -1);
      expect(tile.at(0, -1)!.height, 64);
      expect(tile.at(0, -1)!.block, anyOf('minecraft:grass_block', 'minecraft:short_grass', 'minecraft:poppy'));
      final TerrainColumn pond = tile.at(10, -8)!;
      expect(pond.block, 'minecraft:water');
      expect(pond.height, 63);
      expect(pond.water, 3);
    });

    test('unloaded and forbidden chunks have no columns', () {
      final TerrainSurfaceTile east = _tile(2, 0);
      expect(east.chunkAt(0, 0), TerrainChunkState.unloaded);
      expect(east.local(0, 0), isNull);
      final TerrainSurfaceTile far = _tile(10, 10);
      expect(far.chunks.toSet(), <TerrainChunkState>{TerrainChunkState.forbidden});
    });

    test('a slice reads block states with their properties', () {
      final TerrainSlice slice = _slice(1, 0, 60, 16);
      expect(slice.height, 16);
      final int surface = fakeSurfaceY(22, 0);
      expect(slice.stateAt(22, surface + 1, 0), 'minecraft:stone_brick_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]');
      expect(TerrainBlockState.parse(slice.stateAt(22, surface + 1, 0)!).properties['facing'], 'east');
      expect(slice.stateAt(40, 60, 0), isNull);
    });

    test('a slice outside the world is refused and one past the top is clipped', () {
      expect(() => fakeTerrainBlocks(_get('/v1/terrain/blocks?world=world&cx=0&cz=0&y=400&height=16'), _anchors), throwsA(isA<ApiError>()));
      expect(() => fakeTerrainBlocks(_get('/v1/terrain/blocks?world=world&cx=0&cz=0&y=0&height=65'), _anchors), throwsA(isA<ApiError>()));
      final TerrainSlice top = TerrainSlice.fromJson(_answer(fakeTerrainBlocks(_get('/v1/terrain/blocks?world=world&cx=0&cz=0&y=300&height=64'), _anchors)))!;
      expect(top.y + top.height, 320);
    });

    test('the hash comes back as a 304 when the browser has it', () {
      final Map<String, Object?> first = _answer(fakeTerrainSurface(_get('/v1/terrain/surface?world=world&tx=0&tz=0'), _anchors));
      final FakeResponse again = fakeTerrainSurface(_get('/v1/terrain/surface?world=world&tx=0&tz=0', <String, String>{'If-None-Match': '"${first['hash']}"'}), _anchors);
      expect(again.status, 304);
      expect(again.body, isNull);
    });

    test('the fake answers match the editor-v1 schema', () {
      for (final (String definition, FakeResponse response) in <(String, FakeResponse)>[
        ('terrain-surface-response', fakeTerrainSurface(_get('/v1/terrain/surface?world=world&tx=0&tz=0'), _anchors)),
        ('terrain-surface-response', fakeTerrainSurface(_get('/v1/terrain/surface?world=world&tx=9&tz=9'), _anchors)),
        ('terrain-blocks-response', fakeTerrainBlocks(_get('/v1/terrain/blocks?world=world&cx=0&cz=0&y=56&height=32'), _anchors)),
        ('terrain-blocks-response', fakeTerrainBlocks(_get('/v1/terrain/blocks?world=world&cx=9&cz=0&y=56&height=32'), _anchors)),
      ]) {
        final List<SchemaError> errors = SharedSchemas.embedded.validateMessage(definition, response.body);
        expect(errors, isEmpty, reason: definition);
      }
    });

    test('a broken answer decodes to null', () {
      expect(TerrainSurfaceTile.fromJson(<String, Object?>{'size': 64}), isNull);
      expect(TerrainSlice.fromJson(<String, Object?>{'height': 16, 'palette': <String>['minecraft:air'], 'bits': 4, 'data': ''}), isNull);
    });
  });

  group('volume', () {
    test('slices fill the box and leave the rest unknown', () {
      final TerrainVolume volume = TerrainVolume.of(-4, 60, -4, 8, 8, 8, <TerrainSlice>[_slice(-1, -1, 60, 8)]);
      expect(volume.stateAt(-1, 64, -1), 'minecraft:grass_block[snowy=false]');
      expect(volume.stateAt(2, 64, 2), isNull);
      expect(volume.stateAt(20, 64, 2), isNull);
    });
  });

  group('snapping and checks', () {
    late _Fixture terrain;
    late _Fixture surface;
    setUpAll(() {
      terrain = _Fixture();
      surface = _Fixture(blocks: false);
    });

    test('a point on the plaza snaps onto the grass', () {
      expect(terrainSnapY(terrain, 2, 2, 80), anyOf(65, 66));
      expect(terrainSnapY(terrain, 0, -2, 64), 65);
      expect(terrainSnapY(surface, 0, -2, 64), 65);
    });

    test('a point on the slab and stair end of the path stands on them', () {
      final int surfaceY = fakeSurfaceY(21, 0);
      expect(terrainSnapY(terrain, 21, 0, surfaceY), surfaceY + 2);
      expect(terrainSnapY(terrain, 23, 0, surfaceY), fakeSurfaceY(23, 0) + 1);
    });

    test('unknown terrain does not snap', () {
      expect(terrainSnapY(surface, 200, 0, 64), isNull);
    });

    test('points inside a block, floating or in water are flagged', () {
      expect(terrainCheckPoint(terrain, 0, 64, -2), TerrainIssue.inside);
      expect(terrainCheckPoint(terrain, 0, 65, -2), isNull);
      expect(terrainCheckPoint(terrain, 0, 70, -2), TerrainIssue.floating);
      expect(terrainCheckPoint(terrain, 10, 62, -8), TerrainIssue.water);
      expect(terrainCheckPoint(surface, 0, 62, -2), TerrainIssue.below);
      expect(terrainCheckPoint(surface, 0, 70, -2), TerrainIssue.floating);
      expect(terrainCheckPoint(surface, 200, 70, 0), isNull);
    });

    test('a straight leg up a wall is steep, along the plaza is not', () {
      expect(terrainSteepStep(surface, -4, 0, 4, 0), isNull);
      final _Wall wall = _Wall();
      expect(terrainSteepStep(wall, 0, 0, 6, 0), (3, 0, 4));
      expect(terrainSteepStep(wall, 6, 0, 0, 0), (2, 0, -4));
      expect(terrainSteepStep(wall, 0, 0, 2, 0), isNull);
    });
  });

  group('tile image', () {
    test('known columns are opaque, unloaded faint and forbidden hatched', () {
      final Uint8List rgba = terrainTileRgba(_tile(1, 0), (String id) => 0x808080);
      expect(rgba[3], 255);
      final Uint8List east = terrainTileRgba(_tile(2, 0), (String id) => 0x808080);
      expect(east[3], 70);
      final Uint8List far = terrainTileRgba(_tile(10, 10), (String id) => 0x808080);
      expect(far[3], 110);
      expect(far[0] != far[4 * 4], isTrue);
    });

    test('water is blue and darker when deeper', () {
      final TerrainSurfaceTile tile = _tile(0, -1);
      final Uint8List rgba = terrainTileRgba(tile, (String id) => 0x808080);
      const int pond = ((-8 + 64) * 64 + 10) * 4;
      expect(rgba[pond + 2] > rgba[pond], isTrue);
    });

    test('the mean colour weighs by alpha and applies the tint', () {
      final Uint8List rgba = Uint8List.fromList(<int>[200, 100, 0, 255, 0, 0, 0, 0]);
      expect(terrainMeanColor(rgba, 2, 0, 0, 2, 1, const <double>[1, 1, 1]), 0xC86400);
      expect(terrainMeanColor(rgba, 2, 0, 0, 2, 1, const <double>[0.5, 1, 1]), 0x646400);
      expect(terrainMeanColor(rgba, 2, 1, 0, 1, 1, const <double>[1, 1, 1]), isNull);
    });
  });

  group('client', () {
    test('a cached tile goes out with its hash and a 304 reuses it', () async {
      final List<ApiRequest> requests = <ApiRequest>[];
      Future<ApiResponse> send(ApiRequest request) async {
        requests.add(request);
        final FakeResponse response = fakeTerrainSurface(_get(request.path, request.headers), _anchors);
        return ApiResponse(response.status, response.body);
      }

      final MemoryTerrainCache cache = MemoryTerrainCache();
      final TerrainClient first = TerrainClient(server: 's1', send: send, cache: cache);
      final TerrainSurfaceTile? tile = await first.surface('world', 0, 0);
      expect(tile, isNotNull);
      expect(requests.single.headers.containsKey('If-None-Match'), isFalse);
      expect(await first.surface('world', 0, 0), isNotNull);
      expect(requests, hasLength(1));

      final TerrainClient next = TerrainClient(server: 's1', send: send, cache: cache);
      final TerrainSurfaceTile? again = await next.surface('world', 0, 0);
      expect(requests.last.headers['If-None-Match'], '"${tile!.hash}"');
      expect(next.unchanged, 1);
      expect(again!.hash, tile.hash);
    });

    test('at most [parallel] requests run at once', () async {
      int running = 0, peak = 0;
      Future<ApiResponse> send(ApiRequest request) async {
        running++;
        peak = math.max(peak, running);
        await Future<void>.delayed(const Duration(milliseconds: 2));
        running--;
        final FakeResponse response = fakeTerrainSurface(_get(request.path), _anchors);
        return ApiResponse(response.status, response.body);
      }

      final TerrainClient client = TerrainClient(server: 's', send: send, cache: MemoryTerrainCache(), parallel: 3);
      final List<TerrainSurfaceTile?> tiles = await Future.wait(<Future<TerrainSurfaceTile?>>[for (int i = 0; i < 9; i++) client.surface('world', i % 3, i ~/ 3)]);
      expect(tiles.whereType<TerrainSurfaceTile>(), hasLength(9));
      expect(peak, 3);
    });

    test('worlds the server does not list, or answered 404 for, are never requested', () async {
      final List<String> asked = <String>[];
      final TerrainClient client = TerrainClient(server: 's', send: (ApiRequest request) async {
        asked.add(request.path);
        return const ApiResponse(404, <String, Object?>{'error': 'unknown world'});
      }, cache: MemoryTerrainCache());
      expect(await client.slice('ghost', 0, 0, 0, 16), isNull);
      expect(await client.slice('ghost', 1, 0, 0, 16), isNull);
      expect(asked, hasLength(1));
      client.worlds = <String>{'world'};
      expect(await client.surface('nether', 0, 0), isNull);
      expect(asked, hasLength(1));
    });

    test('a failed request answers the cached copy, and a later call retries', () async {
      final MemoryTerrainCache cache = MemoryTerrainCache();
      final Map<String, Object?> body = _answer(fakeTerrainSurface(_get('/v1/terrain/surface?world=world&tx=0&tz=0'), _anchors));
      cache.entries['s|surface|world|0|0'] = jsonEncode(body);
      int calls = 0;
      final TerrainClient client = TerrainClient(server: 's', send: (ApiRequest request) async {
        calls++;
        throw StateError('offline');
      }, cache: cache);
      expect((await client.surface('world', 0, 0))!.hash, body['hash']);
      await client.surface('world', 0, 0);
      expect(calls, 2);
    });
  });
}

/// A flat floor at 64 with a wall of 68 from x 3 on.
final class _Wall implements TerrainLookup {
  @override
  TerrainColumn? column(int x, int z) => TerrainColumn(x >= 3 ? 68 : 64, 'minecraft:stone', 0);

  @override
  String? state(int x, int y, int z) => null;
}
