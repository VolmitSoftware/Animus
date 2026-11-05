/// Body rigs by Animus body type, their default textures, and the equipment
/// textures the client draws over them. Texture ids are jar paths under
/// `textures/` without `.png`; `tool/extract_minecraft_world_assets.dart`
/// copies exactly [mcEntityTextureIds] into the pack.
library;

import 'mc_dragon_rig.dart';
import 'mc_entity_rigs.dart';
import 'mc_player_rig.dart';
import 'mc_rig.dart';

/// The body types a transport presents (`BodyRules.TYPES`).
const List<String> mcBodyTypes = <String>[
  'minecraft:player',
  'minecraft:armor_stand',
  'minecraft:horse',
  'minecraft:turtle',
  'minecraft:frog',
  'minecraft:llama',
  'minecraft:armadillo',
  'minecraft:ender_dragon',
];

const String mcSteveTexture = 'entity/player/wide/steve';
const String mcAlexTexture = 'entity/player/slim/alex';

/// The body rig for [type], or null when Animus does not present that type.
McRig? mcBodyRig(String type, {bool slim = false, bool small = false}) => switch (type) {
  'minecraft:player' => mcPlayerRig(slim: slim),
  'minecraft:armor_stand' => small ? mcSmallTransform(mcArmorStandRig()) : mcArmorStandRig(),
  'minecraft:horse' => mcHorseRig(),
  'minecraft:turtle' => mcTurtleRig(),
  'minecraft:frog' => mcFrogRig(),
  'minecraft:llama' => mcLlamaRig(),
  'minecraft:armadillo' => mcArmadilloRig(),
  'minecraft:ender_dragon' => mcEnderDragonRig(),
  _ => null,
};

/// The texture a body wears when nothing else is chosen: the default variant
/// a freshly spawned entity of that type shows.
String? mcBodyTexture(String type, {bool slim = false}) => switch (type) {
  'minecraft:player' => slim ? mcAlexTexture : mcSteveTexture,
  'minecraft:armor_stand' => 'entity/armorstand/armorstand',
  'minecraft:horse' => 'entity/horse/horse_brown',
  'minecraft:turtle' => 'entity/turtle/turtle',
  'minecraft:frog' => 'entity/frog/frog_temperate',
  'minecraft:llama' => 'entity/llama/llama_creamy',
  'minecraft:armadillo' => 'entity/armadillo/armadillo',
  'minecraft:ender_dragon' => 'entity/enderdragon/dragon',
  _ => null,
};

/// Armour materials by item id prefix, as `assets/minecraft/equipment/*.json`
/// names their layer textures.
const Map<String, String> mcArmorMaterials = <String, String>{
  'leather': 'leather',
  'chainmail': 'chainmail',
  'iron': 'iron',
  'golden': 'gold',
  'diamond': 'diamond',
  'netherite': 'netherite',
  'copper': 'copper',
};

const List<String> mcDyeColors = <String>[
  'white', 'orange', 'magenta', 'light_blue', 'yellow', 'lime', 'pink', 'gray',
  'light_gray', 'cyan', 'purple', 'blue', 'brown', 'green', 'red', 'black',
];

/// Every texture the preview may bind, for the extraction tool.
final List<String> mcEntityTextureIds = <String>[
  for (final String type in mcBodyTypes) mcBodyTexture(type)!,
  mcAlexTexture,
  for (final String material in mcArmorMaterials.values) ...<String>[
    'entity/equipment/humanoid/$material',
    'entity/equipment/humanoid_leggings/$material',
  ],
  'entity/equipment/humanoid/leather_overlay',
  'entity/equipment/humanoid_leggings/leather_overlay',
  'entity/equipment/humanoid/turtle_scute',
  for (final String material in mcArmorMaterials.values)
    if (material != 'chainmail') 'entity/equipment/horse_body/$material',
  'entity/equipment/horse_body/leather_overlay',
  'entity/equipment/horse_saddle/saddle',
  for (final String color in mcDyeColors) 'entity/equipment/llama_body/$color',
];
