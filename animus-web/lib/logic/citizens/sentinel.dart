/// Port of core's `SentinelMapping`: a Sentinel trait as the disabled `animus:combat` module of an import.
library;

import 'java_json.dart';
import 'java_lang.dart';

enum Disposition { converted, unsupported, statistic, unknown }

const Set<String> _converted = <String>{
  'allTargets', 'allIgnores', 'allAvoids', 'avoidRange', 'enemyTargetTime', 'fightback', 'realistic', 'ignore_los', 'retain_target', //
  'reaction_slowdown', 'runaway', 'chaseRange', 'speed', 'close_chase', 'ranged_chase', 'squad', 'guard_distance_minimum', 'guard_selection_range',
  'guardedNPC', 'guardingUpper', 'guardingLower', 'avoidReturnPoint', 'disable_teleporting', 'damage', 'attackRate', 'attackRateRanged', 'range',
  'reach', 'safeShot', 'accuracy', 'projectileRange', 'health', 'armor', 'invincible', 'protected', 'protectFromRange', 'allow_knockback',
  'healRate', 'drops', 'dropChances', 'enemyDrops', 'death_xp', 'respawnTime', 'spawnPoint', 'worldguard_region', 'needsAmmo',
  'weapon_damage_map', 'warning_text', 'greeting_text', 'greet_range', 'greet_rate',
};
const Set<String> _unsupported = <String>{'autoswitch', 'weapon_redirect_map'};
const String _guardReference = '/traits/sentinel/guardedNPC';
const double _walkingSpeed = 4.3;
const Map<String, String> _categories = <String, String>{
  'PLAYER': 'players', 'PLAYERS': 'players', 'NPC': 'npcs', 'NPCS': 'npcs', 'MONSTER': 'monsters', 'MONSTERS': 'monsters', //
  'MOB': 'mobs', 'MOBS': 'mobs', 'PASSIVE_MOB': 'animals', 'PASSIVE_MOBS': 'animals',
};
const Map<String, String> _entityAliases = <String, String>{
  'ENDERMEN': 'enderman', 'SNOWMEN': 'snow_golem', 'SNOWMAN': 'snow_golem', 'IRON_GOLEMS': 'iron_golem', //
  'ZOMBIE_PIGMEN': 'zombified_piglin', 'PIGZOMBIE': 'zombified_piglin', 'MUSHROOM_COW': 'mooshroom',
};
const Map<String, String> _effectAliases = <String, String>{
  'SLOW': 'slowness', 'FAST_DIGGING': 'haste', 'SLOW_DIGGING': 'mining_fatigue', 'INCREASE_DAMAGE': 'strength', 'HEAL': 'instant_health', //
  'HARM': 'instant_damage', 'JUMP': 'jump_boost', 'CONFUSION': 'nausea', 'DAMAGE_RESISTANCE': 'resistance',
};
const Map<String, String> _fights = <String, String>{
  'pvp': 'player_hurts_player', 'pve': 'player_hurts_mob', 'eve': 'mob_hurts_mob', 'pvnpc': 'player_hurts_npc', //
  'pvsentinel': 'player_hurts_npc', 'npcvnpc': 'npc_hurts_npc',
};
const Map<String, String> _heldWeapons = <String, String>{
  'bow': 'arrow', 'crossbow': 'arrow', 'trident': 'trident', 'blaze_rod': 'fireball', 'snowball': 'snowball', 'snow_ball': 'snowball', //
  'egg': 'egg', 'spectral_arrow': 'spectral_arrow', 'wither_skeleton_skull': 'wither_skull',
};
const Map<String, String> _labelKinds = <String, String>{
  'player': 'byPlayerName', 'npc': 'byNpcName', 'entityname': 'byEntityName', 'helditem': 'byHeldItem', 'offhand': 'byOffhandItem', //
  'equipped': 'byEquippedItem', 'group': 'byGroup',
};

Disposition sentinelDisposition(String field) {
  if (_converted.contains(field)) return Disposition.converted;
  if (_unsupported.contains(field)) return Disposition.unsupported;
  return field.startsWith('stats_') ? Disposition.statistic : Disposition.unknown;
}

JObject _base() => JObject()
  ..addBool('enabled', false)
  ..addBool('reviewAccepted', false);

/// Sentinel's own `text`: string primitives only.
String? _text(JElement? value) => value is JString ? value.value : null;

