// A line-for-line copy of core's `DenizenCommands`: Denizen command lists as behavior nodes. Blocks, if/else and
// choose chains, loops and their stop/next, and each command with an Animus action. A command that cannot convert is
// kept in the report with its reason, and so is every line inside a block that is kept.
part of 'denizen_converter.dart';

/// The click-time condition of an NPC event and the `determine cancelled` line it reaches.
class _Consume {
  const _Consume(this.item, this.condition);

  final DenizenItem item;
  final String condition;
}

/// Converted steps, or the reason the command is kept.
class _Converted {
  const _Converted(this.steps, this.reason);

  _Converted.of(List<JObject> steps) : this(steps, '');

  const _Converted.kept(String reason) : this(const <JObject>[], reason);

  final List<JObject> steps;
  final String reason;
}

/// An action under construction: literal arguments, and expressions and templates that fill null placeholders.
class _Action {
  final JObject arguments = JObject();
  final JObject expressions = JObject();
  final JObject templates = JObject();

  void put(String key, JElement value) => arguments.add(key, value);

  void text(String key, _Text text) {
    if (!text.isDynamic) {
      arguments.addString(key, text.literal!);
      return;
    }
    arguments.add(key, JNull.instance);
    templates.addString('/$key', text.template);
  }

  void expression(String key, String expression) {
    arguments.add(key, JNull.instance);
    expressions.addString('/$key', expression);
  }

  JObject node(String capability) {
    final JObject node = JObject()
      ..addString('type', 'action')
      ..addString('capability', capability)
      ..add('arguments', arguments);
    if (expressions.isNotEmpty) node.add('expressions', expressions);
    if (templates.isNotEmpty) node.add('templates', templates);
    return node;
  }
}

JObject _action(String capability, JObject arguments) => JObject()
  ..addString('type', 'action')
  ..addString('capability', capability)
  ..add('arguments', arguments);

JObject _plain(String type) => JObject()..addString('type', type);

String _raw(DenizenArg arg) => arg.prefix.isEmpty ? arg.value : '${arg.prefix}:${arg.value}';

bool _playerTarget(String value) {
  final String lowered = value.toLowerCase();
  return lowered == '<player>' || lowered == 'player';
}

bool _everyone(String value) => value.toLowerCase() == '<server.online_players>';

final RegExp _heldPlayerPattern = RegExp(r'^(?:player:)?<\[([a-z0-9_]{1,64})](?:\.(?:name|uuid))?>$', caseSensitive: false);

/// A player held in a definition, as Sentinel's target syntax names them: `player:<[target].name>` reads `<[target]>`.
String? _heldPlayer(String value) {
  final RegExpMatch? match = _heldPlayerPattern.firstMatch(value);
  return match == null ? null : '<[${match.group(1)}]>';
}

/// The player who triggered the behavior, as Sentinel's target syntax names them.
bool _triggeringPlayer(String value) {
  String lowered = value.toLowerCase();
  if (lowered.startsWith('player:')) lowered = lowered.substring(7);
  return lowered == '<player>' || lowered == '<player.name>' || lowered == '<player.uuid>';
}

bool _npcTarget(String value) {
  final String lowered = value.toLowerCase();
  return lowered == '<npc>' || lowered == 'npc';
}

bool _mentions(DenizenItem item, String text) {
  if (item.text.toLowerCase().contains(text)) return true;
  if (item.children != null) {
    for (final DenizenItem child in item.children!.items) {
      if (_mentions(child, text)) return true;
    }
  }
  return false;
}

bool _mentionsBody(DenizenItem item, String text) {
  for (final DenizenItem child in item.children!.items) {
    if (_mentions(child, text)) return true;
  }
  return false;
}

/// `cooldown <duration> (global)` in an interact script becomes its click trigger's player or global cooldown.
_Converted _cooldown(DenizenCommand command, _Scope scope) {
  if (scope.interact == null) return const _Converted.kept('cooldown belongs to an interact script');
  bool global = false;
  int? ticks;
  for (final DenizenArg arg in command.args) {
    if (arg.prefix.isEmpty && arg.value.toLowerCase() == 'global') {
      global = true;
    } else if (arg.prefix.isEmpty || arg.prefix == 'duration') {
      ticks = denizenTicks(arg.value);
    } else {
      return _Converted.kept('cooldown ${arg.prefix}: has no Animus equivalent');
    }
  }
  if (ticks == null) return const _Converted.kept('cooldown needs a literal duration');
  if (ticks > 72000) return const _Converted.kept('cooldown longer than an hour');
  if (global) {
    if (ticks > scope.doc.globalCooldown) scope.doc.globalCooldown = ticks;
  } else if (ticks > scope.doc.playerCooldown) {
    scope.doc.playerCooldown = ticks;
  }
  return _Converted.of(<JObject>[]);
}


_Action _reorder(_Action action, List<String> order) {
  final _Action sorted = _Action();
  for (final String key in order) {
    if (action.arguments.has(key)) sorted.arguments.add(key, action.arguments.get(key));
  }
  for (final MapEntry<String, JElement> entry in action.arguments.entries) {
    if (!sorted.arguments.has(entry.key)) sorted.arguments.add(entry.key, entry.value);
  }
  for (final MapEntry<String, JElement> entry in action.expressions.entries) {
    sorted.expressions.add(entry.key, entry.value);
  }
  for (final MapEntry<String, JElement> entry in action.templates.entries) {
    sorted.templates.add(entry.key, entry.value);
  }
  return sorted;
}

String _locationText(String value) {
  String text = value;
  if (text.toLowerCase().startsWith('l@')) text = text.substring(2);
  if (text.toLowerCase().startsWith('<location[') && text.endsWith(']>')) text = text.substring(10, text.length - 2);
  return text;
}

List<double>? _location(String value) {
  final List<String> parts = _locationText(value).split(',');
  if (parts.length < 3 || parts.length > 6) return null;
  final List<double> coordinates = <double>[];
  for (int index = 0; index < 3; index++) {
    if (!_full(r'-?[0-9]{1,8}(?:\.[0-9]{1,6})?', parts[index])) return null;
    coordinates.add(javaParseDouble(parts[index]));
  }
  return coordinates;
}

/// The world, yaw and pitch of a literal location: `x,y,z,world` or `x,y,z,pitch,yaw,world`.
List<String?>? _locationExtras(String value) {
  final List<String> parts = _locationText(value).split(',');
  if (parts.length == 4) return _full('[A-Za-z0-9_./-]{1,64}', parts[3]) ? <String?>[parts[3], null, null] : null;
  if (parts.length == 5 || parts.length == 6) {
    if (!_full(r'-?[0-9]{1,3}(?:\.[0-9]{1,6})?', parts[3]) || !_full(r'-?[0-9]{1,3}(?:\.[0-9]{1,6})?', parts[4])) return null;
    final String? world = parts.length == 6 ? parts[5] : null;
    if (world != null && !_full('[A-Za-z0-9_./-]{1,64}', world)) return null;
    return <String?>[world, parts[4], parts[3]];
  }
  return <String?>[null, null, null];
}

_Converted _walk(DenizenCommand command) {
  List<DenizenArg> plain = command.plain();
  if (plain.length != command.args.length) return const _Converted.kept('walk argument has no Animus equivalent');
  if (plain.length == 2 && _npcTarget(plain.first.value)) plain = plain.sublist(1, 2);
  if (plain.length != 1) return const _Converted.kept('walk needs one literal location');
  final List<double>? at = _location(plain.first.value);
  if (at == null) return const _Converted.kept('walk needs a literal location');
  return _Converted.of(<JObject>[
    _action(
        'animus:navigate',
        JObject()
          ..addDouble('x', at[0])
          ..addDouble('y', at[1])
          ..addDouble('z', at[2])),
  ]);
}

const Map<String, String> _slots = <String, String>{
  'hand': 'main_hand', 'offhand': 'off_hand', 'head': 'head', 'chest': 'chest', 'legs': 'legs', 'boots': 'feet',
};
const List<String> _slotOrder = <String>['hand', 'offhand', 'head', 'chest', 'legs', 'boots'];

/// A place held in a definition: `<[someone]>`, `<[someone].location>` or `<[someone].location.above[2]>`, as the
/// definition's tag and what follows it. The definition is read as players, so a fan-out over it shows the effect at
/// each player and skips anything that is no player.
final RegExp _heldPlacePattern = RegExp(r'^(<\[[A-Za-z0-9_]+])((?:\.location(?:\.above(?:\[[0-9.]{1,6}])?)?)?)>$');
final RegExp _abovePattern = RegExp(r'^<(?:player|npc)\.location\.above(?:\[([0-9]{1,2}(?:\.[0-9]{1,4})?)])?>$');

List<String>? _heldPlace(String value) {
  final RegExpMatch? match = _heldPlacePattern.firstMatch(value);
  return match == null ? null : <String>['${match.group(1)}>', match.group(2)!];
}

/// How far above the feet `<player.location.above[n]>` or `<npc.location.above[n]>` is: n, or 1 without it.
double? _above(String value) {
  final RegExpMatch? match = _abovePattern.firstMatch(value.toLowerCase());
  if (match == null) return null;
  final double height = match.group(1) == null ? 1 : javaParseDouble(match.group(1)!);
  return height <= 16 ? height : null;
}

/// Where a sound or particle plays: `player` or `npc`, or null when the place has no Animus equivalent.
String? _at(String value) {
  final String target = value.toLowerCase();
  if (target == '<player>' || target == '<player.location>' || target == '<player.eye_location>' || _full(r'<player\.location\.above(\[[0-9.]{1,6}])?>', target)) return 'player';
  if (target == '<npc>' || target == '<npc.location>' || target == '<npc.eye_location>' || _full(r'<npc\.location\.above(\[[0-9.]{1,6}])?>', target)) {
    return 'npc';
  }
  return null;
}

double? _number(String value, double minimum, double maximum) {
  if (!_full(r'[0-9]{1,4}(?:\.[0-9]{1,6})?', value)) return null;
  final double number = javaParseDouble(value);
  return number < minimum || number > maximum ? null : number;
}

const Map<String, String> _particleNames = <String, String>{
  'villager_happy': 'happy_villager', 'villager_angry': 'angry_villager', 'spell_witch': 'witch',
  'smoke_normal': 'smoke', 'smoke_large': 'large_smoke', 'explosion_normal': 'poof',
  'explosion_large': 'explosion', 'explosion_huge': 'explosion_emitter', 'water_splash': 'splash',
  'water_bubble': 'bubble', 'water_wake': 'fishing', 'suspended': 'underwater',
  'suspended_depth': 'underwater', 'crit_magic': 'enchanted_hit', 'spell': 'effect',
  'spell_instant': 'instant_effect', 'drip_water': 'dripping_water', 'drip_lava': 'dripping_lava',
  'enchantment_table': 'enchant', 'town_aura': 'mycelium', 'snowball': 'item_snowball',
  'snow_shovel': 'item_snowball', 'slime': 'item_slime', 'fireworks_spark': 'firework',
  'totem': 'totem_of_undying', 'mob_appearance': 'elder_guardian', 'water_drop': 'rain',
};
const Set<String> _dataParticles = <String>{
  'dust', 'redstone', 'block', 'block_crack', 'block_dust', 'item', 'item_crack', 'falling_dust', 'dust_color_transition',
  'vibration', 'entity_effect', 'spell_mob', 'spell_mob_ambient', 'block_marker', 'sculk_charge', 'shriek', 'dust_pillar', 'trail', 'legacy_block_marker',
};

/// [npcGiven] says the caller names the NPC with the npc argument, which a server behavior may do too.
_Converted _particle(DenizenCommand command, _Scope scope, bool npcGiven) {
  String? effect;
  String place = scope.server ? 'player' : 'npc';
  bool trigger = false;
  int? count;
  double? spread;
  double? height;
  for (final DenizenArg arg in command.args) {
    switch (arg.prefix) {
      case 'effect':
        effect = arg.value.toLowerCase();
      case 'at':
        final String? where = _at(arg.value);
        if (where == null) return const _Converted.kept('particles show only at the NPC or a player in Animus');
        place = where;
        height = _above(arg.value);
      case 'quantity':
        if (!_full('[0-9]{1,2}', arg.value) || int.parse(arg.value) < 1 || int.parse(arg.value) > 64) return const _Converted.kept('quantity must be from 1 to 64');
        count = int.parse(arg.value);
      case 'offset':
        double widest = 0;
        for (final String part in arg.value.split(',')) {
          final double? value = _number(part, 0, 8);
          if (value == null) return const _Converted.kept('offset must be from 0 to 8 blocks');
          widest = widest > value ? widest : value;
        }
        spread = widest;
      case 'targets':
        if (!_playerTarget(arg.value)) return const _Converted.kept('particles for other targets have no Animus equivalent');
        trigger = true;
      case 'visibility':
      case 'data':
        // range and particle speed are not carried; the docs list this
        break;
      default:
        return _Converted.kept('playeffect argument ${arg.prefix.isEmpty ? arg.value : '${arg.prefix}:'} has no Animus equivalent');
    }
  }
  if (place == 'npc' && scope.server && !npcGiven) return const _Converted.kept('particles at the NPC need an NPC; $_noNpc');
  if (effect == null || effect.contains('<')) return const _Converted.kept('playeffect needs a literal effect');
  String name = effect.startsWith('minecraft:') ? effect.substring(10) : effect;
  if (_dataParticles.contains(name)) return const _Converted.kept('particles that need extra data have no Animus equivalent');
  name = _particleNames[name] ?? name;
  if (!_full('[a-z0-9_]{1,64}', name)) return _Converted.kept('effect $effect has no particle key');
  final JObject arguments = JObject()..addString('particle', 'minecraft:$name');
  if (count != null) arguments.addInt('count', count);
  if (spread != null) arguments.addDouble('spread', spread);
  if (height != null) arguments.addDouble('height', height);
  if (place == 'player') {
    arguments.addString('at', 'player');
  } else if (!trigger) {
    arguments.addString('audience', 'nearby');
  }
  return _Converted.of(<JObject>[_action('animus:spawn-particle', arguments)]);
}

/// The resource key of a sound name read as the sound plays, as [denizenSoundKey] makes it from a written name: a key
/// stays, a dotted name gets the namespace, and a Bukkit name has its first and last underscores turned into dots.
String _soundKey(String name, bool custom) {
  final String words = "split($name, '_')";
  final String bukkit = custom ? name : "(contains($name, '.') ? $name : first($words) + '.' + join(slice($words, 1, len($words) - 1), '_') + '.' + last($words))";
  return "(contains($name, ':') ? $name : 'minecraft:' + $bukkit)";
}

/// A number as Denizen writes it: without a trailing `.0`.
String _trim(double number) => number == number.roundToDouble() ? number.toInt().toString() : javaDoubleToString(number);

/// [action] followed by the named arguments of [from], with their expressions.
_Action _merged(_Action action, _Action from, List<String> names) {
  for (final String name in names) {
    if (!from.arguments.has(name)) continue;
    action.arguments.add(name, from.arguments.get(name));
    if (from.expressions.has('/$name')) action.expressions.add('/$name', from.expressions.get('/$name'));
  }
  return action;
}

const Map<String, String> _animations = <String, String>{
  'arm_swing': 'swing', 'swing_main_hand': 'swing', 'arm_swing_offhand': 'swing_offhand', 'swing_off_hand': 'swing_offhand',
  'hurt': 'hurt', 'crit': 'crit', 'magic_crit': 'magic_crit', 'sit': 'sit', 'stop_sitting': 'stand',
  'sleep': 'sleep', 'stop_sleeping': 'wake', 'sneak': 'sneak', 'stop_sneaking': 'stand',
  'start_use_mainhand_item': 'use_item', 'stop_use_item': 'stop_using_item',
};

/// `animate <npc> animation:arm_swing`: the vanilla animation every viewer of the NPC sees.
class _Commands {
  _Commands(this.converter, this.expressions);

