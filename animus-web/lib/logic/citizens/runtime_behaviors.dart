/// Port of core's `CitizensBehaviors`: waypoints become an `animus:navigation` route, look-close becomes
/// `animus:look-close`, and text and commands become generated behaviors under `behaviors/citizens/`.
library;

import 'dart:math' as math;

import 'field_notes.dart';
import 'java_json.dart';

const double citizensWalkSpeed = 4.3;
const int _legHorizontal = 32;
const int _legVertical = 8;
const int _maxPoints = 256;
const int _maxCentres = 64;
const int _maxGuides = 1024;
const int _maxWait = 12000;
const int _maxCooldown = 72000;
const int _maxChoices = 125;
const int _maxText = 1024;
const int _maxProximity = 32;
const int _maxLookRange = 64;
const String _namespace = 'citizens';
const double _defaultLinkDistance = 75;
const int _defaultWanderWait = 60;

/// Lookclose booleans Citizens writes only when they differ from the server default, with Citizens' own defaults.
const Map<String, bool> lookDefaults = <String, bool>{'enabled': false, 'realisticlooking': false, 'disablewhilenavigating': true};
/// Look-close options Animus does not have, with what the NPC does instead, in key order.
const Map<String, String> _lookUnsupported = <String, String>{
  'headonly': 'Animus turns the whole body, not only the head',
  'linkedbody': 'Animus turns the whole body with the head',
  'perplayer': 'every viewer sees the NPC look at the same target',
  'randomSwitchTargets': 'the NPC keeps looking at the nearest target',
};

/// Citizens' default random look delay in ticks.
const int _defaultRandomLookDelay = 60;

/// `CitizensBehaviors.speed`: blocks per second for a Citizens speed modifier, within Animus' 1..5.
double citizensSpeed(double factor) {
  final double speed = javaRound(citizensWalkSpeed * factor * 100).toDouble() / 100.0;
  final double capped = speed < 5.0 ? speed : 5.0;
  return capped > 1.0 ? capped : 1.0;
}

class BehaviorDocument {
  const BehaviorDocument(this.path, this.content);

  final String path;
  final String content;
}

class RuntimeResult {
  const RuntimeResult(this.navigation, this.lookClose, this.behaviors, this.documents, this.warnings, this.converted, this.notes, this.points, this.guides);

  final JObject? navigation;
  final JObject? lookClose;
  final List<String> behaviors;
  final List<BehaviorDocument> documents;
  final List<String> warnings;
  final Set<String> converted;
  final Map<String, FieldNote> notes;
  final int points;
  final int guides;
}

/// `CitizensBehaviors.defaults`: the value an unset lookclose boolean had on the server that wrote the save.
JObject citizensDefaults(JObject npcs) {
  final Map<String, Set<bool>> written = <String, Set<bool>>{for (final String field in lookDefaults.keys) field: <bool>{}};
  for (final MapEntry<String, JElement> npc in npcs.entries) {
    final JObject? record = _object(npc.value);
    final JObject? traits = record == null ? null : _object(record.get('traits'));
    final JObject? look = traits == null ? null : _object(traits.get('lookclose'));
    if (look == null) continue;
    for (final String field in lookDefaults.keys) {
      final JElement? value = look.get(field);
      if (value is JBool) written[field]!.add(value.value);
    }
  }
  final JObject values = JObject();
  final JArray inferred = JArray();
  for (final MapEntry<String, bool> field in lookDefaults.entries) {
    final Set<bool> seen = written[field.key]!;
    if (seen.length == 1) {
      values.addBool(field.key, !seen.first);
      inferred.addString(field.key);
    } else {
      values.addBool(field.key, field.value);
    }
  }
  return JObject()
    ..add('lookclose', values)
    ..add('inferred', inferred);
}

/// `CitizensBehaviors.map`.
RuntimeResult mapRuntime(String key, String name, String world, JObject traits, JObject record, JObject defaults) {
  final _Runtime mapping = _Runtime(key, name, world, traits, record, defaults);
  final JObject? navigation = mapping.navigation();
  final JObject? look = mapping.lookClose();
  mapping.text();
  mapping.commands();
  final List<String> sorted = List<String>.of(mapping.warnings)..sort();
  return RuntimeResult(navigation, look, mapping.behaviors, mapping.documents, sorted, mapping.converted, Map<String, FieldNote>.of(mapping.notes), mapping.points,
      mapping.guides);
}

