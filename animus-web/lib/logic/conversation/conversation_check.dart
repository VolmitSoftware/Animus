/// Problems a conversation shows next to the step they belong to. The
/// limits are the server's: `animus:say` takes 1,024 characters, a prompt
/// 512, an answer 128 and its hover 256; a prompt has 1 to 8 answers with
/// unique ids of lowercase letters, digits, `_` or `-`; a behavior holds 32
/// routines and 32 triggers.
library;

import '../gui/gui_json.dart';
import 'conversation_edit.dart';
import 'conversation_model.dart';

/// Above this a line wraps onto several chat lines in a default chat box.
const int chatLineWarning = 256;
const int sayLimit = 1024;
const int promptLimit = 512;
const int optionTextLimit = 128;
const int hoverLimit = 256;
const int routineLimit = 32;
const int triggerLimit = 32;

final RegExp _optionId = RegExp(r'^[a-z0-9][a-z0-9_-]*$');
final RegExp _colourName = RegExp(r'^(black|dark_blue|dark_green|dark_aqua|dark_red|dark_purple|gold|gray|grey|dark_gray|dark_grey|blue|green|aqua|red|light_purple|yellow|white|[0-9a-f]|#[0-9a-fA-F]{6})$');

class ConversationIssue {
  const ConversationIssue(this.error, this.message, {this.at, this.option, this.block});

  final bool error;
  final String message;

  /// The step it belongs to; null for the whole conversation.
  final StepAt? at;

  /// The answer of the choices at [at], when it is about one.
  final int? option;

  /// The block it belongs to, for block-wide issues.
  final String? block;
}

/// Flags and quest names other behaviors of the namespace set.
class KnownNames {
  const KnownNames({this.flags = const <String>{}, this.quests = const <String>{}, this.checked = false});

  final Set<String> flags;
  final Set<String> quests;

  /// False when nothing was looked up, so no name is reported as unknown.
  final bool checked;
}

/// Flags and quests that behaviors in [behaviors] set, read from their
/// `animus:set-flag`, `animus:set-quest-state` and `animus:advance-quest-state`
/// actions.
KnownNames knownNames(Iterable<Object?> behaviors) {
  final Set<String> flags = <String>{};
  final Set<String> quests = <String>{};
  void walk(Object? value) {
    if (value is List<Object?>) {
      value.forEach(walk);
      return;
    }
    if (value is! Map<String, Object?>) return;
    if (value['type'] == 'action') {
      final String? name = asString(asMap(value['arguments'])['name']);
      if (name != null) {
        switch (value['capability']) {
          case 'animus:set-flag':
            flags.add(name);
          case 'animus:set-quest-state' || 'animus:advance-quest-state':
            quests.add(name);
        }
      }
    }
    value.values.forEach(walk);
  }

  behaviors.forEach(walk);
  return KnownNames(flags: flags, quests: quests, checked: true);
}

/// Every issue of [conversation].
List<ConversationIssue> checkConversation(Conversation conversation, {KnownNames names = const KnownNames()}) {
  final List<ConversationIssue> issues = <ConversationIssue>[];
  final int routines = conversation.blocks.length;
  if (routines > routineLimit) issues.add(ConversationIssue(true, '$routines blocks. A behavior holds $routineLimit routines.'));
  final int triggers = conversation.triggerIds.length;
  if (triggers > triggerLimit) issues.add(ConversationIssue(true, '$triggers answers and starts. A behavior holds $triggerLimit triggers.'));
  if (conversation.start.kind == ConversationStartKind.proximity) {
    final num radius = conversation.start.radius;
    if (radius <= 0 || radius > 64) issues.add(const ConversationIssue(true, 'Radius is above 0 and at most 64 blocks.'));
  }
  for (final String name in unreachedBlocks(conversation)) {
    issues.add(ConversationIssue(false, 'Nothing leads here.', block: name));
  }
  for (final MapEntry<String, StepList> block in conversation.blocks.entries) {
    _list(conversation, block.value, block.key, const <Object>[], names, issues);
  }
  return issues;
}

