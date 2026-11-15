/// Port of core's `CitizensCommandLines`: Citizens' own click command lines, read as Citizens 2.0.44's
/// `Util.runCommand` rewrites them. A line that starts with `say` is NPC speech to the clicker, and `npc`,
/// `waypoints` and `wp` lines act on the clicked NPC; each becomes the Animus action that does the same, or review.
library;

import 'dart:math' as math;

import 'java_json.dart';
import 'java_lang.dart';

sealed class CitizensLine {
  const CitizensLine();
}

/// Not one of Citizens' own lines; it runs as a command.
final class PlainLine extends CitizensLine {
  const PlainLine();
}

/// The Animus node that does the same; [note] says what differs.
final class MappedLine extends CitizensLine {
  const MappedLine(this.node, [this.note]);

  final JObject node;
  final String? note;
}

final class ReviewLine extends CitizensLine {
  const ReviewLine(this.reason);

  final String reason;
}

/// Citizens' default `npc.chat.options.range`: who hears speech with no target and no range.
const double citizensChatRange = 5;
const String _clicker = '{player_name}';
final RegExp _valueFlag = RegExp(r'^--[a-zA-Z0-9_-]+$');
final RegExp _flag = RegExp(r'^-[a-zA-Z]+$');
final RegExp _duration = RegExp(r'^(?:(\d{1,6})h)?(?:(\d{1,6})m)?(?:(\d{1,6}(?:\.\d{1,3})?)s)?$', caseSensitive: false);
final RegExp _decimal = RegExp(r'^[-+]?(\d{1,9}(\.\d{1,9})?|\.\d{1,9})$');

/// Citizens' player animations as `animus:animate`; elytra flight has no Animus animation.
const Map<String, String> citizensAnimations = <String, String>{
  'ARM_SWING': 'swing', 'ARM_SWING_OFFHAND': 'swing_offhand', 'HURT': 'hurt', 'CRIT': 'crit', 'MAGIC_CRIT': 'magic_crit', //
  'LEAVE_BED': 'wake', 'STOP_SLEEPING': 'wake', 'SLEEP': 'sleep', 'SIT': 'sit', 'STOP_SITTING': 'stand', 'SNEAK': 'sneak', //
  'STOP_SNEAKING': 'stand', 'EAT_FOOD': 'use_item', 'START_USE_MAINHAND_ITEM': 'use_item', 'START_USE_OFFHAND_ITEM': 'use_item', //
  'STOP_USE_ITEM': 'stop_using_item',
};

/// `CitizensTraits.poseName`: a Citizens pose name as an Animus one, lowercase and at most 32 characters.
String citizensPoseName(String citizens) {
  final String name = javaLower(citizens).replaceAll(RegExp(r'[^a-z0-9_-]+'), '-').replaceFirst(RegExp(r'^[^a-z0-9]+'), '');
  return name.length > 32 ? name.substring(0, 32) : name;
}

/// [text] has no leading slash and Citizens' placeholders already written as Animus ones; [clicker] when the clicking
/// player runs the line.
CitizensLine readCitizensLine(String text, bool clicker, String key) {
  if (text.startsWith('say')) return _speech(javaTrim(text.substring(3)));
  if (!text.startsWith('npc ') && !text.startsWith('waypoints ') && !text.startsWith('wp ')) return const PlainLine();
  final _Words words = _Words.of(text);
  if (words.args.first != 'npc') return const ReviewLine("waypoint commands edit Citizens' waypoints, which Animus routes replace");
  final String id = words.values['id'] ?? key;
  if (id != key) return ReviewLine('acts on NPC $id, not this one');
  final String sub = words.args.length > 1 ? javaLower(words.args[1]) : '';
  final List<String> rest = words.args.sublist(math.min(2, words.args.length));
  switch (sub) {
    case 'speak':
      return _speak(rest.join(' '), words);
    case 'tp':
    case 'teleport':
      return clicker ? MappedLine(_action('animus:teleport', _arguments('to', 'npc'))) : _console('teleport');
    case 'tphere':
    case 'tph':
    case 'move':
      if (!clicker) return _console('bring the NPC to');
      return rest.isEmpty
          ? MappedLine(_action('animus:teleport', _npcTo('player')))
          : ReviewLine('npc $sub cursor moves the NPC to where the clicker looks, which Animus cannot read');
    case 'moveto':
      return _moveto(rest, words);
    case 'pathto':
      return _pathto(rest, words);
    case 'rotate':
      return _rotate(words, clicker);
    case 'pose':
      final String? assume = words.values['assume'];
      return assume != null && citizensPoseName(assume).isNotEmpty
          ? MappedLine(_action('animus:pose', _arguments('pose', citizensPoseName(assume))))
          : const ReviewLine("npc pose edits the NPC's pose list; only --assume has an Animus action");
    case 'panimate':
      return rest.isEmpty ? const ReviewLine('npc panimate names no animation') : _animate(rest.first);
    case 'rename':
      return _rename(rest.join(' '), key);
    case 'despawn':
      return rest.isEmpty || rest.first == key
          ? MappedLine(_command('animus despawn citizens-$key'))
          : ReviewLine('despawns NPC ${rest.first}, not this one');
    default:
      return ReviewLine(sub.isEmpty ? 'npc names no subcommand' : 'npc $sub has no Animus action');
  }
}

