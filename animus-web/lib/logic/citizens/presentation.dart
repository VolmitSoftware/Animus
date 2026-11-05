/// Port of core's `CitizensPresentation`: equipment, inventory, hologram, pose, armor stand, facing, owner and nameplate.
library;

import 'java_json.dart';
import 'java_lang.dart';

const int maxText = 256;
const int maxLines = 16;
const int maxInventorySize = 72;
const int maxItemCount = 99;
const int maxImportedBytes = 16384;

/// `EquipmentSlot` keys in enum order.
const List<String> equipmentSlots = <String>['main_hand', 'off_hand', 'head', 'chest', 'legs', 'feet', 'body', 'saddle'];

const Map<String, String> _slots = <String, String>{
  'hand': 'main_hand',
  'off_hand': 'off_hand',
  'helmet': 'head',
  'chestplate': 'chest',
  'leggings': 'legs',
  'boots': 'feet',
  'body': 'body',
  'saddle': 'saddle',
};
const Set<String> _itemBasics = <String>{'type', 'type_key', 'type_namespace', 'id', 'amount', 'durability', 'data'};
const Set<String> _hologramExtras = <String>{'lineHeight', 'viewRange', 'verticalViewRange', 'hologramUpdateRate', 'default_renderer'};
const List<String> _standParts = <String>['head', 'body', 'leftArm', 'rightArm', 'leftLeg', 'rightLeg'];

final RegExp _itemId = RegExp(r'^[a-z0-9_.-]+:[a-z0-9_./-]+$');

/// `NpcItem.validId`.
bool validItemId(String? id) => id != null && id.length <= 128 && _itemId.hasMatch(id);

class Facing {
  const Facing(this.yaw, this.pitch);

  final double yaw;
  final double pitch;
}

class PresentationResult {
  const PresentationResult(this.fields, this.facing, this.warnings, this.converted);

  final JObject fields;
  final Facing? facing;
  final List<String> warnings;
  final Set<String> converted;
}

PresentationResult mapPresentation(String type, JObject traits, JObject record) {
  final _Presentation mapping = _Presentation(type, traits, record);
  mapping.equipment();
  mapping.inventory();
  mapping.hologram();
  mapping.pose();
  mapping.armorStand();
  mapping.facingFromPoses();
  mapping.owner();
  mapping.nameplate();
  final List<String> sorted = List<String>.of(mapping.warnings)..sort();
  return PresentationResult(mapping.fields.deepCopy(), mapping.facing, sorted, Set<String>.of(mapping.converted));
}

JObject? _object(JElement? value) => value is JObject ? value : null;
String? _text(JElement? value) => value is JPrimitive ? value.asString : null;

bool? _bool(JElement? value) {
  if (value is! JPrimitive) return null;
  if (value is JBool) return value.value;
  final String text = value.asString;
  return text == 'true' ? true : (text == 'false' ? false : null);
}

bool _flag(JObject object, String key, bool fallback) => _bool(object.get(key)) ?? fallback;

double? _number(JElement? value) {
  if (value is! JPrimitive) return null;
  try {
    final double number = javaParseDouble(value.asString);
    return number.isFinite ? number : null;
  } on FormatException {
    return null;
  }
}

BigInt _integer(JElement? value, int fallback) {
  final double? number = _number(value);
  return number == null ? BigInt.from(fallback) : javaDoubleToLong(number.floorToDouble());
}

class _Presentation {
  _Presentation(this.type, this.traits, this.record);

  final String type;
  final JObject traits;
  final JObject record;
  final JObject fields = JObject();
  final List<String> warnings = <String>[];
  final Set<String> converted = <String>{};
  Facing? facing;

  void equipment() {
    final JObject? equipment = _object(traits.get('equipment'));
    if (equipment == null) return;
    final Map<String, JObject> shown = <String, JObject>{};
    for (final MapEntry<String, JElement> entry in equipment.entries) {
      final String key = entry.key;
      final String path = '/traits/equipment/$key';
      final bool cosmetic = key.startsWith('cosmetic_');
      final String realKey = cosmetic ? key.substring('cosmetic_'.length) : key;
      final String? slot = _slots[realKey];
      if (slot == null) {
        warnings.add('$path: unknown Citizens equipment slot; kept for review');
        continue;
      }
      if (!cosmetic &&
          equipment.has('cosmetic_$key') &&
          _item(equipment.get('cosmetic_$key'), '/traits/equipment/cosmetic_$key', <String>[], <String>{}) != null) {
        continue;
      }
      final JObject? item = _item(entry.value, path, warnings, converted);
      if (item == null) continue;
      if (cosmetic) warnings.add('$path: cosmetic item shown in $slot${equipment.has(realKey) ? ' instead of /traits/equipment/$realKey' : ''}');
      shown[slot] = item;
    }
    if (shown.isEmpty) return;
    final JObject result = JObject();
    for (final String slot in equipmentSlots) {
      final JObject? item = shown[slot];
      if (item != null) result.add(slot, item);
    }
    fields.add('equipment', result);
  }