  final _Converter converter;
  final _Expressions expressions;

  // ---- Blocks ----

  List<JObject> block(List<DenizenItem> items, int from, _Scope scope) {
    final List<JObject> steps = <JObject>[];
    for (int index = from; index < items.length; index++) {
      final DenizenItem item = items[index];
      final DenizenCommand command = denizenCommand(item.text);
      switch (command.name) {
        case 'stop':
          converter.outcome(scope, item, 'command', 'stop', true, '');
          steps.add(_plain('return'));
          preserveItems(scope, items, index + 1, 'never runs: after stop');
          return steps;
        case 'determine':
          bool passive = false;
          for (final DenizenArg arg in command.args) {
            if (arg.isWord('passively')) passive = true;
          }
          if (identical(item, scope.cancel)) {
            converter.outcome(scope, item, 'command', 'determine', true, '');
            if (passive) continue;
            steps.add(_plain('return'));
            preserveItems(scope, items, index + 1, 'never runs: after determine');
            return steps;
          }
          if (passive) {
            converter.outcome(scope, item, 'command', 'determine', false, 'determine passively sets a value and goes on; a routine returns only by ending');
            _hold(scope, item);
            continue;
          }
          steps.addAll(determine(item, command, scope));
          preserveItems(scope, items, index + 1, 'never runs: after determine');
          return steps;
        case 'if':
        case 'choose':
          int end = index + 1;
          if (command.name == 'if') {
            while (end < items.length && denizenCommand(items[end].text).name == 'else') {
              end++;
            }
          }
          final List<JObject>? chained = chain(items, index, end, scope);
          if (chained != null) {
            steps.addAll(chained);
          } else if (_held(scope, item, items.sublist(index, end))) {
            return close(scope, items, end, steps);
          }
          index = end - 1;
        case 'else':
          preserveTree(scope, item, 'else without a matching if');
          if (_held(scope, item, <DenizenItem>[item])) return close(scope, items, index + 1, steps);
        case 'ratelimit':
          if (item.children != null) {
            preserveTree(scope, item, 'no Animus equivalent for the ratelimit block');
          } else {
            final _Converted converted = ratelimit(item, command, scope);
            converter.outcome(scope, item, 'command', 'ratelimit', converted.reason.isEmpty, converted.reason);
            if (converted.reason.isEmpty) {
              steps.addAll(converted.steps);
              continue;
            }
          }
          _hold(scope, item);
          return close(scope, items, index + 1, steps);
        case 'random':
          if (item.children == null || command.args.isNotEmpty || item.children!.items.isEmpty) {
            preserveTree(scope, item, 'random needs a block of lines to choose from and no arguments');
            if (item.children != null && _stops(item.children!.items, const <String>{})) {
              _hold(scope, item);
              return close(scope, items, index + 1, steps);
            }
            continue;
          }
          steps.addAll(random(item, scope));
        case 'repeat':
        case 'foreach':
        case 'while':
          final List<JObject>? converted = item.children == null ? control(item, command, scope) : loop(item, command, scope);
          if (converted != null) {
            steps.addAll(converted);
          } else if (_held(scope, item, <DenizenItem>[item])) {
            return close(scope, items, index + 1, steps);
          }
        default:
          if (item.children != null) {
            preserveTree(scope, item, 'no Animus equivalent for the ${command.name} block');
            if (_stops(item.children!.items, const <String>{})) {
              _hold(scope, item);
              return close(scope, items, index + 1, steps);
            }
            continue;
          }
          final _Pending pending = _Pending();
          final _Converted converted = simple(command, scope, pending);
          converter.outcome(scope, item, 'command', command.name, converted.reason.isEmpty, converted.reason);
          if (converted.reason.isEmpty) {
            steps
              ..addAll(pending.steps)
              ..addAll(converted.steps);
            continue;
          }
          if (_guards.contains(command.name)) _hold(scope, item);
          if (_stops(<DenizenItem>[item], const <String>{})) return close(scope, items, index + 1, steps);
      }
    }
    return steps;
  }

  /// Commands that limit or end a run; when one does not convert, the behavior is held back from binding.
  static const Set<String> _guards = <String>{'stop', 'determine', 'queue', 'inject', 'ratelimit', 'cooldown'};

  /// Records a guard or stop that did not convert, which holds its behavior back from binding until an admin reviews it.
  static void _hold(_Scope scope, DenizenItem item) => scope.doc.held.add('${scope.container.file}:${item.line}');

  /// Holds the behavior for a kept control block; true when the block may end the script, so the routine must end too.
  static bool _held(_Scope scope, DenizenItem item, List<DenizenItem> trees) {
    _hold(scope, item);
    return _stops(trees, const <String>{});
  }

  /// Fails closed after a kept line that may end the script: the routine returns there, as if the guard had stopped it,
  /// and every later line is kept, so nothing a guard protected runs unchecked.
  List<JObject> close(_Scope scope, List<DenizenItem> items, int from, List<JObject> steps) {
    steps.add(_plain('return'));
    preserveItems(scope, items, from, 'never runs: after a line that did not convert and may stop the script; the routine ends there');
    return steps;
  }

  /// Whether running these lines may end the script or the loop around them: a `stop`, a `determine` that is not
  /// passive, a `queue` command, an `inject` (its task may stop), a `ratelimit`, or a loop's stop or next that no loop
  /// inside these lines takes. [loops] are the loop kinds already inside.
  static bool _stops(List<DenizenItem> items, Set<String> loops) {
    for (final DenizenItem item in items) {
      final DenizenCommand command = denizenCommand(item.text);
      final String name = command.name;
      switch (name) {
        case 'stop':
        case 'queue':
        case 'inject':
        case 'ratelimit':
          return true;
        case 'determine':
          if (!command.args.any((DenizenArg arg) => arg.isWord('passively'))) return true;
        case 'foreach':
        case 'repeat':
        case 'while':
          if (item.children == null) {
            if (!loops.contains(name)) return true;
            continue;
          }
          if (_stops(item.children!.items, <String>{...loops, name})) return true;
          continue;
        default:
      }
      if (item.children != null && _stops(item.children!.items, loops)) return true;
    }
    return false;
  }

  void preserveTree(_Scope scope, DenizenItem item, String reason) {
    converter.outcome(scope, item, 'command', denizenCommand(item.text).name, false, reason);
    if (item.children != null) preserveItems(scope, item.children!.items, 0, reason.startsWith('inside ') ? reason : 'inside a block that is kept: $reason');
  }

  void preserveItems(_Scope scope, List<DenizenItem> items, int from, String reason) {
    for (int index = from; index < items.length; index++) {
      preserveTree(scope, items[index], reason);
    }
  }

  /// The `consume` condition of an NPC click event, or the `cancel` condition of a cancellable server event: the event's
  /// switch guards, the top-level `if ...: stop` guards and the top-level `ratelimit` windows before a top-level
  /// `determine cancelled`, all of which hold when the event reaches it. A rate-limited event is therefore left alone, as
  /// in Denizen, where the script stops before it cancels. Other lines in between run without deciding anything. Null
  /// when the event never cancels or when something before the cancel cannot be decided as the event happens: a block,
  /// a wait, or a guard that reads definitions, runs a procedure or checks a permission.
  _Consume? consume(List<DenizenItem> items, List<_Cond> guards, _Scope scope) {
    final List<String> parts = <String>[];
    for (final _Cond guard in guards) {
      if (_capabilities(guard)) return null;
      parts.add(_render(guard));
    }
    for (int index = 0; index < items.length; index++) {
      final DenizenItem item = items[index];
      final DenizenCommand command = denizenCommand(item.text);
      if (command.name == 'determine') {
        bool cancelled = false;
        for (final DenizenArg arg in command.args) {
          if (arg.isWord('cancelled')) {
            cancelled = true;
          } else if (!arg.isWord('passively')) {
            return null;
          }
        }
        if (!cancelled || item.children != null) return null;
        return _Consume(item, parts.isEmpty ? 'true' : parts.join(' && '));
      }
      if (command.name == 'wait') return null;
      if (command.name == 'ratelimit' && item.children == null) {
        final String? window = ratelimitFlag(item, command, scope);
        if (window == null) return null;
        parts.add("!has_flag('player', ${_quote(window)})");
        continue;
      }
      if (item.children == null) continue;
      if (command.name != 'if' || index + 1 < items.length && denizenCommand(items[index + 1].text).name == 'else') return null;
      final List<DenizenItem> body = item.children!.items;
      if (body.isEmpty || denizenCommand(body.last.text).name != 'stop' || body.last.children != null) return null;
      final List<String> tokens = <String>[];
      denizenSplit(item.text, tokens, <bool>[]);
      final _Scope probe = _Scope.of(_Doc(false), false, scope.container, null, -1, scope.context);
      final _Pending pending = _Pending();
      final _Result<_Cond> condition = expressions.condition(tokens.sublist(1), probe, pending);
      if (condition.failed || pending.steps.isNotEmpty || probe.doc.variables.isNotEmpty || probe.doc.routines.isNotEmpty || _capabilities(condition.value!)) {
        return null;
      }
      final _Cond stops = condition.value!;
      final String rendered = _render(stops);
      if (rendered == 'true') return null;
      if (rendered == 'false' || rendered == '!(true)') continue;
      parts.add(stops is _Not ? _render(stops.inner) : '!($rendered)');
    }
    return null;
  }

  /// `ratelimit <player> 2s`: a per-player flag that expires after the window, checked and set where the line runs.
  /// Expiry counts whole seconds.
  _Converted ratelimit(DenizenItem item, DenizenCommand command, _Scope scope) {
    if (command.args.length != 2 || command.args.first.prefix.isNotEmpty || !_playerTarget(command.args.first.value)) {
      return const _Converted.kept('ratelimit keyed on anything but the player has no Animus equivalent');
    }
    final int? ticks = denizenTicks(_raw(command.args[1]));
    if (ticks == null) return const _Converted.kept('ratelimit needs a literal duration');
    final int seconds = (ticks + 19) ~/ 20 < 1 ? 1 : (ticks + 19) ~/ 20;
    if (seconds > 31536000) return const _Converted.kept('ratelimit longer than a year');
    final String flag = ratelimitFlag(item, command, scope)!;
    final JObject guard = JObject()
      ..addString('type', 'condition')
      ..addString('expression', "has_flag('player', ${_quote(flag)})")
      ..add('then', _plain('return'));
    final JObject arguments = JObject()
      ..addString('name', flag)
      ..addString('scope', 'player')
      ..addInt('expireSeconds', seconds);
    return _Converted.of(<JObject>[guard, _action('animus:set-flag', arguments)]);
  }

  /// The flag of a `ratelimit <player> <duration>` line that converts; null for any other ratelimit.
  String? ratelimitFlag(DenizenItem item, DenizenCommand command, _Scope scope) {
    if (command.args.length != 2 || command.args.first.prefix.isNotEmpty || !_playerTarget(command.args.first.value)) return null;
    final int? ticks = denizenTicks(_raw(command.args[1]));
    if (ticks == null || ((ticks + 19) ~/ 20 < 1 ? 1 : (ticks + 19) ~/ 20) > 31536000) return null;
    return converter.ratelimitFlag(scope.container, item.line);
  }

  /// `determine` ends a task or procedure with its value; elsewhere it would change an event, so it is kept and the
  /// routine still ends.
  List<JObject> determine(DenizenItem item, DenizenCommand command, _Scope scope) {
    final JObject node = _plain('return');
    if (!<String>['task', 'procedure'].contains(scope.container.type)) {
      _hold(scope, item);
      converter.outcome(
          scope,
          item,
          'command',
          'determine',
          false,
          scope.cancellable
              ? 'only a top-level determine cancelled after plain guards and rate limits cancels the event in Animus; this one is not reached that way; the routine still ends here'
              : "determine changes the event's outcome, and Animus cannot cancel or change this event; the routine still ends here");
      return <JObject>[node];
    }
    if (command.args.length > 1) {
      _hold(scope, item);
      converter.outcome(scope, item, 'command', 'determine', false, 'determine takes one value');
      return <JObject>[node];
    }
    final _Pending pending = _Pending();
    if (command.args.length == 1) {
      final _Result<JElement> value = typedOperand(_raw(command.args.first), scope, pending, (String kind) {
        if (scope.container.type == 'procedure') converter.returned(scope.container, kind);
      });
      if (value.failed) {
        _hold(scope, item);
        converter.outcome(scope, item, 'command', 'determine', false, value.reason!);
        return <JObject>[node];
      }
      node.add('value', value.value);
    }
    converter.outcome(scope, item, 'command', 'determine', true, '');
    return <JObject>[...pending.steps, node];
  }

  /// [_Expressions.operand], telling [kind] what a single tag gives, or `a` for anything else, so a definition or a
  /// procedure keeps holding NPCs.
  _Result<JElement> typedOperand(String raw, _Scope scope, _Pending pending, void Function(String kind) kind) {
    final List<DenizenSegment> segments = denizenSegments(raw);
    if (segments.length == 1 && segments.first.tag && _literalTag(denizenTag(segments.first.text), false) == null) {
      final _Result<_Expr> value = expressions.value(raw, scope, pending);
      if (value.failed) return _Result<JElement>.fail(value.reason!);
      if (value.value!.text.length > _maximumExpression) return const _Result<JElement>.fail('expression longer than $_maximumExpression characters');
      kind(value.value!.kind);
      return _Result<JElement>.ok(JObject()..addString('expression', value.value!.text));
    }
    kind('a');
    return expressions.operand(raw, scope, pending);
  }

  _Converted waitFor(DenizenCommand command, _Scope scope, _Pending pending) {
    if (command.args.length > 1 || command.args.length == 1 && command.args.first.prefix.isNotEmpty) return const _Converted.kept('wait argument has no Animus equivalent');
    int? ticks = command.args.isEmpty ? 60 : denizenTicks(command.args.first.value);
    final _Result<String> computed = ticks == null ? this.ticks(command.args.first.value, scope, pending) : const _Result<String>.ok('');
    if (computed.failed) return _Converted.kept('wait ${computed.reason}');
    if (_full('[0-9]{1,8}', computed.value!)) ticks = int.parse(computed.value!);
    if (ticks == null) {
      return _Converted.of(<JObject>[
        _plain('wait')..add('ticks', JObject()..addString('expression', 'clamp(${computed.value}, 0, 72000)')),
      ]);
    }
    if (ticks == 0) return _Converted.of(<JObject>[]);
    if (ticks > 72000) return const _Converted.kept('wait longer than an hour');
    return _Converted.of(<JObject>[_plain('wait')..addInt('ticks', ticks)]);
  }

  static const Map<String, int> _units = <String, int>{'t': 1, 's': 20, 'm': 1200, 'h': 72000, 'd': 1728000};

  /// A duration that is not written out, as an expression of ticks: a tag with a unit after it (`<[delay]>s`), a tag
  /// giving a number of seconds, or a data value holding a written duration. Otherwise the reason.
  _Result<String> ticks(String raw, _Scope scope, _Pending pending) {
    String value = javaTrim(raw);
    int factor = 20;
    if (value.length > 1 && _units.containsKey(value[value.length - 1].toLowerCase()) && value[value.length - 2] == '>') {
      factor = _units[value[value.length - 1].toLowerCase()]!;
      value = value.substring(0, value.length - 1);
    }
    if (!value.startsWith('<') || denizenTagEnd(value, 0) != value.length - 1) return const _Result<String>.fail('needs a literal duration');
    final _Result<_Expr> expr = expressions.value(value, scope, pending);
    if (expr.failed) return _Result<String>.fail(expr.reason!);
    final String text = expr.value!.text;
    if (expr.value!.kind == 's' && _full(r"'[^'\\]*'", text)) {
      final int? written = denizenTicks(text.substring(1, text.length - 1));
      if (written == null || javaTrim(raw).length != value.length) return const _Result<String>.fail('needs a literal duration');
      return _Result<String>.ok('$written');
    }
    // A value without a unit after it is a duration as Denizen reads one: seconds, or text such as 5m.
    if (javaTrim(raw).length == value.length && expr.value!.kind != 'n' && expr.value!.kind != 'd') return _Result<String>.ok('round(${_seconds(expr.value!)} * 20)');
    return _Result<String>.ok('round(${_numeric(expr.value!)} * $factor)');
  }

