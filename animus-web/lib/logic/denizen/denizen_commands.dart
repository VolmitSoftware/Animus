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

_Converted _waitFor(DenizenCommand command) {
  if (command.args.length > 1 || command.args.length == 1 && command.args.first.prefix.isNotEmpty) return const _Converted.kept('wait argument has no Animus equivalent');
  final int? ticks = command.args.isEmpty ? 60 : denizenTicks(command.args.first.value);
  if (ticks == null) return const _Converted.kept('wait needs a literal duration');
  if (ticks == 0) return _Converted.of(<JObject>[]);
  if (ticks > 72000) return const _Converted.kept('wait longer than an hour');
  return _Converted.of(<JObject>[_plain('wait')..addInt('ticks', ticks)]);
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

_Converted _look(DenizenCommand command) {
  List<DenizenArg> plain = command.plain();
  if (plain.length != command.args.length) return const _Converted.kept('look argument has no Animus equivalent');
  if (plain.length == 2 && _npcTarget(plain.first.value)) plain = plain.sublist(1, 2);
  if (plain.length != 1) return const _Converted.kept('look at more than one target has no Animus equivalent');
  final String target = plain.first.value.toLowerCase();
  final JObject arguments = JObject();
  if (target == '<player>' || target == '<player.location>' || target == '<player.eye_location>') {
    arguments.addString('target', 'player');
  } else {
    final List<double>? at = _location(plain.first.value);
    if (at == null) return const _Converted.kept('look target must be the player or a literal location');
    arguments
      ..addDouble('x', at[0])
      ..addDouble('y', at[1])
      ..addDouble('z', at[2]);
  }
  return _Converted.of(<JObject>[_action('animus:look', arguments)]);
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

/// Where a sound or particle plays: `player` or `npc`, or null when the place has no Animus equivalent.
String? _at(String value) {
  final String target = value.toLowerCase();
  if (target == '<player>' || target == '<player.location>' || target == '<player.eye_location>') return 'player';
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

_Converted _particle(DenizenCommand command, _Scope scope) {
  String? effect;
  String place = scope.server ? 'player' : 'npc';
  bool trigger = false;
  int? count;
  double? spread;
  for (final DenizenArg arg in command.args) {
    switch (arg.prefix) {
      case 'effect':
        effect = arg.value.toLowerCase();
      case 'at':
        final String? where = _at(arg.value);
        if (where == null) return const _Converted.kept('particles show only at the NPC or the player in Animus');
        place = where;
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
  if (place == 'npc' && scope.server) return const _Converted.kept('particles at the NPC need an NPC; $_noNpc');
  if (effect == null || effect.contains('<')) return const _Converted.kept('playeffect needs a literal effect');
  String name = effect.startsWith('minecraft:') ? effect.substring(10) : effect;
  if (_dataParticles.contains(name)) return const _Converted.kept('particles that need extra data have no Animus equivalent');
  name = _particleNames[name] ?? name;
  if (!_full('[a-z0-9_]{1,64}', name)) return _Converted.kept('effect $effect has no particle key');
  final JObject arguments = JObject()..addString('particle', 'minecraft:$name');
  if (count != null) arguments.addInt('count', count);
  if (spread != null) arguments.addDouble('spread', spread);
  if (place == 'player') {
    arguments.addString('at', 'player');
  } else if (!trigger) {
    arguments.addString('audience', 'nearby');
  }
  return _Converted.of(<JObject>[_action('animus:spawn-particle', arguments)]);
}

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
          steps.addAll(chain(items, index, end, scope));
          index = end - 1;
        case 'else':
          preserveTree(scope, item, 'else without a matching if');
        case 'ratelimit':
          if (item.children != null) {
            preserveTree(scope, item, 'no Animus equivalent for the ratelimit block');
            continue;
          }
          final _Converted converted = ratelimit(item, command, scope);
          converter.outcome(scope, item, 'command', 'ratelimit', converted.reason.isEmpty, converted.reason);
          steps.addAll(converted.steps);
        case 'repeat':
        case 'foreach':
        case 'while':
          if (item.children == null) {
            steps.addAll(control(item, command, scope));
          } else {
            steps.addAll(loop(item, command, scope));
          }
        default:
          if (item.children != null) {
            preserveTree(scope, item, 'no Animus equivalent for the ${command.name} block');
            continue;
          }
          final _Pending pending = _Pending();
          final _Converted converted = simple(command, scope, pending);
          converter.outcome(scope, item, 'command', command.name, converted.reason.isEmpty, converted.reason);
          if (converted.reason.isEmpty) {
            steps
              ..addAll(pending.steps)
              ..addAll(converted.steps);
          }
      }
    }
    return steps;
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
      converter.outcome(scope, item, 'command', 'determine', false, 'determine takes one value');
      return <JObject>[node];
    }
    final _Pending pending = _Pending();
    if (command.args.length == 1) {
      final _Result<JElement> value = expressions.operand(_raw(command.args.first), scope, pending);
      if (value.failed) {
        converter.outcome(scope, item, 'command', 'determine', false, value.reason!);
        return <JObject>[node];
      }
      node.add('value', value.value);
    }
    converter.outcome(scope, item, 'command', 'determine', true, '');
    return <JObject>[...pending.steps, node];
  }

  // ---- Chains ----

  /// An if/else-if/else chain or a choose block as nested condition nodes.
  List<JObject> chain(List<DenizenItem> items, int start, int end, _Scope scope) {
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
      return <JObject>[];
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

  List<JObject> loop(DenizenItem item, DenizenCommand command, _Scope scope) {
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
      return <JObject>[];
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
  List<JObject> control(DenizenItem item, DenizenCommand command, _Scope scope) {
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
      return <JObject>[];
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
        'wait' => _waitFor(command),
        'flag' => flag(command, scope, pending),
        'define' => define(command, scope, pending),
        'zap' => zap(command, scope),
        'run' || 'inject' => call(command, scope, pending),
        'execute' => execute(command, scope, pending),
        'give' => give(command, scope, pending),
        'take' => take(command, scope, pending),
        'look' => scope.server ? const _Converted.kept(_noNpc) : _look(command),
        'walk' => scope.server ? const _Converted.kept(_noNpc) : _walk(command),
        'equip' => scope.server ? const _Converted.kept(_noNpc) : equip(command),
        'trait' => scope.server ? const _Converted.kept(_noNpc) : _trait(command),
        'playsound' => sound(command, scope),
        'playeffect' => _particle(command, scope),
        'actionbar' => actionbar(command, scope, pending),
        'title' => title(command, scope, pending),
        'teleport' => teleport(command, scope),
        'adjust' => adjust(command, scope, pending),
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
    for (int index = 0; index < args.length && at < 0; index++) {
      final DenizenArg arg = args[index];
      final bool targets = arg.prefix == 'targets' && !_playerTarget(arg.value) && !(command.name == 'narrate' && _everyone(arg.value));
      final bool place = command.name == 'playsound' &&
          arg.prefix.isEmpty &&
          arg.value.startsWith('<') &&
          _at(arg.value) == null &&
          !arg.value.toLowerCase().contains('location') &&
          _location(arg.value) == null;
      if (targets || place) at = index;
    }
    if (at < 0) return null;
    final String value = args[at].value;
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
    replaced[at] = DenizenArg(args[at].prefix, '<player>', false);
    final _Converted single = simple(DenizenCommand(command.name, replaced), inner, body);
    if (single.reason.isNotEmpty) return single;
    for (final JObject step in single.steps) {
      final JElement? capability = step.get('capability');
      if (capability == null || !_playerActions.contains(capability.asPrimitive.asString)) {
        return _Converted.kept('${command.name} for other players has no Animus equivalent');
      }
      step.object('arguments')!.add('player', JNull.instance);
      final JObject filled = step.object('expressions') ?? JObject();
      filled.addString('/player', variable);
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
    if (!text.value!.isDynamic && javaIsBlank(text.value!.literal!)) return const _Converted.kept('nothing to say');
    final _Action action = _Action()..text('text', text.value!);
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

  _Converted flag(DenizenCommand command, _Scope scope, _Pending pending) {
    if (command.args.length < 2) return const _Converted.kept('flag needs a target and a name');
    final String target = command.args.first.value.toLowerCase().replaceAll('<', '').replaceAll('>', '');
    if (command.args.first.prefix.isNotEmpty || !<String>['player', 'npc', 'server'].contains(target)) {
      return const _Converted.kept('flags on other objects have no Animus equivalent');
    }
    if (target == 'npc' && scope.server) return const _Converted.kept('flag npc needs an NPC; $_noNpc');
    int? expire;
    for (int index = 2; index < command.args.length; index++) {
      final DenizenArg arg = command.args[index];
      if (arg.prefix != 'expire' && arg.prefix != 'duration') return _Converted.kept('flag argument ${_raw(arg)} has no Animus equivalent');
      final int? ticks = denizenTicks(arg.value);
      if (ticks == null) return const _Converted.kept('flag expire needs a literal duration');
      final int seconds = (ticks + 19) ~/ 20 > 1 ? (ticks + 19) ~/ 20 : 1;
      if (seconds > 31536000) return const _Converted.kept('flag expire longer than a year');
      expire = seconds;
    }
    final String rawName = _raw(command.args[1]);
    final int colon = rawName.indexOf(':');
    final String denizen = (colon < 0 ? rawName : rawName.substring(0, colon)).toLowerCase();
    final String value = colon < 0 ? '' : rawName.substring(colon + 1);
    // A name joined with tags, such as npc_done_<npc.id>, is computed as the run writes it, like the reads of it.
    final _Result<String> named = denizenSegments(denizen).any((DenizenSegment segment) => segment.tag) && !denizen.startsWith('<')
        ? expressions.flagNameExpression(denizen, scope, pending)
        : const _Result<String>.fail('');
    final String? name = _flagName(denizen);
    if (named.failed && (denizen.contains('<') || denizen.contains('[') || name == null)) {
      return _Converted.kept('flag name $denizen cannot be an Animus flag name');
    }
    final String nameExpression = named.failed ? _quote(name!) : named.value!;
    final String scopeName = _scopeOf(target);
    final _Action action = _Action();
    if (named.failed) {
      action.put('name', JString(name!));
    } else {
      action.expression('name', nameExpression);
    }
    action.put('scope', JString(scopeName));
    if (colon >= 0 && value == '!') {
      if (expire != null) return const _Converted.kept('clearing a flag takes no expire');
      action.put('clear', const JBool(true));
      return _Converted.of(<JObject>[action.node('animus:set-flag')]);
    }
    if (colon >= 0) {
      final String read = 'flag(${_quote(scopeName)}, $nameExpression, ';
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
    return _Converted.of(<JObject>[action.node('animus:set-flag')]);
  }

  _Converted define(DenizenCommand command, _Scope scope, _Pending pending) {
    if (command.args.isEmpty) return const _Converted.kept('define without a name');
    final DenizenArg first = command.args.first;
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
      final _Result<String> computed = dataAction(value, variable, 'num($variable)', scope, pending);
      if (computed.failed) return _Converted.kept(computed.reason!);
      if (computed.value != null) {
        set.add('value', JObject()..addString('expression', computed.value!));
        return _Converted.of(<JObject>[set]);
      }
    }
    final _Result<JElement> operand = expressions.operand(value, scope, pending);
    if (operand.failed) return _Converted.kept(operand.reason!);
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
          if (arg.value.toLowerCase() != '<npc>') return const _Converted.kept('running for another NPC has no Animus equivalent');
        case 'player':
          if (arg.value.toLowerCase() != '<player>') return const _Converted.kept('running for another player has no Animus equivalent');
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
    if (name != null && name.contains('<')) return dynamic(name, path, save, positional, named, scope, pending);
    final DenizenContainer? task;
    if (name == null && path != null && scope.container.type == 'task') {
      task = scope.container;
    } else {
      if (name == null) return const _Converted.kept('running a computed script has no Animus equivalent');
      task = converter.containers[name.toLowerCase()];
    }
    if (task == null) return _Converted.kept('no script named ${name ?? ''}');
    return callTask(task, path, save, positional, named, scope, pending);
  }

  /// `run <npc.flag[name]>`: the script the flag names, chosen when the line runs from the task scripts that a `flag`
  /// command or an NPC's saved flags give that flag. A value naming none of them runs nothing.
  _Converted dynamic(String raw, String? path, String? save, List<String> positional, Map<String, String> named, _Scope scope, _Pending pending) {
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
      final _Converted call = callTask(task, null, null, positional, named, scope, pending);
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

  _Converted callTask(DenizenContainer task, String? path, String? save, List<String> positional, Map<String, String> named, _Scope scope, _Pending pending) {
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
    final String routine = converter.routineFor(task, path);
    if (scope.calling.contains(routine)) return const _Converted.kept('recursive task calls have no Animus equivalent');
    final JObject call = _plain('call')..addString('routine', routine);
    if (arguments.isNotEmpty) call.add('arguments', arguments);
    if (save != null) {
      final String result = _variable('r_', save);
      converter.declare(scope, result, 'any');
      call.addString('result', result);
    }
    if (!scope.doc.routines.containsKey(routine)) {
      if (scope.doc.routines.length >= behaviorMaximumRoutines) return const _Converted.kept('more than 32 routines in one behavior');
      scope.doc.routines[routine] = JObject();
      scope.calling.add(routine);
      final List<JObject> steps = block(body.items, 0, scope.inContainer(task));
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
    final List<String> words = <String>[];
    for (final DenizenArg arg in command.args) {
      if (arg.isWord('as_server') || arg.isWord('as_op')) {
        as = 'console';
        continue;
      }
      if (arg.isWord('as_player')) {
        as = 'player';
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
    if (split.first.toLowerCase() == 'sentinel') return _sentinel(split.sublist(1), scope);
    final _Result<_Text> text = this.text(line, scope, pending, true, 1024);
    if (text.failed) return _Converted.kept(text.reason!);
    if (!text.value!.isDynamic && javaIsBlank(text.value!.literal!)) return const _Converted.kept('command is empty');
    final _Action action = _Action()..text('command', text.value!);
    if (as == 'player') action.put('as', const JString('player'));
    return _Converted.of(<JObject>[action.node('animus:command')]);
  }

  /// A `/sentinel` command. Sentinel is replaced by animus:combat, so the forms that act on the NPC become
  /// combat-control on the behavior's own NPC; target types and Sentinel settings are kept with the reason.
  _Converted _sentinel(List<String> words, _Scope scope) {
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
    final _Action action = _Action();
    switch (operation) {
      case 'addtarget':
      case 'removetarget':
        if (!player) {
          return _Converted.kept("sentinel ${rest.join(' ')}: target types are animus:combat settings; set them in the NPC's animus:combat targets");
        }
        // Taking the player off the target list stops the attack on them; combat-control releases the current target.
        action.put('operation', JString(operation == 'addtarget' ? 'target' : 'release'));
        if (operation == 'addtarget') action.put('target', const JString('player'));
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

  _Converted sound(DenizenCommand command, _Scope scope) {
    String? sound;
    String? place;
    bool custom = false;
    double? volume;
    double? pitch;
    for (final DenizenArg arg in command.args) {
      if (arg.isWord('custom')) {
        custom = true;
        continue;
      }
      switch (arg.prefix) {
        case 'sound':
          sound = arg.value;
        case 'volume':
          volume = _number(arg.value, 0, 4);
          if (volume == null) return const _Converted.kept('volume must be a number from 0 to 4');
        case 'pitch':
          pitch = _number(arg.value, 0.5, 2);
          if (pitch == null) return const _Converted.kept('pitch must be a number from 0.5 to 2');
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
    if (sound == null || sound.contains('<')) return const _Converted.kept('playsound needs a literal sound');
    if (place == 'npc' && scope.server) return const _Converted.kept('a sound at the NPC needs an NPC; $_noNpc');
    final String? key = denizenSoundKey(sound, custom);
    if (key == null) return _Converted.kept('sound $sound has no resource key');
    converter.sounds[sound] = key;
    final JObject arguments = JObject()..addString('sound', key);
    if (volume != null) arguments.addDouble('volume', volume);
    if (pitch != null) arguments.addDouble('pitch', pitch);
    if (place == 'player' || scope.server) {
      arguments.addString('at', 'player');
    } else if (place == 'npc') {
      arguments.addString('audience', 'nearby');
    }
    return _Converted.of(<JObject>[_action('animus:play-sound', arguments)]);
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

  _Converted teleport(DenizenCommand command, _Scope scope) {
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
      if (at == null || extras == null) return const _Converted.kept('teleport destination must be the player, the NPC or a literal location');
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

  /// Mechanisms with an Animus action: the NPC's name and held or worn items, and showing a book script to the player.
  _Converted adjust(DenizenCommand command, _Scope scope, _Pending pending) {
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
