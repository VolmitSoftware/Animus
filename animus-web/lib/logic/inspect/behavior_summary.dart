/// One plain line per behavior node, for collapsed tree rows and dry-run
/// steps: "Say: Welcome", "If flag met_guard is true", "While count < 3".
library;

import 'dart:convert';

import '../../mc/npc/mc_text.dart';
import '../gui/gui_json.dart';
import '../gui/gui_text.dart';
import 'behavior_operands.dart';

const int _maximumLength = 72;

String _cut(String text) => text.length <= _maximumLength ? text : '${text.substring(0, _maximumLength - 3)}...';

/// A literal or operand in words: `count + 1`, `"Alex"`, `names`.
String operandWords(Object? value, {String? type}) {
  switch (operandMode(value, type: type)) {
    case OperandMode.variable || OperandMode.expression:
      return operandText(value);
    case OperandMode.template:
      return '"${operandText(value)}"';
    case OperandMode.literal:
      if (value is String) return '"$value"';
      if (value is List<Object?>) return value.isEmpty ? 'an empty list' : jsonEncode(value);
      if (value is Map<String, Object?>) return value.isEmpty ? 'an empty map' : jsonEncode(value);
      return '$value';
  }
}

/// Argument [key] of an action or condition: its literal, or the template,
/// expression or variable that fills its placeholder.
String argumentWords(JsonMap node, String key) {
  switch (argumentSource(node, key)) {
    case ArgumentSource.literal:
      final Object? value = asMap(node['arguments'])[key];
      return value is String ? value : (value == null ? '' : jsonEncode(value));
    case ArgumentSource.template:
      return argumentSlotText(node, key);
    case ArgumentSource.binding || ArgumentSource.expression:
      return '{${argumentSlotText(node, key)}}';
  }
}

/// [text] without colour codes and tags, so a summary never shows `&e`.
/// Placeholders stay as written.
String _plain(String text) => guiTextRuns(text, sample: false).map((McTextRun run) => run.text).join();

String _flagName(JsonMap node) {
  final String name = argumentWords(node, 'name');
  final String scope = asString(asMap(node['arguments'])['scope']) ?? 'player';
  return scope == 'player' ? name : '$name ($scope)';
}

String _ticks(Object? value) {
  if (value is! num) return '$value ticks';
  final double seconds = value / 20;
  return '$value ticks (${seconds == seconds.roundToDouble() ? seconds.toInt() : seconds.toStringAsFixed(1)}s)';
}

/// The capability's own name in words: `animus:play-sound` is "Play sound".
String capabilityWords(String? id) {
  if (id == null || id.isEmpty) return 'No capability chosen';
  final String name = id.contains(':') ? id.substring(id.indexOf(':') + 1) : id;
  final String spaced = name.replaceAll('-', ' ').replaceAll('_', ' ');
  return spaced.isEmpty ? id : '${spaced[0].toUpperCase()}${spaced.substring(1)}';
}