  // ---- Chains ----

  /// An if/else-if/else chain or a choose block as nested condition nodes.
  List<JObject>? chain(List<DenizenItem> items, int start, int end, _Scope scope) {
    final DenizenItem head = items[start];
    final DenizenCommand command = denizenCommand(head.text);
    final List<_Cond> conditions = <_Cond>[];
    final List<DenizenItem> bodies = <DenizenItem>[];
    DenizenItem? otherwise;
    final _Pending pending = _Pending();
    String? reason;
    if (command.name == 'choose') {
      final _Result<String> subject =
          command.args.length == 1 ? choice(_raw(command.args.first), scope, pending) : const _Result<String>.fail('choose takes one value');
      if (subject.failed) {
        reason = subject.reason;
      } else if (head.children == null) {
        reason = 'choose without cases';
      } else {
        for (final DenizenItem option in head.children!.items) {
          final DenizenCommand when = denizenCommand(option.text);
          if (option.children == null) {
            reason = 'case without commands';
            break;
          }
          if (when.name == 'default' && when.args.isEmpty) {
            otherwise = option;
            continue;
          }
          if (when.name != 'case' || when.args.isEmpty) {
            reason = 'choose holds a line that is not case or default';
            break;
          }
          _Cond? any;
          for (final DenizenArg value in when.args) {
            final _Result<_Expr> expr = expressions.value(_raw(value), scope, pending);
            if (expr.failed) {
              reason = expr.reason;
              break;
            }
            final _Cond test = _Atom('same(${subject.value}, ${expr.value!.text})');
            any = any == null ? test : _Or(any, test);
          }
          if (reason != null) break;
          conditions.add(any!);
          bodies.add(option);
        }
      }
    } else {
      for (int index = start; index < end; index++) {
        final DenizenItem branch = items[index];
        final List<String> tokens = <String>[];
        denizenSplit(branch.text, tokens, <bool>[]);
        final bool isElse = index > start;
        if (branch.children == null) {
          reason = 'if without a block';
          break;
        }
        if (isElse && tokens.length == 1) {
          if (index != end - 1) {
            reason = 'else before else if';
            break;
          }
          otherwise = branch;
          continue;
        }
        if (isElse && tokens[1].toLowerCase() != 'if') {
          reason = 'unknown else form';
          break;
        }
        final _Result<_Cond> condition = expressions.condition(tokens.sublist(isElse ? 2 : 1), scope, pending);
        if (condition.failed) {
          reason = condition.reason;
          break;
        }
        conditions.add(condition.value!);
        bodies.add(branch);
      }
    }
    if (reason != null || conditions.isEmpty) {
      final String why = reason ?? 'nothing to choose between';
      for (int index = start; index < end; index++) {
        preserveTree(scope, items[index], why);
      }
      return null;
    }
    for (int index = start; index < end; index++) {
      converter.outcome(scope, items[index], 'command', denizenCommand(items[index].text).name, true, '');
    }
    if (command.name == 'choose') {
      for (final DenizenItem option in head.children!.items) {
        converter.outcome(scope, option, 'command', denizenCommand(option.text).name, true, '');
      }
    }
    final List<JObject> blocks = <JObject>[];
    for (final DenizenItem body in bodies) {
      blocks.add(_sequence(block(body.children!.items, 0, scope)));
    }
    JObject? tail = otherwise == null ? null : _sequence(block(otherwise.children!.items, 0, scope));
    for (int index = conditions.length - 1; index >= 0; index--) {
      tail = _build(conditions[index], blocks[index], tail);
    }
    return <JObject>[...pending.steps, tail!];
  }

  /// A `random` block: one of its lines, each with any block it opens, picked evenly as the block runs, by a number
  /// drawn into a temporary and a condition chain on it.
  List<JObject> random(DenizenItem item, _Scope scope) {
    final List<DenizenItem> options = item.children!.items;
    converter.outcome(scope, item, 'command', 'random', true, '');
    final String drawn = converter.temporary(scope);
    final JObject set = _plain('set')
      ..addString('variable', drawn)
      ..add('value', JObject()..addString('expression', 'random(1, ${options.length})'));
    JObject? chain;
    final List<JObject> bodies = <JObject>[];
    for (final DenizenItem option in options) {
      bodies.add(_sequence(block(<DenizenItem>[option], 0, scope)));
    }
    for (int index = options.length - 1; index >= 0; index--) {
      chain = _build(_Atom('($drawn == ${index + 1})'), bodies[index], chain);
    }
    return <JObject>[set, chain!];
  }

  /// The value a choose compares: a variable as it is, anything else read once into a temporary.
  _Result<String> choice(String raw, _Scope scope, _Pending pending) {
    final _Result<_Expr> subject = expressions.value(raw, scope, pending);
    if (subject.failed) return _Result<String>.fail(subject.reason!);
    if (_full('[a-z][a-z0-9_]*', subject.value!.text)) return _Result<String>.ok(subject.value!.text);
    final String temporary = converter.temporary(scope);
    pending.steps.add(_plain('set')
      ..addString('variable', temporary)
      ..add('value', JObject()..addString('expression', subject.value!.text)));
    return _Result<String>.ok(temporary);
  }

  // ---- Loops ----

  List<JObject>? loop(DenizenItem item, DenizenCommand command, _Scope scope) {
    final _Pending pending = _Pending();
    final String kind = command.name;
    final SplayTreeMap<String, String> definitions = SplayTreeMap<String, String>.of(scope.definitions);
    final JObject node = _plain(kind);
    String? reason;
    switch (kind) {
      case 'foreach':
        String as = 'value';
        String key = 'key';
        String? list;
        bool keyed = false;
        for (final DenizenArg arg in command.args) {
          if (arg.prefix == 'as') {
            as = arg.value.toLowerCase();
          } else if (arg.prefix == 'key') {
            key = arg.value.toLowerCase();
            keyed = true;
          } else if (list == null) {
            list = _raw(arg);
          } else {
            reason = 'foreach argument ${_raw(arg)} has no Animus equivalent';
            break;
          }
        }
        if (reason != null) break;
        if (list == null) {
          reason = 'foreach without a list';
          break;
        }
        final _Result<_Expr> source =
            list.contains('<') && denizenSegments(list).length == 1 ? expressions.value(list, scope, pending) : literalList(list, scope, pending);
        if (source.failed) {
          reason = source.reason;
          break;
        }
        node.add('list', JObject()..addString('expression', source.value!.text));
        final String variable = _definitionVariable(as);
        converter.declare(scope, variable, 'any');
        node.addString('as', variable);
        definitions
          ..remove(as)
          ..remove(key);
        // Each item of a list of NPCs is an NPC, so its attributes read NPC facts and flags.
        if (source.value!.kind == 'N') {
          definitions[as] = '@npc:$variable';
          node.addInt('maximum', 1024);
        }
        // Each item of an inventory is an item, whose attributes read its material, amount and name.
        if (source.value!.kind == 'I') definitions[as] = '@item:$variable';
        if (_mentionsBody(item, '[loop_index]')) {
          final String index = converter.loopIndex(scope);
          node.addString('index', index);
          definitions['loop_index'] = '($index + 1)';
        }
        if (keyed || _mentionsBody(item, '[$key]') || _mentionsBody(item, '[$key.')) {
          final String keyVariable = _definitionVariable(key);
          converter.declare(scope, keyVariable, 'any');
          node.addString('key', keyVariable);
        }
      case 'repeat':
        String as = 'value';
        String? count;
        for (final DenizenArg arg in command.args) {
          if (arg.prefix == 'as') {
            as = arg.value.toLowerCase();
          } else if (arg.prefix.isEmpty && count == null) {
            count = arg.value;
          } else {
            reason = 'repeat argument ${_raw(arg)} has no Animus equivalent';
            break;
          }
        }
        if (reason != null) break;
        if (count == null) {
          reason = 'repeat without a count';
          break;
        }
        if (_full('[0-9]{1,4}', count)) {
          final int literal = int.parse(count);
          if (literal > 1024) {
            reason = 'repeat more than 1024 times';
            break;
          }
          if (literal <= 128) {
            node.addInt('count', literal);
          } else {
            node
              ..add('count', JObject()..addString('expression', literal.toString()))
              ..addInt('maximum', literal);
          }
        } else {
          final _Result<_Expr> expr = expressions.value(count, scope, pending);
          if (expr.failed) {
            reason = expr.reason;
            break;
          }
          node
            ..add('count', JObject()..addString('expression', 'int(${expr.value!.text})'))
            ..addInt('maximum', 1024);
        }
        definitions.remove(as);
        if (_mentionsBody(item, '[$as]') || _mentionsBody(item, '[loop_index]')) {
          final String index = converter.loopIndex(scope);
          node.addString('index', index);
          definitions[as] = '($index + 1)';
          definitions['loop_index'] = '($index + 1)';
        }
      default:
        if (_mentionsBody(item, '[loop_index]')) {
          reason = 'while gives no pass number for <[loop_index]>';
          break;
        }
        final List<String> tokens = <String>[];
        denizenSplit(item.text, tokens, <bool>[]);
        final _Result<_Cond> condition = expressions.condition(tokens.sublist(1), scope, pending);
        if (condition.failed) {
          reason = condition.reason;
          break;
        }
        if (_capabilities(condition.value!)) {
          reason = 'a while condition cannot check permissions; it must be one expression';
          break;
        }
        if (pending.steps.isNotEmpty) {
          reason = 'a while condition with procedure tags or map constants would be read only once';
          break;
        }
        node
          ..addString('expression', _render(condition.value!))
          ..addInt('maximum', 1024);
    }
    if (reason != null) {
      preserveTree(scope, item, reason);
      return null;
    }
    converter.outcome(scope, item, 'command', kind, true, '');
    final List<String> loops = <String>[...scope.loops, kind];
    final _Scope inner = scope.loop(definitions, loops);
    node.add('body', _sequence(block(item.children!.items, 0, inner)));
    return <JObject>[...pending.steps, node];
  }

  /// A literal list such as `a|b|c`, items with tags allowed.
  _Result<_Expr> literalList(String raw, _Scope scope, _Pending pending) {
    final List<String> items = <String>[];
    for (final String piece in _pipes(raw)) {
      if (piece.isEmpty) continue;
      final _Result<_Expr> item = expressions.value(piece, scope, pending);
      if (item.failed) return item;
      items.add(item.value!.text);
    }
    return _Result<_Expr>.ok(_Expr('[${items.join(', ')}]', 'l'));
  }

  /// `foreach stop`, `repeat next` and so on: break or continue the innermost loop of that kind.
  List<JObject>? control(DenizenItem item, DenizenCommand command, _Scope scope) {
    String? reason;
    final String word = command.args.length == 1 ? command.args.first.value.toLowerCase() : '';
    if (word != 'stop' && word != 'next') {
      reason = '${command.name} without a block or stop/next';
    } else if (scope.loops.isEmpty) {
      reason = '${command.name} $word outside a converted loop';
    } else if (scope.loops.last != command.name) {
      reason = '${command.name} $word would end an outer loop; Animus breaks only the innermost';
    }
    if (reason != null) {
      preserveTree(scope, item, reason);
      return null;
    }
    converter.outcome(scope, item, 'command', command.name, true, '');
    return <JObject>[_plain(word == 'stop' ? 'break' : 'continue')];
  }

  // ---- Commands ----

  _Converted simple(DenizenCommand command, _Scope scope, _Pending pending) {
    final _Converted? fanned = fanout(command, scope, pending);
    if (fanned != null) return fanned;
    return switch (command.name) {
        'note' => _noteOf(command) != null
            ? _Converted.of(<JObject>[])
            : const _Converted.kept('only a literal cuboid or ellipsoid noted as:name has an Animus equivalent, as the bounds of area triggers'),
        'narrate' => narrate(command, scope, pending),
        // An interaction trigger already drops a player's clicks while their run of it is going, which is what engaging guards against.
        'engage' || 'disengage' => scope.server ? _Converted.kept('${command.name} needs an NPC') : _Converted.of(<JObject>[]),
        'cooldown' => _cooldown(command, scope),
        'announce' => announce(command, scope, pending),
        'chat' => chat(command, scope, pending),
        'wait' => waitFor(command, scope, pending),
        'flag' => flag(command, scope, pending),
        'define' => define(command, scope, pending),
        'zap' => zap(command, scope),
        'run' || 'inject' => call(command, scope, pending),
        'execute' => execute(command, scope, pending),
        'give' => give(command, scope, pending),
        'take' => take(command, scope, pending),
        'look' => look(command, scope, pending),
        'walk' => scope.server ? const _Converted.kept(_noNpc) : _walk(command),
        'equip' => scope.server ? const _Converted.kept(_noNpc) : equip(command),
        'trait' => scope.server ? const _Converted.kept(_noNpc) : _trait(command),
        'lookclose' => lookclose(command, scope, pending),
        'explode' => explode(command, scope, pending),
        'playsound' => sound(command, scope, pending, false),
        'animate' => animate(command, scope, pending),
        'playeffect' => _particle(command, scope, false),
        'actionbar' => actionbar(command, scope, pending),
        'title' => title(command, scope, pending),
        'teleport' => teleport(command, scope, pending),
        'adjust' => adjust(command, scope, pending),
        'bossbar' => bossbar(command, scope, pending),
        'sidebar' => sidebar(command, scope, pending),
        'inventory' => inventory(command),
        'debug' => _Converted.of(<JObject>[]),
        'trigger' => const _Converted.kept("trigger settings change only in on assignment; the NPC's saved triggers are used"),
        '' => const _Converted.kept('empty command line'),
        _ => _Converted.kept('no Animus equivalent for ${command.name}'),
      };
  }

  /// Commands that show something to players, and the actions they become, which take a `player` argument.
  static const Set<String> _fanned = <String>{'narrate', 'actionbar', 'title', 'playsound', 'playeffect'};
  static const Set<String> _playerActions = <String>{'animus:message', 'animus:actionbar', 'animus:title', 'animus:play-sound', 'animus:spawn-particle'};

  /// The definition a fan-out's player goes by inside its filters; `filter_value` is Denizen's own name in filter_tag.
  static const String _fan = '__targets';

