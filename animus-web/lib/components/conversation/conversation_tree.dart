/// The conversation as a tree: the entry block, each answer's follow-up
/// nested under it, then blocks only a go-to reaches and blocks nothing
/// reaches. Every step is a row with a drag handle, its fields and a menu;
/// each list ends with buttons that add a line or another step.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;
import 'package:web/web.dart' as web;

import '../../logic/conversation/conversation_check.dart';
import '../../logic/conversation/conversation_edit.dart';
import '../../logic/conversation/conversation_json.dart';
import '../../logic/conversation/conversation_model.dart';
import '../../logic/inspect/completion_catalog.dart';
import '../../logic/suggest/field_profile.dart';
import '../../logic/suggest/field_profiles.dart';
import '../info_tip.dart';
import 'conversation_fields.dart';
import 'conversation_input.dart';
import 'conversation_picker.dart';
import '../shortcut_sheet.dart' show platformKeys;

/// Runs [change] on the latest conversation and writes it back; edits
/// sharing [coalesce] undo together. [focus] is a field to focus once the
/// page shows it holding [focusValue].
typedef ConversationEdit = void Function(void Function(Conversation conversation) change, {String? coalesce, String? focus, String? focusValue});

/// What the step rows share.
class ConversationScope {
  const ConversationScope({
    required this.conversation,
    required this.issues,
    required this.catalog,
    required this.edit,
    required this.expanded,
    required this.toggle,
    this.suggestions = const ConversationSuggestions(),
  });

  final Conversation conversation;
  final List<ConversationIssue> issues;
  final CompletionCatalog catalog;
  final ConversationEdit edit;

  /// Keys of rows whose extra fields are open.
  final Set<String> expanded;
  final void Function(String key) toggle;
  final ConversationSuggestions suggestions;
}

String lineId(StepAt at) => 'conv-line:${at.key}';
String promptId(StepAt at) => 'conv-prompt:${at.key}';
String optionId(StepAt at, int option) => 'conv-option:${at.key}:$option';

final RegExp _placeholderOptionId = RegExp(r'^option(-\d+)?$');

/// What a dragged row is, so a drop lands only in the same list.
StepAt? _dragStep;
(StepAt, int)? _dragOption;

class ConversationTree extends StatelessWidget {
  const ConversationTree({required this.scope, super.key});

  final ConversationScope scope;

  @override
  Widget build(BuildContext context) => _TreeBuilder(scope).build();
}

class _TreeBuilder {
  _TreeBuilder(this.scope);

  final ConversationScope scope;
  final Set<String> drawn = <String>{};

  Conversation get conversation => scope.conversation;
  ConversationEdit get edit => scope.edit;

  Widget build() {
    final Widget start = _block(conversation.entry, caption: 'Start');
    final List<String> unreached = unreachedBlocks(conversation);
    final List<String> rest = <String>[for (final String name in conversation.blocks.keys) if (!drawn.contains(name)) name];
    return dom.div(classes: 'animus-conv-tree', <Widget>[
      start,
      for (final String name in rest)
        if (!drawn.contains(name)) _block(name, caption: unreached.contains(name) ? 'Not reached' : 'Reached by go to', orphan: unreached.contains(name)),
    ]);
  }

  Widget _block(String name, {required String caption, bool orphan = false}) {
    drawn.add(name);
    final StepList? list = conversation.blocks[name];
    return dom.section(
      classes: 'animus-conv-block${orphan ? ' is-orphan' : ''}',
      attributes: <String, String>{'data-block': name, 'aria-label': 'Block $name'},
      <Widget>[
        dom.div(classes: 'animus-conv-block-head', <Widget>[
          dom.span(classes: 'animus-tag', <Widget>[Component.text(caption)]),
          dom.code(classes: 'animus-muted', <Widget>[Component.text(name)]),
          if (orphan) ...<Widget>[
            const dom.span(classes: 'animus-gui-problem is-warning', <Widget>[Component.text('Nothing leads here.')]),
            Button.ghost(
              label: 'Delete block',
              size: ButtonSize.small,
              attributes: <String, String>{'aria-label': 'Delete block $name'},
              onPressed: () => edit((Conversation c) => c.blocks.remove(name)),
            ),
          ],
        ]),
        if (list != null) _list(list, name, const <Object>[]),
      ],
    );
  }

