/// Port of core's `CitizensBehaviors`: waypoints become an `animus:navigation` route, look-close becomes
/// `animus:look-close`, and text and commands become generated behaviors under `behaviors/citizens/`.
library;

import 'dart:math' as math;

import 'destinations.dart' show destinationsProvider;
import 'citizens_stacks.dart';
import 'field_notes.dart';
import 'java_json.dart';
import 'java_lang.dart';
import 'presentation.dart' show roundHalfUp;

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
/// Citizens' player animations as `animus:animate`; elytra flight has no Animus animation.
const Map<String, String> _animations = <String, String>{
  'ARM_SWING': 'swing', 'ARM_SWING_OFFHAND': 'swing_offhand', 'HURT': 'hurt', 'CRIT': 'crit', 'MAGIC_CRIT': 'magic_crit', //
  'LEAVE_BED': 'wake', 'STOP_SLEEPING': 'wake', 'SLEEP': 'sleep', 'SIT': 'sit', 'STOP_SITTING': 'stand', 'SNEAK': 'sneak', //
  'STOP_SNEAKING': 'stand', 'EAT_FOOD': 'use_item', 'START_USE_MAINHAND_ITEM': 'use_item', 'START_USE_OFFHAND_ITEM': 'use_item', //
  'STOP_USE_ITEM': 'stop_using_item',
};

/// Citizens' rotation defaults: 40 degrees of yaw and 10 of pitch a tick.
const double _rotationYaw = 40;
const double _rotationPitch = 10;

/// `BigDecimal.valueOf(value).setScale(3, HALF_UP).doubleValue()`.
double _degrees(double value) => double.parse(roundHalfUp(JavaDecimal.parse(javaDoubleToString(value))!, 3));

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
  const _Command(this.id, this.hand, this.node);

  final int id;
  final String hand;
  final JObject node;
}

/// One cost: checked part by part so the player hears which part is missing, then taken at once.
class _Charge {
  _Charge(this.runtime, this.arguments);

  final _Runtime runtime;
  final JObject arguments;

  JObject take() => _action('animus:charge', arguments.deepCopy());

  JObject afford(JObject then) {
    JObject node = then;
    final JElement? items = arguments.get('items');
    if (items is JArray) {
      for (int index = items.length - 1; index >= 0; index--) {
        final JObject item = items[index] as JObject;
        final JObject part = JObject()..add('items', JArray(<JElement>[item.deepCopy()]));
        final String id = (item.get('item')! as JObject).get('id')!.asPrimitive.asString;
        node = _otherwise(_condition('animus:can-afford', part, node), runtime._message('MISSING_ITEM', _readable(id), item.get('amount')!.asPrimitive.asString));
      }
    }
    if (arguments.has('levels')) {
      node = _otherwise(_condition('animus:can-afford', _only('levels'), node), runtime._message('MISSING_EXPERIENCE', arguments.get('levels')!.asPrimitive.asString, ''));
    }
    if (arguments.has('money')) {
      node = _otherwise(_condition('animus:can-afford', _only('money'), node), runtime._message('MISSING_MONEY', plainNumber(arguments.get('money')!), ''));
    }
    return node;
  }

  JObject gate(JObject body) => afford(_sequence(<JObject>[take(), body]));

  JObject _only(String field) => JObject()..add(field, arguments.get(field)!.deepCopy());
}

const String _commandBase = '/traits/commandtrait';
const int _maxExpiry = 31536000;
/// Citizens' default error messages, by its error name.
const Map<String, String> _commandMessages = <String, String>{
  'NO_PERMISSION': "You don't have permission to do that.",
  'ON_COOLDOWN': 'Please wait before using this again.',
  'ON_GLOBAL_COOLDOWN': 'Please wait before using this again.',
  'MAXIMUM_TIMES_USED': 'You have reached the maximum number of uses ({0}).',
  'GLOBAL_MAXIMUM_TIMES_USED': 'You have reached the maximum number of uses ({0}).',
  'MISSING_MONEY': r'You need at least ${0}.',
  'MISSING_EXPERIENCE': 'You need at least {0} experience.',
  'MISSING_ITEM': 'Missing {1} {0}',
};

String _readable(String id) {
  final String path = id.substring(id.indexOf(':') + 1).replaceAll('_', ' ');
  return path.isEmpty ? id : '${path[0].toUpperCase()}${path.substring(1)}';
}

JObject _plain(String text) => _action('animus:message', _arguments('text', text.length > _maxText ? text.substring(0, _maxText) : text));

JObject _otherwise(JObject condition, JObject? otherwise) {
  if (otherwise != null) condition.add('else', otherwise);
  return condition;
}

