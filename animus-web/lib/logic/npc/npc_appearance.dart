/// Reads and writes the appearance fields of an NPC definition as
/// `NpcAppearance`, `ArmorStandLook` and `NpcInventory` read them: the body
/// pose, hologram lines, armor stand angles, the stored inventory and the
/// owner.
library;

import '../gui/gui_json.dart';
import '../inspect/named_ids.dart' show idName;

/// Body traits drawn as plain schema fields.
const List<String> npcTraits = <String>['glowing', 'teamColor', 'baby', 'usingItem', 'listed', 'gravity'];

/// The player a click on the NPC is passed on to.
const String clickRedirectField = 'clickRedirect';

/// Every body pose; only standing applies to bodies other than players.
const List<String> npcPoses = <String>['standing', 'sneaking', 'sleeping', 'swimming', 'sitting'];

/// Armor stand parts that take `[x, y, z]` degrees.
const List<String> armorStandParts = <String>['head', 'body', 'leftArm', 'rightArm', 'leftLeg', 'rightLeg'];

/// Inventory sizes: whole chest rows of 9, up to 72 slots.
const List<int> inventorySizes = <int>[9, 18, 27, 36, 45, 54, 63, 72];

/// Hologram lines a definition may declare.
const int hologramMaxLines = 16;

