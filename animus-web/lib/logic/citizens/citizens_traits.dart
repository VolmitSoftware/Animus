/// Port of core's `CitizensTraits`: NPC metadata, navigator settings and the small state traits.
library;

import 'dart:math' as math;

import '../inspect/inspect_schemas.dart';
import '../npc/npc_attributes.dart';
import 'field_notes.dart';
import 'java_json.dart';
import 'java_lang.dart';
import 'presentation.dart' show roundHalfUp;
import 'runtime_behaviors.dart';

/// `BodyTraits.TEAM_COLORS`.
const List<String> teamColors = <String>[
  'black', 'dark_blue', 'dark_green', 'dark_aqua', 'dark_red', 'dark_purple', 'gold', 'gray', 'dark_gray', 'blue', 'green', 'aqua', 'red', //
  'light_purple', 'yellow', 'white',
];
const String citizensTag = 'CITIZENS_NPC';
const int maxTags = 32;
const double maxStep = 1.5;

/// `CitizensTraits.WANDER_RADIUS`, `WANDER_HEIGHT` and `WANDER_WAIT`: vanilla random strolling.
const int wanderRadius = 10;
const int wanderHeight = 7;
const int wanderWait = 120;

/// `CitizensTraits.ROUTE_FALL`: the least drop a Citizens route walks.
const int routeFall = 3;

/// `NamedPoses.MAXIMUM_POSES`.
const int maxPoses = 64;

/// `BodyRules.canBeBaby`.
const Set<String> _babyTypes = <String>{
  'minecraft:armadillo', 'minecraft:axolotl', 'minecraft:bee', 'minecraft:camel', 'minecraft:cat', 'minecraft:chicken', 'minecraft:cow', //
  'minecraft:mooshroom', 'minecraft:dolphin', 'minecraft:donkey', 'minecraft:fox', 'minecraft:frog', 'minecraft:goat', 'minecraft:glow_squid', //
  'minecraft:happy_ghast', 'minecraft:hoglin', 'minecraft:horse', 'minecraft:llama', 'minecraft:mule', 'minecraft:ocelot', 'minecraft:panda', //
  'minecraft:parrot', 'minecraft:pig', 'minecraft:polar_bear', 'minecraft:rabbit', 'minecraft:sheep', 'minecraft:skeleton_horse', //
  'minecraft:sniffer', 'minecraft:squid', 'minecraft:strider', 'minecraft:trader_llama', 'minecraft:turtle', 'minecraft:villager', //
  'minecraft:wandering_trader', 'minecraft:wolf', 'minecraft:zombie_horse', 'minecraft:camel_husk', 'minecraft:nautilus', 'minecraft:zombie_nautilus', //
  'minecraft:zombie', 'minecraft:husk', 'minecraft:drowned', 'minecraft:zombie_villager', 'minecraft:piglin', 'minecraft:zoglin',
};
const Map<String, String> _rotationDefaults = <String, String>{
  'headOnly': 'false', 'immediate': 'false', 'linkedBody': 'false', 'lockPitch': 'false', 'maxPitchPerTick': '10.0', 'maxYawPerTick': '40.0',
};

class TraitsResult {
  const TraitsResult(this.fields, this.migration, this.converted, this.notes, this.warnings, this.raisedFall);

  final JObject fields;
  final JObject migration;
  final Set<String> converted;
  final Map<String, FieldNote> notes;
  final List<String> warnings;

  /// A Citizens route's fall distance below [routeFall] was raised to it.
  final bool raisedFall;
}

/// [patrolled] means the route comes from Citizens waypoints; it then opens doors and drops at least [routeFall] blocks.
TraitsResult mapTraits(String type, JObject record, JObject traits, bool combat, bool routed, bool patrolled, JArray references) {
  final _Traits mapping = _Traits(type, record, traits, combat, routed, patrolled, references);
  mapping
    ..metadata()
    ..movement()
    ..age()
    ..gravity()
    ..scoreboard()
    ..attributes()
    ..rotation()
    ..targetable()
    ..follow()
    ..home()
    ..pause()
    ..keptTraits()
    ..spawned()
    ..mount()
    ..controllable()
    ..poses();
  mapping.kept('/traitnames', 'Citizens bookkeeping of the traits the NPC had; nothing to convert');
  final List<String> sorted = List<String>.of(mapping.warnings)..sort();
  return TraitsResult(mapping.fields.deepCopy(), mapping.migration.deepCopy(), Set<String>.of(mapping.converted),
      Map<String, FieldNote>.of(mapping.notes), sorted, mapping.raisedFall);
}