void _list(Conversation conversation, StepList list, String block, List<Object> route, KnownNames names, List<ConversationIssue> issues) {
  for (int index = 0; index < list.steps.length; index++) {
    final StepAt at = StepAt(block, <Object>[...route, index]);
    final ConversationStep step = list.steps[index];
    void error(String message, {int? option}) => issues.add(ConversationIssue(true, message, at: at, option: option));
    void warn(String message, {int? option}) => issues.add(ConversationIssue(false, message, at: at, option: option));
    switch (step) {
      case ConversationLine(:final String text):
        if (text.trim().isEmpty) {
          error('Empty line.');
        } else if (text.length > sayLimit) {
          error('${text.length} characters. A line takes $sayLimit.');
        } else if (text.length > chatLineWarning) {
          warn('${text.length} characters. Long for chat.');
        }
        if (_controls(text)) error('Control characters.');
      case ConversationChoices(:final String prompt, :final List<ConversationOption> options):
        if (prompt.trim().isEmpty) error('Empty question.');
        if (prompt.length > promptLimit) error('${prompt.length} characters. A question takes $promptLimit.');
        if (options.isEmpty) error('No answers.');
        if (options.length > maximumOptions) error('${options.length} answers. A question takes $maximumOptions.');
        final Set<String> ids = <String>{};
        for (int option = 0; option < options.length; option++) {
          final ConversationOption answer = options[option];
          if (answer.text.trim().isEmpty) error('Empty answer.', option: option);
          if (answer.text.length > optionTextLimit) error('${answer.text.length} characters. An answer takes $optionTextLimit.', option: option);
          if ((answer.hover?.length ?? 0) > hoverLimit) error('Hover over $hoverLimit characters.', option: option);
          if (answer.color != null && !_colourName.hasMatch(answer.color!)) error('Unknown colour ${answer.color}.', option: option);
          if (!_optionId.hasMatch(answer.id) || answer.id.length > 32) error('Id ${answer.id} needs lowercase letters, digits, _ or -.', option: option);
          if (!ids.add(answer.id)) error('Two answers share the id ${answer.id}.', option: option);
          if (answer.next != null && !conversation.blocks.containsKey(answer.next)) error('Leads to ${answer.next}, which is gone.', option: option);
          if (answer.text.contains('{npc.name}') || answer.text.contains('{player_name}')) warn('Answers show placeholders as typed.', option: option);
        }
      case ConversationBranch():
        _condition(step, names, error, warn);
        _list(conversation, step.then, block, <Object>[...route, index, 'then'], names, issues);
        _list(conversation, step.otherwise, block, <Object>[...route, index, 'else'], names, issues);
      case ConversationAction(:final String capability, :final JsonMap arguments):
        if (capability.isEmpty) error('Pick an action.');
        final String? name = asString(arguments['name']);
        if (capability == 'animus:set-flag' && (name == null || name.isEmpty)) error('Name the flag.');
        if (capability == 'animus:set-quest-state' && (name == null || name.isEmpty)) error('Name the quest.');
      case ConversationGoto(:final String target):
        if (!conversation.blocks.containsKey(target)) error(target.isEmpty ? 'Pick where to go.' : 'Goes to $target, which is gone.');
        if (index != list.steps.length - 1) error('Go to must be last.');
      case ConversationEnd():
        if (index != list.steps.length - 1) error('End must be last.');
    }
  }
}

void _condition(ConversationBranch branch, KnownNames names, void Function(String message) error, void Function(String message) warn) {
  if (branch.capability == null && (branch.expression == null || branch.expression!.trim().isEmpty)) {
    error('Pick a condition.');
    return;
  }
  final String? name = asString(branch.arguments['name']);
  switch (branch.capability) {
    case 'animus:flag-state':
      if (name == null || name.isEmpty) {
        error('Name the flag.');
      } else if (names.checked && !names.flags.contains(name)) {
        warn('No behavior sets flag $name.');
      }
    case 'animus:quest-state':
      if (name == null || name.isEmpty) {
        error('Name the quest.');
      } else if (names.checked && !names.quests.contains(name)) {
        warn('No behavior sets quest $name.');
      }
      if (!branch.arguments.containsKey('equals') && !branch.arguments.containsKey('atLeast') && !branch.arguments.containsKey('atMost')) {
        error('Give a stage.');
      }
    case 'animus:has-permission':
      if ((asString(branch.arguments['permission']) ?? '').isEmpty) error('Name the permission.');
  }
}

bool _controls(String text) => text.runes.any((int rune) => rune < 0x20 || (rune >= 0x7f && rune < 0xa0));

/// Issues for one step; [option] narrows them to one answer.
List<ConversationIssue> issuesAt(List<ConversationIssue> issues, StepAt at, {int? option}) => <ConversationIssue>[
      for (final ConversationIssue issue in issues)
        if (issue.at == at && issue.option == option) issue,
    ];
