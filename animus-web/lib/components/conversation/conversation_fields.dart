/// The fields inside conversation rows: what a condition checks, which
/// action runs and its arguments (from the capability catalog), a line's
/// delay, sound and animation, and an answer's colour, hover and target.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../config/docs_links.dart';
import '../../logic/conversation/conversation_edit.dart';
import '../../logic/conversation/conversation_model.dart';
import '../../logic/conversation/conversation_play.dart';
import '../../logic/gui/gui_json.dart';
import '../../logic/inspect/completion_catalog.dart';
import '../../logic/inspect/schema_fields.dart';
import '../../logic/suggest/completion_sources.dart';
import '../../logic/suggest/field_profile.dart';
import '../../logic/suggest/field_profiles.dart';
import '../../model/document_kind.dart';
import '../gui/gui_fields.dart';
import '../info_tip.dart';
import '../inspect/inspect_fields.dart' show KeptJsonEditor;
import 'conversation_input.dart';
import 'conversation_picker.dart';
import 'conversation_tree.dart';

/// The wiki section on conversations.
const DocLink conversationDocs = DocLink('14-web-editor', 'conversations');

/// Workspace ids the fields offer while typing.
class ConversationSuggestions {
  const ConversationSuggestions({this.animations = const <String>[], this.shops = const <String>[]});

  final List<String> animations;
  final List<String> shops;
}

/// A new condition: a player flag.
ConversationBranch newBranch() => ConversationBranch(capability: 'animus:flag-state', arguments: <String, Object?>{'name': ''});

/// What a condition checks, as the picker names it.
enum ConditionKind {
  flag('Flag'),
  quest('Quest stage'),
  permission('Permission'),
  item('Item held'),
  relationship('Relationship'),
  expression('Expression'),
  other('Other');

  const ConditionKind(this.label);

  final String label;
}

ConditionKind conditionKind(ConversationBranch branch) {
  final String? expression = branch.expression;
  if (expression != null) return heldItemOf(expression) != null ? ConditionKind.item : ConditionKind.expression;
  return switch (branch.capability) {
    'animus:flag-state' => branch.arguments['scope'] == 'relationship' ? ConditionKind.relationship : ConditionKind.flag,
    'animus:quest-state' => ConditionKind.quest,
    'animus:has-permission' => ConditionKind.permission,
    _ => ConditionKind.other,
  };
}

/// [branch] switched to check [kind], keeping a name it already had.
void setConditionKind(ConversationBranch branch, ConditionKind kind) {
  final String name = asString(branch.arguments['name']) ?? '';
  branch
    ..expression = null
    ..capability = null
    ..arguments = <String, Object?>{};
  switch (kind) {
    case ConditionKind.flag:
      branch
        ..capability = 'animus:flag-state'
        ..arguments = <String, Object?>{'name': name};
    case ConditionKind.relationship:
      branch
        ..capability = 'animus:flag-state'
        ..arguments = <String, Object?>{'name': name, 'scope': 'relationship'};
    case ConditionKind.quest:
      branch
        ..capability = 'animus:quest-state'
        ..arguments = <String, Object?>{'name': name, 'atLeast': 1};
    case ConditionKind.permission:
      branch
        ..capability = 'animus:has-permission'
        ..arguments = <String, Object?>{'permission': ''};
    case ConditionKind.item:
      branch.expression = heldItemExpression('minecraft:emerald');
    case ConditionKind.expression || ConditionKind.other:
      branch.expression = 'true';
  }
}

/// Edits the branch at [at] of the latest conversation.
void _editBranch(ConversationScope scope, StepAt at, void Function(ConversationBranch branch) change, {String? coalesce}) => scope.edit((Conversation c) {
      final ConversationStep? step = stepAt(c, at);
      if (step is ConversationBranch) change(step);
    }, coalesce: coalesce);

void _putArgument(JsonMap arguments, String key, Object? value) {
  if (value == null || value == '') {
    arguments.remove(key);
  } else {
    arguments[key] = value;
  }
}

class ConditionFields extends StatelessWidget {
  const ConditionFields({required this.scope, required this.branch, required this.at, super.key});

  final ConversationScope scope;
  final ConversationBranch branch;
  final StepAt at;

