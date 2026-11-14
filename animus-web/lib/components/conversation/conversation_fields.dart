/// The fields inside conversation step cards: what a condition checks, which
/// action runs and its arguments (from the capability catalog), a line's
/// delay, sound and animation, and an answer's colour, hover and target.
/// Each sits under its label, so a nested card never lays fields side by
/// side.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import '../../config/docs_links.dart';
import '../../logic/conversation/conversation_edit.dart';
import '../../logic/conversation/conversation_model.dart';
import '../../logic/conversation/conversation_words.dart';
import '../../logic/gui/gui_json.dart';
import '../../logic/inspect/completion_catalog.dart';
import '../../logic/inspect/behavior_summary.dart' show capabilityWords;
import '../../logic/inspect/schema_fields.dart';
import '../../logic/suggest/completion_sources.dart';
import '../../logic/suggest/field_profile.dart';
import '../../logic/suggest/field_profiles.dart';
import '../../logic/suggest/minimessage_tags.dart' show miniMessageColors;
import '../../model/document_kind.dart';
import '../../ui/field.dart';
import '../../ui/mode.dart';
import '../inspect/behavior_inputs.dart' show JsonValueEditor;
import '../inspect/inspect_fields.dart' show KeptJsonEditor;
import '../select/named_value_picker.dart';
import 'conversation_input.dart';
import 'conversation_picker.dart';
import 'conversation_tree.dart';
import 'sound_picker.dart';
import '../../ui/number_input.dart';

/// The wiki section on conversations.
const DocLink conversationDocs = DocLink('14-web-editor', 'conversations');

/// A new condition: a player flag.
ConversationBranch newBranch() => ConversationBranch(capability: 'animus:flag-state', arguments: <String, Object?>{'name': ''});

/// What a condition checks, as the picker names it.
enum ConditionKind {
  flag('Flag'),
  quest('Quest stage'),
  permission('Permission'),
  item('Item held'),
  relationship('Flag with this NPC'),
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

/// The condition kind picker, in the If card's heading row.
class ConditionPicker extends StatelessWidget {
  const ConditionPicker({required this.scope, required this.branch, required this.at, super.key});

  final ConversationScope scope;
  final ConversationBranch branch;
  final StepAt at;

  @override
  Widget build(BuildContext context) {
    final ConditionKind kind = conditionKind(branch);
    return ConversationPicker(
      label: 'Condition',
      value: kind.name,
      options: <PickOption>[
        for (final ConditionKind option in ConditionKind.values)
          if ((option != ConditionKind.other || kind == ConditionKind.other) && (scope.advanced || option != ConditionKind.expression || kind == option)) PickOption(option.name, option.label),
      ],
      onPick: (String value) => _editBranch(scope, at, (ConversationBranch b) => setConditionKind(b, ConditionKind.values.byName(value))),
    );
  }
}

/// What the picked condition needs: a flag name, a quest and stage, a
/// permission, an item or an expression.
class ConditionFields extends StatelessWidget {
  const ConditionFields({required this.scope, required this.branch, required this.at, super.key});

  final ConversationScope scope;
  final ConversationBranch branch;
  final StepAt at;