class _Command {
  const _Command(this.id, this.hand, this.node, this.cooldown);

  final int id;
  final String hand;
  final JObject node;
  final int cooldown;
}

class _Runtime {
  _Runtime(this.key, this.name, this.world, this.traits, this.record, this.defaults);

  final String key;
  final String name;
  final String world;
  final JObject traits;
  final JObject record;
  final JObject defaults;
  final List<String> warnings = <String>[];
  final Set<String> converted = <String>{};
  final Map<String, FieldNote> notes = <String, FieldNote>{};
  final List<String> behaviors = <String>[];
  final List<BehaviorDocument> documents = <BehaviorDocument>[];
  int points = 0;
  int guides = 0;

  // ---- Waypoints ----

  JObject? navigation() {
    final JObject? waypoints = _object(traits.get('waypoints'));
    if (waypoints == null) return null;
    const String base = '/traits/waypoints';
    final String provider = _string(waypoints.get('provider'), 'linear');
    if (waypoints.has('provider')) converted.add('$base/provider');
    final JObject data = _object(waypoints.get(provider)) ?? JObject();
    final String at = '$base/${pointer(provider)}';
    for (final String other in waypoints.keys) {
      if (other != 'provider' && other != provider) {
        notes['$base/${pointer(other)}'] = FieldNote(unsupportedPreserved, 'settings of the $other waypoint provider, which this NPC does not use');
      }
    }
    if (data.isEmpty) converted.add(at);
    final String order;
    final JArray route = JArray();
    final JArray helpers = JArray();
    int dropped = 0;
    final List<String> unsupported = <String>[];
    JObject? wander;
    int waitTicks = 0;
    double? link;
    switch (provider) {
      case 'linear':
        order = _bool(data.get('cycle'), false) ? 'pingpong' : 'sequence';
        if (data.has('cycle')) converted.add('$at/cycle');
        if (!_bool(data.get('pathfind'), true)) warnings.add('$at/pathfind: straight-line movement is not supported; the NPC plans paths');
        dropped += _points(data.get('points'), '$at/points', _maxPoints, route, true, true, unsupported);
      case 'guided':
        order = 'random';
        final String destinations = data.has('availablewaypoints') ? 'availablewaypoints' : 'destinations';
        final String helper = data.has('helperwaypoints') ? 'helperwaypoints' : 'guides';
        dropped += _points(data.get(destinations), '$at/$destinations', _maxPoints, route, true, true, unsupported);
        dropped += _points(data.get(helper), '$at/$helper', _maxGuides, helpers, true, false, unsupported);
        final JElement? distance = data.get('distance');
        final JObject? navigator = _object(record.get('navigator'));
        final JElement? range = navigator?.get('pathfindingrange');
        double value;
        if (_positive(distance)) {
          value = distance!.asPrimitive.asDouble;
          converted.add('$at/distance');
        } else if (_positive(range)) {
          value = range!.asPrimitive.asDouble;
          converted.add('/navigator/pathfindingrange');
        } else {
          value = _defaultLinkDistance;
        }
        if (value > 256) {
          warnings.add('$at/distance: guide links longer than 256 blocks became 256');
          value = 256.0;
        }
        if (value < 1) value = 1.0;
        link = value;
      case 'wander':
        order = 'wander';
        dropped += _points(data.get('regionCentres'), '$at/regionCentres', _maxCentres, route, false, false, unsupported);
        final JElement? xrange = data.get('xrange');
        final JElement? yrange = data.get('yrange');
        final JElement? delay = data.get('delay');
        final int radius = _number(xrange) ? _clamp(javaRound(xrange!.asPrimitive.asDouble), 1, _legHorizontal) : 25;
        final int height = _number(yrange) ? _clamp(javaRound(yrange!.asPrimitive.asDouble), 0, _legVertical) : 3;
        if (_number(xrange)) {
          converted.add('$at/xrange');
          if (xrange!.asPrimitive.asDouble > _legHorizontal) warnings.add('$at/xrange: wander ranges above 32 blocks became 32');
        }
        if (_number(yrange)) {
          converted.add('$at/yrange');
          if (yrange!.asPrimitive.asDouble > _legVertical) warnings.add('$at/yrange: wander heights above 8 blocks became 8');
        }
        if (_number(delay)) converted.add('$at/delay');
        waitTicks = _number(delay) && delay!.asPrimitive.asDouble >= 0 ? _floorClamp(delay.asPrimitive.asDouble, 0, _maxWait) : _defaultWanderWait;
        if (!_bool(data.get('pathfind'), true)) warnings.add('$at/pathfind: straight-line movement is not supported; the NPC plans paths');
        if (data.has('worldguardRegion') && data.get('worldguardRegion') is! JNull) {
          warnings.add('$at/worldguardRegion: WorldGuard wander regions are not supported; the NPC wanders around its centres');
        }
        wander = JObject()
          ..addInt('radius', radius)
          ..addInt('height', height);
      default:
        warnings.add('$base/provider: waypoint provider $provider is not supported; kept in citizens:review');
        return null;
    }
    final List<String> triggers = unsupported.toSet().toList()..sort();
    for (final String trigger in triggers) {
      warnings.add('$at: $trigger');
    }
    if (dropped > 0) warnings.add('$at: $dropped points in another world or without usable coordinates were left out');
    if (world.isEmpty) {
      if (route.isNotEmpty) warnings.add('$base: the NPC has no world, so its route was not converted');
      return null;
    }
    if (route.isEmpty && order != 'wander') return null;
    points = route.length;
    guides = helpers.length;
    final JObject module = JObject();
    module.addBool('enabled', false);
    module.addBool('reviewAccepted', false);
    module.addString('mode', 'walk');
    module.addDouble('speed', _speed());
    module.addString('order', order);
    if (order == 'sequence') module.addBool('loop', route.length > 1);
    module.addInt('waitTicks', waitTicks);
    module.add('points', route);
    if (order == 'random') {
      module.add('guides', helpers);
      module.addDouble('linkDistance', link!);
      final int isolated = _isolated(route, helpers, link);
      if (isolated > 0) {
        warnings.add('$at: $isolated of ${route.length} destinations have no guide or destination within the link distance and the 32-block leg limit, so the NPC never walks to them; add guide points between them in the route editor');
      }
    } else if (order == 'wander') {
      module.add('wander', wander);
    } else {
      final int far = _longLegs(route, order == 'sequence' && route.length > 1);
      if (far > 0) warnings.add('$at: $far legs are longer than 32 blocks across or 8 up or down; the NPC stops before the first one; add points between them in the route editor');
    }
    return module;
  }

