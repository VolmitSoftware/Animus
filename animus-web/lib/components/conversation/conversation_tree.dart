/// The conversation as step cards: the start part, each answer's follow-up
/// nested under it, then parts only a go-to reaches and parts nothing
/// reaches. A card is one step (Say, Ask, If, Do, Go to, End) in a toolbar
/// named after it ("Say 2"): a drag handle, its field and a "..." menu.
/// Steps nested under an answer or a Yes/No branch are rows on a rule, not
/// cards in cards. Every list ends with one "Add step" menu, every question
/// with "Add answer". Part ids, their Start and Then badges and the
/// placeholder entries show in Advanced only.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;
import 'package:web/web.dart' as web;

import '../../logic/conversation/conversation_check.dart';
import '../../logic/conversation/conversation_edit.dart';
import '../../logic/conversation/conversation_json.dart';
import '../../logic/conversation/conversation_model.dart';
import '../../logic/conversation/conversation_words.dart';
import '../../logic/inspect/completion_catalog.dart';
import '../../logic/suggest/field_profile.dart';
import '../../logic/suggest/field_profiles.dart';
import '../../model/animus_document.dart' show DiagnosticSeverity;
import '../../model/display_name.dart' show DocumentNames;
import '../../model/document_kind.dart';
import '../../ui/action_row.dart';
import '../../ui/badge.dart';
import '../../ui/confirm_dialog.dart';
import '../../ui/density.dart';
import '../../ui/menu.dart';
import '../../ui/tone.dart';
import '../select/picker_scope.dart';
import '../shortcut_sheet.dart' show platformKeys;
import 'conversation_fields.dart';
import 'conversation_input.dart';
import 'conversation_picker.dart';
import '../../ui/stack.dart';
import '../../ui/problem_text.dart';

/// Runs [change] on the latest conversation and writes it back; edits
/// sharing [coalesce] undo together. [focus] is a field to focus once the
/// page shows it holding [focusValue].
typedef ConversationEdit = void Function(void Function(Conversation conversation) change, {String? coalesce, String? focus, String? focusValue});

/// What the step cards share.
class ConversationScope {
  const ConversationScope({
    required this.conversation,
    required this.issues,
    required this.catalog,
    required this.edit,
    required this.expanded,
    required this.toggle,
    required this.advanced,
  });

  final Conversation conversation;
  final List<ConversationIssue> issues;
  final CompletionCatalog catalog;
  final ConversationEdit edit;

  /// Keys of cards whose settings are open.
  final Set<String> expanded;
  final void Function(String key) toggle;

  /// Shows part ids, their badges and the placeholder menu.
  final bool advanced;

  /// What a part is called here: its id in Advanced, words in Simple.
  String partName(String block) => advanced ? block : blockTitle(conversation, block);
}

String lineId(StepAt at) => 'conv-line:${at.key}';
String promptId(StepAt at) => 'conv-prompt:${at.key}';
String optionId(StepAt at, int option) => 'conv-option:${at.key}:$option';

final RegExp _placeholderOptionId = RegExp(r'^option(-\d+)?$');

/// What a dragged card is, so a drop lands only in the same list.
StepAt? _dragStep;
(StepAt, int)? _dragOption;

/// The kinds "Add step" offers, each with a few words on what it does.
const List<(String, String)> stepKinds = <(String, String)>[
  ('Say', 'NPC line'),
  ('Ask', 'With answers'),
  ('If', 'Yes or no'),
  ('Do', 'Give, set, open'),
  ('Go to', 'Jump to part'),
  ('End', 'Stop here'),
];

/// A destructive menu entry that asks [question] first, when there is one.
AppMenuEntry deleteEntry(String label, String? question, void Function() remove) => AppMenuEntry(
      label,
      () async {
        if (question != null && !await askToConfirm(title: question, message: 'Undo brings it back.', confirmLabel: label, destructive: true)) return;
        remove();
      },
      destructive: true,
    );

class ConversationTree extends StatelessWidget {
  const ConversationTree({required this.scope, super.key});

  final ConversationScope scope;

  @override
  Widget build(BuildContext context) => _TreeBuilder(scope, (DocumentKind kind, String id) => PickerScope.nameIn(context, kind, id)).build();
}

