/// Conversation steps in words, for step card headings and block titles:
/// what a condition checks, what an action does, a part named after the
/// answer that leads to it.
library;

import '../../model/display_name.dart';
import '../../model/document_kind.dart';
import '../../model/document_summary.dart' show plainName;
import '../gui/gui_json.dart';
import '../item_ids.dart';
import '../inspect/behavior_summary.dart' show capabilityWords;
import '../inspect/behavior_words.dart' show soundName;
import 'conversation_model.dart';

/// A value in a sentence: text without its colour codes, anything else as
/// written.
String _shown(Object? value) => value is String ? plainName(value) ?? value : '${value ?? ''}';

/// A condition in words: `Player has flag met`, `Quest main stage at least 2`.
String conditionLabel(ConversationBranch branch) {
  final JsonMap arguments = branch.arguments;
  final String? expression = branch.expression;
  if (expression != null) {
    final String? item = heldItemOf(expression);
    return item != null ? 'Player holds ${itemName(item).toLowerCase()}' : expression;
  }
  final String name = '${arguments['name'] ?? ''}';
  final String value = arguments.containsKey('value') ? ' is ${_shown(arguments['value'])}' : '';
  return switch (branch.capability) {
    'animus:flag-state' => switch (arguments['scope']) {
        'relationship' => value.isEmpty ? 'Flag $name is set with this NPC' : 'Flag $name with this NPC$value',
        'npc' => value.isEmpty ? 'NPC has flag $name' : 'NPC flag $name$value',
        'global' => value.isEmpty ? 'Flag $name is set for everyone' : 'Flag $name for everyone$value',
        _ => value.isEmpty ? 'Player has flag $name' : 'Player flag $name$value',
      },
    'animus:quest-state' => 'Quest $name stage ${<String>[
        if (arguments['equals'] != null) 'is ${arguments['equals']}',
        if (arguments['atLeast'] != null) 'at least ${arguments['atLeast']}',
        if (arguments['atMost'] != null) 'at most ${arguments['atMost']}',
      ].join(', ')}'.trimRight(),
    'animus:has-permission' => 'Player has permission ${arguments['permission'] ?? ''}',
    final String? other => '${capabilityWords(other)} ${arguments.entries.map((MapEntry<String, Object?> entry) => '${humanizeKey(entry.key).toLowerCase()} ${entry.value}').join(', ')}'.trimRight(),
  };
}

final RegExp _heldItem = RegExp(r"^player\.item_in_hand\s*==\s*'([a-z0-9_.:/-]+)'$");

/// The item an "item held" expression checks, or null.
String? heldItemOf(String expression) => _heldItem.firstMatch(expression.trim())?.group(1);

/// The expression that checks the player holds [item].
String heldItemExpression(String item) => "player.item_in_hand == '$item'";

/// An action in words; [names] names the documents it opens or plays.
String actionLabel(String capability, JsonMap arguments, {DocumentNames names = idDocumentName}) {
  String arg(String key) => '${arguments[key] ?? ''}';
  String document(DocumentKind kind, String key) => arguments[key] is String ? names(kind, arg(key)) : arg(key);
  return switch (capability) {
    'animus:set-flag' => arguments['clear'] == true ? 'Clears flag ${arg('name')}' : 'Sets flag ${arg('name')}${arguments.containsKey('value') ? ' to ${_shown(arguments['value'])}' : ''}',
    'animus:give-item' => 'Gives ${arguments['amount'] ?? 1} ${itemName(arg('item')).toLowerCase()}',
    'animus:take-item' => 'Takes ${arguments['amount'] ?? 1} ${itemName(arg('item')).toLowerCase()}',
    'animus:command' => 'Runs /${arg('command')}',
    'animus:open-shop' => 'Opens shop ${document(DocumentKind.shop, 'shop')}',
    'animus:open-gui' => 'Opens ${document(DocumentKind.inventory, 'gui')}',
    'animus:play-animation' => 'Plays ${document(DocumentKind.animation, 'animation')}',
    'animus:play-sound' => 'Plays ${soundName(arg('sound')).toLowerCase()}',
    'animus:teleport' => 'Teleports the ${arguments['target'] ?? 'player'}',
    'animus:set-quest-state' => 'Sets quest ${arg('name')} to ${arguments['value'] ?? 'random'}',
    'animus:advance-quest-state' => 'Advances quest ${arg('name')}',
    'animus:look' => 'Looks at the player',
    _ => capability.isEmpty ? 'Does nothing yet' : capabilityWords(capability),
  };
}


/// The heading of a step card.
String stepKind(ConversationStep step) => switch (step) {
      ConversationLine() => 'Say',
      ConversationChoices() => 'Ask',
      ConversationBranch() => 'If',
      ConversationAction() => 'Do',
      ConversationGoto() => 'Go to',
      ConversationEnd() => 'End',
    };

/// One short line after a step card's heading; empty when the card's own
/// field says it all.
String stepLine(Conversation conversation, ConversationStep step, {DocumentNames names = idDocumentName}) => switch (step) {
      ConversationLine(:final int? delayTicks, :final String? sound, :final String? animation) => <String>[
          if (delayTicks != null) 'after ${_seconds(delayTicks)}',
          if (sound != null) 'sound',
          if (animation != null) 'animation',
        ].join(', '),
      ConversationChoices(:final List<ConversationOption> options) => options.length == 1 ? '1 answer' : '${options.length} answers',
      ConversationBranch() => conditionLabel(step),
      ConversationAction(:final String capability, :final JsonMap arguments) => actionLabel(capability, arguments, names: names),
      ConversationGoto(:final String target) => blockTitle(conversation, target),
      ConversationEnd() => 'Stops the conversation',
    };

String _seconds(int ticks) {
  final double seconds = ticks / 20;
  return seconds == seconds.roundToDouble() ? '${seconds.toInt()} s' : '${seconds.toStringAsFixed(1)} s';
}

/// A part (block) of [conversation] in words: "Start", the answer that
/// leads to it ('After "Tell me more"'), else its name spaced out.
String blockTitle(Conversation conversation, String name) {
  if (name == conversation.entry) return 'Start';
  for (final StepList list in conversation.blocks.values) {
    for (final ConversationChoices choices in choicesIn(list)) {
      for (final ConversationOption option in choices.options) {
        if (option.next == name && option.text.trim().isNotEmpty) return 'After "${option.text.trim()}"';
      }
    }
  }
  final String spaced = name.replaceAll(RegExp(r'[-_]+'), ' ').trim();
  return spaced.isEmpty ? name : '${spaced[0].toUpperCase()}${spaced.substring(1)}';
}
