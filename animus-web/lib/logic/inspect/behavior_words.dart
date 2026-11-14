/// Behavior words for people who never read a behavior file: a plain name
/// from the id, what starts it ("On click", "Player nearby"), and the human
/// labels of the node types with the engine word kept for tips.
library;

import '../gui/gui_json.dart';

/// "dialogue:innkeeper" is "Innkeeper", "town:guard-talk" is "Guard talk",
/// "npc_intercept" is "NPC intercept".
String behaviorTitle(String id) {
  final String last = id.split(RegExp(r'[:/]')).where((String part) => part.isNotEmpty).lastOrNull ?? id;
  final String spaced = last
      .replaceAll(RegExp(r'[-_.]+'), ' ')
      .trim()
      .replaceAllMapped(RegExp(r'\b(npc|gui|id)\b', caseSensitive: false), (Match word) => word[0]!.toUpperCase());
  if (spaced.isEmpty) return id;
  return '${spaced[0].toUpperCase()}${spaced.substring(1)}';
}

/// An argument key as a label: `x` reads "X", `soundVolume` "Sound volume".
String argumentLabel(String key) => key.length == 1 ? key.toUpperCase() : humanizeKey(key);

/// The folder part of a behavior id: "dialogue" for "dialogue:innkeeper".
String behaviorFolder(String id) => id.contains(':') ? id.substring(0, id.indexOf(':')) : '';

/// What an interaction trigger's `action` means.
const Map<String, String> clickWords = <String, String>{
  'interact': 'On click',
  'attack': 'On left-click',
  'any': 'On any click',
  'sneak_interact': 'On sneak click',
  'sneak_attack': 'On sneak left-click',
  'drop': 'On drop key',
  'sneak_drop': 'On sneak drop key',
  'swap_hands': 'On swap hands',
  'sneak_swap_hands': 'On sneak swap hands',
};

/// The short words for a trigger [event], without its settings.
String eventWords(String event) => switch (event) {
      'interaction' => 'On click',
      'proximity' => 'Player nearby',
      'timer' => 'On a timer',
      'spawn' => 'On spawn',
      'despawn' => 'On despawn',
      'damage' => 'When hurt',
      'death' => 'When killed',
      'waypoint' => 'At a route point',
      'dialogue_choice' => 'An answer',
      'module' => 'From a module',
      'build_finished' => 'Build finished',
      'build_paused' => 'Build paused',
      'animation_finished' => 'Animation ends',
      'player_join' => 'Player joins',
      'player_quit' => 'Player leaves',
      'player_chat' => 'Chat message',
      'command' => 'A command',
      'server_start' => 'Server starts',
      'player_death' => 'Player dies',
      'player_respawn' => 'Player respawns',
      'player_world_change' => 'World change',
      'player_area' => 'Player in an area',
      _ => _sentence(event.replaceAll('_', ' ')),
    };

/// The short words for one trigger, with the click kind, command name or
/// how often a timer runs.
String triggerWords(JsonMap trigger) {
  final String event = asString(trigger['event']) ?? '';
  final Object? interval = trigger['intervalTicks'];
  return switch (event) {
    'interaction' => clickWords[asString(trigger['action']) ?? 'interact'] ?? 'On click',
    'command' when trigger['name'] is String => 'Command /${trigger['name']}',
    'timer' when interval is int && interval > 0 => everyWords(interval),
    _ => eventWords(event),
  };
}

/// How often [ticks] comes round, in the largest whole unit: "Every
/// second", "Every 5 minutes", "Every 1.5 seconds", "Every 7 ticks".
String everyWords(int ticks) {
  String every(num count, String unit) => count == 1 ? 'Every $unit' : 'Every $count ${unit}s';
  if (ticks % 72000 == 0) return every(ticks ~/ 72000, 'hour');
  if (ticks % 1200 == 0) return every(ticks ~/ 1200, 'minute');
  if (ticks % 20 == 0) return every(ticks ~/ 20, 'second');
  if (ticks % 2 == 0) return every(ticks / 20, 'second');
  return 'Every $ticks ticks';
}