  int _points(JElement? list, String path, int limit, JArray out, bool waypoint, bool triggers, List<String> unsupported) {
    int dropped = 0;
    for (final MapEntry<String, JElement> entry in listEntries(list)) {
      final String at = '$path/${entry.key}';
      final JObject? item = _object(entry.value);
      final JObject? location = waypoint ? _object(item?.get('location')) : item;
      final String locationPath = identical(location, item) ? at : '$at/location';
      if (location == null ||
          !_number(location.get('x')) ||
          !_number(location.get('y')) ||
          !_number(location.get('z')) ||
          world != _string(location.get('world'), '')) {
        dropped++;
        continue;
      }
      final double x = location.get('x')!.asPrimitive.asDouble;
      final double y = location.get('y')!.asPrimitive.asDouble;
      final double z = location.get('z')!.asPrimitive.asDouble;
      if (x.abs() > 29999984 || y.abs() > 29999984 || z.abs() > 29999984) {
        dropped++;
        continue;
      }
      if (out.length == limit) {
        warnings.add('$path: only the first $limit points were converted');
        break;
      }
      final JObject point = JObject();
      point.addDouble('x', x.floorToDouble() + 0.5);
      point.addBig('y', javaDoubleToLong(y.floorToDouble()));
      point.addDouble('z', z.floorToDouble() + 0.5);
      for (final String axis in const <String>['world', 'x', 'y', 'z']) {
        converted.add('$locationPath/$axis');
      }
      // Citizens walks to the block centre and never turns to a waypoint's facing; the world UUID only names the world.
      for (final String unused in const <String>['yaw', 'pitch', 'worldid']) {
        if (location.has(unused)) converted.add('$locationPath/$unused');
      }
      if (!triggers && item != null && item.has('triggers')) {
        notes['$at/triggers'] = const FieldNote(statusConverted, 'Citizens never runs triggers on guide waypoints');
      }
      if (triggers && item != null && item.has('triggers')) {
        int delay = 0;
        for (final MapEntry<String, JElement> trigger in listEntries(item.get('triggers'))) {
          final JObject? data = _object(trigger.value);
          final String type = data == null ? '' : _string(data.get('type'), '');
          final String triggerPath = '$at/triggers/${trigger.key}';
          if (type == 'delay' && (_number(data!.get('delay')) || !data.has('delay'))) {
            // Citizens leaves out a delay of 0, its default.
            if (data.has('delay')) {
              final int added = delay + _floorClamp(data.get('delay')!.asPrimitive.asDouble, 0, _maxWait + 1);
              delay = added < _maxWait + 1 ? added : _maxWait + 1;
            }
            converted.add('$triggerPath/type');
            converted.add('$triggerPath/delay');
          } else {
            unsupported.add('${type.isEmpty ? 'unknown' : type} waypoint triggers are not supported; kept in citizens:review');
          }
        }
        if (delay > _maxWait) warnings.add('$at/triggers: delays above 12000 ticks became 12000');
        if (delay > 0) point.addInt('waitTicks', delay > _maxWait ? _maxWait : delay);
      }
      out.add(point);
    }
    return dropped;
  }