JObject convertSentinel(JElement? sentinel, JElement? equipment, JArray references) {
  if (sentinel is JObject) return _Converter(sentinel, equipment, references).run();
  final JObject module = _base();
  module.add('preservedSettings', sentinel == null ? JNull.instance : sentinel.deepCopy());
  module.add('historicalStatistics', JObject());
  return module;
}

enum _Scope { targets, avoids, ignores, nested }

class _RuleList {
  _RuleList(JObject list, _Scope scope) {
    for (final MapEntry<String, JElement> entry in list.entries) {
      final String kind = entry.key;
      if (kind == 'byAllInOne' || kind == 'byMultiple') {
        _nestedLists(kind, entry.value, scope);
        continue;
      }
      final List<String>? values = _strings(entry.value);
      if (values == null) {
        residue.add(kind, entry.value.deepCopy());
        continue;
      }
      final JArray leftover = JArray();
      for (final String value in values) {
        if (kind == 'byEvent' && value == 'guarded_fight' && scope == _Scope.targets) {
          assist = true;
        } else if (!_rule(kind, value, rules, scope)) {
          leftover.addString(value);
        }
      }
      if (leftover.isNotEmpty) residue.add(kind, leftover);
    }
  }

  final JObject rules = JObject();
  final JObject residue = JObject();
  bool assist = false;

  void _nestedLists(String kind, JElement value, _Scope scope) {
    final List<JElement> lists = <JElement>[];
    if (value is JArray) {
      lists.addAll(value.elements);
    } else if (value is JObject) {
      lists.addAll(value.members.values);
    } else {
      residue.add(kind, value.deepCopy());
      return;
    }
    final JArray leftover = JArray();
    for (final JElement nested in lists) {
      JElement? converted;
      if (nested is JObject && scope != _Scope.nested) {
        converted = kind == 'byAllInOne' ? _allInOne(nested) : (scope == _Scope.ignores ? null : _together(nested));
      }
      if (converted == null) {
        leftover.add(nested.deepCopy());
      } else {
        final String into = kind == 'byAllInOne' ? 'all' : 'together';
        if (!rules.has(into)) rules.add(into, JArray());
        (rules.get(into)! as JArray).add(converted);
      }
    }
    if (leftover.isNotEmpty) residue.add(kind, leftover);
  }

  static JElement? _allInOne(JObject list) {
    final _RuleList nested = _RuleList(list, _Scope.nested);
    return nested.residue.isEmpty && nested.rules.isNotEmpty ? nested.rules : null;
  }

  static JElement? _together(JObject list) {
    final JArray parts = JArray();
    for (final MapEntry<String, JElement> entry in list.entries) {
      final List<String>? values = _strings(entry.value);
      if (values == null) return null;
      for (final String value in values) {
        final JObject part = JObject();
        if (!_rule(entry.key, value, part, _Scope.nested)) return null;
        parts.add(part);
      }
    }
    return parts.length >= 2 && parts.length <= 8 ? parts : null;
  }
}

bool _rule(String kind, String value, JObject rules, _Scope scope) {
  switch (kind) {
    case 'targets':
      final String upper = javaUpper(value);
      if (upper == 'OWNER') {
        rules.addBool('owner', true);
        return true;
      }
      final String? category = _categories[upper];
      if (category != null) return _add(rules, 'categories', category);
      final String? type = _entityType(value);
      return type != null && _add(rules, 'entityTypes', type);
    case 'byPlayerName':
      return _pattern(rules, 'patterns', 'players', value);
    case 'byNpcName':
      return _pattern(rules, 'patterns', 'npcs', value);
    case 'byEntityName':
      return _pattern(rules, 'patterns', 'names', value);
    case 'byHeldItem':
      return _item(rules, 'mainHand', value);
    case 'byOffhandItem':
      return _item(rules, 'offHand', value);
    case 'byEquippedItem':
      return _item(rules, 'worn', value);
    case 'byGroup':
      return RegExp(r'^[A-Za-z0-9_.-]{1,64}$').hasMatch(value) && _add(rules, 'groups', value);
    case 'byOther':
      return _other(value, rules);
    case 'byEvent':
      return (scope == _Scope.targets || scope == _Scope.avoids) && _event(value, rules);
    default:
      return false;
  }
}

