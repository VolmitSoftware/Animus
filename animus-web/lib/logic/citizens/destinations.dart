/// Port of core's `DestinationsMapping`: the Citizens trait of NPC Destinations (`traits.npcdestinations`) becomes an
/// `animus:schedule` module, installed off and unreviewed. The shared golden fixtures prove the two agree.
library;

import '../../mc/npc/npc_look.dart' show NpcSlot, mcDrawnSlots;
import 'citizens_converter.dart' show unusedSkin;
import 'field_notes.dart';
import 'java_json.dart';
import 'java_lang.dart';
import 'presentation.dart' show validItemId;
import 'runtime_behaviors.dart' show pointer;

const String destinationsTrait = 'npcdestinations';

/// The Citizens waypoint provider NPC Destinations registers under its own name.
const String destinationsProvider = 'npcdestinations';

const String _base = '/traits/$destinationsTrait';
const int _maximumLocations = 64, _maximumCommands = 16, _maximumCommand = 256, _maximumWander = 32, _maximumSeconds = 86400, _day = 24000;
const int _maximumItemCount = 99, _maximumComponents = 4096, _maximumImportedBytes = 16384;
final RegExp _name = RegExp(r'^[a-z0-9][a-z0-9_-]{0,31}$');
final RegExp _slot = RegExp(r'^(?:0|[1-9][0-9]{0,3})$');
final RegExp _javaInt = RegExp(r'^[+-]?[0-9]+$');
const Map<String, String> _itemSlots = <String, String>{'Head': 'head', 'Chest': 'chest', 'Legs': 'legs', 'Boots': 'feet', 'Hand': 'main_hand', 'OffHand': 'off_hand'};
const Set<String> _pathfinder = <String>{'citizens_Swim', 'citizens_NewPathFinder', 'citizens_AvoidWater', 'citizens_DistanceMargin', 'citizens_PathDistanceMargin', 'maxprocessingtime'};

/// A skin file to install as `skins/<hash>.json`.
class DestinationsSkin {
  const DestinationsSkin(this.hash, this.content);

  final String hash;
  final String content;
  String get path => 'skins/$hash.json';
}

class DestinationsResult {
  const DestinationsResult(this.module, this.movement, this.skins, this.converted, this.notes, this.warnings);

  /// Null when the trait has no usable destination.
  final JObject? module;
  final JObject movement;
  final List<DestinationsSkin> skins;
  final Set<String> converted;
  final Map<String, FieldNote> notes;
  final List<String> warnings;
}

/// `DestinationsMapping.convert`.
DestinationsResult convertDestinations(JElement? trait, String type, String world, Map<String, String> worlds) {
  final _Mapping mapping = _Mapping(type, world, worlds);
  final JArray locations = trait is JObject ? mapping.read(trait) : JArray();
  if (trait is! JObject) mapping.warnings.add('$_base: the trait is not a mapping; kept for review');
  JObject? module;
  if (locations.isNotEmpty) {
    module = JObject()
      ..addBool('enabled', true)
      ..add('locations', locations);
  }
  return DestinationsResult(module, mapping.movement, mapping.skins, mapping.converted, mapping.notes, mapping.warnings);
}

/// `DestinationsMapping.movement`: the trait's door and stuck settings added to the definition's movement.
void mergeDestinationsMovement(JObject definition, JObject movement) {
  if (movement.isEmpty) return;
  final JObject target = definition.get('movement') is JObject ? definition.get('movement')! as JObject : JObject();
  for (final MapEntry<String, JElement> field in movement.entries) {
    final JElement? known = target.get(field.key);
    final JElement value = field.value;
    if (known is JObject && value is JObject) {
      for (final MapEntry<String, JElement> inner in value.entries) {
        known.add(inner.key, inner.value.deepCopy());
      }
    } else {
      target.add(field.key, value.deepCopy());
    }
  }
  definition.add('movement', target);
}

class _Mapping {
  _Mapping(this.type, this.world, this.worlds);