JObject? _object(JElement? value) => value is JObject ? value : null;

bool? _bool(JElement? value) {
  if (value is! JPrimitive) return null;
  if (value is JBool) return value.value;
  final String text = value.asString;
  return text == 'true' ? true : (text == 'false' ? false : null);
}

/// A Citizens attribute key as a vanilla id: `minecraft:scale`, and the older
/// `generic_scale`, `generic.scale` and `zombie_spawn_reinforcements` forms.
String _attributeId(String key) {
  String id = key.toLowerCase();
  if (id.startsWith(attributeNamespace)) id = id.substring(attributeNamespace.length);
  id = id.replaceAll('.', '_');
  for (final String prefix in const <String>['generic_', 'player_', 'zombie_', 'horse_']) {
    if (id.startsWith(prefix)) return id.substring(prefix.length);
  }
  return id;
}

bool _number(JElement? value) => value is JNumber && value.asDouble.isFinite;
bool _positive(JElement? value) => _number(value) && value!.asPrimitive.asDouble > 0;

/// `CitizensTraits.targetable`: whether hostile mobs may target the NPC in Citizens.
bool citizensTargetable(JObject traits) {
  final JObject? targetable = _object(traits.get('targetable'));
  return targetable != null && _bool(targetable.get('targetable')) == true;
}

/// `CitizensTraits.targetableCombat`: a combat module that never attacks or fights back.
JObject targetableCombat() {
  final JObject module = JObject();
  module.addBool('enabled', true);
  final JObject targeting = JObject();
  targeting.addBool('fightBack', false);
  module.add('targeting', targeting);
  return module;
}

/// `CitizensTraits.health`: an NPC without Sentinel is killable unless Citizens protected it, which is the default.
JObject citizensHealth(JObject record) {
  final JObject? metadata = _object(record.get('metadata'));
  return JObject()..addBool('killable', (metadata == null ? null : _bool(metadata.get('protected'))) == false);
}

/// `CitizensTraits.unprotected`: the NPC's metadata turns Citizens' protection off.
bool citizensUnprotected(JObject record) {
  final JObject? metadata = _object(record.get('metadata'));
  return metadata != null && _bool(metadata.get('protected')) == false;
}

/// `CitizensTraits.wanders`: Citizens ran vanilla AI on a body that has some.
bool citizensWanders(String type, JObject record) {
  final JObject? metadata = _object(record.get('metadata'));
  return type != 'minecraft:player' && metadata != null && _bool(metadata.get('minecraft-ai')) == true;
}

/// `CitizensTraits.vanillaWander`: the wander route standing in for vanilla random strolling.
JObject vanillaWander(JObject record) {
  final JObject? navigator = _object(record.get('navigator'));
  final JElement? modifier = navigator?.get('speedmodifier');
  final JObject module = JObject();
  module.addBool('enabled', true);
  module.addString('mode', 'walk');
  module.addDouble('speed', citizensSpeed(_positive(modifier) ? modifier!.asPrimitive.asDouble : 1));
  module.addString('order', 'wander');
  module.addInt('waitTicks', wanderWait);
  module.add('points', JArray());
  final JObject wander = JObject();
  wander.addInt('radius', wanderRadius);
  wander.addInt('height', wanderHeight);
  module.add('wander', wander);
  return module;
}

/// A JSON string's text, or empty for anything else.
String _text(JElement? value) => value is JString ? value.value : '';

final RegExp _uuidPattern = RegExp(r'^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$');

String? _uuid(JElement? value) {
  final String text = _text(value);
  return _uuidPattern.hasMatch(text) ? text.toLowerCase() : null;
}

/// How close a Citizens follower stays when it sets no margin, and how far behind its charge it aims.
const double followDistance = 2;
const double followPoint = 1;

