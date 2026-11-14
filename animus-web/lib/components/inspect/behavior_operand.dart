/// The one operand control of the behavior editor, used wherever the
/// language takes a literal or an operand (`set.value`, `repeat.count`,
/// `foreach.list`, `return.value`, `call.arguments`), and the argument field
/// of actions and conditions, which can fill a null placeholder from a
/// variable, an expression or a template. A value written as is shows its
/// control alone with a "Fill from" menu; the source picker appears only
/// once another source is in use.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import '../../logic/gui/gui_json.dart';
import '../../logic/inspect/behavior_language.dart';
import '../../logic/inspect/behavior_operands.dart';
import '../../logic/inspect/behavior_words.dart';
import '../../logic/inspect/schema_fields.dart';
import '../../model/animus_document.dart';
import '../../ui/menu.dart';
import 'behavior_inputs.dart';
import 'inspect_fields.dart';
import '../select/animus_select.dart';
import '../../ui/field.dart';
import '../../ui/problem_text.dart';

/// A value of target [type] (a variable type, or null for any JSON): a
/// literal typed by [type], a compatible declared variable, an expression
/// or a template.
class OperandControl extends StatelessWidget {
  const OperandControl({required this.value, required this.onChanged, required this.variables, required this.scope, required this.label, this.type, this.literal, super.key});

  final Object? value;
  final void Function(Object? value) onChanged;

  /// The document's declared variables, name to declaration.
  final JsonMap variables;
  final ExpressionScope scope;
  final String label;
  final String? type;

  /// A control for the literal, when the field has its own (a bounded
  /// number); otherwise the literal is edited by [type].
  final Widget? literal;

  @override
  Widget build(BuildContext context) {
    final OperandMode mode = operandMode(value, type: type);
    final List<String> compatible = compatibleVariables(variables, type);
    final List<OperandMode> modes = operandModes(type);
    void pick(OperandMode picked) => onChanged(switchOperand(value, picked, type: type, variables: compatible));
    final bool plain = mode == OperandMode.literal;
    return dom.div(
      classes: 'animus-operand',
      attributes: <String, String>{'data-operand': mode.name},
      <Widget>[
        operandRow(
          flag: plain && (type == 'boolean' || value is bool),
          source: plain
              ? null
              : AnimusSelect.values(
                  value: mode.name,
                  values: <String>[for (final OperandMode option in modes) option.name],
                  labels: <String, String>{for (final OperandMode option in modes) option.name: option.label},
                  hiddenLabel: '$label source',
                  onChanged: (String? next) {
                    final OperandMode? picked = OperandMode.values.where((OperandMode option) => option.name == next).firstOrNull;
                    if (picked != null) pick(picked);
                  },
                ),
          value: switch (mode) {
            OperandMode.literal => literal ?? TypedValueEditor(type: type ?? 'any', value: value, label: label, onChanged: onChanged),
            OperandMode.variable =>
              compatible.isEmpty
                  ? noteLine(type == null || type == 'any' ? 'Add a variable first.' : 'No ${(variableTypeWords[type] ?? type ?? 'matching').toLowerCase()} variable. Add one or use an expression.')
                  : AnimusSelect.values(
                      value: operandText(value).isEmpty ? null : operandText(value),
                      values: compatible,
                      details: <String, String>{for (final String name in compatible) name: variableTypeWords[asString(asMap(variables[name])['type'])] ?? 'Anything'},
                      noneLabel: 'Pick a variable',
                      hiddenLabel: '$label variable',
                      onChanged: (String? next) => onChanged(<String, Object?>{'variable': next ?? ''}),
                    ),
            OperandMode.expression => BehaviorExpressionInput(
              value: operandText(value),
              scope: scope,
              label: '$label expression',
              placeholder: type == 'boolean' ? 'count < 3' : 'count + 1',
              onChanged: (String next) => onChanged(<String, Object?>{'expression': next}),
            ),
            OperandMode.template => BehaviorExpressionInput(
              value: operandText(value),
              scope: scope,
              template: true,
              label: '$label template',
              placeholder: 'Hello {{ player.name }}',
              onChanged: (String next) => onChanged(<String, Object?>{'template': next}),
            ),
          },
          fill: plain
              ? FillFromMenu(
                  label: label,
                  entries: <AppMenuEntry>[
                    for (final OperandMode option in modes)
                      if (option != OperandMode.literal) AppMenuEntry(option.label, () => pick(option)),
                  ],
                )
              : null,
        ),
      ],
    );
  }
}

/// The menu beside a value written as is: fill it from a variable, an
/// expression or a template instead.
class FillFromMenu extends StatelessWidget {
  const FillFromMenu({required this.label, required this.entries, super.key});

  /// What the value is, for the button's attributes.
  final String label;
  final List<AppMenuEntry> entries;

  @override
  Widget build(BuildContext context) => AppMenu(label: 'Fill from', alignEnd: true, attributes: <String, String>{'data-fill-from': label}, entries: entries);
}

