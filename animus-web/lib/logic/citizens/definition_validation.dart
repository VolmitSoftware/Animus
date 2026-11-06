/// Port of core's `NpcDefinition.validate` with `NpcAppearance`, `HologramStyle`, `BodyTraits`, `NpcItem`, `ArmorStandLook`,
/// `NpcInventory` and `MovementSettings`.
///
/// The module validators (`WaypointSettings`, `CombatSettings`, `MountDeclaration`, `RideSettings`, `ShopBinding`,
/// `GuiBinding`, `HireTerms`) are not ported: the modules this converter writes are built to pass them, and the server
/// validates every definition again when an import is applied, so a module the browser accepted wrongly is still refused.
library;

import 'citizens_traits.dart' show teamColors;
import 'java_json.dart';
import 'java_lang.dart';
import 'presentation.dart';

const List<String> _poses = <String>['standing', 'sneaking', 'sleeping', 'swimming', 'sitting'];
const List<String> _parts = <String>['head', 'body', 'leftArm', 'rightArm', 'leftLeg', 'rightLeg'];
const Set<String> _lookFlags = <String>{'small', 'arms', 'basePlate', 'invisible'};
const Set<String> _itemFields = <String>{'id', 'count', 'components', 'imported'};
const Set<String> _hologramFields = <String>{'lines', 'lineHeight', 'viewRange', 'billboard', 'shadowed', 'seeThrough', 'interpolationDelay', 'interpolationDuration'};
const Set<String> _lineFields = <String>{'text', 'marginTop', 'marginBottom'};
const Set<String> _billboards = <String>{'center', 'fixed', 'vertical', 'horizontal'};
const Set<String> _movementFields = <String>{'speed', 'avoidWater', 'fly', 'openDoors', 'stepHeight', 'maximumFall', 'pathRange', 'arrivalMargin', 'stuck'};

/// `NpcAppearance.displayText`.
bool displayText(String? text) => text != null && text.length <= maxText && !text.runes.any(javaIsIsoControl);

List<String> validateDefinition(JObject object) {
  final List<String> errors = <String>[];
  _integer(object, 'schemaVersion', BigInt.one, BigInt.one, errors);
  _integer(object, 'revision', BigInt.one, javaLongMax, errors);
  final String uuid = _string(object, 'uuid', errors);
  if (!canonicalUuid(uuid)) errors.add(r'$.uuid: expected canonical UUID');
  if (!RegExp(r'^[a-z0-9][a-z0-9_-]{0,63}$').hasMatch(_string(object, 'id', errors))) {
    errors.add(r'$.id: expected 1–64 lowercase letters, digits, _ or -');
  }
  if (!RegExp(r'^(?:[a-z0-9][a-z0-9_-]{0,63}(?:/[a-z0-9][a-z0-9_-]{0,63}){0,7})?$').hasMatch(_string(object, 'group', errors))) {
    errors.add(r'$.group: expected the folder under npcs/: empty, or up to 8 names of 1–64 lowercase letters, digits, _ or - joined by /');
  }
  if (!displayText(_string(object, 'name', errors))) errors.add('\$.name: expected at most $maxText characters without control characters');
  if (!RegExp(r'^[a-z0-9_]+:[a-z0-9_/]+$').hasMatch(_string(object, 'type', errors))) errors.add(r'$.type: expected namespace:type');
  if (object.get('spawned') is! JBool) errors.add(r'$.spawned: expected boolean');
  final JElement? position = object.get('position');
  if (position is! JObject) {
    errors.add(r'$.position: expected object');
  } else {
    final List<String> positionErrors = <String>[];
    _string(position, 'world', positionErrors);
    for (final String key in const <String>['x', 'y', 'z', 'yaw', 'pitch']) {
      final JElement? value = position.get(key);
      if (value is! JNumber || !value.asDouble.isFinite || value.asDouble.abs() > 30000000) {
        positionErrors.add('\$.$key: expected finite coordinate within ±30000000');
      }
    }
    for (final String error in positionErrors) {
      errors.add('\$.position${error.substring(1)}');
    }
  }
  if (object.has('skin')) {
    final JElement? skin = object.get('skin');
    if (skin is! JString || !RegExp(r'^[a-f0-9]{64}$').hasMatch(skin.value)) errors.add(r'$.skin: expected SHA-256 reference');
  }
  if (object.has('behaviors')) {
    final JElement? behaviors = object.get('behaviors');
    if (behaviors is! JArray) {
      errors.add(r'$.behaviors: expected array');
    } else {
      for (final JElement reference in behaviors.elements) {
        if (reference is! JString || !RegExp(r'^[a-z0-9_-]+:[a-z0-9_/-]+$').hasMatch(reference.value)) {
          errors.add(r'$.behaviors: expected namespace:id references');
        }
      }
    }
  }
  if (object.has('owner')) {
    final JElement? owner = object.get('owner');
    if (!canonicalUuid(owner is JString ? owner.value : '')) errors.add(r'$.owner: expected canonical player UUID');
  }
  _appearance(object, errors);
  _inventory(object, errors);
  _movement(object, errors);
  _namedPoses(object, errors);
  if (object.has('modules') && object.get('modules') is! JObject) errors.add(r'$.modules: expected object');
  return errors;
}

