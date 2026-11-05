/// The NPC combat module `modules["animus:combat"]`: its field tables and a
/// browser-side copy of core's `CombatSettings` parser, reporting the same
/// paths and messages.
///
/// Differences from the server: the server stops at the first error while
/// this reports every error it finds; patterns are compiled with Dart's
/// regular expressions, so a pattern that uses Java-only syntax (inline flags,
/// possessive quantifiers, atomic groups, `\p{..}` classes, `\Q..\E`) is left
/// to the server, and an invalid pattern's message names Dart's reason
/// instead of Java's. Every other rule is mirrored exactly.
library;

import '../../model/animus_document.dart';
import '../gui/gui_json.dart';

const String combatModule = 'animus:combat';

/// The module's path, as the server writes it.
const String combatRoot = r'$.modules.animus:combat';

/// Keys the module object accepts.
const List<String> combatRootKeys = <String>[
  'enabled',
  'reviewAccepted',
  'targets',
  'ignores',
  'avoids',
  'targeting',
  'avoidance',
  'squad',
  'guard',
  'pursuit',
  'melee',
  'ranged',
  'defense',
  'death',
  'respawn',
  'greetings',
  'preservedSettings',
  'historicalStatistics',
  'migrationReport',
];

/// The three rule-list sections.
const List<String> combatRuleSections = <String>['targets', 'ignores', 'avoids'];

/// Keys each settings section accepts.
const Map<String, List<String>> combatSectionKeys = <String, List<String>>{
  'targeting': <String>['scanRange', 'scanIntervalTicks', 'reactionTicks', 'memoryTicks', 'retainTarget', 'fightBack', 'lineOfSight'],
  'avoidance': <String>['range', 'fleeWhenAttacked', 'returnPoint'],
  'squad': <String>['name', 'alertRange'],
  'guard': <String>['player', 'npc', 'followDistance', 'pointRange', 'assist', 'catchUpDistance', 'replanDistance'],
  'pursuit': <String>['closeChase', 'rangedChase', 'speed', 'giveUpRange', 'leashRange', 'returnToPost', 'teleportWhenStuck', 'replanTicks', 'region'],
  'melee': <String>['enabled', 'damage', 'reach', 'cooldownTicks', 'knockback', 'fromEquipment', 'weaponDamage'],
  'ranged': <String>[
    'enabled',
    'weapon',
    'damage',
    'range',
    'minimumRange',
    'cooldownTicks',
    'velocity',
    'inaccuracy',
    'safeShot',
    'knockback',
    'effect',
    'fromEquipment',
    'useAmmo',
  ],
  'defense': <String>[
    'maximumHealth',
    'armor',
    'invincible',
    'knockback',
    'protectedFromIgnored',
    'protectFromRange',
    'healRateTicks',
    'invulnerableTicks',
    'armorFromEquipment',
  ],
  'death': <String>['drops', 'experience', 'victimDrops'],
  'respawn': <String>['delayTicks', 'locations', 'deleteOnDeath'],
  'greetings': <String>['friendly', 'hostile', 'range', 'cooldownTicks'],
};

/// Rules any rule set may use.
const List<String> combatPlainRules = <String>[
  'categories',
  'entityTypes',
  'players',
  'npcs',
  'names',
  'squads',
  'owner',
  'patterns',
  'items',
  'healthAbove',
  'healthBelow',
  'permissions',
  'groups',
  'effects',
];

/// Rules only `targets` and `avoids` may use.
const List<String> combatScanRules = <String>['together', 'events', 'chat'];

const List<String> combatCategories = <String>['players', 'npcs', 'mobs', 'monsters', 'animals'];

const List<String> combatWeapons = <String>[
  'arrow',
  'spectral_arrow',
  'trident',
  'snowball',
  'egg',
  'fireball',
  'small_fireball',
  'wither_skull',
  'llama_spit',
  'potion',
];

const List<String> combatLineOfSight = <String>['required', 'ignored', 'field_of_view'];

const List<String> combatFightKinds = <String>['player_hurts_player', 'player_hurts_mob', 'mob_hurts_mob', 'player_hurts_npc', 'npc_hurts_npc'];

/// Keys of `patterns` and of `items`.
const List<String> combatPatternKeys = <String>['players', 'npcs', 'names'];
const List<String> combatItemKeys = <String>['mainHand', 'offHand', 'worn'];