class _TreeBuilder {
  _TreeBuilder(this.scope, this.names);

  final ConversationScope scope;

  /// Names the documents a step opens or plays.
  final DocumentNames names;
  final Set<String> drawn = <String>{};

  Conversation get conversation => scope.conversation;
  ConversationEdit get edit => scope.edit;
  bool get advanced => scope.advanced;

  Widget build() {
    drawn.add(conversation.entry);
    final StepList? entry = conversation.blocks[conversation.entry];
    final Widget start = dom.section(
      classes: 'animus-conv-part is-start',
      attributes: <String, String>{'data-block': conversation.entry, 'aria-label': advanced ? 'Part ${conversation.entry}' : 'Start'},
      <Widget>[
        stack(<Widget>[
          if (advanced) _partHead(conversation.entry, 'Start'),
          if (entry != null) _list(entry, conversation.entry, const <Object>[], cards: true),
        ]),
      ],
    );
    final Widget main = dom.div(<Widget>[start]);
    final List<String> unreached = unreachedBlocks(conversation);
    final List<String> rest = <String>[for (final String name in conversation.blocks.keys) if (!drawn.contains(name)) name];
    final List<Widget> others = <Widget>[
      for (final String name in rest)
        if (!drawn.contains(name)) _part(name, orphan: unreached.contains(name)),
    ];
    return stack(spacing: 16, <Widget>[
      main,
      if (others.isNotEmpty) ...<Widget>[
        const dom.h3(<Widget>[Component.text('Other parts')]),
        ...others,
      ],
    ]);
  }

  /// A part that is not under an answer: one a go-to reaches, or one
  /// nothing reaches (with a warning and Delete).
  Widget _part(String name, {required bool orphan}) {
    drawn.add(name);
    final StepList? list = conversation.blocks[name];
    return dom.section(
      classes: 'animus-conv-part${orphan ? ' is-orphan' : ''}',
      attributes: <String, String>{'data-block': name, 'aria-label': 'Part ${scope.partName(name)}'},
      <Widget>[
        stack(<Widget>[
          ActionRow(
            dense: true,
            label: 'Part ${scope.partName(name)}',
            leading: <Widget>[
              dom.h4(<Widget>[Component.text(blockTitle(conversation, name))]),
              if (advanced) ...<Widget>[
                StatusBadge(orphan ? 'Not reached' : 'Reached by go to', tone: orphan ? BadgeTone.warning : BadgeTone.neutral),
                dom.code(classes: 'animus-conv-id', <Widget>[Text(name, size: FontSize.sm, color: TextColor.muted)]),
              ],
            ],
            more: <AppMenuEntry>[
              if (orphan) deleteEntry('Delete part', 'Delete this part?', () => edit((Conversation c) => c.blocks.remove(name))),
            ],
          ),
          if (orphan) problemLine(DiagnosticSeverity.warning, 'Nothing leads here.'),
          if (list != null) _list(list, name, const <Object>[], cards: true),
        ]),
      ],
    );
  }

  /// Advanced: a part's badge and id over its steps.
  Widget _partHead(String name, String badge) => ActionRow(dense: true, leading: <Widget>[
        StatusBadge(badge),
        dom.code(classes: 'animus-conv-id', <Widget>[Text(name, size: FontSize.sm, color: TextColor.muted)]),
      ]);

  /// The steps of a list and its "Add step" menu; a part's own steps
  /// ([cards]) are the graph's cards, nested ones rows on a rail.
  Widget _list(StepList list, String block, List<Object> route, {bool cards = false}) {
    final StepAt end = StepAt(block, <Object>[...route, list.steps.length]);
    final String where = route.isEmpty ? scope.partName(block) : '${scope.partName(block)}, ${_branchName(route)}';
    return stack(<Widget>[
      dom.ol(classes: 'animus-conv-steps${cards ? ' is-cards' : ''}', attributes: <String, String>{'aria-label': 'Steps of $where'}, <Widget>[
        for (int index = 0; index < list.steps.length; index++) _step(list.steps[index], StepAt(block, <Object>[...route, index]), list.steps.length),
      ]),
      ActionRow(dense: true, label: 'Add to $where', leading: <Widget>[
        AppMenu(label: 'Add step', icon: ArcaneIcon.plus(), outline: true, id: 'conv-add:${end.key}', entries: _kindEntries(end)),
      ]),
    ]);
  }

