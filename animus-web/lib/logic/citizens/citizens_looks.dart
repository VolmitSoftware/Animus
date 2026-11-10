/// Port of core's `CitizensLooks`: mob look traits, skin layers, boss bars,
/// player filters and click redirects.
library;

import '../gui/gui_json.dart';
import '../inspect/inspect_schemas.dart';
import 'field_notes.dart';
import 'java_json.dart';
import 'runtime_behaviors.dart';

class LooksResult {
  const LooksResult(this.fields, this.converted, this.notes, this.warnings);

  final JObject fields;
  final Set<String> converted;
  final Map<String, FieldNote> notes;
  final List<String> warnings;
}

enum _Kind { choice, flag, shown, number, id, text }

class _Rule {
  const _Rule(this.source, this.setting, this.kind, [this.fallback, this.aliases = const <String, String>{}]);

  final String source;
  final String setting;
  final _Kind kind;
  final String? fallback;
  final Map<String, String> aliases;
}

class _Trait {
  const _Trait(this.key, this.fields, this.rules);

  final String key;
  final Map<String, String> fields;
  final List<_Rule> rules;
}

_Rule _choice(String source, String setting, [String? fallback, Map<String, String> aliases = const <String, String>{}]) =>
    _Rule(source, setting, _Kind.choice, fallback, aliases);
_Rule _flag(String source, String setting) => _Rule(source, setting, _Kind.flag);
_Rule _shown(String source, String setting) => _Rule(source, setting, _Kind.shown);
_Rule _number(String source, String setting, [String? fallback]) => _Rule(source, setting, _Kind.number, fallback);

/// `BodyVariant.DYES`.
const List<String> _dyes = <String>[
  'white', 'orange', 'magenta', 'light_blue', 'yellow', 'lime', 'pink', 'gray', 'light_gray', 'cyan', 'purple', 'blue', 'brown', 'green', 'red', 'black', //
];
const List<String> _barColors = <String>['pink', 'blue', 'red', 'green', 'yellow', 'purple', 'white'];
const List<String> _barStyles = <String>['solid', 'segmented_6', 'segmented_10', 'segmented_12', 'segmented_20'];
const List<String> _barFlags = <String>['darken_sky', 'play_boss_music', 'create_fog'];
const Map<String, String> _chest = <String, String>{'minecraft:donkey': 'donkey', 'minecraft:mule': 'mule', 'minecraft:llama': 'llama'};