bool _other(String value, JObject rules) {
  final int colon = value.indexOf(':');
  if (colon <= 0 || colon == value.length - 1) return false;
  final String prefix = javaLower(value.substring(0, colon));
  final String rest = value.substring(colon + 1);
  switch (prefix) {
    case 'squad':
      return _add(rules, 'squads', rest);
    case 'uuid':
      return canonicalUuid(rest) && _add(rules, 'players', rest);
    case 'healthabove':
    case 'healthbelow':
      double percent;
      try {
        percent = javaParseDouble(rest);
      } on FormatException {
        return false;
      }
      if (!percent.isFinite || percent < 0 || percent > 100) return false;
      final String key = prefix == 'healthabove' ? 'healthAbove' : 'healthBelow';
      if (rules.has(key)) {
        final double current = rules.get(key)!.asPrimitive.asDouble;
        percent = prefix == 'healthabove' ? (current < percent ? current : percent) : (current > percent ? current : percent);
      }
      rules.addDouble(key, percent);
      return true;
    case 'potion':
      final String upper = javaUpper(rest);
      if (!RegExp(r'^[A-Z_]{2,48}$').hasMatch(upper)) return false;
      return _add(rules, 'effects', 'minecraft:${_effectAliases[upper] ?? javaLower(upper)}');
    case 'permission':
      return RegExp(r'^[A-Za-z0-9_.*-]{1,128}$').hasMatch(rest) && _add(rules, 'permissions', rest);
    default:
      return false;
  }
}

bool _event(String value, JObject rules) {
  final String lower = javaLower(value);
  final String? fight = _fights[lower];
  final JObject events = rules.object('events') ?? JObject();
  bool converted = false;
  if (fight != null) {
    if (!events.has('kinds')) events.add('kinds', JArray());
    final JArray kinds = events.get('kinds')! as JArray;
    if (!kinds.contains(JString(fight))) kinds.addString(fight);
    converted = true;
  } else if (lower.startsWith('message:') || lower.startsWith('message,')) {
    final String message = value.substring('message:'.length);
    if (javaIsBlank(message) || message.length > 128) return false;
    return _add(rules, 'chat', message);
  } else if (lower.startsWith('pv:') || lower.startsWith('ev:')) {
    final String into = lower.startsWith('pv:') ? 'playerAttacks' : 'anyAttacks';
    final JObject attacked = events.object(into) ?? JObject();
    if (!_label(value.substring(3), attacked)) return false;
    events.add(into, attacked);
    converted = true;
  }
  if (converted) rules.add('events', events);
  return converted;
}

bool _label(String label, JObject rules) {
  final int colon = label.indexOf(':');
  if (colon < 0) return _rule('targets', label, rules, _Scope.nested);
  final String prefix = javaLower(label.substring(0, colon));
  final String? kind = _labelKinds[prefix];
  return kind != null ? _rule(kind, label.substring(colon + 1), rules, _Scope.nested) : _other(label, rules);
}

bool _pattern(JObject rules, String section, String key, String value) {
  if (!validNamePattern(value)) return false;
  if (!rules.has(section)) rules.add(section, JObject());
  return _add(rules.get(section)! as JObject, key, value);
}

bool _item(JObject rules, String slot, String value) {
  final String lower = javaLower(value);
  if (lower.startsWith('name:') || lower.startsWith('lore:')) return false;
  return _pattern(rules, 'items', slot, value);
}

bool _add(JObject rules, String key, String value) {
  if (!rules.has(key)) rules.add(key, JArray());
  final JArray list = rules.get(key)! as JArray;
  if (!list.contains(JString(value))) list.addString(value);
  return true;
}

String? _entityType(String name) {
  final String upper = javaUpper(name);
  if (!RegExp(r'^[A-Z_]{2,48}$').hasMatch(upper)) return null;
  final String? alias = _entityAliases[upper];
  if (alias != null) return 'minecraft:$alias';
  String singular = upper;
  if (upper.endsWith('CHES') || upper.endsWith('XES')) {
    singular = upper.substring(0, upper.length - 2);
  } else if (upper.endsWith('S') && !upper.endsWith('SS')) {
    singular = upper.substring(0, upper.length - 1);
  }
  return 'minecraft:${javaLower(singular)}';
}

