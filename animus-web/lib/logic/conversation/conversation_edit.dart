/// Edits on a [Conversation]: adding, moving, duplicating and removing
/// steps and options, follow-ups, inline choices and the start. New ids are
/// made here once and then kept, so later edits never rename a routine or a
/// trigger the server already knows.
library;

import '../gui/gui_json.dart';
import 'conversation_json.dart';
import 'conversation_model.dart';

/// The most options one prompt takes.
const int maximumOptions = 8;

final RegExp _identifierChars = RegExp(r'[^a-z0-9_-]+');

/// [base] made into an identifier of at most [length] characters.
String identifierFrom(String base, {String fallback = 'item', int length = 32}) {
  String slug = base.toLowerCase().replaceAll(_identifierChars, '-').replaceAll(RegExp(r'-+'), '-');
  slug = slug.replaceAll(RegExp(r'^[-_]+|[-_]+$'), '');
  if (slug.length > length) slug = slug.substring(0, length).replaceAll(RegExp(r'[-_]+$'), '');
  return slug.isEmpty ? fallback : slug;
}

/// [name] without a `-2` style suffix, so a copy of `a-2` is `a-3`.
String _stem(String name) => name.replaceFirst(RegExp(r'-\d+$'), '');

/// [base], or `base-2`, `base-3`... the first one not in [taken].
String freeId(String base, Iterable<String> taken, {int length = 64}) {
  final Set<String> used = taken.toSet();
  if (!used.contains(base)) return base;
  for (int index = 2;; index++) {
    final String suffix = '-$index';
    final String name = '${base.length + suffix.length > length ? base.substring(0, length - suffix.length) : base}$suffix';
    if (!used.contains(name)) return name;
  }
}

/// A question typed as `Need directions? [Yes] [No]`: the prompt and the
/// option texts, or null when [text] does not end in bracketed options.
(String, List<String>)? inlineChoices(String text) {
  final RegExpMatch? match = RegExp(r'^(.*?)\s*((?:\[[^\[\]]+\]\s*)+)$').firstMatch(text.trim());
  if (match == null) return null;
  final List<String> options = <String>[
    for (final RegExpMatch option in RegExp(r'\[([^\[\]]+)\]').allMatches(match.group(2)!))
      if (option.group(1)!.trim().isNotEmpty) option.group(1)!.trim(),
  ];
  if (options.isEmpty) return null;
  return (match.group(1)!.trim(), options.take(maximumOptions).toList());
}

/// A new option with an id made from [text], unique in [choices].
ConversationOption newOption(ConversationChoices choices, String text) =>
    ConversationOption(freeId(identifierFrom(text, fallback: 'option'), choices.options.map((ConversationOption option) => option.id), length: 32), text);

/// A new prompt with a fresh id.
ConversationChoices newChoices(Conversation conversation, String prompt, List<String> options) {
  final ConversationChoices choices = ConversationChoices(freeId('ask', conversation.dialogues), prompt, <ConversationOption>[]);
  for (final String text in options) {
    choices.options.add(newOption(choices, text));
  }
  return choices;
}

void insertStep(Conversation conversation, StepAt at, ConversationStep step) {
  final StepList? list = listAt(conversation, at.block, at.listRoute);
  if (list == null) return;
  list.steps.insert(at.index.clamp(0, list.steps.length), step);
}

void removeStep(Conversation conversation, StepAt at) {
  final StepList? list = listAt(conversation, at.block, at.listRoute);
  if (list == null || at.index >= list.steps.length) return;
  list.steps.removeAt(at.index);
}

void replaceStep(Conversation conversation, StepAt at, ConversationStep step) {
  final StepList? list = listAt(conversation, at.block, at.listRoute);
  if (list == null || at.index >= list.steps.length) return;
  list.steps[at.index] = step;
}

/// Moves the step at [at] to [to] within its list; returns where it went.
StepAt moveStep(Conversation conversation, StepAt at, int to) {
  final StepList? list = listAt(conversation, at.block, at.listRoute);
  if (list == null || at.index >= list.steps.length || to < 0 || to >= list.steps.length) return at;
  list.steps.insert(to, list.steps.removeAt(at.index));
  return at.sibling(to);
}

