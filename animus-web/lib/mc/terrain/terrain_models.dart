/// Block states to baked quads, the way the client picks a blockstate
/// variant: the `variants` entry whose properties all match (the first of
/// a weighted list), or every `multipart` entry whose `when` holds. Each
/// state is baked once into quads in block space (0..1), rotated by the
/// variant, each tagged with the side it is culled against. Without the
/// full blockstate file the default variant stands in. `uvlock` is not
/// applied, so a turned stair keeps its texture turned with it.
library;

import '../../logic/terrain/terrain_data.dart';
import '../assets/mc_model_json.dart';
import '../assets/mc_model_resolver.dart';
import '../assets/mc_tint.dart';
import '../mesh/mc_element_mesher.dart';
import '../mesh/mc_mesh.dart';
import '../scene/mc_math.dart';

/// Sides in the order the mesher steps to a neighbour: down, up, north,
/// south, west, east.
const List<(int, int, int)> terrainSides = <(int, int, int)>[(0, -1, 0), (0, 1, 0), (0, 0, -1), (0, 0, 1), (-1, 0, 0), (1, 0, 0)];

const Map<String, int> _sideIndex = <String, int>{'down': 0, 'up': 1, 'north': 2, 'south': 3, 'west': 4, 'east': 5};

/// One baked quad: four corners and UVs in winding order, its shade and
/// tint, and the side it is hidden by ([TerrainQuad.noCull] for never).
final class TerrainQuad {
  const TerrainQuad(this.corners, this.uvs, this.shade, this.tint, this.cull);

  static const int noCull = -1;

  /// x, y, z for each of the four corners, block space.
  final List<double> corners;
  final List<double> uvs;
  final double shade;
  final List<double> tint;
  final int cull;
}

/// What the mesher draws for one block state.
final class TerrainBlockModel {
  const TerrainBlockModel({required this.quads, required this.occludes, required this.fluid, required this.translucent, required this.sameCulls});

  static const TerrainBlockModel air = TerrainBlockModel(quads: <TerrainQuad>[], occludes: false, fluid: null, translucent: false, sameCulls: false);

  final List<TerrainQuad> quads;

  /// A full opaque cube: the faces of neighbours against it are hidden.
  final bool occludes;

  /// `water` or `lava` when the state is (or holds) that fluid.
  final String? fluid;

  /// Drawn in the blended pass: stained glass, ice, slime and honey.
  final bool translucent;

  /// Glass-like: faces between two blocks of the same id are hidden.
  final bool sameCulls;
}

bool _matches(Map<String, String> properties, String key) {
  if (key.isEmpty) return true;
  for (final String pair in key.split(',')) {
    final int equals = pair.indexOf('=');
    if (equals < 0) continue;
    if (properties[pair.substring(0, equals)] != pair.substring(equals + 1)) return false;
  }
  return true;
}

/// Property values a block takes by default whenever it has them.
const Set<String> _defaultValues = <String>{'bottom', 'straight', 'false', 'none', 'floor', 'lower', 'single', 'y', '0'};

/// For a state written with only some properties (`oak_stairs[facing=east]`,
/// as a definition writes one): the variant agreeing with every property
/// given that takes the most default values for the rest.
MapEntry<String, Object?>? _closest(Map<String, Object?> variants, Map<String, String> properties) {
  MapEntry<String, Object?>? best;
  int bestScore = -1;
  for (final MapEntry<String, Object?> entry in variants.entries) {
    int score = 0;
    bool agrees = true;
    for (final String pair in entry.key.split(',')) {
      final int equals = pair.indexOf('=');
      if (equals < 0) continue;
      final String? given = properties[pair.substring(0, equals)];
      final String value = pair.substring(equals + 1);
      if (given == null) {
        if (_defaultValues.contains(value)) score++;
      } else if (given != value) {
        agrees = false;
        break;
      }
    }
    if (agrees && score > bestScore) {
      best = entry;
      bestScore = score;
    }
  }
  return best;
}

bool _when(Map<String, String> properties, Object? condition) {
  if (condition is! Map<String, Object?>) return true;
  final Object? any = condition['OR'], all = condition['AND'];
  if (any is List<Object?>) return any.any((Object? part) => _when(properties, part));
  if (all is List<Object?>) return all.every((Object? part) => _when(properties, part));
  for (final MapEntry<String, Object?> entry in condition.entries) {
    final String actual = properties[entry.key] ?? '';
    if (!'${entry.value}'.split('|').contains(actual)) return false;
  }
  return true;
}

McBlockstateVariantJson? _first(Object? apply) {
  final Object? value = apply is List<Object?> ? (apply.isEmpty ? null : apply.first) : apply;
  if (value is! Map<String, Object?> || value['model'] is! String) return null;
  return McBlockstateVariantJson.fromJson(value);
}

/// The variants [state] draws with, from its full blockstate JSON.
List<McBlockstateVariantJson> terrainVariants(Map<String, Object?> blockstate, TerrainBlockState state) {
  final Object? variants = blockstate['variants'];
  if (variants is Map<String, Object?>) {
    MapEntry<String, Object?>? found;
    for (final MapEntry<String, Object?> entry in variants.entries) {
      if (_matches(state.properties, entry.key)) {
        found = entry;
        break;
      }
    }
    found ??= _closest(variants, state.properties);
    final McBlockstateVariantJson? variant = found == null ? null : _first(found.value);
    return variant == null ? const <McBlockstateVariantJson>[] : <McBlockstateVariantJson>[variant];
  }
  final Object? parts = blockstate['multipart'];
  if (parts is! List<Object?>) return const <McBlockstateVariantJson>[];
  return <McBlockstateVariantJson>[
    for (final Object? part in parts)
      if (part is Map<String, Object?> && _when(state.properties, part['when'])) ?_first(part['apply']),
  ];
}