  double _speed() {
    final JObject? navigator = _object(record.get('navigator'));
    final JElement? modifier = navigator?.get('speedmodifier');
    double factor = 1;
    if (_positive(modifier)) {
      factor = modifier!.asPrimitive.asDouble;
      converted.add('/navigator/speedmodifier');
    }
    return citizensSpeed(factor);
  }

  // ---- Look-close ----

  JObject? lookClose() {
    final JObject? look = _object(traits.get('lookclose'));
    if (look == null) return null;
    const String base = '/traits/lookclose';
    final JObject values = defaults.get('lookclose')! as JObject;
    final bool enabled = _flag(look, 'enabled', values.get('enabled')!.asPrimitive.asBoolean, base);
    final bool realistic = _flag(look, 'realisticlooking', values.get('realisticlooking')!.asPrimitive.asBoolean, base);
    final bool disabled = _flag(look, 'disablewhilenavigating', values.get('disablewhilenavigating')!.asPrimitive.asBoolean, base);
    double range = 10;
    if (_positive(look.get('range'))) {
      range = look.get('range')!.asPrimitive.asDouble;
      converted.add('$base/range');
    }
    if (range > _maxLookRange) {
      warnings.add('$base/range: look ranges above 64 blocks became 64; lower it in animus:look-close if that is too far');
      range = _maxLookRange.toDouble();
    }
    if (range < 1) range = 1;
    final List<String> options = _lookUnsupported.keys.toList()..sort();
    for (final String option in options) {
      final JElement? value = look.get(option);
      if (value is! JBool) continue;
      if (!value.value) {
        converted.add('$base/$option');
      } else {
        notes['$base/$option'] = FieldNote(requiresReview, _lookUnsupported[option]!);
      }
    }
    if (look.has('filter') && look.get('filter') is JPrimitive) {
      notes['$base/filter'] = const FieldNote(requiresReview, 'entity filters are not available; the NPC looks at the nearest target');
    }
    final bool npcs = _flag(look, 'targetnpcs', false, base);
    final JObject module = JObject()
      ..addBool('enabled', enabled)
      ..addDouble('range', range)
      ..addBool('lineOfSight', realistic)
      ..addBool('whileNavigating', !disabled);
    if (npcs) module.addBool('npcs', true);
    final JObject? random = _randomLook(look, base);
    if (random != null) module.add('randomLook', random);
    return module;
  }

  JObject? _randomLook(JObject look, String base) {
    bool declared = false;
    for (final String key in const <String>['enableRandomLook', 'randomlookdelay', 'randomLookDelay', 'randomYawRange', 'randomPitchRange']) {
      declared |= look.has(key);
    }
    if (!declared) return null;
    final JObject random = JObject();
    random.addBool('enabled', _flag(look, 'enableRandomLook', false, base));
    final String delayKey = look.has('randomlookdelay') ? 'randomlookdelay' : 'randomLookDelay';
    final JElement? delay = look.get(delayKey);
    int ticks = _defaultRandomLookDelay;
    if (_number(delay)) {
      ticks = _floorClamp(delay!.asPrimitive.asDouble, 1, _maxWait);
      converted.add('$base/$delayKey');
    }
    random.addInt('delayTicks', ticks);
    random.add('yaw', _angles(look, 'randomYawRange', -360, 360, 0, 360, base));
    random.add('pitch', _angles(look, 'randomPitchRange', -90, 90, 0, 0, base));
    return random;
  }