/// `CitizensTraits.follow`: Citizens' follow trait as an `animus:combat` guard, or null when it follows nobody.
JObject? citizensFollow(JObject traits, Set<String> npcUuids) {
  final JObject? follow = _object(traits.get('followtrait'));
  final String? target = follow == null ? null : _uuid(follow.get('followingUUID'));
  if (target == null) return null;
  final JObject guard = JObject();
  guard.addString(npcUuids.contains(target) ? 'npc' : 'player', target);
  final JElement? margin = follow!.get('margin');
  final double distance = _positive(margin) ? math.max(1.0, math.min(64.0, margin!.asPrimitive.asDouble)) : followDistance;
  guard.addDouble('followDistance', distance);
  guard.addDouble('pointRange', math.min(followPoint, distance));
  guard.addBool('protect', _bool(follow.get('protect')) == true);
  return guard;
}

/// `CitizensTraits.home`: Citizens' home trait as `animus:home` when its home is in the NPC's world.
JObject? citizensHome(JObject traits, String world) {
  final JObject? trait = _object(traits.get('hometrait'));
  final JObject? location = trait == null ? null : _object(trait.get('location'));
  if (location == null || world.isEmpty || world != _text(location.get('world')) || !_number(location.get('x')) || !_number(location.get('y')) || !_number(location.get('z'))) {
    return null;
  }
  final JObject home = JObject();
  for (final String axis in const <String>['x', 'y', 'z']) {
    final double value = location.get(axis)!.asPrimitive.asDouble;
    if (value.abs() > 29999984) return null;
    home.addDouble(axis, value);
  }
  if (_number(location.get('yaw'))) home.addDouble('yaw', _round3(location.get('yaw')!.asPrimitive.asDouble.remainder(360)));
  if (_number(location.get('pitch'))) {
    final double pitch = location.get('pitch')!.asPrimitive.asDouble;
    home.addDouble('pitch', _round3(pitch > 90 ? 90 : (pitch < -90 ? -90 : pitch)));
  }
  final JElement? delay = trait!.get('delay');
  final JElement? distance = trait.get('distance');
  if (_number(delay) && delay!.asPrimitive.asDouble > 0) home.addInt('delayTicks', math.min(72000, delay.asPrimitive.asDouble.floor()));
  if (_number(distance) && distance!.asPrimitive.asDouble > 0) home.addDouble('distance', math.min(256.0, distance.asPrimitive.asDouble));
  home.addString('return', _text(trait.get('strategy')) == 'PATHFIND' ? 'walk' : 'teleport');
  return home;
}

/// `CitizensTraits.mirror`: Citizens' mirror trait as `animus:mirror`.
JObject? citizensMirror(JObject traits) {
  final JObject? trait = _object(traits.get('mirrortrait'));
  if (trait == null || _bool(trait.get('enabled')) != true) return null;
  final JObject mirror = JObject()..addBool('skin', true);
  if (_bool(trait.get('mirrorName')) == true) mirror.addBool('name', true);
  if (_bool(trait.get('mirrorEquipment')) == true) mirror.addBool('equipment', true);
  return mirror;
}

/// `CitizensTraits.pause`: Citizens' pause-pathfinding trait as the `pause` of the NPC's route.
JObject? citizensPause(JObject traits) {
  final JObject? trait = _object(traits.get('pausepathfinding'));
  if (trait == null) return null;
  final JObject pause = JObject();
  final JElement? range = trait.get('playerrange');
  final JElement? ticks = trait.get('pauseticks');
  final JElement? lockout = trait.get('lockoutduration');
  if (_positive(range)) pause.addDouble('playerRange', math.min(64.0, range!.asPrimitive.asDouble));
  if (_bool(trait.get('rightclick')) == true) pause.addBool('onClick', true);
  if (!pause.has('playerRange') && !pause.has('onClick')) return null;
  pause.addInt('ticks', _positive(ticks) ? math.min(12000, math.max(1, ticks!.asPrimitive.asDouble.floor())) : 20);
  if (_positive(lockout)) pause.addInt('lockoutTicks', math.min(72000, lockout!.asPrimitive.asDouble.floor()));
  return pause;
}

