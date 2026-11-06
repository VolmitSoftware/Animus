// A line-for-line copy of core's `DenizenEvents`: world script event names as server triggers. An event such as
// `on player chats message:hello*` becomes the trigger fields (`"event": "player_chat", "pattern": "hello*"`), the
// conditions its other switches add around the routine (`flagged:`, `permission:`, `in:`), and the `context.*` keys
// its script may read. An event or switch without an Animus equivalent gives the reason instead.
part of 'denizen_converter.dart';

/// A mapped event: trigger fields without id and routine, wrapping conditions, readable context keys; or a reason.
class _Mapped {
  const _Mapped(this.trigger, this.guards, this.context, this.reason);

  const _Mapped.fail(String this.reason)
      : trigger = null,
        guards = const <_Cond>[],
        context = const <String, String>{};

  final JObject? trigger;
  final List<_Cond> guards;
  final Map<String, String> context;
  final String? reason;
}

List<String> _eventTokens(String key) => javaSplit(_collapse(key), ' ');

/// The phrase of an event key without its `on`/`after` word and switches, for routine names and reports.
String _eventPhrase(String key) {
  final List<String> words = <String>[];
  final List<String> tokens = _eventTokens(key);
  for (int index = 0; index < tokens.length; index++) {
    if (index == 0 && (tokens[0] == 'on' || tokens[0] == 'after')) continue;
    if (!_switchToken(tokens[index])) words.add(tokens[index]);
  }
  return words.join(' ');
}

bool _switchToken(String token) => _full('[a-z_]+:.+', token);

_Mapped _denizenEvent(String key) {
  final List<String> tokens = _eventTokens(key);
  if (tokens.isEmpty || tokens[0] != 'on' && tokens[0] != 'after') return const _Mapped.fail('world events start with on or after');
  final List<String> words = <String>[];
  final SplayTreeMap<int, List<String>> switches = SplayTreeMap<int, List<String>>();
  for (int index = 1; index < tokens.length; index++) {
    final String token = tokens[index];
    if (_switchToken(token)) {
      switches[index] = <String>[token.substring(0, token.indexOf(':')), token.substring(token.indexOf(':') + 1)];
    } else {
      words.add(token);
    }
  }
  final String phrase = words.join(' ');
  final JObject trigger = JObject();
  final SplayTreeMap<String, String> context = SplayTreeMap<String, String>();
  bool player = true;
  final String event;
  switch (phrase) {
    case 'player joins':
    case 'player join':
    case 'player logs in':
      event = 'player_join';
    case 'player quits':
    case 'player quit':
      event = 'player_quit';
    case 'player chats':
      event = 'player_chat';
      context['message'] = 'message';
    case 'server start':
    case 'server starts':
      event = 'server_start';
      player = false;
    case 'player dies':
    case 'player death':
      event = 'player_death';
      context['message'] = 'message';
    case 'player respawns':
      event = 'player_respawn';
    case 'delta time secondly':
    case 'delta time minutely':
    case 'delta time hourly':
      event = 'timer';
      player = false;
    default:
      if (phrase.startsWith('player changes world')) {
        event = 'player_world_change';
        final List<String> rest = javaSplit(javaTrim(phrase.substring('player changes world'.length)), ' ');
        for (int index = 0; index + 1 < rest.length; index += 2) {
          if (rest[index] != 'from' && rest[index] != 'to' || !_full('[a-z0-9_./-]{1,64}', rest[index + 1])) {
            return _Mapped.fail('world change form $phrase has no Animus equivalent');
          }
          trigger.addString(rest[index], rest[index + 1]);
        }
        if (rest.length % 2 == 1 && rest[0].isNotEmpty) return _Mapped.fail('world change form $phrase has no Animus equivalent');
        context['origin_world'] = 'from';
        context['destination_world'] = 'to';
      } else if (phrase.contains('npc')) {
        return const _Mapped.fail('this event covers every NPC; Animus binds a behavior to each NPC instead');
      } else {
        return _Mapped.fail('no Animus server trigger for the event $phrase');
      }
  }
  final JObject fields = JObject()..addString('event', event);
  if (event == 'timer') {
    int every = 1;
    for (final List<String> option in switches.values) {
      if (option[0] == 'every') {
        if (!_full('[0-9]{1,5}', option[1])) return _Mapped.fail('every:${option[1]} needs a whole number');
        every = int.parse(option[1]);
      }
    }
    final int unit = phrase.endsWith('secondly')
        ? 20
        : phrase.endsWith('minutely')
            ? 1200
            : 72000;
    final int ticks = every * unit;
    if (ticks < 20 || ticks > 72000) return _Mapped.fail('a timer every $ticks ticks is outside 20 to 72000');
    fields.addInt('intervalTicks', ticks);
  }
  for (final MapEntry<String, JElement> field in trigger.entries) {
    fields.add(field.key, field.value);
  }
  final List<_Cond> guards = <_Cond>[];
  for (final List<String> option in switches.values) {
    final String name = option[0];
    final String value = option[1];
    switch (name) {
      case 'every':
        if (event != 'timer') return const _Mapped.fail('switch every: belongs to delta time');
      case 'priority':
      case 'ignorecancelled':
        // order and cancellation have no meaning without cancellation
        break;
      case 'cancelled':
        if (value != 'false') return _Mapped.fail('switch cancelled:$value has no Animus equivalent; Animus triggers see no cancelled events');
      case 'message':
        if (event != 'player_chat') return const _Mapped.fail('switch message: belongs to player chats');
        if (value.length > 256 || value.contains('<')) return _Mapped.fail('chat pattern $value has no Animus equivalent');
        fields.addString('pattern', value);
      case 'from':
      case 'to':
        if (event != 'player_world_change' || !_full('[a-z0-9_./-]{1,64}', value)) return _Mapped.fail('switch $name:$value has no Animus equivalent');
        fields.addString(name, value);
      case 'flagged':
      case 'server_flagged':
        final String? flag = _flagName(value);
        if (flag == null) return _Mapped.fail('flag name $value cannot be an Animus flag name');
        if (name == 'flagged' && !player) return _Mapped.fail('switch flagged: needs a player; $event has none');
        guards.add(_Atom('has_flag(${_quote(name == 'flagged' ? 'player' : 'global')}, ${_quote(flag)})'));
      case 'permission':
        if (!player) return _Mapped.fail('switch permission: needs a player; $event has none');
        guards.add(_Capability('animus:has-permission', JObject()..addString('permission', value)));
      case 'in':
        if (!player) return _Mapped.fail('switch in: needs a player; $event has none');
        if (!_full('[a-z0-9_-]{1,64}', value)) return _Mapped.fail('switch in:$value names an area Animus cannot check');
        guards.add(_Atom('same(player.world, ${_quote(value)})'));
      default:
        return _Mapped.fail('event switch $name: has no Animus equivalent');
    }
  }
  return _Mapped(fields, guards, context, null);
}

/// The context keys of a command script's run.
Map<String, String> _commandContext() {
  final SplayTreeMap<String, String> context = SplayTreeMap<String, String>();
  for (final String key in <String>['alias', 'args', 'command', 'raw_args']) {
    context[key] = key;
  }
  return context;
}