  final String type;
  final String world;
  final Map<String, String> worlds;
  final JObject movement = JObject();
  final List<DestinationsSkin> skins = <DestinationsSkin>[];
  final Set<String> converted = <String>{};
  final Map<String, FieldNote> notes = <String, FieldNote>{};
  final List<String> warnings = <String>[];

  void note(String path, String status, String reason) => notes[path] = FieldNote(status, reason);

  JArray read(JObject trait) {
    final JArray locations = JArray();
    final JObject? destinations = _object(trait.get('Destinations'));
    if (destinations != null) {
      final Map<int, String> ordered = <int, String>{};
      for (final String key in destinations.keys) {
        if (_slot.hasMatch(key)) {
          ordered[int.parse(key)] = key;
        } else if (_pathfinder.contains(key)) {
          note('$_base/Destinations/$key', unsupportedPreserved, 'Citizens pathfinder setting; Animus plans its own paths');
        }
      }
      for (final int index in ordered.keys.toList()..sort()) {
        final String key = ordered[index]!;
        if (locations.length >= _maximumLocations) {
          note('$_base/Destinations/$key', requiresReview, 'a schedule holds at most $_maximumLocations locations');
          continue;
        }
        final JObject? location = _destination(destinations.get(key), '$_base/Destinations/$key');
        if (location != null) locations.add(location);
      }
    }
    _legacy(trait.get('Locations'), locations);
    _doors(trait);
    if (trait.has('TeleportOnNoPath')) {
      movement.add('stuck', JObject()..addBool('teleport', _truthy(trait.get('TeleportOnNoPath'))));
      converted.add('$_base/TeleportOnNoPath');
    }
    note('$_base/TeleportOnFailedStartLoc', unsupportedPreserved, 'movement.stuck.teleport decides what a blocked leg does');
    note('$_base/PauseForPlayers', unsupportedPreserved, 'Animus NPCs do not stop for nearby players');
    note('$_base/PauseTimeout', unsupportedPreserved, 'Animus NPCs do not stop for nearby players');
    note('$_base/blocksUnderSurface', unsupportedPreserved, 'Animus plans its own paths');
    note('$_base/AllowedBlocks', unsupportedPreserved, 'Animus plans its own paths over any walkable block');
    note('$_base/MaxDistFromDestination', unsupportedPreserved, 'movement.arrivalMargin decides when a location is reached');
    note('$_base/enabledplugins', statusConverted, 'the add-on settings of each location are reported with that location');
    return locations;
  }

  void _doors(JObject trait) {
    final bool gates = _truthy(trait.get('OpensGates')), wood = _truthy(trait.get('OpensWoodDoors'));
    if (gates || wood) movement.addBool('openDoors', true);
    for (final String key in const <String>['OpensGates', 'OpensWoodDoors']) {
      if (trait.has(key)) converted.add('$_base/$key');
    }
    if (_truthy(trait.get('OpensMetalDoors'))) {
      note('$_base/OpensMetalDoors', unsupportedPreserved, 'Animus NPCs never open iron doors');
    } else if (trait.has('OpensMetalDoors')) {
      converted.add('$_base/OpensMetalDoors');
    }
  }

  void _legacy(JElement? value, JArray locations) {
    if (value is! JArray) return;
    for (int index = 0; index < value.length; index++) {
      final String path = '$_base/Locations/$index';
      final JElement entry = value.elements[index];
      final List<String> parts = entry is JPrimitive ? javaSplit(entry.asString, ':') : const <String>[];
      final List<int?> numbers = <int?>[for (final String part in parts.take(4)) _parseInt(javaTrim(part))];
      if (parts.length < 4 || locations.length >= _maximumLocations || numbers.contains(null)) {
        note(path, requiresReview, 'not an x:y:z:time location, or more than $_maximumLocations locations');
        continue;
      }
      locations.add(JObject()
        ..addInt('time', numbers[3]! % _day)
        ..addInt('x', numbers[0]!)
        ..addInt('y', numbers[1]!)
        ..addInt('z', numbers[2]!));
      converted.add(path);
    }
  }