/// `BigDecimal.valueOf(value).setScale(3, HALF_UP).doubleValue()`.
double _round3(double value) => double.parse(roundHalfUp(JavaDecimal.parse(javaDoubleToString(value))!, 3));

/// `Double.parseDouble` of a pose part, or null when it is not a finite number.
double? _decimal(String text) {
  try {
    final double value = javaParseDouble(text);
    return value.isFinite ? value : null;
  } on FormatException {
    return null;
  }
}

class _Traits {
  _Traits(this.type, this.record, this.traits, this.combat, this.routed, this.patrolled, this.references);

  final String type;
  final JObject record;
  final JObject traits;
  final bool combat;
  final bool routed;
  final bool patrolled;
  bool raisedFall = false;
  final JArray references;
  final JObject fields = JObject();
  final JObject migration = JObject();
  final Set<String> converted = <String>{};
  final Map<String, FieldNote> notes = <String, FieldNote>{};
  final List<String> warnings = <String>[];

  void note(String path, String status, String reason) => notes[path] = FieldNote(status, reason);
  void kept(String path, String reason) => note(path, unsupportedPreserved, reason);
  void review(String path, String reason) => note(path, requiresReview, reason);

  void metadata() {
    final JObject? metadata = _object(record.get('metadata'));
    if (metadata == null) return;
    for (final MapEntry<String, JElement> entry in metadata.entries) {
      final String key = entry.key;
      final String path = '/metadata/${pointer(key)}';
      final bool? flag = _bool(entry.value);
      switch (key) {
        case 'cached-skin-uuid' || 'cached-skin-uuid-name':
          if (entry.value is! JPrimitive) break;
          migration.addString(key == 'cached-skin-uuid' ? 'skinUuid' : 'skinName', entry.value.asPrimitive.asString);
          converted.add(path);
        case 'collidable':
          if (flag == false) {
            converted.add(path);
          } else if (flag == true) {
            kept(path, 'Animus bodies never push players or mobs, so a collidable NPC cannot be shown');
          }
        case 'glowing':
          if (flag == null) break;
          if (flag) fields.addBool('glowing', true);
          converted.add(path);
        case 'minecraft-ai':
          if (flag == false || flag == true && type == 'minecraft:player') {
            converted.add(path);
          } else if (flag == true && routed) {
            note(path, statusConverted,
                "vanilla AI becomes walking around within $wanderRadius blocks, or the NPC's own route; vanilla attacking and fleeing do not run");
          } else if (flag == true) {
            kept(path, 'the NPC has no world to wander in');
          }
        case 'protected':
          if (flag != null) converted.add(path);
        case 'using-held-item':
          if (flag == null) break;
          if (flag) fields.addBool('usingItem', true);
          converted.add(path);
        case 'removefromplayerlist':
          if (flag == null) break;
          if (!flag && type == 'minecraft:player') fields.addBool('listed', true);
          converted.add(path);
        case 'flyable':
          if (flag != null) converted.add(path);
        case 'pathfinder-open-doors':
          if (flag != null) converted.add(path);
        case 'disable-default-stuck-action':
          if (flag != null) converted.add(path);
      }
    }
  }