final List<_Trait> _traits = <_Trait>[
  _Trait('sheeptrait', const <String, String>{'minecraft:sheep': 'sheep'}, <_Rule>[_choice('color', 'color', 'white'), _flag('sheared', 'sheared')]),
  _Trait('woolcolor', const <String, String>{'minecraft:sheep': 'sheep'}, <_Rule>[_choice('', 'color')]),
  _Trait('cattrait', const <String, String>{'minecraft:cat': 'cat'}, <_Rule>[
    _choice('type', 'variant', 'black'), _choice('collarColor', 'collar'), _flag('sitting', 'sitting'), _flag('lying', 'lying'), //
  ]),
  _Trait('parrottrait', const <String, String>{'minecraft:parrot': 'parrot'}, <_Rule>[
    _choice('variant', 'variant', 'blue', const <String, String>{'red': 'red_blue', 'cyan': 'yellow_blue'}),
  ]),
  _Trait('foxtrait', const <String, String>{'minecraft:fox': 'fox'}, <_Rule>[
    _choice('type', 'type', 'red'), _flag('sitting', 'sitting'), _flag('crouching', 'crouching'), _flag('interested', 'interested'), //
    _flag('pouncing', 'pouncing'), _flag('sleeping', 'sleeping'), _flag('faceplanted', 'faceplanted'),
  ]),
  _Trait('axolotltrait', const <String, String>{'minecraft:axolotl': 'axolotl'}, <_Rule>[_choice('variant', 'variant'), _flag('playingDead', 'playingDead')]),
  _Trait('rabbittype', const <String, String>{'minecraft:rabbit': 'rabbit'}, <_Rule>[
    _choice('type', 'type', 'brown', const <String, String>{'black_and_white': 'white_splotched', 'salt_and_pepper': 'salt', 'the_killer_bunny': 'evil'}),
  ]),
  _Trait('profession', const <String, String>{'minecraft:villager': 'villager', 'minecraft:zombie_villager': 'zombieVillager'}, <_Rule>[_choice('', 'profession')]),
  _Trait('villagertrait', const <String, String>{'minecraft:villager': 'villager', 'minecraft:zombie_villager': 'zombieVillager'}, <_Rule>[
    _choice('type', 'type'),
    _choice('level', 'level', null, const <String, String>{'1': 'novice', '2': 'apprentice', '3': 'journeyman', '4': 'expert', '5': 'master'}),
  ]),
  _Trait('powered', const <String, String>{'minecraft:creeper': 'creeper'}, <_Rule>[_flag('', 'powered')]),
  _Trait('slimesize', const <String, String>{'minecraft:slime': 'slime', 'minecraft:magma_cube': 'magmaCube'}, <_Rule>[_number('size', 'size', '3')]),
  _Trait('phantomtrait', const <String, String>{'minecraft:phantom': 'phantom'}, <_Rule>[_number('size', 'size', '1')]),
  _Trait('mushroomcowtrait', const <String, String>{'minecraft:mooshroom': 'mooshroom'}, <_Rule>[_choice('variant', 'variant')]),
  _Trait('pandatrait', const <String, String>{'minecraft:panda': 'panda'}, <_Rule>[
    _choice('mainGene', 'mainGene', 'normal'), _choice('hiddenGene', 'hiddenGene'), _flag('sitting', 'sitting'), _flag('rolling', 'rolling'), //
    _flag('sneezing', 'sneezing'), _flag('eating', 'eating'),
  ]),
  _Trait('goattrait', const <String, String>{'minecraft:goat': 'goat'}, <_Rule>[_shown('leftHorn', 'leftHorn'), _shown('rightHorn', 'rightHorn')]),
  _Trait('pigtrait', const <String, String>{'minecraft:pig': 'pig'}, <_Rule>[_choice('variant', 'variant')]),
  _Trait('cowtrait', const <String, String>{'minecraft:cow': 'cow'}, <_Rule>[_choice('variant', 'variant')]),
  _Trait('chickentrait', const <String, String>{'minecraft:chicken': 'chicken'}, <_Rule>[_choice('variant', 'variant')]),
  _Trait('shulkertrait', const <String, String>{'minecraft:shulker': 'shulker'}, <_Rule>[_choice('color', 'color'), _number('peek', 'peek')]),
  _Trait('pufferfishtrait', const <String, String>{'minecraft:pufferfish': 'pufferfish'}, <_Rule>[
    _choice('puffState', 'puff', null, const <String, String>{'0': 'deflated', '1': 'half', '2': 'full'}),
  ]),
  _Trait('tropicalfishtrait', const <String, String>{'minecraft:tropical_fish': 'tropicalFish'}, <_Rule>[
    _choice('pattern', 'pattern', 'brinely'), _choice('bodyColor', 'baseColor', 'blue'), _choice('patternColor', 'patternColor', 'blue'),
  ]),
  _Trait('polarbeartrait', const <String, String>{'minecraft:polar_bear': 'polarBear'}, <_Rule>[_flag('rearing', 'standing')]),
  _Trait('piglintrait', const <String, String>{'minecraft:piglin': 'piglin'}, <_Rule>[_flag('dancing', 'dancing')]),
  _Trait('allaytrait', const <String, String>{'minecraft:allay': 'allay'}, <_Rule>[_flag('dancing', 'dancing')]),
  _Trait('beetrait', const <String, String>{'minecraft:bee': 'bee'}, <_Rule>[_flag('nectar', 'nectar'), _flag('stung', 'stung')]),
  _Trait('vextrait', const <String, String>{'minecraft:vex': 'vex'}, <_Rule>[_flag('charging', 'charging')]),
  _Trait('spellcastertrait', const <String, String>{'minecraft:evoker': 'evoker'}, <_Rule>[_choice('spell', 'spell')]),
  _Trait('sniffertrait', const <String, String>{'minecraft:sniffer': 'sniffer'}, <_Rule>[_choice('state', 'state')]),
  _Trait('coppergolemtrait', const <String, String>{'minecraft:copper_golem': 'copperGolem'}, <_Rule>[_choice('weather', 'weather')]),
  _Trait('endermantrait', const <String, String>{'minecraft:enderman': 'enderman'}, <_Rule>[_flag('angry', 'angry')]),
  _Trait('llamatrait', const <String, String>{'minecraft:llama': 'llama'}, <_Rule>[_choice('color', 'variant', 'brown')]),
  _Trait('frogtrait', const <String, String>{'minecraft:frog': 'frog'}, <_Rule>[_choice('variant', 'variant')]),
  _Trait('armadillotrait', const <String, String>{'minecraft:armadillo': 'armadillo'}, <_Rule>[_choice('state', 'state')]),
  _Trait('gamemodetrait', const <String, String>{'minecraft:player': 'player'}, <_Rule>[_choice('mode', 'gameMode')]),
  const _Trait('paintingtrait', <String, String>{'minecraft:painting': 'painting'}, <_Rule>[_Rule('art', 'variant', _Kind.id)]),
  _Trait('itemdisplaytrait', const <String, String>{'minecraft:item_display': 'itemDisplay'}, <_Rule>[_choice('transform', 'transform')]),
  _Trait('displaytrait', const <String, String>{
    'minecraft:block_display': 'blockDisplay', 'minecraft:item_display': 'itemDisplay', 'minecraft:text_display': 'textDisplay', //
  }, <_Rule>[_choice('billboard', 'billboard')]),
  _Trait('textdisplaytrait', const <String, String>{'minecraft:text_display': 'textDisplay'}, <_Rule>[
    const _Rule('text', 'text', _Kind.text), _choice('alignment', 'alignment'), _number('lineWidth', 'lineWidth'), _flag('shadowed', 'shadowed'), //
    _flag('seeThrough', 'seeThrough'),
  ]),
  _Trait('skinlayers', const <String, String>{'minecraft:player': 'player'}, <_Rule>[
    _shown('cape', 'cape'), _shown('hat', 'hat'), _shown('jacket', 'jacket'), _shown('left-sleeve', 'leftSleeve'), //
    _shown('right-sleeve', 'rightSleeve'), _shown('left-pants', 'leftPants'), _shown('right-pants', 'rightPants'),
  ]),
];

