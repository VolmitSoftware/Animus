/// Where an NPC can stand, from the terrain the editor has: block states
/// near the stage when loaded, surface tiles elsewhere. Snapping puts a
/// placed point on a standable block; checks flag points inside blocks,
/// with no floor under them, or in water, and legs too steep to walk.
library;

import 'terrain_data.dart';

/// How far a walking NPC drops without the leg counting as blocked.
const int terrainMaxDrop = 3;

/// How far a point may hang over its floor before it counts as floating.
const int terrainMaxHover = 3;

const Set<String> _passableKeys = <String>{
  'air', 'cave_air', 'void_air', 'light', 'structure_void', 'short_grass', 'tall_grass', 'fern', 'large_fern', 'dead_bush', 'bush',
  'firefly_bush', 'short_dry_grass', 'tall_dry_grass', 'seagrass', 'tall_seagrass', 'kelp', 'kelp_plant', 'vine', 'glow_lichen',
  'hanging_roots', 'spore_blossom', 'sugar_cane', 'sweet_berry_bush', 'wheat', 'carrots', 'potatoes', 'beetroots', 'nether_wart',
  'torch', 'wall_torch', 'redstone_wire', 'rail', 'powered_rail', 'detector_rail', 'activator_rail', 'lever', 'tripwire', 'tripwire_hook',
  'cobweb', 'dandelion', 'poppy', 'blue_orchid', 'allium', 'azure_bluet', 'oxeye_daisy', 'cornflower', 'lily_of_the_valley',
  'wither_rose', 'torchflower', 'sunflower', 'lilac', 'rose_bush', 'peony', 'pink_petals', 'wildflowers', 'leaf_litter', 'brown_mushroom',
  'red_mushroom', 'crimson_fungus', 'warped_fungus', 'crimson_roots', 'warped_roots', 'nether_sprouts', 'weeping_vines',
  'weeping_vines_plant', 'twisting_vines', 'twisting_vines_plant', 'cave_vines', 'cave_vines_plant', 'pitcher_plant', 'snow',
  'closed_eyeblossom', 'open_eyeblossom', 'cactus_flower', 'fire', 'soul_fire', 'big_dripleaf_stem', 'small_dripleaf', 'scaffolding',
};

/// Ids ending like these have no collision.
const List<String> _passableSuffixes = <String>[
  '_sapling', '_tulip', '_sign', '_wall_sign', '_hanging_sign', '_banner', '_wall_banner', '_button', '_pressure_plate', '_torch',
  '_coral', '_coral_fan', '_coral_wall_fan', '_mushroom', '_propagule',
];

String _key(String id) {
  final int open = id.indexOf('[');
  final String bare = open < 0 ? id : id.substring(0, open);
  return bare.startsWith('minecraft:') ? bare.substring(10) : bare;
}

/// True for a block a body walks through: air, plants, torches, signs.
bool terrainPassable(String id) {
  final String key = _key(id);
  if (_passableKeys.contains(key)) return true;
  for (final String suffix in _passableSuffixes) {
    if (key.endsWith(suffix)) return true;
  }
  return false;
}

/// True for water, lava and bubble columns.
bool terrainFluid(String id) {
  final String key = _key(id);
  return key == 'water' || key == 'lava' || key == 'bubble_column';
}

/// True for a block that holds a body up.
bool terrainSolid(String id) => !terrainPassable(id) && !terrainFluid(id);

/// What the editor knows at a column or voxel. Either lookup may be null
/// when no data covers the place.
abstract interface class TerrainLookup {
  /// The surface column at [x], [z].
  TerrainColumn? column(int x, int z);

  /// The block state at [x], [y], [z].
  String? state(int x, int y, int z);
}

/// The feet y of a body standing on the surface of [column]: on top of the
/// top block, or in it when the top block is a plant; null when the
/// column is unknown.
int terrainSurfaceStand(TerrainColumn column) => terrainPassable(column.block) && !terrainFluid(column.block) ? column.height : column.height + 1;

/// The standable feet y at [x], [z] nearest [nearY]: a solid floor below,
/// room for the feet and the head. Block states win when they cover the
/// column; else the surface; null when nothing is known.
int? terrainSnapY(TerrainLookup terrain, int x, int z, int nearY) {
  int? best;
  for (int offset = 0; offset <= 24; offset++) {
    for (final int y in offset == 0 ? <int>[nearY] : <int>[nearY + offset, nearY - offset]) {
      final String? floor = terrain.state(x, y - 1, z), feet = terrain.state(x, y, z), head = terrain.state(x, y + 1, z);
      if (floor == null || feet == null) continue;
      if (terrainSolid(floor) && terrainPassable(feet) && (head == null || terrainPassable(head))) {
        best = y;
        break;
      }
    }
    if (best != null) return best;
  }
  final TerrainColumn? column = terrain.column(x, z);
  return column == null ? null : terrainSurfaceStand(column);
}

/// Why a point is not a place to stand.
enum TerrainIssue {
  inside('inside a block'),
  below('below the surface'),
  floating('no floor within $terrainMaxHover blocks'),
  water('in water');

  const TerrainIssue(this.text);

  final String text;
}

/// What is wrong at a point with feet at [x], [y], [z], or null when it
/// is fine or the terrain there is unknown.
TerrainIssue? terrainCheckPoint(TerrainLookup terrain, int x, int y, int z) {
  final String? feet = terrain.state(x, y, z);
  if (feet != null) {
    if (terrainSolid(feet)) return TerrainIssue.inside;
    if (terrainFluid(feet) || TerrainBlockState.parse(feet).waterlogged) return TerrainIssue.water;
    for (int drop = 1; drop <= terrainMaxHover + 1; drop++) {
      final String? below = terrain.state(x, y - drop, z);
      if (below == null) return null;
      if (terrainSolid(below)) return drop > terrainMaxHover ? TerrainIssue.floating : null;
      if (terrainFluid(below)) return TerrainIssue.water;
    }
    return TerrainIssue.floating;
  }
  final TerrainColumn? column = terrain.column(x, z);
  if (column == null) return null;
  final int stand = terrainSurfaceStand(column);
  if (column.water > 0 && y <= stand) return TerrainIssue.water;
  if (y < stand) return TerrainIssue.below;
  if (y > stand + terrainMaxHover) return TerrainIssue.floating;
  return null;
}

/// Where a straight walk from [ax], [az] to [bx], [bz] over the surface
/// climbs more than one block or drops more than [terrainMaxDrop]: the
/// column and the step, or null when every step is walkable or unknown.
(int, int, int)? terrainSteepStep(TerrainLookup terrain, int ax, int az, int bx, int bz) {
  final int steps = (bx - ax).abs() > (bz - az).abs() ? (bx - ax).abs() : (bz - az).abs();
  int? last;
  for (int i = 0; i <= steps; i++) {
    final double t = steps == 0 ? 0 : i / steps;
    final int x = (ax + (bx - ax) * t).round(), z = (az + (bz - az) * t).round();
    final TerrainColumn? column = terrain.column(x, z);
    if (column == null) {
      last = null;
      continue;
    }
    final int stand = terrainSurfaceStand(column);
    if (last != null && (stand - last > 1 || last - stand > terrainMaxDrop)) return (x, z, stand - last);
    last = stand;
  }
  return null;
}