  void inventory() {
    final JObject? inventory = _object(traits.get('inventory'));
    if (inventory == null) return;
    final Map<int, JObject> slots = <int, JObject>{};
    for (final MapEntry<String, JElement> entry in inventory.entries) {
      final String path = '/traits/inventory/${entry.key}';
      if (!RegExp(r'^(?:0|[1-9][0-9]{0,3})$').hasMatch(entry.key)) {
        warnings.add('$path: not a slot number; kept for review');
        continue;
      }
      final int slot = int.parse(entry.key);
      if (slot >= maxInventorySize) {
        warnings.add('$path: slot outside 0..${maxInventorySize - 1}; kept for review');
        continue;
      }
      final JObject? item = _item(entry.value, path, warnings, converted);
      if (item != null) slots[slot] = item;
    }
    if (slots.isEmpty) return;
    final List<int> order = slots.keys.toList()..sort();
    final JObject contents = JObject();
    for (final int slot in order) {
      contents.add('$slot', slots[slot]);
    }
    final JObject result = JObject();
    final int size = (order.last ~/ 9 + 1) * 9;
    result.addInt('size', size < 9 ? 9 : size);
    result.add('slots', contents);
    fields.add('inventory', result);
  }

  JObject? _item(JElement? value, String path, List<String> problems, Set<String> paths) {
    final JObject? source = _object(value);
    if (source == null) {
      problems.add('$path: item is not a mapping; kept for review');
      return null;
    }
    String? raw;
    if (_text(source.get('type_key')) != null) {
      raw = '${_text(source.get('type_namespace')) ?? 'minecraft'}:${_text(source.get('type_key'))}';
    } else {
      raw = _text(source.get('type')) ?? _text(source.get('id'));
    }
    if (raw == null) {
      problems.add('$path: item has no type; kept for review');
      return null;
    }
    String id = javaLower(raw);
    if (!id.contains(':')) id = 'minecraft:$id';
    if (id == 'minecraft:air') return null;
    if (!validItemId(id)) {
      problems.add('$path: item type is not a namespaced id; kept for review');
      return null;
    }
    BigInt count = _integer(source.get('amount'), 1);
    if (count < BigInt.one) {
      problems.add('$path: item count below 1; kept for review');
      return null;
    }
    if (count > BigInt.from(maxItemCount)) {
      problems.add('$path: count $count shown as $maxItemCount');
      count = BigInt.from(maxItemCount);
    }
    final JObject item = JObject();
    item.addString('id', id);
    item.addBig('count', count);
    final BigInt damage = _integer(source.has('durability') ? source.get('durability') : source.get('data'), 0);
    if (damage > BigInt.zero) item.addString('components', '[damage=$damage]');
    final bool extra = source.keys.any((String key) => !_itemBasics.contains(key));
    if (extra) {
      if (javaUtf8(writeJson(source)).length > maxImportedBytes) {
        problems.add('$path: item data beyond id, count and damage is too large to copy; kept for review');
      } else {
        item.add('imported', source.deepCopy());
        problems.add('$path: item data beyond id, count and damage kept in imported; it is not shown');
      }
    }
    for (final String key in source.keys) {
      if (_itemBasics.contains(key)) paths.add('$path/$key');
    }
    return item;
  }