List<String>? _strings(JElement value) {
  if (value is! JArray) return null;
  final List<String> result = <String>[];
  for (final JElement entry in value.elements) {
    final String? text = _text(entry);
    if (text == null) return null;
    result.add(text);
  }
  return result;
}

/// `NamePattern.compile` succeeds: 1..128 characters and a valid `java.util.regex` pattern. The browser checks with
/// its own regular expressions after rewriting the Java-only forms Sentinel lists use (possessive and atomic
/// groups, inline flags, `\Q..\E`), and refuses a `{` that does not start a repetition, as Java does. Other syntax
/// differences between the two engines are possible but unlikely in name patterns.
bool validNamePattern(String source) {
  if (source.isEmpty || source.length > 128) return false;
  String translated = source.replaceAllMapped(RegExp(r'\\Q(.*?)(?:\\E|$)', dotAll: true), (Match match) => RegExp.escape(match.group(1)!));
  if (RegExp(r'(?<!\\)\{(?![0-9]+(?:,[0-9]*)?\})').hasMatch(translated.replaceAll(RegExp(r'\[(?:\\.|[^\]\\])*\]'), ''))) return false;
  translated = translated
      .replaceAll(RegExp(r'^\(\?[a-zA-Z]+\)'), '')
      .replaceAll('(?>', '(?:')
      .replaceAllMapped(RegExp(r'([*+?}])\+'), (Match match) => match.group(1)!);
  try {
    RegExp(translated, caseSensitive: false);
    return true;
  } on FormatException {
    return false;
  }
}

class _Converter {
  _Converter(this.source, this.equipment, this.references);

  final JObject source;
  final JElement? equipment;
  final JArray references;
  final JObject module = _base();
  final JObject preserved = JObject();
  final JArray converted = JArray();
  final JArray unsupported = JArray();
  final JArray unknown = JArray();
  final List<String> notes = <String>[];
  bool assist = false;

  JObject run() {
    final JObject statistics = JObject();
    for (final MapEntry<String, JElement> field in source.entries) {
      switch (sentinelDisposition(field.key)) {
        case Disposition.statistic:
          statistics.add(field.key, field.value.deepCopy());
        case Disposition.unsupported:
          preserved.add(field.key, field.value.deepCopy());
          unsupported.addString(field.key);
        case Disposition.unknown:
          preserved.add(field.key, field.value.deepCopy());
          unknown.addString(field.key);
        case Disposition.converted:
          break;
      }
    }
    _targetLists();
    _targeting();
    _avoidance();
    _guard();
    _pursuit();
    _attacks();
    _defense();
    _death();
    _respawn();
    _greetings();
    for (final String field in source.keys) {
      if (sentinelDisposition(field) == Disposition.converted && !preserved.has(field)) converted.addString(field);
    }
    module.add('preservedSettings', preserved);
    module.add('historicalStatistics', statistics);
    final JObject report = JObject();
    report.add('converted', converted);
    report.add('unsupported', unsupported);
    report.add('unknown', unknown);
    report.add('notes', JArray(<JElement>[for (final String note in notes) JString(note)]));
    module.add('migrationReport', report);
    return module;
  }

  JObject _section(String name) {
    if (!module.has(name)) module.add(name, JObject());
    return module.get(name)! as JObject;
  }

  void _keep(String field, String why) {
    preserved.add(field, source.get(field)!.deepCopy());
    notes.add('$field: $why');
  }

  bool _present(String field) {
    final JElement? value = source.get(field);
    return value != null && !value.isNull;
  }

  double _number(String field, double fallback, double minimum, double maximum) {
    final JElement? value = source.get(field);
    if (value == null) return fallback;
    if (value is! JNumber || !value.asDouble.isFinite) {
      _keep(field, 'not a number; using ${javaDoubleToString(fallback)}');
      return fallback;
    }
    final double exact = value.asDouble;
    if (exact < minimum || exact > maximum) {
      final double limited = exact < minimum ? minimum : maximum;
      _keep(field, "${javaDoubleToString(exact)} is outside this module's range; using ${javaDoubleToString(limited)}");
      return limited;
    }
    return exact;
  }

  int _ticks(String field, int fallback, int minimum, int maximum) =>
      javaRound(_number(field, fallback.toDouble(), minimum.toDouble(), maximum.toDouble())).toInt();

