/// Reads and writes the appearance fields of an NPC definition as
/// `NpcAppearance`, `ArmorStandLook` and `NpcInventory` read them: the body
/// pose, hologram lines, armor stand angles, the stored inventory and the
/// owner.
library;

import '../gui/gui_json.dart';

/// Every body pose; only standing applies to bodies other than players.
const List<String> npcPoses = <String>['standing', 'sneaking', 'sleeping', 'swimming', 'sitting'];

/// Armor stand parts that take `[x, y, z]` degrees.
const List<String> armorStandParts = <String>['head', 'body', 'leftArm', 'rightArm', 'leftLeg', 'rightLeg'];

/// Inventory sizes: whole chest rows of 9, up to 72 slots.
const List<int> inventorySizes = <int>[9, 18, 27, 36, 45, 54, 63, 72];

/// Hologram lines a definition may declare.
const int hologramMaxLines = 16;

final RegExp _owner = RegExp(r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$');

/// The poses a body of [type] may take.
List<String> posesFor(String type) => type == 'minecraft:player' ? npcPoses : const <String>['standing'];

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
