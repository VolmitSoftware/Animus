/// Inputs of the behavior editor: expression and template text with the
/// behavior language's completion and syntax check, and typed literal
/// editors for variable values (text, numbers, true or false, lists and
/// maps of those, or any JSON).
library;

import 'dart:convert';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/gui/gui_json.dart';
import '../../logic/inspect/behavior_language.dart';
import '../../logic/suggest/field_profile.dart';
import '../gui/gui_fields.dart';
import '../suggest/autocomplete_input.dart';
import '../suggest/suggest_scope.dart';
import 'inspect_fields.dart';

int _nextId = 0;

/// A monospace expression or template field that completes variables,
/// facts, context keys and functions; a problem shows under the field as
/// you type.
class BehaviorExpressionInput extends StatefulWidget {
  const BehaviorExpressionInput({
    required this.value,
    required this.onChanged,
    required this.scope,
    required this.label,
    this.template = false,
    this.placeholder,
    super.key,
  });

  final String value;
  final void Function(String value) onChanged;
  final ExpressionScope scope;

  /// The accessible name.
  final String label;

  /// Text with `{{ }}` holes instead of one expression.
  final bool template;
  final String? placeholder;

  @override
  State<BehaviorExpressionInput> createState() => _BehaviorExpressionInputState();
}

class _BehaviorExpressionInputState extends State<BehaviorExpressionInput> {
  final String _id = 'animus-expr-${_nextId++}';

  /// The latest typed text, ahead of the saved value.
  String? _typed;

  @override
  void didUpdateWidget(BehaviorExpressionInput oldComponent) {
    super.didUpdateWidget(oldComponent);
    if (component.value != oldComponent.value && component.value != _typed) _typed = null;
  }

  @override
  Widget build(BuildContext context) {
    final String text = _typed ?? component.value;
    final String? problem = text.isEmpty && component.template ? null : (component.template ? checkBehaviorTemplate(text, component.scope) : checkBehaviorExpression(text, component.scope));
    final BehaviorNames names = BehaviorNames(scope: component.scope, variableScopes: SuggestScope.of(context).behavior?.variableScopes ?? const <String, String>{});
    return dom.div(classes: 'animus-gui-text animus-expr', <Widget>[
      AutocompleteInput(
        id: _id,
        value: component.value,
        profile: component.template ? FieldProfile.behaviorTemplate : FieldProfile.behaviorExpression,
        behavior: names,
        label: component.label,
        placeholder: component.placeholder,
        oneLine: true,
        classes: 'animus-gui-input is-code animus-expr-input',
        invalid: problem != null,
        describedBy: problem != null ? '$_id-problem' : null,
        onChanged: (String next) {
          setState(() => _typed = next);
          component.onChanged(next);
        },
      ),
      if (problem != null) dom.span(id: '$_id-problem', classes: 'animus-gui-problem is-error', <Widget>[Component.text(problem)]),
    ]);
  }
}

/// The kinds a free JSON value can take in [JsonValueEditor].
enum _ValueKind {
  text('Text'),
  number('Number'),
  flag('True or false'),
  json('JSON');

  const _ValueKind(this.label);

  final String label;
}

_ValueKind _kindOf(Object? value) => switch (value) {
      String() => _ValueKind.text,
      num() => _ValueKind.number,
      bool() => _ValueKind.flag,
      _ => _ValueKind.json,
    };

/// A value of type `any`: text, a number, true or false, or JSON for
/// lists and maps. The kind picker converts the value.
class JsonValueEditor extends StatelessWidget {
  const JsonValueEditor({required this.value, required this.onChanged, required this.label, super.key});

  final Object? value;
  final void Function(Object? value) onChanged;
  final String label;

  @override
  Widget build(BuildContext context) {
    final _ValueKind kind = _kindOf(value);
    return dom.div(classes: 'animus-value-editor', <Widget>[
      GuiSelect(
        value: kind.name,
        options: <String>[for (final _ValueKind option in _ValueKind.values) option.name],
        labels: <String, String>{for (final _ValueKind option in _ValueKind.values) option.name: option.label},
        label: '$label kind',
        onChanged: (String? next) {
          final _ValueKind? picked = _ValueKind.values.where((_ValueKind option) => option.name == next).firstOrNull;
          if (picked == null || picked == kind) return;
          onChanged(switch (picked) {
            _ValueKind.text => value == null ? '' : (value is String ? value : jsonEncode(value)),
            _ValueKind.number => value is String ? (num.tryParse(value! as String) ?? 0) : 0,
            _ValueKind.flag => value == true || value == 'true',
            _ValueKind.json => value is List<Object?> || value is Map<String, Object?> ? value : <Object?>[],
          });
        },
      ),
      switch (kind) {
        _ValueKind.text => GuiTextInput(value: value is String ? value! as String : '', label: label, onChanged: (String next) => onChanged(next)),
        _ValueKind.number => GuiNumberInput(value: value as num?, integer: false, label: label, onChanged: (num? next) => onChanged(next ?? 0)),
        _ValueKind.flag => GuiSwitch(value: value == true, label: label, onChanged: (bool next) => onChanged(next)),
        _ValueKind.json => KeptJsonEditor(value: value, label: label, onChanged: (Object? next) => onChanged(next ?? <Object?>[])),
      },
    ]);
  }
}