  bool _flag(String field, bool fallback) {
    final JElement? value = source.get(field);
    if (value == null) return fallback;
    if (value is! JBool) {
      _keep(field, 'not a boolean; using $fallback');
      return fallback;
    }
    return value.value;
  }

  void _targetLists() {
    _rules('allTargets', 'targets', _Scope.targets);
    _rules('allIgnores', 'ignores', _Scope.ignores);
    _rules('allAvoids', 'avoids', _Scope.avoids);
  }

  void _rules(String field, String into, _Scope scope) {
    final JElement? list = source.get(field);
    if (list == null) return;
    if (list is! JObject) {
      _keep(field, 'not a target list');
      return;
    }
    final _RuleList result = _RuleList(list, scope);
    if (result.assist) assist = true;
    if (result.rules.isNotEmpty) module.add(into, result.rules);
    if (result.residue.isNotEmpty) {
      preserved.add(field, result.residue);
      notes.add('$field: some entries have no equivalent rule and are not applied');
    }
  }

  void _targeting() {
    final JObject section = _section('targeting');
    section.addDouble('scanRange', _number('range', 20, 1, 256));
    section.addInt('reactionTicks', _ticks('reaction_slowdown', 0, 0, 200));
    section.addInt('memoryTicks', _ticks('enemyTargetTime', 0, 0, 1200));
    section.addBool('retainTarget', _flag('retain_target', false));
    section.addBool('fightBack', _flag('fightback', true));
    final bool ignore = _flag('ignore_los', false);
    final bool realistic = _flag('realistic', false);
    section.addString('lineOfSight', ignore ? 'ignored' : (realistic ? 'field_of_view' : 'required'));
    if (source.has('squad')) {
      final String? name = _text(source.get('squad'));
      if (name == null || javaIsBlank(name) || name.length > 256) {
        _keep('squad', 'not a squad name');
      } else {
        _section('squad').addString('name', name);
      }
    }
  }

  void _avoidance() {
    final JObject section = _section('avoidance');
    section.addDouble('range', _number('avoidRange', 10, 1, 64));
    section.addBool('fleeWhenAttacked', _flag('runaway', false));
    final JObject? point = _location('avoidReturnPoint');
    if (point != null) section.add('returnPoint', point);
  }

  JObject? _location(String field) {
    final JElement? value = source.get(field);
    if (value == null || value.isNull) return null;
    if (value is JObject) {
      final String? world = _text(value.get('world'));
      final JObject result = JObject();
      bool valid = world != null && !javaIsBlank(world);
      if (valid) result.addString('world', world);
      for (final String axis in const <String>['x', 'y', 'z']) {
        final JElement? coordinate = value.get(axis);
        if (coordinate is! JNumber || !coordinate.asDouble.isFinite || coordinate.asDouble.abs() > 30000000) {
          valid = false;
        } else {
          result.addDouble(axis, coordinate.asDouble);
        }
      }
      if (valid) return result;
    }
    _keep(field, 'not a usable location');
    return null;
  }

  void _guard() {
    final JObject section = _section('guard');
    section.addDouble('followDistance', _number('guard_distance_minimum', 7, 1, 64));
    section.addDouble('pointRange', _number('guard_selection_range', 4, 0, 16));
    if (assist) section.addBool('assist', true);
    final BigInt? upper = _exactLong('guardingUpper');
    final BigInt? lower = _exactLong('guardingLower');
    bool player = false;
    if (upper != null && lower != null && (upper != BigInt.zero || lower != BigInt.zero)) {
      section.addString('player', uuidOf(upper, lower));
      player = true;
    }
    if (source.has('guardedNPC')) _guardedNpc(section, player);
  }

  void _guardedNpc(JObject section, bool playerGuarded) {
    for (final JElement entry in references.elements) {
      final JObject reference = entry as JObject;
      if (_text(reference.get('source')) != _guardReference) continue;
      final String? status = _text(reference.get('status'));
      if (status == 'none') return;
      final String? uuid = _text(reference.get('npcUuid'));
      if (status == 'resolved' && uuid != null) {
        if (playerGuarded) {
          _keep('guardedNPC', 'this NPC also guards a player, which takes precedence');
        } else {
          section.addString('npc', uuid);
        }
        return;
      }
    }
    _keep('guardedNPC', 'the guarded NPC is not part of this import');
  }