/// Status effects a potion helps with instead of attacking.
const List<String> combatHelpfulEffects = <String>[
  'minecraft:instant_health',
  'minecraft:regeneration',
  'minecraft:speed',
  'minecraft:strength',
  'minecraft:resistance',
  'minecraft:fire_resistance',
  'minecraft:absorption',
  'minecraft:health_boost',
  'minecraft:saturation',
  'minecraft:night_vision',
  'minecraft:water_breathing',
  'minecraft:invisibility',
  'minecraft:jump_boost',
  'minecraft:haste',
  'minecraft:luck',
  'minecraft:slow_falling',
  'minecraft:conduit_power',
  'minecraft:dolphins_grace',
  'minecraft:hero_of_the_village',
];

/// Entity types offered while typing an `entityTypes` rule.
const List<String> combatCommonEntityTypes = <String>[
  'minecraft:zombie',
  'minecraft:husk',
  'minecraft:drowned',
  'minecraft:skeleton',
  'minecraft:stray',
  'minecraft:creeper',
  'minecraft:spider',
  'minecraft:cave_spider',
  'minecraft:enderman',
  'minecraft:witch',
  'minecraft:pillager',
  'minecraft:vindicator',
  'minecraft:evoker',
  'minecraft:ravager',
  'minecraft:slime',
  'minecraft:phantom',
  'minecraft:blaze',
  'minecraft:piglin',
  'minecraft:wolf',
  'minecraft:cow',
  'minecraft:pig',
  'minecraft:sheep',
  'minecraft:chicken',
  'minecraft:villager',
  'minecraft:iron_golem',
];

/// A numeric field's default and inclusive bounds.
class CombatRange {
  const CombatRange(this.section, this.key, this.fallback, this.minimum, this.maximum, {this.integer = false});

  final String section;
  final String key;
  final num fallback;
  final num minimum;
  final num maximum;
  final bool integer;

  /// `1-256, default 16`.
  String get help => '${_plain(minimum)} to ${_plain(maximum)}, default ${_plain(fallback)}';

  /// The server's bounds text: Java prints doubles as `1.0` and ints as `1`.
  String get bounds => integer ? '$minimum..$maximum' : '${_javaDouble(minimum)}..${_javaDouble(maximum)}';
}

const List<CombatRange> combatRanges = <CombatRange>[
  CombatRange('targeting', 'scanRange', 16, 1, 256),
  CombatRange('targeting', 'scanIntervalTicks', 10, 1, 200, integer: true),
  CombatRange('targeting', 'reactionTicks', 0, 0, 200, integer: true),
  CombatRange('targeting', 'memoryTicks', 60, 0, 1200, integer: true),
  CombatRange('avoidance', 'range', 10, 1, 64),
  CombatRange('squad', 'alertRange', 16, 1, 64),
  CombatRange('guard', 'followDistance', 7, 1, 64),
  CombatRange('guard', 'pointRange', 4, 0, 16),
  CombatRange('guard', 'catchUpDistance', 24, 0, 256),
  CombatRange('guard', 'replanDistance', 1.5, 0.25, 16),
  CombatRange('pursuit', 'speed', 4, 1, 10),
  CombatRange('pursuit', 'giveUpRange', 32, 1, 256),
  CombatRange('pursuit', 'leashRange', 64, 1, 256),
  CombatRange('pursuit', 'replanTicks', 20, 5, 200, integer: true),
  CombatRange('melee', 'damage', 2, 0.01, 100),
  CombatRange('melee', 'reach', 3, 0.5, 6),
  CombatRange('melee', 'cooldownTicks', 20, 1, 1200, integer: true),
  CombatRange('melee', 'knockback', 0.4, 0, 4),
  CombatRange('ranged', 'damage', 3, 0.01, 100),
  CombatRange('ranged', 'range', 24, 1, 256),
  CombatRange('ranged', 'minimumRange', 0, 0, 256),
  CombatRange('ranged', 'cooldownTicks', 40, 1, 1200, integer: true),
  CombatRange('ranged', 'velocity', 1.6, 0.5, 4),
  CombatRange('ranged', 'inaccuracy', 0, 0, 16),
  CombatRange('ranged', 'knockback', 0.2, 0, 4),
  CombatRange('defense', 'maximumHealth', 20, 1, 2048),
  CombatRange('defense', 'armor', 0, 0, 1),
  CombatRange('defense', 'protectFromRange', 0, 0, 256),
  CombatRange('defense', 'healRateTicks', 0, 0, 72000, integer: true),
  CombatRange('defense', 'invulnerableTicks', 10, 0, 100, integer: true),
  CombatRange('death', 'experience', 0, 0, 10000, integer: true),
  CombatRange('respawn', 'delayTicks', 100, -1, 72000, integer: true),
  CombatRange('greetings', 'range', 10, 1, 64),
  CombatRange('greetings', 'cooldownTicks', 100, 0, 72000, integer: true),
  // Nested objects, by their own section name.
  CombatRange('weaponDamage', '*', 1, 0, 100),
  CombatRange('effect', 'amplifier', 0, 0, 9, integer: true),
  CombatRange('effect', 'durationTicks', 200, 1, 72000, integer: true),
  CombatRange('drop', 'amount', 1, 1, 64, integer: true),
  CombatRange('drop', 'chance', 1, 0, 1),
  CombatRange('rule', 'healthAbove', 0, 0, 100),
  CombatRange('rule', 'healthBelow', 0, 0, 100),
];

