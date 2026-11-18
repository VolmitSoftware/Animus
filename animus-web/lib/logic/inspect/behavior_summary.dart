/// One plain line per behavior node, for collapsed tree rows and dry-run
/// steps: "Say: Welcome", "If flag met_guard is true", "While count < 3".
library;

import 'dart:convert';

import '../../mc/npc/mc_text.dart';
import '../../model/display_name.dart';
import '../../model/document_kind.dart';
import '../gui/gui_json.dart';
import '../gui/gui_text.dart';
import '../item_ids.dart';
import 'argument_choices.dart' show argumentGiven;
import 'behavior_operands.dart';
import 'behavior_words.dart' show argumentLabel, soundName;
import 'named_ids.dart' show particleName;

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
      final String text = argumentSlotText(node, key);
      return text.isEmpty ? '' : '{$text}';
  }
}

/// [text] without colour codes and tags, so a summary never shows `&e`.
/// Placeholders stay as written.
String _plain(String text) => guiTextRuns(text, sample: false).map((McTextRun run) => run.text).join();

/// A flag or quest name and whose it is, in words.
String _flagName(JsonMap node) {
  final String name = argumentWords(node, 'name');
  return switch (asString(asMap(node['arguments'])['scope'])) {
    'npc' => '$name of the NPC',
    'global' => '$name for everyone',
    'relationship' => '$name with this NPC',
    _ => name,
  };
}

final RegExp _literalId = RegExp(r'^[a-z0-9_.-]+(:[a-z0-9_./-]+)?$');

/// [value] read by [name] when it is a written id; a template or
/// expression stays as written.
String _named(String value, String Function(String id) name) => _literalId.hasMatch(value) ? name(value) : value;

String _ticks(Object? value) {
  if (value is Map && value['expression'] is String) return '${value['expression']} ticks';
  if (value is! num) return '$value ticks';
  final double seconds = value / 20;
  return '$value ticks (${seconds == seconds.roundToDouble() ? seconds.toInt() : seconds.toStringAsFixed(1)}s)';
}

/// The capability's own name in words: `animus:play-sound` is "Play sound".
String capabilityWords(String? id) {
  if (id == null || id.isEmpty) return 'No capability chosen';
  final String name = id.contains(':') ? id.substring(id.indexOf(':') + 1) : id;
  final String spaced = name.replaceAll('-', ' ').replaceAll('_', ' ').replaceAll('actionbar', 'action bar');
  return spaced.isEmpty ? id : '${spaced[0].toUpperCase()}${spaced.substring(1)}';
}

/// A location argument in words: `1,64,3` as "1, 64, 3", `1,64,3,world_nether`
/// as "1, 64, 3 in world_nether"; anything else, such as a placeholder, as
/// written.
String locationWords(String location) {
  final List<String> parts = location.split(',').map((String part) => part.trim()).toList();
  if ((parts.length != 3 && parts.length != 4) || parts.take(3).any((String part) => num.tryParse(part) == null)) return location;
  final String at = parts.take(3).join(', ');
  return parts.length == 4 && parts[3].isNotEmpty ? '$at in ${parts[3]}' : at;
}