  @override
  Widget build(BuildContext context) {
    final ConditionKind kind = conditionKind(branch);
    final String id = 'conv-if:${at.key}';
    Widget text(String key, String label) => _labelled(
          '$id:$key',
          label,
          null,
          ConversationInput(
            id: '$id:$key',
            value: '${branch.arguments[key] ?? ''}',
            label: label,
            code: scope.advanced,
            profile: switch (kind) {
              ConditionKind.flag || ConditionKind.relationship => const FieldProfile(values: <ValueRef>[ValueRef.flags()]),
              ConditionKind.quest => const FieldProfile(values: <ValueRef>[ValueRef.quests()]),
              _ => FieldProfile.none,
            },
            onChanged: (String value) => _editBranch(scope, at, (ConversationBranch b) => b.arguments[key] = value, coalesce: 'if:${at.key}:$key'),
          ),
        );
    const List<PickOption> scopes = <PickOption>[PickOption('player', 'This player'), PickOption('npc', 'This NPC'), PickOption('global', 'Everyone')];
    Widget scopePicker() => _labelled(
          '$id:scope',
          'Kept for',
          'Whose flag it is: the player talking, the NPC, or one for the whole server.',
          ConversationPicker(
            id: '$id:scope',
            label: 'Kept for',
            value: asString(branch.arguments['scope']) ?? 'player',
            options: scopes,
            onPick: (String value) => _editBranch(scope, at, (ConversationBranch b) => _putArgument(b.arguments, 'scope', value == 'player' ? null : value)),
          ),
        );
    final String compare = <String>['equals', 'atLeast', 'atMost'].firstWhere(branch.arguments.containsKey, orElse: () => 'atLeast');
    final List<Widget> fields = switch (kind) {
      ConditionKind.flag => <Widget>[text('name', 'Flag name'), scopePicker()],
      ConditionKind.relationship => <Widget>[text('name', 'Flag name')],
      ConditionKind.quest => <Widget>[
          text('name', 'Quest'),
          _labelled(
            '$id:compare',
            'Stage is',
            null,
            ConversationPicker(
              id: '$id:compare',
              label: 'Stage is',
              value: compare,
              options: const <PickOption>[PickOption('equals', 'Exactly'), PickOption('atLeast', 'At least'), PickOption('atMost', 'At most')],
              onPick: (String value) => _editBranch(scope, at, (ConversationBranch b) {
                final Object stage = b.arguments.remove(compare) ?? 1;
                b.arguments
                  ..remove('equals')
                  ..remove('atLeast')
                  ..remove('atMost')
                  ..[value] = stage;
              }),
            ),
          ),
          _labelled(
            '$id:stage',
            'Stage',
            null,
            NumberInput(
              id: '$id:stage',
              value: branch.arguments[compare] is num ? branch.arguments[compare]! as num : null,
              label: 'Stage',
              onChanged: (num? value) => _editBranch(scope, at, (ConversationBranch b) => b.arguments[compare] = value ?? 0, coalesce: 'if:${at.key}:stage'),
            ),
          ),
          scopePicker(),
        ],
      ConditionKind.permission => <Widget>[text('permission', 'Permission')],
      ConditionKind.item => <Widget>[
          _labelled(
            '$id:item',
            'Item',
            null,
            ConversationInput(
              id: '$id:item',
              value: heldItemOf(branch.expression ?? '') ?? '',
              label: 'Item held',
              code: scope.advanced,
              profile: idProfile(const ValueRef.registry(RegistryKind.item)),
              onChanged: (String value) => _editBranch(scope, at, (ConversationBranch b) => b.expression = heldItemExpression(value.trim()), coalesce: 'if:${at.key}:item'),
            ),
          ),
        ],
      ConditionKind.expression => <Widget>[
          _labelled(
            '$id:expression',
            'Expression',
            'True or false, such as player.level >= 10.',
            ConversationInput(
              id: '$id:expression',
              value: branch.expression ?? '',
              label: 'Expression',
              code: true,
              profile: FieldProfile.behaviorExpression,
              placeholder: 'player.level >= 10',
              onChanged: (String value) => _editBranch(scope, at, (ConversationBranch b) => b.expression = value, coalesce: 'if:${at.key}:expression'),
            ),
          ),
        ],
      ConditionKind.other => <Widget>[
          _labelled(
            '$id:other',
            capabilityWords(branch.capability),
            'A check this editor has no form for (${branch.capability}). Its settings are kept as written.',
            KeptJsonEditor(
              value: branch.arguments,
              label: 'Settings of ${branch.capability}',
              onChanged: (Object? value) => _editBranch(scope, at, (ConversationBranch b) => b.arguments = asMap(value)),
            ),
          ),
        ],
    };
    return dom.div(fields);
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
        if (scope.advanced)
          for (final CapabilitySpec spec in others) PickOption(spec.id, capabilityWords(spec.id), hint: spec.id),
        if (action.capability.isNotEmpty && !actionNames.containsKey(action.capability) && (!scope.advanced || !others.any((CapabilitySpec spec) => spec.id == action.capability)))
          PickOption(action.capability, capabilityWords(action.capability), hint: scope.advanced ? action.capability : null),
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
    if (spec == null) {
      if (action.capability.isEmpty) return const dom.div(<Widget>[]);
      return dom.div(<Widget>[
        KeptJsonEditor(
          value: action.arguments,
          label: 'Settings of ${action.capability}',
          onChanged: (Object? value) => scope.edit((Conversation c) {
            final ConversationStep? step = stepAt(c, at);
            if (step is ConversationAction) step.arguments = asMap(value);
          }),
        ),
      ]);
    }
    final List<FieldSpec> shown = <FieldSpec>[
      for (final FieldSpec field in spec.arguments)
        if (field.required || action.arguments.containsKey(field.key)) field,
    ];
    final List<FieldSpec> rest = <FieldSpec>[
      for (final FieldSpec field in spec.arguments)
        if (!shown.contains(field)) field,
    ];
    return dom.div(<Widget>[
      for (final FieldSpec field in shown) _row(field),
      if (rest.isNotEmpty) MoreFields(label: 'More settings', children: <Widget>[for (final FieldSpec field in rest) _row(field)]),
    ]);
  }

