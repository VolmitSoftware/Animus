/// The conversation a behavior holds: what starts it (its triggers), what
/// the NPC says (`animus:say`), the prompts it asks (`animus:choices`) and,
/// under each answer, the routine its `dialogue_choice` trigger runs. Calls
/// are followed inline; a routine already on the way down is shown as a jump
/// back instead of being expanded again.
library;

import '../gui/gui_json.dart';

const String sayCapability = 'animus:say';
const String choicesCapability = 'animus:choices';

/// How deep calls and answers are followed.
const int dialogueDepthLimit = 12;

sealed class DialogueStep {
  const DialogueStep(this.steps);

  /// Where the node is in the behavior document.
  final List<JsonStep> steps;
}

class DialogueSay extends DialogueStep {
  const DialogueSay(super.steps, this.text);

  /// Null when a binding fills it at run time.
  final String? text;
}

class DialogueOption {
  const DialogueOption(this.id, this.text, this.index, this.answers);

  final String id;
  final String text;
  final int index;

  /// What each matching `dialogue_choice` trigger runs.
  final List<DialogueAnswer> answers;
}

class DialogueAnswer {
  const DialogueAnswer(this.triggerId, this.routine, this.steps);

  final String triggerId;
  final String routine;
  final List<DialogueStep> steps;
}

class DialogueChoices extends DialogueStep {
  const DialogueChoices(super.steps, this.id, this.prompt, this.options);

  final String id;
  final String prompt;
  final List<DialogueOption> options;
}

/// A condition with dialogue in either branch.
class DialogueBranch extends DialogueStep {
  const DialogueBranch(super.steps, this.label, this.then, this.otherwise);

  final String label;
  final List<DialogueStep> then;
  final List<DialogueStep> otherwise;
}

/// Any other action, shown as what it does.
class DialogueEffect extends DialogueStep {
  const DialogueEffect(super.steps, this.capability);

  final String capability;
}

/// A call to a routine already being shown above this one.
class DialogueJump extends DialogueStep {
  const DialogueJump(super.steps, this.routine);

  final String routine;
}

/// One way a conversation starts.
class DialogueStart {
  const DialogueStart(this.label, this.routine, this.steps);

  /// `interaction`, `proximity`... or `entry` when no trigger names one.
  final String label;
  final String routine;
  final List<DialogueStep> steps;
}

class DialogueTree {
  const DialogueTree(this.starts, this.unasked);

  final List<DialogueStart> starts;

  /// `dialogue_choice` triggers whose prompt no routine asks.
  final List<String> unasked;

  /// Whether the behavior says or asks anything.
  bool get hasDialogue => starts.any((DialogueStart start) => _talks(start.steps));

  static bool _talks(List<DialogueStep> steps) => steps.any(
    (DialogueStep step) => switch (step) {
      DialogueSay() || DialogueChoices() => true,
      DialogueBranch(:final List<DialogueStep> then, :final List<DialogueStep> otherwise) => _talks(then) || _talks(otherwise),
      _ => false,
    },
  );
}

/// Builds the tree of a behavior document.
DialogueTree dialogueTree(JsonMap behavior) {
  final _Walker walker = _Walker(behavior);
  final List<DialogueStart> starts = <DialogueStart>[];
  for (final JsonMap trigger in walker.triggers) {
    if (trigger['event'] == 'dialogue_choice') continue;
    final String routine = asString(trigger['routine']) ?? walker.entry;
    starts.add(DialogueStart('${trigger['event'] ?? 'trigger'}', routine, walker.routine(routine, const <String>{}, 0)));
  }
  if (starts.isEmpty && walker.routines.containsKey(walker.entry)) {
    starts.add(DialogueStart('entry', walker.entry, walker.routine(walker.entry, const <String>{}, 0)));
  }
  final List<String> unasked = <String>[
    for (final JsonMap trigger in walker.triggers)
      if (trigger['event'] == 'dialogue_choice' && !walker.asked.contains(trigger['dialogue'])) asString(trigger['id']) ?? '${trigger['dialogue']}',
  ];
  return DialogueTree(starts, unasked);
}