CitizensLine _speech(String text) {
  if (text.isEmpty) return const ReviewLine('says no text');
  return MappedLine(_action('animus:say', _arguments('text', text)));
}

/// Speech to the target, to everyone within the range, or to bystanders in Citizens' chat range; a bubble alone with --bubble.
CitizensLine _speak(String text, _Words words) {
  if (javaIsBlank(text)) return const ReviewLine('npc speak says no text');
  final JObject spoken = _arguments('text', text);
  final String? bubble = words.values['bubble'];
  if (bubble != null) {
    final int? ticks = citizensTicks(bubble);
    if (ticks == null || ticks < 1) return ReviewLine('the bubble duration $bubble cannot be read');
    spoken.addInt('bubbleTicks', ticks < 1200 ? ticks : 1200);
    spoken.addBool('chat', false);
    return MappedLine(_action('animus:say', spoken), ticks > 1200 ? 'bubbles longer than 1,200 ticks became 1,200' : null);
  }
  final String? target = words.values['target'];
  if (target != null && target != _clicker) return ReviewLine('speaks to $target, one fixed player or NPC');
  final String? written = words.values['range'];
  if (written != null) {
    final double? range = _number(written);
    if (range == null || range <= 0) return ReviewLine('the range $written is not a positive number');
    spoken.addString('audience', 'nearby');
    spoken.addDouble('radius', math.max(1.0, math.min(64.0, range)));
    final String? note = target != null ? 'the clicker hears it only within the range' : range > 64 ? 'ranges above 64 blocks became 64' : null;
    return MappedLine(_action('animus:say', spoken), note);
  }
  if (target == null) {
    spoken.addString('audience', 'nearby');
    spoken.addDouble('radius', citizensChatRange);
  }
  return MappedLine(_action('animus:say', spoken));
}

CitizensLine _moveto(List<String> rest, _Words words) {
  final List<String> parts;
  if (rest.isNotEmpty) {
    parts = rest.join(':').split(':');
  } else if (words.values.containsKey('x') && words.values.containsKey('y') && words.values.containsKey('z')) {
    parts = <String>[words.values['x']!, words.values['y']!, words.values['z']!];
  } else {
    return const ReviewLine("npc moveto without all of x, y and z keeps the NPC's other coordinates, which Animus cannot read");
  }
  if (parts.length == 4 || words.values.containsKey('world')) return const ReviewLine('npc moveto names a world; set the Animus world by hand');
  if (parts.length != 3) return const ReviewLine('npc moveto needs x, y and z');
  final JObject arguments = _npcTo(null);
  if (!_coordinates(parts, arguments)) return const ReviewLine('npc moveto has a coordinate that is not a number');
  for (final String angle in const <String>['yaw', 'pitch']) {
    final String? written = words.values[angle];
    if (written == null) continue;
    final double? value = _number(written);
    final int limit = angle == 'yaw' ? 360 : 90;
    if (value == null || value.abs() > limit) return ReviewLine('npc moveto has a $angle outside ${-limit} to $limit');
    arguments.addDouble(angle, value);
  }
  return MappedLine(_action('animus:teleport', arguments));
}

CitizensLine _pathto(List<String> rest, _Words words) {
  if (rest.length != 3) return ReviewLine('npc pathto ${rest.join(' ')} walks to the clicker or where they look; animus:navigate needs a point');
  final JObject arguments = JObject();
  if (!_coordinates(rest, arguments)) return const ReviewLine('npc pathto has a coordinate that is not a number');
  final bool tuned = words.flags.contains('s') || words.values.containsKey('margin');
  return MappedLine(_action('animus:navigate', arguments), tuned ? 'straight-line walking and --margin are not kept' : null);
}

CitizensLine _rotate(_Words words, bool clicker) {
  final String? entity = words.values['toentity'];
  if (entity != null && (entity == _clicker || entity == 'me')) {
    if (entity == 'me' && !clicker) return _console('turn the NPC toward');
    return MappedLine(_action('animus:look', _arguments('target', 'player')));
  }
  final String? towards = words.values['towards'];
  if (entity == null && towards != null) {
    final JObject arguments = JObject();
    if (_coordinates(towards.split(RegExp('[,:]')), arguments)) return MappedLine(_action('animus:look', arguments));
  }
  return const ReviewLine('npc rotate turns to a set yaw, pitch or entity; Animus looks only at a point or the clicker');
}