/// A value beside its source picker or its "Fill from" menu. The value
/// takes the room left; a switch, shorter than the menu, has it underneath.
Widget operandRow({required Widget value, Widget? source, Widget? fill, bool flag = false}) => flag
    ? Column(crossAxisAlignment: CrossAxisAlignment.start, mainAxisSize: MainAxisSize.min, gapSize: Gap.sm, children: <Widget>[value, ?fill])
    : Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        gapSize: Gap.sm,
        children: <Widget>[
          ?source,
          Expanded(child: value),
          ?fill,
        ],
      );

/// The element id of the argument at diagnostic [path].
String argumentElementId(String path) => 'animus-argument:$path';

/// One argument of an action or condition: the typed field as written, or
/// a null placeholder filled at run time, with the filling shown inline.
class ArgumentField extends StatelessWidget {
  const ArgumentField({
    required this.node,
    required this.argument,
    required this.path,
    required this.onNode,
    required this.literal,
    required this.variables,
    required this.scope,
    this.problems = const <Diagnostic>[],
    super.key,
  });

  final JsonMap node;
  final FieldSpec argument;

  /// The argument's diagnostic path.
  final String path;

  /// Writes the whole node: placeholders touch `arguments` and a slot map.
  final void Function(JsonMap node) onNode;

  /// The typed control for the literal.
  final Widget literal;
  final JsonMap variables;
  final ExpressionScope scope;
  final List<Diagnostic> problems;

  @override
  Widget build(BuildContext context) {
    final String key = argument.key;
    final ArgumentSource source = argumentSource(node, key);
    final String text = argumentSlotText(node, key);
    final bool literalNull = source == ArgumentSource.literal && asMap(node['arguments']).containsKey(key) && asMap(node['arguments'])[key] == null;
    void pick(ArgumentSource picked) {
      if (picked == source) return;
      final String start = switch (picked) {
        ArgumentSource.binding => variables.keys.firstOrNull ?? '',
        ArgumentSource.literal => '',
        _ => source == ArgumentSource.literal ? _literalText(asMap(node['arguments'])[key]) : text,
      };
      onNode(setArgumentSource(node, key, picked, text: start, literal: _literalFor(argument)));
    }

    return Field(
      label: fieldLabel(argument, label: argumentLabel(argument.key)),
      help: argument.boundsHint,
      tip: argument.description,
      problems: problems,
      child: dom.div(
        id: argumentElementId(path),
        classes: 'animus-operand',
        attributes: <String, String>{'data-argument': key, 'data-source': source.name},
        <Widget>[
          operandRow(
            flag: source == ArgumentSource.literal && argument.kind == FieldKind.boolean,
            source: source == ArgumentSource.literal
                ? null
                : AnimusSelect.values(
                    value: source.name,
                    values: <String>[for (final ArgumentSource option in ArgumentSource.values) option.name],
                    labels: <String, String>{for (final ArgumentSource option in ArgumentSource.values) option.name: option.label},
                    hiddenLabel: '${humanizeKey(key)} source',
                    onChanged: (String? next) {
                      final ArgumentSource? picked = ArgumentSource.values.where((ArgumentSource option) => option.name == next).firstOrNull;
                      if (picked != null) pick(picked);
                    },
                  ),
            value: switch (source) {
              ArgumentSource.literal => literalNull ? noteLine('Empty. Pick where it comes from.') : literal,
              ArgumentSource.binding =>
                variables.isEmpty
                    ? noteLine('Add a variable first.')
                    : AnimusSelect.values(
                        value: text.isEmpty ? null : text,
                        values: variables.keys.toList(),
                        noneLabel: 'Pick a variable',
                        hiddenLabel: '${humanizeKey(key)} variable',
                        onChanged: (String? next) => onNode(setArgumentSlotText(node, key, next ?? '')),
                      ),
              ArgumentSource.expression => BehaviorExpressionInput(
                value: text,
                scope: scope,
                label: '${humanizeKey(key)} expression',
                onChanged: (String next) => onNode(setArgumentSlotText(node, key, next)),
              ),
              ArgumentSource.template => BehaviorExpressionInput(
                value: text,
                scope: scope,
                template: true,
                label: '${humanizeKey(key)} template',
                placeholder: 'Hello {{ player.name }}',
                onChanged: (String next) => onNode(setArgumentSlotText(node, key, next)),
              ),
            },
            fill: source == ArgumentSource.literal
                ? FillFromMenu(
                    label: humanizeKey(key),
                    entries: <AppMenuEntry>[
                      for (final ArgumentSource option in ArgumentSource.values)
                        if (option != ArgumentSource.literal) AppMenuEntry(option.label, () => pick(option)),
                    ],
                  )
                : null,
          ),
        ],
      ),
    );
  }

  static String _literalText(Object? value) => value is String ? value : (value == null ? '' : '$value');

  static Object _literalFor(FieldSpec field) => switch (field.kind) {
    FieldKind.boolean => false,
    FieldKind.integer || FieldKind.number => field.minimum ?? 0,
    FieldKind.choice => field.options.firstOrNull?.value ?? '',
    FieldKind.array => <Object?>[],
    FieldKind.object => <String, Object?>{},
    _ => '',
  };
}
