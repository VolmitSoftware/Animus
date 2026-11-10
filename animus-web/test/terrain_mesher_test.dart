@TestOn('vm')
library;

import 'dart:convert';

import 'package:animus_editor/logic/terrain/terrain_data.dart';
import 'package:animus_editor/logic/terrain/terrain_volume.dart';
import 'package:animus_editor/mc/assets/mc_model_resolver.dart';
import 'package:animus_editor/mc/assets/mc_model_store.dart';
import 'package:animus_editor/mc/mesh/mc_mesh.dart';
import 'package:animus_editor/mc/scene/mc_math.dart';
import 'package:animus_editor/mc/terrain/terrain_mesher.dart';
import 'package:animus_editor/mc/terrain/terrain_models.dart';
import 'package:test/test.dart';

Map<String, Object?> _face(String side) => <String, Object?>{'texture': '#all', 'cullface': side};

Map<String, Object?> _box(List<num> from, List<num> to) => <String, Object?>{
      'from': from,
      'to': to,
      'faces': <String, Object?>{for (final String side in <String>['down', 'up', 'north', 'south', 'west', 'east']) side: _face(side)},
    };

final Map<String, Object?> _models = <String, Object?>{
  'block/cube_all': <String, Object?>{
    'elements': <Object?>[
      _box(<num>[0, 0, 0], <num>[16, 16, 16]),
    ],
  },
  'block/stone': <String, Object?>{'parent': 'block/cube_all', 'textures': <String, Object?>{'all': 'block/stone'}},
  'block/glass': <String, Object?>{'parent': 'block/cube_all', 'textures': <String, Object?>{'all': 'block/glass'}},
  'block/slab': <String, Object?>{
    'textures': <String, Object?>{'all': 'block/stone'},
    'elements': <Object?>[
      _box(<num>[0, 0, 0], <num>[16, 8, 16]),
    ],
  },
  'block/slab_top': <String, Object?>{
    'textures': <String, Object?>{'all': 'block/stone'},
    'elements': <Object?>[
      _box(<num>[0, 8, 0], <num>[16, 16, 16]),
    ],
  },
  // A step: one face that culls against north only.
  'block/step': <String, Object?>{
    'textures': <String, Object?>{'all': 'block/stone'},
    'elements': <Object?>[
      <String, Object?>{
        'from': <num>[0, 0, 0],
        'to': <num>[16, 16, 8],
        'faces': <String, Object?>{'north': _face('north')},
      },
    ],
  },
  'block/post': <String, Object?>{
    'textures': <String, Object?>{'all': 'block/stone'},
    'elements': <Object?>[
      _box(<num>[6, 0, 6], <num>[10, 16, 10]),
    ],
  },
  'block/arm': <String, Object?>{
    'textures': <String, Object?>{'all': 'block/stone'},
    'elements': <Object?>[
      _box(<num>[7, 12, 0], <num>[9, 15, 9]),
    ],
  },
  'block/water': <String, Object?>{'textures': <String, Object?>{'particle': 'block/water_still'}},
};

final Map<String, Object?> _states = <String, Object?>{
  'stone': <String, Object?>{'variants': <String, Object?>{'': <String, Object?>{'model': 'block/stone'}}},
  'glass': <String, Object?>{'variants': <String, Object?>{'': <String, Object?>{'model': 'block/glass'}}},
  'water': <String, Object?>{'variants': <String, Object?>{'': <String, Object?>{'model': 'block/water'}}},
  'stone_slab': <String, Object?>{
    'variants': <String, Object?>{
      'type=bottom': <String, Object?>{'model': 'block/slab'},
      'type=top': <String, Object?>{'model': 'block/slab_top'},
      'type=double': <String, Object?>{'model': 'block/stone'},
    },
  },
  'step': <String, Object?>{
    'variants': <String, Object?>{
      'facing=north': <String, Object?>{'model': 'block/step'},
      'facing=east': <String, Object?>{'model': 'block/step', 'y': 90},
    },
  },
  'fence': <String, Object?>{
    'multipart': <Object?>[
      <String, Object?>{'apply': <String, Object?>{'model': 'block/post'}},
      <String, Object?>{'when': <String, Object?>{'north': 'true'}, 'apply': <String, Object?>{'model': 'block/arm'}},
      <String, Object?>{
        'when': <String, Object?>{
          'OR': <Object?>[
            <String, Object?>{'east': 'true'},
            <String, Object?>{'west': 'true'},
          ],
        },
        'apply': <String, Object?>{'model': 'block/arm', 'y': 90},
      },
    ],
  },
};

TerrainModels _models0() => TerrainModels(
      McModelResolver(McJsonModelStore.fromJson(<String, Object?>{'models': _models, 'items': <String, Object?>{}, 'blockstates': <String, Object?>{}})),
      uv: (String id) => const McUvRect(0, 0, 1, 1),
      blockstates: _states,
    );

/// A volume of the given cells at x 0.., y 0, z 0.
TerrainVolume _row(List<String> states, {int sy = 1}) {
  final TerrainSlice slice = TerrainSlice.fromJson(<String, Object?>{
    'world': 'w',
    'cx': 0,
    'cz': 0,
    'y': 0,
    'height': sy,
    'status': 'ok',
    'hash': 'h',
    'palette': <String>['minecraft:air', ...states.toSet()],
    'bits': 4,
    'data': '',
  }.._encode(states, sy))!;
  return TerrainVolume.of(0, 0, 0, 16, sy, 16, <TerrainSlice>[slice]);
}