  @override
  Widget build(BuildContext context) {
    final ConditionKind kind = conditionKind(branch);
    final String id = 'conv-if:${at.key}';
    Widget text(String key, String label, {String? placeholder}) => ConversationInput(
          id: '$id:$key',
          value: '${branch.arguments[key] ?? ''}',
          label: label,
          code: true,
          placeholder: placeholder,
          profile: switch (kind) {
            ConditionKind.flag || ConditionKind.relationship => const FieldProfile(values: <ValueRef>[ValueRef.flags()]),
            ConditionKind.quest => const FieldProfile(values: <ValueRef>[ValueRef.quests()]),
            _ => FieldProfile.none,
          },
          onChanged: (String value) => _editBranch(scope, at, (ConversationBranch b) => b.arguments[key] = value, coalesce: 'if:${at.key}:$key'),
        );
    const List<PickOption> scopes = <PickOption>[PickOption('player', 'Player'), PickOption('npc', 'NPC'), PickOption('global', 'Everyone')];
    Widget scopePicker() => ConversationPicker(
          label: 'Scope',
          value: asString(branch.arguments['scope']) ?? 'player',
          options: scopes,
          onPick: (String value) => _editBranch(scope, at, (ConversationBranch b) => _putArgument(b.arguments, 'scope', value == 'player' ? null : value)),
        );
    final String compare = <String>['equals', 'atLeast', 'atMost'].firstWhere(branch.arguments.containsKey, orElse: () => 'atLeast');
    return dom.div(classes: 'animus-conv-fields', <Widget>[
      ConversationPicker(
        label: 'Condition',
        value: kind.name,
        options: <PickOption>[for (final ConditionKind option in ConditionKind.values) if (option != ConditionKind.other || kind == ConditionKind.other) PickOption(option.name, option.label)],
        onPick: (String value) => _editBranch(scope, at, (ConversationBranch b) => setConditionKind(b, ConditionKind.values.byName(value))),
      ),
      ...switch (kind) {
        ConditionKind.flag => <Widget>[text('name', 'Flag name', placeholder: 'flag name'), scopePicker()],
        ConditionKind.relationship => <Widget>[text('name', 'Relationship flag', placeholder: 'flag name')],
        ConditionKind.quest => <Widget>[
            text('name', 'Quest name', placeholder: 'quest name'),
            ConversationPicker(
              label: 'Stage test',
              value: compare,
              options: const <PickOption>[PickOption('equals', 'is'), PickOption('atLeast', 'at least'), PickOption('atMost', 'at most')],
              onPick: (String value) => _editBranch(scope, at, (ConversationBranch b) {
                final Object stage = b.arguments.remove(compare) ?? 1;
                b.arguments
                  ..remove('equals')
                  ..remove('atLeast')
                  ..remove('atMost')
                  ..[value] = stage;
              }),
            ),
            GuiNumberInput(
              value: branch.arguments[compare] is num ? branch.arguments[compare]! as num : null,
              label: 'Stage',
              onChanged: (num? value) => _editBranch(scope, at, (ConversationBranch b) => b.arguments[compare] = value ?? 0, coalesce: 'if:${at.key}:stage'),
            ),
            scopePicker(),
          ],
        ConditionKind.permission => <Widget>[text('permission', 'Permission', placeholder: 'town.vip')],
        ConditionKind.item => <Widget>[
            ConversationInput(
              id: '$id:item',
              value: heldItemOf(branch.expression ?? '') ?? '',
              label: 'Item held',
              code: true,
              profile: idProfile(const ValueRef.registry(RegistryKind.item)),
              placeholder: 'minecraft:emerald',
              onChanged: (String value) => _editBranch(scope, at, (ConversationBranch b) => b.expression = heldItemExpression(value.trim()), coalesce: 'if:${at.key}:item'),
            ),
          ],
        ConditionKind.expression => <Widget>[
            ConversationInput(
              id: '$id:expression',
              value: branch.expression ?? '',
              label: 'Expression',
              code: true,
              profile: FieldProfile.behaviorExpression,
              placeholder: "player.level >= 10",
              onChanged: (String value) => _editBranch(scope, at, (ConversationBranch b) => b.expression = value, coalesce: 'if:${at.key}:expression'),
            ),
          ],
        ConditionKind.other => <Widget>[
            dom.code(<Widget>[Component.text(branch.capability ?? '')]),
            KeptJsonEditor(
              value: branch.arguments,
              label: 'Arguments of ${branch.capability}',
              onChanged: (Object? value) => _editBranch(scope, at, (ConversationBranch b) => b.arguments = asMap(value)),
            ),
          ],
      },
    ]);
  }
}