  BigInt? _exactLong(String field) {
    final JElement? value = source.get(field);
    if (value == null) return null;
    final BigInt? exact = value is JNumber ? value.asDecimal.longValueExact() : null;
    if (exact == null) _keep(field, 'not half of a UUID');
    return exact;
  }

  void _pursuit() {
    final JObject section = _section('pursuit');
    final double range = _number('chaseRange', 100, 1, 256);
    section.addDouble('leashRange', range);
    section.addDouble('giveUpRange', range);
    final double speed = _number('speed', 1, 0, double.maxFinite);
    final double blocksPerSecond = speed * _walkingSpeed;
    final double limited = blocksPerSecond > 10 ? 10 : (blocksPerSecond < 1 ? 1 : blocksPerSecond);
    if (limited != blocksPerSecond && !preserved.has('speed')) {
      _keep('speed', '${javaDoubleToString(blocksPerSecond)} blocks per second is outside movement limits; using ${javaDoubleToString(limited)}');
    }
    section.addDouble('speed', limited);
    section.addBool('closeChase', _flag('close_chase', true));
    section.addBool('rangedChase', _flag('ranged_chase', false));
    section.addBool('teleportWhenStuck', !_flag('disable_teleporting', false));
    section.addBool('returnToPost', true);
    if (_present('worldguard_region')) {
      final String? region = _text(source.get('worldguard_region'));
      if (region != null && RegExp(r"^[A-Za-z0-9_,'+/-]{1,64}$").hasMatch(region)) {
        section.addString('region', region);
      } else {
        _keep('worldguard_region', 'not a usable region name');
      }
    }
  }

  void _attacks() {
    final JObject melee = _section('melee');
    final JObject ranged = _section('ranged');
    final double damage = source.has('damage') ? _number('damage', -1, -1, 100) : -1;
    melee.addDouble('reach', _number('reach', 3, .5, 6));
    melee.addInt('cooldownTicks', _ticks('attackRate', 30, 1, 1200));
    if (damage > 0) {
      melee.addDouble('damage', damage);
      ranged.addDouble('damage', damage);
    } else if (damage < 0) {
      melee.addBool('fromEquipment', true);
      ranged.addBool('fromEquipment', true);
    } else {
      melee.addBool('enabled', false);
    }
    _weaponDamage(melee);
    final String? weapon = _heldWeapon();
    ranged.addBool('enabled', weapon != null);
    if (weapon != null) ranged.addString('weapon', weapon);
    ranged.addInt('cooldownTicks', _ticks('attackRateRanged', 30, 1, 1200));
    ranged.addDouble('range', _number('projectileRange', 100, 1, 256));
    ranged.addDouble('inaccuracy', _number('accuracy', 0, 0, 16));
    ranged.addBool('safeShot', _flag('safeShot', true));
    ranged.addBool('useAmmo', _flag('needsAmmo', false));
  }

  void _weaponDamage(JObject melee) {
    final JElement? map = source.get('weapon_damage_map');
    if (map == null || map.isNull) return;
    if (map is! JObject || map.length > 64) {
      _keep('weapon_damage_map', 'not a map of at most 64 items');
      return;
    }
    final JObject converted = JObject();
    for (final MapEntry<String, JElement> entry in map.entries) {
      final String item = 'minecraft:${javaLower(entry.key).replaceAll('minecraft:', '')}';
      final JElement value = entry.value;
      if (!RegExp(r'^[a-z0-9_.-]+:[a-z0-9_./-]+$').hasMatch(item) ||
          value is! JNumber ||
          !value.asDouble.isFinite ||
          value.asDouble < 0 ||
          value.asDouble > 100) {
        _keep('weapon_damage_map', 'an entry is not an item with damage 0..100');
        return;
      }
      converted.addDouble(item, value.asDouble);
    }
    if (converted.isNotEmpty) melee.add('weaponDamage', converted);
  }

  String? _heldWeapon() {
    final JElement? gear = equipment;
    if (gear is! JObject) return null;
    final JElement? hand = gear.get('hand');
    if (hand is! JObject) return null;
    final String? type = _text(hand.get('type')) ?? _text(hand.get('id'));
    if (type == null) return null;
    return _heldWeapons[javaLower(type).replaceAll('minecraft:', '')];
  }