  /// A command for players other than the triggering one (`targets:<server.online_players>`, a filtered list, a list in
  /// a flag or definition, or a sound played at `<[someone]>`): the same command for one player, run once for each
  /// listed player in a `foreach` over `players(...)`, which keeps at most 100 of them. Trailing `filter[...]`,
  /// `filter_tag[...]` and `exclude[...]` become a condition on each player.
  _Converted? fanout(DenizenCommand command, _Scope scope, _Pending pending) {
    if (!_fanned.contains(command.name)) return null;
    final List<DenizenArg> args = command.args;
    int at = -1;
    String replacement = '<player>';
    bool anchored = false;
    for (int index = 0; index < args.length && at < 0; index++) {
      final DenizenArg arg = args[index];
      final bool targets = arg.prefix == 'targets' && !_playerTarget(arg.value) && !(command.name == 'narrate' && _everyone(arg.value));
      final bool place = command.name == 'playsound' &&
          arg.prefix.isEmpty &&
          arg.value.startsWith('<') &&
          _at(arg.value) == null &&
          !arg.value.toLowerCase().contains('location') &&
          _location(arg.value) == null;
      final List<String>? held = command.name == 'playeffect' && arg.prefix == 'at' || command.name == 'playsound' && arg.prefix.isEmpty ? _heldPlace(arg.value) : null;
      if (targets || place) {
        at = index;
      } else if (held != null) {
        at = index;
        replacement = command.name == 'playsound' ? '<player>' : '<player${held[1]}>';
        // A particle, or a sound at a location, placed at a held player is still for the players the command
        // names, or everyone near when it names none, as in Denizen; only targets naming the same players make it theirs.
        final String holder = held[0];
        anchored = (command.name == 'playeffect' || held[1].isNotEmpty) &&
            !args.any((DenizenArg other) => other.prefix == 'targets' && other.value.toLowerCase() == holder.toLowerCase());
      }
    }
    if (at < 0) return null;
    final String value = _heldPlace(args[at].value)?[0] ?? args[at].value;
    final List<DenizenPart> filters = <DenizenPart>[];
    final List<DenizenSegment> segments = denizenSegments(value);
    _Result<_Expr>? list;
    if (segments.length == 1 && segments.first.tag) {
      final DenizenTag tag = denizenTag(segments.first.text);
      final List<DenizenPart> parts = List<DenizenPart>.of(tag.parts);
      while (tag.fallback == null &&
          parts.length > 1 &&
          parts.last.parameter != null &&
          <String>['filter', 'filter_tag', 'exclude'].contains(parts.last.name.toLowerCase())) {
        filters.insert(0, parts.removeLast());
      }
      final List<String> written = <String>[
        for (final DenizenPart part in parts) part.name + (part.parameter == null ? '' : '[${part.parameter}]'),
      ];
      list = expressions.value('<${written.join('.')}${tag.fallback == null ? '' : '||${tag.fallback}'}>', scope, pending);
    } else {
      final List<String> items = <String>[];
      for (final String piece in _pipes(value)) {
        if (piece.isEmpty) continue;
        final _Result<_Expr> item = expressions.value(piece, scope, pending);
        if (item.failed) {
          list = item;
          break;
        }
        items.add(item.value!.text);
      }
      list ??= _Result<_Expr>.ok(_Expr('[${items.join(', ')}]', 'l'));
    }
    if (list.failed) return _Converted.kept('targets ${list.reason}');
    if (list.value!.kind == 'e' || list.value!.kind == 'N') {
      if (_heldPlace(args[at].value) == null) return _Converted.kept('${command.name} for NPCs has no viewer; only players see particles and hear sounds');
      return atNpcs(command, at, list.value!, scope, pending);
    }
    final String variable = converter.temporary(scope);
    final SplayTreeMap<String, String> definitions = SplayTreeMap<String, String>.of(scope.definitions);
    definitions[_fan] = '@$variable';
    definitions['filter_value'] = '@$variable';
    final _Scope inner = scope.loop(definitions, scope.loops);
    final _Pending body = _Pending();
    final List<String> conditions = <String>[];
    for (final DenizenPart filter in filters) {
      final String name = filter.name.toLowerCase();
      final String parameter = filter.parameter!;
      final _Result<_Expr> test = expressions.value(name == 'filter' ? '<[$_fan].$parameter>' : parameter, inner, body);
      if (test.failed) return _Converted.kept('targets $name: ${test.reason}');
      conditions.add(name == 'exclude' ? '!contains(players(${test.value!.text}), $variable)' : _truth(test.value!));
    }
    final List<DenizenArg> replaced = List<DenizenArg>.of(args);
    replaced[at] = DenizenArg(args[at].prefix, replacement, false);
    // Shown to the same players it is shown at: the player of each pass.
    for (int index = 0; index < args.length; index++) {
      if (index != at && args[index].prefix == 'targets' && args[index].value.toLowerCase() == value.toLowerCase()) replaced[index] = const DenizenArg('targets', '<player>', false);
    }
    final _Converted single = simple(DenizenCommand(command.name, replaced), inner, body);
    if (single.reason.isNotEmpty) return single;
    final List<JObject> actions = <JObject>[];
    for (final JObject step in single.steps) {
      if (!_gatherPlayerActions(step, actions, anchored ? 1 : 0, false)) return _Converted.kept('${command.name} for other players has no Animus equivalent');
    }
    final bool named = replaced.any((DenizenArg arg) => arg.prefix == 'targets');
    for (final JObject step in actions) {
      final String argument = anchored ? 'atPlayer' : 'player';
      step.object('arguments')!.add(argument, JNull.instance);
      if (anchored && !named) step.object('arguments')!.addString('audience', 'nearby');
      final JObject filled = step.object('expressions') ?? JObject();
      filled.addString('/$argument', variable);
      step.add('expressions', filled);
    }
    final List<JObject> steps = <JObject>[...body.steps, ...single.steps];
    JObject each = _sequence(steps);
    if (conditions.isNotEmpty) each = _build(_Atom(conditions.join(' && ')), each, null);
    final JObject loop = _plain('foreach')
      ..add('list', JObject()..addString('expression', 'players(${list.value!.text})'))
      ..addString('as', variable)
      ..addInt('maximum', 100)
      ..add('body', each);
    return _Converted.of(<JObject>[loop]);
  }

  /// A particle or sound placed at an NPC held in a definition, or at each NPC of a list: the action at the NPC with the
  /// `npc` argument, for the triggering player with `targets:<player>` or for everyone near.
  _Converted atNpcs(DenizenCommand command, int at, _Expr npcs, _Scope scope, _Pending pending) {
    final List<DenizenArg> args = command.args;
    for (final DenizenArg arg in args) {
      if (arg.prefix == 'targets' && !_playerTarget(arg.value)) return _Converted.kept('${command.name} for NPCs has no viewer; only players see particles and hear sounds');
    }
    final List<String> held = _heldPlace(args[at].value)!;
    final List<DenizenArg> replaced = List<DenizenArg>.of(args);
    replaced[at] = DenizenArg(args[at].prefix, command.name == 'playsound' ? '<npc>' : '<npc${held[1]}>', false);
    final DenizenCommand placed = DenizenCommand(command.name, replaced);
    final _Converted single = command.name == 'playeffect' ? _particle(placed, scope, true) : sound(placed, scope, pending, true);
    if (single.reason.isNotEmpty) return single;
    final JObject node = single.steps.first;
    final String each = npcs.kind == 'e' ? npcs.text : converter.temporary(scope);
    node.object('arguments')!.add('npc', JNull.instance);
    final JObject filled = node.object('expressions') ?? JObject();
    filled.addString('/npc', each);
    node.add('expressions', filled);
    if (npcs.kind == 'e') return _Converted.of(<JObject>[node]);
    return _Converted.of(<JObject>[
      _plain('foreach')
        ..add('list', JObject()..addString('expression', npcs.text))
        ..addString('as', each)
        ..addInt('maximum', 1024)
        ..add('body', node),
    ]);
  }

  /// Gathers the player actions of a converted command into [actions], through at most [loops] fan-outs over the players
  /// it is for and the filters inside them; false when anything else is there.
  static bool _gatherPlayerActions(JObject node, List<JObject> actions, int loops, bool looped) {
    switch (node.get('type')!.asPrimitive.asString) {
      case 'action':
        if (!_playerActions.contains(node.get('capability')!.asPrimitive.asString)) return false;
        actions.add(node);
        return true;
      case 'set':
        return true;
      case 'sequence':
        for (final JElement step in (node.get('steps')! as JArray).elements) {
          if (!_gatherPlayerActions(step as JObject, actions, loops, looped)) return false;
        }
        return true;
      case 'condition':
        return looped && !node.has('else') && _gatherPlayerActions(node.object('then')!, actions, loops, true);
      case 'foreach':
        return loops > 0 && _gatherPlayerActions(node.object('body')!, actions, loops - 1, true);
      default:
        return false;
    }
  }

  /// Text of an argument with a length limit on the literal and the template.
  _Result<_Text> text(String raw, _Scope scope, _Pending pending, bool command, int limit) {
    final _Result<_Text> text = expressions.text(raw, scope, pending, command, 0);
    if (text.failed) return text;
    final _Text value = text.value!;
    if (value.isDynamic ? value.template.length > _maximumExpression : value.literal!.length > limit) {
      return _Result<_Text>.fail('text longer than ${value.isDynamic ? _maximumExpression : limit} characters');
    }
    return text;
  }

  _Converted narrate(DenizenCommand command, _Scope scope, _Pending pending) {
    final List<String> words = <String>[];
    String to = 'trigger';
    for (final DenizenArg arg in command.args) {
      if (arg.prefix.isEmpty) {
        if (!arg.isWord('per_player')) words.add(arg.value);
        continue;
      }
      if (arg.prefix == 'targets' && _playerTarget(arg.value)) continue;
      if (arg.prefix == 'targets' && _everyone(arg.value)) {
        to = 'all';
        continue;
      }
      if (arg.prefix == 'targets') return const _Converted.kept('narrate targets other than the player or every online player have no Animus equivalent');
      return _Converted.kept('narrate argument ${arg.prefix}: has no Animus equivalent');
    }
    return message(words, to, scope, pending);
  }

  _Converted message(List<String> words, String to, _Scope scope, _Pending pending) {
    if (words.isEmpty) return const _Converted.kept('nothing to say');
    final _Result<_Text> text = this.text(words.join(' '), scope, pending, false, _maximumText);
    if (text.failed) return _Converted.kept(text.reason!);
    final _Action action = _Action();
    // A blank narrate is an empty chat line; a lone reset code sends one.
    if (!text.value!.isDynamic && javaIsBlank(text.value!.literal!)) {
      action.put('text', const JString('&r'));
    } else {
      action.text('text', text.value!);
    }
    if (to != 'trigger') action.put('to', JString(to));
    return _Converted.of(<JObject>[action.node('animus:message')]);
  }

  _Converted announce(DenizenCommand command, _Scope scope, _Pending pending) {
    final List<String> words = <String>[];
    String to = 'all';
    for (final DenizenArg arg in command.args) {
      if (arg.isWord('to_console')) {
        to = 'console';
        continue;
      }
      if (arg.prefix.isEmpty && !arg.quoted && <String>['to_ops', 'to_flagged', 'to_permission'].contains(arg.value.toLowerCase())) {
        return _Converted.kept('announce ${arg.value} has no Animus audience');
      }
      if (arg.prefix.isNotEmpty) return _Converted.kept('announce argument ${arg.prefix}: has no Animus equivalent');
      words.add(arg.value);
    }
    return message(words, to, scope, pending);
  }

  _Converted chat(DenizenCommand command, _Scope scope, _Pending pending) {
    if (scope.server) return const _Converted.kept('chat makes the NPC speak; $_noNpc');
    final List<String> words = <String>[];
    bool trigger = false;
    for (final DenizenArg arg in command.args) {
      if (arg.prefix.isEmpty) {
        words.add(arg.value);
        continue;
      }
      if (arg.prefix == 'targets' && _playerTarget(arg.value)) {
        trigger = true;
        continue;
      }
      if (arg.prefix == 'talkers' && _npcTarget(arg.value)) continue;
      return _Converted.kept('chat argument ${arg.prefix}: has no Animus equivalent');
    }
    if (words.isEmpty) return const _Converted.kept('nothing to say');
    final _Result<_Text> text = this.text(words.join(' '), scope, pending, false, _maximumText);
    if (text.failed) return _Converted.kept(text.reason!);
    final _Action action = _Action()..text('text', text.value!);
    if (!trigger) action.put('audience', const JString('nearby'));
    return _Converted.of(<JObject>[action.node('animus:say')]);
  }

  /// The new value of a Denizen data action (`++`, `+:n`, `->:v`, ...) on the current value. A null value when it is
  /// a plain value.
  _Result<String> dataAction(String action, String current, String currentNumber, _Scope scope, _Pending pending) {
    if (action == '++') return _Result<String>.ok('($currentNumber + 1)');
    if (action == '--') return _Result<String>.ok('($currentNumber - 1)');
    for (final String operator in <String>['+:', '-:', '*:', '/:']) {
      if (!action.startsWith(operator)) continue;
      final _Result<_Expr> amount = expressions.value(action.substring(2), scope, pending);
      if (amount.failed) return _Result<String>.fail(amount.reason!);
      return _Result<String>.ok('($currentNumber ${operator[0]} ${_numeric(amount.value!)})');
    }
    if (action.startsWith('->:')) {
      final _Result<_Expr> item = expressions.value(action.substring(3), scope, pending);
      if (item.failed) return _Result<String>.fail(item.reason!);
      return _Result<String>.ok('append($current, ${item.value!.text})');
    }
    if (action.startsWith('<-:')) {
      final _Result<_Expr> item = expressions.value(action.substring(3), scope, pending);
      if (item.failed) return _Result<String>.fail(item.reason!);
      final String found = 'index_of($current, ${item.value!.text})';
      return _Result<String>.ok('($found >= 0 ? remove($current, $found) : $current)');
    }
    if (action.startsWith('|:')) {
      String list = current;
      for (final String piece in _pipes(action.substring(2))) {
        if (piece.isEmpty) continue;
        if (piece.contains('<')) return const _Result<String>.fail('adding a computed list with |: has no Animus expression; list items must be written out');
        list = 'append($list, ${_literal(piece).text})';
      }
      return _Result<String>.ok(list);
    }
    return const _Result<String>.ok(null);
  }

  /// `flag player|npc|server name:value`, and a flag on players held in a definition or listed online
  /// (`flag <[target]> ...`, `flag <server.online_players> ...`): the same flag set for each player in a `foreach` over
  /// `players(...)`, which holds one player or a list and skips anything that is no player.
  _Converted flag(DenizenCommand command, _Scope scope, _Pending pending) {
    if (command.args.length < 2) return const _Converted.kept('flag needs a target and a name');
    final DenizenArg first = command.args.first;
    final String target = first.value.toLowerCase().replaceAll('<', '').replaceAll('>', '');
    if (first.prefix.isNotEmpty) return const _Converted.kept('flags on other objects have no Animus equivalent');
    if (<String>['player', 'npc', 'server'].contains(target)) {
      if (target == 'npc' && scope.server) return const _Converted.kept('flag npc needs an NPC; $_noNpc');
      return flagOf(command, _scopeOf(target), null, scope, pending);
    }
    if (!_full(r'\[[a-z0-9_]+]|server\.online_players', target)) return const _Converted.kept('flags on other objects have no Animus equivalent');
    if (target.startsWith('[') && (scope.doc.kinds[target.substring(1, target.length - 1)] ?? 'a') == _unconverted) {
      return _Converted.kept('flag on definition ${target.substring(1, target.length - 1)}, whose value did not convert');
    }
    final _Result<_Expr> who = expressions.value(first.value, scope, pending);
    if (who.failed) return _Converted.kept(who.reason!);
    if (who.value!.kind == 'L') return locationFlag(command, who.value!.text, scope, pending);
    if (who.value!.kind == 'e') return flagOf(command, 'npc', who.value!.text, scope, pending);
    final String each = converter.temporary(scope);
    final bool npcs = who.value!.kind == 'N';
    final _Converted single = flagOf(command, npcs ? 'npc' : 'player', each, scope, pending);
    if (single.reason.isNotEmpty) return single;
    final JObject loop = _plain('foreach')
      ..add('list', JObject()..addString('expression', npcs ? who.value!.text : 'players(${who.value!.text})'))
      ..addString('as', each)
      ..addInt('maximum', npcs ? 1024 : 100)
      ..add('body', _sequence(single.steps));
    return _Converted.of(<JObject>[loop]);
  }