  static String _branchName(List<Object> route) => route.last == 'then' ? 'Yes' : 'No';

  /// Menu entries that insert a step of each kind at [at].
  List<AppMenuEntry> _kindEntries(StepAt at) => <AppMenuEntry>[
        for (final (String kind, String line) in stepKinds) AppMenuEntry(kind, () => _insert(kind, at), hint: line),
      ];

  void _insert(String kind, StepAt at) => switch (kind) {
        'Say' => edit((Conversation c) => insertStep(c, at, ConversationLine('')), focus: lineId(at), focusValue: ''),
        'Ask' => edit((Conversation c) => insertStep(c, at, newChoices(c, '', <String>['', ''])), focus: promptId(at), focusValue: ''),
        'If' => edit((Conversation c) => insertStep(c, at, newBranch())),
        'Do' => edit((Conversation c) => insertStep(c, at, newAction(c))),
        'Go to' => edit((Conversation c) => insertStep(c, at, ConversationGoto(c.entry))),
        _ => edit((Conversation c) => insertStep(c, at, ConversationEnd())),
      };

  /// The step a `/word` typed into an empty line turns into.
  static ConversationStep? _slashStep(Conversation c, String text) => switch (text.trim().toLowerCase()) {
        '/ask' || '/question' => newChoices(c, '', <String>['', '']),
        '/if' || '/condition' => newBranch(),
        '/do' || '/action' => newAction(c),
        '/goto' || '/go' => ConversationGoto(c.entry),
        '/end' => ConversationEnd(),
        _ => null,
      };

  static String _kindName(ConversationStep step) => switch (step) {
        ConversationLine() => 'line',
        ConversationChoices() => 'choices',
        ConversationBranch() => 'branch',
        ConversationAction() => 'action',
        ConversationGoto() => 'goto',
        ConversationEnd() => 'end',
      };

