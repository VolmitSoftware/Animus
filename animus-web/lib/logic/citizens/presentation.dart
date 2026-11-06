/// Port of core's `CitizensPresentation`: equipment, inventory, hologram, pose, armor stand, facing, owner and nameplate.
library;

import 'citizens_items.dart';
import 'field_notes.dart';
import 'java_json.dart';
import 'java_lang.dart';
import 'runtime_behaviors.dart' show listEntries, pointer;

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
/// Citizens renderer settings per hologram line, with the Animus hologram field each becomes.
const Map<String, String> _renderer = <String, String>{
  'billboard': 'billboard', 'seeThrough': 'seeThrough', 'shadowed': 'shadowed', 'interpolationDelay': 'interpolationDelay', //
  'interpolationDuration': 'interpolationDuration',
};
const Set<String> _billboards = <String>{'center', 'fixed', 'vertical', 'horizontal'};
const double _maxLineHeight = 4;
const double _maxMargin = 4;
const double _maxViewRange = 512;
const int _maxInterpolation = 1200;
const int _maxComponents = 4096;
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
  const PresentationResult(this.fields, this.facing, this.warnings, this.converted, this.notes);

  final JObject fields;
  final Facing? facing;
  final List<String> warnings;
  final Set<String> converted;
  final Map<String, FieldNote> notes;
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
  return PresentationResult(mapping.fields.deepCopy(), mapping.facing, sorted, Set<String>.of(mapping.converted), Map<String, FieldNote>.of(mapping.notes));
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
  final Map<String, FieldNote> notes = <String, FieldNote>{};
  Facing? facing;

  void equipment() {
    final JObject? equipment = _object(traits.get('equipment'));
    if (equipment == null) {
      final Map<String, JObject> shown = <String, JObject>{};
      _horse(shown);
      _store(shown);
      return;
    }
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
    _horse(shown);
    _store(shown);
  }

  void _store(Map<String, JObject> shown) {
    if (shown.isEmpty) return;
    final JObject result = JObject();
    for (final String slot in equipmentSlots) {
      final JObject? item = shown[slot];
      if (item != null) result.add(slot, item);
    }
    fields.add('equipment', result);
  }

  /// A horse's saddle and armour from HorseModifiers fill the saddle and body slots that Equipment left empty.
  void _horse(Map<String, JObject> shown) {
    final JObject? horse = _object(traits.get('horsemodifiers'));
    if (horse == null) return;
    for (final MapEntry<String, String> part in const <MapEntry<String, String>>[MapEntry<String, String>('saddle', 'saddle'), MapEntry<String, String>('armor', 'body')]) {
      if (!horse.has(part.key) || horse.get(part.key)!.isNull) continue;
      final String path = '/traits/horsemodifiers/${part.key}';
      if (shown.containsKey(part.value)) {
        notes[path] = const FieldNote(unsupportedPreserved, 'the Equipment trait already fills this slot');
        continue;
      }
      final JObject? item = _item(horse.get(part.key), path, warnings, converted);
      if (item != null) shown[part.value] = item;
    }
  }

  void inventory() {
    final JElement? declared = traits.get('inventory');
    if (declared is! JObject && declared is! JArray) return;
    final List<MapEntry<String, JElement>> entries = declared is JArray
        ? <MapEntry<String, JElement>>[for (int i = 0; i < declared.length; i++) MapEntry<String, JElement>('$i', declared[i])]
        : (declared! as JObject).entries.toList();
    final Map<int, JObject> slots = <int, JObject>{};
    for (final MapEntry<String, JElement> entry in entries) {
      final String path = '/traits/inventory/${entry.key}';
      if (entry.value.isNull) {
        converted.add(path);
        continue;
      }
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
    final ItemMetaResult meta = readCitizensItem(source, damage);
    bool shown = true;
    if (meta.components != null && meta.components!.length > _maxComponents) {
      notes['$path/meta'] = const FieldNote(requiresReview, "the item's components are longer than $_maxComponents characters; the item shows without them");
      shown = false;
    } else if (meta.components != null) {
      item.addString('components', meta.components!);
    }
    final List<String> other = <String>[
      for (final String key in source.keys)
        if (!_itemBasics.contains(key) && key != 'meta' && key != 'editable_components') key,
    ]..sort();
    if (source.has('meta')) {
      if (!meta.readable) {
        notes['$path/meta'] = const FieldNote(requiresReview, 'the item meta could not be read; the item shows without it');
        problems.add("$path: the item's name, enchantments and other data could not be read, so it shows as a plain $id; set the item again in the editor if it matters");
      } else if (meta.unmapped.isNotEmpty) {
        final List<String> keys = meta.unmapped.toList()..sort();
        notes['$path/meta'] = FieldNote(requiresReview, "item meta ${keys.join(', ')} has no item component here; kept in the item's imported data");
      } else if (shown) {
        paths.add('$path/meta');
      }
    }
    if (source.has('editable_components')) {
      notes['$path/editable_components'] = const FieldNote(statusConverted, "Citizens' copy of the item name and lore, read with the meta");
    }
    for (final String key in other) {
      notes['$path/${pointer(key)}'] = FieldNote(requiresReview, "item field $key has no item component here; kept in the item's imported data");
    }
    if (other.isNotEmpty || !meta.readable || meta.unmapped.isNotEmpty || !shown) {
      if (javaUtf8(writeJson(source)).length <= maxImportedBytes) item.add('imported', source.deepCopy());
    }
    for (final String key in source.keys) {
      if (_itemBasics.contains(key)) paths.add('$path/$key');
    }
    return item;
  }

  /// Citizens keeps hologram lines bottom-up; Animus lists them top-down, so the order is reversed.
  void hologram() {
    final JObject? hologram = _object(traits.get('hologramtrait'));
    if (hologram == null) return;
    const String base = '/traits/hologramtrait';
    final List<MapEntry<String, JElement>> entries = listEntries(hologram.get('lines'));
    final JArray lines = JArray();
    final JObject style = JObject();
    JObject? first;
    final List<JElement> shown = <JElement>[];
    for (final MapEntry<String, JElement> entry in entries) {
      final String path = '$base/lines/${pointer(entry.key)}';
      String? line = _text(entry.value);
      String linePath = path;
      final JObject? structured = _object(entry.value);
      double top = 0;
      double bottom = 0;
      if (structured != null) {
        final String key = structured.has('text') ? 'text' : '';
        line = _text(structured.get(key));
        linePath = '$path/$key';
        final JObject? margin = _object(structured.get('margin'));
        if (margin != null) {
          final double? t = _number(margin.get('top'));
          final double? b = _number(margin.get('bottom'));
          if (t != null) {
            top = _clampMargin(t, '$path/margin/top');
            converted.add('$path/margin/top');
          }
          if (b != null) {
            bottom = _clampMargin(b, '$path/margin/bottom');
            converted.add('$path/margin/bottom');
          }
        }
        final JObject? renderer = _object(structured.get('renderer'));
        if (renderer != null) {
          first ??= renderer;
          for (final MapEntry<String, JElement> setting in renderer.entries) {
            final String at = '$path/renderer/${pointer(setting.key)}';
            if (!_renderer.containsKey(setting.key)) {
              notes[at] = FieldNote(requiresReview, 'renderer setting ${setting.key} has no hologram equivalent');
              continue;
            }
            if (identical(renderer, first)) continue;
            if (_sameJson(setting.value, first.get(setting.key))) {
              converted.add(at);
            } else {
              notes[at] = FieldNote(requiresReview, "every hologram line shares one style; the bottom line's ${setting.key} is used");
            }
          }
        }
        for (final String field in structured.keys) {
          if (field != 'text' && field != '' && field != 'margin' && field != 'renderer') {
            notes['$path/${pointer(field)}'] = FieldNote(requiresReview, 'line setting $field has no hologram equivalent');
          }
        }
      }
      if (line == null) {
        warnings.add('$path: the line has no text and was left out; add it in the editor if it is needed');
        continue;
      }
      if (lines.length == maxLines) {
        warnings.add('$base/lines: only the bottom $maxLines lines are shown; move the others into fewer lines');
        break;
      }
      String cleaned = String.fromCharCodes(line.runes.map((int point) => javaIsIsoControl(point) ? 0x20 : point));
      if (cleaned.length > maxText) {
        final int unit = cleaned.codeUnitAt(maxText - 1);
        final int end = unit >= 0xD800 && unit <= 0xDBFF ? maxText - 1 : maxText;
        cleaned = cleaned.substring(0, end);
        warnings.add('$path: shortened to $maxText characters; shorten the line in the editor');
      }
      if (top != 0 || bottom != 0) {
        final JObject object = JObject()..addString('text', cleaned);
        if (top != 0) object.addDouble('marginTop', top);
        if (bottom != 0) object.addDouble('marginBottom', bottom);
        shown.add(object);
      } else {
        shown.add(JString(cleaned));
      }
      lines.addString(cleaned);
      converted.add(linePath);
    }
    if (first != null) {
      final String index = entries[_firstIndex(entries)].key;
      for (final MapEntry<String, JElement> setting in first.entries) {
        final String? field = _renderer[setting.key];
        if (field == null) continue;
        final String at = '$base/lines/$index/renderer/${pointer(setting.key)}';
        final JElement value = setting.value;
        if (field == 'billboard') {
          final String billboard = _text(value) == null ? '' : javaLower(_text(value)!);
          if (!_billboards.contains(billboard)) {
            notes[at] = FieldNote(requiresReview, 'billboard ${_text(value)} is not one Animus knows');
            continue;
          }
          if (billboard != 'center') style.addString('billboard', billboard);
        } else if (field.startsWith('interpolation')) {
          final double? ticks = _number(value);
          if (ticks == null) continue;
          BigInt whole = javaDoubleToLong(ticks.floorToDouble());
          if (whole < BigInt.zero) whole = BigInt.zero;
          if (whole > BigInt.from(_maxInterpolation)) whole = BigInt.from(_maxInterpolation);
          if (whole != BigInt.zero) style.addBig(field, whole);
        } else {
          final bool? flag = _bool(value);
          if (flag == null) continue;
          if (flag) style.addBool(field, true);
        }
        converted.add(at);
      }
    }
    final double? height = _number(hologram.get('lineHeight'));
    if (height != null) {
      if (height >= 0 && height <= _maxLineHeight) {
        style.addDouble('lineHeight', height);
      } else if (height >= 0) {
        style.addDouble('lineHeight', _maxLineHeight);
        warnings.add('$base/lineHeight: lines more than 4 blocks apart became 4 apart; lower lineHeight in the editor if that matters');
      }
      converted.add('$base/lineHeight');
    }
    final double? range = _number(hologram.get('viewRange'));
    if (range != null) {
      if (range >= 1) style.addDouble('viewRange', range < _maxViewRange ? range : _maxViewRange);
      converted.add('$base/viewRange');
    }
    if (hologram.has('hologramUpdateRate')) {
      notes['$base/hologramUpdateRate'] = const FieldNote(statusConverted, 'Animus hologram text has no placeholders to refresh');
    }
    if (hologram.has('verticalViewRange')) {
      notes['$base/verticalViewRange'] = const FieldNote(requiresReview, 'holograms are hidden by distance only, not by height');
    }
    if (hologram.has('default_renderer')) {
      notes['$base/default_renderer'] = const FieldNote(requiresReview, 'Animus draws every hologram line as text');
    }
    if (lines.isEmpty) return;
    final JArray ordered = JArray();
    for (int index = shown.length - 1; index >= 0; index--) {
      ordered.add(shown[index]);
    }
    final JObject result = JObject()..add('lines', ordered);
    for (final MapEntry<String, JElement> entry in style.entries) {
      result.add(entry.key, entry.value);
    }
    fields.add('hologram', result);
  }

  double _clampMargin(double margin, String path) {
    if (margin.abs() <= _maxMargin) return margin;
    warnings.add('$path: margins beyond 4 blocks became 4; adjust the line in the editor if that matters');
    return margin < 0 ? -_maxMargin : _maxMargin;
  }

  void pose() {
    final bool player = type == 'minecraft:player';
    final JObject? sneak = _object(traits.get('sneak'));
    final JObject? extension = _object(traits.get('sneaking'));
    final String? sneakPath = sneak != null && _bool(sneak.get('sneaking')) == true
        ? '/traits/sneak/sneaking'
        : (extension != null && _bool(extension.get('sneaking')) == true ? '/traits/sneaking/sneaking' : null);
    bool sneaking = sneakPath != null;
    for (final String path in const <String>['/traits/sneak/sneaking', '/traits/sneaking/sneaking']) {
      final JObject? owner = _object(traits.get(path.split('/')[2]));
      if (owner != null && _bool(owner.get('sneaking')) == false) converted.add(path);
    }
    final JObject? sit = _object(traits.get('sittrait'));
    bool sitting = sit != null && sit.get('sittingAt') != null && !sit.get('sittingAt')!.isNull;
    if (sneaking && !player) {
      notes[sneakPath] = const FieldNote(unsupportedPreserved, 'sneaking shows only on player bodies');
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
    if (sneaking && sitting) notes[sneakPath!] = const FieldNote(unsupportedPreserved, 'the NPC sits, which is shown instead of sneaking');
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
      converted.add(sneakPath!);
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
    final bool? gravity = _bool(stand.get('gravity'));
    if (gravity != null) {
      if (!gravity) fields.addBool('gravity', false);
      converted.add('/traits/armorstandtrait/gravity');
    }
    if (stand.has('marker')) warnings.add('/traits/armorstandtrait: marker is kept for review');
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

int _firstIndex(List<MapEntry<String, JElement>> entries) {
  for (int index = 0; index < entries.length; index++) {
    final JObject? line = _object(entries[index].value);
    if (line != null && _object(line.get('renderer')) != null) return index;
  }
  return 0;
}

/// `JsonElement.equals` for the scalars a renderer setting holds: numbers by value, the rest by content.
bool _sameJson(JElement a, JElement? b) {
  if (b == null) return false;
  if (a is JNumber && b is JNumber) {
    if (a is JInt && b is JInt) return a.value == b.value;
    return a.asDouble == b.asDouble;
  }
  if (a is JPrimitive && b is JPrimitive) return a == b;
  return writeJson(a) == writeJson(b);
}