  JObject? _destination(JElement? value, String path) {
    final JObject? source = _object(value);
    if (source == null) {
      note(path, requiresReview, 'the destination is not a mapping');
      return null;
    }
    final JObject? place = _object(source.get('Location'));
    final String? from = place == null ? null : _text(place.get('world'));
    final String? mapped = from == null ? null : (worlds[from] ?? from);
    if (place == null || !_whole(place.get('x')) || !_whole(place.get('y')) || !_whole(place.get('z'))) {
      note(path, requiresReview, 'the destination has no block location');
      warnings.add('$path: a destination without a block location was left out');
      return null;
    }
    if (mapped == null || mapped != world) {
      note(path, requiresReview, "the destination lies in $from, not the NPC's world; Animus schedules stay in one world");
      warnings.add("$path: a destination in $from was left out; schedule locations lie in the NPC's own world");
      return null;
    }
    final int time = _integer(source.get('TimeOfDay'), 0);
    if (time < 0) {
      note(path, unsupportedPreserved, 'a destination with time -1 is only chosen by other plugins');
      return null;
    }
    final JObject location = JObject();
    final String? name = _alias(_text(source.get('AliasName')), '$path/AliasName');
    if (name != null) location.addString('name', name);
    location.addInt('time', time < _day - 1 ? time : _day - 1);
    if (time >= _day) warnings.add('$path/TimeOfDay: $time is past the end of the day; it became ${_day - 1}');
    converted.add('$path/TimeOfDay');
    for (final String axis in const <String>['x', 'y', 'z']) {
      location.addInt(axis, (place.get(axis)! as JNumber).asDecimal.intValueExact()!);
    }
    for (final String axis in const <String>['world', 'x', 'y', 'z']) {
      converted.add('$path/Location/$axis');
    }
    _facing(place, location, '$path/Location');
    converted.add('$path/locationid');
    note('$path/MaxDistance', unsupportedPreserved, 'movement.arrivalMargin decides when a location is reached');
    _probability(_object(source.get('Probability')), location, '$path/Probability');
    final int weather = _integer(source.get('WeatherFlag'), 0);
    if (weather == 1 || weather == 2) location.addString('weather', weather == 1 ? 'clear' : 'storm');
    if (source.has('WeatherFlag')) converted.add('$path/WeatherFlag');
    _wander(_object(source.get('WanderSettings')), location, '$path/WanderSettings');
    for (final String key in const <String>['UseBlockSetting', 'Use_Blocks']) {
      _blocks(source, key, '$path/$key');
    }
    _items(_object(source.get('Items')), location, '$path/Items');
    _skin(_object(source.get('Skin')), location, '$path/Skin');
    _commands(_object(source.get('Commands')), location, '$path/Commands');
    note('$path/Pause', unsupportedPreserved, 'Animus NPCs do not stop for nearby players');
    note('$path/Citizens', unsupportedPreserved, 'Citizens pathfinder setting; Animus plans its own paths');
    _addons(source, location, path);
    return location;
  }

  String? _alias(String? alias, String path) {
    if (alias == null || javaIsBlank(alias)) {
      converted.add(path);
      return null;
    }
    String name = javaLower(alias).replaceAll(RegExp('[^a-z0-9_-]+'), '-').replaceAll(RegExp('^[-_]+'), '');
    if (name.length > 32) name = name.substring(0, 32);
    if (!_name.hasMatch(name)) {
      note(path, requiresReview, 'the alias has no letters or digits to name the location with');
      return null;
    }
    converted.add(path);
    if (name != alias) notes[path] = FieldNote(statusConverted, 'named $name: lowercase letters, digits, _ and -');
    return name;
  }