/// Actions the picker lists first, in words.
const Map<String, String> actionNames = <String, String>{
  'animus:set-flag': 'Set flag',
  'animus:give-item': 'Give item',
  'animus:take-item': 'Take item',
  'animus:command': 'Run command',
  'animus:open-shop': 'Open shop',
  'animus:play-animation': 'Play animation',
  'animus:teleport': 'Teleport',
  'animus:set-quest-state': 'Set quest stage',
};

/// The picker value that starts a quest: stage 1.
const String startQuestChoice = 'start-quest';

class ActionPicker extends StatelessWidget {
  const ActionPicker({required this.scope, required this.action, required this.at, super.key});

  final ConversationScope scope;
  final ConversationAction action;
  final StepAt at;

  @override
  Widget build(BuildContext context) {
    final List<CapabilitySpec> others = <CapabilitySpec>[
      for (final CapabilitySpec spec in scope.catalog.capabilitiesFor('action'))
        if (!actionNames.containsKey(spec.id) && spec.id != sayCapability && spec.id != choicesCapability) spec,
    ];
    return ConversationPicker(
      label: 'Action',
      value: action.capability,
      placeholder: 'Pick an action',
      options: <PickOption>[
        const PickOption(startQuestChoice, 'Start quest'),
        for (final MapEntry<String, String> entry in actionNames.entries) PickOption(entry.key, entry.value),
        for (final CapabilitySpec spec in others) PickOption(spec.id, spec.id),
        if (action.capability.isNotEmpty && !actionNames.containsKey(action.capability) && !others.any((CapabilitySpec spec) => spec.id == action.capability))
          PickOption(action.capability, action.capability),
      ],
      onPick: (String value) => scope.edit((Conversation c) {
        final ConversationStep? step = stepAt(c, at);
        if (step is! ConversationAction) return;
        final String? name = asString(step.arguments['name']);
        if (value == startQuestChoice) {
          step
            ..capability = 'animus:set-quest-state'
            ..arguments = <String, Object?>{'name': name ?? '', 'value': 1};
        } else if (value != step.capability) {
          step
            ..capability = value
            ..arguments = <String, Object?>{if (name != null && (value == 'animus:set-flag' || value == 'animus:set-quest-state')) 'name': name};
        }
      }),
    );
  }
}

/// An action's arguments: required ones and ones already set, or every one
/// when the row is open.
class ActionArguments extends StatelessWidget {
  const ActionArguments({required this.scope, required this.action, required this.at, super.key});

  final ConversationScope scope;
  final ConversationAction action;
  final StepAt at;

  void _set(String key, Object? value, {String? coalesce}) => scope.edit((Conversation c) {
        final ConversationStep? step = stepAt(c, at);
        if (step is ConversationAction) _putArgument(step.arguments, key, value);
      }, coalesce: coalesce);

  @override
  Widget build(BuildContext context) {
    final CapabilitySpec? spec = scope.catalog.capability(action.capability);
    final String key = 'args:${at.key}';
    if (spec == null) {
      if (action.capability.isEmpty) return const dom.div(<Widget>[]);
      return dom.div(classes: 'animus-conv-args', <Widget>[
        KeptJsonEditor(
          value: action.arguments,
          label: 'Arguments of ${action.capability}',
          onChanged: (Object? value) => scope.edit((Conversation c) {
            final ConversationStep? step = stepAt(c, at);
            if (step is ConversationAction) step.arguments = asMap(value);
          }),
        ),
      ]);
    }
    final bool all = scope.expanded.contains(key);
    final List<FieldSpec> shown = <FieldSpec>[
      for (final FieldSpec field in spec.arguments)
        if (all || field.required || action.arguments.containsKey(field.key)) field,
    ];
    final bool more = shown.length < spec.arguments.length;
    return dom.div(classes: 'animus-conv-args', <Widget>[
      for (final FieldSpec field in shown) _row(field),
      if (more || all)
        Button.ghost(
          label: all ? 'Fewer fields' : 'All fields',
          size: ButtonSize.small,
          attributes: <String, String>{'aria-expanded': '$all'},
          onPressed: () => scope.toggle(key),
        ),
    ]);
  }

