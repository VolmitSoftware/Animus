/// The `animus:choices` prompts a behavior asks, for the pickers of its
/// `dialogue_choice` triggers.
library;

import '../gui/gui_json.dart';
import '../offline/new_documents.dart';
import 'reference_options.dart';

const String choicesCapability = 'animus:choices';

class DialoguePromptOption {
  const DialoguePromptOption(this.id, this.text);

  final String id;
  final String text;
}

class DialoguePrompt {
  const DialoguePrompt(this.id, this.prompt, this.options);

  final String id;
  final String prompt;
  final List<DialoguePromptOption> options;
}

/// Every prompt in [behavior]'s routines, in document order. A prompt id
/// asked in more than one place is listed once with all its options.
List<DialoguePrompt> dialoguePrompts(JsonMap behavior) {
  final Map<String, String> prompts = <String, String>{};
  final Map<String, Map<String, String>> options = <String, Map<String, String>>{};
  void visit(Object? value) {
    if (value is List<Object?>) {
      value.forEach(visit);
      return;
    }
    if (value is! Map<String, Object?>) return;
    if (value['type'] == 'action' && value['capability'] == choicesCapability) {
      final JsonMap arguments = asMap(value['arguments']);
      final String id = asString(arguments['id']) ?? '';
      if (id.isNotEmpty) {
        final String prompt = asString(arguments['prompt']) ?? '';
        if ((prompts[id] ?? '').isEmpty) prompts[id] = prompt;
        final Map<String, String> known = options.putIfAbsent(id, () => <String, String>{});
        for (final Object? option in asList(arguments['options'])) {
          final String optionId = asString(asMap(option)['id']) ?? '';
          if (optionId.isNotEmpty) known.putIfAbsent(optionId, () => asString(asMap(option)['text']) ?? '');
        }
      }
    }
    value.values.forEach(visit);
  }

  visit(behavior['routines']);
  return <DialoguePrompt>[
    for (final MapEntry<String, String> prompt in prompts.entries)
      DialoguePrompt(prompt.key, prompt.value, <DialoguePromptOption>[
        for (final MapEntry<String, String> option in options[prompt.key]!.entries) DialoguePromptOption(option.key, option.value),
      ]),
  ];
}

String _label(String id, String text) => text.isEmpty ? id : '$id: $text';

/// The prompts as picker options, labelled with their text.
PickerOptions promptOptions(List<DialoguePrompt> prompts) => PickerOptions(
      <String>[for (final DialoguePrompt prompt in prompts) prompt.id],
      labels: <String, String>{for (final DialoguePrompt prompt in prompts) prompt.id: _label(prompt.id, prompt.prompt)},
    );

/// The options of prompt [dialogue]; none when it is not asked here.
PickerOptions choiceOptions(List<DialoguePrompt> prompts, String? dialogue) {
  final List<DialoguePromptOption> options =
      prompts.where((DialoguePrompt prompt) => prompt.id == dialogue).firstOrNull?.options ?? const <DialoguePromptOption>[];
  return PickerOptions(
    <String>[for (final DialoguePromptOption option in options) option.id],
    labels: <String, String>{for (final DialoguePromptOption option in options) option.id: _label(option.id, option.text)},
  );
}

/// [trigger] answering prompt [dialogue]; a `choice` that prompt does not
/// offer is dropped.
JsonMap withDialogue(JsonMap trigger, String? dialogue, List<DialoguePrompt> prompts) {
  final JsonMap next = asMap(writeIn(trigger, const <JsonStep>['dialogue'], dialogue));
  final String? choice = asString(next['choice']);
  if (choice != null && !choiceOptions(prompts, dialogue).ids.contains(choice)) next.remove('choice');
  return next;
}

/// A trigger id for answering [dialogue] with [choice] that [taken] lacks.
String dialogueTriggerId(String dialogue, String? choice, Iterable<String> taken) =>
    freeId(slug(choice == null ? dialogue : '$dialogue-$choice', fallback: 'answer'), taken.toSet());

/// Whether [behavior] has a `dialogue_choice` trigger.
bool hasDialogueTriggers(JsonMap behavior) => asList(behavior['triggers']).any((Object? trigger) => asMap(trigger)['event'] == 'dialogue_choice');