  void _facing(JObject place, JObject location, String path) {
    final double? yaw = _decimal(place.get('yaw')), pitch = _decimal(place.get('pitch'));
    if (yaw != null && (yaw != 0 || (pitch != null && pitch != 0))) {
      location.addDouble('yaw', yaw.remainder(360));
      if (pitch != null && pitch != 0) location.addDouble('pitch', pitch.clamp(-90.0, 90.0));
    }
    for (final String axis in const <String>['yaw', 'pitch']) {
      if (place.has(axis)) converted.add('$path/$axis');
    }
  }

  void _probability(JObject? probability, JObject location, String path) {
    if (probability == null) return;
    final int chance = _integer(probability.get('ChancePercent'), 0);
    if (chance > 0 && chance < 100) location.addInt('chance', chance);
    final int min = _integer(probability.get('Min_Time'), 0), max = _integer(probability.get('Max_Time'), 0);
    if (min > 0) {
      final int low = min < _maximumSeconds ? min : _maximumSeconds;
      location.add('staySeconds', JArray(<JElement>[JInt.of(low), JInt.of(max.clamp(low, _maximumSeconds))]));
    }
    for (final String key in probability.keys) {
      converted.add('$path/$key');
    }
  }

  void _wander(JObject? wander, JObject location, String path) {
    if (wander == null) return;
    final double? distance = _decimal(wander.get('Wandering_Distance'));
    if (distance != null && distance > 0) {
      final JObject settings = JObject();
      final BigInt rounded = javaRound(distance);
      final int radius = rounded < BigInt.one ? 1 : (rounded > BigInt.from(_maximumWander) ? _maximumWander : rounded.toInt());
      if (distance > _maximumWander) warnings.add('$path/Wandering_Distance: wandering farther than $_maximumWander blocks became $_maximumWander');
      settings.addInt('radius', radius);
      final int min = _integer(wander.get('Wait_Minimum'), 0), max = _integer(wander.get('Wait_Maximum'), 0);
      if (min > 0 && max > 0) {
        final int high = min > max ? min : max;
        settings.add('waitSeconds', JArray(<JElement>[JInt.of(min < max ? min : max), JInt.of(high < _maximumSeconds ? high : _maximumSeconds)]));
      }
      location.add('wander', settings);
    }
    for (final String key in const <String>['Wandering_Distance', 'Wait_Minimum', 'Wait_Maximum']) {
      if (wander.has(key)) converted.add('$path/$key');
    }
    final String? region = _text(wander.get('Wandering_Region'));
    if (region != null && !javaIsBlank(region)) {
      note('$path/Wandering_Region', requiresReview, "a WorldGuard region; give the location wander.area with the region's corners");
      warnings.add('$path/Wandering_Region: the NPC wandered in WorldGuard region $region; set wander.area to its corners');
    } else if (wander.has('Wandering_Region')) {
      converted.add('$path/Wandering_Region');
    }
    _blocks(wander, 'Wandering_UseBlocks', '$path/Wandering_UseBlocks');
  }

  void _blocks(JObject parent, String key, String path) {
    if (!parent.has(key)) return;
    if (_truthy(parent.get(key))) {
      note(path, unsupportedPreserved, 'Animus wanders over any walkable block');
    } else {
      converted.add(path);
    }
  }

  void _items(JObject? items, JObject location, String path) {
    if (items == null) return;
    final Map<String, JObject> equipment = <String, JObject>{};
    for (final MapEntry<String, String> slot in _itemSlots.entries) {
      final JElement? value = items.get(slot.key);
      if (value == null) continue;
      if (value is JNull) {
        converted.add('$path/${slot.key}');
        continue;
      }
      final String at = '$path/${slot.key}';
      final JObject? item = _item(value, at);
      if (item == null) continue;
      if (mcDrawnSlots(type).any((NpcSlot drawn) => drawn.key == slot.value)) {
        equipment[slot.value] = item;
        continue;
      }
      bool under(String field) => field == at || field.startsWith('$at/');
      converted.removeWhere(under);
      notes.removeWhere((String field, FieldNote _) => under(field));
      note(at, unsupportedPreserved, 'a $type body does not show the ${slot.value} slot');
    }
    if (equipment.isNotEmpty) {
      final JObject sorted = JObject();
      for (final String slot in equipment.keys.toList()..sort()) {
        sorted.add(slot, equipment[slot]);
      }
      location.add('equipment', sorted);
    }
    if (_truthy(items.get('Clear'))) location.addBool('clearEquipment', true);
    if (items.has('Clear')) converted.add('$path/Clear');
  }