const Set<String> _translucentKeys = <String>{'ice', 'slime_block', 'honey_block', 'tinted_glass'};

bool _isTranslucent(String key) => _translucentKeys.contains(key) || key.contains('stained_glass');

bool _isSameCulling(String key) => key == 'glass' || key == 'glass_pane' || key == 'ice' || key.endsWith('stained_glass') || key.endsWith('stained_glass_pane') || key == 'tinted_glass';

/// Cutout or see-through blocks that never hide a neighbour.
bool _seeThrough(String key) => _isSameCulling(key) || _isTranslucent(key) || key.endsWith('_leaves') || key == 'spawner' || key == 'barrier';

int _sideOf(McVec3 normal) {
  if (normal.y < -0.5) return 0;
  if (normal.y > 0.5) return 1;
  if (normal.z < -0.5) return 2;
  if (normal.z > 0.5) return 3;
  if (normal.x < -0.5) return 4;
  return 5;
}

double _shadeOf(int side) => switch (side) {
      0 => mcShadeDown,
      1 => mcShadeUp,
      2 || 3 => mcShadeNorthSouth,
      _ => mcShadeEastWest,
    };

/// Bakes block states with the pack's models; one bake per state string.
final class TerrainModels {
  TerrainModels(this.resolver, {required this.uv, this.blockstates});

  final McModelResolver resolver;
  final McUvLookup uv;

  /// Full blockstate files by key (`oak_stairs`), or null when the pack has
  /// none and default variants stand in.
  final Map<String, Object?>? blockstates;

  final Map<String, TerrainBlockModel> _baked = <String, TerrainBlockModel>{};

  TerrainBlockModel of(String stateText) => _baked.putIfAbsent(stateText, () => _bake(TerrainBlockState.parse(stateText)));

  TerrainBlockModel _bake(TerrainBlockState state) {
    final String key = state.key;
    if (key == 'air' || key == 'cave_air' || key == 'void_air') return TerrainBlockModel.air;
    final String? fluid = key == 'water' || key == 'bubble_column' || key == 'kelp' || key == 'kelp_plant' || key == 'seagrass' || key == 'tall_seagrass' || state.waterlogged
        ? 'water'
        : (key == 'lava' ? 'lava' : null);
    final Object? full = blockstates?[key];
    final List<McBlockstateVariantJson> variants = full is Map<String, Object?>
        ? terrainVariants(full, state)
        : <McBlockstateVariantJson>[?resolver.blockVariant(key)];
    final List<TerrainQuad> quads = <TerrainQuad>[];
    bool fullCube = false;
    for (final McBlockstateVariantJson variant in variants) {
      final McResolvedModel? model = resolver.resolveModel(variant.model);
      if (model == null) continue;
      final McMat4 turn = McMat4.translation(0.5, 0.5, 0.5)
          .multiply(McMat4.rotationY(-variant.y.toDouble()))
          .multiply(McMat4.rotationX(-variant.x.toDouble()))
          .multiply(McMat4.translation(-0.5, -0.5, -0.5));
      for (final McResolvedElement element in model.elements) {
        if (element.rotation == null && element.from.every((double v) => v <= 0) && element.to.every((double v) => v >= 16)) fullCube = true;
        for (final MapEntry<String, McResolvedFace> face in element.faces.entries) {
          quads.add(_quad(element, face.key, face.value, turn, key));
        }
      }
    }
    return TerrainBlockModel(
      quads: quads,
      occludes: fullCube && fluid == null && !_seeThrough(key),
      fluid: fluid,
      translucent: _isTranslucent(key),
      sameCulls: _isSameCulling(key),
    );
  }

  TerrainQuad _quad(McResolvedElement element, String face, McResolvedFace resolved, McMat4 turn, String key) {
    final McMeshBuilder builder = McMeshBuilder();
    mcAppendElement(builder, element, uv: uv, tint: (int? index) => mcTintForBlock(key, index), cull: (String other) => other != face);
    final McMesh mesh = builder.build();
    final List<double> corners = <double>[];
    for (int i = 0; i < 4; i++) {
      final McVec3 p = turn.transformPoint(McVec3(mesh.positions[i * 3], mesh.positions[i * 3 + 1], mesh.positions[i * 3 + 2]));
      corners.addAll(<double>[p.x, p.y, p.z]);
    }
    final McVec3 a = McVec3(corners[0], corners[1], corners[2]), b = McVec3(corners[3], corners[4], corners[5]), c = McVec3(corners[6], corners[7], corners[8]);
    // Corners wind clockwise from outside, so this cross points inward.
    final McVec3 inward = (b - a).cross(c - a);
    final int side = _sideOf(-inward);
    final int cull = resolved.cullface == null ? TerrainQuad.noCull : _sideOf(_rotated(turn, _sideIndex[resolved.cullface] ?? 1));
    return TerrainQuad(corners, mesh.uvs.toList(), element.shade ? _shadeOf(side) : 1, mesh.tints.sublist(0, 3).toList(), cull);
  }

  static McVec3 _rotated(McMat4 turn, int side) {
    final (int dx, int dy, int dz) = terrainSides[side];
    final McVec3 centre = turn.transformPoint(const McVec3(0.5, 0.5, 0.5));
    return turn.transformPoint(McVec3(0.5 + dx, 0.5 + dy, 0.5 + dz)) - centre;
  }
}