  void movement() {
    final JObject? navigator = _object(record.get('navigator'));
    final JObject? metadata = _object(record.get('metadata'));
    final JObject movement = JObject();
    bool any = false;
    if (navigator != null) {
      final JElement? modifier = navigator.get('speedmodifier');
      if (_positive(modifier)) {
        movement.addDouble('speed', citizensSpeed(modifier!.asPrimitive.asDouble));
        converted.add('/navigator/speedmodifier');
        any = true;
      }
      final bool? avoid = _bool(navigator.get('avoidwater'));
      if (avoid != null) {
        movement.addBool('avoidWater', avoid);
        converted.add('/navigator/avoidwater');
        any = true;
      }
      final JElement? fall = navigator.get('falldistance');
      if (_number(fall)) {
        final double value = fall!.asPrimitive.asDouble;
        BigInt blocks = javaDoubleToLong(value.floorToDouble());
        if (blocks > BigInt.from(16)) blocks = BigInt.from(16);
        if (blocks < BigInt.zero) blocks = BigInt.zero;
        if (value > 16) warnings.add('/navigator/falldistance: falls longer than 16 blocks became 16');
        if (patrolled && blocks < BigInt.from(routeFall)) {
          blocks = BigInt.from(routeFall);
          raisedFall = true;
        }
        movement.addBig('maximumFall', blocks);
        converted.add('/navigator/falldistance');
        any = true;
      }
      final JElement? range = navigator.get('pathfindingrange');
      if (_positive(range)) {
        final double value = range!.asPrimitive.asDouble;
        movement.addDouble('pathRange', value > 256 ? 256 : (value < 1 ? 1 : value));
        converted.add('/navigator/pathfindingrange');
        any = true;
      }
      final JElement? margin = navigator.get('distancemargin');
      if (_number(margin) && margin!.asPrimitive.asDouble >= 0) {
        final double value = margin.asPrimitive.asDouble;
        movement.addDouble('arrivalMargin', value < 4 ? value : 4);
        converted.add('/navigator/distancemargin');
        any = true;
      }
      final JElement? teleport = navigator.get('destinationteleportmargin');
      if (_number(teleport) && teleport!.asPrimitive.asDouble <= 0) {
        converted.add('/navigator/destinationteleportmargin');
      } else if (_number(teleport)) {
        review('/navigator/destinationteleportmargin',
            'Animus walks the whole way instead of teleporting the last ${javaDoubleToString(teleport!.asPrimitive.asDouble)} blocks');
      }
      final bool? useDefault = _bool(navigator.get('usedefaultstuckaction'));
      final JElement? ticks = navigator.get('stationaryticks');
      final bool? disabled = metadata == null ? null : _bool(metadata.get('disable-default-stuck-action'));
      if (useDefault != null || _number(ticks)) {
        final JObject stuck = JObject();
        BigInt after = BigInt.from(100);
        if (_number(ticks) && ticks!.asPrimitive.asDouble >= 1) {
          after = javaDoubleToLong(ticks.asPrimitive.asDouble.floorToDouble());
          if (after > BigInt.from(12000)) after = BigInt.from(12000);
        }
        stuck.addBig('afterTicks', after);
        stuck.addBool('teleport', useDefault != false && disabled != true);
        movement.add('stuck', stuck);
        if (useDefault != null) converted.add('/navigator/usedefaultstuckaction');
        if (_number(ticks)) converted.add('/navigator/stationaryticks');
        any = true;
      }
    }
    // Citizens' navigator swims by default, so a route whose save sets no avoidwater crosses water at the surface.
    if (routed && !movement.has('avoidWater')) {
      movement.addBool('avoidWater', false);
      any = true;
    }
    if (metadata != null && _bool(metadata.get('flyable')) == true) {
      movement.addString('style', 'fly');
      any = true;
    }
    if (patrolled || metadata != null && _bool(metadata.get('pathfinder-open-doors')) == true) {
      movement.addBool('openDoors', true);
      any = true;
    }
    if (any) fields.add('movement', movement);
  }

  void age() {
    final JObject? age = _object(traits.get('age'));
    if (age == null) return;
    final JElement? value = age.get('age');
    if (age.has('locked')) converted.add('/traits/age/locked');
    if (!_number(value)) return;
    if (value!.asPrimitive.asDouble >= 0) {
      converted.add('/traits/age/age');
      return;
    }
    if (!_babyTypes.contains(type)) {
      kept('/traits/age/age', '$type bodies have no baby form');
      return;
    }
    fields.addBool('baby', true);
    converted.add('/traits/age/age');
  }

  void gravity() {
    final JObject? gravity = _object(traits.get('gravity'));
    if (gravity == null) return;
    final bool? noGravity = _bool(gravity.get('enabled'));
    if (noGravity == null) return;
    if (noGravity) fields.addBool('gravity', false);
    converted.add('/traits/gravity/enabled');
  }

