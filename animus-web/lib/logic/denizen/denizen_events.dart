// A line-for-line copy of core's `DenizenEvents`: world script event names as server triggers. An event such as
// `on player chats message:hello*` becomes the trigger fields (`"event": "player_chat", "pattern": "hello*"`), the
// conditions its other switches add around the routine (`flagged:`, `permission:`, `in:`), and the `context.*` keys
// its script may read. A right click on an NPC, and a hit on one, is an NPC event instead: an `interaction` trigger of a
// behavior bound to every NPC, whose run reads the NPC as `context.entity`. Area events take their bounds from the
// noted areas. An event or switch without an Animus equivalent gives the reason instead.
part of 'denizen_converter.dart';

/// A mapped event: trigger fields without id and routine, wrapping conditions, readable context keys, whether it is
/// an NPC event and whether its platform event can be cancelled; or a reason. The context key `entity` maps to `npc` on
/// NPC events: the clicked entity is the NPC.
class _Mapped {
  const _Mapped(this.trigger, this.guards, this.context, this.npc, this.cancellable, this.reason);

  const _Mapped.fail(String this.reason)
      : trigger = null,
        guards = const <_Cond>[],
        context = const <String, String>{},
        npc = false,
        cancellable = false;

  final JObject? trigger;
  final List<_Cond> guards;
  final Map<String, String> context;
  final bool npc;
  final bool cancellable;
  final String? reason;
}

/// A noted area: a cuboid's two corners or an ellipsoid's centre and radii, in one world.
class _Note {
  const _Note(this.name, this.world, this.cuboid, this.bounds);

