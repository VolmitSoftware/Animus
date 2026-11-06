/// Editors for values no schema describes: a value with a kind picker (text,
/// number, true/false, list, object, empty) and a key/value editor whose
/// rows each hold one. Raw JSON stays behind an "Advanced" toggle.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;
import 'package:web/web.dart' as web;

import '../../logic/gui/gui_json.dart';
import '../../logic/inspect/schema_fields.dart';
import '../../logic/inspect/typed_values.dart';
import '../../logic/select/select_model.dart';
import '../gui/gui_fields.dart';
import '../select/animus_select.dart';
import '../select/suggest_input.dart';
import 'inspect_fields.dart' show KeptJsonEditor;

/// Object and list nesting the typed editors draw before showing JSON.
const int typedEditorDepth = 4;

final List<SelectOption> _kindOptions = <SelectOption>[
  for (final TypedKind kind in TypedKind.values) SelectOption(label: kind.label, value: kind.name),
];

/// One value of any kind: the kind picker, then the control for that kind.
class TypedValueEditor extends StatelessWidget {
  const TypedValueEditor({required this.value, required this.onChanged, required this.label, this.depth = 0, this.suggestions = const <FieldOption>[], super.key});

  final Object? value;
  final void Function(Object? value) onChanged;

  /// Names the controls for assistive technology ("Value of speed").
  final String label;
  final int depth;

  /// Values offered while typing text.
  final List<FieldOption> suggestions;

  @override
  Widget build(BuildContext context) {
    final TypedKind kind = typedKindOf(value);
    final bool nests = kind == TypedKind.list || kind == TypedKind.object;
    return dom.div(classes: 'animus-typed-value${nests ? ' is-nested' : ''}', <Widget>[
      dom.div(classes: 'animus-typed-kind', <Widget>[
        AnimusSelect(
          value: kind.name,
          hiddenLabel: 'Kind of $label',
          options: _kindOptions,
          onChanged: (String next) => onChanged(convertTyped(value, TypedKind.parse(next) ?? kind)),
        ),
      ]),
      _control(kind),
    ]);
  }

  Widget _control(TypedKind kind) {
    final Object? current = value;
    if (depth >= typedEditorDepth && (kind == TypedKind.list || kind == TypedKind.object)) {
      return KeptJsonEditor(value: current, label: label, onChanged: onChanged);
    }
    return switch (kind) {
      TypedKind.text => SuggestInput(value: current! as String, label: label, suggestions: suggestions, onChanged: onChanged),
      TypedKind.number => GuiNumberInput(value: current! as num, integer: false, label: label, onChanged: (num? next) => onChanged(next ?? 0)),
      TypedKind.boolean => GuiSwitch(value: current! as bool, label: label, onChanged: onChanged),
      TypedKind.list => TypedListEditor(items: current! as List<Object?>, label: label, depth: depth + 1, onChanged: onChanged),
      TypedKind.object => KeyValueEditor(value: current! as JsonMap, label: label, depth: depth + 1, onChanged: onChanged),
      TypedKind.empty => const dom.span(classes: 'animus-muted animus-typed-empty', <Widget>[Component.text('null')]),
    };
  }
}

/// A list whose items each have their own kind.
class TypedListEditor extends StatelessWidget {
  const TypedListEditor({required this.items, required this.onChanged, required this.label, this.depth = 0, super.key});

  final List<Object?> items;
  final void Function(List<Object?> items) onChanged;
  final String label;
  final int depth;

  @override
  Widget build(BuildContext context) {
    final TypedKind last = items.isEmpty ? TypedKind.text : typedKindOf(items.last);
    return dom.div(classes: 'animus-typed-list', attributes: <String, String>{'role': 'group', 'aria-label': label}, <Widget>[
      for (int index = 0; index < items.length; index++)
        dom.div(key: ValueKey<String>('item:$index'), classes: 'animus-typed-item', <Widget>[
          TypedValueEditor(
            value: items[index],
            label: '$label item ${index + 1}',
            depth: depth,
            onChanged: (Object? next) => onChanged(<Object?>[...items]..[index] = next),
          ),
          dom.div(classes: 'animus-typed-actions', <Widget>[
            GuiIconButton(
              icon: ArcaneIcon.arrowUp(size: IconSize.sm),
              label: 'Move $label item ${index + 1} up',
              disabled: index == 0,
              onPressed: () => onChanged(moveItem(items, index, index - 1)),
            ),
            GuiIconButton(
              icon: ArcaneIcon.arrowDown(size: IconSize.sm),
              label: 'Move $label item ${index + 1} down',
              disabled: index == items.length - 1,
              onPressed: () => onChanged(moveItem(items, index, index + 1)),
            ),
            GuiIconButton(
              icon: ArcaneIcon.x(size: IconSize.sm),
              label: 'Remove $label item ${index + 1}',
              onPressed: () => onChanged(<Object?>[...items]..removeAt(index)),
            ),
          ]),
        ]),
      Button.ghost(
        label: 'Add item',
        size: ButtonSize.small,
        attributes: <String, String>{'aria-label': 'Add an item to $label'},
        onPressed: () => onChanged(<Object?>[...items, convertTyped(null, last)]),
      ),
    ]);
  }
}