  void hologram() {
    final JObject? hologram = _object(traits.get('hologramtrait'));
    if (hologram == null) return;
    final JElement? declared = hologram.get('lines');
    final List<MapEntry<String, JElement>> entries = <MapEntry<String, JElement>>[];
    if (declared is JArray) {
      for (int i = 0; i < declared.length; i++) {
        entries.add(MapEntry<String, JElement>('$i', declared[i]));
      }
    } else if (declared is JObject) {
      for (final MapEntry<String, JElement> entry in declared.entries) {
        if (RegExp(r'^(?:0|[1-9][0-9]{0,8})$').hasMatch(entry.key)) entries.add(entry);
      }
      entries.sort((MapEntry<String, JElement> a, MapEntry<String, JElement> b) => int.parse(a.key).compareTo(int.parse(b.key)));
    }
    bool extras = hologram.keys.any(_hologramExtras.contains);
    final JArray lines = JArray();
    for (final MapEntry<String, JElement> entry in entries) {
      final String path = '/traits/hologramtrait/lines/${entry.key}';
      String? line = _text(entry.value);
      String linePath = path;
      final JObject? structured = _object(entry.value);
      if (structured != null) {
        for (final String key in structured.keys) {
          if (key != 'text' && key.isNotEmpty) extras = true;
        }
        final String key = structured.has('text') ? 'text' : '';
        line = _text(structured.get(key));
        linePath = '$path/$key';
      }
      if (line == null) {
        warnings.add('$path: line has no text; kept for review');
        continue;
      }
      if (lines.length == maxLines) {
        warnings.add('/traits/hologramtrait/lines: only the first $maxLines lines are shown');
        break;
      }
      String cleaned = String.fromCharCodes(line.runes.map((int point) => javaIsIsoControl(point) ? 0x20 : point));
      if (cleaned != line) warnings.add('$path: control characters shown as spaces');
      if (cleaned.length > maxText) {
        final int unit = cleaned.codeUnitAt(maxText - 1);
        final int end = unit >= 0xD800 && unit <= 0xDBFF ? maxText - 1 : maxText;
        cleaned = cleaned.substring(0, end);
        warnings.add('$path: shortened to $maxText characters');
      }
      lines.addString(cleaned);
      converted.add(linePath);
    }
    if (extras) {
      warnings.add('/traits/hologramtrait: line margins, line height, view ranges and renderer settings are kept for review; lines use uniform spacing');
    }
    if (lines.isEmpty) return;
    final JObject result = JObject();
    result.add('lines', lines);
    fields.add('hologram', result);
  }

  void pose() {
    final bool player = type == 'minecraft:player';
    final JObject? sneak = _object(traits.get('sneak'));
    bool sneaking = sneak != null && _bool(sneak.get('sneaking')) == true;
    final JObject? sit = _object(traits.get('sittrait'));
    bool sitting = sit != null && sit.get('sittingAt') != null && !sit.get('sittingAt')!.isNull;
    if (sneaking && !player) {
      warnings.add('/traits/sneak: sneaking shows only on player bodies; kept for review');
      sneaking = false;
    }
    if (sitting && !player) {
      warnings.add('/traits/sittrait: sitting shows only on player bodies; kept for review');
      sitting = false;
    }
    final JObject? mount = _object(traits.get('mounttrait'));
    if (sitting && mount != null && mount.has('mountedon')) {
      warnings.add('/traits/sittrait: a mounted NPC rides instead of sitting; kept for review');
      sitting = false;
    }
    if (sneaking && sitting) warnings.add('/traits/sneak: sitting is shown instead of sneaking');
    if (sitting) {
      fields.addString('pose', 'sitting');
      converted.add('/traits/sittrait/sittingAt');
      final JObject? at = _object(sit!.get('sittingAt'));
      // Java dereferences this without a check, so a non-mapping sittingAt fails the conversion there too.
      if (at == null) throw StateError('sittingAt is not a mapping');
      for (final String key in at.keys) {
        converted.add('/traits/sittrait/sittingAt/$key');
      }
    } else if (sneaking) {
      fields.addString('pose', 'sneaking');
      converted.add('/traits/sneak/sneaking');
    }
  }

  void armorStand() {
    final JObject? stand = _object(traits.get('armorstandtrait'));
    if (stand == null) return;
    if (type != 'minecraft:armor_stand') {
      warnings.add('/traits/armorstandtrait: applies only to armor stand bodies; kept for review');
      return;
    }
    final JObject look = JObject();
    look.addBool('small', _flag(stand, 'small', false));
    look.addBool('arms', _flag(stand, 'hasarms', true));
    look.addBool('basePlate', _flag(stand, 'hasbaseplate', true));
    look.addBool('invisible', !_flag(stand, 'visible', true));
    for (final String key in const <String>['small', 'hasarms', 'hasbaseplate', 'visible']) {
      if (_bool(stand.get(key)) != null) converted.add('/traits/armorstandtrait/$key');
    }
    for (final String part in _standParts) {
      if (!stand.has(part) || stand.get(part)!.isNull) continue;
      final JObject? angle = _object(stand.get(part));
      final String path = '/traits/armorstandtrait/$part';
      final double? x = angle == null ? null : _number(angle.get('x'));
      final double? y = angle == null ? null : _number(angle.get('y'));
      final double? z = angle == null ? null : _number(angle.get('z'));
      if (x == null || y == null || z == null) {
        warnings.add('$path: angle not readable; kept for review');
        continue;
      }
      final JArray degrees = JArray();
      for (final double radians in <double>[x, y, z]) {
        degrees.addDouble(_degrees(radians));
      }
      look.add(part, degrees);
      for (final String axis in const <String>['x', 'y', 'z']) {
        converted.add('$path/$axis');
      }
    }
    final List<String> kept = <String>[
      for (final String key in const <String>['marker', 'gravity'])
        if (stand.has(key)) key,
    ];
    if (kept.isNotEmpty) warnings.add('/traits/armorstandtrait: ${kept.join(' and ')}${kept.length == 1 ? ' is' : ' are'} kept for review');
    fields.add('armorStand', look);
  }

