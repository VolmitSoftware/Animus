/// A behavior's conversation as a tree: what starts it, each line the NPC
/// says, each prompt with its answers, and under each answer what the
/// matching `dialogue_choice` trigger runs. Lines, prompts and answers are
/// edited in place; everything else stays in the routine editor below.
///
/// It is drawn as nested lists, not an ARIA tree: every line is a text field,
/// and a tree's single tab stop would take them out of the Tab order.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/gui/gui_json.dart';
import '../../logic/npc/dialogue_tree.dart';
import '../gui/gui_fields.dart';

class DialogueView extends StatelessWidget {
  const DialogueView({required this.tree, required this.write, this.readOnly = false, super.key});

  final DialogueTree tree;

  /// Writes [value] at [steps] in the behavior document.
  final void Function(List<JsonStep> steps, Object? value) write;
  final bool readOnly;

  @override
  Widget build(BuildContext context) {
    return dom.div(classes: 'animus-dialogue', attributes: const <String, String>{'role': 'group', 'aria-label': 'Conversation'}, <Widget>[
      for (final DialogueStart start in tree.starts)
        dom.div(classes: 'animus-dialogue-start', <Widget>[
          dom.div(classes: 'animus-dialogue-head', <Widget>[
            dom.span(classes: 'animus-tag', <Widget>[Component.text(start.label)]),
            dom.span(classes: 'animus-muted', <Widget>[Component.text('runs ${start.routine}')]),
          ]),
          _steps(start.steps),
        ]),
      if (tree.unasked.isNotEmpty)
        dom.p(classes: 'animus-gui-problem is-warning', <Widget>[
          Component.text('No routine asks the prompt of trigger${tree.unasked.length == 1 ? '' : 's'} ${tree.unasked.join(', ')}; ${tree.unasked.length == 1 ? 'it' : 'they'} never run.'),
        ]),
    ]);
  }

  Widget _steps(List<DialogueStep> steps) {
    if (steps.isEmpty) return const dom.p(classes: 'animus-muted', <Widget>[Component.text('Says nothing.')]);
    return dom.ol(classes: 'animus-dialogue-steps', <Widget>[
      for (final DialogueStep step in steps) dom.li(<Widget>[_step(step)]),
    ]);
  }

  Widget _step(DialogueStep step) => switch (step) {
        DialogueSay(:final List<JsonStep> steps, :final String? text) => dom.div(classes: 'animus-dialogue-say', <Widget>[
            const dom.span(classes: 'animus-dialogue-speaker', <Widget>[Component.text('NPC says')]),
            if (text == null)
              const dom.span(classes: 'animus-muted', <Widget>[Component.text('a value bound at run time')])
            else if (readOnly)
              GuiStyledText(text)
            else
              GuiTextInput(
                value: text,
                label: 'Line at ${formatPath(steps)}',
                onChanged: (String value) => write(<JsonStep>[...steps, 'arguments', 'text'], value),
              ),
          ]),
        DialogueChoices(:final List<JsonStep> steps, :final String id, :final String prompt, :final List<DialogueOption> options) => dom.div(classes: 'animus-dialogue-choices', <Widget>[
            dom.div(classes: 'animus-dialogue-say', <Widget>[
              dom.span(classes: 'animus-dialogue-speaker', <Widget>[const Component.text('NPC asks'), dom.code(<Widget>[Component.text(' $id')])]),
              if (readOnly)
                GuiStyledText(prompt)
              else
                GuiTextInput(
                  value: prompt,
                  label: 'Prompt $id',
                  onChanged: (String value) => write(<JsonStep>[...steps, 'arguments', 'prompt'], value),
                ),
            ]),
            dom.ul(classes: 'animus-dialogue-options', <Widget>[
              for (final DialogueOption option in options)
                dom.li(classes: 'animus-dialogue-option', <Widget>[
                  dom.div(classes: 'animus-dialogue-answer', <Widget>[
                    dom.span(classes: 'animus-dialogue-speaker', <Widget>[const Component.text('Player answers'), dom.code(<Widget>[Component.text(' ${option.id}')])]),
                    if (readOnly)
                      GuiStyledText(option.text)
                    else
                      GuiTextInput(
                        value: option.text,
                        label: 'Answer ${option.id} of $id',
                        onChanged: (String value) => write(<JsonStep>[...steps, 'arguments', 'options', option.index, 'text'], value),
                      ),
                  ]),
                  if (option.answers.isEmpty)
                    const dom.p(classes: 'animus-muted', <Widget>[Component.text('No dialogue_choice trigger answers this; the conversation ends.')])
                  else
                    for (final DialogueAnswer answer in option.answers)
                      dom.div(classes: 'animus-dialogue-branch', <Widget>[
                        dom.span(classes: 'animus-muted', <Widget>[Component.text('trigger ${answer.triggerId} runs ${answer.routine}')]),
                        _steps(answer.steps),
                      ]),
                ]),
            ]),
          ]),
        DialogueBranch(:final String label, :final List<DialogueStep> then, :final List<DialogueStep> otherwise) => dom.div(classes: 'animus-dialogue-branchset', <Widget>[
            dom.div(classes: 'animus-dialogue-branch', <Widget>[
              dom.span(classes: 'animus-tag', <Widget>[Component.text('if $label')]),
              _steps(then),
            ]),
            if (otherwise.isNotEmpty)
              dom.div(classes: 'animus-dialogue-branch', <Widget>[
                const dom.span(classes: 'animus-tag', <Widget>[Component.text('otherwise')]),
                _steps(otherwise),
              ]),
          ]),
        DialogueEffect(:final String capability) => dom.span(classes: 'animus-dialogue-effect', <Widget>[dom.code(<Widget>[Component.text(capability)])]),
        DialogueJump(:final String routine) => dom.span(classes: 'animus-dialogue-effect', <Widget>[Component.text('back to $routine')]),
      };
}