/// `DefinitionSpec.activation`: the rules every server applies when it spawns a definition, so the preview's valid count
/// matches what can spawn. A malformed native `animus:mount` module is not diagnosed here (the converter never writes one).
List<String> activationErrors(JObject definition) {
  final List<String> errors = <String>[];
  final JElement? type = definition.get('type');
  if (definition.has('skin') && !(type is JString && type.value == 'minecraft:player')) errors.add(r'$.skin: textures require a player body');
  final JElement? pose = definition.get('pose');
  if (pose is JString && pose.value == 'sitting' && _declaresMount(definition)) {
    errors.add(r'$.pose: a sitting NPC cannot also ride another NPC; remove the pose or the mount');
  }
  return errors;
}

/// `MountDeclaration.parse(...).isPresent()` for well-formed documents.
bool _declaresMount(JObject definition) {
  final JElement? modules = definition.get('modules');
  if (modules is JObject && modules.has('animus:mount')) {
    final JElement? module = modules.get('animus:mount');
    final JElement? enabled = module is JObject ? module.get('enabled') : null;
    return !(enabled is JBool && !enabled.value);
  }
  final JElement? migration = definition.get('migration');
  final JElement? references = migration is JObject ? migration.get('references') : null;
  if (references is! JArray) return false;
  for (final JElement reference in references.elements) {
    if (reference is! JObject) continue;
    final JElement? kind = reference.get('kind');
    if (!(kind is JString && kind.value == 'mount')) continue;
    final JElement? status = reference.get('status');
    return !(status is JString && status.value == 'none');
  }
  return false;
}

String _string(JObject data, String key, List<String> errors) {
  final JElement? value = data.get(key);
  if (value is! JString || javaIsBlank(value.value)) {
    errors.add('\$.$key: expected nonblank string');
    return '';
  }
  return value.value;
}

void _integer(JObject object, String key, BigInt minimum, BigInt maximum, List<String> errors) {
  final JElement? value = object.get(key);
  final BigInt? number = value is JNumber ? value.asDecimal.longValueExact() : null;
  if (number == null || number < minimum || number > maximum) errors.add('\$.$key: expected integer $minimum..$maximum');
}

