/// Body rigs by Animus body type, their default textures, and the equipment
/// textures the client draws over them. Texture ids are jar paths under
/// `textures/` without `.png`; `tool/extract_minecraft_world_assets.dart`
/// copies exactly [mcEntityTextureIds] into the pack.
library;

import 'mc_dragon_rig.dart';
import 'mc_entity_rigs.dart';
import 'mc_player_rig.dart';
import 'mc_rig.dart';

/// The body types a transport presents that the preview draws.
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

/// Presented body types the preview has no model for: the rest of
/// `BodyRules.TYPES`, every other living entity and every body with settings
/// of its own.
const List<String> mcPlainBodyTypes = <String>[
  'minecraft:allay', 'minecraft:axolotl', 'minecraft:bat', 'minecraft:bee', 'minecraft:blaze', 'minecraft:block_display', //
  'minecraft:bogged', 'minecraft:breeze', 'minecraft:camel', 'minecraft:camel_husk', 'minecraft:cat', 'minecraft:cave_spider', //
  'minecraft:chicken', 'minecraft:cod', 'minecraft:copper_golem', 'minecraft:cow', 'minecraft:creaking', 'minecraft:creeper', //
  'minecraft:dolphin', 'minecraft:donkey', 'minecraft:drowned', 'minecraft:elder_guardian', 'minecraft:end_crystal', 'minecraft:enderman', //
  'minecraft:endermite', 'minecraft:evoker', 'minecraft:falling_block', 'minecraft:fox', 'minecraft:ghast', 'minecraft:giant', //
  'minecraft:glow_item_frame', 'minecraft:glow_squid', 'minecraft:goat', 'minecraft:guardian', 'minecraft:happy_ghast', 'minecraft:hoglin', //
  'minecraft:husk', 'minecraft:illusioner', 'minecraft:iron_golem', 'minecraft:item', 'minecraft:item_display', 'minecraft:item_frame', //
  'minecraft:magma_cube', 'minecraft:mannequin', 'minecraft:mooshroom', 'minecraft:mule', 'minecraft:nautilus', 'minecraft:ocelot', //
  'minecraft:painting', 'minecraft:panda', 'minecraft:parched', 'minecraft:parrot', 'minecraft:phantom', 'minecraft:pig', //
  'minecraft:piglin', 'minecraft:piglin_brute', 'minecraft:pillager', 'minecraft:polar_bear', 'minecraft:pufferfish', 'minecraft:rabbit', //
  'minecraft:ravager', 'minecraft:salmon', 'minecraft:sheep', 'minecraft:shulker', 'minecraft:silverfish', 'minecraft:skeleton', //
  'minecraft:skeleton_horse', 'minecraft:slime', 'minecraft:sniffer', 'minecraft:snow_golem', 'minecraft:spider', 'minecraft:squid', //
  'minecraft:stray', 'minecraft:strider', 'minecraft:sulfur_cube', 'minecraft:tadpole', 'minecraft:text_display', 'minecraft:trader_llama', //
  'minecraft:tropical_fish', 'minecraft:vex', 'minecraft:villager', 'minecraft:vindicator', 'minecraft:wandering_trader', //
  'minecraft:warden', 'minecraft:witch', 'minecraft:wither', 'minecraft:wither_skeleton', 'minecraft:wolf', 'minecraft:zoglin', //
  'minecraft:zombie', 'minecraft:zombie_horse', 'minecraft:zombie_nautilus', 'minecraft:zombie_villager', 'minecraft:zombified_piglin',
];

/// Every body type a transport presents, those with a preview model first.
const List<String> mcPresentedBodyTypes = <String>[...mcBodyTypes, ...mcPlainBodyTypes];

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
  'minecraft:horse' => 'entity/horse/horse_white',
  'minecraft:turtle' => 'entity/turtle/turtle',
  'minecraft:frog' => 'entity/frog/frog_temperate',
  'minecraft:llama' => 'entity/llama/llama_creamy',
  'minecraft:armadillo' => 'entity/armadillo/armadillo',
  'minecraft:ender_dragon' => 'entity/enderdragon/dragon',
  _ => null,
};

/// The client's coat, wool and skin choices in `BodyVariant` order; the
/// first is what a body shows with the setting left out.
const List<String> mcHorseColors = <String>['white', 'creamy', 'chestnut', 'brown', 'black', 'gray', 'dark_brown'];
const List<String> mcHorseMarkings = <String>['none', 'white', 'white_field', 'white_dots', 'black_dots'];
const List<String> mcLlamaVariants = <String>['creamy', 'white', 'brown', 'gray'];
const List<String> mcFrogVariants = <String>['temperate', 'warm', 'cold'];

String _choice(Map<String, Object?> settings, String key, List<String> values) => switch (settings[key]) {
      final String value when values.contains(value) => value,
      _ => values.first,
    };

/// The texture a body wears for its own settings (`horse.color`,
/// `llama.variant`, `frog.variant`), else [mcBodyTexture].
String? mcSettingsTexture(String type, Map<String, Object?> settings) => switch (type) {
      'minecraft:horse' => 'entity/horse/horse_${_choice(settings, 'color', mcHorseColors).replaceAll('_', '')}',
      'minecraft:llama' => 'entity/llama/llama_${_choice(settings, 'variant', mcLlamaVariants)}',
      'minecraft:frog' => 'entity/frog/frog_${_choice(settings, 'variant', mcFrogVariants)}',
      _ => mcBodyTexture(type),
    };

/// The markings layer over a horse's coat, or null for none.
String? mcHorseMarkingsTexture(Map<String, Object?> settings) {
  final String markings = _choice(settings, 'markings', mcHorseMarkings);
  return markings == 'none' ? null : 'entity/horse/horse_markings_${markings.replaceAll('_', '')}';
}

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
  for (final String color in mcHorseColors.skip(1)) 'entity/horse/horse_${color.replaceAll('_', '')}',
  for (final String markings in mcHorseMarkings.skip(1)) 'entity/horse/horse_markings_${markings.replaceAll('_', '')}',
  for (final String variant in mcLlamaVariants.skip(1)) 'entity/llama/llama_$variant',
  for (final String variant in mcFrogVariants.skip(1)) 'entity/frog/frog_$variant',
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