  Widget _list(StepList list, String block, List<Object> route) {
    final StepAt end = StepAt(block, <Object>[...route, list.steps.length]);
    return dom.div(classes: 'animus-conv-list', <Widget>[
      dom.ol(classes: 'animus-conv-steps', <Widget>[
        for (int index = 0; index < list.steps.length; index++) _step(list.steps[index], StepAt(block, <Object>[...route, index]), list.steps.length),
      ]),
      dom.div(classes: 'animus-conv-add', <Widget>[
        Button.ghost(
          label: 'Line',
          icon: ArcaneIcon.plus(size: IconSize.sm),
          size: ButtonSize.small,
          attributes: <String, String>{'aria-label': 'Add a line to $block${route.isEmpty ? '' : ' ${_branchName(route)}'}', 'data-add-line': end.key},
          onPressed: () => edit((Conversation c) => insertStep(c, end, ConversationLine('')), focus: lineId(end), focusValue: ''),
        ),
        ConversationMenu(
          label: 'Add a step to $block${route.isEmpty ? '' : ' ${_branchName(route)}'}',
          text: 'Step',
          icon: ArcaneIcon.plus(size: IconSize.sm),
          actions: _insertActions(end),
        ),
      ]),
    ]);
  }

  static String _branchName(List<Object> route) => route.last == 'then' ? 'yes branch' : 'no branch';

  /// Menu entries that insert a step of each kind at [at].
  List<MenuAction> _insertActions(StepAt at, {String suffix = ''}) => <MenuAction>[
        MenuAction('Question$suffix', () => edit((Conversation c) => insertStep(c, at, newChoices(c, '', <String>['', ''])), focus: promptId(at), focusValue: '')),
        MenuAction('Condition$suffix', () => edit((Conversation c) => insertStep(c, at, newBranch()))),
        MenuAction('Action$suffix', () => edit((Conversation c) => insertStep(c, at, ConversationAction('animus:set-flag', arguments: <String, Object?>{'name': ''})))),
        MenuAction('Go to$suffix', () => edit((Conversation c) => insertStep(c, at, ConversationGoto(c.entry)))),
        MenuAction('End$suffix', () => edit((Conversation c) => insertStep(c, at, ConversationEnd()))),
      ];

  /// The step a `/word` typed into an empty line turns into.
  static ConversationStep? _slashStep(Conversation c, String text) => switch (text.trim().toLowerCase()) {
        '/ask' || '/question' => newChoices(c, '', <String>['', '']),
        '/if' || '/condition' => newBranch(),
        '/do' || '/action' => ConversationAction('animus:set-flag', arguments: <String, Object?>{'name': ''}),
        '/goto' || '/go' => ConversationGoto(c.entry),
        '/end' => ConversationEnd(),
        _ => null,
      };

  Widget _step(ConversationStep step, StepAt at, int count) {
    final (String kind, String tag) = switch (step) {
      ConversationLine() => ('line', 'NPC'),
      ConversationChoices() => ('choices', 'Asks'),
      ConversationBranch() => ('branch', 'If'),
      ConversationAction() => ('action', 'Does'),
      ConversationGoto() => ('goto', 'Go to'),
      ConversationEnd() => ('end', 'End'),
    };
    final List<ConversationIssue> issues = issuesAt(scope.issues, at);
    return dom.li(
      classes: 'animus-conv-step is-$kind${issues.any((ConversationIssue issue) => issue.error) ? ' has-error' : ''}',
      attributes: <String, String>{'data-step': at.key, 'data-kind': kind},
      events: _dropEvents(at),
      <Widget>[
        dom.div(classes: 'animus-conv-row', <Widget>[
          _handle(at, 'Drag to reorder'),
          dom.span(classes: 'animus-conv-kind', <Widget>[Component.text(tag)]),
          ..._fields(step, at),
          _stepMenu(step, at, count),
        ]),
        ..._issues(issues),
        ..._below(step, at),
      ],
    );
  }

  Widget _handle(StepAt at, String label, {int? option}) => dom.span(
        classes: 'animus-conv-handle',
        attributes: <String, String>{'draggable': 'true', 'title': label, 'aria-hidden': 'true'},
        events: <String, void Function(web.Event)>{
          'dragstart': (web.Event event) {
            event.stopPropagation();
            _dragStep = option == null ? at : null;
            _dragOption = option == null ? null : (at, option);
            (event as web.DragEvent).dataTransfer?.setData('text/plain', at.key);
          },
          'dragend': (web.Event _) {
            _dragStep = null;
            _dragOption = null;
          },
        },
        <Widget>[ArcaneIcon.gripVertical(size: IconSize.sm)],
      );

