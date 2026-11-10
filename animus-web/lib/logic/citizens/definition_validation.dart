/// Port of core's `NpcDefinition.validate` with `NpcAppearance`, `NpcDisguise`, `HologramStyle`, `BodyTraits`, `NpcAttributes`, `NpcItem`, `ArmorStandLook`,
/// `NpcInventory` and `MovementSettings`.
///
/// The module validators (`WaypointSettings`, `CombatSettings`, `MountDeclaration`, `RideSettings`, `ShopBinding`,
/// `GuiBinding`, `HireTerms`) are not ported: the modules this converter writes are built to pass them, and the server
/// validates every definition again when an import is applied, so a module the browser accepted wrongly is still refused.
library;

import 'dart:convert';

import '../../model/animus_document.dart';
import '../gui/gui_json.dart';
import '../inspect/inspect_schemas.dart';
import '../npc/group_merge.dart' show groupMergeNpc;
import '../npc/combat_settings.dart';
import '../npc/group_model.dart';
import '../npc/route_model.dart' show movementStyles;
import '../npc/npc_attributes.dart';
import '../npc/npc_paths.dart' show npcSchemaVersion;
import 'citizens_traits.dart' show teamColors;
import 'java_json.dart';
import 'java_lang.dart';
import 'presentation.dart';

const List<String> _poses = <String>['standing', 'sneaking', 'sleeping', 'swimming', 'sitting'];
const List<String> _parts = <String>['head', 'body', 'leftArm', 'rightArm', 'leftLeg', 'rightLeg'];
const Set<String> _lookFlags = <String>{'small', 'arms', 'basePlate', 'invisible', 'marker'};
const Set<String> _itemFields = <String>{'id', 'count', 'components', 'imported'};
const Set<String> _hologramFields = <String>{'lines', 'lineHeight', 'viewRange', 'billboard', 'shadowed', 'seeThrough', 'interpolationDelay', 'interpolationDuration'};
const Set<String> _lineFields = <String>{'text', 'marginTop', 'marginBottom'};
const Set<String> _billboards = <String>{'center', 'fixed', 'vertical', 'horizontal'};
const Set<String> _movementFields = <String>{'speed', 'avoidWater', 'openDoors', 'stepHeight', 'maximumFall', 'pathRange', 'arrivalMargin', 'stuck', 'style', 'onLand', 'auto'};

/// `NpcAppearance.displayText`.
bool displayText(String? text) => text != null && text.length <= maxText && !text.runes.any(javaIsIsoControl);

List<String> validateDefinition(JObject object) {
  final List<String> errors = <String>[];
  _integer(object, 'schemaVersion', BigInt.from(npcSchemaVersion), BigInt.from(npcSchemaVersion), errors);
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
  if (object.has('disguise')) {
    final JElement? disguise = object.get('disguise');
    if (disguise is! JString || disguise.value.trim().isEmpty || disguise.value.length > 8192 || disguise.value.runes.any(javaIsIsoControl)) {
      errors.add(r'$.disguise: expected disguise text of 1-8192 characters without control characters');
    }
  }
  _inventory(object, errors);
  _movement(object, errors);
  _namedPoses(object, errors);
  if (object.has('modules') && object.get('modules') is! JObject) errors.add(r'$.modules: expected object');
  return errors;
}

/// `DefinitionSpec.activation` over a converted definition.
List<String> activationErrors(JObject definition) => definitionActivationErrors(asMap(jsonDecode(writeJson(definition))));

/// `DefinitionSpec.activation`: the rules every server applies when it spawns a definition, on its plain JSON. The
/// converter's preview counts with it, and the editor reports it on NPC documents and on their effective definitions.
List<String> definitionActivationErrors(JsonMap definition) {
  final List<String> errors = <String>[];
  if (definition.containsKey('skin') && definition['type'] != 'minecraft:player') errors.add(r'$.skin: textures require a player body');
  if (definition['pose'] == 'sitting' && _declaresMount(definition)) {
    errors.add(r'$.pose: a sitting NPC cannot also ride another NPC; remove the pose or the mount');
  }
  final Object? model = asMap(definition['modules'])['animus:modelengine'];
  if (definition.containsKey('rig') && model is Map && model['enabled'] != false) {
    errors.add(r'$.modules.animus:modelengine: an NPC shows either a rig or a Model Engine model; remove one');
  }
  return errors;
}