void _appearance(JObject definition, List<String> errors) {
  final JElement? typeValue = definition.get('type');
  final String type = typeValue is JPrimitive ? typeValue.asString : '';
  if (definition.has('equipment')) {
    final JElement value = definition.get('equipment')!;
    if (value is! JObject) {
      errors.add(r'$.equipment: expected object');
    } else {
      for (final MapEntry<String, JElement> entry in value.entries) {
        if (!equipmentSlots.contains(entry.key)) {
          errors.add('\$.equipment.${entry.key}: unknown slot; expected main_hand, off_hand, head, chest, legs, feet, body or saddle');
          continue;
        }
        _item(entry.value, '\$.equipment.${entry.key}', errors);
      }
    }
  }
  if (definition.has('hologram')) {
    final JElement value = definition.get('hologram')!;
    if (value is! JObject) {
      errors.add(r'$.hologram: expected object');
    } else {
      for (final String key in value.keys) {
        if (!_hologramFields.contains(key)) errors.add('\$.hologram.$key: unknown field');
      }
      final JElement? declared = value.get('lines');
      if (declared is! JArray) {
        errors.add(r'$.hologram.lines: expected array');
      } else if (declared.length > maxLines) {
        errors.add('\$.hologram.lines: expected at most $maxLines lines');
      } else {
        for (int i = 0; i < declared.length; i++) {
          final String path = '\$.hologram.lines[$i]';
          final String? line = _hologramLine(declared[i], path, errors);
          if (line != null && !displayText(line)) errors.add('$path: expected at most $maxText characters without control characters');
        }
      }
      _decimal(value, 'lineHeight', r'$.hologram', 0, 4, 'blocks', errors);
      _decimal(value, 'viewRange', r'$.hologram', 1, 512, 'blocks', errors);
      final JElement? billboard = value.get('billboard');
      if (billboard != null && !(billboard is JString && _billboards.contains(billboard.value))) {
        errors.add(r'$.hologram.billboard: expected center, fixed, vertical or horizontal');
      }
      for (final String key in const <String>['shadowed', 'seeThrough']) {
        if (value.has(key) && value.get(key) is! JBool) errors.add('\$.hologram.$key: expected boolean');
      }
      for (final String key in const <String>['interpolationDelay', 'interpolationDuration']) {
        final JElement? ticks = value.get(key);
        if (ticks != null && !_whole(ticks, 0, 1200)) errors.add('\$.hologram.$key: expected 0..1200 ticks');
      }
    }
  }
  if (definition.has('nameplate')) {
    final JElement value = definition.get('nameplate')!;
    if (value is! JObject) {
      errors.add(r'$.nameplate: expected object');
    } else {
      for (final String key in value.keys) {
        if (key != 'visible') errors.add('\$.nameplate.$key: unknown field');
      }
      final JElement? visible = value.get('visible');
      if (visible != null && visible is! JBool) errors.add(r'$.nameplate.visible: expected boolean');
    }
  }
  if (definition.has('pose')) {
    final JElement value = definition.get('pose')!;
    final String? pose = value is JString && _poses.contains(value.value) ? value.value : null;
    if (pose == null) {
      errors.add(r'$.pose: expected standing, sneaking, sleeping, swimming or sitting');
    } else if (pose != 'standing' && type != 'minecraft:player') {
      errors.add('\$.pose: $pose is available only for minecraft:player bodies');
    }
  }
  if (definition.has('armorStand')) {
    if (type != 'minecraft:armor_stand') {
      errors.add(r'$.armorStand: available only for minecraft:armor_stand bodies');
    } else {
      _armorStand(definition.get('armorStand')!, errors);
    }
  }
  for (final String key in const <String>['glowing', 'baby', 'usingItem', 'listed', 'gravity']) {
    if (definition.has(key) && definition.get(key) is! JBool) errors.add('\$.$key: expected boolean');
  }
  final JElement? color = definition.get('teamColor');
  if (color != null && !(color is JString && teamColors.contains(color.value))) errors.add(r'$.teamColor: expected a team colour such as red, dark_aqua or gold');
}

/// `HologramStyle.line`: a string, or an object with text and margins.
String? _hologramLine(JElement value, String path, List<String> errors) {
  if (value is JString) return value.value;
  if (value is! JObject) {
    errors.add('$path: expected string or object with text');
    return null;
  }
  for (final String key in value.keys) {
    if (!_lineFields.contains(key)) errors.add('$path.$key: unknown field');
  }
  final JElement? text = value.get('text');
  if (text is! JString) {
    errors.add('$path.text: expected string');
    return null;
  }
  _decimal(value, 'marginTop', path, -4, 4, 'blocks', errors);
  _decimal(value, 'marginBottom', path, -4, 4, 'blocks', errors);
  return text.value;
}