  Map<String, void Function(web.Event)> _dropEvents(StepAt at) {
    bool same() => _dragStep != null && _dragStep!.block == at.block && _dragStep!.listRoute.join('.') == at.listRoute.join('.');
    return <String, void Function(web.Event)>{
      'dragover': (web.Event event) {
        if (!same()) return;
        event.preventDefault();
        event.stopPropagation();
      },
      'drop': (web.Event event) {
        if (!same()) return;
        event.preventDefault();
        event.stopPropagation();
        final StepAt from = _dragStep!;
        _dragStep = null;
        if (from.index != at.index) edit((Conversation c) => moveStep(c, from, at.index));
      },
    };
  }

  List<Widget> _issues(List<ConversationIssue> issues) => <Widget>[
        for (final ConversationIssue issue in issues)
          dom.span(
            classes: 'animus-gui-problem is-${issue.error ? 'error' : 'warning'}',
            attributes: const <String, String>{'role': 'note'},
            <Widget>[Component.text(issue.message)],
          ),
      ];

  Widget _stepMenu(ConversationStep step, StepAt at, int count) => ConversationMenu(
        label: 'Step options',
        icon: ArcaneIcon.ellipsis(size: IconSize.sm),
        actions: <MenuAction>[
          MenuAction('Add line below', () => edit((Conversation c) => insertStep(c, at.sibling(at.index + 1), ConversationLine('')), focus: lineId(at.sibling(at.index + 1)), focusValue: ''), hint: 'Enter'),
          ..._insertActions(at.sibling(at.index + 1), suffix: ' below'),
          if (step is ConversationLine) MenuAction('Delay, sound, animation', () => scope.toggle(at.key)),
          MenuAction('Move up', at.index == 0 ? null : () => edit((Conversation c) => moveStep(c, at, at.index - 1)), hint: 'Alt+Up'),
          MenuAction('Move down', at.index >= count - 1 ? null : () => edit((Conversation c) => moveStep(c, at, at.index + 1)), hint: 'Alt+Down'),
          MenuAction('Duplicate', () => edit((Conversation c) => duplicateStep(c, at)), hint: platformKeys('Mod+D')),
          MenuAction('Delete', () => edit((Conversation c) => removeStep(c, at))),
        ],
      );

  /// Keys every step field shares: Alt+arrows move the step, arrows move
  /// between fields, Ctrl+D duplicates.
  bool _commonKeys(web.KeyboardEvent event, web.HTMLInputElement input, StepAt at, String Function(StepAt at) idOf) {
    final bool modifier = event.ctrlKey || event.metaKey;
    if (event.altKey && (event.key == 'ArrowUp' || event.key == 'ArrowDown')) {
      final int to = at.index + (event.key == 'ArrowUp' ? -1 : 1);
      final StepList? list = listAt(conversation, at.block, at.listRoute);
      if (list == null || to < 0 || to >= list.steps.length) return true;
      input.blur();
      edit((Conversation c) => moveStep(c, at, to), focus: idOf(at.sibling(to)), focusValue: input.value);
      return true;
    }
    if (!event.altKey && !modifier && (event.key == 'ArrowUp' || event.key == 'ArrowDown')) return focusNeighbour(input, event.key == 'ArrowUp' ? -1 : 1);
    if (modifier && event.key.toLowerCase() == 'd') {
      edit((Conversation c) => duplicateStep(c, at), focus: idOf(at.sibling(at.index + 1)));
      return true;
    }
    return false;
  }

  bool _lineKeys(web.KeyboardEvent event, web.HTMLInputElement input, StepAt at) {
    final bool modifier = event.ctrlKey || event.metaKey;
    if (event.key == 'Enter' && !event.shiftKey && !modifier && !event.altKey) {
      final String text = input.value;
      if (text.startsWith('/')) {
        if (_slashStep(conversation, text) == null) return true;
        edit((Conversation c) {
          final ConversationStep? made = _slashStep(c, text);
          if (made != null) replaceStep(c, at, made);
        }, focus: text.trim() == '/ask' || text.trim() == '/question' ? promptId(at) : null, focusValue: '');
        return true;
      }
      if (inlineChoices(text) != null) {
        edit((Conversation c) => lineToChoices(c, at), focus: optionId(at, 0));
        return true;
      }
      final StepAt next = at.sibling(at.index + 1);
      edit((Conversation c) => insertStep(c, next, ConversationLine('')), focus: lineId(next), focusValue: '');
      return true;
    }
    if (event.key == 'Backspace' && input.value.isEmpty && !modifier) {
      focusNeighbour(input, -1);
      edit((Conversation c) => removeStep(c, at));
      return true;
    }
    return _commonKeys(event, input, at, lineId);
  }