/// [errors] as `path: message` lines split into diagnostics.
List<Diagnostic> definitionDiagnostics(List<String> errors, {String code = 'definition'}) => <Diagnostic>[
      for (final String error in errors)
        Diagnostic(DiagnosticSeverity.error, error.substring(0, error.indexOf(': ')), error.substring(error.indexOf(': ') + 2), code: code),
    ];

/// The activation rules on an NPC document's own fields.
List<Diagnostic> npcActivationDiagnostics(Object? npc) =>
    npc is Map<String, Object?> ? definitionDiagnostics(definitionActivationErrors(npc), code: 'activation') : const <Diagnostic>[];

/// The activation rules on [npc]'s effective definition over [groups]: the server checks the merged definition, so a
/// group's skin on a llama member is refused too. Problems of the NPC's own fields are left to
/// [npcActivationDiagnostics]; the rest name the group that set the value.
List<Diagnostic> effectiveActivationDiagnostics(JsonMap npc, Map<String, JsonMap> groups) {
  final NpcEffective? effective = effectiveNpc(npc, groups);
  if (effective == null) return const <Diagnostic>[];
  final Set<String> own = definitionActivationErrors(npc).toSet();
  final List<String> errors = <String>[];
  for (final String error in definitionActivationErrors(effective.json)) {
    if (own.contains(error)) continue;
    final String field = error.substring(2, error.indexOf(': '));
    final String source = effective.sourceOf(field);
    errors.add(source == groupMergeNpc ? error : '$error (the $field comes from group $source)');
  }
  return definitionDiagnostics(errors, code: 'activation');
}

/// Server rules the generated NPC schema cannot say, on an editor document's plain JSON: control characters in the name
/// and hologram lines (`NpcAppearance.displayText`), an attribute set twice (`NpcAttributes`), inventory slots past its
/// size (`NpcInventory`), the pitch range (`DefinitionSpec.validate`), riding itself (`MountRelations`) and look-close
/// ranges (`LookClose`). [id] is the NPC's document id (`town/guard`), null for a group's values.
List<String> definitionRuleErrors(JsonMap definition, {String? id}) {
  final List<String> errors = <String>[];
  final Object? name = definition['name'];
  if (name is String && !displayText(name)) errors.add('\$.name: expected at most $maxText characters without control characters');
  final List<Object?> lines = asList(asMap(definition['hologram'])['lines']);
  for (int i = 0; i < lines.length; i++) {
    final Object? line = lines[i];
    final Object? text = line is Map<String, Object?> ? line['text'] : line;
    if (text is String && text.length <= maxText && !displayText(text)) {
      errors.add('\$.hologram.lines[$i]: expected at most $maxText characters without control characters');
    }
  }
  final Object? pitch = asMap(definition['position'])['pitch'];
  if (pitch is num && pitch.abs() > 90 && pitch.abs() <= 30000000) errors.add(r'$.position.pitch: must be within -90..90');
  final Set<String> attributes = <String>{};
  for (final String key in asMap(definition[attributesField]).keys) {
    if (!attributes.add(bareAttributeId(key))) errors.add('\$.$attributesField.$key: ${bareAttributeId(key)} is set twice');
  }
  final JsonMap inventory = asMap(definition['inventory']);
  final Object? size = inventory['size'];
  if (size is int && size >= 9 && size <= maxInventorySize && size % 9 == 0) {
    for (final String slot in asMap(inventory['slots']).keys) {
      if (RegExp(r'^(?:0|[1-9][0-9]?)$').hasMatch(slot) && int.parse(slot) >= size) errors.add('\$.inventory.slots.$slot: slot outside the inventory size $size');
    }
  }
  final JsonMap modules = asMap(definition['modules']);
  final Object? vehicle = asMap(modules['animus:mount'])['vehicle'];
  if (id != null && vehicle == id) errors.add(r'$.modules.animus:mount.vehicle: an NPC cannot ride itself');
  final JsonMap randomLook = asMap(asMap(modules['animus:look-close'])['randomLook']);
  for (final String key in const <String>['yaw', 'pitch']) {
    final List<Object?> range = asList(randomLook[key]);
    if (range.length == 2 && range[0] is num && range[1] is num && (range[0]! as num) > (range[1]! as num)) {
      errors.add('\$.modules.animus:look-close.randomLook.$key: the minimum is above the maximum');
    }
  }
  return errors;
}

/// The diagnostics of [definitionRuleErrors] for an NPC document at [id].
List<Diagnostic> npcRuleDiagnostics(Object? npc, String id) =>
    npc is Map<String, Object?> ? definitionDiagnostics(definitionRuleErrors(npc, id: id), code: 'definition') : const <Diagnostic>[];