/// Boolean fields and their defaults.
const Map<String, Map<String, bool>> combatBooleans = <String, Map<String, bool>>{
  'targeting': <String, bool>{'retainTarget': false, 'fightBack': true},
  'avoidance': <String, bool>{'fleeWhenAttacked': false},
  'guard': <String, bool>{'assist': false},
  'pursuit': <String, bool>{'closeChase': true, 'rangedChase': false, 'returnToPost': true, 'teleportWhenStuck': false},
  'melee': <String, bool>{'enabled': true, 'fromEquipment': false},
  'ranged': <String, bool>{'enabled': false, 'safeShot': true, 'fromEquipment': false, 'useAmmo': false},
  'defense': <String, bool>{'invincible': false, 'knockback': true, 'protectedFromIgnored': false, 'armorFromEquipment': false},
  'death': <String, bool>{'victimDrops': true},
  'respawn': <String, bool>{'deleteOnDeath': false},
};

CombatRange? combatRange(String section, String key) {
  for (final CombatRange range in combatRanges) {
    if (range.section == section && (range.key == key || range.key == '*')) return range;
  }
  return null;
}

/// The server's path for [steps] under the module: `$.modules.animus:combat.melee.reach`, `...drops[0].item`.
String combatPath(List<JsonStep> steps) {
  final StringBuffer path = StringBuffer(combatRoot);
  for (final JsonStep step in steps) {
    path.write(step is int ? '[$step]' : '.$step');
  }
  return path.toString();
}

/// [path] in the server's form; the schema validator writes the module key as `["animus:combat"]`.
String normalizeCombatPath(String path) => path.replaceFirst(r'$.modules["animus:combat"]', combatRoot);

/// The diagnostics in [all] that sit inside the module, with server-form paths.
List<Diagnostic> combatDiagnostics(List<Diagnostic> all) => <Diagnostic>[
  for (final Diagnostic diagnostic in all)
    if (_inside(normalizeCombatPath(diagnostic.path), combatRoot))
      normalizeCombatPath(diagnostic.path) == diagnostic.path
          ? diagnostic
          : Diagnostic(diagnostic.severity, normalizeCombatPath(diagnostic.path), diagnostic.message, code: diagnostic.code),
];

bool _inside(String path, String root) => path == root || path.startsWith('$root.') || path.startsWith('$root[');

/// The combat diagnostics of an NPC definition [npc], leaving out paths [existing] already reports.
List<Diagnostic> npcCombatDiagnostics(Object? npc, List<Diagnostic> existing) {
  final Object? modules = npc is Map<String, Object?> ? npc['modules'] : null;
  if (modules is! Map<String, Object?> || !modules.containsKey(combatModule)) return const <Diagnostic>[];
  final Set<String> reported = <String>{for (final Diagnostic diagnostic in existing) normalizeCombatPath(diagnostic.path)};
  return <Diagnostic>[
    for (final Diagnostic diagnostic in validateCombat(modules[combatModule]))
      if (!reported.contains(diagnostic.path)) diagnostic,
  ];
}