  void scoreboard() {
    final JObject? scoreboard = _object(traits.get('scoreboardtrait'));
    if (scoreboard == null) return;
    final JElement? color = scoreboard.get('color');
    if (color is JPrimitive) {
      final String name = javaLower(color.asString);
      if (teamColors.contains(name)) {
        fields.addString('teamColor', name);
        converted.add('/traits/scoreboardtrait/color');
      } else if (name == 'reset') {
        converted.add('/traits/scoreboardtrait/color');
      } else {
        kept('/traits/scoreboardtrait/color', 'team colour ${color.asString} is a format, not a colour');
      }
    }
    final JArray tags = JArray();
    final Set<String> seen = <String>{};
    for (final MapEntry<String, JElement> entry in listEntries(scoreboard.get('tags'))) {
      final String path = '/traits/scoreboardtrait/tags/${entry.key}';
      final JElement value = entry.value;
      if (value is! JPrimitive) continue;
      final String tag = value.asString;
      if (tag == citizensTag) {
        converted.add(path);
        continue;
      }
      String slug = javaLower(tag).replaceAll(RegExp(r'[^a-z0-9_-]+'), '-').replaceFirst(RegExp(r'^[^a-z0-9]+'), '');
      if (slug.length > 32) slug = slug.substring(0, 32);
      if (slug.isEmpty) {
        kept(path, 'the tag has no letters or digits to keep');
        continue;
      }
      if (tags.length == maxTags) {
        kept(path, 'an NPC keeps at most 32 tags');
        continue;
      }
      if (seen.add(slug)) tags.addString(slug);
      converted.add(path);
    }
    if (tags.isNotEmpty) fields.add('tags', tags);
  }

  void attributes() {
    final JObject? trait = _object(traits.get('attributetrait'));
    final JObject attributes = (trait == null ? null : _object(trait.get('attributes'))) ?? JObject();
    final Map<String, AttributeSpec> table = attributeTable(InspectSchemas.embedded.npc);
    final JObject values = JObject();
    for (final MapEntry<String, JElement> entry in attributes.entries) {
      final String path = '/traits/attributetrait/attributes/${pointer(entry.key)}';
      final AttributeSpec? known = table[_attributeId(entry.key)];
      if (known == null) {
        kept(path, 'attribute ${entry.key} is not a vanilla attribute on any supported Minecraft version');
        continue;
      }
      if (!_number(entry.value)) {
        kept(path, 'the value is not a number');
        continue;
      }
      final double value = entry.value.asPrimitive.asDouble;
      final double clamped = value < known.minimum ? known.minimum : (value > known.maximum ? known.maximum : value);
      if (clamped != value) {
        warnings.add('$path: ${formatAttribute(value)} is outside ${known.range} for ${known.id} and became ${formatAttribute(clamped)}');
      }
      values.add(known.id, clamped == value ? entry.value : JDouble(clamped));
      if (known.id == 'step_height' && clamped > maxStep) {
        note(path, statusConverted, 'Animus steps up at most 1.5 blocks without a jump, so higher steps are jumped');
      } else {
        converted.add(path);
      }
    }
    scaledHealth(values, table);
    if (values.isNotEmpty) fields.add(attributesField, values);
  }

  /// ScaledMaxHealthTrait's maximum health becomes the max_health attribute, unless the attribute trait sets one.
  void scaledHealth(JObject values, Map<String, AttributeSpec> table) {
    final JObject? scaled = _object(traits.get('scaledhealthtrait'));
    if (scaled == null || !scaled.has('maxHealth')) return;
    const String path = '/traits/scaledhealthtrait/maxHealth';
    if (!_number(scaled.get('maxHealth'))) {
      kept(path, 'the value is not a number');
      return;
    }
    if (values.has('max_health')) {
      note(path, statusConverted, "the attribute trait's max_health is used");
      return;
    }
    final AttributeSpec known = table['max_health']!;
    final double value = scaled.get('maxHealth')!.asPrimitive.asDouble;
    final double clamped = value < known.minimum ? known.minimum : (value > known.maximum ? known.maximum : value);
    if (clamped != value) {
      warnings.add('$path: ${formatAttribute(value)} is outside ${known.range} for max_health and became ${formatAttribute(clamped)}');
    }
    values.add('max_health', clamped == value ? scaled.get('maxHealth') : JDouble(clamped));
    converted.add(path);
  }