final RegExp _owner = RegExp(r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$');

/// A body type as people read it: `minecraft:iron_golem` reads "Iron golem",
/// and another namespace follows in brackets ("Dragon (mymod)").
String bodyLabel(String type) => idName(type);

/// The poses a body of [type] may take.
List<String> posesFor(String type) => type == 'minecraft:player' ? npcPoses : const <String>['standing'];

/// The body traits a body of [type] shows, in the inspector's order: the
/// baby flag only where the type has a baby form, the tab list and a raised
/// item only on players.
List<String> traitsFor(String type) => <String>[
      'glowing',
      'teamColor',
      if (_babies.contains(type)) 'baby',
      if (type == 'minecraft:player') ...<String>['usingItem', 'listed'],
      'gravity',
    ];

/// Presented bodies with a baby form (`BodyRules.babyFlag`).
const Set<String> _babies = <String>{
  'minecraft:horse', 'minecraft:turtle', 'minecraft:frog', 'minecraft:llama', 'minecraft:armadillo', 'minecraft:sheep', 'minecraft:wolf', //
  'minecraft:cat', 'minecraft:parrot', 'minecraft:fox', 'minecraft:axolotl', 'minecraft:rabbit', 'minecraft:villager', 'minecraft:zombie_villager', //
  'minecraft:mooshroom', 'minecraft:panda', 'minecraft:goat', 'minecraft:pig', 'minecraft:cow', 'minecraft:chicken', 'minecraft:polar_bear', //
  'minecraft:piglin', 'minecraft:bee', 'minecraft:sniffer', 'minecraft:donkey', 'minecraft:mule', 'minecraft:camel',
};

/// A definition field a body of another type cannot show, and why.
final class MisplacedField {
  const MisplacedField(this.field, this.reason);

  final String field;
  final String reason;
}

/// Fields that only another body type shows: what is left behind when an
/// NPC changes type. The server refuses each of them on this body.
List<MisplacedField> misplacedBodyFields(JsonMap doc, String type) => <MisplacedField>[
      if (doc.containsKey('skin') && type != 'minecraft:player') const MisplacedField('skin', 'Skins show only on player bodies.'),
      if (doc['pose'] != null && doc['pose'] != 'standing' && type != 'minecraft:player')
        MisplacedField('pose', 'The ${asString(doc['pose']) ?? 'pose'} pose shows only on player bodies.'),
      if (doc.containsKey('armorStand') && type != 'minecraft:armor_stand') const MisplacedField('armorStand', 'Only for armor stand bodies.'),
      for (final MapEntry<String, String> settings in bodySettingsTypes.entries)
        if (doc.containsKey(settings.key) && settings.value != type) MisplacedField(settings.key, 'Only for ${bodyLabel(settings.value).toLowerCase()} bodies.'),
    ];

/// [doc] without the fields [misplacedBodyFields] names.
JsonMap withoutMisplacedBodyFields(JsonMap doc, String type) {
  final Set<String> drop = <String>{for (final MisplacedField field in misplacedBodyFields(doc, type)) field.field};
  return <String, Object?>{
    for (final MapEntry<String, Object?> entry in doc.entries)
      if (!drop.contains(entry.key)) entry.key: entry.value,
  };
}

/// The definition fields holding one body type's own settings
/// (`BodyVariant.FIELDS`), with that body type.
const Map<String, String> bodySettingsTypes = <String, String>{
  'horse': 'minecraft:horse',
  'llama': 'minecraft:llama',
  'frog': 'minecraft:frog',
  'armadillo': 'minecraft:armadillo',
  'dragon': 'minecraft:ender_dragon',
  'player': 'minecraft:player',
  'sheep': 'minecraft:sheep',
  'wolf': 'minecraft:wolf',
  'cat': 'minecraft:cat',
  'parrot': 'minecraft:parrot',
  'fox': 'minecraft:fox',
  'axolotl': 'minecraft:axolotl',
  'rabbit': 'minecraft:rabbit',
  'villager': 'minecraft:villager',
  'zombieVillager': 'minecraft:zombie_villager',
  'creeper': 'minecraft:creeper',
  'slime': 'minecraft:slime',
  'magmaCube': 'minecraft:magma_cube',
  'phantom': 'minecraft:phantom',
  'mooshroom': 'minecraft:mooshroom',
  'panda': 'minecraft:panda',
  'goat': 'minecraft:goat',
  'pig': 'minecraft:pig',
  'cow': 'minecraft:cow',
  'chicken': 'minecraft:chicken',
  'shulker': 'minecraft:shulker',
  'pufferfish': 'minecraft:pufferfish',
  'tropicalFish': 'minecraft:tropical_fish',
  'snowGolem': 'minecraft:snow_golem',
  'polarBear': 'minecraft:polar_bear',
  'piglin': 'minecraft:piglin',
  'allay': 'minecraft:allay',
  'bee': 'minecraft:bee',
  'vex': 'minecraft:vex',
  'evoker': 'minecraft:evoker',
  'sniffer': 'minecraft:sniffer',
  'bat': 'minecraft:bat',
  'copperGolem': 'minecraft:copper_golem',
  'donkey': 'minecraft:donkey',
  'mule': 'minecraft:mule',
  'enderman': 'minecraft:enderman',
  'wither': 'minecraft:wither',
  'camel': 'minecraft:camel',
  'painting': 'minecraft:painting',
  'itemFrame': 'minecraft:item_frame',
  'glowItemFrame': 'minecraft:glow_item_frame',
  'fallingBlock': 'minecraft:falling_block',
  'endCrystal': 'minecraft:end_crystal',
  'blockDisplay': 'minecraft:block_display',
  'itemDisplay': 'minecraft:item_display',
  'textDisplay': 'minecraft:text_display',
  'droppedItem': 'minecraft:item',
};

/// The field holding [type]'s own settings, or null for bodies without any.
String? bodySettingsField(String type) {
  for (final MapEntry<String, String> entry in bodySettingsTypes.entries) {
    if (entry.value == type) return entry.key;
  }
  return null;
}

/// The text of one `hologram.lines` entry: a string, or an object's `text`.
String hologramLineText(Object? line) => line is String ? line : (asString(asMap(line)['text']) ?? '');

/// [lines] with entry [index]'s text replaced, keeping an object entry's margins.
List<Object?> setHologramLineText(List<Object?> lines, int index, String text) {
  final List<Object?> next = List<Object?>.of(lines);
  if (index < 0 || index >= next.length) return next;
  final Object? line = next[index];
  next[index] = line is Map<String, Object?> ? <String, Object?>{...line, 'text': text} : text;
  return next;
}

/// The `[x, y, z]` degrees of armor stand [part], or null when undeclared.
List<double>? partAngles(JsonMap stand, String part) {
  final List<Object?> values = asList(stand[part]);
  if (values.length != 3) return null;
  final List<double> angles = <double>[for (final Object? value in values) asDouble(value) ?? 0];
  return angles;
}

/// [stand] with axis [axis] of [part] set; a part back at 0, 0, 0 is removed
/// so the client default applies.
JsonMap setPartAngle(JsonMap stand, String part, int axis, num value) {
  final List<num> angles = <num>[
    for (final double angle in partAngles(stand, part) ?? const <double>[0, 0, 0]) _whole(angle),
  ];
  angles[axis] = _whole(value.toDouble());
  final JsonMap next = <String, Object?>{...stand};
  if (angles.every((num angle) => angle == 0)) {
    next.remove(part);
  } else {
    next[part] = angles;
  }
  return next;
}

num _whole(double value) => value == value.roundToDouble() ? value.toInt() : value;

/// Slot numbers of [inventory] at or past its size, which the server refuses.
List<int> slotsOutside(JsonMap inventory) {
  final int size = asInt(inventory['size']) ?? 0;
  final List<int> outside = <int>[
    for (final String key in asMap(inventory['slots']).keys)
      if (int.tryParse(key) case final int slot when slot >= size) slot,
  ];
  return outside..sort();
}

/// Whether [text] is a canonical lowercase UUID, as `owner` must be.
bool validOwner(String text) => _owner.hasMatch(text);