  void facingFromPoses() {
    final JObject? poses = _object(traits.get('poses'));
    if (poses == null || _text(poses.get('defaultPose')) == null) return;
    final String wanted = javaLower(_text(poses.get('defaultPose'))!);
    final List<JElement> list = <JElement>[];
    final JElement? declared = poses.get('list');
    if (declared is JArray) {
      list.addAll(declared.elements);
    } else if (declared is JObject) {
      list.addAll(declared.members.values);
    }
    for (final JElement entry in list) {
      final List<String> parts = javaSplit(_text(entry) ?? '', ';');
      if (parts.length < 3 || javaLower(parts[0]) != wanted) continue;
      final double yaw;
      final double pitch;
      try {
        yaw = javaParseDouble(parts[1]);
        pitch = javaParseDouble(parts[2]);
      } on FormatException {
        break;
      }
      if (!yaw.isFinite || !pitch.isFinite) break;
      facing = Facing(yaw, pitch > 90 ? 90 : (pitch < -90 ? -90 : pitch));
      converted.add('/traits/poses/defaultPose');
      warnings.add('/traits/poses: default pose $wanted sets the facing; other named poses are kept for review');
      return;
    }
    warnings.add('/traits/poses: default pose $wanted is not in the list; kept for review');
  }

  void owner() {
    final JObject? owner = _object(traits.get('owner'));
    if (owner == null || !owner.has('uuid')) return;
    final String? uuid = _text(owner.get('uuid'));
    if (canonicalUuid(uuid)) {
      fields.addString('owner', uuid!);
      converted.add('/traits/owner/uuid');
    } else {
      warnings.add('/traits/owner: no player UUID; kept for review');
    }
  }

  void nameplate() {
    final JObject? metadata = _object(record.get('metadata'));
    if (metadata == null || !metadata.has('nameplate-visible')) return;
    final String value = javaLower(_text(metadata.get('nameplate-visible')) ?? '');
    if (value == 'false') {
      final JObject nameplate = JObject();
      nameplate.addBool('visible', false);
      fields.add('nameplate', nameplate);
      converted.add('/metadata/nameplate-visible');
    } else if (value == 'true') {
      converted.add('/metadata/nameplate-visible');
    } else {
      warnings.add('/metadata/nameplate-visible: ${value == 'hover' ? 'hover-only nameplates show always' : 'unknown value; kept for review'}');
    }
  }
}

/// `BigDecimal.valueOf(Math.toDegrees(radians) % 360).setScale(3, HALF_UP).doubleValue()`.
double _degrees(double radians) {
  final double degrees = javaToDegrees(radians).remainder(360);
  final JavaDecimal decimal = JavaDecimal.parse(javaDoubleToString(degrees))!;
  return double.parse(roundHalfUp(decimal, 3));
}

/// A decimal rounded half up to [scale] places, as plain text.
String roundHalfUp(JavaDecimal decimal, int scale) {
  BigInt unscaled = decimal.unscaled;
  final int shift = decimal.scale - scale;
  if (shift > 0) {
    final BigInt divisor = BigInt.from(10).pow(shift);
    final bool negative = unscaled.isNegative;
    final BigInt magnitude = unscaled.abs();
    BigInt quotient = magnitude ~/ divisor;
    if ((magnitude.remainder(divisor)) * BigInt.two >= divisor) quotient += BigInt.one;
    unscaled = negative ? -quotient : quotient;
  } else if (shift < 0) {
    unscaled *= BigInt.from(10).pow(-shift);
  }
  final bool negative = unscaled.isNegative;
  final String digits = unscaled.abs().toString().padLeft(scale + 1, '0');
  final String text = '${digits.substring(0, digits.length - scale)}.${digits.substring(digits.length - scale)}';
  return unscaled == BigInt.zero ? '0.0' : (negative ? '-$text' : text);
}