  JObject? _item(JElement value, String path) {
    final JObject? source = _object(value);
    final String? raw = source == null ? null : (_text(source.get('id')) ?? _text(source.get('type')));
    if (raw == null) {
      note(path, requiresReview, 'the item has no type');
      return null;
    }
    String id = javaLower(raw);
    if (!id.contains(':')) id = 'minecraft:$id';
    if (id == 'minecraft:air') {
      converted.add(path);
      return null;
    }
    if (!validItemId(id)) {
      note(path, requiresReview, 'the item type is not a namespaced id');
      return null;
    }
    final JObject item = JObject()..addString('id', id);
    final int count = _integer(source!.has('count') ? source.get('count') : source.get('amount'), 1);
    item.addInt('count', count.clamp(1, _maximumItemCount));
    for (final String key in const <String>['==', 'v', 'DataVersion', 'schema_version', 'id', 'type', 'count', 'amount']) {
      if (source.has(key)) converted.add('$path/${pointer(key)}');
    }
    final JObject? components = _object(source.get('components'));
    if (components != null) {
      final List<String> keys = components.keys.toList()..sort();
      final List<String> parts = <String>[
        for (final String key in keys)
          if (components.get(key) is JPrimitive) '$key=${(components.get(key)! as JPrimitive).asString}',
      ];
      final String text = '[${parts.join(',')}]';
      if (parts.isNotEmpty && parts.length == components.length && text.length <= _maximumComponents) {
        item.addString('components', text);
        for (final String key in components.keys) {
          converted.add('$path/components/${pointer(key)}');
        }
      } else {
        note('$path/components', requiresReview, "item components that do not fit an item text; kept in the item's imported data");
      }
    }
    if (source.has('meta')) note('$path/meta', requiresReview, "Bukkit item meta has no item text here; kept in the item's imported data");
    if ((source.has('meta') || (components != null && !item.has('components'))) && javaUtf8(writeJson(source)).length <= _maximumImportedBytes) {
      item.add('imported', source.deepCopy());
    }
    return item;
  }

  void _skin(JObject? skin, JObject location, String path) {
    if (skin == null) return;
    final String? name = _text(skin.get('Name')), texture = _text(skin.get('MetaData')), signature = _text(skin.get('Signature'));
    final bool named = name != null && !javaIsBlank(name);
    if (!named) {
      for (final String key in skin.keys) {
        converted.add('$path/$key');
      }
      return;
    }
    if (type != 'minecraft:player') {
      note(path, unsupportedPreserved, unusedSkin(type));
      return;
    }
    if (texture == null || javaIsBlank(texture)) {
      note(path, requiresReview, 'the skin $name was saved without its texture; set the location\'s skin by hand');
      return;
    }
    final JObject document = JObject()
      ..addString('skinName', name)
      ..addString('textureRaw', texture);
    if (signature != null && !javaIsBlank(signature)) document.addString('signature', signature);
    final String content = writeJson(document), hash = sha256Hex(content);
    skins.add(DestinationsSkin(hash, content));
    location.addString('skin', hash);
    if (!_truthy(skin.get('ApplyOnArrival'))) location.addBool('skinOnDeparture', true);
    for (final String key in skin.keys) {
      converted.add('$path/$key');
    }
  }