/// The line at [at] as choices when its text ends in bracketed options.
/// Returns whether it changed.
bool lineToChoices(Conversation conversation, StepAt at) {
  final ConversationStep? step = stepAt(conversation, at);
  if (step is! ConversationLine) return false;
  final (String, List<String>)? typed = inlineChoices(step.text);
  if (typed == null) return false;
  final (String prompt, List<String> options) = typed;
  replaceStep(conversation, at, newChoices(conversation, prompt.isEmpty ? 'Choose one.' : prompt, options));
  return true;
}

/// The name a new follow-up block for [option] gets.
String followUpName(Conversation conversation, ConversationOption option) =>
    freeId(identifierFrom(option.id, fallback: 'answer'), conversation.routineNames);

/// A new block for [option] of the choices at [at], holding one empty line,
/// named after the option. Returns the block's name.
String addFollowUp(Conversation conversation, StepAt at, int option) {
  final ConversationStep? step = stepAt(conversation, at);
  if (step is! ConversationChoices || option >= step.options.length) return '';
  final ConversationOption chosen = step.options[option];
  if (chosen.next != null && conversation.blocks.containsKey(chosen.next)) return chosen.next!;
  final String name = followUpName(conversation, chosen);
  conversation.blocks[name] = StepList(<ConversationStep>[ConversationLine('')]);
  chosen.next = name;
  chosen.trigger ??= freeId(identifierFrom('${step.dialogue}-${chosen.id}', length: 64), conversation.triggerIds);
  return name;
}

/// Sets every answer's trigger id that is not set yet, so ids are made once.
void assignTriggerIds(Conversation conversation) {
  for (final StepList block in conversation.blocks.values) {
    for (final ConversationChoices choices in choicesIn(block)) {
      for (final ConversationOption option in choices.options) {
        if (option.next != null && option.trigger == null) {
          option.trigger = freeId(identifierFrom('${choices.dialogue}-${option.id}', length: 64), conversation.triggerIds);
        }
      }
    }
  }
}

/// A copy of [block] and the blocks its answers lead to that nothing else
/// leads to, under new names. Go-to steps keep their targets. Returns the
/// copy's name.
String duplicateBlock(Conversation conversation, String block) {
  final Map<String, String> renamed = <String, String>{};
  final Map<String, int> incoming = _incoming(conversation);
  String copyOf(String name) {
    final String? done = renamed[name];
    if (done != null) return done;
    final StepList? source = conversation.blocks[name];
    if (source == null) return name;
    final String fresh = freeId(_stem(name), <String>{...conversation.routineNames, ...renamed.values});
    renamed[name] = fresh;
    final StepList copy = source.copy();
    conversation.blocks[fresh] = copy;
    for (final ConversationChoices choices in choicesIn(copy)) {
      _freshPrompt(conversation, choices);
      for (final ConversationOption option in choices.options) {
        final String? next = option.next;
        if (next != null && (incoming[next] ?? 0) <= 1 && next != conversation.entry) option.next = copyOf(next);
      }
    }
    return fresh;
  }

  return copyOf(block);
}

/// Gives a copied prompt a new id and its answers new trigger ids.
void _freshPrompt(Conversation conversation, ConversationChoices choices) {
  choices.dialogue = freeId(identifierFrom(_stem(choices.dialogue), fallback: 'ask', length: 60), conversation.dialogues);
  for (final ConversationOption option in choices.options) {
    option.trigger = null;
    option.triggerRaw = <String, Object?>{};
  }
  assignTriggerIds(conversation);
}

/// How many answers and go-tos lead to each block.
Map<String, int> _incoming(Conversation conversation) {
  final Map<String, int> counts = <String, int>{};
  void count(StepList list) {
    for (final ConversationStep step in list.steps) {
      switch (step) {
        case ConversationChoices(:final List<ConversationOption> options):
          for (final ConversationOption option in options) {
            if (option.next != null) counts[option.next!] = (counts[option.next!] ?? 0) + 1;
          }
        case ConversationGoto(:final String target):
          counts[target] = (counts[target] ?? 0) + 1;
        case ConversationBranch(:final StepList then, :final StepList otherwise):
          count(then);
          count(otherwise);
        default:
          break;
      }
    }
  }

  conversation.blocks.values.forEach(count);
  return counts;
}