  void rotation() {
    final JObject? rotation = _object(traits.get('rotationtrait'));
    final JObject? parameters = rotation == null ? null : _object(rotation.get('globalParameters'));
    if (parameters == null) return;
    final bool looks = traits.has('lookclose');
    for (final MapEntry<String, JElement> entry in parameters.entries) {
      final String path = '/traits/rotationtrait/globalParameters/${pointer(entry.key)}';
      final String value = entry.value is JPrimitive ? entry.value.asPrimitive.asString : '';
      if (entry.key == 'immediate' || value == _rotationDefaults[entry.key]) {
        converted.add(path);
      } else if (entry.key == 'headOnly' || entry.key == 'linkedBody') {
        // Look-close sets head-only and linked body itself each time it turns the NPC.
        note(path, statusConverted, "look-close's own head-only and linked-body settings apply");
      } else if (!_rotationDefaults.containsKey(entry.key)) {
        kept(path, 'rotation setting this converter does not know');
      } else if (looks) {
        note(path, statusConverted, 'became turnRate in animus:look-close');
      } else {
        note(path, statusConverted, 'only look-close turns gradually, and this NPC has no look-close');
      }
    }
  }

  void follow() {
    final JObject? follow = _object(traits.get('followtrait'));
    if (follow == null) return;
    if (_uuid(follow.get('followingUUID')) == null) {
      if (follow.has('followingUUID')) kept('/traits/followtrait/followingUUID', 'not a UUID, so the NPC follows nobody');
      for (final String field in const <String>['margin', 'protect']) {
        if (follow.has(field)) converted.add('/traits/followtrait/$field');
      }
      return;
    }
    for (final String field in const <String>['followingUUID', 'margin', 'protect']) {
      if (follow.has(field)) converted.add('/traits/followtrait/$field');
    }
    if (follow.has('followingUUID')) {
      note('/traits/followtrait/followingUUID', statusConverted, 'became the guard of animus:combat');
    }
  }

  void home() {
    final JObject? trait = _object(traits.get('hometrait'));
    if (trait == null) return;
    final JObject? location = _object(traits.get('location'));
    if (citizensHome(traits, location == null ? '' : _text(location.get('world'))) != null) {
      note('/traits/hometrait', statusConverted, '');
    } else {
      kept('/traits/hometrait', 'the home is missing or in another world than the NPC; set animus:home by hand');
    }
  }

  void pause() {
    final JObject? trait = _object(traits.get('pausepathfinding'));
    if (trait == null) return;
    final JObject? pause = citizensPause(traits);
    // A trait that pauses for nothing changes nothing.
    if (routed || pause == null) {
      note('/traits/pausepathfinding', statusConverted, '');
    } else {
      kept('/traits/pausepathfinding', 'the NPC has no route to pause');
    }
    if (routed && pause != null && _positive(trait.get('playerrange')) && trait.get('playerrange')!.asPrimitive.asDouble > 64) {
      warnings.add('/traits/pausepathfinding/playerrange: pause ranges above 64 blocks became 64');
    }
  }

