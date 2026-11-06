/// A browser-side playthrough of a conversation: what the NPC says and asks
/// from a block until it waits for an answer or ends. Conditions take the
/// branch the author picked for them; nothing touches a server.
library;

import '../gui/gui_json.dart';
import 'conversation_json.dart';
import 'conversation_model.dart';

/// Steps one playthrough walks before it stops, so a loop with no question
/// cannot hang the page.
const int playStepLimit = 256;

sealed class PlayEntry {
  const PlayEntry();
}

class PlayLine extends PlayEntry {
  const PlayLine(this.text, {this.delayTicks});

  final String text;
  final int? delayTicks;
}

class PlayQuestion extends PlayEntry {
  const PlayQuestion(this.at, this.choices);

  final StepAt at;
  final ConversationChoices choices;
}

/// A condition and the branch taken.
class PlayCondition extends PlayEntry {
  const PlayCondition(this.at, this.label, this.taken);

  final StepAt at;
  final String label;
  final bool taken;
}

/// Anything the NPC does that is not speech.
class PlayEffect extends PlayEntry {
  const PlayEffect(this.text);

  final String text;
}

class PlayStop extends PlayEntry {
  const PlayStop(this.text);

  final String text;
}

/// Plays [conversation] from [block]. [branch] answers each condition by
/// its step key; [player] and [npc] fill the placeholders.
List<PlayEntry> playFrom(Conversation conversation, String block, {required bool Function(StepAt at) branch, String player = 'Steve', String npc = 'NPC'}) {
  final List<PlayEntry> entries = <PlayEntry>[];
  int budget = playStepLimit;
  String fill(String text) => text.replaceAll(playerNamePlaceholder, player).replaceAll(npcNamePlaceholder, npc);

  /// Plays [list]; true when the run stopped (asked, ended or went on).
  bool play(StepList list, String name, List<Object> route) {
    for (int index = 0; index < list.steps.length; index++) {
      if (--budget < 0) {
        entries.add(const PlayStop('Stopped: it loops without a question.'));
        return true;
      }
      final StepAt at = StepAt(name, <Object>[...route, index]);
      switch (list.steps[index]) {
        case ConversationLine(:final String text, :final int? delayTicks, :final String? sound, :final String? animation):
          entries.add(PlayLine(fill(text), delayTicks: delayTicks));
          if (sound != null && sound.isNotEmpty) entries.add(PlayEffect('Plays $sound'));
          if (animation != null && animation.isNotEmpty) entries.add(PlayEffect('Plays $animation'));
        case final ConversationChoices choices:
          entries.add(PlayQuestion(at, choices));
          // The run goes on after showing the prompt; the answer starts a new one.
        case final ConversationBranch step:
          final bool taken = branch(at);
          entries.add(PlayCondition(at, conditionLabel(step), taken));
          if (play(taken ? step.then : step.otherwise, name, <Object>[...route, index, taken ? 'then' : 'else'])) return true;
        case ConversationAction(:final String capability, :final JsonMap arguments):
          entries.add(PlayEffect(actionLabel(capability, arguments)));
        case ConversationGoto(:final String target):
          final StepList? next = conversation.blocks[target];
          if (next == null) {
            entries.add(PlayStop('Stopped: $target is gone.'));
            return true;
          }
          play(next, target, const <Object>[]);
          return true;
        case ConversationEnd():
          return true;
      }
    }
    return false;
  }

  final StepList? start = conversation.blocks[block];
  if (start != null) play(start, block, const <Object>[]);
  return entries;
}

/// A condition in words: `flag met`, `quest main at least 2`.
String conditionLabel(ConversationBranch branch) {
  final JsonMap arguments = branch.arguments;
  final String? expression = branch.expression;
  if (expression != null) {
    final String? item = heldItemOf(expression);
    return item != null ? 'holding $item' : expression;
  }
  final String name = '${arguments['name'] ?? ''}';
  final String scope = arguments['scope'] == null ? '' : ' (${arguments['scope']})';
  return switch (branch.capability) {
    'animus:flag-state' => arguments['scope'] == 'relationship'
        ? 'relationship $name${arguments.containsKey('value') ? ' = ${arguments['value']}' : ''}'
        : 'flag $name$scope${arguments.containsKey('value') ? ' = ${arguments['value']}' : ''}',
    'animus:quest-state' => 'quest $name$scope ${<String>[
        if (arguments['equals'] != null) 'is ${arguments['equals']}',
        if (arguments['atLeast'] != null) 'at least ${arguments['atLeast']}',
        if (arguments['atMost'] != null) 'at most ${arguments['atMost']}',
      ].join(', ')}'.trimRight(),
    'animus:has-permission' => 'has ${arguments['permission'] ?? ''}',
    _ => '${branch.capability ?? 'condition'} ${arguments.entries.map((MapEntry<String, Object?> entry) => '${entry.key}=${entry.value}').join(' ')}'.trimRight(),
  };
}

final RegExp _heldItem = RegExp(r"^player\.item_in_hand\s*==\s*'([a-z0-9_.:/-]+)'$");

/// The item an "item held" expression checks, or null.
String? heldItemOf(String expression) => _heldItem.firstMatch(expression.trim())?.group(1);

/// The expression that checks the player holds [item].
String heldItemExpression(String item) => "player.item_in_hand == '$item'";

/// An action in words.
String actionLabel(String capability, JsonMap arguments) {
  String arg(String key) => '${arguments[key] ?? ''}';
  return switch (capability) {
    'animus:set-flag' => arguments['clear'] == true ? 'Clears flag ${arg('name')}' : 'Sets flag ${arg('name')}${arguments.containsKey('value') ? ' to ${arg('value')}' : ''}',
    'animus:give-item' => 'Gives ${arguments['amount'] ?? 1} ${arg('item')}',
    'animus:take-item' => 'Takes ${arguments['amount'] ?? 1} ${arg('item')}',
    'animus:command' => 'Runs /${arg('command')}',
    'animus:open-shop' => 'Opens shop ${arg('shop')}',
    'animus:open-gui' => 'Opens ${arg('gui')}',
    'animus:play-animation' => 'Plays ${arg('animation')}',
    'animus:play-sound' => 'Plays ${arg('sound')}',
    'animus:teleport' => 'Teleports the ${arguments['target'] ?? 'player'}',
    'animus:set-quest-state' => 'Sets quest ${arg('name')} to ${arguments['value'] ?? 'random'}',
    'animus:advance-quest-state' => 'Advances quest ${arg('name')}',
    'animus:look' => 'Looks at the player',
    _ => capability.isEmpty ? 'Does nothing yet' : capability,
  };
}