extension on Map<String, Object?> {
  void _encode(List<String> states, int sy) {
    final List<String> palette = (this['palette']! as List<String>);
    final List<int> indices = List<int>.filled(256 * sy, 0);
    for (int i = 0; i < states.length; i++) {
      final int y = i ~/ 16, x = i % 16;
      indices[(y * 16) * 16 + x] = palette.indexOf(states[i]);
    }
    this['data'] = _base64(terrainPack(indices, 4));
  }
}

String _base64(List<int> bytes) => const Base64Codec().encode(bytes);

int _quads(List<TerrainMesh> meshes, {bool? translucent}) => meshes
    .where((TerrainMesh mesh) => translucent == null || mesh.translucent == translucent)
    .fold(0, (int sum, TerrainMesh mesh) => sum + mesh.mesh.indices.length ~/ 6);

List<TerrainMesh> _mesh(TerrainVolume volume) =>
    terrainMesh(volume, _models0(), origin: McVec3.zero, water: const McUvRect(0, 0, 1, 1), lava: const McUvRect(0, 0, 1, 1));

void main() {
  test('two stones hide the face between them', () {
    expect(_quads(_mesh(_row(<String>['minecraft:stone']))), 6);
    expect(_quads(_mesh(_row(<String>['minecraft:stone', 'minecraft:stone']))), 10);
  });

  test('glass hides glass but not stone', () {
    expect(_quads(_mesh(_row(<String>['minecraft:glass', 'minecraft:glass']))), 10);
    // The stone keeps its face toward the glass; the glass loses its face toward the stone.
    expect(_quads(_mesh(_row(<String>['minecraft:stone', 'minecraft:glass']))), 11);
  });

  test('a slab picks its variant by type and never hides a neighbour', () {
    final List<TerrainMesh> bottom = _mesh(_row(<String>['minecraft:stone_slab[type=bottom]']));
    double top(List<TerrainMesh> meshes) {
      double most = 0;
      for (final TerrainMesh mesh in meshes) {
        for (int i = 1; i < mesh.mesh.positions.length; i += 3) {
          if (mesh.mesh.positions[i] > most) most = mesh.mesh.positions[i];
        }
      }
      return most;
    }

    expect(top(bottom), 0.5);
    expect(top(_mesh(_row(<String>['minecraft:stone_slab[type=top]']))), 1);
    expect(_quads(_mesh(_row(<String>['minecraft:stone_slab[type=bottom]', 'minecraft:stone']))), 11);
    expect(_quads(_mesh(_row(<String>['minecraft:stone_slab[type=double]', 'minecraft:stone']))), 10);
  });

  test('a state that leaves properties out takes their default values', () {
    double top(String state) {
      double most = 0;
      for (final TerrainMesh mesh in _mesh(_row(<String>[state]))) {
        for (int i = 1; i < mesh.mesh.positions.length; i += 3) {
          if (mesh.mesh.positions[i] > most) most = mesh.mesh.positions[i];
        }
      }
      return most;
    }

    expect(top('minecraft:stone_slab'), 0.5);
    expect(top('minecraft:stone_slab[type=top]'), 1);
  });

  test('a turned variant culls against its turned side', () {
    final TerrainModels models = _models0();
    expect(models.of('minecraft:step[facing=north]').quads.single.cull, 2);
    expect(models.of('minecraft:step[facing=east]').quads.single.cull, 5);
  });

  test('multipart applies every part whose condition holds', () {
    final TerrainModels models = _models0();
    expect(models.of('minecraft:fence[east=false,north=false,west=false]').quads, hasLength(6));
    expect(models.of('minecraft:fence[east=false,north=true,west=false]').quads, hasLength(12));
    expect(models.of('minecraft:fence[east=true,north=true,west=false]').quads, hasLength(18));
    expect(models.of('minecraft:fence[east=true]').occludes, isFalse);
  });

  test('water draws a lowered top, blended, and none between water blocks', () {
    final List<TerrainMesh> one = _mesh(_row(<String>['minecraft:water[level=0]']));
    expect(_quads(one, translucent: true), 6);
    expect(_quads(one, translucent: false), 0);
    double top = 0;
    for (int i = 1; i < one.first.mesh.positions.length; i += 3) {
      if (one.first.mesh.positions[i] > top) top = one.first.mesh.positions[i];
    }
    expect(top, terrainFluidTop);
    expect(_quads(_mesh(_row(<String>['minecraft:water[level=0]', 'minecraft:water[level=0]'])), translucent: true), 10);
  });

  test('positions are relative to the origin', () {
    final List<TerrainMesh> meshes = terrainMesh(_row(<String>['minecraft:stone']), _models0(),
        origin: const McVec3(0.5, 1, 0.5), water: const McUvRect(0, 0, 1, 1), lava: const McUvRect(0, 0, 1, 1));
    final List<double> xs = <double>[for (int i = 0; i < meshes.first.mesh.positions.length; i += 3) meshes.first.mesh.positions[i]];
    expect(xs.reduce((double a, double b) => a < b ? a : b), -0.5);
    expect(xs.reduce((double a, double b) => a > b ? a : b), 0.5);
  });

  test('without full blockstates the default variant stands in', () {
    final TerrainModels models = TerrainModels(
      McModelResolver(McJsonModelStore.fromJson(<String, Object?>{'models': _models, 'items': <String, Object?>{}, 'blockstates': _states})),
      uv: (String id) => const McUvRect(0, 0, 1, 1),
    );
    expect(models.of('minecraft:stone').occludes, isTrue);
    expect(models.of('minecraft:air').quads, isEmpty);
  });
}