class _Walker {
  _Walker(JsonMap behavior)
    : routines = asMap(behavior['routines']),
      entry = asString(behavior['entry']) ?? 'main',
      triggers = <JsonMap>[
        for (final Object? trigger in asList(behavior['triggers']))
          if (trigger is Map<String, Object?>) trigger,
      ];

  final JsonMap routines;
  final String entry;
  final List<JsonMap> triggers;

  /// Prompt ids some routine asks.
  final Set<String> asked = <String>{};

  List<DialogueStep> routine(String name, Set<String> path, int depth) {
    if (depth > dialogueDepthLimit || !routines.containsKey(name)) return const <DialogueStep>[];
    if (path.contains(name)) return <DialogueStep>[DialogueJump(<JsonStep>['routines', name], name)];
    return walk(routines[name], <JsonStep>['routines', name], <String>{...path, name}, depth);
  }

  List<DialogueAnswer> answers(String dialogue, String option, Set<String> path, int depth) => <DialogueAnswer>[
    for (final JsonMap trigger in triggers)
      if (trigger['event'] == 'dialogue_choice' && trigger['dialogue'] == dialogue && (trigger['choice'] == null || trigger['choice'] == option))
        DialogueAnswer(asString(trigger['id']) ?? '', asString(trigger['routine']) ?? entry, routine(asString(trigger['routine']) ?? entry, path, depth + 1)),
  ];

  List<DialogueStep> walk(Object? value, List<JsonStep> at, Set<String> path, int depth) {
    final JsonMap node = asMap(value);
    switch (node['type']) {
      case 'sequence':
      case 'parallel':
        final String key = node['type'] == 'sequence' ? 'steps' : 'branches';
        final JsonList children = asList(node[key]);
        return <DialogueStep>[
          for (int index = 0; index < children.length; index++) ...walk(children[index], <JsonStep>[...at, key, index], path, depth),
        ];
      case 'repeat':
        return walk(node['body'], <JsonStep>[...at, 'body'], path, depth);
      case 'call':
        final String called = asString(node['routine']) ?? '';
        if (path.contains(called)) return <DialogueStep>[DialogueJump(at, called)];
        return routine(called, path, depth + 1);
      case 'condition':
        final List<DialogueStep> then = walk(node['then'], <JsonStep>[...at, 'then'], path, depth);
        final List<DialogueStep> otherwise = node['else'] == null ? const <DialogueStep>[] : walk(node['else'], <JsonStep>[...at, 'else'], path, depth);
        if (then.isEmpty && otherwise.isEmpty) return const <DialogueStep>[];
        return <DialogueStep>[DialogueBranch(at, _conditionLabel(node), then, otherwise)];
      case 'action':
        final String capability = asString(node['capability']) ?? '';
        final JsonMap arguments = asMap(node['arguments']);
        if (capability == sayCapability) return <DialogueStep>[DialogueSay(at, asString(arguments['text']))];
        if (capability == choicesCapability) {
          final String id = asString(arguments['id']) ?? '';
          asked.add(id);
          final JsonList options = asList(arguments['options']);
          return <DialogueStep>[
            DialogueChoices(at, id, asString(arguments['prompt']) ?? '', <DialogueOption>[
              for (int index = 0; index < options.length; index++)
                DialogueOption(
                  asString(asMap(options[index])['id']) ?? '',
                  asString(asMap(options[index])['text']) ?? '',
                  index,
                  depth >= dialogueDepthLimit ? const <DialogueAnswer>[] : answers(id, asString(asMap(options[index])['id']) ?? '', path, depth),
                ),
            ]),
          ];
        }
        if (capability.isEmpty || capability == 'animus:look') return const <DialogueStep>[];
        return <DialogueStep>[DialogueEffect(at, capability)];
      default:
        return const <DialogueStep>[];
    }
  }
}

String _conditionLabel(JsonMap node) {
  final String capability = asString(node['capability']) ?? 'condition';
  final JsonMap arguments = asMap(node['arguments']);
  if (arguments.isEmpty) return capability;
  return '$capability ${arguments.entries.take(3).map((MapEntry<String, Object?> entry) => '${entry.key}=${entry.value}').join(' ')}';
}