  void _commands(JObject? commands, JObject location, String path) {
    if (commands == null) return;
    final JElement? arrival = commands.get('arrival');
    if (arrival is! JArray) {
      if (arrival != null) note('$path/arrival', requiresReview, 'not a list of commands');
      return;
    }
    final JArray kept = JArray();
    for (int index = 0; index < arrival.length; index++) {
      final String at = '$path/arrival/$index';
      final JElement entry = arrival.elements[index];
      String command = entry is JPrimitive ? javaStrip(entry.asString) : '';
      while (command.startsWith('/')) {
        command = command.substring(1);
      }
      if (command.isEmpty || command.length > _maximumCommand || command.runes.any(javaIsIsoControl) || kept.length >= _maximumCommands) {
        note(at, requiresReview, 'empty, longer than $_maximumCommand characters or past $_maximumCommands commands');
        continue;
      }
      kept.addString(command);
      converted.add(at);
    }
    if (kept.isEmpty) return;
    location.add('commands', kept);
    warnings.add('$path: arrival commands run from the console, as they did under Citizens');
  }

  void _addons(JObject source, JObject location, String path) {
    final JElement? settings = source.get('PluginSettings');
    if (settings is JPrimitive && settings.asString.isEmpty) converted.add('$path/PluginSettings');
    final JObject? addons = _object(settings);
    if (addons != null) {
      for (final String addon in addons.keys) {
        final String at = '$path/PluginSettings/${pointer(addon)}';
        if (addon == 'Animations') {
          _animation(_object(addons.get(addon)), location, at);
        } else {
          note(at, requiresIntegration, 'settings of the NPC Destinations $addon add-on, which Animus does not run');
        }
      }
    }
    for (final String old in const <String>['Sentinel', 'JobsReborn']) {
      if (source.has(old)) note('$path/$old', requiresIntegration, 'settings of the NPC Destinations $old add-on, which Animus does not run');
    }
  }

  void _animation(JObject? animation, JObject location, String path) {
    if (animation == null) {
      note(path, requiresReview, 'not a mapping');
      return;
    }
    final String? setting = _text(animation.get('Setting'));
    final String? pose = setting == 'SLEEP' ? 'sleeping' : setting == 'SIT' ? 'sitting' : null;
    if (pose != null && type == 'minecraft:player') {
      location.addString('pose', pose);
      converted.add('$path/Setting');
    } else if (pose != null) {
      note('$path/Setting', unsupportedPreserved, 'only player bodies lie down or sit');
    } else if (setting == null || setting == 'NONE') {
      converted.add('$path/Setting');
    } else {
      note('$path/Setting', unsupportedPreserved, 'the ${javaLower(setting)} animation has no Animus equivalent');
    }
    note(path, unsupportedPreserved, 'animation sounds and timing have no Animus equivalent');
  }
}

JObject? _object(JElement? value) => value is JObject ? value : null;
String? _text(JElement? value) => value is JPrimitive ? value.asString : null;
bool _whole(JElement? value) => value is JNumber && value.asDecimal.intValueExact() != null;
bool _truthy(JElement? value) => value is JPrimitive && (value is JBool ? value.value : value.asString.toLowerCase() == 'true');

/// `Integer.parseInt`: null where Java throws.
int? _parseInt(String text) {
  if (!_javaInt.hasMatch(text)) return null;
  final BigInt value = BigInt.parse(text);
  return value.bitLength > 31 ? null : value.toInt();
}

/// `new BigDecimal(text.trim()).longValue()`, or [fallback] where Java throws.
int _integer(JElement? value, int fallback) {
  if (value is! JPrimitive) return fallback;
  final JavaDecimal? decimal = JavaDecimal.parse(javaTrim(value.asString));
  if (decimal == null) return fallback;
  final BigInt whole = decimal.scale <= 0 ? decimal.unscaled * BigInt.from(10).pow(-decimal.scale) : decimal.unscaled ~/ BigInt.from(10).pow(decimal.scale);
  return whole.toSigned(64).toInt();
}

/// `Double.parseDouble(text.trim())` when finite, else null.
double? _decimal(JElement? value) {
  if (value is! JPrimitive) return null;
  try {
    final double parsed = javaParseDouble(javaTrim(value.asString));
    return parsed.isFinite ? parsed : null;
  } on FormatException {
    return null;
  }
}