  Widget _row(FieldSpec field) {
    final String id = 'conv-arg:${at.key}:${field.key}';
    final Object? value = action.arguments[field.key];
    final String label = '${humanizeKey(field.key)}${field.required ? ' *' : ''}';
    return dom.div(classes: 'animus-conv-arg', <Widget>[
      dom.label(classes: 'animus-gui-label', attributes: <String, String>{'for': id}, <Widget>[withTip(Component.text(label), field.description)]),
      _control(field, id, value),
      if (field.boundsHint case final String hint) dom.span(classes: 'animus-gui-help', <Widget>[Component.text(hint)]),
    ]);
  }

  Widget _control(FieldSpec field, String id, Object? value) {
    switch (field.kind) {
      case FieldKind.choice when field.options.every((FieldOption option) => option.value is String):
        return ConversationPicker(
          id: id,
          label: humanizeKey(field.key),
          value: value is String ? value : null,
          placeholder: field.required ? 'Pick one' : 'Default',
          options: <PickOption>[
            if (!field.required) const PickOption('', 'Default'),
            for (final FieldOption option in field.options) PickOption(option.label, option.label),
          ],
          onPick: (String picked) => _set(field.key, picked.isEmpty ? null : picked),
        );
      case FieldKind.boolean when value is bool || value == null:
        return GuiSwitch(value: value == true, label: humanizeKey(field.key), onChanged: (bool on) => _set(field.key, on ? true : null));
      case FieldKind.integer || FieldKind.number when value is num || value == null:
        return GuiNumberInput(
          id: id,
          value: value as num?,
          integer: field.kind == FieldKind.integer,
          label: humanizeKey(field.key),
          digits: field.digits,
          onChanged: (num? next) => _set(field.key, next, coalesce: 'arg:${at.key}:${field.key}'),
        );
      case FieldKind.string when value is String || value == null:
        return ConversationInput(
          id: id,
          value: value as String? ?? '',
          label: humanizeKey(field.key),
          code: field.key != 'text' && field.key != 'command',
          placeholder: _placeholder(field.key),
          profile: argumentProfile(action.capability, field.key),
          onChanged: (String next) => _set(field.key, next, coalesce: 'arg:${at.key}:${field.key}'),
        );
      default:
        return KeptJsonEditor(value: value, label: humanizeKey(field.key), onChanged: (Object? next) => _set(field.key, next));
    }
  }

  String? _placeholder(String key) => switch (key) {
        'name' => 'name',
        'item' => 'minecraft:bread',
        'shop' => scope.suggestions.shops.firstOrNull ?? 'town:baker',
        'animation' => scope.suggestions.animations.firstOrNull ?? 'town:golem/wave',
        'command' => 'say hello {player_name}',
        _ => null,
      };
}

/// A small labelled field inside an opened row.
Widget _labelled(String id, String label, String? tip, Widget control) => dom.div(classes: 'animus-conv-arg', <Widget>[
      dom.label(classes: 'animus-gui-label', attributes: <String, String>{'for': id}, <Widget>[withTip(Component.text(label), tip, docs: conversationDocs)]),
      control,
    ]);

class LineExtras extends StatelessWidget {
  const LineExtras({required this.scope, required this.line, required this.at, super.key});

  final ConversationScope scope;
  final ConversationLine line;
  final StepAt at;

  void _edit(void Function(ConversationLine line) change, String field) => scope.edit((Conversation c) {
        final ConversationStep? step = stepAt(c, at);
        if (step is ConversationLine) change(step);
      }, coalesce: 'line:${at.key}:$field');

  @override
  Widget build(BuildContext context) {
    final String id = 'conv-extra:${at.key}';
    return dom.div(classes: 'animus-conv-args', <Widget>[
      _labelled(
        '$id:delay',
        'Delay',
        'Ticks to wait before the line. 20 ticks is about a second.',
        GuiNumberInput(
          id: '$id:delay',
          value: line.delayTicks,
          label: 'Delay in ticks',
          placeholder: '0',
          onChanged: (num? value) => _edit((ConversationLine l) => l.delayTicks = value == null || value <= 0 ? null : value.toInt().clamp(1, 72000), 'delay'),
        ),
      ),
      _labelled(
        '$id:sound',
        'Sound',
        'Played at the NPC for the player it talks to.',
        ConversationInput(
          id: '$id:sound',
          value: line.sound ?? '',
          label: 'Sound',
          code: true,
          profile: idProfile(const ValueRef.registry(RegistryKind.sound)),
          placeholder: 'minecraft:entity.villager.ambient',
          onChanged: (String value) => _edit((ConversationLine l) => l.sound = value.trim().isEmpty ? null : value.trim(), 'sound'),
        ),
      ),
      _labelled(
        '$id:animation',
        'Animation',
        'A clip of the NPC rig, played as the line is said.',
        ConversationInput(
          id: '$id:animation',
          value: line.animation ?? '',
          label: 'Animation',
          code: true,
          profile: idProfile(const ValueRef.document(DocumentKind.animation)),
          placeholder: scope.suggestions.animations.firstOrNull ?? 'town:golem/wave',
          onChanged: (String value) => _edit((ConversationLine l) => l.animation = value.trim().isEmpty ? null : value.trim(), 'animation'),
        ),
      ),
    ]);
  }
}

