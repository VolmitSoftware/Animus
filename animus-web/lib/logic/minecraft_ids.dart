/// Minecraft registry ids (items, blocks, sounds, particles, entity types,
/// effects, attributes, biomes, dimensions), read as core's `MinecraftIds`
/// reads them: a bare id with no colon means `minecraft:<id>`.
library;

const int minecraftIdMaxLength = 128;

/// A registry id with an optional namespace, the form the schemas accept.
const String minecraftIdPattern = r'([a-z0-9_.-]+:)?[a-z0-9_./-]+';

final RegExp _form = RegExp('^$minecraftIdPattern\$');

/// Trimmed, lower-case and namespaced: `Netherite_Axe` becomes
/// `minecraft:netherite_axe`. Blank gives an empty string.
String normalizeMinecraftId(String? id) {
  final String trimmed = (id ?? '').trim().toLowerCase();
  if (trimmed.isEmpty || trimmed.contains(':')) return trimmed;
  return 'minecraft:$trimmed';
}

/// Whether the id as written is lower-case `[namespace:]path` and at most
/// [minecraftIdMaxLength] characters once namespaced.
bool validMinecraftId(String? id) =>
    id != null && _form.hasMatch(id) && normalizeMinecraftId(id).length <= minecraftIdMaxLength;