  List<Widget> _fields(ConversationStep step, StepAt at) => switch (step) {
        ConversationLine(:final String text) => <Widget>[
            ConversationInput(
              id: lineId(at),
              value: text,
              label: 'NPC line ${at.index + 1} in ${at.block}',
              placeholder: 'Line, or Question? [Yes] [No]',
              profile: argumentProfile(sayCapability, 'text'),
              onChanged: (String value) => edit((Conversation c) {
                final ConversationStep? line = stepAt(c, at);
                if (line is ConversationLine) line.text = value;
              }, coalesce: 'text:${at.key}'),
              onKey: (web.KeyboardEvent event, web.HTMLInputElement input) => _lineKeys(event, input, at),
            ),
            PlaceholderMenu(field: lineId(at)),
          ],
        ConversationChoices(:final String prompt) => <Widget>[
            ConversationInput(
              id: promptId(at),
              value: prompt,
              label: 'Question in ${at.block}',
              placeholder: 'Question',
              profile: argumentProfile(choicesCapability, 'prompt'),
              onChanged: (String value) => edit((Conversation c) {
                final ConversationStep? choices = stepAt(c, at);
                if (choices is ConversationChoices) choices.prompt = value;
              }, coalesce: 'prompt:${at.key}'),
              onKey: (web.KeyboardEvent event, web.HTMLInputElement input) {
                if (event.key == 'Enter' && !event.shiftKey) {
                  if ((step).options.isEmpty) {
                    edit((Conversation c) => _addOption(c, at, 0), focus: optionId(at, 0), focusValue: '');
                  } else {
                    focusField(optionId(at, 0));
                  }
                  return true;
                }
                return _commonKeys(event, input, at, promptId);
              },
            ),
            PlaceholderMenu(field: promptId(at)),
          ],
        ConversationBranch() => <Widget>[
            ConditionFields(scope: scope, branch: step, at: at),
          ],
        ConversationAction() => <Widget>[
            ActionPicker(scope: scope, action: step, at: at),
          ],
        ConversationGoto(:final String target) => <Widget>[
            ConversationPicker(
              label: 'Go to',
              value: target,
              placeholder: 'Pick a block',
              options: <PickOption>[for (final String name in conversation.blocks.keys) PickOption(name, name, hint: name == conversation.entry ? 'start' : null)],
              onPick: (String value) => edit((Conversation c) {
                final ConversationStep? goto = stepAt(c, at);
                if (goto is ConversationGoto) goto.target = value;
              }),
            ),
          ],
        ConversationEnd() => <Widget>[const dom.span(classes: 'animus-muted', <Widget>[Component.text('Stops here.')])],
      };

  List<Widget> _below(ConversationStep step, StepAt at) => switch (step) {
        ConversationLine() => <Widget>[
            if (scope.expanded.contains(at.key) || step.delayTicks != null || step.sound != null || step.animation != null)
              LineExtras(scope: scope, line: step, at: at),
          ],
        ConversationChoices(:final List<ConversationOption> options) => <Widget>[
            dom.ol(classes: 'animus-conv-options', attributes: <String, String>{'aria-label': 'Answers'}, <Widget>[
              for (int index = 0; index < options.length; index++) _option(step, at, index),
            ]),
            if (options.length < maximumOptions)
              dom.div(classes: 'animus-conv-add', <Widget>[
                Button.ghost(
                  label: 'Answer',
                  icon: ArcaneIcon.plus(size: IconSize.sm),
                  size: ButtonSize.small,
                  attributes: <String, String>{'aria-label': 'Add an answer to ${step.dialogue}'},
                  onPressed: () => edit((Conversation c) => _addOption(c, at, options.length), focus: optionId(at, options.length), focusValue: ''),
                ),
              ]),
          ],
        ConversationBranch(:final StepList then, :final StepList otherwise) => <Widget>[
            dom.div(classes: 'animus-conv-branches', <Widget>[
              dom.div(classes: 'animus-conv-branch', attributes: const <String, String>{'data-branch': 'then'}, <Widget>[
                const dom.span(classes: 'animus-tag', <Widget>[Component.text('Yes')]),
                _list(then, at.block, <Object>[...at.route, 'then']),
              ]),
              dom.div(classes: 'animus-conv-branch', attributes: const <String, String>{'data-branch': 'else'}, <Widget>[
                const dom.span(classes: 'animus-tag', <Widget>[Component.text('No')]),
                _list(otherwise, at.block, <Object>[...at.route, 'else']),
              ]),
            ]),
          ],
        ConversationAction() => <Widget>[ActionArguments(scope: scope, action: step, at: at)],
        _ => const <Widget>[],
      };