  JArray _angles(JObject look, String key, double minimum, double maximum, double low, double high, String base) {
    final List<MapEntry<String, JElement>> bounds = listEntries(look.get(key));
    if (bounds.length == 2 && _number(bounds[0].value) && _number(bounds[1].value)) {
      final double a = bounds[0].value.asPrimitive.asDouble;
      final double b = bounds[1].value.asPrimitive.asDouble;
      final double smaller = math.min(a, b);
      final double larger = math.max(a, b);
      low = smaller > minimum ? smaller : minimum;
      high = larger < maximum ? larger : maximum;
      for (final MapEntry<String, JElement> bound in bounds) {
        converted.add('$base/$key/${bound.key}');
      }
    }
    return JArray()
      ..addDouble(low)
      ..addDouble(high);
  }

  bool _flag(JObject object, String field, bool fallback, String base) {
    final JElement? value = object.get(field);
    if (value is JBool) {
      converted.add('$base/$field');
      return value.value;
    }
    return fallback;
  }

  // ---- Text ----

  void text() {
    final JObject? text = _object(traits.get('text'));
    if (text == null) return;
    const String base = '/traits/text';
    final List<List<String>> lines = <List<String>>[];
    for (final MapEntry<String, JElement> entry in listEntries(text.get('text'))) {
      final JElement value = entry.value;
      if (value is! JString) continue;
      if (lines.length == _maxChoices) {
        warnings.add('$base/text: only the first $_maxChoices lines were converted');
        break;
      }
      final List<String> parts = <String>[];
      for (final String part in value.value.split(_newline)) {
        if (part.isEmpty) continue;
        String spoken = _placeholders(part, '$base/text/${entry.key}');
        if (spoken.length > _maxText) {
          warnings.add('$base/text/${entry.key}: cut to 1024 characters');
          spoken = spoken.substring(0, _maxText);
        }
        parts.add(spoken);
      }
      if (parts.isEmpty) continue;
      converted.add('$base/text/${entry.key}');
      lines.add(parts);
    }
    if (lines.isEmpty) return;
    final bool close = _bool(text.get('talk-close'), false);
    final bool random = _bool(text.get('random-talker'), false);
    for (final String field in const <String>['talk-close', 'random-talker', 'range', 'delay']) {
      if (text.has(field)) converted.add('$base/$field');
    }
    double range = _positive(text.get('range')) ? text.get('range')!.asPrimitive.asDouble : 5;
    if (range > _maxProximity) {
      warnings.add('$base/range: talk ranges above 32 blocks became 32');
      range = _maxProximity.toDouble();
    }
    final int cooldown = _number(text.get('delay')) && text.get('delay')!.asPrimitive.asDouble > 0
        ? _floorClamp(text.get('delay')!.asPrimitive.asDouble, 0, _maxCooldown)
        : 100;
    final String item = _string(text.get('talkitem'), 'default');
    if (item != 'default' && item != '*') warnings.add('$base/talkitem: a required held item is not supported; any click talks');
    if (_bool(text.get('speech-bubbles'), false)) warnings.add('$base/speech-bubbles: speech bubbles are not supported; the NPC speaks in chat');
    if (!_bool(text.get('send-text-to-chat'), true)) warnings.add('$base/send-text-to-chat: the NPC speaks in chat');
    final List<JObject> choices = <JObject>[
      for (final List<String> parts in lines) _sequence(<JObject>[for (final String part in parts) _action('animus:say', _arguments('text', part))]),
    ];
    final JObject main = _select(choices, 'text-$key', 'npc', random);
    final JObject trigger = JObject();
    if (close) {
      trigger
        ..addString('id', 'near')
        ..addString('event', 'proximity')
        ..addString('phase', 'enter')
        ..addDouble('radius', range);
    } else {
      trigger
        ..addString('id', 'click')
        ..addString('event', 'interaction')
        ..addString('action', 'interact');
    }
    trigger.addInt('cooldownTicks', cooldown);
    _behavior('text-$key', 'main', JObject()..add('main', main), JArray(<JElement>[trigger]));
  }

  // ---- Commands ----