/// `node` only when the behavior may run commands, checked before it takes anything for one.
JObject _allowed(JObject node) => _condition('animus:commands-allowed', JObject(), node);

/// Whether `node` or a step inside it runs `animus:command`.
bool _runsCommand(JElement? node) {
  if (node is JArray) {
    for (final JElement step in node.elements) {
      if (_runsCommand(step)) return true;
    }
    return false;
  }
  if (node is! JObject) return false;
  final JElement? capability = node.get('capability');
  if (capability is JPrimitive && capability.asString == 'animus:command') return true;
  for (final String field in const <String>['steps', 'then', 'else']) {
    if (_runsCommand(node.get(field))) return true;
  }
  return false;
}

JObject _check(String capability, JObject arguments) => JObject()
  ..addString('type', 'condition')
  ..addString('capability', capability)
  ..add('arguments', arguments);

JObject _stage(String name, String scope, int atLeast) => _check('animus:quest-state', _quest(name, scope)..addInt('atLeast', atLeast));

JObject _setFlag(String name, String scope, int seconds) {
  final JObject arguments = _quest(name, scope);
  if (seconds > 0) arguments.addInt('expireSeconds', seconds < 1 ? 1 : seconds);
  return _action('animus:set-flag', arguments);
}

bool _sameNodes(List<JObject> a, List<JObject> b) {
  if (a.length != b.length) return false;
  for (int index = 0; index < a.length; index++) {
    if (writeJson(a[index]) != writeJson(b[index])) return false;
  }
  return true;
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
  /// What each converted route point runs on arrival, by its index: chat and command waypoint triggers.
  final Map<int, JObject> arrivals = <int, JObject>{};
  bool waypointCommands = false;

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
      case destinationsProvider:
        // NPC Destinations walks the NPC itself; its trait converts to animus:schedule.
        return null;
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
    _arrivals();
    final JObject module = JObject();
    module.addBool('enabled', true);
    module.addString('mode', 'walk');
    module.addDouble('speed', _speed());
    module.addString('order', order);
    if (order == 'sequence') module.addBool('loop', route.length > 1);
    module.addInt('waitTicks', waitTicks);
    module.add('points', route);
    if (order == 'random') {
      module.add('guides', helpers);
      module.addDouble('linkDistance', link!);
    } else if (order == 'wander') {
      module.add('wander', wander);
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
        final List<JObject> steps = <JObject>[];
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
          } else if (type == 'speed') {
            // Citizens sets the navigator's speed for every later leg; a SpeedTrigger saves 1 when left at its default.
            point.addDouble('speed', citizensSpeed(_positive(data!.get('speed')) ? data.get('speed')!.asPrimitive.asDouble : 1));
            converted.add('$triggerPath/type');
            converted.add('$triggerPath/speed');
          } else if (type == 'teleport') {
            _teleportTrigger(data!, triggerPath, point);
          } else if (type == 'chat') {
            _chatTrigger(data!, triggerPath, steps);
          } else if (type == 'command') {
            _commandTrigger(data!, triggerPath, steps);
          } else if (type == 'animation') {
            _animationTrigger(data!, triggerPath, point, steps);
          } else {
            unsupported.add('${type.isEmpty ? 'unknown' : type} waypoint triggers are not supported; kept in citizens:review');
          }
        }
        if (delay > _maxWait) warnings.add('$at/triggers: delays above 12000 ticks became 12000');
        if (delay > 0) point.addInt('waitTicks', delay > _maxWait ? _maxWait : delay);
        if (steps.isNotEmpty) arrivals[out.length] = _sequence(steps);
      }
      out.add(point);
    }
    return dropped;
  }

  /// A teleport trigger moves the NPC on arrival to the trigger's exact position and facing; one in another world names
  /// that world, and the route goes on there.
  void _teleportTrigger(JObject data, String path, JObject point) {
    if (_teleportTo(_object(data.get('location')), '$path/location', point)) converted.add('$path/type');
  }

  /// Writes the point's teleport from a Citizens location; false, with a warning, when it has no usable position.
  bool _teleportTo(JObject? location, String path, JObject point) {
    if (location == null || !_number(location.get('x')) || !_number(location.get('y')) || !_number(location.get('z'))) {
      warnings.add('$path: a teleport without a location was left out');
      return false;
    }
    final String target = _string(location.get('world'), '');
    if (target.isEmpty) {
      warnings.add('$path: a teleport without a world was left out');
      return false;
    }
    final JObject teleport = JObject();
    for (final String axis in const <String>['x', 'y', 'z']) {
      final double value = location.get(axis)!.asPrimitive.asDouble;
      if (value.abs() > 29999984) {
        warnings.add('$path: the teleport lies outside the world and was left out');
        return false;
      }
      teleport.addDouble(axis, value);
    }
    if (_number(location.get('yaw'))) teleport.addDouble('yaw', _degrees(location.get('yaw')!.asPrimitive.asDouble.remainder(360)));
    if (_number(location.get('pitch'))) {
      final double pitch = location.get('pitch')!.asPrimitive.asDouble;
      teleport.addDouble('pitch', _degrees(pitch > 90 ? 90 : (pitch < -90 ? -90 : pitch)));
    }
    if (target != world) teleport.addString('world', target);
    point.add('teleport', teleport);
    for (final String field in const <String>['world', 'x', 'y', 'z', 'yaw', 'pitch', 'worldid']) {
      if (location.has(field)) converted.add('$path/$field');
    }
    return true;
  }

  /// Animation trigger animations run in order on arrival; Citizens' `at` becomes the point's teleport when it has none.
  void _animationTrigger(JObject data, String path, JObject point, List<JObject> steps) {
    final int before = steps.length;
    for (final MapEntry<String, JElement> line in listEntries(data.get('animations'))) {
      final String name = line.value is JPrimitive ? line.value.asPrimitive.asString.toUpperCase() : '';
      final String? animation = _animations[name];
      if (animation == null) {
        warnings.add('$path/animations/${line.key}: the $name animation has no Animus animation; left out');
        continue;
      }
      steps.add(_action('animus:animate', _arguments('animation', animation)));
      converted.add('$path/animations/${line.key}');
    }
    final JObject? at = _object(data.get('at'));
    if (at != null && !point.has('teleport')) {
      _teleportTo(at, '$path/at', point);
    } else if (at != null) {
      warnings.add("$path/at: the point already teleports; the animation's own position was left out");
    }
    if (steps.length > before) converted.add('$path/type');
  }

  /// Chat trigger lines go to players within its radius, or to the NPC's world when Citizens' radius is unset.
  void _chatTrigger(JObject data, String path, List<JObject> steps) {
    double radius = _number(data.get('radius')) ? data.get('radius')!.asPrimitive.asDouble : -1;
    if (radius > _maxLookRange) {
      warnings.add('$path/radius: chat radii above 64 blocks became 64');
      radius = _maxLookRange.toDouble();
    }
    final int before = steps.length;
    for (final MapEntry<String, JElement> line in listEntries(data.get('lines'))) {
      if (line.value is! JPrimitive) continue;
      String text = line.value.asPrimitive.asString;
      if (text.contains('<p>') || text.contains('<player>')) {
        warnings.add('$path/lines/${line.key}: a waypoint has no player to name; left out');
        continue;
      }
      text = _placeholders(text, '$path/lines/${line.key}');
      if (text.isEmpty || text.length > _maxText) {
        warnings.add('$path/lines/${line.key}: empty or longer than 1024 characters; left out');
        continue;
      }
      final JObject arguments = _arguments('text', text)..addString('to', radius > 0 ? 'nearby' : 'world');
      if (radius > 0) arguments.addDouble('radius', math.max(1.0, radius));
      steps.add(_action('animus:message', arguments));
      converted.add('$path/lines/${line.key}');
    }
    if (steps.length > before) {
      converted.add('$path/type');
      if (data.has('radius')) converted.add('$path/radius');
    }
  }

  /// Command trigger commands run from the console, as Citizens runs them with no player.
  void _commandTrigger(JObject data, String path, List<JObject> steps) {
    final int before = steps.length;
    for (final MapEntry<String, JElement> line in listEntries(data.get('commands'))) {
      if (line.value is! JPrimitive) continue;
      String command = line.value.asPrimitive.asString;
      if (command.startsWith('/')) command = command.substring(1);
      if (command.contains('<p>') || command.contains('<player>')) {
        warnings.add('$path/commands/${line.key}: a waypoint has no player to name; left out');
        continue;
      }
      command = _placeholders(command, '$path/commands/${line.key}');
      if (command.isEmpty || command.length > _maxText) {
        warnings.add('$path/commands/${line.key}: empty or longer than 1024 characters; left out');
        continue;
      }
      steps.add(_action('animus:command', _arguments('command', command)..addString('as', 'console')));
      waypointCommands = true;
      converted.add('$path/commands/${line.key}');
    }
    if (steps.length > before) converted.add('$path/type');
  }

  /// The behavior `citizens:waypoints-<id>`: one routine per point with chat or command triggers, run on arrival.
  void _arrivals() {
    if (arrivals.isEmpty) return;
    final JObject routines = JObject();
    final JArray triggers = JArray();
    final List<int> indexes = arrivals.keys.toList()..sort();
    for (final int index in indexes) {
      final String routine = 'point-$index';
      routines.add(routine, arrivals[index]);
      triggers.add(JObject()
        ..addString('id', routine)
        ..addString('event', 'waypoint')
        ..addInt('waypoint', index)
        ..addString('routine', routine));
    }
    final String id = 'waypoints-$key';
    if (waypointCommands) {
      warnings.add('/traits/waypoints: waypoint commands stay denied until $_namespace:$id is on the [behaviors.actions] commandBehaviors list in config.toml');
    }
    _behavior(id, 'point-${indexes.first}', routines, triggers);
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

  /// The NPC is an armor stand: Citizens' `type` trait, with or without its namespace.
  bool _armorStand() {
    final JElement? type = traits.get('type');
    if (type is! JPrimitive) return false;
    final String key = type.asString.toLowerCase();
    return key == 'armor_stand' || key == 'minecraft:armor_stand';
  }

  // ---- Look-close ----

  JObject? lookClose() {
    final JObject? look = _object(traits.get('lookclose'));
    if (look == null) return null;
    const String base = '/traits/lookclose';
    final JObject values = defaults.get('lookclose')! as JObject;
    bool enabled = _flag(look, 'enabled', values.get('enabled')!.asPrimitive.asBoolean, base);
    // A posed stand that turns to players breaks the scene it was posed for; only a save that turns it on explicitly does.
    if (enabled && _armorStand() && !look.has('enabled')) {
      enabled = false;
      warnings.add("$base/enabled: an armor stand keeps its own facing and pose, so the server's look-close default is not applied; "
          'set enabled in animus:look-close to turn it toward players');
    }
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
    final bool npcs = _flag(look, 'targetnpcs', false, base);
    // A linked body turns with the head, as Animus turns every NPC; head-only looking leaves the body where it stands.
    final bool linked = _flag(look, 'linkedbody', false, base);
    final bool headOnly = _flag(look, 'headonly', false, base) && !linked;
    final bool perPlayer = _flag(look, 'perplayer', false, base);
    final JObject module = JObject()
      ..addBool('enabled', enabled)
      ..addDouble('range', range)
      ..addBool('lineOfSight', realistic)
      ..addBool('whileNavigating', !disabled);
    if (npcs) module.addBool('npcs', true);
    if (headOnly) module.addBool('headOnly', true);
    if (perPlayer) module.addBool('perPlayer', true);
    final JArray? permissions = _filter(look, base);
    if (permissions == null) {
      module.addBool('enabled', false);
      warnings.add('$base/filter: the filter admits no players, so look-close is off');
    } else if (permissions.isNotEmpty) {
      module.add('permissions', permissions);
    }
    final JObject? turn = _turnRate();
    if (turn != null) module.add('turnRate', turn);
    // Citizens switches to a random player in range every random look delay.
    if (_flag(look, 'randomSwitchTargets', false, base)) {
      final String delayKey = look.has('randomlookdelay') ? 'randomlookdelay' : 'randomLookDelay';
      final JElement? delay = look.get(delayKey);
      module.addInt('switchTicks', _number(delay) ? _floorClamp(delay!.asPrimitive.asDouble, 1, _maxWait) : _defaultRandomLookDelay);
      if (_number(delay)) converted.add('$base/$delayKey');
    }
    final JObject? random = _randomLook(look, base);
    if (random != null) module.add('randomLook', random);
    return module;
  }

  /// Citizens' look-close filter as the permissions a player must hold; a group becomes `group.<name>`. Null when no player passes.
  JArray? _filter(JObject look, String base) {
    final JArray permissions = JArray();
    final JElement? value = look.get('filter');
    if (value is! JPrimitive || value.asString.trim().isEmpty) return permissions;
    final String path = '$base/filter';
    bool groups = false;
    bool players = true;
    for (final String argument in value.asString.trim().split(RegExp(' +'))) {
      final int split = argument.indexOf('=');
      final String name = split < 0 ? argument : argument.substring(0, split);
      final List<String> values = split < 0 ? <String>[] : argument.substring(split + 1).split(',');
      switch (name.toLowerCase()) {
        case 'type':
          bool player = false;
          for (final String type in values) {
            player |= type.trim().toLowerCase() == 'player' || type.trim().toLowerCase() == 'minecraft:player';
          }
          players &= player;
        case 'permission' || 'perm':
          for (final String node in values) {
            if (node.trim().isNotEmpty && permissions.length < 8) permissions.addString(node.trim());
          }
        case 'group':
          for (final String group in values) {
            if (group.trim().isNotEmpty && permissions.length < 8) {
              permissions.addString('group.${group.trim().toLowerCase()}');
              groups = true;
            }
          }
        default:
          notes[path] = FieldNote(requiresReview, 'filter part $name is not known; the NPC looks at any player');
          return permissions;
      }
    }
    if (!players) {
      notes[path] = const FieldNote(requiresReview, 'the filter admits no players, so look-close is off');
      return null;
    }
    notes[path] = FieldNote(statusConverted, groups ? 'groups became group.<name> permissions, which LuckPerms and most permission plugins grant to group members' : '');
    return permissions;
  }

  /// Citizens' gradual rotation as the most a look turns each tick; null for its defaults or immediate turning.
  JObject? _turnRate() {
    final JObject? rotation = _object(traits.get('rotationtrait'));
    final JObject? parameters = rotation == null ? null : _object(rotation.get('globalParameters'));
    if (parameters == null || _bool(parameters.get('immediate'), false)) return null;
    final double yaw = _number(parameters.get('maxYawPerTick')) ? math.max(0.0, math.min(360.0, parameters.get('maxYawPerTick')!.asPrimitive.asDouble)) : _rotationYaw;
    double pitch = _number(parameters.get('maxPitchPerTick')) ? math.max(0.0, math.min(180.0, parameters.get('maxPitchPerTick')!.asPrimitive.asDouble)) : _rotationPitch;
    if (_bool(parameters.get('lockPitch'), false)) pitch = 0;
    if (yaw == _rotationYaw && pitch == _rotationPitch) return null;
    return JObject()
      ..addDouble('yaw', yaw)
      ..addDouble('pitch', pitch);
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
    final String? held = close || item == 'default' || item.contains('*') || item.isEmpty ? null : _heldItem(item);
    if (text.has('talkitem')) converted.add('$base/talkitem');
    // Speech bubbles show each line over the NPC for Citizens' bubble duration, 50 ticks by default.
    final bool bubbles = _bool(text.get('speech-bubbles'), false);
    final bool chat = !bubbles || _bool(text.get('send-text-to-chat'), true);
    final int bubbleTicks = _number(text.get('speech-bubble-duration')) && text.get('speech-bubble-duration')!.asPrimitive.asDouble > 0
        ? _floorClamp(text.get('speech-bubble-duration')!.asPrimitive.asDouble, 1, 1200)
        : 50;
    for (final String field in const <String>['speech-bubbles', 'send-text-to-chat', 'speech-bubble-duration']) {
      if (text.has(field)) converted.add('$base/$field');
    }
    final List<JObject> choices = <JObject>[
      for (final List<String> parts in lines)
        _sequence(<JObject>[
          for (final String part in parts) _action('animus:say', _spoken(part, bubbles ? bubbleTicks : 0, chat)),
        ]),
    ];
    JObject main = _select(choices, 'text-$key', 'npc', random);
    if (held != null) {
      main = JObject()
        ..addString('type', 'condition')
        ..addString('expression', held)
        ..add('then', main);
    }
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
    // Citizens spaces each player's lines on their own, so one player never holds back another.
    trigger.addInt('playerCooldownTicks', cooldown);
    _behavior('text-$key', 'main', JObject()..add('main', main), JArray(<JElement>[trigger]));
  }

  /// The arguments of one spoken line, with its speech bubble and without chat when Citizens sent none.
  static JObject _spoken(String part, int bubbleTicks, bool chat) {
    final JObject spoken = _arguments('text', part);
    if (bubbleTicks > 0) spoken.addInt('bubbleTicks', bubbleTicks);
    if (!chat) spoken.addBool('chat', false);
    return spoken;
  }

  /// Citizens' talk item, a comma list of material names, as a check of the clicking player's held item.
  static String? _heldItem(String pattern) {
    final List<String> checks = <String>[];
    for (final String part in pattern.split(',')) {
      String material = part.trim().toLowerCase();
      if (material.startsWith('minecraft:')) material = material.substring('minecraft:'.length);
      if (!_material.hasMatch(material)) continue;
      checks.add("same(player.item_in_hand, 'minecraft:$material')");
    }
    return checks.isEmpty ? null : checks.join(' || ');
  }

  // ---- Commands (port of core's CitizensCommands, without the add-on files the browser does not read) ----

  bool _hide = false;
  final Map<String, String> _custom = <String, String>{};

  void commands() {
    final Map<String, List<JObject>> clicks = <String, List<JObject>>{
      for (final String action in const <String>['interact', 'attack', 'sneak_interact', 'sneak_attack']) action: <JObject>[]
    };
    final JObject? trait = _object(traits.get('commandtrait'));
    if (trait != null) _citizensCommands(trait, clicks);
    final JObject routines = JObject();
    final JArray triggers = JArray();
    final List<JObject> right = clicks['interact']!;
    final List<JObject> left = clicks['attack']!;
    if (right.isNotEmpty && _sameNodes(right, left)) {
      _commandRoutine('click', 'any', right, routines, triggers);
    } else {
      _commandRoutine('right', 'interact', right, routines, triggers);
      _commandRoutine('left', 'attack', left, routines, triggers);
    }
    _commandRoutine('sneak-right', 'sneak_interact', clicks['sneak_interact']!, routines, triggers);
    _commandRoutine('sneak-left', 'sneak_attack', clicks['sneak_attack']!, routines, triggers);
    if (routines.isEmpty) return;
    warnings.add('$_commandBase: commands stay denied until $_namespace:commands-$key is on the [behaviors.actions] commandBehaviors list in config.toml');
    _behavior('commands-$key', routines.keys.first, routines, triggers);
  }

  void _commandRoutine(String routine, String action, List<JObject> steps, JObject routines, JArray triggers) {
    if (steps.isEmpty) return;
    routines.add(routine, _sequence(steps));
    triggers.add(JObject()
      ..addString('id', routine)
      ..addString('event', 'interaction')
      ..addString('action', action)
      ..addString('routine', routine));
  }

  void _citizensCommands(JObject trait, Map<String, List<JObject>> clicks) {
    _hide = _bool(trait.get('hideErrorMessages'), false);
    if (trait.has('hideErrorMessages')) converted.add('$_commandBase/hideErrorMessages');
    final JObject? messages = _object(trait.get('customErrorMessages'));
    if (messages != null) {
      for (final MapEntry<String, JElement> message in messages.entries) {
        if (_commandMessages.containsKey(message.key) && message.value is JPrimitive) {
          _custom[message.key] = message.value.asPrimitive.asString;
          converted.add('$_commandBase/customErrorMessages/${pointer(message.key)}');
        }
      }
    }
    if (listEntries(trait.get('temporaryPermissions')).isNotEmpty) {
      warnings.add('$_commandBase/temporaryPermissions: temporary permissions are not granted; player commands run with the player\'s own permissions');
    }
    final (bool, _Charge?) clickCost = _cost(trait.get('cost'), trait.get('experienceCost'), trait.get('itemRequirements'), _commandBase, 'itemRequirements');
    if (!clickCost.$1) {
      warnings.add('$_commandBase: the click cost has items that cannot be matched exactly, so no commands were converted');
      return;
    }
    final List<_Command> commands = <_Command>[];
    for (final MapEntry<String, JElement> entry in listEntries(trait.get('commands'))) {
      final _Command? command = _command(entry.key, _object(entry.value));
      if (command != null) commands.add(command);
    }
    if (commands.isEmpty) return;
    commands.sort((_Command a, _Command b) => a.id.compareTo(b.id));
    String mode = _string(trait.get('executionMode'), 'LINEAR').toUpperCase();
    if (trait.has('executionMode')) converted.add('$_commandBase/executionMode');
    if (!const <String>{'LINEAR', 'RANDOM', 'CYCLE', 'SEQUENTIAL'}.contains(mode)) {
      warnings.add('$_commandBase/executionMode: $mode is not known; commands run in order');
      mode = 'LINEAR';
    }
    final Map<String, List<_Command>> hands = <String, List<_Command>>{
      'interact': _clicks(commands, 'RIGHT', 'BOTH'),
      'attack': _clicks(commands, 'LEFT', 'BOTH'),
      'sneak_interact': _clicks(commands, 'SHIFT_RIGHT', 'SHIFT_BOTH'),
      'sneak_attack': _clicks(commands, 'SHIFT_LEFT', 'SHIFT_BOTH'),
    };
    const Map<String, String> names = <String, String>{'interact': 'right', 'attack': 'left', 'sneak_interact': 'sneak-right', 'sneak_attack': 'sneak-left'};
    final List<_Command> right = hands['interact']!;
    for (final MapEntry<String, List<_Command>> hand in hands.entries) {
      if (hand.value.isEmpty) continue;
      final String routine = hand.key == 'attack' && _sameCommands(right, hand.value) ? 'right' : names[hand.key]!;
      JObject body = _mode(hand.value, mode, 'commands-$key-$routine');
      if (clickCost.$2 != null) body = _runsCommand(body) ? _allowed(clickCost.$2!.gate(body)) : clickCost.$2!.gate(body);
      clicks[hand.key]!.add(body);
    }
    if ((hands['sneak_interact']!.isNotEmpty || hands['sneak_attack']!.isNotEmpty) && commands.any((_Command command) => !command.hand.startsWith('SHIFT_'))) {
      warnings.add('$_commandBase: LEFT, RIGHT and BOTH commands also run on a sneaking click, beside the SHIFT ones');
    }
  }

  JObject _mode(List<_Command> commands, String mode, String quest) {
    final List<JObject> nodes = <JObject>[
      for (final _Command command in commands.sublist(0, commands.length < _maxChoices ? commands.length : _maxChoices)) command.node
    ];
    if (commands.length > _maxChoices) warnings.add('$_commandBase/commands: only the first $_maxChoices commands for one click were converted');
    return switch (mode) {
      'RANDOM' => _select(nodes, quest, 'npc', true),
      'CYCLE' => _select(nodes, quest, 'npc', false),
      'SEQUENTIAL' => _select(nodes, quest, 'relationship', false),
      _ => _sequence(nodes),
    };
  }

  _Command? _command(String id, JObject? data) {
    final String at = '$_commandBase/commands/$id';
    if (data == null || !data.has('command') || data.get('command') is! JPrimitive) return null;
    final String hand = _string(data.get('hand'), 'BOTH').toUpperCase();
    if (!_hands.contains(hand)) {
      warnings.add('$at/hand: $hand is not a Citizens click; not converted');
      return null;
    }
    final (bool, _Charge?) cost = _cost(data.get('cost'), data.get('experienceCost'), data.get('itemCost'), at, 'itemCost');
    if (!cost.$1) {
      warnings.add('$at/itemCost: an item cost cannot be matched exactly; not converted');
      return null;
    }
    final bool npc = _bool(data.get('npc'), false);
    final bool player = _bool(data.get('player'), false);
    final bool op = _bool(data.get('op'), false);
    String text = data.get('command')!.asPrimitive.asString;
    if (text.startsWith('/')) text = text.substring(1);
    text = _placeholders(text, '$at/command', npc: npc);
    if (text.isEmpty || text.length > _maxText) {
      warnings.add('$at/command: empty or longer than 1024 characters; not converted');
      return null;
    }
    final RegExpMatch? mythic = (player || op) && !npc ? _mythicCast.firstMatch(text) : null;
    final JObject run;
    if (mythic != null) {
      run = _action('mythic:cast-skill', _arguments('skill', mythic.group(1)!)..addString('caster', 'player'));
      warnings.add('$at/command: the MythicMobs skill cast became mythic:cast-skill, which needs MythicMobs on the server');
    } else {
      if (op) warnings.add('$at/op: the command ran as an operator player; it now runs from the console');
      if (npc && player) warnings.add("$at/npc: the NPC's own player body ran the command; it now runs from the console, with the NPC's name for the player");
      run = _action('animus:command', _arguments('command', text)..addString('as', player && !op && !npc ? 'player' : 'console'));
    }
    final String flag = 'commands-$key-$id';
    final int cooldown = _seconds(data.get('cooldown'), '$at/cooldown');
    final int globalCooldown = _seconds(data.get('globalcooldown'), '$at/globalcooldown');
    final int uses = _whole(data.get('n'), '$at/n');
    final int globalUses = _whole(data.get('gn'), '$at/gn');
    final List<JObject> effects = <JObject>[
      if (cost.$2 != null) cost.$2!.take(),
      if (cooldown > 0) _setFlag('$flag-cooldown', 'relationship', cooldown),
      if (globalCooldown > 0) _setFlag('$flag-global-cooldown', 'npc', globalCooldown),
      if (uses > 0) _action('animus:advance-quest-state', _quest('$flag-uses', 'relationship')),
      if (globalUses > 0) _action('animus:advance-quest-state', _quest('$flag-global-uses', 'npc')),
    ];
    final bool taxed = effects.isNotEmpty;
    effects.add(run);
    JObject node = _sequence(effects);
    if (cost.$2 != null) node = cost.$2!.afford(node);
    if (globalUses > 0) node = _refuse(_stage('$flag-global-uses', 'npc', globalUses), 'GLOBAL_MAXIMUM_TIMES_USED', '$globalUses', '', node);
    if (uses > 0) node = _refuse(_stage('$flag-uses', 'relationship', uses), 'MAXIMUM_TIMES_USED', '$uses', '', node);
    if (globalCooldown > 0) node = _refuse(_check('animus:flag-state', _quest('$flag-global-cooldown', 'npc')), 'ON_GLOBAL_COOLDOWN', '', '', node);
    if (cooldown > 0) node = _refuse(_check('animus:flag-state', _quest('$flag-cooldown', 'relationship')), 'ON_COOLDOWN', '', '', node);
    if (taxed && run.get('capability')!.asPrimitive.asString == 'animus:command') node = _allowed(node);
    final List<MapEntry<String, JElement>> permissions = listEntries(data.get('permissions'));
    for (int index = permissions.length - 1; index >= 0; index--) {
      final JElement permission = permissions[index].value;
      if (permission is! JPrimitive) continue;
      node = _otherwise(_condition('animus:has-permission', _arguments('permission', permission.asString), node), _message('NO_PERMISSION', '', ''));
      converted.add('$at/permissions/${permissions[index].key}');
    }
    if (_number(data.get('delay')) && data.get('delay')!.asPrimitive.asDouble >= 1) {
      final JObject wait = JObject()
        ..addString('type', 'wait')
        ..addInt('ticks', _floorClamp(data.get('delay')!.asPrimitive.asDouble, 1, _maxCooldown));
      node = _sequence(<JObject>[wait, node]);
      converted.add('$at/delay');
    }
    for (final String field in const <String>['command', 'hand', 'player', 'op', 'npc']) {
      if (data.has(field)) converted.add('$at/$field');
    }
    return _Command(int.parse(id), hand, node);
  }

  int _seconds(JElement? value, String at) {
    if (!_number(value)) return 0;
    converted.add(at);
    return _floorClamp(value!.asPrimitive.asDouble, 0, _maxExpiry);
  }

  int _whole(JElement? value, String at) {
    if (!_number(value)) return 0;
    converted.add(at);
    return _floorClamp(value!.asPrimitive.asDouble, 0, 1000000);
  }

  /// A cost, or null inside a true result when there is none; false when an item cannot be matched exactly.
  (bool, _Charge?) _cost(JElement? money, JElement? levels, JElement? items, String at, String itemField) {
    final JObject arguments = JObject();
    bool exact = true;
    if (_positive(money)) {
      arguments.add('money', citizensMoney(money!.asPrimitive.asDouble));
      converted.add('$at/cost');
    }
    if (_positive(levels)) {
      arguments.addInt('levels', _floorClamp(levels!.asPrimitive.asDouble, 1, 100000));
      converted.add('$at/experienceCost');
    }
    final JArray wanted = JArray();
    for (final MapEntry<String, JElement> entry in listEntries(items)) {
      final CitizensStack? stack = readCitizensStack(entry.value);
      if (stack == null) continue;
      if (stack.inexact != null) {
        exact = false;
        continue;
      }
      wanted.add(JObject()
        ..add('item', stack.item.deepCopy())
        ..addInt('amount', stack.amount < 6400 ? stack.amount : 6400));
      converted.add('$at/$itemField/${entry.key}');
    }
    if (!exact) return (false, null);
    if (wanted.isNotEmpty) arguments.add('items', wanted);
    return (true, arguments.isEmpty ? null : _Charge(this, arguments));
  }

  JObject? _message(String error, String first, String second) {
    if (_hide) return null;
    final String text = (_custom[error] ?? _commandMessages[error]!).replaceAll('{0}', first).replaceAll('{1}', second);
    if (javaIsBlank(text)) return null;
    return _plain(_placeholders(text, '$_commandBase/customErrorMessages/$error', npc: false));
  }

  /// Runs the message when the check holds and `node` otherwise.
  JObject _refuse(JObject check, String error, String first, String second, JObject node) {
    final JObject? message = _message(error, first, second);
    check.add('then', message ?? _sequence(const <JObject>[]));
    check.add('else', node);
    return check;
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

  String _placeholders(String text, String path, {bool npc = false}) {
    final String player = npc ? name : '{player_name}';
    final String result = text.replaceAll('<player>', player).replaceAll('<p>', player).replaceAll('<npc>', name).replaceAll('<id>', key);
    if (_untranslated.hasMatch(result)) warnings.add('$path: placeholders other than <p>, <player>, <npc> and <id> are not filled in');
    return result;
  }
}

final RegExp _newline = RegExp('<br>|\\n');
final RegExp _untranslated = RegExp(r'<[a-z_]+>|%[a-z_]+%');
final RegExp _listKey = RegExp(r'^(?:0|[1-9][0-9]{0,8})$');

/// A player casting a MythicMobs skill, `mm test cast <skill>`, which `mythic:cast-skill` does without the command permission.
final RegExp _mythicCast = RegExp(r'^(?:mythicmobs:)?(?:mm|mythicmobs)\s+(?:test|t)\s+(?:cast|c)\s+([A-Za-z0-9_.-]{1,128})(?:\s+-s)?\s*$', caseSensitive: false);
const Set<String> _hands = <String>{'LEFT', 'RIGHT', 'BOTH', 'SHIFT_LEFT', 'SHIFT_RIGHT', 'SHIFT_BOTH'};

/// The commands one click runs: those for that hand and those for both.
List<_Command> _clicks(List<_Command> commands, String hand, String both) =>
    commands.where((_Command command) => command.hand == hand || command.hand == both).toList();

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

final RegExp _material = RegExp(r'^[a-z0-9_]+$');

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