  /// A flag on a location held in a definition: Denizen keeps it on the block there, and Animus in a global flag named
  /// `locations-<name>` that maps each block, written `x,y,z,world`, to its value. Expiry has no per-block form.
  _Converted locationFlag(DenizenCommand command, String location, _Scope scope, _Pending pending) {
    if (command.args.length > 2) return const _Converted.kept('a flag on a location takes no expire');
    final String rawName = _raw(command.args[1]);
    final int colon = rawName.indexOf(':');
    final String denizen = (colon < 0 ? rawName : rawName.substring(0, colon)).toLowerCase();
    final String value = colon < 0 ? '' : rawName.substring(colon + 1);
    final _Result<String> name = expressions.locationFlagName(denizen, scope, pending);
    if (name.failed) return _Converted.kept(name.reason!);
    final String key = _blockKey(location);
    final String blocks = expressions.locationBlocks(name.value!, scope, pending);
    final String written;
    if (colon >= 0 && value == '!') {
      written = 'remove($blocks, $key)';
    } else {
      final String held = 'get($blocks, $key, ';
      final _Result<String> computed = colon < 0 ? const _Result<String>.ok(null) : dataAction(value, '$held[])', 'num(${held}0))', scope, pending);
      if (computed.failed) return _Converted.kept(computed.reason!);
      String? stored = computed.value;
      if (stored == null && colon < 0) stored = 'true';
      if (stored == null) {
        final _Result<_Expr> operand = expressions.value(value, scope, pending);
        if (operand.failed) return _Converted.kept(operand.reason!);
        stored = operand.value!.text;
      }
      written = 'put($blocks, $key, $stored)';
    }
    if (written.length > _maximumExpression) return const _Converted.kept('expression longer than 4096 characters');
    final _Action action = _Action();
    action.expression('name', name.value!);
    action.put('scope', const JString('global'));
    action.expression('value', written);
    return _Converted.of(<JObject>[action.node('animus:set-flag')]);
  }

  /// One flag write of [scopeName]; [other] holds another player, or NPC for an NPC flag, whose flag it is.
  _Converted flagOf(DenizenCommand command, String scopeName, String? other, _Scope scope, _Pending pending) {
    final String argument = scopeName == 'npc' ? 'npc' : 'player';
    int? expire;
    String? computedExpire;
    for (int index = 2; index < command.args.length; index++) {
      final DenizenArg arg = command.args[index];
      if (arg.prefix != 'expire' && arg.prefix != 'duration') return _Converted.kept('flag argument ${_raw(arg)} has no Animus equivalent');
      int? ticks = denizenTicks(arg.value);
      final _Result<String> computed = ticks == null ? this.ticks(arg.value, scope, pending) : const _Result<String>.ok('');
      if (computed.failed) return _Converted.kept('flag expire ${computed.reason}');
      if (_full('[0-9]{1,8}', computed.value!)) ticks = int.parse(computed.value!);
      if (ticks == null) {
        computedExpire = 'clamp(ceil(${computed.value} / 20), 1, 31536000)';
        continue;
      }
      final int seconds = (ticks + 19) ~/ 20 > 1 ? (ticks + 19) ~/ 20 : 1;
      if (seconds > 31536000) return const _Converted.kept('flag expire longer than a year');
      expire = seconds;
    }
    final String rawName = _raw(command.args[1]);
    final int colon = rawName.indexOf(':');
    final String denizen = (colon < 0 ? rawName : rawName.substring(0, colon)).toLowerCase();
    final String value = colon < 0 ? '' : rawName.substring(colon + 1);
    // A name joined with tags, such as npc_done_<npc.id>, is computed as the run writes it, like the reads of it.
    final _Result<String> named = denizenSegments(denizen).any((DenizenSegment segment) => segment.tag)
        ? expressions.flagNameExpression(denizen, scope, pending)
        : const _Result<String>.fail('');
    final String? name = _flagName(denizen);
    if (named.failed && (denizen.contains('<') || denizen.contains('[') || name == null)) {
      return _Converted.kept('flag name $denizen cannot be an Animus flag name');
    }
    final String nameExpression = named.failed ? _quote(name!) : named.value!;
    final _Action action = _Action();
    if (named.failed) {
      action.put('name', JString(name!));
    } else {
      action.expression('name', nameExpression);
    }
    action.put('scope', JString(scopeName));
    if (colon >= 0 && value == '!') {
      if (expire != null || computedExpire != null) return const _Converted.kept('clearing a flag takes no expire');
      action.put('clear', const JBool(true));
      if (other != null) action.expression(argument, other);
      return _Converted.of(<JObject>[action.node('animus:set-flag')]);
    }
    if (colon >= 0) {
      final String read = other != null ? '${argument}_flag($other, $nameExpression, ' : 'flag(${_quote(scopeName)}, $nameExpression, ';
      final _Result<String> computed = dataAction(value, '$read[])', 'num(${read}0))', scope, pending);
      if (computed.failed) return _Converted.kept(computed.reason!);
      if (computed.value != null) {
        if (computed.value!.length > _maximumExpression) return const _Converted.kept('expression longer than 4096 characters');
        action.expression('value', computed.value!);
      } else {
        final _Result<JElement> operand = expressions.operand(value, scope, pending);
        if (operand.failed) return _Converted.kept(operand.reason!);
        final JElement result = operand.value!;
        if (result is JObject && result.has('expression')) {
          action.expression('value', result.get('expression')!.asPrimitive.asString);
        } else if (result is JObject) {
          action.put('value', JNull.instance);
          action.templates.addString('/value', result.get('template')!.asPrimitive.asString);
        } else {
          action.put('value', result);
        }
      }
    }
    if (expire != null) action.put('expireSeconds', JInt.of(expire));
    if (computedExpire != null) action.expression('expireSeconds', computedExpire);
    if (other != null) action.expression(argument, other);
    return _Converted.of(<JObject>[action.node('animus:set-flag')]);
  }

  _Converted define(DenizenCommand command, _Scope scope, _Pending pending) {
    if (command.args.isEmpty) return const _Converted.kept('define without a name');
    final DenizenArg first = command.args.length == 1 ? denizenPrefixed(command.args.first) : command.args.first;
    final String name;
    final String value;
    bool isAction = false;
    if (first.prefix.isNotEmpty && command.args.length == 1) {
      name = first.prefix;
      value = first.value;
      isAction = true;
    } else {
      if (first.prefix.isNotEmpty || command.args.length != 2) return const _Converted.kept('define takes a name and one value');
      name = first.value.toLowerCase();
      value = _raw(command.args[1]);
    }
    if (name.contains('<') || name.contains('.') || name.contains('[')) return const _Converted.kept('defining a computed name or a map key has no Animus equivalent');
    final String variable = _definitionVariable(name);
    converter.declare(scope, variable, 'any');
    final JObject set = _plain('set')..addString('variable', variable);
    if (isAction) {
      if (value == '!') {
        set.addString('value', '');
        return _Converted.of(<JObject>[set]);
      }
      // An unset definition is empty text: a list action starts from an empty list and a number from zero, as in Denizen.
      final String unset = "same(text($variable), '')";
      final _Result<String> computed = dataAction(value, '($unset ? [] : $variable)', 'num(($unset ? 0 : $variable))', scope, pending);
      if (computed.failed) return _Converted.kept(computed.reason!);
      if (computed.value != null) {
        set.add('value', JObject()..addString('expression', computed.value!));
        return _Converted.of(<JObject>[set]);
      }
    }
    final _Result<JElement> operand = typedOperand(value, scope, pending, (String kind) {
      if (kind == 'e' || kind == 'N' || kind == 'L' || kind == 'i' || kind == 'I') {
        scope.doc.kinds[name] = kind;
      } else {
        scope.doc.kinds.remove(name);
      }
    });
    // A definition whose value did not convert holds nothing, so a flag on it is kept rather than set on nobody.
    if (operand.failed) {
      scope.doc.kinds[name] = _unconverted;
      return _Converted.kept(operand.reason!);
    }
    set.add('value', operand.value);
    return _Converted.of(<JObject>[set]);
  }

  _Converted zap(DenizenCommand command, _Scope scope) {
    if (scope.server) return const _Converted.kept('zap moves an interact step with an NPC; $_noNpc');
    _Interact? target = scope.interact;
    String? step;
    for (final DenizenArg arg in command.args) {
      if (arg.prefix == 'script') {
        target = converter.interacts[arg.value.toLowerCase()];
        continue;
      }
      if (arg.prefix == 'step') {
        step = arg.value;
        continue;
      }
      if (arg.prefix.isNotEmpty) return _Converted.kept('zap argument ${arg.prefix}: has no Animus equivalent');
      if (converter.interacts.containsKey(arg.value.toLowerCase()) && step == null && identical(target, scope.interact)) {
        target = converter.interacts[arg.value.toLowerCase()];
        continue;
      }
      if (step != null) return const _Converted.kept('zap with a duration has no Animus equivalent');
      step = arg.value;
    }
    if (target == null) return const _Converted.kept("zap outside an interact script needs the script's name");
    final _Interact interact = target;
    final int stage;
    if (step == null) {
      if (scope.interact == null || !identical(scope.interact, interact) || scope.step < 0 || scope.step + 1 >= interact.order.length) {
        return const _Converted.kept('zap to the next step needs a following step');
      }
      stage = interact.stage(interact.order[scope.step + 1])!;
    } else {
      if (step.contains('<')) return const _Converted.kept('zap to a computed step has no Animus equivalent');
      final int? found = interact.stage(step);
      if (found == null) return _Converted.kept('no step $step in ${interact.container.name}');
      stage = found;
    }
    return _Converted.of(<JObject>[
      _action(
          'animus:set-quest-state',
          JObject()
            ..addString('name', interact.quest)
            ..addString('scope', 'relationship')
            ..addInt('value', stage)),
    ]);
  }

  /// `run` and `inject`: a call of the task's routine, with definitions as arguments and a saved determination as its
  /// result.
  _Converted call(DenizenCommand command, _Scope scope, _Pending pending) {
    String? name;
    String? path;
    String? save;
    final List<String> positional = <String>[];
    final LinkedHashMap<String, String> named = LinkedHashMap<String, String>();
    String? npcFor, playerFor;
    for (final DenizenArg arg in command.args) {
      if (arg.prefix.isEmpty) {
        if (arg.isWord('instantly') || arg.isWord('local') || arg.isWord('locally')) continue;
        if (!arg.quoted && _definitionArgument.hasMatch(arg.value)) {
          final int colon = arg.value.indexOf(':');
          named[arg.value.substring(4, colon).toLowerCase()] = arg.value.substring(colon + 1);
          continue;
        }
        if (name != null) return _Converted.kept('argument ${arg.value} has no Animus equivalent');
        name = arg.value;
        continue;
      }
      switch (arg.prefix) {
        case 'path':
          path = arg.value;
        case 'npc':
          if (arg.value.toLowerCase() != '<npc>') npcFor = arg.value;
        case 'player':
          if (arg.value.toLowerCase() != '<player>') playerFor = arg.value;
        case 'def':
        case 'definitions':
        case 'context':
          positional.addAll(_pipes(arg.value));
        case 'defmap':
          final String map = arg.value;
          if (!map.toLowerCase().startsWith('<map[') || !map.endsWith(']>') || map.substring(5, map.length - 2).contains('<')) {
            return const _Converted.kept('defmap needs a literal <map[...]>');
          }
          for (final String entry in map.substring(5, map.length - 2).split(';')) {
            final int equals = entry.indexOf('=');
            if (equals <= 0) return const _Converted.kept('defmap entry without a key');
            named[entry.substring(0, equals).toLowerCase()] = entry.substring(equals + 1);
          }
        case 'save':
          save = arg.value.toLowerCase();
        default:
          return _Converted.kept('argument ${arg.prefix}: has no Animus equivalent');
      }
    }
    final JObject subject = JObject();
    if (playerFor != null || npcFor != null) {
      if (command.name == 'inject') return const _Converted.kept('inject runs in place for the same player and NPC');
      final String? problem = this.subject(playerFor, npcFor, subject, scope, pending);
      if (problem != null) return _Converted.kept(problem);
    }
    if (name != null && name.contains('<')) return dynamic(name, path, save, positional, named, subject, scope, pending);
    final DenizenContainer? task;
    if (name == null && path != null && scope.container.type == 'task') {
      task = scope.container;
    } else {
      if (name == null) return const _Converted.kept('running a computed script has no Animus equivalent');
      task = converter.containers[name.toLowerCase()];
    }
    if (task == null) return _Converted.kept('no script named ${name ?? ''}');
    if (command.name == 'inject' && task.type == 'task') {
      final DenizenNode? injected = task.body.get(path?.toLowerCase() ?? 'script');
      if (injected is DenizenSequence && _stops(injected.items, const <String>{})) {
        // A stop there ends this script too, so the lines run in place, sharing this script's definitions.
        if (save != null || positional.isNotEmpty || named.isNotEmpty) {
          return _Converted.kept('inject runs ${task.name} inside this script, so a stop there ends this script too; an Animus call ends only the task');
        }
        final String routine = converter.routineFor(task, path);
        if (scope.calling.contains(routine)) return const _Converted.kept('recursive inject has no Animus equivalent');
        scope.calling.add(routine);
        final _Scope inside = scope.injected(task);
        final List<JObject> steps = block(injected.items, 0, inside);
        scope.calling.remove(routine);
        return _Converted.of(steps);
      }
    }
    return callTask(task, path, save, positional, named, subject, scope, pending);
  }

  /// The player and NPC a run for someone else is for, as the call node's `player` and `npc`: a player or NPC held in a
  /// definition or read by a tag. Null when both convert, otherwise the reason.
  String? subject(String? player, String? npc, JObject subject, _Scope scope, _Pending pending) {
    if (player != null) {
      final _Result<_Expr> who = expressions.value(player, scope, pending);
      if (who.failed) return who.reason;
      if (who.value!.kind == 'e' || who.value!.kind == 'N') return 'player: names an NPC';
      subject.add('player', JObject()..addString('expression', "get(players(${who.value!.text}), 0, '')"));
    }
    if (npc != null) {
      final _Result<_Expr> which = expressions.value(npc, scope, pending);
      if (which.failed) return which.reason;
      if (which.value!.kind == 'N' || which.value!.kind == 'l') return 'npc: names a list; run the task for each NPC in a foreach';
      subject.add('npc', JObject()..addString('expression', which.value!.kind == 'e' ? which.value!.text : "replace(text(${which.value!.text}), 'n@', '')"));
    }
    return null;
  }

  /// `run <npc.flag[name]>`: the script the flag names, chosen when the line runs from the task scripts that a `flag`
  /// command or an NPC's saved flags give that flag. A value naming none of them runs nothing.
  _Converted dynamic(String raw, String? path, String? save, List<String> positional, Map<String, String> named, JObject subject, _Scope scope, _Pending pending) {
    if (!raw.startsWith('<') || !raw.endsWith('>') || denizenTagEnd(raw, 0) != raw.length - 1) {
      return const _Converted.kept('running a computed script has no Animus equivalent');
    }
    final DenizenTag tag = denizenTag(raw.substring(1, raw.length - 1));
    final String base = tag.base.toLowerCase();
    final String flagScope = base == 'npc'
        ? 'npc'
        : base == 'player'
            ? 'player'
            : base == 'server'
                ? 'global'
                : '';
    if (flagScope.isEmpty ||
        tag.parts.length != 2 ||
        tag.parts.first.parameter != null ||
        tag.parts[1].name.toLowerCase() != 'flag' ||
        tag.parts[1].parameter == null ||
        tag.parts[1].parameter!.contains('<')) {
      return const _Converted.kept('running a computed script has no Animus equivalent');
    }
    if (flagScope == 'npc' && scope.server) return const _Converted.kept(_noNpc);
    if (save != null || path != null) return const _Converted.kept('save: and path: of a script chosen by a flag have no Animus equivalent');
    final String? flag = _flagName(tag.parts[1].parameter!);
    if (flag == null) return _Converted.kept('flag name ${tag.parts[1].parameter!} cannot be an Animus flag name');
    final List<DenizenContainer> tasks = <DenizenContainer>[];
    for (final String value in converter.flagValues(flagScope, flag)) {
      final DenizenContainer? task = converter.containers[value];
      if (task != null && task.type == 'task') tasks.add(task);
    }
    if (tasks.isEmpty) return _Converted.kept('no script gives the flag $flag the name of a task script');
    final String read = "lower(text(flag(${_quote(flagScope)}, ${_quote(flag)}, '')))";
    final List<JObject> calls = <JObject>[];
    for (final DenizenContainer task in tasks) {
      final _Converted call = callTask(task, null, null, positional, named, subject, scope, pending);
      if (call.reason.isNotEmpty) return call;
      calls.add(_sequence(call.steps));
    }
    JObject? chain;
    for (int index = tasks.length - 1; index >= 0; index--) {
      final _Cond chosen = _Atom('same($read, ${_quote(tasks[index].name.toLowerCase())})');
      chain = _build(chosen, calls[index], chain);
    }
    return _Converted.of(<JObject>[chain!]);
  }