  void commands() {
    final JObject? trait = _object(traits.get('commandtrait'));
    if (trait == null) return;
    const String base = '/traits/commandtrait';
    if (_positive(trait.get('cost')) || _positive(trait.get('experienceCost')) || listEntries(trait.get('itemRequirements')).isNotEmpty) {
      warnings.add('$base: the commands cost money, experience or items, which Animus cannot charge, so none were converted');
      return;
    }
    if (listEntries(trait.get('temporaryPermissions')).isNotEmpty) {
      warnings.add('$base/temporaryPermissions: temporary permissions are not granted; player commands run with the player\'s own permissions');
    }
    final List<_Command> commands = <_Command>[];
    for (final MapEntry<String, JElement> entry in listEntries(trait.get('commands'))) {
      final String at = '$base/commands/${entry.key}';
      final JObject? data = _object(entry.value);
      if (data == null || !data.has('command') || data.get('command') is! JPrimitive) continue;
      final String hand = _string(data.get('hand'), 'BOTH').toUpperCase();
      if (hand != 'LEFT' && hand != 'RIGHT' && hand != 'BOTH') {
        warnings.add('$at/hand: $hand needs a sneaking click, which Animus does not tell apart; not converted');
        continue;
      }
      if (_bool(data.get('npc'), false)) {
        warnings.add('$at/npc: commands run as the NPC are not supported; not converted');
        continue;
      }
      if (_positive(data.get('cost')) || _positive(data.get('experienceCost')) || listEntries(data.get('itemCost')).isNotEmpty) {
        warnings.add('$at: the command costs money, experience or items, which Animus cannot charge; not converted');
        continue;
      }
      if (_positive(data.get('n')) || _positive(data.get('gn'))) {
        warnings.add('$at: use limits are not supported; not converted');
        continue;
      }
      String text = data.get('command')!.asPrimitive.asString;
      if (text.startsWith('/')) text = text.substring(1);
      text = _placeholders(text, '$at/command');
      if (text.isEmpty || text.length > _maxText) {
        warnings.add('$at/command: empty or longer than 1024 characters; not converted');
        continue;
      }
      final bool player = _bool(data.get('player'), false);
      final bool op = _bool(data.get('op'), false);
      if (op) warnings.add('$at/op: the command ran as an operator player; it now runs from the console');
      final JObject arguments = _arguments('command', text)..addString('as', player && !op ? 'player' : 'console');
      JObject node = _action('animus:command', arguments);
      if (_number(data.get('delay')) && data.get('delay')!.asPrimitive.asDouble >= 1) {
        final JObject wait = JObject()
          ..addString('type', 'wait')
          ..addInt('ticks', _floorClamp(data.get('delay')!.asPrimitive.asDouble, 1, _maxCooldown));
        node = _sequence(<JObject>[wait, node]);
        converted.add('$at/delay');
      }
      final List<MapEntry<String, JElement>> permissions = listEntries(data.get('permissions'));
      for (int index = permissions.length - 1; index >= 0; index--) {
        final JElement permission = permissions[index].value;
        if (permission is! JPrimitive) continue;
        node = JObject()
          ..addString('type', 'condition')
          ..addString('capability', 'animus:has-permission')
          ..add('arguments', _arguments('permission', permission.asString))
          ..add('then', node);
        converted.add('$at/permissions/${permissions[index].key}');
      }
      int seconds = 0;
      for (final String field in const <String>['cooldown', 'globalcooldown']) {
        if (_number(data.get(field))) {
          final int value = _floorClamp(data.get(field)!.asPrimitive.asDouble, 0, _maxCooldown);
          if (value > seconds) seconds = value;
          converted.add('$at/$field');
        }
      }
      if (_number(data.get('cooldown')) && data.get('cooldown')!.asPrimitive.asDouble > 0) {
        warnings.add('$at/cooldown: the per-player cooldown is one cooldown for the NPC');
      }
      for (final String field in const <String>['command', 'hand', 'player', 'op']) {
        if (data.has(field)) converted.add('$at/$field');
      }
      commands.add(_Command(int.parse(entry.key), hand, node, seconds));
    }
    if (commands.isEmpty) return;
    commands.sort((_Command a, _Command b) => a.id.compareTo(b.id));
    String mode = _string(trait.get('executionMode'), 'LINEAR').toUpperCase();
    if (trait.has('executionMode')) converted.add('$base/executionMode');
    if (!const <String>{'LINEAR', 'RANDOM', 'CYCLE', 'SEQUENTIAL'}.contains(mode)) {
      warnings.add('$base/executionMode: $mode is not known; commands run in order');
      mode = 'LINEAR';
    }
    final List<_Command> right = commands.where((_Command command) => command.hand != 'LEFT').toList();
    final List<_Command> left = commands.where((_Command command) => command.hand != 'RIGHT').toList();
    final JObject routines = JObject();
    final JArray triggers = JArray();
    if (_sameCommands(right, left)) {
      _routine('click', 'any', right, mode, routines, triggers);
    } else {
      if (right.isNotEmpty) _routine('right', 'interact', right, mode, routines, triggers);
      if (left.isNotEmpty) _routine('left', 'attack', left, mode, routines, triggers);
    }
    final String id = 'commands-$key';
    warnings.add('$base: commands stay denied until $_namespace:$id is on the [behaviors.actions] commandBehaviors list in config.toml');
    _behavior(id, routines.keys.first, routines, triggers);
  }