const Map<String, String> _settled = <String, String>{
  '/traits/llamatrait/strength': 'a llama\'s strength changes only what it carries',
  '/traits/horsemodifiers/tamed': 'taming changes nothing a horse body shows',
  '/traits/snowmantrait/formSnow': 'NPCs leave no snow trail',
};
const Map<String, String> _unpresented = <String, String>{
  'areaeffectcloudtrait': 'area effect clouds', 'boattrait': 'boats', 'interactiontrait': 'interaction entities',
};

/// [self] is the NPC's own UUID, which a click redirect may not name;
/// [protectedNpc] whether Citizens protected the NPC.
LooksResult mapLooks(String type, JObject traits, String self, bool protectedNpc) {
  final _Looks mapping = _Looks(type, traits, self, protectedNpc);
  for (final _Trait trait in _traits) {
    mapping.apply(trait);
  }
  mapping
    ..horse()
    ..snowman()
    ..bee()
    ..wolf()
    ..bat()
    ..wither()
    ..camel()
    ..endCrystal()
    ..bossBar()
    ..playerFilter()
    ..clickRedirect();
  for (final MapEntry<String, String> settled in _settled.entries) {
    if (mapping.at(settled.key) != null) mapping.notes[settled.key] = FieldNote(statusConverted, settled.value);
  }
  for (final MapEntry<String, String> body in _unpresented.entries) {
    if (traits.has(body.key)) mapping.kept('/traits/${body.key}', 'Animus does not present ${body.value}');
  }
  final List<String> sorted = List<String>.of(mapping.warnings)..sort();
  return LooksResult(mapping.fields.deepCopy(), Set<String>.of(mapping.converted), Map<String, FieldNote>.of(mapping.notes), sorted);
}

/// The schema node of one body setting (`BodyVariant.Setting`).
JsonMap _setting(String field, String key) =>
    asMap(asMap(asMap(asMap(InspectSchemas.embedded.npc.root['properties'])[field])['properties'])[key]);

List<String> _values(String field, String key) => <String>[for (final Object? value in asList(_setting(field, key)['enum'])) '$value'];

JObject? _object(JElement? value) => value is JObject ? value : null;
String _text(JElement? value, String fallback) => value is JPrimitive ? value.asString : fallback;
bool? _bool(JElement? value) {
  if (value is! JPrimitive) return null;
  final String text = value.asString;
  return text == 'true' ? true : (text == 'false' ? false : null);
}

