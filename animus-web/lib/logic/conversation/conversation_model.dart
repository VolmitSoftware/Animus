/// A conversation: a behavior document read as lines, choices, branches and
/// actions. Each block is one routine of the behavior; a choice leads to a
/// block through its `dialogue_choice` trigger, and a go-to step is a call.
/// Every step keeps the JSON it was read from, so keys the conversation does
/// not edit survive a rewrite.
library;

import '../gui/gui_json.dart';

const String sayCapability = 'animus:say';
const String choicesCapability = 'animus:choices';
const String playSoundCapability = 'animus:play-sound';
const String playAnimationCapability = 'animus:play-animation';

/// What starts the conversation.
enum ConversationStartKind {
  click('On click', 'interact', 'A player right-clicks the NPC.'),
  sneakClick('On sneak click', 'sneak_interact', 'Right-click while sneaking. A shop on the NPC still opens without sneaking.'),
  leftClick('On left-click', 'attack', 'A player hits the NPC.'),
  sneakLeftClick('On sneak left-click', 'sneak_attack', 'Left-click while sneaking.'),
  drop('On drop key', 'drop', 'Q while looking at the NPC.'),
  sneakDrop('On sneak drop key', 'sneak_drop', 'Q while sneaking.'),
  swapHands('On swap hands', 'swap_hands', 'F while looking at the NPC.'),
  sneakSwapHands('On sneak swap hands', 'sneak_swap_hands', 'F while sneaking.'),
  proximity('Player nearby', null, 'A player walks within a radius.'),
  manual('By command', null, 'Only /animus behaviors run starts it.');

  const ConversationStartKind(this.label, this.action, this.line);

  final String label;

  /// One line under the label in the picker.
  final String line;

  /// The interaction trigger's `action`, or null for a start that is no click or key.
  final String? action;
}

class ConversationStart {
  ConversationStart(this.kind, {this.radius = 6, JsonMap? trigger, this.gate}) : trigger = trigger ?? <String, Object?>{};

  ConversationStartKind kind;

  /// Blocks, for [ConversationStartKind.proximity].
  num radius;

  /// The trigger as written, so its cooldown and other keys stay.
  JsonMap trigger;

  /// The routine an older sneak start used to check sneaking. It is not a
  /// block, and a rewrite drops it for the sneak_interact action.
  String? gate;

  ConversationStart copy() => ConversationStart(kind, radius: radius, trigger: asMap(deepCopy(trigger)), gate: gate);
}

/// Steps in order. [sequence] is false for a routine written as one bare
/// node, which stays bare while it holds one node.
class StepList {
  StepList(this.steps, {this.sequence = true});

  final List<ConversationStep> steps;
  bool sequence;

  StepList copy() => StepList(<ConversationStep>[for (final ConversationStep step in steps) step.copy()], sequence: sequence);
}

sealed class ConversationStep {
  ConversationStep(JsonMap? raw) : raw = raw ?? <String, Object?>{};

  /// The node as written.
  JsonMap raw;

  ConversationStep copy();
}

/// The NPC says [text], after [delayTicks], with an optional sound and
/// animation.
final class ConversationLine extends ConversationStep {
  ConversationLine(this.text, {this.delayTicks, this.sound, this.animation, JsonMap? raw}) : super(raw);

  String text;
  int? delayTicks;
  String? sound;
  String? animation;

  @override
  ConversationLine copy() => ConversationLine(text, delayTicks: delayTicks, sound: sound, animation: animation, raw: asMap(deepCopy(raw)));
}

class ConversationOption {
  ConversationOption(this.id, this.text, {this.color, this.hover, this.style, this.next, this.trigger, JsonMap? raw, JsonMap? triggerRaw})
      : raw = raw ?? <String, Object?>{},
        triggerRaw = triggerRaw ?? <String, Object?>{};

  /// Kept once made, so renaming the text keeps the trigger matching.
  String id;
  String text;
  String? color;
  String? hover;
  String? style;

  /// The block this answer leads to; null ends the conversation.
  String? next;

  /// The id of the `dialogue_choice` trigger that runs [next].
  String? trigger;
  JsonMap raw;
  JsonMap triggerRaw;

  ConversationOption copy() => ConversationOption(id, text,
      color: color, hover: hover, style: style, next: next, trigger: trigger, raw: asMap(deepCopy(raw)), triggerRaw: asMap(deepCopy(triggerRaw)));
}

/// The player picks one of [options] (`animus:choices`).
final class ConversationChoices extends ConversationStep {
  ConversationChoices(this.dialogue, this.prompt, this.options, {JsonMap? raw}) : super(raw);

  /// The prompt id `dialogue_choice` triggers match.
  String dialogue;
  String prompt;
  final List<ConversationOption> options;

  @override
  ConversationChoices copy() =>
      ConversationChoices(dialogue, prompt, <ConversationOption>[for (final ConversationOption option in options) option.copy()], raw: asMap(deepCopy(raw)));
}