  void _routine(String routine, String action, List<_Command> commands, String mode, JObject routines, JArray triggers) {
    final List<JObject> nodes = <JObject>[];
    int seconds = 0;
    for (final _Command command in commands.sublist(0, commands.length < _maxChoices ? commands.length : _maxChoices)) {
      nodes.add(command.node);
      if (command.cooldown > seconds) seconds = command.cooldown;
    }
    if (commands.length > _maxChoices) warnings.add('/traits/commandtrait/commands: only the first $_maxChoices commands for one click were converted');
    final String quest = 'commands-$key-$routine';
    routines.add(
        routine,
        switch (mode) {
          'RANDOM' => _select(nodes, quest, 'npc', true),
          'CYCLE' => _select(nodes, quest, 'npc', false),
          'SEQUENTIAL' => _select(nodes, quest, 'relationship', false),
          _ => _sequence(nodes),
        });
    final JObject trigger = JObject()
      ..addString('id', routine)
      ..addString('event', 'interaction')
      ..addString('action', action)
      ..addString('routine', routine);
    if (seconds > 0) trigger.addInt('cooldownTicks', _clamp(BigInt.from(seconds * 20), 0, _maxCooldown));
    triggers.add(trigger);
  }

  void _behavior(String path, String entry, JObject routines, JArray triggers) {
    final JObject document = JObject()
      ..addInt('schemaVersion', 1)
      ..addInt('revision', 1)
      ..addString('id', '$_namespace:$path')
      ..addString('entry', entry)
      ..add('routines', routines)
      ..add('triggers', triggers);
    behaviors.add('$_namespace:$path');
    documents.add(BehaviorDocument('behaviors/$_namespace/$path.json', writeJson(document)));
  }

  String _placeholders(String text, String path) {
    final String result = text.replaceAll('<player>', '{player_name}').replaceAll('<p>', '{player_name}').replaceAll('<npc>', name).replaceAll('<id>', key);
    if (_untranslated.hasMatch(result)) warnings.add('$path: placeholders other than <p>, <player>, <npc> and <id> are not filled in');
    return result;
  }
}

final RegExp _newline = RegExp('<br>|\\n');
final RegExp _untranslated = RegExp(r'<[a-z_]+>|%[a-z_]+%');
final RegExp _listKey = RegExp(r'^(?:0|[1-9][0-9]{0,8})$');

bool _sameCommands(List<_Command> a, List<_Command> b) {
  if (a.length != b.length) return false;
  for (int index = 0; index < a.length; index++) {
    if (!identical(a[index], b[index])) return false;
  }
  return true;
}

JObject _select(List<JObject> choices, String quest, String scope, bool random) {
  if (choices.length == 1) return choices.first;
  final List<JObject> steps = <JObject>[];
  if (random) steps.add(_action('animus:set-quest-state', _quest(quest, scope)..addInt('random', choices.length)));
  for (int index = 0; index < choices.length; index++) {
    steps.add(_condition('animus:quest-state', _quest(quest, scope)..addInt('equals', index), choices[index]));
  }
  if (!random) {
    steps.add(_action('animus:advance-quest-state', _quest(quest, scope)..addInt('maximum', choices.length)));
    steps.add(_condition('animus:quest-state', _quest(quest, scope)..addInt('atLeast', choices.length),
        _action('animus:set-quest-state', _quest(quest, scope)..addInt('value', 0))));
  }
  return _sequence(steps);
}