String _action(JsonMap node, DocumentNames names) {
  final String? id = asString(node['capability']);
  final JsonMap arguments = asMap(node['arguments']);
  String argument(String key) => argumentWords(node, key).trim();
  String shown(String key) => _plain(argument(key)).trim();
  bool has(String key) => argument(key).isNotEmpty;
  String document(DocumentKind kind, String key) => _named(argument(key), (String value) => names(kind, value));
  String item(String key) => _named(argument(key), (String value) => itemName(value).toLowerCase());
  // A value-or-name pair: "Say: Hello", or just "Say" before the text is typed.
  String titled(String name, String value) => value.isEmpty ? name : '$name: $value';
  String position() => <String>['x', 'y', 'z'].every(has) ? '${argument('x')}, ${argument('y')}, ${argument('z')}' : '';
  // A location written x,y,z or x,y,z,world reads as coordinates, with its world when it names one.
  String place() => locationWords(argument('location'));
  final String name = capabilityWords(id);
  return switch (id) {
    'animus:say' => titled('Say', shown('text')),
    'animus:message' => titled('Message', shown('text')),
    'animus:actionbar' => titled('Action bar', shown('text')),
    'animus:title' => titled('Title', <String>[shown('title'), shown('subtitle')].where((String part) => part.isNotEmpty).join(' / ')),
    'animus:set-flag' => arguments['clear'] != null
        ? (has('name') ? 'Clear flag ${_flagName(node)}' : 'Clear a flag')
        : (has('name') ? 'Set flag ${_flagName(node)}${arguments.containsKey('value') ? (has('value') ? ' to ${argument('value')}' : '') : ' to true'}' : 'Set a flag'),
    'animus:set-quest-state' => !has('name')
        ? 'Set a quest'
        : 'Set quest ${_flagName(node)}${has('value') ? ' to ${argument('value')}' : has('random') ? ' to a random stage up to ${argument('random')}' : ''}',
    'animus:advance-quest-state' => has('name') ? 'Advance quest ${_flagName(node)} by ${has('by') ? argument('by') : '1'}' : 'Advance a quest',
    'animus:choices' => titled('Ask', shown('prompt')),
    'animus:navigate' => has('route')
        ? 'Walk route ${document(DocumentKind.route, 'route')}'
        : has('anchor')
            ? 'Walk to anchor ${argument('anchor')}'
            : position().isNotEmpty
                ? 'Walk to ${position()}'
                : has('location')
                    ? 'Walk to ${place()}'
                    : 'Walk',
    'animus:command' => has('command') ? 'Run command /${argument('command')}${arguments['asOperator'] == true ? ' as operator' : ''}' : 'Run a command',
    'animus:give-item' => has('stack')
        ? 'Give ${has('amount') ? '${argument('amount')} ' : ''}item ${argument('stack')}'
        : has('item')
            ? 'Give ${has('amount') ? '${argument('amount')} ' : ''}${item('item')}'
            : 'Give an item',
    'animus:read-inventory' => 'Read the inventory${node['result'] is String ? ' into ${node['result']}' : ''}',
    'animus:bossbar' => !has('id')
        ? (arguments['operation'] == 'remove' ? 'Remove a boss bar' : 'Boss bar')
        : arguments['operation'] == 'remove'
            ? 'Remove boss bar ${argument('id')}'
            : titled('Boss bar ${argument('id')}', shown('title')),
    'animus:sidebar' => switch (arguments['operation']) {
        'remove' => 'Remove the sidebar',
        'add' => 'Add sidebar lines',
        'set_line' => has('line') ? 'Set sidebar line ${argument('line')}' : 'Set a sidebar line',
        _ => titled('Sidebar', shown('title')),
      },
    'animus:ray-trace' => 'Find the block the player looks at${node['result'] is String ? ' into ${node['result']}' : ''}',
    'animus:read-block' => has('location') ? 'Read the block at ${argument('location')}${node['result'] is String ? ' into ${node['result']}' : ''}' : 'Read a block',
    'animus:explode' => 'Explosion of power ${has('power') ? argument('power') : '1'}',
    'animus:look-close' => arguments['enabled'] == false ? 'Turn look-close off' : 'Turn look-close on',
    'animus:take-item' => has('item') ? 'Take ${has('amount') ? '${argument('amount')} ' : ''}${item('item')}' : 'Take an item',
    'animus:open-gui' => has('gui') ? 'Open GUI ${document(DocumentKind.inventory, 'gui')}' : 'Open a GUI',
    'animus:open-shop' => has('shop') ? 'Open shop ${document(DocumentKind.shop, 'shop')}' : 'Open a shop',
    'animus:play-sound' => has('sound') ? 'Play sound ${soundName(argument('sound')).toLowerCase()}' : 'Play a sound',
    'animus:spawn-particle' => has('particle') ? 'Show particle ${_named(argument('particle'), (String value) => particleName(value).toLowerCase())}' : 'Show a particle',
    'animus:play-animation' => has('animation') ? 'Play animation ${document(DocumentKind.animation, 'animation')}' : 'Play an animation',
    'animus:teleport' => 'Teleport ${arguments['target'] == 'npc' ? 'the NPC' : 'the player'}${position().isNotEmpty ? ' to ${position()}' : has('location') ? ' to ${place()}' : has('anchor') ? ' to anchor ${argument('anchor')}' : switch (arguments['to']) {
        'npc' => ' to the NPC',
        'player' => ' to the player',
        _ => '',
      }}',
    'animus:look' => argumentGiven(node, 'target')
        ? 'Look at the player'
        : position().isNotEmpty
            ? 'Look at ${position()}'
            : has('location')
                ? 'Look at ${place()}'
                : 'Look',
    _ => titled(name, <String>[
        for (final String key in arguments.keys)
          if (has(key)) '${argumentLabel(key).toLowerCase()} ${argument(key)}',
      ].take(2).join(', ')),
  };
}