/// Every error core's `CombatSettings.parseModule` would raise for [settings].
List<Diagnostic> validateCombat(Object? settings) {
  final _Check check = _Check();
  if (settings is! Map<String, Object?>) {
    check.error(combatRoot, 'expected object');
    return check.out;
  }
  final _Reader root = _Reader(check, settings, combatRoot);
  root.known(combatRootKeys);
  for (final String section in combatRuleSections) {
    final _Reader? rules = root.child(section);
    if (rules != null) _rules(rules, section == 'ignores' ? _Level.ignoring : _Level.seeking);
  }
  _section(root, 'targeting', (_Reader reader) {
    reader.numbers('targeting');
    reader.booleans('targeting');
    final String? rule = reader.string('lineOfSight');
    if (rule != null && !combatLineOfSight.contains(rule.toLowerCase())) {
      check.error(reader.field('lineOfSight'), 'expected required, ignored or field_of_view');
    }
  });
  _section(root, 'avoidance', (_Reader reader) {
    reader.numbers('avoidance');
    reader.booleans('avoidance');
    final _Reader? point = reader.child('returnPoint');
    if (point != null) _location(point);
  });
  _section(root, 'squad', (_Reader reader) {
    reader.string('name');
    reader.numbers('squad');
  });
  _section(root, 'guard', (_Reader reader) {
    final bool player = reader.uuid('player');
    final bool npc = reader.uuid('npc');
    if (player && npc) check.error(reader.path, 'guard either a player or an npc');
    reader.numbers('guard');
    reader.booleans('guard');
  });
  _section(root, 'pursuit', (_Reader reader) {
    final String? region = reader.string('region');
    if (region != null && !_region.hasMatch(region)) check.error(reader.field('region'), 'expected a region name');
    reader.numbers('pursuit');
    reader.booleans('pursuit');
  });
  _section(root, 'melee', (_Reader reader) {
    final _Reader? map = reader.child('weaponDamage');
    if (map != null) {
      if (map.object.length > 64) {
        check.error(map.path, 'at most 64 entries');
      } else {
        for (final String item in map.object.keys) {
          if (!_namespaced.hasMatch(item)) {
            check.error(map.field(item), 'expected namespace:item key');
          } else {
            map.number(item, combatRange('weaponDamage', item)!);
          }
        }
      }
    }
    reader.numbers('melee');
    reader.booleans('melee');
  });
  _section(root, 'ranged', (_Reader reader) {
    final bool hadWeapon = reader.object.containsKey('weapon');
    final String? name = hadWeapon ? reader.string('weapon') : 'arrow';
    String? weapon;
    if (name != null) {
      if (combatWeapons.contains(name)) {
        weapon = name;
      } else {
        check.error(
          reader.field('weapon'),
          'expected arrow, spectral_arrow, trident, snowball, egg, fireball, small_fireball, wither_skull, llama_spit or potion',
        );
      }
    }
    if (weapon != null) {
      if (reader.object.containsKey('effect')) {
        if (weapon != 'potion') {
          check.error(reader.field('effect'), 'only a potion has an effect');
        } else {
          final _Reader? potion = reader.child('effect');
          if (potion != null) {
            potion.known(const <String>['id', 'amplifier', 'durationTicks']);
            potion.namespaced('id', 'expected namespace:effect');
            potion.integer('amplifier', combatRange('effect', 'amplifier')!);
            potion.integer('durationTicks', combatRange('effect', 'durationTicks')!);
          }
        }
      } else if (weapon == 'potion') {
        check.error(reader.field('effect'), 'required for a potion');
      }
    }
    reader.numbers('ranged');
    reader.booleans('ranged');
  });
  _section(root, 'defense', (_Reader reader) {
    reader.numbers('defense');
    reader.booleans('defense');
  });
  _section(root, 'death', (_Reader reader) {
    final List<Object?>? drops = reader.array('drops', 32);
    for (int index = 0; index < (drops?.length ?? 0); index++) {
      final String path = '${reader.field('drops')}[$index]';
      final Object? entry = drops![index];
      if (entry is! Map<String, Object?>) {
        check.error(path, 'expected object');
        continue;
      }
      final _Reader drop = _Reader(check, entry, path);
      drop.known(const <String>['item', 'amount', 'chance']);
      drop.namespaced('item', 'expected namespace:item');
      drop.integer('amount', combatRange('drop', 'amount')!);
      drop.number('chance', combatRange('drop', 'chance')!);
    }
    reader.numbers('death');
    reader.booleans('death');
  });
  _section(root, 'respawn', (_Reader reader) {
    final List<Object?>? locations = reader.array('locations', 16);
    for (int index = 0; index < (locations?.length ?? 0); index++) {
      final String path = '${reader.field('locations')}[$index]';
      final Object? entry = locations![index];
      if (entry is! Map<String, Object?>) {
        check.error(path, 'expected object');
        continue;
      }
      _location(_Reader(check, entry, path));
    }
    final int? delay = reader.integer('delayTicks', combatRange('respawn', 'delayTicks')!);
    final bool? delete = reader.boolean('deleteOnDeath');
    final bool delayValid = !reader.object.containsKey('delayTicks') || delay != null;
    if (delete == true && delayValid && (delay ?? 100) != -1) check.error(reader.field('deleteOnDeath'), 'requires delayTicks -1');
  });
  _section(root, 'greetings', (_Reader reader) {
    for (final String key in const <String>['friendly', 'hostile']) {
      if (!reader.object.containsKey(key)) continue;
      final String? text = reader.string(key);
      if (text != null && (text.trim().isEmpty || text.runes.any(_isoControl))) {
        check.error(reader.field(key), 'expected text without control characters');
      }
    }
    reader.numbers('greetings');
  });
  return check.out;
}