  Widget _row(FieldSpec field) {
    final String id = 'conv-arg:${at.key}:${field.key}';
    final Widget control = _control(field, id, action.arguments[field.key]);
    // A JSON box or a value with its kind picker has no id to name.
    // Simple keeps the bounds in the tip and in the problem a value outside
    // them shows; Advanced also lists them under the field.
    return _labelled(control is KeptJsonEditor || control is JsonValueEditor ? null : id, humanizeKey(field.key), field.help, control,
        hint: scope.advanced ? field.boundsHint : null, required: field.required);
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
            for (final FieldOption option in field.options) PickOption(option.label, humanizeKey(option.label)),
          ],
          onPick: (String picked) => _set(field.key, picked.isEmpty ? null : picked),
        );
      case FieldKind.boolean when value is bool || value == null:
        return ArcaneToggleSwitch(value: value == true, label: humanizeKey(field.key), onChanged: (bool on) => _set(field.key, on ? true : null), size: ComponentSize.sm);
      case FieldKind.integer || FieldKind.number when value is num || value == null:
        return NumberInput(
          id: id,
          value: value as num?,
          integer: field.kind == FieldKind.integer,
          label: humanizeKey(field.key),
          digits: field.digits,
          onChanged: (num? next) => _set(field.key, next, coalesce: 'arg:${at.key}:${field.key}'),
        );
      case FieldKind.string when value is String || value == null:
        final FieldProfile profile = argumentProfile(action.capability, field.key);
        void write(String next) => _set(field.key, next, coalesce: 'arg:${at.key}:${field.key}');
        if (profile.miniMessage) return formattedTextInput(id: id, value: value as String? ?? '', label: humanizeKey(field.key), profile: profile, onChanged: write);
        // Simple picks ids and documents by name; Advanced types them.
        final List<ValueRef>? named = scope.advanced ? null : NamedValuePicker.refsOf(profile);
        if (named != null) return NamedValuePicker(id: id, refs: named, value: value as String?, label: humanizeKey(field.key), onChanged: (String? next) => _set(field.key, next));
        return ConversationInput(
          id: id,
          value: value as String? ?? '',
          label: humanizeKey(field.key),
          code: scope.advanced && field.key != 'command',
          profile: profile,
          onChanged: write,
        );
      // Text, a number or true or false is edited as that, never as JSON.
      case FieldKind.any when value == null || value is String || value is num || value is bool:
        return JsonValueEditor(
          value: value ?? '',
          label: humanizeKey(field.key),
          onChanged: (Object? next) => _set(field.key, next, coalesce: 'arg:${at.key}:${field.key}'),
        );
      default:
        return KeptJsonEditor(value: value, label: humanizeKey(field.key), onChanged: (Object? next) => _set(field.key, next));
    }
  }

}

/// A field inside a step card, as every form lays one out.
Widget _labelled(String? id, String label, String? tip, Widget control, {String? hint, bool required = false}) => Field(
      label: label,
      forId: id,
      tip: tip == null || tip.trim().isEmpty ? null : tip,
      docs: conversationDocs,
      hint: hint,
      required: required,
      child: control,
    );

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
    return dom.div(<Widget>[
      _labelled(
        '$id:delay',
        'Delay',
        'Ticks to wait before the line. 20 ticks is about a second.',
        NumberInput(
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
        scope.advanced
            ? ConversationInput(
                id: '$id:sound',
                value: line.sound ?? '',
                label: 'Sound',
                code: true,
                profile: idProfile(const ValueRef.registry(RegistryKind.sound)),
                onChanged: (String value) => _edit((ConversationLine l) => l.sound = value.trim().isEmpty ? null : value.trim(), 'sound'),
              )
            : SoundPicker(
                id: '$id:sound',
                value: line.sound,
                label: 'Sound',
                allowNone: true,
                onChanged: (String? value) => _edit((ConversationLine l) => l.sound = value, 'sound'),
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
          code: scope.advanced,
          profile: idProfile(const ValueRef.document(DocumentKind.animation)),
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
  PickOption('gray', 'Gray'),
];

/// The colour Minecraft draws an answer of [color] in (a name, an alias
/// such as grey, or #RRGGBB); transparent for anything else.
String answerSwatch(String color) {
  if (RegExp(r'^#[0-9a-fA-F]{6}$').hasMatch(color)) return color;
  return miniMessageColors[color.replaceAll('grey', 'gray')] ?? 'transparent';
}

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
    return dom.div(<Widget>[
      _labelled(
        '$id:color',
        'Color',
        'Without one, yes-like answers are green and no-like ones red.',
        ConversationPicker(
          id: '$id:color',
          label: 'Color',
          value: option.color ?? '',
          options: colours,
          onPick: (String value) => _edit((ConversationOption o) => o.color = value.isEmpty ? null : value),
        ),
      ),
      _labelled(
        '$id:hover',
        'Hover',
        'Shown when the player points at the answer.',
        formattedTextInput(
          id: '$id:hover',
          value: option.hover ?? '',
          label: 'Hover',
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
        'The part this answer runs.',
        ConversationPicker(
          id: '$id:next',
          label: 'Leads to',
          value: option.next ?? '',
          options: <PickOption>[
            const PickOption('', 'Nothing, it ends'),
            for (final String name in scope.conversation.blocks.keys) PickOption(name, blockTitle(scope.conversation, name), hint: scope.advanced ? name : null),
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