  static void _addOption(Conversation c, StepAt at, int index) {
    final ConversationStep? step = stepAt(c, at);
    if (step is! ConversationChoices || step.options.length >= maximumOptions) return;
    step.options.insert(index.clamp(0, step.options.length), newOption(step, ''));
  }

  Widget _option(ConversationChoices choices, StepAt at, int index) {
    final ConversationOption option = choices.options[index];
    final String? next = option.next;
    final bool nests = next != null && conversation.blocks.containsKey(next) && !drawn.contains(next);
    final List<ConversationIssue> issues = issuesAt(scope.issues, at, option: index);
    final String key = '${at.key}/$index';
    void follow() {
      if (next != null && conversation.blocks.containsKey(next)) {
        final StepList list = conversation.blocks[next]!;
        if (list.steps.isNotEmpty && list.steps.first is ConversationLine) focusField(lineId(StepAt(next, const <Object>[0])));
        return;
      }
      edit((Conversation c) => addFollowUp(c, at, index), focus: lineId(StepAt(followUpName(conversation, option), const <Object>[0])), focusValue: '');
    }

    return dom.li(
      classes: 'animus-conv-option${issues.any((ConversationIssue issue) => issue.error) ? ' has-error' : ''}',
      attributes: <String, String>{'data-option': option.id},
      events: <String, void Function(web.Event)>{
        'dragover': (web.Event event) {
          if (_dragOption?.$1 != at) return;
          event.preventDefault();
          event.stopPropagation();
        },
        'drop': (web.Event event) {
          final (StepAt, int)? from = _dragOption;
          if (from == null || from.$1 != at) return;
          event.preventDefault();
          event.stopPropagation();
          _dragOption = null;
          edit((Conversation c) => _moveOption(c, at, from.$2, index));
        },
      },
      <Widget>[
        dom.div(classes: 'animus-conv-row', <Widget>[
          _handle(at, 'Drag to reorder', option: index),
          if (option.color != null)
            dom.span(classes: 'animus-conv-swatch', attributes: <String, String>{'data-color': option.color!, 'title': option.color!}, const <Widget>[]),
          ConversationInput(
            id: optionId(at, index),
            value: option.text,
            label: 'Answer ${index + 1} of ${choices.dialogue}',
            placeholder: 'Answer',
            profile: FieldProfile.richText,
            onChanged: (String value) => edit((Conversation c) {
              final ConversationStep? step = stepAt(c, at);
              if (step is! ConversationChoices || index >= step.options.length) return;
              final ConversationOption edited = step.options[index]..text = value;
              // An answer nothing follows yet takes its id from its text.
              if (edited.next == null && edited.trigger == null && (value.isNotEmpty || _placeholderOptionId.hasMatch(edited.id))) {
                edited.id = freeId(identifierFrom(value, fallback: 'option'), <String>[for (final ConversationOption other in step.options) if (!identical(other, edited)) other.id], length: 32);
              }
            }, coalesce: 'option:${at.key}:$index'),
            onKey: (web.KeyboardEvent event, web.HTMLInputElement input) => _optionKeys(event, input, choices, at, index, follow),
          ),
          if (next == null)
            Button.ghost(
              icon: ArcaneIcon.plus(size: IconSize.sm),
              size: ButtonSize.small,
              attributes: <String, String>{'aria-label': 'Add what follows ${option.text.isEmpty ? 'answer ${index + 1}' : option.text}', 'title': 'Add what follows (${platformKeys('Mod+Enter')})'},
              onPressed: follow,
            ),
          ConversationMenu(
            label: 'Answer options',
            icon: ArcaneIcon.ellipsis(size: IconSize.sm),
            actions: <MenuAction>[
              MenuAction(next == null ? 'Add what follows' : 'Edit what follows', follow, hint: platformKeys('Mod+Enter')),
              MenuAction('Color, hover, target', () => scope.toggle(key)),
              MenuAction('Move up', index == 0 ? null : () => edit((Conversation c) => _moveOption(c, at, index, index - 1)), hint: 'Alt+Up'),
              MenuAction('Move down', index >= choices.options.length - 1 ? null : () => edit((Conversation c) => _moveOption(c, at, index, index + 1)), hint: 'Alt+Down'),
              MenuAction('Duplicate', choices.options.length >= maximumOptions ? null : () => edit((Conversation c) => duplicateOption(c, at, index)), hint: platformKeys('Mod+D')),
              MenuAction('Delete', () => edit((Conversation c) => _removeOption(c, at, index))),
            ],
          ),
        ]),
        ..._issues(issues),
        if (scope.expanded.contains(key)) OptionExtras(scope: scope, choices: choices, at: at, index: index),
        if (nests)
          dom.div(classes: 'animus-conv-follow', <Widget>[_block(next, caption: 'Then')])
        else if (next != null)
          dom.div(classes: 'animus-conv-follow is-link', <Widget>[
            const dom.span(classes: 'animus-muted', <Widget>[Component.text('Goes to ')]),
            dom.code(<Widget>[Component.text(next)]),
          ]),
      ],
    );
  }