/// Duplicates the step at [at] right after it. A prompt gets a new id and
/// its answers copies of their follow-ups.
StepAt duplicateStep(Conversation conversation, StepAt at) {
  final ConversationStep? step = stepAt(conversation, at);
  if (step == null) return at;
  final ConversationStep copy = step.copy();
  final StepAt target = at.sibling(at.index + 1);
  insertStep(conversation, target, copy);
  final List<ConversationChoices> prompts = switch (copy) {
    ConversationChoices() => <ConversationChoices>[copy],
    ConversationBranch() => <ConversationChoices>[...choicesIn(copy.then), ...choicesIn(copy.otherwise)],
    _ => const <ConversationChoices>[],
  };
  for (final ConversationChoices choices in prompts) {
    _freshPrompt(conversation, choices);
    for (final ConversationOption option in choices.options) {
      if (option.next != null) option.next = duplicateBlock(conversation, option.next!);
    }
  }
  assignTriggerIds(conversation);
  return target;
}

/// Duplicates option [index] of the choices at [at], follow-up included.
void duplicateOption(Conversation conversation, StepAt at, int index) {
  final ConversationStep? step = stepAt(conversation, at);
  if (step is! ConversationChoices || index >= step.options.length || step.options.length >= maximumOptions) return;
  final ConversationOption source = step.options[index];
  final ConversationOption copy = source.copy()
    ..id = freeId(_stem(source.id), step.options.map((ConversationOption option) => option.id), length: 32)
    ..trigger = null
    ..triggerRaw = <String, Object?>{};
  if (copy.next != null) copy.next = duplicateBlock(conversation, copy.next!);
  step.options.insert(index + 1, copy);
  assignTriggerIds(conversation);
}

/// Changes how the conversation starts. The trigger keeps its id and
/// cooldown.
void setStart(Conversation conversation, ConversationStartKind kind, {num? radius}) {
  final ConversationStart start = conversation.start;
  if (kind == ConversationStartKind.sneakClick) {
    start.gate ??= freeId('sneak-start', conversation.blocks.keys);
  } else if (start.gate != null) {
    start.gate = null;
    start.trigger.remove('routine');
    start.trigger.remove('consume');
  }
  if (start.kind == ConversationStartKind.manual && kind != ConversationStartKind.manual && !start.trigger.containsKey('id')) {
    start.trigger = <String, Object?>{'id': freeId('start', conversation.triggerIds)};
  }
  start.kind = kind;
  if (radius != null) start.radius = radius;
}

/// Blocks no start, answer or go-to reaches.
List<String> unreachedBlocks(Conversation conversation) {
  final Set<String> seen = <String>{};
  void visit(String name) {
    if (!seen.add(name)) return;
    final StepList? list = conversation.blocks[name];
    if (list != null) _targets(list).forEach(visit);
  }

  visit(conversation.entry);
  return <String>[for (final String name in conversation.blocks.keys) if (!seen.contains(name)) name];
}

Iterable<String> _targets(StepList list) sync* {
  for (final ConversationStep step in list.steps) {
    switch (step) {
      case ConversationChoices(:final List<ConversationOption> options):
        for (final ConversationOption option in options) {
          if (option.next != null) yield option.next!;
        }
      case ConversationGoto(:final String target):
        yield target;
      case ConversationBranch(:final StepList then, :final StepList otherwise):
        yield* _targets(then);
        yield* _targets(otherwise);
      default:
        break;
    }
  }
}

/// A new conversation behavior with id [id]: a right-click start, a greeting
/// and a question with two answers that each say one line.
JsonMap newConversationBehavior(String id) {
  final Conversation conversation = Conversation(
    entry: 'main',
    start: ConversationStart(ConversationStartKind.click, trigger: <String, Object?>{'id': 'start', 'cooldownTicks': 40}),
    blocks: <String, StepList>{'main': StepList(<ConversationStep>[ConversationLine('Hello, $playerNamePlaceholder.')])},
  );
  final ConversationChoices question = newChoices(conversation, 'What brings you here?', <String>['Just looking', 'Goodbye']);
  conversation.blocks['main']!.steps.add(question);
  addFollowUp(conversation, const StepAt('main', <Object>[1]), 0);
  addFollowUp(conversation, const StepAt('main', <Object>[1]), 1);
  (conversation.blocks['just-looking']!.steps.first as ConversationLine).text = 'Take your time.';
  (conversation.blocks['goodbye']!.steps.first as ConversationLine).text = 'Safe travels.';
  return writeConversation(conversation, <String, Object?>{'schemaVersion': 1, 'revision': 1, 'id': id, 'entry': 'main', 'routines': <String, Object?>{}});
}
