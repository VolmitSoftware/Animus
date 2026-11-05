/// Port of core's `CitizensTraits`: NPC metadata, navigator settings and the small state traits.
library;

import 'field_notes.dart';
import 'java_json.dart';
import 'java_lang.dart';
import 'runtime_behaviors.dart';

/// `BodyTraits.TEAM_COLORS`.
const List<String> teamColors = <String>[
  'black', 'dark_blue', 'dark_green', 'dark_aqua', 'dark_red', 'dark_purple', 'gold', 'gray', 'dark_gray', 'blue', 'green', 'aqua', 'red', //
  'light_purple', 'yellow', 'white',
];
const String citizensTag = 'CITIZENS_NPC';
const int maxTags = 32;
const double maxStep = 1.5;

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
  const TraitsResult(this.fields, this.migration, this.converted, this.notes, this.warnings);

  final JObject fields;
  final JObject migration;
  final Set<String> converted;
  final Map<String, FieldNote> notes;
  final List<String> warnings;
}

TraitsResult mapTraits(String type, JObject record, JObject traits, bool combat, JArray references) {
  final _Traits mapping = _Traits(type, record, traits, combat, references);
  mapping
    ..metadata()
    ..movement()
    ..age()
    ..gravity()
    ..scoreboard()
    ..attributes()
    ..rotation()
    ..targetable()
    ..spawned()
    ..mount()
    ..controllable()
    ..poses()
    ..horse();
  mapping.kept('/traitnames', 'Citizens bookkeeping of the traits the NPC had; nothing to convert');
  final List<String> sorted = List<String>.of(mapping.warnings)..sort();
  return TraitsResult(mapping.fields.deepCopy(), mapping.migration.deepCopy(), Set<String>.of(mapping.converted),
      Map<String, FieldNote>.of(mapping.notes), sorted);
}

JObject? _object(JElement? value) => value is JObject ? value : null;

bool? _bool(JElement? value) {
  if (value is! JPrimitive) return null;
  if (value is JBool) return value.value;
  final String text = value.asString;
  return text == 'true' ? true : (text == 'false' ? false : null);
}

bool _number(JElement? value) => value is JNumber && value.asDouble.isFinite;
bool _positive(JElement? value) => _number(value) && value!.asPrimitive.asDouble > 0;

class _Traits {
  _Traits(this.type, this.record, this.traits, this.combat, this.references);

  final String type;
  final JObject record;
  final JObject traits;
  final bool combat;
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
          if (flag == false) {
            converted.add(path);
          } else if (flag == true) {
            kept(path, 'Animus bodies have no vanilla mob AI; use routes, look-close or combat instead');
          }
        case 'protected':
          if (flag == true || flag == false && combat) {
            converted.add(path);
          } else if (flag == false) {
            kept(path, 'Animus NPCs take damage only through an enabled animus:combat module, which this NPC does not have');
          }
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
          if (flag == false) {
            converted.add(path);
          } else if (flag == true) {
            review(path, "Animus routes do not open doors; leave the doors on this NPC's route open or add points around them");
          }
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
    if (metadata != null && _bool(metadata.get('flyable')) == true) {
      movement.addBool('fly', true);
      any = true;
    }
    final JObject? trait = _object(traits.get('attributetrait'));
    final JObject? attributes = trait == null ? null : _object(trait.get('attributes'));
    final JElement? step = attributes?.get('minecraft:step_height');
    if (_number(step) && step!.asPrimitive.asDouble >= 0) {
      final double value = step.asPrimitive.asDouble;
      movement.addDouble('stepHeight', value < maxStep ? value : maxStep);
      const String path = '/traits/attributetrait/attributes/minecraft:step_height';
      if (value > maxStep) {
        note(path, statusConverted, 'Animus steps up at most 1.5 blocks without a jump, so higher steps are jumped');
      } else {
        converted.add(path);
      }
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
    final JObject? attributes = trait == null ? null : _object(trait.get('attributes'));
    if (attributes == null) return;
    for (final String key in attributes.keys) {
      if (key == 'minecraft:step_height') continue;
      kept('/traits/attributetrait/attributes/${pointer(key)}', 'attribute $key has no Animus setting');
    }
  }

  void rotation() {
    final JObject? rotation = _object(traits.get('rotationtrait'));
    final JObject? parameters = rotation == null ? null : _object(rotation.get('globalParameters'));
    if (parameters == null) return;
    for (final MapEntry<String, JElement> entry in parameters.entries) {
      final String path = '/traits/rotationtrait/globalParameters/${pointer(entry.key)}';
      final String value = entry.value is JPrimitive ? entry.value.asPrimitive.asString : '';
      if (entry.key == 'immediate' || value == _rotationDefaults[entry.key]) {
        converted.add(path);
      } else {
        review(path, 'Animus turns NPCs at once; gradual or head-only rotation is not available');
      }
    }
  }

  void targetable() {
    final JObject? targetable = _object(traits.get('targetable'));
    if (targetable == null) return;
    final bool? value = _bool(targetable.get('targetable'));
    if (value == false) {
      converted.add('/traits/targetable/targetable');
    } else if (value == true) {
      kept('/traits/targetable/targetable', 'Animus bodies are packet-only, so hostile mobs never target them');
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
    kept('/traits/poses/list', 'named poses are used by Citizens pose commands; Animus has no named poses, only the default facing');
  }

  void horse() {
    final JObject? horse = _object(traits.get('horsemodifiers'));
    if (horse == null) return;
    for (final String key in const <String>['color', 'style', 'carryingChest', 'tamed']) {
      if (horse.has(key)) review('/traits/horsemodifiers/$key', 'horse colours, markings and chests are not shown');
    }
  }
}