JObject _quest(String name, String scope) => JObject()
  ..addString('name', name)
  ..addString('scope', scope);

JObject _action(String capability, JObject arguments) => JObject()
  ..addString('type', 'action')
  ..addString('capability', capability)
  ..add('arguments', arguments);

JObject _condition(String capability, JObject arguments, JObject then) => JObject()
  ..addString('type', 'condition')
  ..addString('capability', capability)
  ..add('arguments', arguments)
  ..add('then', then);

JObject _sequence(List<JObject> steps) {
  if (steps.length == 1) return steps.first;
  return JObject()
    ..addString('type', 'sequence')
    ..add('steps', JArray(<JElement>[...steps]));
}

JObject _arguments(String field, String value) => JObject()..addString(field, value);

bool _fits(JObject from, JObject to) =>
    (from.get('x')!.asPrimitive.asDouble - to.get('x')!.asPrimitive.asDouble).abs() <= _legHorizontal &&
    (from.get('z')!.asPrimitive.asDouble - to.get('z')!.asPrimitive.asDouble).abs() <= _legHorizontal &&
    (from.get('y')!.asPrimitive.asDouble - to.get('y')!.asPrimitive.asDouble).abs() <= _legVertical;

int _longLegs(JArray route, bool loop) {
  int count = 0;
  for (int index = 1; index < route.length; index++) {
    if (!_fits(route[index - 1] as JObject, route[index] as JObject)) count++;
  }
  if (loop && !_fits(route[route.length - 1] as JObject, route[0] as JObject)) count++;
  return count;
}

int _isolated(JArray destinations, JArray helpers, double link) {
  final List<JObject> nodes = <JObject>[for (final JElement node in destinations.elements) node as JObject, for (final JElement node in helpers.elements) node as JObject];
  int count = 0;
  for (int index = 0; index < destinations.length; index++) {
    final JObject from = nodes[index];
    bool linked = false;
    for (int other = 0; other < nodes.length && !linked; other++) {
      if (other == index) continue;
      final JObject to = nodes[other];
      final double dx = from.get('x')!.asPrimitive.asDouble - to.get('x')!.asPrimitive.asDouble;
      final double dy = from.get('y')!.asPrimitive.asDouble - to.get('y')!.asPrimitive.asDouble;
      final double dz = from.get('z')!.asPrimitive.asDouble - to.get('z')!.asPrimitive.asDouble;
      linked = math.sqrt(dx * dx + dy * dy + dz * dz) <= link && _fits(from, to);
    }
    if (!linked) count++;
  }
  return count;
}

/// `CitizensBehaviors.entries`: items of a YAML sequence, or of a mapping with integer keys in numeric order.
List<MapEntry<String, JElement>> listEntries(JElement? value) {
  final List<MapEntry<String, JElement>> result = <MapEntry<String, JElement>>[];
  if (value is JArray) {
    for (int index = 0; index < value.length; index++) {
      result.add(MapEntry<String, JElement>('$index', value[index]));
    }
  } else if (value is JObject) {
    for (final MapEntry<String, JElement> entry in value.entries) {
      if (_listKey.hasMatch(entry.key)) result.add(entry);
    }
    result.sort((MapEntry<String, JElement> a, MapEntry<String, JElement> b) => int.parse(a.key).compareTo(int.parse(b.key)));
  }
  return result;
}

String pointer(String key) => key.replaceAll('~', '~0').replaceAll('/', '~1');
JObject? _object(JElement? value) => value is JObject ? value : null;
String _string(JElement? value, String fallback) => value is JString ? value.value : fallback;
bool _bool(JElement? value, bool fallback) => value is JBool ? value.value : fallback;
bool _number(JElement? value) => value is JNumber && value.asDouble.isFinite;
bool _positive(JElement? value) => _number(value) && value!.asPrimitive.asDouble > 0;
int _clamp(BigInt value, int minimum, int maximum) {
  if (value < BigInt.from(minimum)) return minimum;
  if (value > BigInt.from(maximum)) return maximum;
  return value.toInt();
}

int _floorClamp(double value, int minimum, int maximum) => _clamp(javaDoubleToLong(value.floorToDouble()), minimum, maximum);