/// `GroupDocument` and `GroupDocuments` rules the group schema cannot say: the id matches the file, the description
/// has no control characters, the file is at most 256 KiB ([bytes] long), and the values pass [definitionRuleErrors]
/// and the combat module's checks, under `$.values`.
List<String> groupDocumentErrors(JsonMap group, String id, int bytes) {
  final List<String> errors = <String>[];
  if (bytes > 256 * 1024) errors.add(r'$: the group file exceeds 256 KiB');
  final Object? declared = group['id'];
  if (declared is String && RegExp(r'^[a-z0-9][a-z0-9_-]{0,63}$').hasMatch(declared) && declared != id) {
    errors.add('\$.id: the group id is $declared but the file is $id');
  }
  final Object? description = group['description'];
  if (description is String && description.length <= maxText && !displayText(description)) {
    errors.add('\$.description: expected at most $maxText characters without control characters');
  }
  final JsonMap values = asMap(group['values']);
  for (final String error in definitionRuleErrors(values)) {
    errors.add('\$.values.${error.substring(2)}');
  }
  final JsonMap modules = asMap(values['modules']);
  if (modules.containsKey(combatModule)) {
    for (final Diagnostic problem in validateCombat(modules[combatModule])) {
      errors.add('\$.values.${problem.path.substring(2)}: ${problem.message}');
    }
  }
  return errors;
}

/// The diagnostics of [groupDocumentErrors] for a group document at [id] with [source].
List<Diagnostic> groupRuleDiagnostics(Object? group, String id, String source) =>
    group is Map<String, Object?> ? definitionDiagnostics(groupDocumentErrors(group, id, utf8.encode(source).length), code: 'group') : const <Diagnostic>[];

/// `MountDeclaration.parse(...).isPresent()` for well-formed documents.
bool _declaresMount(JsonMap definition) {
  final JsonMap modules = asMap(definition['modules']);
  if (modules.containsKey('animus:mount')) return asMap(modules['animus:mount'])['enabled'] != false;
  for (final Object? reference in asList(asMap(definition['migration'])['references'])) {
    final JsonMap entry = asMap(reference);
    if (entry['kind'] != 'mount') continue;
    return entry['status'] != 'none';
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
  _attributes(definition, errors);
}

/// `NpcAttributes.read`, against the attribute table of the embedded NPC schema.
void _attributes(JObject definition, List<String> errors) {
  final JElement? field = definition.get(attributesField);
  if (field == null) return;
  if (field is! JObject) {
    errors.add(r'$.attributes: expected an object of attribute ids and numbers');
    return;
  }
  final Map<String, AttributeSpec> table = attributeTable(InspectSchemas.embedded.npc);
  final Set<String> seen = <String>{};
  for (final MapEntry<String, JElement> entry in field.entries) {
    final String path = '\$.$attributesField.${entry.key}';
    final AttributeSpec? spec = table[bareAttributeId(entry.key)];
    final JElement value = entry.value;
    if (spec == null) {
      errors.add('$path: unknown attribute ${entry.key}; expected a vanilla attribute id such as scale, gravity or movement_speed');
    } else if (value is! JNumber) {
      errors.add('$path: expected a number');
    } else if (spec.problem(value.asDouble) case final String problem) {
      errors.add('$path: $problem');
    } else if (!seen.add(spec.id)) {
      errors.add('$path: ${spec.id} is set twice');
    }
  }
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
  for (final String key in const <String>['avoidWater', 'openDoors']) {
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
  final JElement? style = value.get('style');
  if (style != null && !(style is JString && movementStyles.contains(style.value))) errors.add('\$.movement.style: expected ${movementStyles.join(', ')}');
  final JElement? onLand = value.get('onLand');
  if (onLand != null && !(onLand is JString && (onLand.value == 'walk' || onLand.value == 'avoid'))) errors.add(r'$.movement.onLand: expected walk or avoid');
  final JElement? auto = value.get('auto');
  if (auto != null && auto is! JObject) errors.add(r'$.movement.auto: expected object');
  if (auto is JObject) {
    for (final String key in auto.keys) {
      if (key != 'swim' && key != 'crawl') errors.add('\$.movement.auto.$key: unknown field');
    }
    for (final String key in const <String>['swim', 'crawl']) {
      if (auto.has(key) && auto.get(key) is! JBool) errors.add('\$.movement.auto.$key: expected boolean');
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