/// Answer colours the picker offers; any `#RRGGBB` typed in Code works too.
const List<PickOption> answerColours = <PickOption>[
  PickOption('', 'Default'),
  PickOption('green', 'Green'),
  PickOption('red', 'Red'),
  PickOption('gold', 'Gold'),
  PickOption('yellow', 'Yellow'),
  PickOption('aqua', 'Aqua'),
  PickOption('light_purple', 'Pink'),
  PickOption('white', 'White'),
  PickOption('gray', 'Grey'),
];

class OptionExtras extends StatelessWidget {
  const OptionExtras({required this.scope, required this.choices, required this.at, required this.index, super.key});

  final ConversationScope scope;
  final ConversationChoices choices;
  final StepAt at;
  final int index;

  void _edit(void Function(ConversationOption option) change, {String? coalesce}) => scope.edit((Conversation c) {
        final ConversationStep? step = stepAt(c, at);
        if (step is ConversationChoices && index < step.options.length) change(step.options[index]);
      }, coalesce: coalesce);

  @override
  Widget build(BuildContext context) {
    final ConversationOption option = choices.options[index];
    final String id = 'conv-answer:${at.key}:$index';
    final List<PickOption> colours = <PickOption>[
      ...answerColours,
      if (option.color != null && !answerColours.any((PickOption colour) => colour.value == option.color)) PickOption(option.color!, option.color!),
    ];
    return dom.div(classes: 'animus-conv-args', <Widget>[
      _labelled(
        '$id:color',
        'Colour',
        'Without one, yes-like answers are green and no-like ones red.',
        ConversationPicker(
          id: '$id:color',
          label: 'Colour',
          value: option.color ?? '',
          options: colours,
          onPick: (String value) => _edit((ConversationOption o) => o.color = value.isEmpty ? null : value),
        ),
      ),
      _labelled(
        '$id:hover',
        'Hover',
        'Shown when the player points at the answer.',
        ConversationInput(
          id: '$id:hover',
          value: option.hover ?? '',
          label: 'Hover',
          placeholder: 'Click to choose this answer',
          profile: FieldProfile.richText,
          onChanged: (String value) => _edit((ConversationOption o) => o.hover = value.isEmpty ? null : value, coalesce: 'hover:${at.key}:$index'),
        ),
      ),
      _labelled(
        '$id:style',
        'Style',
        null,
        ConversationPicker(
          id: '$id:style',
          label: 'Style',
          value: option.style ?? '',
          options: const <PickOption>[
            PickOption('', 'Default'),
            PickOption('bold', 'Bold'),
            PickOption('italic', 'Italic'),
            PickOption('underlined', 'Underlined'),
            PickOption('plain', 'Plain'),
          ],
          onPick: (String value) => _edit((ConversationOption o) => o.style = value.isEmpty ? null : value),
        ),
      ),
      _labelled(
        '$id:next',
        'Leads to',
        'The block this answer runs. Nothing ends the conversation.',
        ConversationPicker(
          id: '$id:next',
          label: 'Leads to',
          value: option.next ?? '',
          options: <PickOption>[
            const PickOption('', 'Nothing'),
            for (final String name in scope.conversation.blocks.keys) PickOption(name, name),
          ],
          onPick: (String value) => scope.edit((Conversation c) {
            final ConversationStep? step = stepAt(c, at);
            if (step is! ConversationChoices || index >= step.options.length) return;
            final ConversationOption chosen = step.options[index];
            chosen.next = value.isEmpty ? null : value;
            assignTriggerIds(c);
          }),
        ),
      ),
    ]);
  }
}