  bool _optionKeys(web.KeyboardEvent event, web.HTMLInputElement input, ConversationChoices choices, StepAt at, int index, void Function() follow) {
    final bool modifier = event.ctrlKey || event.metaKey;
    final int count = choices.options.length;
    if (event.key == 'Enter' && modifier) {
      follow();
      return true;
    }
    if (event.key == 'Enter' && !event.shiftKey && !event.altKey) {
      if (count >= maximumOptions) return true;
      edit((Conversation c) => _addOption(c, at, index + 1), focus: optionId(at, index + 1), focusValue: '');
      return true;
    }
    if (event.key == 'Backspace' && input.value.isEmpty && !modifier && count > 1) {
      focusNeighbour(input, -1);
      edit((Conversation c) => _removeOption(c, at, index));
      return true;
    }
    if (event.altKey && (event.key == 'ArrowUp' || event.key == 'ArrowDown')) {
      final int to = index + (event.key == 'ArrowUp' ? -1 : 1);
      if (to < 0 || to >= count) return true;
      input.blur();
      edit((Conversation c) => _moveOption(c, at, index, to), focus: optionId(at, to), focusValue: input.value);
      return true;
    }
    if (!event.altKey && !modifier && (event.key == 'ArrowUp' || event.key == 'ArrowDown')) return focusNeighbour(input, event.key == 'ArrowUp' ? -1 : 1);
    if (modifier && event.key.toLowerCase() == 'd') {
      if (count < maximumOptions) edit((Conversation c) => duplicateOption(c, at, index), focus: optionId(at, index + 1));
      return true;
    }
    return false;
  }

  static void _moveOption(Conversation c, StepAt at, int from, int to) {
    final ConversationStep? step = stepAt(c, at);
    if (step is! ConversationChoices || from >= step.options.length || to < 0 || to >= step.options.length) return;
    step.options.insert(to, step.options.removeAt(from));
  }

  static void _removeOption(Conversation c, StepAt at, int index) {
    final ConversationStep? step = stepAt(c, at);
    if (step is ConversationChoices && index < step.options.length) step.options.removeAt(index);
  }
}

/// Inserts `{player_name}` or `{npc.name}` at the caret of [field].
class PlaceholderMenu extends StatelessWidget {
  const PlaceholderMenu({required this.field, super.key});

  final String field;

  @override
  Widget build(BuildContext context) => ConversationMenu(
        label: 'Insert a placeholder',
        icon: ArcaneIcon.braces(size: IconSize.sm),
        actions: <MenuAction>[
          MenuAction('Player name', () => insertAtCaret(field, playerNamePlaceholder), hint: playerNamePlaceholder),
          MenuAction('NPC name', () => insertAtCaret(field, npcNamePlaceholder), hint: npcNamePlaceholder),
        ],
      );
}

/// The heading tip that lists the keys.
Widget conversationKeysTip(String label) => dom.h2(classes: 'animus-conv-title', <Widget>[
      withTip(
        Component.text(label),
        'Enter adds a line, and a line ending in [Yes] [No] becomes a question. Type /ask, /if, /do, /goto or /end in an empty line; ${platformKeys('Alt')}+arrows move, ${platformKeys('Mod+D')} duplicates, ${platformKeys('Mod+Enter')} on an answer adds what follows.',
        docs: conversationDocs,
      ),
    ]);