  void _defense() {
    final JObject section = _section('defense');
    section.addDouble('maximumHealth', _number('health', 20, 1, 2048));
    final double armor = source.has('armor') ? _number('armor', -1, -1, 1) : -1;
    if (armor < 0) {
      section.addInt('armor', 0);
      section.addBool('armorFromEquipment', true);
    } else {
      section.addDouble('armor', armor);
    }
    section.addBool('invincible', _flag('invincible', false));
    section.addBool('protectedFromIgnored', _flag('protected', false));
    section.addDouble('protectFromRange', _number('protectFromRange', 0, 0, 256));
    section.addBool('knockback', _flag('allow_knockback', true));
    section.addInt('healRateTicks', _ticks('healRate', 100, 0, 72000));
  }

  void _death() {
    final JObject section = _section('death');
    section.addBool('victimDrops', _flag('enemyDrops', false));
    section.addInt('experience', _ticks('death_xp', 0, 0, 10000));
    final JElement? drops = source.get('drops');
    if (drops == null) return;
    if (drops is! JArray) {
      _keep('drops', 'not a list');
      return;
    }
    final JElement? declared = source.get('dropChances');
    final JArray chances = declared is JArray ? declared : JArray();
    if (source.has('dropChances') && declared is! JArray) _keep('dropChances', 'not a list');
    final JArray rules = JArray();
    bool complete = true;
    for (int i = 0; i < drops.length && rules.length < 32; i++) {
      final JObject? rule = _dropRule(drops[i], i < chances.length ? chances[i] : null);
      if (rule != null) {
        rules.add(rule);
      } else {
        complete = false;
      }
    }
    if (drops.length > 32) complete = false;
    if (rules.isNotEmpty) section.add('drops', rules);
    if (!complete) _keep('drops', 'items with extra data cannot be represented and are not dropped');
  }

  static const Set<String> _dropKeys = <String>{'==', 'v', 'type', 'id', 'amount', 'count', 'schema_version', 'DataVersion'};

  static JObject? _dropRule(JElement item, JElement? chance) {
    if (item is! JObject) return null;
    for (final String key in item.keys) {
      if (!_dropKeys.contains(key)) return null;
    }
    final String? type = _text(item.get('type')) ?? _text(item.get('id'));
    if (type == null) return null;
    String id = javaLower(type);
    if (!id.contains(':')) id = 'minecraft:$id';
    if (!RegExp(r'^[a-z0-9_.-]+:[a-z0-9_./-]+$').hasMatch(id)) return null;
    final JElement? amount = item.has('amount') ? item.get('amount') : item.get('count');
    int count = 1;
    if (amount is JNumber) {
      final int raw = amount.asInt;
      count = raw > 64 ? 64 : (raw < 1 ? 1 : raw);
    }
    double probability = 1;
    if (chance is JNumber) {
      final double raw = chance.asDouble;
      probability = raw > 1 ? 1 : (raw < 0 ? 0 : raw);
    }
    final JObject rule = JObject();
    rule.addString('item', id);
    rule.addInt('amount', count);
    rule.addDouble('chance', probability);
    return rule;
  }

  void _respawn() {
    final JObject section = _section('respawn');
    final double time = _number('respawnTime', 100, -double.maxFinite, 72000);
    if (time < 0) {
      section.addInt('delayTicks', -1);
      section.addBool('deleteOnDeath', true);
    } else {
      final BigInt ticks = javaRound(time);
      section.addBig('delayTicks', ticks == BigInt.zero ? BigInt.from(-1) : ticks);
    }
    final JObject? point = _location('spawnPoint');
    if (point != null) section.add('locations', JArray(<JElement>[point]));
  }

  void _greetings() {
    final JObject section = _section('greetings');
    final String? friendly = _line('greeting_text');
    if (friendly != null) section.addString('friendly', friendly);
    final String? hostile = _line('warning_text');
    if (hostile != null) section.addString('hostile', hostile);
    section.addDouble('range', _number('greet_range', 10, 1, 64));
    section.addInt('cooldownTicks', _ticks('greet_rate', 100, 0, 72000));
  }

  String? _line(String field) {
    if (!_present(field)) return null;
    final String? text = _text(source.get(field));
    if (text == null || text.length > 256 || text.runes.any(javaIsIsoControl)) {
      _keep(field, 'not a line of at most 256 characters');
      return null;
    }
    return javaIsBlank(text) ? null : text;
  }
}