  _Converted callTask(DenizenContainer task, String? path, String? save, List<String> positional, Map<String, String> named, JObject subject, _Scope scope, _Pending pending) {
    if (task.type != 'task') return const _Converted.kept('only task scripts can become routines');
    final String key = path?.toLowerCase() ?? 'script';
    final DenizenNode? body = task.body.get(key);
    if (body is! DenizenSequence) return _Converted.kept('task ${task.name} has no $key list');
    final List<String> names = _definitionNames(task);
    if (positional.length > names.length) return _Converted.kept('task ${task.name} takes ${names.length} definitions');
    final JObject arguments = JObject();
    for (int index = 0; index < positional.length; index++) {
      final _Converted? problem = argument(arguments, names[index], positional[index], scope, pending);
      if (problem != null) return problem;
    }
    for (final MapEntry<String, String> entry in named.entries) {
      final _Converted? problem = argument(arguments, entry.key, entry.value, scope, pending);
      if (problem != null) return problem;
    }
    // A server behavior running a task for an NPC converts it again as an NPC's routine, since it then has an NPC.
    final bool asNpc = scope.server && subject.has('npc');
    String routine = converter.routineFor(task, path);
    if (asNpc) routine = '${routine.length > 60 ? routine.substring(0, 60) : routine}-npc';
    if (scope.calling.contains(routine)) return const _Converted.kept('recursive task calls have no Animus equivalent');
    final JObject call = _plain('call')..addString('routine', routine);
    if (arguments.isNotEmpty) call.add('arguments', arguments);
    for (final MapEntry<String, JElement> entry in subject.entries) {
      call.add(entry.key, entry.value.deepCopy());
    }
    if (save != null) {
      final String result = _variable('r_', save);
      converter.declare(scope, result, 'any');
      call.addString('result', result);
    }
    if (!scope.doc.routines.containsKey(routine)) {
      if (scope.doc.routines.length >= behaviorMaximumRoutines) return const _Converted.kept('more than 32 routines in one behavior');
      scope.doc.routines[routine] = JObject();
      scope.calling.add(routine);
      final List<JObject> steps = block(body.items, 0, asNpc ? scope.inContainer(task).withNpc() : scope.inContainer(task));
      scope.calling.remove(routine);
      scope.doc.routines[routine] = _sequence(steps);
    }
    if (save != null) scope.saves[save] = call.get('result')!.asPrimitive.asString;
    return _Converted.of(<JObject>[call]);
  }

  _Converted? argument(JObject arguments, String name, String raw, _Scope scope, _Pending pending) {
    final _Result<JElement> operand = expressions.operand(raw, scope, pending);
    if (operand.failed) return _Converted.kept(operand.reason!);
    final String variable = _definitionVariable(name);
    converter.declare(scope, variable, 'any');
    arguments.add(variable, operand.value);
    return null;
  }

  _Converted execute(DenizenCommand command, _Scope scope, _Pending pending) {
    String? as;
    bool operator = false;
    final List<String> words = <String>[];
    for (final DenizenArg arg in command.args) {
      if (arg.isWord('as_server')) {
        as = 'console';
        continue;
      }
      if (arg.isWord('as_player')) {
        as = 'player';
        continue;
      }
      // as_op makes the player an operator for the one line, as asOperator does.
      if (arg.isWord('as_op')) {
        as = 'player';
        operator = true;
        continue;
      }
      if (arg.isWord('as_npc')) return const _Converted.kept('commands run as the NPC have no Animus equivalent');
      if (arg.isWord('silent')) continue;
      words.add(_raw(arg));
    }
    if (as == null) return const _Converted.kept('execute needs as_server, as_op or as_player');
    String line = javaTrim(words.join(' '));
    if (line.startsWith('/')) line = line.substring(1);
    final List<String> split = line.split(RegExp(r'\s+'));
    if (split.first.toLowerCase() == 'sentinel') return _sentinel(split.sublist(1), scope, pending);
    final _Result<_Text> text = this.text(line, scope, pending, true, 1024);
    if (text.failed) return _Converted.kept(text.reason!);
    if (!text.value!.isDynamic && javaIsBlank(text.value!.literal!)) return const _Converted.kept('command is empty');
    final _Action action = _Action()..text('command', text.value!);
    if (as == 'player') action.put('as', const JString('player'));
    if (operator) action.put('asOperator', const JBool(true));
    return _Converted.of(<JObject>[action.node('animus:command')]);
  }

  /// A `/sentinel` command. Sentinel is replaced by animus:combat, so the forms that act on the NPC become
  /// combat-control on the behavior's own NPC; target types and Sentinel settings are kept with the reason.
  _Converted _sentinel(List<String> words, _Scope scope, _Pending pending) {
    if (scope.server) return const _Converted.kept('sentinel acts on an NPC, and $_noNpc');
    final List<String> rest = <String>[];
    for (int i = 0; i < words.length; i++) {
      final String word = words[i];
      String? id;
      if (word.toLowerCase() == '--id' && i + 1 < words.length) {
        id = words[++i];
      } else if (word.toLowerCase().startsWith('--id=')) {
        id = word.substring(5);
      }
      if (id == null) {
        rest.add(word);
        continue;
      }
      if (id.toLowerCase() != '<npc.id>' && id.toLowerCase() != '<npc>') {
        return const _Converted.kept("sentinel names another NPC with --id; animus:combat acts on the behavior's own NPC");
      }
    }
    final String operation = rest.isEmpty ? '' : rest.first.toLowerCase();
    final List<String> arguments = rest.isEmpty ? const <String>[] : rest.sublist(1);
    final bool player = arguments.length == 1 && _triggeringPlayer(arguments.first);
    final String? held = arguments.length == 1 ? _heldPlayer(arguments.first) : null;
    final _Action action = _Action();
    switch (operation) {
      case 'addtarget':
      case 'removetarget':
        if (!player && held == null) {
          return _Converted.kept("sentinel ${rest.join(' ')}: target types are animus:combat settings; set them in the NPC's animus:combat targets");
        }
        // Taking the player off the target list stops the attack on them; combat-control releases the current target.
        action.put('operation', JString(operation == 'addtarget' ? 'target' : 'release'));
        if (operation == 'addtarget' && player) {
          action.put('target', const JString('player'));
        } else if (operation == 'addtarget') {
          final _Result<_Expr> who = expressions.value(held!, scope, pending);
          if (who.failed) return _Converted.kept(who.reason!);
          action.expression('target', "get(players(${who.value!.text}), 0, '')");
        }
      case 'forgive':
      case 'forgiveall':
        if (arguments.isNotEmpty) return _Converted.kept('sentinel $operation with arguments has no combat action');
        action.put('operation', const JString('release'));
      case 'guard':
        if (arguments.isNotEmpty && !player) return const _Converted.kept('sentinel guard guards only the triggering player in Animus');
        action.put('operation', const JString('guard'));
        if (player) action.put('target', const JString('player'));
      case '':
        return const _Converted.kept('sentinel without a subcommand');
      default:
        return _Converted.kept("sentinel $operation has no combat action; set it in the NPC's animus:combat settings");
    }
    return _Converted.of(<JObject>[action.node('animus:combat-control')]);
  }

  /// `trait state:true|false sentinel`: Sentinel on or off becomes the NPC's combat on or off.
  _Converted _trait(DenizenCommand command) {
    String state = 'toggle';
    String? name;
    for (final DenizenArg arg in command.args) {
      if (arg.prefix == 'state') {
        state = arg.value.toLowerCase();
      } else if (arg.prefix == 'to' && _npcTarget(arg.value)) {
        continue;
      } else if (arg.prefix.isEmpty && name == null) {
        name = arg.value.toLowerCase();
      } else {
        return _Converted.kept('trait argument ${_raw(arg)} has no Animus equivalent');
      }
    }
    if (name != 'sentinel') return _Converted.kept('no Animus equivalent for the ${name ?? 'unnamed'} trait');
    if (state != 'true' && state != 'false') return const _Converted.kept('trait toggles Sentinel without saying on or off; use state:true or state:false');
    final _Action action = _Action()..put('operation', JString(state == 'true' ? 'enable' : 'disable'));
    return _Converted.of(<JObject>[action.node('animus:combat-control')]);
  }

  String? material(String value) {
    String lowered = value.toLowerCase();
    if (lowered.startsWith('minecraft:')) lowered = lowered.substring(10);
    if (!_full('[a-z0-9_]{1,64}', lowered) || converter.containers.containsKey(lowered)) return null;
    return 'minecraft:$lowered';
  }

  /// An amount: a literal from 1 to 6400, or a tag read as a whole number.
  _Converted? amount(_Action action, String value, _Scope scope, _Pending pending) {
    if (_full('[0-9]{1,4}', value)) {
      final int amount = int.parse(value);
      if (amount < 1 || amount > 6400) return const _Converted.kept('quantity must be from 1 to 6400');
      action.put('amount', JInt.of(amount));
      return null;
    }
    if (!value.contains('<')) return const _Converted.kept('quantity must be a number');
    final _Result<_Expr> expr = expressions.value(value, scope, pending);
    if (expr.failed) return _Converted.kept(expr.reason!);
    action.expression('amount', 'int(${expr.value!.text})');
    return null;
  }

  _Converted give(DenizenCommand command, _Scope scope, _Pending pending) {
    String? item;
    String? quantity;
    for (final DenizenArg arg in command.args) {
      if (arg.prefix == 'quantity') {
        quantity = arg.value;
      } else if (arg.prefix.isEmpty && item == null) {
        item = arg.value;
      } else if (arg.prefix == 'to' && arg.value.toLowerCase() == '<player.inventory>') {
        continue;
      } else {
        return _Converted.kept('give argument ${_raw(arg)} has no Animus equivalent');
      }
    }
    if (item == null) return const _Converted.kept('nothing to give');
    final String what = item;
    if (what.startsWith('<') && denizenTagEnd(what, 0) == what.length - 1) return giveValue(what, quantity, scope, pending);
    if (what.toLowerCase() == 'xp' || what.toLowerCase() == 'money') return _Converted.kept('giving $what has no Animus equivalent');
    final DenizenContainer? script = converter.containers[what.toLowerCase()];
    if (script != null && script.type == 'book') {
      if (quantity != null) return const _Converted.kept('a book is given one at a time');
      final JObject? book = converter.book(script, scope, pending);
      return book != null ? _Converted.of(<JObject>[book]) : _Converted.kept('book script ${script.name} did not convert');
    }
    final _Action action = _Action();
    if (script != null && script.type == 'item') {
      final String? problem = converter.itemScript(script, action, scope, pending);
      if (problem != null) return _Converted.kept(problem);
    } else {
      final String? material = this.material(what);
      if (material == null) return const _Converted.kept('only plain materials, simple item scripts and book scripts can be given');
      action.put('item', JString(material));
    }
    if (quantity != null) {
      final _Converted? problem = amount(action, quantity, scope, pending);
      if (problem != null) return problem;
    }
    return _Converted.of(<JObject>[_reorder(action, <String>['item', 'amount', 'name', 'lore']).node('animus:give-item')]);
  }

  /// `give <[item]>`: an item value, a list of them, or a material name read as the command runs, each given as one
  /// stack; a value that may be a list is given item by item, up to 100.
  _Converted giveValue(String raw, String? quantity, _Scope scope, _Pending pending) {
    final _Result<_Expr> value = expressions.value(raw, scope, pending);
    if (value.failed) return _Converted.kept(value.reason!);
    final String kind = value.value!.kind;
    if (const <String>['L', 'e', 'N', 'b', 'n', 't', 'd'].contains(kind)) return const _Converted.kept('only items and materials can be given');
    final _Action action = _Action();
    if (quantity != null) {
      final _Converted? problem = amount(action, quantity, scope, pending);
      if (problem != null) return problem;
    }
    if (kind == 'i') {
      action.expression('stack', value.value!.text);
      return _Converted.of(<JObject>[_reorder(action, <String>['stack', 'amount']).node('animus:give-item')]);
    }
    final String each = converter.temporary(scope);
    action.expression('stack', expressions.stack(_Expr(each, 'a'), scope, pending));
    final String text = value.value!.text;
    final JObject loop = _plain('foreach')
      ..add('list', JObject()..addString('expression', kind == 'l' || kind == 'I' ? text : "(same(type_of($text), 'list') ? $text : [$text])"))
      ..addString('as', each)
      ..addInt('maximum', 100)
      ..add('body', _reorder(action, <String>['stack', 'amount']).node('animus:give-item'));
    return _Converted.of(<JObject>[loop]);
  }

  _Converted take(DenizenCommand command, _Scope scope, _Pending pending) {
    String? item;
    String? quantity;
    for (final DenizenArg arg in command.args) {
      if (arg.prefix == 'quantity') {
        quantity = arg.value;
      } else if ((arg.prefix == 'item' || arg.prefix.isEmpty) && item == null) {
        if (arg.prefix.isEmpty &&
            <String>['money', 'xp', 'iteminhand', 'cursoritem', 'bydisplay', 'scriptname', 'slot', 'flagged'].contains(arg.value.toLowerCase())) {
          return _Converted.kept('take ${arg.value} has no Animus equivalent');
        }
        item = material(arg.value);
        if (item == null) return const _Converted.kept('only plain materials can be taken; item scripts, flags and tags have no Animus equivalent');
      } else {
        return _Converted.kept('take argument ${_raw(arg)} has no Animus equivalent');
      }
    }
    if (item == null) return const _Converted.kept('nothing to take');
    final _Action action = _Action()..put('item', JString(item));
    final _Converted? problem = amount(action, quantity ?? '1', scope, pending);
    if (problem != null) return problem;
    return _Converted.of(<JObject>[action.node('animus:take-item')]);
  }

  _Converted equip(DenizenCommand command) {
    final SplayTreeMap<int, JObject> slots = SplayTreeMap<int, JObject>();
    for (final DenizenArg arg in command.args) {
      if (arg.prefix.isEmpty) {
        if (_npcTarget(arg.value)) continue;
        return const _Converted.kept('equipping another entity has no Animus equivalent');
      }
      if (!_slots.containsKey(arg.prefix)) return _Converted.kept('equipment slot ${arg.prefix} has no Animus equivalent');
      final JObject? equipment = this.equipment(_slots[arg.prefix]!, arg.value);
      if (equipment == null) return const _Converted.kept('only plain materials can be equipped');
      slots[_slotOrder.indexOf(arg.prefix)] = equipment;
    }
    if (slots.isEmpty) return const _Converted.kept('nothing to equip');
    return _Converted(List<JObject>.of(slots.values), '');
  }

  JObject? equipment(String slot, String value) {
    final JObject arguments = JObject()..addString('slot', slot);
    if (value.toLowerCase() != 'air') {
      final String? item = material(value);
      if (item == null) return null;
      arguments.addString('item', item);
    }
    return _action('animus:set-equipment', arguments);
  }