CitizensLine _animate(String animation) {
  final String? animus = citizensAnimations[javaUpper(animation).replaceAll(' ', '_').replaceAll('-', '_')];
  return animus == null
      ? ReviewLine('the player animation $animation has no Animus animation')
      : MappedLine(_action('animus:animate', _arguments('animation', animus)));
}

CitizensLine _rename(String name, String key) {
  if (javaIsBlank(name)) return const ReviewLine('npc rename gives no name');
  if (name.contains('"')) return const ReviewLine('the new name holds a quote, which the rename command cannot pass');
  return MappedLine(_command('animus npc rename citizens-$key "$name"'));
}

CitizensLine _console(String what) => ReviewLine('runs from the console, where Citizens has no player to $what');

JObject _action(String capability, JObject arguments) => JObject()
  ..addString('type', 'action')
  ..addString('capability', capability)
  ..add('arguments', arguments);

JObject _arguments(String field, String value) => JObject()..addString(field, value);

JObject _command(String command) => _action('animus:command', _arguments('command', command)..addString('as', 'console'));

/// Teleport arguments that move the NPC, to [to] when given.
JObject _npcTo(String? to) {
  final JObject arguments = _arguments('target', 'npc');
  if (to != null) arguments.addString('to', to);
  return arguments;
}

/// Adds x, y and z when all three are numbers.
bool _coordinates(List<String> parts, JObject arguments) {
  final List<double> values = <double>[];
  for (final String part in parts) {
    final double? value = _number(part);
    if (value == null || value.abs() > 30000000) return false;
    values.add(value);
  }
  if (values.length != 3) return false;
  arguments
    ..addDouble('x', values[0])
    ..addDouble('y', values[1])
    ..addDouble('z', values[2]);
  return true;
}

double? _number(String text) => _decimal.hasMatch(text) ? double.parse(text) : null;

/// Citizens' duration words: ticks as a bare number or with t, else hours, minutes and seconds such as 1m30s.
int? citizensTicks(String text) {
  if (RegExp(r'^\d{1,9}$').hasMatch(text)) return int.parse(text);
  if (RegExp(r'^\d{1,9}t$').hasMatch(text)) return int.parse(text.substring(0, text.length - 1));
  final RegExpMatch? duration = _duration.firstMatch(text.replaceAll('min', 'm').replaceAll('hr', 'h'));
  if (text.isEmpty || duration == null) return null;
  final double seconds = (duration.group(1) == null ? 0 : int.parse(duration.group(1)!) * 3600.0) +
      (duration.group(2) == null ? 0 : int.parse(duration.group(2)!) * 60.0) +
      (duration.group(3) == null ? 0 : double.parse(duration.group(3)!));
  return (seconds * 20).floor();
}

/// A line split the way Citizens' CommandContext splits it: quoted words joined, then --name value and -abc flags taken out.
class _Words {
  _Words(this.args, this.values, this.flags);

  factory _Words.of(String text) {
    final List<String> raw = <String>[
      for (final String word in text.split(' '))
        if (!javaIsBlank(word)) javaTrim(word)
    ];
    final List<String> words = <String>[];
    final List<bool> quoted = <bool>[];
    for (int index = 0; index < raw.length; index++) {
      final String word = raw[index];
      final String quote = word[0];
      if (index > 0 && (quote == '"' || quote == "'" || quote == '`')) {
        final StringBuffer joined = StringBuffer(word.substring(1));
        int end = index;
        bool closed = joined.isNotEmpty && joined.toString().endsWith(quote);
        while (!closed && end + 1 < raw.length) {
          end++;
          joined
            ..write(' ')
            ..write(raw[end]);
          closed = raw[end].endsWith(quote);
        }
        if (closed) {
          final String all = joined.toString();
          words.add(all.substring(0, all.length - 1));
          quoted.add(true);
          index = end;
          continue;
        }
      }
      words.add(word);
      quoted.add(false);
    }
    final List<String> args = <String>[];
    final Map<String, String> values = <String, String>{};
    final Set<String> flags = <String>{};
    for (int index = 0; index < words.length; index++) {
      final String word = words[index];
      if (index > 0 && !quoted[index] && index + 1 < words.length && _valueFlag.hasMatch(word)) {
        values[javaLower(word.substring(2))] = words[++index];
      } else if (index > 0 && !quoted[index] && _flag.hasMatch(word)) {
        flags.addAll(word.substring(1).split(''));
      } else {
        args.add(word);
      }
    }
    return _Words(args, values, flags);
  }

  final List<String> args;
  final Map<String, String> values;
  final Set<String> flags;
}