/// The `initial` of a variable of [type]: typed controls for scalars, item
/// rows for a list, key and value rows for a map, JSON for anything else.
class TypedValueEditor extends StatelessWidget {
  const TypedValueEditor({required this.type, required this.value, required this.onChanged, required this.label, super.key});

  final String type;
  final Object? value;
  final void Function(Object? value) onChanged;
  final String label;

  @override
  Widget build(BuildContext context) {
    switch (type) {
      case 'boolean':
        return GuiSwitch(value: value == true, label: label, onChanged: (bool next) => onChanged(next));
      case 'integer' || 'decimal':
        return GuiNumberInput(
          value: value is num ? value! as num : null,
          integer: type == 'integer',
          label: label,
          onChanged: (num? next) => onChanged(type == 'integer' ? (next ?? 0).round() : (next ?? 0).toDouble()),
        );
      case 'string':
        return GuiTextInput(value: value is String ? value! as String : '', label: label, onChanged: (String next) => onChanged(next));
      case 'list':
        final JsonList items = asList(value);
        return dom.div(classes: 'animus-value-list', attributes: <String, String>{'role': 'group', 'aria-label': label}, <Widget>[
          if (items.isEmpty) const dom.p(classes: 'animus-empty-line', <Widget>[Component.text('Empty list')]),
          for (int index = 0; index < items.length; index++)
            dom.div(key: ValueKey<String>('item-$index'), classes: 'animus-value-row', <Widget>[
              dom.span(classes: 'animus-value-key', <Widget>[Component.text('${index + 1}')]),
              JsonValueEditor(value: items[index], label: '$label item ${index + 1}', onChanged: (Object? next) => onChanged(<Object?>[...items]..[index] = next)),
              GuiIconButton(icon: ArcaneIcon.x(size: IconSize.sm), label: 'Remove $label item ${index + 1}', onPressed: () => onChanged(<Object?>[...items]..removeAt(index))),
            ]),
          Button.ghost(label: 'Add item', size: ButtonSize.small, onPressed: () => onChanged(<Object?>[...items, ''])),
        ]);
      case 'map':
        final JsonMap entries = asMap(value);
        final List<String> keys = entries.keys.toList();
        return dom.div(classes: 'animus-value-list', attributes: <String, String>{'role': 'group', 'aria-label': label}, <Widget>[
          if (entries.isEmpty) const dom.p(classes: 'animus-empty-line', <Widget>[Component.text('Empty map')]),
          for (int index = 0; index < keys.length; index++)
            dom.div(key: ValueKey<String>('entry-$index'), classes: 'animus-value-row', <Widget>[
              GuiTextInput(
                value: keys[index],
                label: '$label key ${index + 1}',
                onChanged: (String next) {
                  if (next.isNotEmpty && !entries.containsKey(next)) onChanged(renameKey(entries, keys[index], next));
                },
              ),
              JsonValueEditor(
                value: entries[keys[index]],
                label: '$label value of ${keys[index]}',
                onChanged: (Object? next) => onChanged(<String, Object?>{...entries, keys[index]: next}),
              ),
              GuiIconButton(icon: ArcaneIcon.x(size: IconSize.sm), label: 'Remove key ${keys[index]}', onPressed: () => onChanged(<String, Object?>{...entries}..remove(keys[index]))),
            ]),
          Button.ghost(
            label: 'Add key',
            size: ButtonSize.small,
            onPressed: () {
              String key = 'key';
              for (int number = 2; entries.containsKey(key); number++) {
                key = 'key_$number';
              }
              onChanged(<String, Object?>{...entries, key: ''});
            },
          ),
        ]);
      default:
        return JsonValueEditor(value: value, label: label, onChanged: onChanged);
    }
  }
}