  /// [npcGiven] says the caller names the NPC with the npc argument, which a server behavior may do too.
  _Converted sound(DenizenCommand command, _Scope scope, _Pending pending, bool npcGiven) {
    String? sound;
    String? place;
    bool custom = false;
    final _Action levels = _Action();
    for (final DenizenArg arg in command.args) {
      if (arg.isWord('custom')) {
        custom = true;
        continue;
      }
      switch (arg.prefix) {
        case 'sound':
          sound = arg.value;
        // The sound category picks the player's volume slider; Animus plays every sound in the master category.
        case 'sound_category':
          break;
        case 'volume':
        case 'pitch':
          final String? reason = level(levels, arg.prefix, arg.value, arg.prefix == 'volume' ? 0 : 0.5, arg.prefix == 'volume' ? 4 : 2, scope, pending);
          if (reason != null) return _Converted.kept(reason);
        case '':
          if (place != null) return const _Converted.kept('sound for several targets has no Animus equivalent');
          final String? where = _at(arg.value);
          if (where != null) {
            place = where;
          } else if (sound == null && !arg.value.startsWith('<')) {
            sound = arg.value;
          } else {
            return const _Converted.kept('sound location must be the NPC or the player');
          }
        default:
          return _Converted.kept('playsound argument ${arg.prefix}: has no Animus equivalent');
      }
    }
    if (sound == null) return const _Converted.kept('playsound needs a sound');
    if (place == 'npc' && scope.server && !npcGiven) return const _Converted.kept('a sound at the NPC needs an NPC; $_noNpc');
    _Action action = _Action();
    if (sound.contains('<')) {
      final _Result<_Expr> named = expressions.value(sound, scope, pending);
      if (named.failed) return _Converted.kept(named.reason!);
      final String held = converter.temporary(scope);
      pending.steps.add(_plain('set')
        ..addString('variable', held)
        ..add('value', JObject()..addString('expression', 'lower(${_textual(named.value!)})')));
      action.expression('sound', _soundKey(held, custom));
    } else {
      final String? key = denizenSoundKey(sound, custom);
      if (key == null) return _Converted.kept('sound $sound has no resource key');
      converter.sounds[sound] = key;
      action.put('sound', JString(key));
    }
    action = _merged(action, levels, const <String>['volume', 'pitch']);
    if (place == 'player' || scope.server && !npcGiven) {
      action.put('at', const JString('player'));
    } else if (place == 'npc') {
      action.put('audience', const JString('nearby'));
    }
    return _Converted.of(<JObject>[action.node('animus:play-sound')]);
  }

  /// A sound's volume or pitch: a literal number held to the range, as the game holds pitch to 0.5-2, or a tag read as
  /// a number and held there when it plays. Null when it converts, otherwise the reason.
  String? level(_Action action, String name, String value, double minimum, double maximum, _Scope scope, _Pending pending) {
    if (_full(r'[0-9]{1,4}(?:\.[0-9]{1,6})?', value)) {
      final double number = javaParseDouble(value);
      action.arguments.addDouble(name, number < minimum ? minimum : number > maximum ? maximum : number);
      return null;
    }
    if (!value.contains('<')) return '$name must be a number';
    final _Result<_Expr> expr = expressions.value(value, scope, pending);
    if (expr.failed) return expr.reason;
    action.expression(name, 'clamp(${_numeric(expr.value!)}, ${_literal(_trim(minimum)).text}, ${_literal(_trim(maximum)).text})');
    return null;
  }

  _Converted actionbar(DenizenCommand command, _Scope scope, _Pending pending) {
    final List<String> words = <String>[];
    for (final DenizenArg arg in command.args) {
      if (arg.prefix.isEmpty) {
        words.add(arg.value);
        continue;
      }
      if (arg.prefix == 'targets' && _playerTarget(arg.value)) continue;
      return _Converted.kept('actionbar argument ${arg.prefix}: has no Animus equivalent');
    }
    if (words.isEmpty) return const _Converted.kept('actionbar without text');
    final _Result<_Text> text = this.text(words.join(' '), scope, pending, false, 256);
    if (text.failed) return _Converted.kept(text.reason!);
    final _Action action = _Action()..text('text', text.value!);
    return _Converted.of(<JObject>[action.node('animus:actionbar')]);
  }

  _Converted title(DenizenCommand command, _Scope scope, _Pending pending) {
    final _Action action = _Action();
    bool any = false;
    final SplayTreeMap<int, int> times = SplayTreeMap<int, int>();
    for (final DenizenArg written in command.args) {
      final DenizenArg arg = _quotedPrefix(written, const <String>['title', 'subtitle', 'fade_in', 'stay', 'fade_out', 'targets']);
      switch (arg.prefix) {
        case 'title':
        case 'subtitle':
          final _Result<_Text> text = this.text(arg.value, scope, pending, false, 256);
          if (text.failed) return _Converted.kept(text.reason!);
          action.text(arg.prefix, text.value!);
          any = true;
        case 'fade_in':
        case 'stay':
        case 'fade_out':
          final int? ticks = denizenTicks(arg.value);
          if (ticks == null || ticks > 72000) return const _Converted.kept('title timing needs a literal duration up to an hour');
          times[arg.prefix == 'fade_in'
              ? 0
              : arg.prefix == 'stay'
                  ? 1
                  : 2] = ticks;
        case 'targets':
          if (!_playerTarget(arg.value)) return const _Converted.kept('titles for other targets have no Animus equivalent');
        default:
          return _Converted.kept('title argument ${arg.prefix.isEmpty ? arg.value : '${arg.prefix}:'} has no Animus equivalent');
      }
    }
    if (!any) return const _Converted.kept('title without text');
    const List<String> names = <String>['fadeIn', 'stay', 'fadeOut'];
    for (final MapEntry<int, int> time in times.entries) {
      action.put(names[time.key], JInt.of(time.value));
    }
    return _Converted.of(<JObject>[_reorder(action, <String>['title', 'subtitle', 'fadeIn', 'stay', 'fadeOut']).node('animus:title')]);
  }

  /// Denizen reads a prefix inside quotes too: `"title:Town gate"` is the title argument.
  static DenizenArg _quotedPrefix(DenizenArg arg, List<String> names) {
    if (!arg.quoted || arg.prefix.isNotEmpty) return arg;
    final int colon = arg.value.indexOf(':');
    if (colon <= 0 || !names.contains(arg.value.substring(0, colon).toLowerCase())) return arg;
    return DenizenArg(arg.value.substring(0, colon).toLowerCase(), arg.value.substring(colon + 1), true);
  }

  _Converted teleport(DenizenCommand command, _Scope scope, _Pending pending) {
    final List<DenizenArg> plain = command.plain();
    if (plain.length != command.args.length) return const _Converted.kept('teleport argument has no Animus equivalent');
    if (plain.isEmpty || plain.length > 2) return const _Converted.kept('teleport needs a target and a destination');
    String target = 'player';
    if (plain.length == 2) {
      final String who = plain.first.value;
      if (_npcTarget(who)) {
        target = 'npc';
      } else if (!_playerTarget(who)) {
        return const _Converted.kept('teleporting other entities has no Animus equivalent');
      }
    }
    if (target == 'npc' && scope.server) return const _Converted.kept('teleport of the NPC needs an NPC; $_noNpc');
    final String destination = plain.last.value;
    final String lowered = destination.toLowerCase();
    final JObject arguments = JObject();
    if (target == 'npc') arguments.addString('target', 'npc');
    if (lowered == '<npc>' || lowered == '<npc.location>') {
      if (scope.server) return const _Converted.kept('teleport to the NPC needs an NPC; $_noNpc');
      arguments.addString('to', 'npc');
    } else if (lowered == '<player>' || lowered == '<player.location>') {
      arguments.addString('to', 'player');
    } else {
      final List<double>? at = _location(destination);
      final List<String?>? extras = _locationExtras(destination);
      if (at == null || extras == null) {
        final _Action computed = _Action();
        for (final MapEntry<String, JElement> entry in arguments.entries) {
          computed.put(entry.key, entry.value);
        }
        final String? problem = destination.startsWith('<')
            ? coordinates(destination, computed, scope, pending, true)
            : 'teleport destination must be the player, the NPC, a literal location or a location value';
        if (problem != null) return _Converted.kept(problem);
        return _Converted.of(<JObject>[computed.node('animus:teleport')]);
      }
      arguments
        ..addDouble('x', at[0])
        ..addDouble('y', at[1])
        ..addDouble('z', at[2]);
      if (extras[0] != null) arguments.addString('world', extras[0]!);
      if (extras[1] != null) arguments.addDouble('yaw', javaParseDouble(extras[1]!));
      if (extras[2] != null) arguments.addDouble('pitch', javaParseDouble(extras[2]!));
    }
    if (target == 'npc' && arguments.has('to') && arguments.get('to')!.asPrimitive.asString == 'npc') return const _Converted.kept('teleporting the NPC to itself does nothing');
    if (target == 'player' && arguments.has('to') && arguments.get('to')!.asPrimitive.asString == 'player') {
      return const _Converted.kept('teleporting the player to itself does nothing');
    }
    return _Converted.of(<JObject>[_action('animus:teleport', arguments)]);
  }

  /// `look (<entity>) <target> (duration:..)`: the NPC, or another NPC held in a definition, turns to the player, a literal
  /// location or a location value. A duration is not held; the look-close module turns the NPC again as usual.
  _Converted look(DenizenCommand command, _Scope scope, _Pending pending) {
    List<DenizenArg> plain = command.plain();
    for (final DenizenArg arg in command.args) {
      if (arg.prefix.isNotEmpty && arg.prefix != 'duration') return _Converted.kept('look argument ${arg.prefix}: has no Animus equivalent');
    }
    final _Action action = _Action();
    if (plain.length == 2) {
      final _Converted? other = subjectNpc(plain.first.value, action, scope, pending);
      if (other != null) return other;
      plain = plain.sublist(1, 2);
    } else if (scope.server) {
      return const _Converted.kept(_noNpc);
    }
    if (plain.length != 1) return const _Converted.kept('look at more than one target has no Animus equivalent');
    final String target = plain.first.value.toLowerCase();
    if (target == '<player>' || target == '<player.location>' || target == '<player.eye_location>') {
      action.put('target', const JString('player'));
    } else {
      final List<double>? at = _location(plain.first.value);
      if (at != null) {
        action.put('x', JDouble(at[0]));
        action.put('y', JDouble(at[1]));
        action.put('z', JDouble(at[2]));
      } else {
        final String? problem = coordinates(plain.first.value, action, scope, pending, false);
        if (problem != null) return _Converted.kept(problem);
      }
    }
    return _Converted.of(<JObject>[_reorder(action, <String>['target', 'x', 'y', 'z', 'npc']).node('animus:look')]);
  }

  /// The entity a command acts on, written first: the NPC itself, or another NPC held in a definition as the `npc`
  /// argument. A reason when it is anything else, or the NPC itself in a server behavior.
  _Converted? subjectNpc(String raw, _Action action, _Scope scope, _Pending pending) {
    if (_npcTarget(raw)) return scope.server ? const _Converted.kept(_noNpc) : null;
    if (!_full(r'<\[[A-Za-z0-9_]{1,64}]>', raw)) return const _Converted.kept('only NPCs can be turned or animated');
    final _Result<_Expr> held = expressions.value(raw, scope, pending);
    if (held.failed) return _Converted.kept(held.reason!);
    // Only a value known to hold an NPC, such as an item of an NPC list, may be one; a player held there could not be turned.
    if (held.value!.kind != 'e') return const _Converted.kept('only NPCs held in a definition, such as an item of an NPC list, can be turned or animated');
    action.expression('npc', held.value!.text);
    return null;
  }

  /// x, y and z (and world when [world]) from a location value, such as a definition holding one; [anything] also reads a
  /// value of unknown kind as location text, written with or without `l@`. Null when it converts.
  String? coordinates(String raw, _Action action, _Scope scope, _Pending pending, bool world, [bool anything = false]) {
    final _Result<_Expr> place = expressions.value(raw, scope, pending);
    if (place.failed) return place.reason;
    final bool unknown = anything && place.value!.kind == 'a';
    if (place.value!.kind != 'L' && !unknown) return 'the target must be the player, the NPC, a literal location or a location value';
    final String parts = unknown ? "split(replace(text(${place.value!.text}), 'l@', ''), ',')" : "split(${place.value!.text}, ',')";
    const List<String> axes = <String>['x', 'y', 'z'];
    for (int axis = 0; axis < 3; axis++) {
      action.expression(axes[axis], 'num(get($parts, $axis, 0))');
    }
    if (world) action.expression('world', "get($parts, 3, '')");
    return null;
  }

  _Converted animate(DenizenCommand command, _Scope scope, _Pending pending) {
    String? animation;
    bool npc = false;
    final _Action subject = _Action();
    for (final DenizenArg arg in command.args) {
      if (arg.prefix.isEmpty) {
        if (npc) return const _Converted.kept('animating several entities has no Animus equivalent');
        final _Converted? other = subjectNpc(arg.value, subject, scope, pending);
        if (other != null) return other;
        npc = true;
        continue;
      }
      if (arg.prefix == 'animation') {
        animation = arg.value.toLowerCase();
      } else if (arg.prefix == 'for') {
        return const _Converted.kept('animate for: has no Animus equivalent; every viewer sees the animation');
      } else {
        return _Converted.kept('animate argument ${arg.prefix}: has no Animus equivalent');
      }
    }
    if (!npc) return const _Converted.kept('animate needs the NPC');
    if (animation == null || animation.contains('<')) return const _Converted.kept('animate needs a literal animation');
    final String? mapped = _animations[animation];
    if (mapped == null) return _Converted.kept('animation $animation has no Animus equivalent');
    final _Action action = _Action();
    action.put('animation', JString(mapped));
    final JElement? npcExpression = subject.expressions.get('/npc');
    if (npcExpression != null) action.expression('npc', npcExpression.asPrimitive.asString);
    return _Converted.of(<JObject>[action.node('animus:animate')]);
  }

  /// `lookclose (<npc>) state:true|false (range:n) (realistic)`: the NPC's look-close until a restart or a definition
  /// change, with line of sight for realistic. Without a state it toggles, which has no Animus equivalent.
  _Converted lookclose(DenizenCommand command, _Scope scope, _Pending pending) {
    final _Action action = _Action();
    bool? state;
    for (final DenizenArg arg in command.args) {
      switch (arg.prefix) {
        case 'state':
          if (arg.value.toLowerCase() != 'true' && arg.value.toLowerCase() != 'false') return const _Converted.kept('lookclose state must be true or false');
          state = arg.value.toLowerCase() == 'true';
        case 'range':
          final double? range = _number(arg.value, 1, 64);
          if (range == null) return const _Converted.kept('lookclose range must be a written number from 1 to 64');
          action.put('range', JDouble(range));
        case '':
          if (arg.isWord('realistic')) {
            action.put('lineOfSight', const JBool(true));
          } else {
            final _Converted? other = subjectNpc(arg.value, action, scope, pending);
            if (other != null) return other;
          }
        default:
          return _Converted.kept('lookclose argument ${arg.prefix}: has no Animus equivalent');
      }
    }
    if (scope.server && !action.expressions.has('/npc')) return const _Converted.kept(_noNpc);
    if (state == null) return const _Converted.kept('lookclose without state: toggles, which has no Animus equivalent; write state:true or state:false');
    final _Action ordered = _Action();
    ordered.put('enabled', JBool(state));
    for (final MapEntry<String, JElement> entry in action.arguments.entries) {
      ordered.arguments.add(entry.key, entry.value);
    }
    for (final MapEntry<String, JElement> entry in action.expressions.entries) {
      ordered.expressions.add(entry.key, entry.value);
    }
    return _Converted.of(<JObject>[ordered.node('animus:look-close')]);
  }