/// Registry folders a sound name leaves out: the folder says what makes it.
const Map<String, String> _soundGroups = <String, String>{'block': 'Blocks', 'entity': 'Creatures', 'item': 'Items'};

String _soundPath(String id) => id.contains(':') ? id.substring(id.indexOf(':') + 1) : id;

/// A sound id in plain words: "minecraft:block.note_block.chime" is "Note
/// block chime". The id stays what is written.
String soundName(String id) {
  final List<String> parts = _soundPath(id).split('.');
  final List<String> named = parts.length > 1 && (_soundGroups.containsKey(parts.first) || parts.first == 'ui' || parts.first == 'ambient') ? parts.sublist(1) : parts;
  return _sentence(named.join(' ').replaceAll('_', ' ').trim());
}

/// What makes the sound [id]: Blocks, Creatures, Items or Other.
String soundGroup(String id) => _soundGroups[_soundPath(id).split('.').first] ?? 'Other';

/// One line about what starts a behavior, from its trigger [events] (a
/// catalog summary) or, when loaded, its [triggers]. Answers are left out:
/// they continue a conversation, they do not start one.
String startLine({List<String> events = const <String>[], List<Object?>? triggers}) {
  final List<String> words = <String>[];
  if (triggers != null) {
    for (final Object? raw in triggers) {
      final JsonMap trigger = asMap(raw);
      if (trigger['event'] == 'dialogue_choice') continue;
      final String said = triggerWords(trigger);
      if (!words.contains(said)) words.add(said);
    }
  } else {
    for (final String event in events) {
      if (event == 'dialogue_choice') continue;
      final String said = eventWords(event);
      if (!words.contains(said)) words.add(said);
    }
  }
  if (words.isEmpty) return 'Runs when called';
  if (words.length > 3) return '${words.take(3).join(', ')} +${words.length - 3}';
  return words.join(', ');
}

/// A behavior node type in words, with the engine's own word for the tip.
class NodeWords {
  const NodeWords(this.label, this.line);

  /// One to three words for the type picker and the card heading.
  final String label;

  /// What it does, in one short sentence.
  final String line;
}

const Map<String, NodeWords> nodeWords = <String, NodeWords>{
  'sequence': NodeWords('Steps in order', 'Runs its steps one after another.'),
  'parallel': NodeWords('At the same time', 'Runs its branches together.'),
  'action': NodeWords('Do', 'Does one thing: say a line, give an item, walk.'),
  'condition': NodeWords('If', 'Checks something, then runs Yes or No.'),
  'wait': NodeWords('Wait', 'Pauses before the next step.'),
  'repeat': NodeWords('Repeat', 'Runs its steps a number of times.'),
  'foreach': NodeWords('For each', 'Runs its steps once per item of a list.'),
  'while': NodeWords('Repeat while', 'Runs its steps while a test holds.'),
  'break': NodeWords('Leave loop', 'Stops the loop it is in.'),
  'continue': NodeWords('Next round', 'Skips to the next round of its loop.'),
  'set': NodeWords('Set variable', 'Stores a value for later steps.'),
  'call': NodeWords('Run part', 'Runs another part of this behavior.'),
  'return': NodeWords('Stop', 'Ends this part here.'),
};

/// The label of node [type]; an unknown type keeps its own name.
String nodeLabel(String? type) => type == null ? 'No type' : (nodeWords[type]?.label ?? type);

String _sentence(String text) => text.isEmpty ? text : '${text[0].toUpperCase()}${text.substring(1)}';

/// Variable types in words; the engine word stays the stored value.
const Map<String, String> variableTypeWords = <String, String>{
  'boolean': 'True or false',
  'integer': 'Whole number',
  'decimal': 'Number',
  'string': 'Text',
  'list': 'List',
  'map': 'Map',
  'any': 'Anything',
};

/// Variable scopes in words: who shares the value.
const Map<String, String> variableScopeWords = <String, String>{
  'execution': 'One run',
  'npc': 'This NPC',
  'player': 'Each player',
  'relationship': 'NPC and player',
  'global': 'Everyone',
};

/// Variable lifetimes in words.
const Map<String, String> variableLifetimeWords = <String, String>{
  'transient': 'Until restart',
  'persistent': 'Saved',
};