String _condition(JsonMap node) {
  final Object? expression = node['expression'];
  if (expression is String) return expression.trim().isEmpty ? 'If' : 'If $expression';
  final JsonMap arguments = asMap(node['arguments']);
  String argument(String key) => argumentWords(node, key).trim();
  bool has(String key) => argument(key).isNotEmpty;
  return switch (asString(node['capability'])) {
    'animus:flag-state' => has('name') ? 'If flag ${_flagName(node)} is ${arguments.containsKey('value') && has('value') ? argument('value') : 'true'}' : 'If a flag is set',
    'animus:quest-state' => <String>[
        has('name') ? 'If quest ${_flagName(node)}' : 'If a quest',
        <String>[
          if (has('equals')) 'is ${argument('equals')}',
          if (has('atLeast')) 'is at least ${argument('atLeast')}',
          if (has('atMost')) 'is at most ${argument('atMost')}',
        ].join(' and '),
      ].where((String part) => part.isNotEmpty).join(' '),
    'animus:has-permission' => has('permission') ? 'If the player has permission ${argument('permission')}' : 'If the player has a permission',
    'animus:player-in-range' => has('radius') ? 'If a player is within ${argument('radius')} blocks' : 'If a player is near',
    'animus:npc-state' => 'If the NPC is spawned and healthy',
    'animus:can-fit' => 'If the item fits in the inventory',
    final String? id => 'If ${capabilityWords(id).toLowerCase()}',
  };
}

/// One plain line about [node]; [variables] gives `set` its target type,
/// [names] the documents it opens or plays.
String nodeSummary(Object? raw, {JsonMap variables = const <String, Object?>{}, DocumentNames names = idDocumentName}) {
  final JsonMap node = asMap(raw);
  final String? type = asString(node['type']);
  final String line = switch (type) {
    'sequence' => '${asList(node['steps']).length} step${asList(node['steps']).length == 1 ? '' : 's'} in order',
    'parallel' => '${asList(node['branches']).length} branch${asList(node['branches']).length == 1 ? '' : 'es'} at once',
    'action' => _action(node, names),
    'condition' => _condition(node),
    'wait' => 'Wait ${_ticks(node['ticks'])}',
    'repeat' => 'Repeat ${operandWords(node['count'], type: 'integer')} time${node['count'] == 1 ? '' : 's'}',
    'foreach' => 'For each ${asString(node['as']) ?? 'item'} in ${operandWords(node['list'])}',
    'while' => 'While ${asString(node['expression']) ?? '?'}',
    'break' => 'Leave the loop',
    'continue' => 'Start the next pass',
    'call' => 'Call routine ${asString(node['routine']) ?? '?'}${node.containsKey('player') || node.containsKey('npc') ? ' for ${node.containsKey('npc') ? 'another NPC' : 'another player'}' : ''}${node['result'] is String ? ' into ${node['result']}' : ''}',
    'return' => node.containsKey('value') ? 'Return ${operandWords(node['value'])}' : 'Return',
    'set' => 'Set ${asString(node['variable']) ?? '?'} to ${operandWords(node['value'], type: asString(asMap(variables[node['variable']])['type']))}',
    null => 'A step with no type',
    _ => 'Unknown step $type, kept as written',
  };
  return _cut(line);
}