double? _decimal(String text) {
  try {
    final double number = javaParseDouble(text);
    return number.isFinite ? number : null;
  } on FormatException {
    return null;
  }
}

/// `RED`, `minecraft:red` and `Red` all read as `red`.
String _normalize(String text) {
  final String name = text.toLowerCase().trim();
  final int colon = name.indexOf(':');
  return colon >= 0 ? name.substring(colon + 1) : name;
}

final RegExp _idPattern = RegExp(r'^([a-z0-9_.-]+:)?[a-z0-9_./-]+$');
BigInt _clamp(BigInt value, int minimum, int maximum) =>
    value < BigInt.from(minimum) ? BigInt.from(minimum) : (value > BigInt.from(maximum) ? BigInt.from(maximum) : value);
final RegExp _node = RegExp(r'^[A-Za-z0-9_.*-]{1,128}$');
final RegExp _uuid = RegExp(r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$');

class _Looks {
  _Looks(this.type, this.traits, this.self, this.protectedNpc);

  final bool protectedNpc;
  final String type;
  final JObject traits;
  final String self;
  final JObject fields = JObject();
  final Set<String> converted = <String>{};
  final Map<String, FieldNote> notes = <String, FieldNote>{};
  final List<String> warnings = <String>[];

  JElement? at(String path) {
    JElement? current = traits;
    for (final String part in path.substring('/traits/'.length).split('/')) {
      if (current is! JObject) return null;
      current = current.get(part);
    }
    return current;
  }

  void kept(String path, String reason) => notes[path] = FieldNote(unsupportedPreserved, reason);

  JObject settings(String field) {
    if (!fields.has(field)) fields.add(field, JObject());
    return fields.get(field)! as JObject;
  }

  void _dropEmpty(String field) {
    final JElement? value = fields.get(field);
    if (value is JObject && value.isEmpty) fields.remove(field);
  }

  void apply(_Trait trait) {
    if (!traits.has(trait.key)) return;
    final String base = '/traits/${trait.key}';
    final String? field = trait.fields[type];
    if (field == null) {
      final List<String> types = trait.fields.keys.toList()..sort();
      kept(base, 'applies only to ${types.join(' and ')} bodies, so Citizens did not show it either');
      return;
    }
    final JElement value = traits.get(trait.key)!;
    for (final _Rule rule in trait.rules) {
      final String path = rule.source.isEmpty ? base : '$base/${rule.source}';
      JElement? declared = rule.source.isEmpty ? value : (value is JObject ? value.get(rule.source) : null);
      if (declared is JNull) declared = null;
      if (declared == null && rule.fallback == null) continue;
      final String? text = declared == null ? rule.fallback : (declared is JPrimitive ? declared.asString : null);
      switch (rule.kind) {
        case _Kind.flag:
          final bool? on = _bool(declared);
          if (on == null) {
            if (declared != null) kept(path, 'not true or false');
            continue;
          }
          if (on) settings(field).addBool(rule.setting, true);
        case _Kind.shown:
          final bool? on = _bool(declared);
          if (on == null) {
            if (declared != null) kept(path, 'not true or false');
            continue;
          }
          if (!on) settings(field).addString(rule.setting, 'hidden');
        case _Kind.number:
          final double? number = text == null ? null : _decimal(text);
          if (number == null) {
            kept(path, 'not a number');
            continue;
          }
          final JsonMap node = _setting(field, rule.setting);
          final BigInt minimum = BigInt.from(node['minimum']! as num), maximum = BigInt.from(node['maximum']! as num);
          final BigInt whole = javaRound(number);
          final BigInt clamped = whole < minimum ? minimum : (whole > maximum ? maximum : whole);
          if (clamped != whole) warnings.add('$path: $whole is outside $minimum..$maximum and became $clamped');
          settings(field).addBig(rule.setting, clamped);
        case _Kind.id:
          String id = text == null ? '' : text.toLowerCase().trim();
          if (!id.contains(':')) id = 'minecraft:$id';
          if (!_idPattern.hasMatch(id)) {
            kept(path, '$text is not an id Animus knows');
            continue;
          }
          settings(field).addString(rule.setting, id);
        case _Kind.text:
          if (text == null) {
            kept(path, 'not text');
            continue;
          }
          String shown = text.replaceAll(RegExp(r'[\x00-\x1F\x7F]'), ' ');
          if (shown.length > 256) {
            shown = shown.substring(0, 256);
            warnings.add('$path: cut to 256 characters');
          }
          settings(field).addString(rule.setting, shown);
        case _Kind.choice:
          final String? name = text == null ? null : _normalize(text);
          final String? mapped = name == null ? null : (rule.aliases[name] ?? name);
          if (mapped == null || !_values(field, rule.setting).contains(mapped)) {
            kept(path, '${text ?? 'this value'} is not a ${rule.setting} Animus knows');
            continue;
          }
          settings(field).addString(rule.setting, mapped);
      }
      if (declared != null) converted.add(path);
    }
    _dropEmpty(field);
  }

  void horse() {
    final JObject? horse = _object(traits.get('horsemodifiers'));
    if (horse == null) return;
    const String base = '/traits/horsemodifiers';
    if (type == 'minecraft:horse') {
      final String color = _normalize(_text(horse.get('color'), 'creamy'));
      String style = _normalize(_text(horse.get('style'), 'none'));
      if (style == 'whitefield') style = 'white_field';
      if (_values('horse', 'color').contains(color)) {
        settings('horse').addString('color', color);
        if (horse.has('color')) converted.add('$base/color');
      } else {
        kept('$base/color', '$color is not a horse colour Animus knows');
      }
      if (_values('horse', 'markings').contains(style)) {
        if (style != 'none') settings('horse').addString('markings', style);
        if (horse.has('style')) converted.add('$base/style');
      } else {
        kept('$base/style', '$style is not a horse marking Animus knows');
      }
    } else {
      for (final String key in const <String>['color', 'style']) {
        if (horse.has(key)) kept('$base/$key', 'horse coats show only on minecraft:horse bodies, so Citizens did not show it either');
      }
    }
    final bool? chest = _bool(horse.get('carryingChest'));
    if (chest == null) return;
    final String? field = _chest[type];
    if (field == null) {
      if (chest) {
        kept('$base/carryingChest', 'only donkeys, mules and llamas carry a chest');
      } else {
        converted.add('$base/carryingChest');
      }
      return;
    }
    if (chest) settings(field).addBool('chest', true);
    converted.add('$base/carryingChest');
  }

  void snowman() {
    final JObject? snowman = _object(traits.get('snowmantrait'));
    if (snowman == null || !snowman.has('derp')) return;
    if (type != 'minecraft:snow_golem') {
      kept('/traits/snowmantrait/derp', 'applies only to minecraft:snow_golem bodies, so Citizens did not show it either');
      return;
    }
    if (_bool(snowman.get('derp')) == true) settings('snowGolem').addString('pumpkin', 'hidden');
    converted.add('/traits/snowmantrait/derp');
  }

  void bee() {
    final JObject? bee = _object(traits.get('beetrait'));
    if (bee == null || !bee.has('anger') || type != 'minecraft:bee') return;
    final JElement anger = bee.get('anger')!;
    final double? value = anger is JPrimitive ? _decimal(anger.asString) : null;
    if (value == null) {
      kept('/traits/beetrait/anger', 'not a number');
      return;
    }
    if (value > 0) settings('bee').addBool('angry', true);
    converted.add('/traits/beetrait/anger');
  }

  void wolf() {
    final JObject? wolf = _object(traits.get('wolfmodifiers'));
    if (wolf == null) return;
    const String base = '/traits/wolfmodifiers';
    if (type != 'minecraft:wolf') {
      kept(base, 'applies only to minecraft:wolf bodies, so Citizens did not show it either');
      return;
    }
    if (wolf.has('variant')) {
      final String variant = _normalize(_text(wolf.get('variant'), ''));
      if (_values('wolf', 'variant').contains(variant)) {
        settings('wolf').addString('variant', variant);
        converted.add('$base/variant');
      } else {
        kept('$base/variant', '$variant is not a wolf coat Animus knows');
      }
    }
    final bool tamed = _bool(wolf.get('tamed')) == true;
    if (wolf.has('tamed')) converted.add('$base/tamed');
    final String collar = _normalize(_text(wolf.get('collarColor'), 'red'));
    if (tamed && _dyes.contains(collar)) settings('wolf').addString('collar', collar);
    if (wolf.has('collarColor')) {
      if (tamed && !_dyes.contains(collar)) {
        kept('$base/collarColor', '$collar is not a dye colour');
      } else {
        notes['$base/collarColor'] = FieldNote(statusConverted, tamed ? '' : 'a collar shows only on a tamed wolf');
      }
    }
    for (final String key in const <String>['sitting', 'angry', 'interested']) {
      if (!wolf.has(key)) continue;
      if (_bool(wolf.get(key)) == true) settings('wolf').addBool(key, true);
      converted.add('$base/$key');
    }
    _dropEmpty('wolf');
  }

  void bat() {
    final JObject? bat = _object(traits.get('battrait'));
    if (bat == null) return;
    if (type != 'minecraft:bat') {
      kept('/traits/battrait', 'applies only to minecraft:bat bodies, so Citizens did not show it either');
      return;
    }
    if (_bool(bat.get('awake')) != true) settings('bat').addBool('resting', true);
    if (bat.has('awake')) converted.add('/traits/battrait/awake');
  }

  void wither() {
    final JObject? wither = _object(traits.get('withertrait'));
    if (wither == null) return;
    const String base = '/traits/withertrait';
    if (type != 'minecraft:wither') {
      kept(base, 'applies only to minecraft:wither bodies, so Citizens did not show it either');
      return;
    }
    final bool? charged = _bool(wither.get('charged'));
    final JElement? declaredTicks = wither.get('invulnerableticks');
    final double? ticks = declaredTicks is JPrimitive ? _decimal(declaredTicks.asString) : null;
    final BigInt shown = charged != null
        ? BigInt.from(charged ? 20 : 0)
        : ticks != null
            ? _clamp(javaRound(ticks), 0, 1000)
            : BigInt.from(protectedNpc ? 20 : 0);
    if (shown > BigInt.zero) settings('wither').addBig('invulnerableTicks', shown);
    if (charged != null) converted.add('$base/charged');
    if (ticks != null) converted.add('$base/invulnerableticks');
    if (_bool(wither.get('arrowshield')) == true) settings('wither').addBool('armored', true);
    if (wither.has('arrowshield')) converted.add('$base/arrowshield');
  }

  void camel() {
    final JObject? camel = _object(traits.get('cameltrait'));
    if (camel == null || !camel.has('pose')) return;
    const String path = '/traits/cameltrait/pose';
    if (type != 'minecraft:camel') {
      kept('/traits/cameltrait', 'applies only to minecraft:camel bodies, so Citizens did not show it either');
      return;
    }
    final String pose = _normalize(_text(camel.get('pose'), ''));
    if (pose == 'sitting') {
      settings('camel').addBool('sitting', true);
    } else if (pose == 'panic') {
      settings('camel').addBool('dashing', true);
    } else if (pose != 'standing') {
      kept(path, '$pose is not a camel pose Animus knows');
      return;
    }
    converted.add(path);
  }

  void endCrystal() {
    final JObject? crystal = _object(traits.get('endercrystaltrait'));
    if (crystal == null) return;
    if (type != 'minecraft:end_crystal') {
      kept('/traits/endercrystaltrait', 'applies only to minecraft:end_crystal bodies, so Citizens did not show it either');
      return;
    }
    if (_bool(crystal.get('showBase')) != true) settings('endCrystal').addString('base', 'hidden');
    if (crystal.has('showBase')) converted.add('/traits/endercrystaltrait/showBase');
  }

  void bossBar() {
    final JObject? bar = _object(traits.get('bossbar'));
    if (bar == null) return;
    const String base = '/traits/bossbar';
    if (_bool(bar.get('visible')) == false) {
      notes[base] = const FieldNote(statusConverted, 'the bar was hidden, so the NPC shows none');
      return;
    }
    final JObject result = JObject();
    if (bar.has('visible')) converted.add('$base/visible');
    String title = _text(bar.get('title'), '');
    if (title.isNotEmpty) {
      if (title.length > 256) {
        title = title.substring(0, 256);
        warnings.add('$base/title: cut to 256 characters');
      }
      result.addString('title', title.replaceAll(RegExp(r'[\x00-\x1F\x7F]'), ' '));
    }
    if (bar.has('title')) converted.add('$base/title');
    _choose(bar, base, 'color', _barColors, 'purple', result);
    _choose(bar, base, 'style', _barStyles, 'solid', result);
    final JArray flags = JArray();
    for (final MapEntry<String, JElement> entry in listEntries(bar.get('flags'))) {
      final String flag = entry.value is JPrimitive ? _normalize(entry.value.asPrimitive.asString) : '';
      if (_barFlags.contains(flag)) {
        if (!flags.contains(JString(flag))) flags.addString(flag);
        converted.add('$base/flags/${entry.key}');
      } else {
        kept('$base/flags/${entry.key}', 'not a boss bar flag Animus knows');
      }
    }
    if (flags.isNotEmpty) result.add('flags', flags);
    final JElement? declaredRange = bar.get('range');
    final double? range = declaredRange is JPrimitive ? _decimal(declaredRange.asString) : null;
    if (range != null) {
      if (range > 0) result.addDouble('range', range < 1 ? 1 : (range > 256 ? 256 : range));
      if (range > 256) warnings.add('$base/range: ranges above 256 blocks became 256');
      converted.add('$base/range');
    }
    final String track = _text(bar.get('track'), '');
    if (track.toLowerCase() == 'health') {
      result.addString('progress', 'health');
      converted.add('$base/track');
    } else if (track.isNotEmpty) {
      notes['$base/track'] = const FieldNote(requiresReview, 'a placeholder fill is not converted; the bar is full');
    }
    final String permission = _text(bar.get('viewPermission'), '');
    if (permission.isNotEmpty) {
      if (_node.hasMatch(permission)) {
        result.addString('permission', permission);
        converted.add('$base/viewPermission');
      } else {
        kept('$base/viewPermission', 'not a permission node Animus accepts');
      }
    }
    fields.add('bossBar', result);
  }

  void _choose(JObject bar, String base, String key, List<String> values, String fallback, JObject result) {
    if (!bar.has(key)) return;
    final String name = _normalize(_text(bar.get(key), ''));
    if (!values.contains(name)) {
      kept('$base/$key', '$name is not a boss bar $key Animus knows');
      return;
    }
    if (name != fallback) result.addString(key, name);
    converted.add('$base/$key');
  }

  void playerFilter() {
    final JObject? filter = _object(traits.get('playerfilter'));
    if (filter == null) return;
    const String base = '/traits/playerfilter';
    final JObject result = JObject();
    final String mode = _normalize(_text(filter.get('mode'), 'denylist'));
    if (mode == 'allowlist') result.addString('mode', 'allow');
    if (filter.has('mode')) converted.add('$base/mode');
    _list(filter, base, 'players', _uuid, true, result);
    _list(filter, base, 'groups', _node, false, result);
    _list(filter, base, 'permissions', _node, false, result);
    final JElement? declaredRange = filter.get('applyRange');
    final double? range = declaredRange is JPrimitive ? _decimal(declaredRange.asString) : null;
    if (range != null) {
      if (range >= 1) result.addDouble('range', range > 512 ? 512 : range);
      converted.add('$base/applyRange');
    }
    if (!result.has('players') && !result.has('groups') && !result.has('permissions')) return;
    fields.add('viewers', result);
  }

  void _list(JObject filter, String base, String key, RegExp pattern, bool lower, JObject result) {
    final JArray values = JArray();
    for (final MapEntry<String, JElement> entry in listEntries(filter.get(key))) {
      final String path = '$base/$key/${entry.key}';
      String value = entry.value is JPrimitive ? entry.value.asPrimitive.asString : '';
      if (lower) value = value.toLowerCase();
      if (!pattern.hasMatch(value)) {
        kept(path, 'not a value Animus accepts');
        continue;
      }
      if (values.length == 256) {
        kept(path, 'a filter keeps at most 256 entries in each list');
        continue;
      }
      if (!values.contains(JString(value))) values.addString(value);
      converted.add(path);
    }
    if (values.isNotEmpty) result.add(key, values);
  }

  void clickRedirect() {
    final JObject? redirect = _object(traits.get('clickredirecttrait'));
    if (redirect == null || !redirect.has('uuid')) return;
    final String target = _text(redirect.get('uuid'), '').toLowerCase();
    const String path = '/traits/clickredirecttrait/uuid';
    if (!_uuid.hasMatch(target)) {
      kept(path, 'not an NPC UUID');
      return;
    }
    if (target == self) {
      kept(path, 'the NPC redirects clicks to itself');
      return;
    }
    fields.addString('clickRedirect', target);
    converted.add(path);
  }
}