  final String name;
  final String world;
  final bool cuboid;
  final List<double> bounds;
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

/// Denizen damage causes as vanilla damage type patterns.
const Map<String, String> _causes = <String, String>{
  'fall': 'fall', 'lava': 'lava', 'fire': 'in_fire', 'fire_tick': 'on_fire', 'drowning': 'drown', 'entity_attack': '*_attack',
  'void': 'out_of_world', 'starvation': 'starve', 'suffocation': 'in_wall', 'lightning': 'lightning_bolt', 'magic': 'magic',
  'wither': 'wither', 'contact': 'cactus', 'block_explosion': '*explosion', 'entity_explosion': '*explosion', 'freeze': 'freeze',
  'hot_floor': 'hot_floor', 'cramming': 'cramming', 'fly_into_wall': 'fly_into_wall', 'dragon_breath': 'dragon_breath',
  'thorns': 'thorns', 'sonic_boom': 'sonic_boom', 'campfire': 'campfire', 'projectile': 'arrow', 'poison': 'magic',
};
const String _eventName = '[a-z0-9_*]{1,64}';

/// The `context` key that lets `<player.target>` read the NPC; no Denizen context name contains a dot.
const String _denizenTarget = 'player.target';

/// [key] with the areas Denizen notes; [npcEntity] says the script treats the entity as an NPC (`is_npc`), and
/// [npcTarget] that it treats the player's target as one, which makes the drop and swap-hands keys NPC interaction
/// triggers.
_Mapped _denizenEvent(String key, Map<String, _Note> notes, bool npcEntity, bool npcTarget) {
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
  bool npc = false;
  bool cancellable = false;
  final String event;
  final List<String> w = words;
  switch (phrase) {
    case 'player drops item' || 'player swaps items' when npcTarget:
      event = 'interaction';
      npc = true;
      trigger.addString('action', phrase == 'player drops item' ? 'drop' : 'swap_hands');
      context[_denizenTarget] = 'npc';
      context['sneaking'] = 'sneaking';
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
    case 'player right clicks entity':
    case 'player right clicks npc':
      event = 'interaction';
      npc = true;
      trigger.addString('action', 'interact');
      context['entity'] = 'npc';
      context['hand'] = 'hand';
      context['sneaking'] = 'sneaking';
    case 'player damages npc':
    case 'npc damaged by player':
      event = 'interaction';
      npc = true;
      trigger.addString('action', 'attack');
      context['entity'] = 'npc';
      context['sneaking'] = 'sneaking';
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
      } else if (w.length == 3 && w[0] == 'player' && (w[1] == 'enters' || w[1] == 'exits')) {
        final _Note? note = notes[w[2]];
        if (note == null) {
          return _Mapped.fail(<String>['area', 'cuboid', 'ellipsoid', 'polygon'].contains(w[2])
              ? 'an event for every area has no Animus equivalent; name the noted area'
              : 'area ${w[2]} is not a noted cuboid or ellipsoid');
        }
        if (!_full('[a-z0-9][a-z0-9_-]{0,63}', w[2])) return _Mapped.fail('area name ${w[2]} cannot be an Animus area name');
        event = 'player_area';
        trigger.addString('phase', w[1] == 'enters' ? 'enter' : 'leave');
        trigger.addString('area', w[2]);
        trigger.addString('world', note.world);
        final JArray bounds = JArray();
        for (final double value in note.bounds) {
          bounds.add(value == value.roundToDouble() && value.abs() < 1e15 ? JInt.of(value.toInt()) : JDouble(value));
        }
        trigger.add(note.cuboid ? 'cuboid' : 'ellipsoid', bounds);
        context['area'] = 'area';
        context['cause'] = 'cause';
      } else if (w.length == 3 && w[0] == 'player' && (w[1] == 'opens' || w[1] == 'closes')) {
        event = w[1] == 'opens' ? 'player_inventory_open' : 'player_inventory_close';
        cancellable = w[1] == 'opens';
        if (w[2] != 'inventory') {
          if (!_full(_eventName, w[2])) return _Mapped.fail('inventory type ${w[2]} has no Animus equivalent');
          trigger.addString('inventory', w[2]);
        }
        context['inventory'] = 'inventory_type';
      } else if (w.length >= 3 &&
          w[0] == 'player' &&
          w[1] == 'clicks' &&
          w[w.length - 1] == 'inventory' &&
          (w.length == 4 && w[2] == 'in' || w.length == 5 && w[3] == 'in')) {
        event = 'player_inventory_click';
        cancellable = true;
        if (w.length == 5 && w[2] != 'item') {
          if (!_full(_eventName, w[2])) return _Mapped.fail('item ${w[2]} has no Animus equivalent');
          trigger.addString('item', w[2]);
        }
        for (final String name in <String>['click', 'slot', 'item']) {
          context[name] = name;
        }
        context['raw_slot'] = 'slot';
        context['inventory'] = 'inventory_type';
      } else if (w.length == 4 && w[0] == 'player' && w[1] == 'picks' && w[2] == 'up' ||
          w.length == 3 && w[0] == 'player' && (w[1] == 'drops' || w[1] == 'consumes')) {
        final String item = w[w.length - 1];
        event = w[1] == 'drops'
            ? 'player_item_drop'
            : w[1] == 'consumes'
                ? 'player_item_consume'
                : 'player_item_pickup';
        cancellable = true;
        if (item != 'item') {
          if (!_full(_eventName, item)) return _Mapped.fail('item $item has no Animus equivalent');
          trigger.addString('item', item);
        }
        context['item'] = 'item';
      } else if (w.length >= 3 &&
          w[0] == 'player' &&
          (w.length == 3 && w[1] == 'clicks' || w.length == 4 && (w[1] == 'right' || w[1] == 'left') && w[2] == 'clicks')) {
        final String block = w[w.length - 1];
        if (<String>['entity', 'npc', 'player', 'item', 'in'].contains(block)) return _Mapped.fail('no Animus server trigger for the event $phrase');
        event = 'player_block_click';
        cancellable = true;
        if (w.length == 4) trigger.addString('click', w[1]);
        if (block != 'block') {
          if (!_full(_eventName, block)) return _Mapped.fail('block $block has no Animus equivalent');
          trigger.addString('block', block);
        }
        context['location'] = 'location';
        context['hand'] = 'hand';
        context['click_type'] = 'click';
      } else if (w.length == 3 && w[0] == 'player' && (w[1] == 'places' || w[1] == 'breaks')) {
        event = w[1] == 'places' ? 'player_block_place' : 'player_block_break';
        cancellable = true;
        if (w[2] != 'block') {
          if (!_full(_eventName, w[2])) return _Mapped.fail('block ${w[2]} has no Animus equivalent');
          trigger.addString('block', w[2]);
        }
        context['location'] = 'location';
        context['material'] = 'block';
      } else if (w.length >= 2 && w[0] == 'player' && w[1] == 'damaged' && (w.length == 2 || w.length == 4 && w[2] == 'by')) {
        event = 'player_damaged';
        cancellable = true;
        if (w.length == 4 && w[3] != 'entity') {
          if (!_full(_eventName, w[3])) return _Mapped.fail('damage cause ${w[3]} has no Animus equivalent');
          final String? cause = _causes[w[3]];
          if (cause != null) {
            trigger.addString('cause', cause);
          } else {
            trigger.addString('damager', w[3]);
          }
        }
        context['damage'] = 'damage';
        context['final_damage'] = 'damage';
        context['cause'] = 'cause';
        context['damager'] = 'damager';
      } else if (w.length == 3 && w[0] == 'player' && w[1] == 'damages' || w.length == 4 && w[1] == 'damaged' && w[2] == 'by' && w[3] == 'player') {
        final String entity = w[1] == 'damages' ? w[2] : w[0];
        if (npcEntity) {
          event = 'interaction';
          npc = true;
          trigger.addString('action', 'attack');
          context['entity'] = 'npc';
          context['sneaking'] = 'sneaking';
        } else {
          event = 'player_damage_entity';
          cancellable = true;
          if (entity != 'entity') {
            if (!_full(_eventName, entity)) return _Mapped.fail('entity $entity has no Animus equivalent');
            trigger.addString('entity', entity);
          }
          context['entity'] = 'entity';
          context['damage'] = 'damage';
          context['final_damage'] = 'damage';
          context['cause'] = 'cause';
        }
      } else if (w.length == 3 && w[0] == 'player' && w[1] == 'kills' || w.length == 4 && w[1] == 'killed' && w[2] == 'by' && w[3] == 'player') {
        final String entity = w[1] == 'kills' ? w[2] : w[0];
        if (entity == 'npc') return const _Mapped.fail('NPC deaths are NPC death triggers; Animus binds a behavior to each NPC instead');
        event = 'player_kill';
        if (entity != 'entity') {
          if (!_full(_eventName, entity)) return _Mapped.fail('entity $entity has no Animus equivalent');
          trigger.addString('entity', entity);
        }
        context['entity'] = 'entity';
      } else if (_projectileForm(w)) {
        final _Form form = _projectile(w, trigger, context);
        final String? reason = form.reason;
        if (reason != null) return _Mapped.fail(reason);
        event = form.event!;
        cancellable = true;
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
  final bool inventory = event.startsWith('player_inventory_');
  final bool projectile = event.startsWith('projectile_') || event == 'entity_shoot_bow';
  final List<_Cond> guards = <_Cond>[];
  for (final List<String> option in switches.values) {
    final String name = option[0];
    final String value = option[1];
    switch (name) {
      case 'every':
        if (event != 'timer') return const _Mapped.fail('switch every: belongs to delta time');
      case 'priority':
      case 'bukkit_priority':
      case 'ignorecancelled':
        // Animus has one order, and its triggers see no cancelled events.
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
      case 'with':
        if (event == 'entity_shoot_bow') {
          if (!_full(_eventName, value)) return _Mapped.fail('switch with:$value names a bow Animus cannot check');
          fields.addString('bow', value);
          break;
        }
        if (event != 'player_block_click' && event != 'interaction') return const _Mapped.fail('switch with: has no Animus equivalent on this event');
        if (!_full('[a-z0-9_]{1,64}', value)) return _Mapped.fail('switch with:$value names an item Animus cannot check');
        guards.add(_Atom('same(player.item_in_hand, ${_quote(value == 'air' ? 'minecraft:air' : 'minecraft:$value')})'));
      case 'click':
        if (event != 'player_inventory_click' || !_full(_eventName, value)) return _Mapped.fail('switch click:$value has no Animus equivalent');
        fields.addString('click', value);
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
        if (inventory) {
          if (!_full(_eventName, value)) return _Mapped.fail('switch in:$value names an inventory Animus cannot check');
          if (value != 'inventory') fields.addString('inventory', value);
          break;
        }
        if (!player) return _Mapped.fail('switch in: needs a player; $event has none');
        if (!_full('[a-z0-9_-]{1,64}', value)) return _Mapped.fail('switch in:$value names an area Animus cannot check');
        if (notes.containsKey(value)) return _Mapped.fail('switch in:$value names a noted area; Animus checks areas only as enter and exit events');
        guards.add(_Atom('same_world(${projectile ? 'context.world' : 'player.world'}, ${_quote(value)})'));
      case 'entity':
      case 'block':
      case 'shooter':
        if (event != 'projectile_hit' && !(name == 'shooter' && projectile)) return _Mapped.fail('switch $name: has no Animus equivalent on this event');
        if (value == 'npc') return const _Mapped.fail(_npcProjectileTarget);
        if (!_full(_eventName, value)) return _Mapped.fail('switch $name:$value names a type Animus cannot check');
        fields.addString(name, value);
      default:
        return _Mapped.fail('event switch $name: has no Animus equivalent');
    }
  }
  return _Mapped(fields, guards, context, npc, cancellable || npc, null);
}

// ---- Projectiles ----

/// A projectile event's trigger name, or the reason it has none.
class _Form {
  const _Form(this.event, this.reason);

  final String? event;
  final String? reason;
}

const String _npcProjectileShooter = 'Animus NPCs shoot combat helper projectiles, which fire no server triggers';
const String _npcProjectileTarget = 'Animus NPCs are virtual bodies that real projectiles pass through';

/// `<projectile> launched`, `<projectile> hits [<target>]`, `<projectile> collides with <entity>` and `<entity> shoots <bow or block>`.
bool _projectileForm(List<String> w) =>
    w.length == 2 && (w[1] == 'launched' || w[1] == 'hits') ||
    w.length == 3 && (w[1] == 'hits' || w[1] == 'shoots') ||
    w.length == 4 && w[1] == 'collides' && w[2] == 'with';

/// A projectile event as `projectile_launch`, `projectile_hit` or `entity_shoot_bow`. Denizen's older
/// `<entity> shoots <block>` and `<projectile> hits <block>` are hits on a block; `*:*` filters for any hit entity or block.
_Form _projectile(List<String> w, JObject trigger, Map<String, String> context) {
  if (w[0] == 'npc') return const _Form(null, _npcProjectileShooter);
  String? reason;
  if (w[1] == 'shoots' && (w[2] == 'item' || w[2].endsWith('bow'))) {
    reason = _projectileFilter(trigger, 'shooter', w[0], 'entity') ?? _projectileFilter(trigger, 'bow', w[2], 'item');
    if (reason != null) return _Form(null, reason);
    context['entity'] = 'shooter';
    for (final String key in <String>['bow', 'projectile', 'force', 'hand']) {
      context[key] = key;
    }
    return const _Form('entity_shoot_bow', null);
  }
  reason = w[1] == 'shoots'
      ? _projectileFilter(trigger, 'shooter', w[0], 'entity')
      : w[0] == 'entity'
          ? null
          : _projectileFilter(trigger, 'projectile', w[0], 'projectile');
  if (reason != null) return _Form(null, reason);
  context['projectile'] = 'projectile';
  context['shooter'] = 'shooter';
  if (w[1] == 'launched') {
    context['entity'] = 'projectile';
    return const _Form('projectile_launch', null);
  }
  if (w.length > 2) {
    final String target = w[w.length - 1];
    final bool entity = w[1] == 'collides' || target == 'entity' || target == 'player';
    if (target == 'npc') return const _Form(null, _npcProjectileTarget);
    if (!_full(_eventName, target)) return _Form(null, '${entity ? 'entity ' : 'block '}$target has no Animus equivalent');
    trigger.addString(entity ? 'entity' : 'block', target == 'entity' || target == 'block' ? '*:*' : target);
    if (w[1] == 'collides') context['entity'] = 'entity';
  }
  context['hit_entity'] = 'entity';
  context['hit_block'] = 'location';
  context['location'] = 'location';
  return const _Form('projectile_hit', null);
}

/// Adds the filter [field] unless [value] is the word for any.
String? _projectileFilter(JObject trigger, String field, String value, String any) {
  if (value == any) return null;
  if (!_full(_eventName, value)) return '$field $value has no Animus equivalent';
  trigger.addString(field, value);
  return null;
}

/// The context keys of a command script's run.
Map<String, String> _commandContext() {
  final SplayTreeMap<String, String> context = SplayTreeMap<String, String>();
  for (final String key in <String>['alias', 'args', 'command', 'raw_args']) {
    context[key] = key;
  }
  return context;
}

// ---- Notes ----

/// The cuboids and ellipsoids Denizen's `notables.yml` keeps: `cu@world,x1,y1,z1,x2,y2,z2` (or the older
/// `cu@x,y,z,world|x,y,z,world`) and `ellipsoid@x,y,z,world,rx,ry,rz`, by lowercase name. Cuboids with more than one box
/// and other notables are skipped with an issue each.
SplayTreeMap<String, _Note> _notables(JObject root, List<String> issues) {
  final SplayTreeMap<String, _Note> notes = SplayTreeMap<String, _Note>();
  for (final MapEntry<String, JElement> section in root.entries) {
    final String kind = section.key.toLowerCase();
    final JElement sectionValue = section.value;
    if (sectionValue is! JObject || kind != 'cuboids' && kind != 'ellipsoids') continue;
    for (final MapEntry<String, JElement> entry in sectionValue.entries) {
      final String name = entry.key.toLowerCase();
      final JElement value = entry.value;
      final JElement? written = value is JObject ? value.get('object') : value;
      final String text = written is JPrimitive ? written.asString : '';
      final _Note? note = _note(name, text);
      if (note == null) {
        issues.add('notables.yml: ${kind.substring(0, kind.length - 1)} $name is not one box or one ellipsoid with literal numbers; its area events are kept');
      } else {
        notes[name] = note;
      }
    }
  }
  return notes;
}

const String _noteWorld = '[A-Za-z0-9_./-]{1,64}';

/// A literal cuboid or ellipsoid: `cu@...`, `ellipsoid@...`, `cuboid[...]` or `ellipsoid[...]`.
_Note? _note(String name, String raw) {
  String text = javaTrim(raw);
  String lowered = text.toLowerCase();
  if (lowered.startsWith('<') && lowered.endsWith(']>')) {
    final int open = text.indexOf('[');
    if (open < 0) return null;
    final String base = lowered.substring(1, open);
    final String prefix = base == 'cuboid'
        ? 'cu@'
        : base == 'ellipsoid'
            ? 'ellipsoid@'
            : '?@';
    text = prefix + text.substring(open + 1, text.length - 2);
    lowered = text.toLowerCase();
  }
  if (lowered.startsWith('cu@')) {
    final String body = text.substring(3);
    final List<String> parts;
    final String world;
    if (body.contains('|')) {
      final List<String> corners = body.split('|');
      if (corners.length != 2) return null;
      final List<String> a = corners[0].split(',');
      final List<String> b = corners[1].split(',');
      if (a.length != 4 || b.length != 4 || a[3] != b[3]) return null;
      world = a[3];
      parts = <String>[a[0], a[1], a[2], b[0], b[1], b[2]];
    } else {
      final List<String> all = body.split(',');
      if (all.length != 7) return null;
      world = all[0];
      parts = all.sublist(1, 7);
    }
    final List<double>? bounds = _noteNumbers(parts);
    if (bounds == null || !_full(_noteWorld, world)) return null;
    return _Note(name, world, true, bounds);
  }
  if (lowered.startsWith('ellipsoid@')) {
    final List<String> all = text.substring(10).split(',');
    if (all.length != 7 || !_full(_noteWorld, all[3])) return null;
    final String world = all[3];
    final List<double>? bounds = _noteNumbers(<String>[all[0], all[1], all[2], all[4], all[5], all[6]]);
    if (bounds == null) return null;
    if (!(bounds[3] >= 0.1 && bounds[4] >= 0.1 && bounds[5] >= 0.1 && bounds[3] <= 100000 && bounds[4] <= 100000 && bounds[5] <= 100000)) return null;
    return _Note(name, world, false, bounds);
  }
  return null;
}

List<double>? _noteNumbers(List<String> parts) {
  final List<double> values = <double>[];
  for (final String raw in parts) {
    final String part = javaTrim(raw);
    if (!_full(r'-?[0-9]{1,8}(?:\.[0-9]{1,20})?', part)) return null;
    final double value = javaRound(javaParseDouble(part) * 1000).toDouble() / 1000.0;
    if (value.abs() > 1000000) return null;
    values.add(value);
  }
  return values;
}
