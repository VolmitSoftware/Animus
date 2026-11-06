/// The one operand control of the behavior editor, used wherever the
/// language takes a literal or an operand (`set.value`, `repeat.count`,
/// `foreach.list`, `return.value`, `call.arguments`), and the argument field
/// of actions and conditions, which can fill a null placeholder from a
/// variable, an expression or a template.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/gui/gui_json.dart';
import '../../logic/inspect/behavior_language.dart';
import '../../logic/inspect/behavior_operands.dart';
import '../../logic/inspect/schema_fields.dart';
import '../../model/animus_document.dart';
import '../gui/gui_fields.dart';
import 'behavior_inputs.dart';
import 'inspect_fields.dart';

/// A value of target [type] (a variable type, or null for any JSON): a
/// literal typed by [type], a compatible declared variable, an expression
/// or a template.
class OperandControl extends StatelessWidget {
  const OperandControl({
    required this.value,
    required this.onChanged,
    required this.variables,
    required this.scope,
    required this.label,
    this.type,
    this.literal,
    super.key,
  });

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
    return dom.div(classes: 'animus-operand', attributes: <String, String>{'data-operand': mode.name}, <Widget>[
      GuiSelect(
        value: mode.name,
        options: <String>[for (final OperandMode option in modes) option.name],
        labels: <String, String>{for (final OperandMode option in modes) option.name: option.label},
        label: '$label source',
        onChanged: (String? next) {
          final OperandMode? picked = OperandMode.values.where((OperandMode option) => option.name == next).firstOrNull;
          if (picked != null) onChanged(switchOperand(value, picked, type: type, variables: compatible));
        },
      ),
      switch (mode) {
        OperandMode.literal => literal ?? TypedValueEditor(type: type ?? 'any', value: value, label: label, onChanged: onChanged),
        OperandMode.variable => compatible.isEmpty
            ? dom.p(classes: 'animus-gui-note', <Widget>[
                Component.text(type == null || type == 'any'
                    ? 'Declare a variable first.'
                    : 'No $type variable. Declare one or use an expression.'),
              ])
            : GuiSelect(
                value: operandText(value).isEmpty ? null : operandText(value),
                options: compatible,
                labels: <String, String>{for (final String name in compatible) name: '$name (${asString(asMap(variables[name])['type']) ?? '?'})'},
                noneLabel: 'Pick a variable',
                label: '$label variable',
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
    ]);
  }
}

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
    return GuiRow(
      label: fieldLabel(argument),
      help: argument.help,
      problems: problems,
      child: dom.div(classes: 'animus-operand', attributes: <String, String>{'data-argument': key, 'data-source': source.name}, <Widget>[
        GuiSelect(
          value: source.name,
          options: <String>[for (final ArgumentSource option in ArgumentSource.values) option.name],
          labels: <String, String>{for (final ArgumentSource option in ArgumentSource.values) option.name: option.label},
          label: '${humanizeKey(key)} source',
          onChanged: (String? next) {
            final ArgumentSource? picked = ArgumentSource.values.where((ArgumentSource option) => option.name == next).firstOrNull;
            if (picked == null || picked == source) return;
            final String start = switch (picked) {
              ArgumentSource.binding => variables.keys.firstOrNull ?? '',
              ArgumentSource.literal => '',
              _ => source == ArgumentSource.literal ? _literalText(asMap(node['arguments'])[key]) : text,
            };
            onNode(setArgumentSource(node, key, picked, text: start, literal: _literalFor(argument)));
          },
        ),
        switch (source) {
          ArgumentSource.literal => literalNull
              ? const dom.p(classes: 'animus-gui-note', <Widget>[
                  Component.text('Empty placeholder. Pick a source.'),
                ])
              : literal,
          ArgumentSource.binding => variables.isEmpty
              ? const dom.p(classes: 'animus-gui-note', <Widget>[Component.text('Declare a variable first.')])
              : GuiSelect(
                  value: text.isEmpty ? null : text,
                  options: variables.keys.toList(),
                  noneLabel: 'Pick a variable',
                  label: '${humanizeKey(key)} variable',
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
      ]),
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