  /// Citizens' `name;world;x;y;z` anchors become the definition's named `anchors`; one in the NPC's world names no world.
  void anchors() {
    final JObject? anchors = _object(traits.get('anchors'));
    if (anchors == null) return;
    final JObject? location = _object(traits.get('location'));
    final String own = location == null ? '' : _text(location.get('world'));
    final JObject named = JObject();
    for (final MapEntry<String, JElement> entry in listEntries(anchors.get('list'))) {
      final String path = '/traits/anchors/list/${entry.key}';
      final List<String> parts = entry.value is JPrimitive ? javaSplit(entry.value.asPrimitive.asString, ';') : const <String>[];
      final double? x = parts.length == 5 ? _decimal(parts[2]) : null;
      final double? y = parts.length == 5 ? _decimal(parts[3]) : null;
      final double? z = parts.length == 5 ? _decimal(parts[4]) : null;
      if (x == null || y == null || z == null || parts[1].trim().isEmpty || x.abs() > 29999984 || y.abs() > 29999984 || z.abs() > 29999984) {
        kept(path, 'not a name;world;x;y;z anchor');
        continue;
      }
      String name = javaLower(parts[0]).replaceAll(RegExp(r'[^a-z0-9_-]+'), '-').replaceFirst(RegExp(r'^[^a-z0-9]+'), '');
      if (name.length > 32) name = name.substring(0, 32);
      if (name.isEmpty) {
        kept(path, 'the anchor name has no letters or digits to keep');
        continue;
      }
      if (named.has(name)) {
        kept(path, 'another anchor already uses the name $name');
        continue;
      }
      if (named.length == 64) {
        kept(path, 'an NPC keeps at most 64 anchors');
        continue;
      }
      final JObject anchor = JObject()
        ..addDouble('x', x)
        ..addDouble('y', y)
        ..addDouble('z', z);
      if (parts[1] != own) anchor.addString('world', parts[1]);
      named.add(name, anchor);
      converted.add(path);
    }
    if (named.isNotEmpty) fields.add('anchors', named);
  }

  void keptTraits() {
    anchors();
    if (traits.has('mirrortrait')) note('/traits/mirrortrait', statusConverted, citizensMirror(traits) != null ? 'became animus:mirror' : '');
  }

  void targetable() {
    final JObject? targetable = _object(traits.get('targetable'));
    if (targetable == null) return;
    final bool? value = _bool(targetable.get('targetable'));
    if (value == false || value == true && combat) {
      converted.add('/traits/targetable/targetable');
    } else if (value == true) {
      kept('/traits/targetable/targetable', 'hostile mobs target only NPCs with an animus:combat module');
    }
  }

  void spawned() {
    if (traits.get('spawned') is JBool) converted.add('/traits/spawned');
  }

  void mount() {
    for (final JElement reference in references.elements) {
      final JObject entry = reference as JObject;
      if (entry.get('kind')!.asPrimitive.asString != 'mount') continue;
      final String status = entry.get('status')!.asPrimitive.asString;
      if (status == 'resolved') {
        converted.add('/traits/mounttrait/mountedon');
      } else {
        review('/traits/mounttrait/mountedon', 'the NPC this one rides is $status in the save; set animus:mount by hand');
      }
    }
  }

  void controllable() {
    final JObject? controllable = _object(traits.get('controllable'));
    if (controllable == null) return;
    if (_bool(controllable.get('enabled')) == false) converted.add('/traits/controllable/enabled');
  }

  void poses() {
    final JObject? poses = _object(traits.get('poses'));
    if (poses == null || !poses.has('list')) return;
    final JObject named = JObject();
    for (final MapEntry<String, JElement> entry in listEntries(poses.get('list'))) {
      final String path = '/traits/poses/list/${entry.key}';
      final List<String> parts = entry.value is JPrimitive ? javaSplit(entry.value.asPrimitive.asString, ';') : const <String>[];
      final double? yaw = parts.length >= 3 ? _decimal(parts[1]) : null;
      final double? pitch = parts.length >= 3 ? _decimal(parts[2]) : null;
      if (yaw == null || pitch == null) {
        kept(path, 'not a name;yaw;pitch pose');
        continue;
      }
      String name = javaLower(parts[0]).replaceAll(RegExp(r'[^a-z0-9_-]+'), '-').replaceFirst(RegExp(r'^[^a-z0-9]+'), '');
      if (name.length > 32) name = name.substring(0, 32);
      if (name.isEmpty) {
        kept(path, 'the pose name has no letters or digits to keep');
        continue;
      }
      if (named.has(name)) {
        kept(path, 'another pose already uses the name $name');
        continue;
      }
      if (named.length == maxPoses) {
        kept(path, 'an NPC keeps at most 64 poses');
        continue;
      }
      final JObject facing = JObject();
      facing.addDouble('yaw', _round3(yaw.remainder(360)));
      facing.addDouble('pitch', _round3(pitch > 90 ? 90 : (pitch < -90 ? -90 : pitch)));
      named.add(name, facing);
      converted.add(path);
    }
    if (named.isNotEmpty) fields.add('poses', named);
  }

}