void _decimal(JObject object, String key, String path, double minimum, double maximum, String unit, List<String> errors) {
  final JElement? value = object.get(key);
  if (value == null) return;
  if (value is! JNumber || !value.asDouble.isFinite || value.asDouble < minimum || value.asDouble > maximum) {
    errors.add('$path.$key: expected ${_format(minimum)}..${_format(maximum)} $unit');
  }
}

String _format(double value) => value == value.roundToDouble() ? '${value.toInt()}' : javaDoubleToString(value);

bool _whole(JElement value, double minimum, double maximum) =>
    value is JNumber && value.asDouble == value.asDouble.roundToDouble() && value.asDouble >= minimum && value.asDouble <= maximum;

/// `MovementSettings.read`.
void _movement(JObject definition, List<String> errors) {
  final JElement? value = definition.get('movement');
  if (value == null) return;
  if (value is! JObject) {
    errors.add(r'$.movement: expected object');
    return;
  }
  _decimal(value, 'speed', r'$.movement', 1, 5, 'blocks/second', errors);
  for (final String key in const <String>['avoidWater', 'fly', 'openDoors']) {
    if (value.has(key) && value.get(key) is! JBool) errors.add('\$.movement.$key: expected boolean');
  }
  _decimal(value, 'stepHeight', r'$.movement', 0, 1.5, 'blocks', errors);
  if (value.has('maximumFall') && !_whole(value.get('maximumFall')!, 0, 16)) errors.add(r'$.movement.maximumFall: expected whole 0..16 blocks');
  _decimal(value, 'pathRange', r'$.movement', 1, 256, 'blocks', errors);
  _decimal(value, 'arrivalMargin', r'$.movement', 0, 4, 'blocks', errors);
  if (value.has('stuck')) {
    final JElement stuck = value.get('stuck')!;
    if (stuck is! JObject) {
      errors.add(r'$.movement.stuck: expected object');
    } else {
      for (final String key in stuck.keys) {
        if (key != 'afterTicks' && key != 'teleport') errors.add('\$.movement.stuck.$key: unknown field');
      }
      if (stuck.has('afterTicks') && !_whole(stuck.get('afterTicks')!, 1, 12000)) errors.add(r'$.movement.stuck.afterTicks: expected whole 1..12000 ticks');
      if (stuck.has('teleport') && stuck.get('teleport') is! JBool) errors.add(r'$.movement.stuck.teleport: expected boolean');
    }
  }
  for (final String key in value.keys) {
    if (!_movementFields.contains(key)) errors.add('\$.movement.$key: unknown field');
  }
}

/// `NamedPoses.read`.
void _namedPoses(JObject definition, List<String> errors) {
  final JElement? value = definition.get('poses');
  if (value == null) return;
  if (value is! JObject) {
    errors.add(r'$.poses: expected object');
    return;
  }
  if (value.length > 64) {
    errors.add(r'$.poses: expected at most 64 poses');
    return;
  }
  for (final MapEntry<String, JElement> entry in value.entries) {
    final String path = '\$.poses.${entry.key}';
    if (!RegExp(r'^[a-z0-9][a-z0-9_-]{0,31}$').hasMatch(entry.key)) {
      errors.add('$path: expected a name of 1-32 lowercase letters, digits, _ or -');
      continue;
    }
    final JElement pose = entry.value;
    if (pose is! JObject) {
      errors.add('$path: expected object');
      continue;
    }
    for (final String key in pose.keys) {
      if (key != 'yaw' && key != 'pitch') errors.add('$path.$key: unknown field');
    }
    _angle(pose, 'yaw', 360, path, errors);
    _angle(pose, 'pitch', 90, path, errors);
  }
}

