/// Inputs of the behavior editor: expression and template text with the
/// behavior language's completion and syntax check, and typed literal
/// editors for variable values (text, numbers, true or false, lists and
/// maps of those, or any JSON).
library;

import 'dart:convert';
import 'dart:js_interop';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;
import 'package:web/web.dart' as web;

import '../../logic/gui/gui_expression.dart' show ExpressionCompletion;
import '../../logic/gui/gui_json.dart';
import '../../logic/inspect/behavior_language.dart';
import '../gui/gui_fields.dart';
import 'inspect_fields.dart';

int _nextId = 0;

/// A monospace expression or template field. Typing offers variables,
/// roots, facts and functions below it (arrow keys, Enter or Tab to pick,
/// Escape to close); a problem shows under the field as you type.
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
  List<ExpressionCompletion> _completions = const <ExpressionCompletion>[];
  int _active = 0;
  bool _focused = false;
  String? _typed;

  String get _text => _typed ?? component.value;

  web.HTMLTextAreaElement? get _area {
    final web.Element? element = web.document.getElementById(_id);
    return element != null && element.isA<web.HTMLTextAreaElement>() ? element as web.HTMLTextAreaElement : null;
  }

  void _suggest(String text, int caret) {
    final List<ExpressionCompletion> next = completeBehaviorExpression(text, caret, component.scope, template: component.template);
    setState(() {
      _completions = next;
      _active = 0;
    });
  }

  void _apply(ExpressionCompletion completion) {
    final web.HTMLTextAreaElement? area = _area;
    final (String text, int caret) = completion.applyTo(area?.value ?? _text);
    if (area != null) {
      area.value = text;
      area.setSelectionRange(caret, caret);
      area.focus();
    }
    _typed = text;
    component.onChanged(text);
    _suggest(text, caret);
  }

  void _onKey(web.Event event) {
    if (!event.isA<web.KeyboardEvent>()) return;
    final web.KeyboardEvent key = event as web.KeyboardEvent;
    if (key.key == 'Enter' && _completions.isEmpty) {
      // One line: Enter never inserts a line break.
      key.preventDefault();
      return;
    }
    if (_completions.isEmpty) return;
    switch (key.key) {
      case 'ArrowDown':
        key.preventDefault();
        setState(() => _active = (_active + 1) % _completions.length);
      case 'ArrowUp':
        key.preventDefault();
        setState(() => _active = (_active - 1 + _completions.length) % _completions.length);
      case 'Enter' || 'Tab':
        key.preventDefault();
        _apply(_completions[_active]);
      case 'Escape':
        key.preventDefault();
        key.stopPropagation();
        setState(() => _completions = const <ExpressionCompletion>[]);
    }
  }

  @override
  Widget build(BuildContext context) {
    final String text = _text;
    final String? problem = text.isEmpty && component.template ? null : (component.template ? checkBehaviorTemplate(text, component.scope) : checkBehaviorExpression(text, component.scope));
    final bool open = _focused && _completions.isNotEmpty;
    return dom.div(classes: 'animus-gui-text animus-expr', <Widget>[
      dom.textarea(
        id: _id,
        classes: 'animus-gui-input is-code animus-expr-input${problem != null ? ' is-invalid' : ''}',
        rows: 1,
        attributes: <String, String>{
          'spellcheck': 'false',
          'autocomplete': 'off',
          'aria-label': component.label,
          'aria-autocomplete': 'list',
          'placeholder': ?component.placeholder,
          if (open) 'aria-expanded': 'true',
          if (open) 'aria-controls': '$_id-list',
          if (problem != null) 'aria-invalid': 'true',
          if (problem != null) 'aria-describedby': '$_id-problem',
        },
        events: <String, void Function(web.Event)>{
          'input': (web.Event event) {
            final web.HTMLTextAreaElement area = event.target! as web.HTMLTextAreaElement;
            final String value = area.value.replaceAll('\n', ' ');
            _typed = value;
            component.onChanged(value);
            _suggest(value, area.selectionStart);
          },
          'keydown': _onKey,
          'focus': (web.Event event) {
            _focused = true;
            final web.HTMLTextAreaElement area = event.target! as web.HTMLTextAreaElement;
            _suggest(area.value, area.selectionStart);
          },
          'blur': (web.Event _) {
            // A completion click lands before blur hides the list.
            Future<void>.delayed(const Duration(milliseconds: 150), () {
              if (mounted) {
                setState(() {
                  _focused = false;
                  _typed = null;
                });
              }
            });
          },
        },
        <Widget>[Component.text(text)],
      ),
      if (problem != null) dom.span(id: '$_id-problem', classes: 'animus-gui-problem is-error', <Widget>[Component.text(problem)]),
      if (open)
        dom.ul(id: '$_id-list', classes: 'animus-gui-completions animus-expr-completions', attributes: const <String, String>{'role': 'listbox'}, <Widget>[
          for (int index = 0; index < _completions.length; index++)
            dom.li(
              classes: index == _active ? 'is-active' : null,
              attributes: <String, String>{'role': 'option', 'aria-selected': '${index == _active}'},
              events: <String, void Function(web.Event)>{
                'mousedown': (web.Event event) {
                  event.preventDefault();
                  _apply(_completions[index]);
                },
              },
              <Widget>[
                dom.code(<Widget>[Component.text(_completions[index].label)]),
                dom.span(<Widget>[Component.text(_completions[index].detail)]),
              ],
            ),
        ]),
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
          if (items.isEmpty) const dom.p(classes: 'animus-muted', <Widget>[Component.text('Empty list')]),
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
          if (entries.isEmpty) const dom.p(classes: 'animus-muted', <Widget>[Component.text('Empty map')]),
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