  Widget _step(ConversationStep step, StepAt at, int count) {
    final String kind = _kindName(step);
    final String heading = stepKind(step);
    final List<ConversationIssue> issues = issuesAt(scope.issues, at);
    final String line = step is ConversationBranch || step is ConversationAction ? stepLine(conversation, step, names: names) : '';
    return dom.li(
      classes: 'animus-conv-step is-$kind${issues.any((ConversationIssue issue) => issue.error) ? ' has-error' : ''}',
      attributes: <String, String>{'data-step': at.key, 'data-kind': kind},
      events: _dropEvents(at),
      <Widget>[
        stack(<Widget>[
          ActionRow(
            dense: true,
            label: '$heading ${at.index + 1}',
            moreId: 'conv-menu:${at.key}',
            leading: <Widget>[
              _handle(at, 'Drag to reorder'),
              dom.span(classes: 'animus-conv-kind', <Widget>[Component.text(heading)]),
              Expanded(child: _field(step, at)),
            ],
            more: _stepEntries(step, at, count, heading),
          ),
          if (line.isNotEmpty) noteLine(line),
          ..._issues(issues),
          ..._below(step, at),
        ]),
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
        for (final ConversationIssue issue in issues) problemLine(issue.error ? DiagnosticSeverity.error : DiagnosticSeverity.warning, issue.message),
      ];

  /// Whether deleting [step] loses anything worth a question.
  static bool _holdsSomething(ConversationStep step) => switch (step) {
        ConversationLine(:final String text) => text.trim().isNotEmpty,
        ConversationGoto() || ConversationEnd() => false,
        _ => true,
      };

  List<AppMenuEntry> _stepEntries(ConversationStep step, StepAt at, int count, String heading) {
    final StepAt below = at.sibling(at.index + 1);
    final String? text = switch (step) {
      ConversationLine() => lineId(at),
      ConversationChoices() => promptId(at),
      _ => null,
    };
    return <AppMenuEntry>[
      AppMenuEntry('Add line below', () => edit((Conversation c) => insertStep(c, below, ConversationLine('')), focus: lineId(below), focusValue: ''), hint: 'Enter'),
      if (step is ConversationLine) AppMenuEntry('Line settings', () => scope.toggle(at.key)),
      if (advanced && text != null) ...<AppMenuEntry>[
        AppMenuEntry('Insert player name', () => insertAtCaret(text, playerNamePlaceholder), hint: playerNamePlaceholder),
        AppMenuEntry('Insert NPC name', () => insertAtCaret(text, npcNamePlaceholder), hint: npcNamePlaceholder),
      ],
      const AppMenuEntry.separator(),
      AppMenuEntry('Move up', at.index == 0 ? null : () => edit((Conversation c) => moveStep(c, at, at.index - 1)), hint: platformKeys('Alt+Up')),
      AppMenuEntry('Move down', at.index >= count - 1 ? null : () => edit((Conversation c) => moveStep(c, at, at.index + 1)), hint: platformKeys('Alt+Down')),
      AppMenuEntry('Duplicate', () => edit((Conversation c) => duplicateStep(c, at)), hint: platformKeys('Mod+D')),
      const AppMenuEntry.separator(),
      deleteEntry('Delete', _holdsSomething(step) ? 'Delete this ${heading == 'Go to' ? 'go-to' : heading.toLowerCase()} step?' : null, () => edit((Conversation c) => removeStep(c, at))),
    ];
  }

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

  Widget _field(ConversationStep step, StepAt at) => switch (step) {
        ConversationLine(:final String text) => formattedTextInput(
              id: lineId(at),
              value: text,
              label: 'Line ${at.index + 1} of ${scope.partName(at.block)}',
              placeholder: 'What the NPC says',
              profile: argumentProfile(sayCapability, 'text'),
              onChanged: (String value) => edit((Conversation c) {
                final ConversationStep? line = stepAt(c, at);
                if (line is ConversationLine) line.text = value;
              }, coalesce: 'text:${at.key}'),
              onKey: (web.KeyboardEvent event, web.HTMLInputElement input) => _lineKeys(event, input, at),
            ),
        ConversationChoices(:final String prompt) => formattedTextInput(
              id: promptId(at),
              value: prompt,
              label: 'Question in ${scope.partName(at.block)}',
              placeholder: 'The question',
              profile: argumentProfile(choicesCapability, 'prompt'),
              onChanged: (String value) => edit((Conversation c) {
                final ConversationStep? choices = stepAt(c, at);
                if (choices is ConversationChoices) choices.prompt = value;
              }, coalesce: 'prompt:${at.key}'),
              onKey: (web.KeyboardEvent event, web.HTMLInputElement input) {
                if (event.key == 'Enter' && !event.shiftKey) {
                  if (step.options.isEmpty) {
                    edit((Conversation c) => _addOption(c, at, 0), focus: optionId(at, 0), focusValue: '');
                  } else {
                    focusField(optionId(at, 0));
                  }
                  return true;
                }
                return _commonKeys(event, input, at, promptId);
              },
            ),
        ConversationBranch() => ConditionPicker(scope: scope, branch: step, at: at),
        ConversationAction() => ActionPicker(scope: scope, action: step, at: at),
        ConversationGoto(:final String target) => ConversationPicker(
              label: 'Go to',
              value: target,
              placeholder: 'Pick a part',
              options: <PickOption>[
                for (final String name in conversation.blocks.keys)
                  PickOption(name, blockTitle(conversation, name), hint: advanced && name != conversation.entry ? name : null),
              ],
              onPick: (String value) => edit((Conversation c) {
                final ConversationStep? goto = stepAt(c, at);
                if (goto is ConversationGoto) goto.target = value;
              }),
            ),
        ConversationEnd() => noteLine('Stops here.'),
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
                ActionRow(dense: true, label: 'Answers', leading: <Widget>[
                  Button.outline(
                    label: 'Add answer',
                    icon: ArcaneIcon.plus().sized(controlIconSize),
                    size: ButtonSize.sm,
                    attributes: <String, String>{'data-add-answer': at.key},
                    onPressed: () => edit((Conversation c) => _addOption(c, at, options.length), focus: optionId(at, options.length), focusValue: ''),
                  ),
                ]),
              ]),
          ],
        ConversationBranch(:final StepList then, :final StepList otherwise) => <Widget>[
            ConditionFields(scope: scope, branch: step, at: at),
            dom.div(classes: 'animus-conv-branch', attributes: const <String, String>{'data-branch': 'then'}, <Widget>[
              stack(<Widget>[noteLine('Yes'), _list(then, at.block, <Object>[...at.route, 'then'])]),
            ]),
            dom.div(classes: 'animus-conv-branch', attributes: const <String, String>{'data-branch': 'else'}, <Widget>[
              stack(<Widget>[noteLine('No'), _list(otherwise, at.block, <Object>[...at.route, 'else'])]),
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
    final String name = option.text.isEmpty ? 'answer ${index + 1}' : option.text;
    void follow() {
      if (next != null && conversation.blocks.containsKey(next)) {
        final StepList list = conversation.blocks[next]!;
        if (list.steps.isNotEmpty && list.steps.first is ConversationLine) focusField(lineId(StepAt(next, const <Object>[0])));
        return;
      }
      edit((Conversation c) => addFollowUp(c, at, index), focus: lineId(StepAt(followUpName(conversation, option), const <Object>[0])), focusValue: '');
    }

    if (nests) drawn.add(next);
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
        stack(<Widget>[
          ActionRow(
            dense: true,
            label: 'Answer ${index + 1}',
            moreId: 'conv-menu:$key',
            leading: <Widget>[
              _handle(at, 'Drag to reorder', option: index),
              if (option.color != null)
                dom.span(
                  classes: 'animus-select-swatch',
                  attributes: <String, String>{'data-color': option.color!, 'title': option.color!},
                  styles: dom.Styles(raw: <String, String>{'background': answerSwatch(option.color!)}),
                  const <Widget>[],
                ),
              Expanded(
                child: formattedTextInput(
                  id: optionId(at, index),
                  value: option.text,
                  label: 'Answer ${index + 1} of ${choices.prompt.isEmpty ? 'the question' : choices.prompt}',
                  placeholder: 'An answer',
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
              ),
            ],
            secondary: <RowAction>[
              if (next == null) RowAction('Add what follows $name', follow, icon: ArcaneIcon.cornerDownRight(), iconOnly: true),
            ],
            more: <AppMenuEntry>[
              AppMenuEntry(next == null ? 'Add what follows' : 'Edit what follows', follow, hint: platformKeys('Mod+Enter')),
              AppMenuEntry('Answer settings', () => scope.toggle(key)),
              const AppMenuEntry.separator(),
              AppMenuEntry('Move up', index == 0 ? null : () => edit((Conversation c) => _moveOption(c, at, index, index - 1)), hint: platformKeys('Alt+Up')),
              AppMenuEntry('Move down', index >= choices.options.length - 1 ? null : () => edit((Conversation c) => _moveOption(c, at, index, index + 1)), hint: platformKeys('Alt+Down')),
              AppMenuEntry('Duplicate', choices.options.length >= maximumOptions ? null : () => edit((Conversation c) => duplicateOption(c, at, index)), hint: platformKeys('Mod+D')),
              const AppMenuEntry.separator(),
              deleteEntry('Delete', next == null ? null : 'Delete this answer?', () => edit((Conversation c) => _removeOption(c, at, index))),
            ],
          ),
          ..._issues(issues),
          if (scope.expanded.contains(key)) OptionExtras(scope: scope, choices: choices, at: at, index: index),
          if (nests)
            dom.div(classes: 'animus-conv-follow', attributes: <String, String>{'data-block': next}, <Widget>[
              stack(<Widget>[
                if (advanced) _partHead(next, 'Then'),
                _list(conversation.blocks[next]!, next, const <Object>[]),
              ]),
            ])
          else if (next != null)
            dom.div(classes: 'animus-conv-follow is-link', <Widget>[
              Text('Goes to ${blockTitle(conversation, next)}', size: FontSize.sm, color: TextColor.muted),
              if (advanced) dom.code(classes: 'animus-conv-id', <Widget>[Text(' $next', size: FontSize.sm, color: TextColor.muted)]),
            ]),
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

/// The keys of the step cards, for the tip on the Steps heading.
String conversationKeys() =>
    'Enter adds a line, and a line ending in [Yes] [No] becomes a question. Type /ask, /if, /do, /goto or /end in an empty line. ${platformKeys('Alt')}+arrows move a step, ${platformKeys('Mod+D')} duplicates, ${platformKeys('Mod+Enter')} on an answer adds what follows.';