/// Whether [rules] (a rule set) holds any rule a scan can match, as core's `TargetRules.scans`.
bool combatRulesScan(Map<String, Object?> rules) {
  bool listed(Object? value) => value is List<Object?> && value.isNotEmpty;
  bool keyed(Object? value, List<String> keys) => value is Map<String, Object?> && keys.any((String key) => listed(value[key]));
  return <String>[
        'categories',
        'entityTypes',
        'players',
        'npcs',
        'names',
        'squads',
        'permissions',
        'groups',
        'effects',
        'all',
        'together',
      ].any((String key) => listed(rules[key])) ||
      rules['owner'] == true ||
      keyed(rules['patterns'], combatPatternKeys) ||
      keyed(rules['items'], combatItemKeys) ||
      rules.containsKey('healthAbove') ||
      rules.containsKey('healthBelow');
}

final RegExp _namespaced = RegExp(r'^[a-z0-9_.-]+:[a-z0-9_./-]+$');
final RegExp _permission = RegExp(r'^[A-Za-z0-9_.*-]{1,128}$');
final RegExp _group = RegExp(r'^[A-Za-z0-9_.-]{1,64}$');
final RegExp _region = RegExp(r"^[A-Za-z0-9_,'+/-]{1,64}$");
final RegExp _uuid = RegExp(r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$');

/// Java regex syntax Dart cannot compile; such patterns are checked by the server only.
final RegExp _javaOnly = RegExp(r'\(\?[a-zA-Z-]+[):]|\(\?>|[*+?}]\+|\\[pP]\{|\\[QEAZzGhHRXv]');

bool _isoControl(int rune) => rune <= 0x1f || (rune >= 0x7f && rune <= 0x9f);

String _plain(num value) => value == value.roundToDouble() ? value.toInt().toString() : value.toString();

String _javaDouble(num value) => value == value.roundToDouble() ? '${value.toInt()}.0' : value.toDouble().toString();

enum _Level { seeking, ignoring, nested }

class _Check {
  final List<Diagnostic> out = <Diagnostic>[];

  void error(String path, String message) => out.add(Diagnostic(DiagnosticSeverity.error, path, message, code: 'combat'));
}

void _section(_Reader root, String name, void Function(_Reader reader) body) {
  final _Reader? reader = root.child(name);
  if (reader == null) return;
  reader.known(combatSectionKeys[name]!);
  body(reader);
}

void _location(_Reader reader) {
  reader.known(const <String>['world', 'x', 'y', 'z']);
  final bool typed = !reader.object.containsKey('world') || reader.string('world') != null;
  if (typed && (asString(reader.object['world']) ?? '').trim().isEmpty) reader.check.error(reader.field('world'), 'expected nonblank string');
  for (final String axis in const <String>['x', 'y', 'z']) {
    if (!reader.object.containsKey(axis)) {
      reader.check.error(reader.field(axis), 'required');
      continue;
    }
    final double? value = reader.finite(axis);
    if (value != null && value.abs() > 30000000) reader.check.error(reader.field(axis), 'must be within +-30000000');
  }
}

void _rules(_Reader rules, _Level level) {
  final _Check check = rules.check;
  final Set<String> refused = <String>{};
  for (final String key in rules.object.keys) {
    final bool allowed =
        combatPlainRules.contains(key) || (key == 'all' && level != _Level.nested) || (combatScanRules.contains(key) && level == _Level.seeking);
    if (allowed) continue;
    refused.add(key);
    if (key == 'all' || combatScanRules.contains(key)) {
      check.error(rules.field(key), 'not available ${level == _Level.nested ? 'inside a nested rule set' : 'in ignores'}');
    } else {
      check.error(rules.field(key), 'unknown setting');
    }
  }
  final List<String> categories = rules.strings('categories');
  for (int index = 0; index < categories.length; index++) {
    if (!combatCategories.contains(categories[index])) check.error('${rules.field('categories')}[$index]', 'unknown category');
  }
  final _Reader? patterns = rules.child('patterns');
  if (patterns != null) {
    patterns.known(combatPatternKeys);
    for (final String key in combatPatternKeys) {
      patterns.patterns(key);
    }
  }
  final _Reader? items = rules.child('items');
  if (items != null) {
    items.known(combatItemKeys);
    for (final String key in combatItemKeys) {
      items.patterns(key);
    }
  }
  rules.matching('effects', _namespaced, 'expected namespace:effect');
  rules.matching('permissions', _permission, 'expected a permission node');
  rules.matching('groups', _group, 'expected a group name');
  if (!refused.contains('all')) {
    final List<Object?>? all = rules.array('all', 16);
    for (int index = 0; index < (all?.length ?? 0); index++) {
      _nested(check, all![index], '${rules.field('all')}[$index]');
    }
  }
  if (!refused.contains('together')) {
    final List<Object?>? together = rules.array('together', 16);
    for (int index = 0; index < (together?.length ?? 0); index++) {
      final String path = '${rules.field('together')}[$index]';
      final Object? group = together![index];
      if (group is! List<Object?>) {
        check.error(path, 'expected array of rule sets');
        continue;
      }
      if (group.length < 2 || group.length > 8) {
        check.error(path, 'expected 2..8 rule sets');
        continue;
      }
      for (int part = 0; part < group.length; part++) {
        _nested(check, group[part], '$path[$part]');
      }
    }
  }
  if (!refused.contains('chat')) {
    final List<String> chat = rules.strings('chat');
    for (int index = 0; index < chat.length; index++) {
      final int length = chat[index].runes.length;
      if (length < 1 || length > 128) {
        check.error('${rules.field('chat')}[$index]', 'expected 1..128 characters');
      } else if (chat[index].trim().isEmpty) {
        check.error('${rules.field('chat')}[$index]', 'expected text');
      }
    }
  }
  for (final String key in const <String>['entityTypes', 'players', 'npcs', 'names', 'squads']) {
    rules.strings(key);
  }
  rules.boolean('owner');
  rules.number('healthAbove', combatRange('rule', 'healthAbove')!);
  rules.number('healthBelow', combatRange('rule', 'healthBelow')!);
  if (level == _Level.seeking) {
    final _Reader? events = rules.child('events');
    if (events != null) _fights(events);
  }
}

void _nested(_Check check, Object? value, String path) {
  if (value is! Map<String, Object?>) {
    check.error(path, 'expected object');
    return;
  }
  final int before = check.out.length;
  _rules(_Reader(check, value, path), _Level.nested);
  if (check.out.length == before && !combatRulesScan(value)) check.error(path, 'expected at least one rule');
}

void _fights(_Reader reader) {
  reader.known(const <String>['kinds', 'playerAttacks', 'anyAttacks']);
  final List<String> kinds = reader.strings('kinds');
  for (int index = 0; index < kinds.length; index++) {
    if (!combatFightKinds.contains(kinds[index])) {
      reader.check.error(
        '${reader.field('kinds')}[$index]',
        'expected player_hurts_player, player_hurts_mob, mob_hurts_mob, player_hurts_npc or npc_hurts_npc',
      );
    }
  }
  for (final String key in const <String>['playerAttacks', 'anyAttacks']) {
    if (reader.object.containsKey(key)) _nested(reader.check, reader.object[key], reader.field(key));
  }
}

/// Core's `Reader`: each read reports its own error and returns null.
class _Reader {
  _Reader(this.check, this.object, this.path);

  final _Check check;
  final Map<String, Object?> object;
  final String path;

  String field(String key) => '$path.$key';

  void known(List<String> keys) {
    for (final String key in object.keys) {
      if (!keys.contains(key)) check.error(field(key), 'unknown setting');
    }
  }

  /// The object at [key], or null when absent or not an object.
  _Reader? child(String key) {
    if (!object.containsKey(key)) return null;
    final Object? value = object[key];
    if (value is! Map<String, Object?>) {
      check.error(field(key), 'expected object');
      return null;
    }
    return _Reader(check, value, field(key));
  }

  bool? boolean(String key) {
    if (!object.containsKey(key)) return null;
    final Object? value = object[key];
    if (value is! bool) {
      check.error(field(key), 'expected boolean');
      return null;
    }
    return value;
  }

  void booleans(String section) {
    for (final String key in combatBooleans[section]?.keys ?? const <String>[]) {
      boolean(key);
    }
  }

  String? string(String key) {
    if (!object.containsKey(key)) return null;
    final Object? value = object[key];
    if (value is! String || value.length > 256) {
      check.error(field(key), 'expected string of at most 256 characters');
      return null;
    }
    return value;
  }

  double? finite(String key) {
    final Object? value = object[key];
    if (value is! num || !value.isFinite) {
      check.error(field(key), 'expected finite number');
      return null;
    }
    return value.toDouble();
  }

  double? number(String key, CombatRange range) {
    if (!object.containsKey(key)) return null;
    final double? value = finite(key);
    if (value == null) return null;
    if (value < range.minimum || value > range.maximum) {
      check.error(field(key), 'must be within ${range.bounds}');
      return null;
    }
    return value;
  }

  int? integer(String key, CombatRange range) {
    if (!object.containsKey(key)) return null;
    final Object? value = object[key];
    if (value is! num || !value.isFinite || value != value.truncateToDouble() || value.abs() > 2147483647) {
      check.error(field(key), 'expected integer');
      return null;
    }
    final int exact = value.toInt();
    if (exact < range.minimum || exact > range.maximum) {
      check.error(field(key), 'must be within ${range.bounds}');
      return null;
    }
    return exact;
  }

  void numbers(String section) {
    for (final CombatRange range in combatRanges) {
      if (range.section != section) continue;
      if (range.integer) {
        integer(range.key, range);
      } else {
        number(range.key, range);
      }
    }
  }

  /// A `namespace:id` string at [key]; absent reads as empty and fails, as core's `string(key, "")` does.
  void namespaced(String key, String expected) {
    final String? value = object.containsKey(key) ? string(key) : '';
    if (value != null && !_namespaced.hasMatch(value)) check.error(field(key), expected);
  }

  /// Whether [key] holds a canonical UUID; reports anything else that is present.
  bool uuid(String key) {
    if (!object.containsKey(key)) return false;
    final String? text = string(key);
    if (text == null) return false;
    if (!_uuid.hasMatch(text)) {
      check.error(field(key), 'expected canonical UUID');
      return false;
    }
    return true;
  }

  List<Object?>? array(String key, int maximum) {
    if (!object.containsKey(key)) return null;
    final Object? value = object[key];
    if (value is! List<Object?>) {
      check.error(field(key), 'expected array');
      return null;
    }
    if (value.length > maximum) {
      check.error(field(key), 'at most $maximum entries');
      return null;
    }
    return value;
  }

  /// The strings at [key]; non-strings are reported and left out.
  List<String> strings(String key) {
    final List<Object?>? values = array(key, 256);
    final List<String> result = <String>[];
    for (int index = 0; index < (values?.length ?? 0); index++) {
      final Object? value = values![index];
      if (value is String) {
        result.add(value);
      } else {
        check.error('${field(key)}[$index]', 'expected string');
      }
    }
    return result;
  }

  void matching(String key, RegExp form, String expected) {
    final List<Object?> values = asList(object[key]);
    final List<String> strings = this.strings(key);
    if (strings.length != values.length) return;
    for (int index = 0; index < strings.length; index++) {
      if (!form.hasMatch(strings[index])) check.error('${field(key)}[$index]', expected);
    }
  }

  void patterns(String key) {
    final List<Object?> values = asList(object[key]);
    for (int index = 0; index < values.length; index++) {
      final Object? value = values[index];
      if (value is! String) continue;
      final String at = '${field(key)}[$index]';
      if (value.isEmpty || value.length > 128) {
        check.error(at, 'pattern must be 1..128 characters');
        continue;
      }
      if (_javaOnly.hasMatch(value)) continue;
      try {
        RegExp(value, caseSensitive: false);
      } on FormatException catch (invalid) {
        check.error(at, 'invalid pattern: ${invalid.message.toLowerCase()}');
      }
    }
    strings(key);
  }
}