  /// `explode (power:n) (<location>) (fire) (breakblocks)`: an explosion at the location, or at the NPC. Fire and broken
  /// blocks need `explosionsDamageBlocks`, which is off by default.
  _Converted explode(DenizenCommand command, _Scope scope, _Pending pending) {
    if (scope.server) return const _Converted.kept("explode happens at the NPC's place; $_noNpc");
    final _Action action = _Action();
    for (final DenizenArg arg in command.args) {
      if (arg.isWord('fire')) {
        action.put('fire', const JBool(true));
        continue;
      }
      if (arg.isWord('breakblocks')) {
        action.put('breakBlocks', const JBool(true));
        continue;
      }
      switch (arg.prefix) {
        case 'power':
          final double? power = _number(arg.value, 0, 16);
          if (power != null) {
            action.put('power', JDouble(power));
          } else {
            if (!arg.value.contains('<')) return const _Converted.kept('explode power must be from 0 to 16');
            final _Result<_Expr> computed = expressions.value(arg.value, scope, pending);
            if (computed.failed) return _Converted.kept(computed.reason!);
            action.expression('power', 'clamp(${_numeric(computed.value!)}, 0, 16)');
          }
        case '':
          if (_npcTarget(arg.value)) continue;
          final List<double>? at = _location(arg.value);
          if (at != null) {
            action.put('x', JDouble(at[0]));
            action.put('y', JDouble(at[1]));
            action.put('z', JDouble(at[2]));
            continue;
          }
          // A definition given to explode holds a place, which Denizen writes as location text.
          final String? problem = arg.value.startsWith('<') ? coordinates(arg.value, action, scope, pending, true, true) : 'explode argument ${arg.value} has no Animus equivalent';
          if (problem != null) return _Converted.kept(problem);
        default:
          return _Converted.kept('explode argument ${arg.prefix}: has no Animus equivalent');
      }
    }
    return _Converted.of(<JObject>[_reorder(action, <String>['x', 'y', 'z', 'world', 'power', 'fire', 'breakBlocks']).node('animus:explode')]);
  }

  /// Mechanisms with an Animus action: the NPC's name and held or worn items, and showing a book script to the player.
  _Converted adjust(DenizenCommand command, _Scope scope, _Pending pending) {
    if (command.args.length == 2 && command.args.first.prefix == 'def') return adjustItem(command.args.first.value, command.args[1], scope, pending);
    if (command.args.length != 2 || command.args.first.prefix.isNotEmpty) return const _Converted.kept('adjust takes one object and one mechanism');
    final String object = command.args.first.value;
    final DenizenArg mechanism = command.args[1];
    final String name = mechanism.prefix.isEmpty ? mechanism.value.toLowerCase() : mechanism.prefix;
    if (_npcTarget(object)) {
      if (scope.server) return const _Converted.kept('adjust of the NPC needs an NPC; $_noNpc');
      switch (name) {
        case 'name':
          final _Result<_Text> text = this.text(mechanism.value, scope, pending, false, 16);
          if (text.failed) return _Converted.kept(text.reason!);
          final _Action action = _Action()..text('name', text.value!);
          return _Converted.of(<JObject>[action.node('animus:set-appearance')]);
        case 'item_in_hand':
        case 'item_in_offhand':
          final JObject? equipment = this.equipment(name == 'item_in_hand' ? 'main_hand' : 'off_hand', mechanism.value);
          return equipment != null ? _Converted.of(<JObject>[equipment]) : const _Converted.kept('only plain materials can be equipped');
        default:
          return _Converted.kept('NPC mechanism $name has no Animus equivalent');
      }
    }
    if (_playerTarget(object) && name == 'show_book') {
      final DenizenContainer? book = converter.containers[mechanism.value.toLowerCase()];
      if (book == null || book.type != 'book') return const _Converted.kept('show_book needs a book script');
      final JObject? given = converter.book(book, scope, pending);
      return given != null ? _Converted.of(<JObject>[given]) : _Converted.kept('book script ${book.name} did not convert');
    }
    return _Converted.kept('mechanism $name of $object has no Animus equivalent');
  }

  static final RegExp _barId = RegExp(r'^([A-Za-z0-9_.-]{1,64})(?:<(player|\[[A-Za-z0-9_]{1,64}])\.uuid>)?$');

  /// `bossbar create|update|auto|remove id ...`: a boss bar for each player, by the written part of its id. An id that ends
  /// with a player's uuid, as scripts write bars meant for one player, is that player's bar.
  _Converted bossbar(DenizenCommand command, _Scope scope, _Pending pending) {
    const List<String> named = <String>['title', 'progress', 'color', 'style', 'players'];
    final List<DenizenArg> plain = <DenizenArg>[
      for (final DenizenArg arg in command.args)
        if (_quotedPrefix(arg, named).prefix.isEmpty) arg,
    ];
    if (plain.length != 2 || !const <String>['create', 'update', 'auto', 'remove'].contains(plain.first.value.toLowerCase())) {
      return const _Converted.kept('bossbar needs create, update, auto or remove and one id');
    }
    final bool remove = plain.first.value.toLowerCase() == 'remove';
    final RegExpMatch? id = _barId.firstMatch(plain[1].value);
    if (id == null) return const _Converted.kept("a bossbar id must be written out, with at most a player's uuid after it");
    String? players = id.group(2) == null || id.group(2) == 'player' ? null : '<${id.group(2)}>';
    final _Action action = _Action();
    action.put('id', JString(id.group(1)!.toLowerCase()));
    if (remove) action.put('operation', const JString('remove'));
    for (final DenizenArg written in command.args) {
      if (plain.contains(written)) continue;
      final DenizenArg arg = _quotedPrefix(written, named);
      switch (arg.prefix) {
        case 'players':
          if (players != null && players.toLowerCase() != arg.value.toLowerCase()) return const _Converted.kept('a bossbar for players its id does not name has no Animus equivalent');
          if (!_playerTarget(arg.value)) players = arg.value;
        case 'title':
          if (remove) return const _Converted.kept('a removed bossbar takes no title');
          final _Result<_Text> text = this.text(arg.value, scope, pending, false, 256);
          if (text.failed) return _Converted.kept(text.reason!);
          action.text('title', text.value!);
        case 'progress':
          if (remove) return const _Converted.kept('a removed bossbar takes no progress');
          if (_full(r'(?:0|1)(?:\.[0-9]{1,6})?', arg.value) && double.parse(arg.value) <= 1) {
            action.put('progress', JDouble(double.parse(arg.value)));
          } else {
            final _Result<_Expr> progress = expressions.value(arg.value, scope, pending);
            if (progress.failed) return _Converted.kept(progress.reason!);
            action.expression('progress', 'clamp(${_numeric(progress.value!)}, 0, 1)');
          }
        case 'color':
        case 'style':
          final String value = arg.value.toLowerCase();
          final List<String> known = arg.prefix == 'color'
              ? const <String>['pink', 'blue', 'red', 'green', 'yellow', 'purple', 'white']
              : const <String>['solid', 'segmented_6', 'segmented_10', 'segmented_12', 'segmented_20'];
          if (remove || !known.contains(value)) return _Converted.kept('bossbar ${arg.prefix} $value has no Animus equivalent');
          action.put(arg.prefix, JString(value));
        default:
          return _Converted.kept('bossbar argument ${arg.prefix.isEmpty ? arg.value : '${arg.prefix}:'} has no Animus equivalent');
      }
    }
    return forPlayers(action.node('animus:bossbar'), players, scope, pending);
  }

  /// `sidebar set|add|set_line|remove (title:..) (values:a|b) (scores:n) (players:..)`: the players' sidebar, its lines top
  /// first, up to 15. add puts lines below the last ones, and set_line writes the line its one score names.
  _Converted sidebar(DenizenCommand command, _Scope scope, _Pending pending) {
    const List<String> named = <String>['title', 'values', 'players', 'scores'];
    final List<DenizenArg> plain = <DenizenArg>[
      for (final DenizenArg arg in command.args)
        if (_quotedPrefix(arg, named).prefix.isEmpty) arg,
    ];
    final String operation = plain.isEmpty ? 'set' : plain.first.value.toLowerCase();
    if (plain.length > 1 || !const <String>['set', 'add', 'set_line', 'remove'].contains(operation)) {
      return const _Converted.kept('only sidebar set, add, set_line and remove have an Animus equivalent');
    }
    final _Action action = _Action();
    String? players;
    bool titled = false, lined = false;
    if (operation != 'set') action.put('operation', JString(operation));
    for (final DenizenArg written in command.args) {
      final DenizenArg arg = _quotedPrefix(written, named);
      if (arg.prefix != 'scores') continue;
      if (operation != 'set_line' || !_full('[0-9]{1,2}', arg.value) || int.parse(arg.value) < 1 || int.parse(arg.value) > 15) {
        return const _Converted.kept('sidebar scores: takes one written line from 1 to 15, with set_line only');
      }
      action.put('line', JInt.of(int.parse(arg.value)));
      lined = true;
    }
    if (operation == 'set_line' && !lined) return const _Converted.kept('sidebar set_line needs scores: naming its line');
    for (final DenizenArg written in command.args) {
      if (plain.contains(written)) continue;
      final DenizenArg arg = _quotedPrefix(written, named);
      switch (arg.prefix) {
        case 'scores':
          break;
        case 'players':
          if (!_playerTarget(arg.value)) players = arg.value;
        case 'title':
          if (operation == 'remove') return const _Converted.kept('a removed sidebar takes no title');
          final _Result<_Text> text = this.text(arg.value, scope, pending, false, 128);
          if (text.failed) return _Converted.kept(text.reason!);
          action.text('title', text.value!);
          titled = true;
        case 'values':
          if (operation == 'remove') return const _Converted.kept('a removed sidebar takes no values');
          final _Result<_Expr> lines = denizenSegments(arg.value).length == 1 && arg.value.startsWith('<') ? expressions.value(arg.value, scope, pending) : literalList(arg.value, scope, pending);
          if (lines.failed) return _Converted.kept(lines.reason!);
          final String held = lines.value!.text;
          final String text = lines.value!.kind == 'l' ? "join($held, '|')" : "(same(type_of($held), 'list') ? join($held, '|') : text($held))";
          action.expression('lines', "slice(split($text, '|'), 0, ${operation == 'set_line' ? 1 : 15})");
        default:
          return _Converted.kept('sidebar argument ${arg.prefix.isEmpty ? arg.value : '${arg.prefix}:'} has no Animus equivalent');
      }
    }
    if (operation == 'set' && !titled) return const _Converted.kept('a sidebar needs a title in Animus');
    return forPlayers(action.node('animus:sidebar'), players, scope, pending);
  }

  /// One action for the triggering player, or for each player a list names, up to 100, as the player argument.
  _Converted forPlayers(JObject node, String? players, _Scope scope, _Pending pending) {
    if (players == null) return _Converted.of(<JObject>[node]);
    final _Result<_Expr> list = expressions.value(players, scope, pending);
    if (list.failed) return _Converted.kept('players ${list.reason}');
    if (list.value!.kind == 'e' || list.value!.kind == 'N') return const _Converted.kept('only players can be shown bars and sidebars');
    final String each = converter.temporary(scope);
    node.object('arguments')!.add('player', JNull.instance);
    final JObject filled = node.object('expressions') ?? JObject();
    filled.addString('/player', each);
    node.add('expressions', filled);
    return _Converted.of(<JObject>[
      _plain('foreach')
        ..add('list', JObject()..addString('expression', 'players(${list.value!.text})'))
        ..addString('as', each)
        ..addInt('maximum', 100)
        ..add('body', node),
    ]);
  }

  static final RegExp _enchantment = RegExp(r'^(?:minecraft:)?([a-z0-9_]{1,64})[=,]([0-9]{1,3})$');

  /// `adjust def:sword enchantments:[sharpness=5]`: an item held in a definition takes its enchantments, display name,
  /// lore or quantity into its map. Other mechanisms have no item value form.
  _Converted adjustItem(String name, DenizenArg written, _Scope scope, _Pending pending) {
    if (!_full('[A-Za-z0-9_]{1,64}', name)) return const _Converted.kept('adjust def: needs a definition name');
    const List<String> mechanisms = <String>['enchantments', 'display', 'lore', 'quantity'];
    final DenizenArg mechanism = _quotedPrefix(written, mechanisms);
    if (!mechanisms.contains(mechanism.prefix)) {
      return _Converted.kept('item mechanism ${mechanism.prefix.isEmpty ? mechanism.value.split(':').first.toLowerCase() : mechanism.prefix} has no Animus equivalent');
    }
    final String variable = _definitionVariable(name.toLowerCase());
    converter.declare(scope, variable, 'any');
    final String value;
    switch (mechanism.prefix) {
      case 'enchantments':
        String raw = mechanism.value.toLowerCase();
        if (raw.startsWith('[') && raw.endsWith(']')) raw = raw.substring(1, raw.length - 1);
        final JObject levels = JObject();
        for (final String entry in raw.split(RegExp('[;|]'))) {
          final RegExpMatch? match = _enchantment.firstMatch(javaTrim(entry));
          if (match == null) return const _Converted.kept('enchantments need written names and levels');
          final int level = int.parse(match.group(2)!);
          if (level < 1 || level > 255) return const _Converted.kept('an enchantment level is 1 to 255');
          levels.addInt('minecraft:${match.group(1)}', level);
        }
        if (levels.length > 32) return const _Converted.kept('an item takes up to 32 enchantments');
        value = "put($variable, 'enchantments', ${expressions.constant(levels, scope, pending)})";
      case 'display':
        final _Result<_Expr> named = expressions.value(mechanism.value, scope, pending);
        if (named.failed) return _Converted.kept(named.reason!);
        value = "put($variable, 'name', ${_textual(named.value!)})";
      case 'lore':
        final _Result<_Expr> lines = expressions.value(mechanism.value, scope, pending);
        if (lines.failed) return _Converted.kept(lines.reason!);
        value = "put($variable, 'lore', ${lines.value!.kind == 'l' ? lines.value!.text : "split(${_textual(lines.value!)}, '|')"})";
      default:
        final _Result<_Expr> amount = expressions.value(mechanism.value, scope, pending);
        if (amount.failed) return _Converted.kept(amount.reason!);
        value = "put($variable, 'amount', int(${_numeric(amount.value!)}))";
    }
    if (value.length > _maximumExpression) return const _Converted.kept('expression longer than 4096 characters');
    return _Converted.of(<JObject>[
      _plain('set')
        ..addString('variable', variable)
        ..add('value', JObject()..addString('expression', value)),
    ]);
  }

  _Converted inventory(DenizenCommand command) {
    if (command.args.isEmpty || !command.args.first.isWord('open')) return const _Converted.kept('only inventory open has an Animus equivalent');
    String? destination;
    for (final DenizenArg arg in command.args.sublist(1)) {
      if (arg.prefix == 'd' || arg.prefix == 'destination') {
        destination = arg.value;
      } else if (arg.prefix == 'o' || arg.prefix == 'origin' || arg.prefix == 'slot') {
        return _Converted.kept('inventory open with ${arg.prefix}: has no Animus equivalent');
      } else if (!(arg.prefix == 'player' && _playerTarget(arg.value))) {
        return _Converted.kept('inventory argument ${_raw(arg)} has no Animus equivalent');
      }
    }
    if (destination == null) return const _Converted.kept('inventory open without a destination');
    String name = destination;
    if (name.toLowerCase().startsWith('<inventory[') && name.endsWith(']>')) name = name.substring(11, name.length - 2);
    final DenizenContainer? script = converter.containers[name.toLowerCase()];
    if (script == null || script.type != 'inventory') return const _Converted.kept('inventory open needs an inventory script by name');
    final String? gui = converter.guiId(script);
    if (gui == null) return _Converted.kept('inventory script ${script.name} did not convert to a GUI');
    return _Converted.of(<JObject>[_action('animus:open-gui', JObject()..addString('gui', gui))]);
  }
}

final RegExp _definitionArgument = RegExp(r'^def\.[a-z0-9_]+:.*$', caseSensitive: false);