String _action(JsonMap node) {
  final String? id = asString(node['capability']);
  final JsonMap arguments = asMap(node['arguments']);
  String argument(String key) => argumentWords(node, key);
  String shown(String key) => _plain(argument(key));
  return switch (id) {
    'animus:say' => 'Say: ${shown('text')}',
    'animus:message' => 'Message: ${shown('text')}',
    'animus:actionbar' => 'Action bar: ${shown('text')}',
    'animus:title' => 'Title: ${<String>[shown('title'), shown('subtitle')].where((String part) => part.isNotEmpty).join(' / ')}',
    'animus:set-flag' => arguments['clear'] != null ? 'Clear flag ${_flagName(node)}' : 'Set flag ${_flagName(node)} to ${arguments.containsKey('value') ? argument('value') : 'true'}',
    'animus:set-quest-state' => 'Set quest ${_flagName(node)} to ${argument('value')}',
    'animus:advance-quest-state' => 'Advance quest ${_flagName(node)} by ${arguments.containsKey('by') ? argument('by') : '1'}',
    'animus:choices' => 'Ask: ${shown('prompt')}',
    'animus:navigate' => arguments['route'] != null ? 'Walk route ${argument('route')}' : 'Walk to ${argument('x')}, ${argument('y')}, ${argument('z')}',
    'animus:command' => 'Run command /${argument('command')}',
    'animus:give-item' => 'Give ${arguments.containsKey('amount') ? '${argument('amount')} ' : ''}${argument('item')}',
    'animus:take-item' => 'Take ${arguments.containsKey('amount') ? '${argument('amount')} ' : ''}${argument('item')}',
    'animus:open-gui' => 'Open GUI ${argument('gui')}',
    'animus:open-shop' => 'Open shop ${argument('shop')}',
    'animus:play-sound' => 'Play sound ${argument('sound')}',
    'animus:spawn-particle' => 'Show particle ${argument('particle')}',
    'animus:play-animation' => 'Play animation ${argument('animation')}',
    'animus:teleport' => 'Teleport the player',
    'animus:look' => arguments['target'] == 'player' ? 'Look at the player' : 'Look at ${argument('x')}, ${argument('y')}, ${argument('z')}',
    _ => <String>[
        capabilityWords(id),
        if (arguments.isNotEmpty) arguments.keys.take(2).map((String key) => '$key ${argument(key)}').join(', '),
      ].join(': '),
  };
}

String _condition(JsonMap node) {
  final Object? expression = node['expression'];
  if (expression is String) return 'If $expression';
  final JsonMap arguments = asMap(node['arguments']);
  return switch (asString(node['capability'])) {
    'animus:flag-state' => 'If flag ${_flagName(node)} is ${arguments.containsKey('value') ? argumentWords(node, 'value') : 'true'}',
    'animus:quest-state' => 'If quest ${_flagName(node)} ${<String>[
        if (arguments.containsKey('equals')) 'is ${argumentWords(node, 'equals')}',
        if (arguments.containsKey('atLeast')) 'is at least ${argumentWords(node, 'atLeast')}',
        if (arguments.containsKey('atMost')) 'is at most ${argumentWords(node, 'atMost')}',
      ].join(' and ')}'
          .trimRight(),
    'animus:has-permission' => 'If the player has permission ${argumentWords(node, 'permission')}',
    'animus:player-in-range' => 'If a player is within ${argumentWords(node, 'radius')} blocks',
    'animus:npc-state' => 'If the NPC is spawned and healthy',
    final String? id => 'If ${capabilityWords(id).toLowerCase()}',
  };
}

/// One plain line about [node]; [variables] gives `set` its target type.
String nodeSummary(Object? raw, {JsonMap variables = const <String, Object?>{}}) {
  final JsonMap node = asMap(raw);
  final String? type = asString(node['type']);
  final String line = switch (type) {
    'sequence' => '${asList(node['steps']).length} step${asList(node['steps']).length == 1 ? '' : 's'} in order',
    'parallel' => '${asList(node['branches']).length} branch${asList(node['branches']).length == 1 ? '' : 'es'} at once',
    'action' => _action(node),
    'condition' => _condition(node),
    'wait' => 'Wait ${_ticks(node['ticks'])}',
    'repeat' => 'Repeat ${operandWords(node['count'], type: 'integer')} time${node['count'] == 1 ? '' : 's'}',
    'foreach' => 'For each ${asString(node['as']) ?? 'item'} in ${operandWords(node['list'])}',
    'while' => 'While ${asString(node['expression']) ?? '?'}',
    'break' => 'Leave the loop',
    'continue' => 'Start the next pass',
    'call' => 'Call routine ${asString(node['routine']) ?? '?'}${node['result'] is String ? ' into ${node['result']}' : ''}',
    'return' => node.containsKey('value') ? 'Return ${operandWords(node['value'])}' : 'Return',
    'set' => 'Set ${asString(node['variable']) ?? '?'} to ${operandWords(node['value'], type: asString(asMap(variables[node['variable']])['type']))}',
    null => 'A step with no type',
    _ => 'Unknown step $type, kept as written',
  };
  return _cut(line);
}