void _angle(JObject pose, String key, int limit, String path, List<String> errors) {
  final JElement? value = pose.get(key);
  if (value == null && key == 'pitch') return;
  if (value is! JNumber || !value.asDouble.isFinite || value.asDouble.abs() > limit) errors.add('$path.$key: expected -$limit..$limit degrees');
}

void _armorStand(JElement value, List<String> errors) {
  if (value is! JObject) {
    errors.add(r'$.armorStand: expected object');
    return;
  }
  for (final String key in value.keys) {
    if (!_parts.contains(key) && !_lookFlags.contains(key)) errors.add('\$.armorStand.$key: unknown field');
  }
  for (final String key in const <String>['small', 'arms', 'basePlate', 'invisible']) {
    final JElement? flag = value.get(key);
    if (flag != null && flag is! JBool) errors.add('\$.armorStand.$key: expected boolean');
  }
  for (final String part in _parts) {
    if (!value.has(part)) continue;
    final JElement angles = value.get(part)!;
    bool valid = angles is JArray && angles.length == 3;
    if (angles is JArray && valid) {
      for (final JElement angle in angles.elements) {
        if (angle is! JNumber || !angle.asDouble.isFinite || angle.asDouble.abs() > 360) valid = false;
      }
    }
    if (!valid) errors.add('\$.armorStand.$part: expected [x, y, z] degrees within -360..360');
  }
}

void _item(JElement? value, String path, List<String> errors) {
  if (value is! JObject) {
    errors.add('$path: expected item object');
    return;
  }
  for (final String key in value.keys) {
    if (!_itemFields.contains(key)) errors.add('$path.$key: unknown field');
  }
  final JElement? id = value.get('id');
  if (!validItemId(id is JString ? id.value : null)) errors.add('$path.id: expected namespace:item');
  if (value.has('count')) {
    final JElement count = value.get('count')!;
    final int? number = count is JNumber ? count.asDecimal.intValueExact() : null;
    if (number == null || number < 1 || number > maxItemCount) errors.add('$path.count: expected integer 1..$maxItemCount');
  }
  if (value.has('components')) {
    final JElement text = value.get('components')!;
    if (text is! JString || text.value.length > 4096 || !text.value.startsWith('[') || !text.value.endsWith(']')) {
      errors.add('$path.components: expected [component=value,...] of at most 4096 characters');
    }
  }
  if (value.has('imported')) {
    final JElement data = value.get('imported')!;
    if (data is! JObject || javaUtf8(writeJson(data)).length > maxImportedBytes) {
      errors.add('$path.imported: expected an object of at most $maxImportedBytes bytes');
    }
  }
}

void _inventory(JObject definition, List<String> errors) {
  if (!definition.has('inventory')) return;
  final JElement value = definition.get('inventory')!;
  if (value is! JObject) {
    errors.add(r'$.inventory: expected object');
    return;
  }
  for (final String key in value.keys) {
    if (key != 'size' && key != 'slots') errors.add('\$.inventory.$key: unknown field');
  }
  final JElement? declared = value.get('size');
  int? size = declared is JNumber ? declared.asDecimal.intValueExact() : null;
  if (size == null || size < 9 || size > maxInventorySize || size % 9 != 0) {
    errors.add('\$.inventory.size: expected a multiple of 9 within 9..$maxInventorySize');
    size = maxInventorySize;
  }
  final JElement? contents = value.get('slots');
  if (contents is! JObject) {
    errors.add(r'$.inventory.slots: expected object');
    return;
  }
  for (final MapEntry<String, JElement> entry in contents.entries) {
    final String path = '\$.inventory.slots.${entry.key}';
    if (!RegExp(r'^(?:0|[1-9][0-9]?)$').hasMatch(entry.key)) {
      errors.add('$path: expected slot number');
      continue;
    }
    final int slot = int.parse(entry.key);
    if (slot >= size) {
      errors.add('$path: slot outside the inventory size $size');
      continue;
    }
    _item(entry.value, path, errors);
  }
}