/// A condition: a capability with arguments, or an expression.
final class ConversationBranch extends ConversationStep {
  ConversationBranch({this.capability, JsonMap? arguments, this.expression, StepList? then, StepList? otherwise, JsonMap? raw})
      : arguments = arguments ?? <String, Object?>{},
        then = then ?? StepList(<ConversationStep>[]),
        otherwise = otherwise ?? StepList(<ConversationStep>[]),
        super(raw);

  String? capability;
  JsonMap arguments;
  String? expression;
  StepList then;
  StepList otherwise;

  @override
  ConversationBranch copy() => ConversationBranch(
        capability: capability,
        arguments: asMap(deepCopy(arguments)),
        expression: expression,
        then: then.copy(),
        otherwise: otherwise.copy(),
        raw: asMap(deepCopy(raw)),
      );
}

/// Any other action capability.
final class ConversationAction extends ConversationStep {
  ConversationAction(this.capability, {JsonMap? arguments, JsonMap? raw})
      : arguments = arguments ?? <String, Object?>{},
        super(raw);

  String capability;
  JsonMap arguments;

  @override
  ConversationAction copy() => ConversationAction(capability, arguments: asMap(deepCopy(arguments)), raw: asMap(deepCopy(raw)));
}

/// Continues at another block (a `call` as the last step).
final class ConversationGoto extends ConversationStep {
  ConversationGoto(this.target, {JsonMap? raw}) : super(raw);

  String target;

  @override
  ConversationGoto copy() => ConversationGoto(target, raw: asMap(deepCopy(raw)));
}

/// Stops here (`return`).
final class ConversationEnd extends ConversationStep {
  ConversationEnd({JsonMap? raw}) : super(raw);

  @override
  ConversationEnd copy() => ConversationEnd(raw: asMap(deepCopy(raw)));
}

class Conversation {
  Conversation({required this.entry, required this.start, required this.blocks});

  /// The block a start runs.
  String entry;
  ConversationStart start;

  /// Blocks by routine name, in the document's order.
  final Map<String, StepList> blocks;

  Conversation copy() => Conversation(
        entry: entry,
        start: start.copy(),
        blocks: <String, StepList>{for (final MapEntry<String, StepList> block in blocks.entries) block.key: block.value.copy()},
      );

  /// Every prompt id in use.
  Set<String> get dialogues => <String>{
        for (final StepList block in blocks.values)
          for (final ConversationChoices choices in choicesIn(block)) choices.dialogue,
      };

  /// Every trigger id in use, the start's included.
  Set<String> get triggerIds => <String>{
        if (start.kind != ConversationStartKind.manual) asString(start.trigger['id']) ?? 'start',
        for (final StepList block in blocks.values)
          for (final ConversationChoices choices in choicesIn(block))
            for (final ConversationOption option in choices.options)
              if (option.trigger != null) option.trigger!,
      };

  /// Routine names in use, the sneak gate's included.
  Set<String> get routineNames => <String>{...blocks.keys, if (start.gate != null) start.gate!};
}

/// Every choices step in [list], branches included.
Iterable<ConversationChoices> choicesIn(StepList list) sync* {
  for (final ConversationStep step in list.steps) {
    if (step is ConversationChoices) yield step;
    if (step is ConversationBranch) {
      yield* choicesIn(step.then);
      yield* choicesIn(step.otherwise);
    }
  }
}

/// Where a step is: its block and the route down to it, such as `[2]` or
/// `[1, 'then', 0]` for the first step of the third step's yes branch.
class StepAt {
  const StepAt(this.block, this.route);

  final String block;
  final List<Object> route;

  int get index => route.last as int;

  /// The list holding the step.
  List<Object> get listRoute => route.sublist(0, route.length - 1);

  StepAt sibling(int index) => StepAt(block, <Object>[...listRoute, index]);

  StepAt child(String side, int index) => StepAt(block, <Object>[...route, side, index]);

  /// A stable text form, for element ids and issue keys.
  String get key => '$block:${route.join('.')}';

  @override
  bool operator ==(Object other) => other is StepAt && other.key == key;

  @override
  int get hashCode => key.hashCode;
}

/// The list at [block] and [listRoute], or null when the route is stale.
StepList? listAt(Conversation conversation, String block, List<Object> listRoute) {
  StepList? list = conversation.blocks[block];
  for (int index = 0; index + 1 < listRoute.length && list != null; index += 2) {
    final int at = listRoute[index] as int;
    if (at >= list.steps.length) return null;
    final ConversationStep step = list.steps[at];
    if (step is! ConversationBranch) return null;
    list = listRoute[index + 1] == 'then' ? step.then : step.otherwise;
  }
  return list;
}

ConversationStep? stepAt(Conversation conversation, StepAt at) {
  final StepList? list = listAt(conversation, at.block, at.listRoute);
  if (list == null || at.index < 0 || at.index >= list.steps.length) return null;
  return list.steps[at.index];
}