/// The entries of an object: a key, a kind and a value per row, with Add,
/// Remove and an "Advanced" toggle that edits the whole object as JSON.
class KeyValueEditor extends StatefulWidget {
  const KeyValueEditor({
    required this.value,
    required this.onChanged,
    required this.label,
    this.depth = 0,
    this.valueControl,
    this.addLabel = 'Add field',
    super.key,
  });

  final JsonMap value;
  final void Function(JsonMap value) onChanged;
  final String label;
  final int depth;

  /// A control for one entry's value in place of the typed one, for maps
  /// whose values share a schema.
  final Widget Function(String key, Object? value, void Function(Object? value) onChanged)? valueControl;
  final String addLabel;

  @override
  State<KeyValueEditor> createState() => _KeyValueEditorState();
}

class _KeyValueEditorState extends State<KeyValueEditor> {
  bool _advanced = false;

  void _put(String key, Object? next) => component.onChanged(putEntry(component.value, key, next));

  @override
  Widget build(BuildContext context) {
    final JsonMap value = component.value;
    final List<String> keys = value.keys.toList();
    return dom.div(classes: 'animus-kv', attributes: <String, String>{'role': 'group', 'aria-label': component.label}, <Widget>[
      if (_advanced)
        KeptJsonEditor(value: value, label: '${component.label} as JSON', onChanged: (Object? next) {
          if (next is Map<String, Object?>) component.onChanged(next);
        })
      else ...<Widget>[
        for (int index = 0; index < keys.length; index++)
          dom.div(key: ValueKey<String>('entry:$index'), classes: 'animus-kv-row', <Widget>[
            _KeyField(
              name: keys[index],
              label: '${component.label} key ${index + 1}',
              problemFor: (String to) => renameProblem(value, keys[index], to),
              onRename: (String to) => component.onChanged(renameKey(value, keys[index], to)),
            ),
            dom.div(classes: 'animus-kv-value', <Widget>[
              component.valueControl?.call(keys[index], value[keys[index]], (Object? next) => _put(keys[index], next)) ??
                  TypedValueEditor(
                    value: value[keys[index]],
                    label: keys[index],
                    depth: component.depth,
                    onChanged: (Object? next) => _put(keys[index], next),
                  ),
            ]),
            GuiIconButton(
              icon: ArcaneIcon.x(size: IconSize.sm),
              label: 'Remove ${keys[index]}',
              onPressed: () => component.onChanged(removeEntry(value, keys[index])),
            ),
          ]),
        if (keys.isEmpty) const dom.span(classes: 'animus-muted animus-kv-empty', <Widget>[Component.text('No fields.')]),
      ],
      dom.div(classes: 'animus-row animus-kv-tools', <Widget>[
        if (!_advanced)
          Button.ghost(
            label: component.addLabel,
            size: ButtonSize.small,
            attributes: <String, String>{'aria-label': '${component.addLabel} to ${component.label}'},
            onPressed: () => component.onChanged(putEntry(value, freshKey(value), '')),
          ),
        Button.ghost(
          label: 'Advanced',
          size: ButtonSize.small,
          attributes: <String, String>{'aria-pressed': '$_advanced', 'title': 'Edit ${component.label} as JSON'},
          onPressed: () => setState(() => _advanced = !_advanced),
        ),
      ]),
    ]);
  }
}

/// An entry's key. Typing does not rename on every keystroke: Enter or
/// leaving the field renames, Escape restores the key.
class _KeyField extends StatefulWidget {
  const _KeyField({required this.name, required this.label, required this.onRename, required this.problemFor});

  final String name;
  final String label;
  final void Function(String to) onRename;
  final String? Function(String to) problemFor;

  @override
  State<_KeyField> createState() => _KeyFieldState();
}

class _KeyFieldState extends State<_KeyField> {
  String? _typed;
  String? _problem;

  void _commit() {
    final String? typed = _typed;
    if (typed == null) return;
    final String to = typed.trim();
    final String? problem = component.problemFor(to);
    if (problem != null) {
      setState(() => _problem = problem);
      return;
    }
    setState(() {
      _typed = null;
      _problem = null;
    });
    if (to != component.name) component.onRename(to);
  }

  @override
  Widget build(BuildContext context) {
    return dom.div(classes: 'animus-kv-key', <Widget>[
      dom.input(
        classes: 'animus-gui-input is-code${_problem != null ? ' is-invalid' : ''}',
        value: _typed ?? component.name,
        attributes: <String, String>{
          'aria-label': component.label,
          'spellcheck': 'false',
          'autocomplete': 'off',
          if (_problem != null) 'aria-invalid': 'true',
        },
        events: <String, void Function(web.Event)>{
          'input': (web.Event event) {
            _typed = (event.target! as web.HTMLInputElement).value;
            if (_problem != null) setState(() => _problem = null);
          },
          'keydown': (web.Event event) {
            final String key = (event as web.KeyboardEvent).key;
            if (key == 'Enter') {
              event.preventDefault();
              _commit();
            } else if (key == 'Escape' && _typed != null) {
              event.stopPropagation();
              setState(() {
                _typed = null;
                _problem = null;
              });
              (event.target! as web.HTMLInputElement).value = component.name;
            }
          },
          'blur': (web.Event _) => _commit(),
        },
      ),
      if (_problem != null) dom.span(classes: 'animus-gui-problem is-error', attributes: const <String, String>{'role': 'alert'}, <Widget>[Component.text(_problem!)]),
    ]);
  }
}
