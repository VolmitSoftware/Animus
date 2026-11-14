/// Editors for values no schema describes: a value with a kind picker (text,
/// number, true/false, list, object, empty) and a key/value editor whose
/// rows each hold one. Raw JSON stays behind an "Edit as JSON" toggle.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:web/web.dart' as web;

import '../../logic/gui/gui_json.dart';
import '../../logic/inspect/typed_values.dart';
import '../../logic/select/select_model.dart';
import '../../logic/suggest/field_profile.dart';
import '../../ui/action_row.dart';
import '../../ui/density.dart';
import '../../ui/menu.dart';
import '../select/animus_select.dart';
import '../select/suggest_input.dart';
import 'inspect_fields.dart' show KeptJsonEditor;
import '../../ui/icon_button.dart';
import '../../ui/number_input.dart';
import '../../ui/stack.dart';
import '../../ui/problem_text.dart';

/// Object and list nesting the typed editors draw before showing JSON.
const int typedEditorDepth = 4;

/// Text of an unknown key: MiniMessage tags complete on `<`, plain words are
/// left alone.
FieldProfile typedTextProfile(String key) => FieldProfile.richText;

final List<SelectOption> _kindOptions = <SelectOption>[for (final TypedKind kind in TypedKind.values) SelectOption(label: kind.label, value: kind.name)];

/// One value of any kind: the kind picker, then the control for that kind.
class TypedValueEditor extends StatelessWidget {
  const TypedValueEditor({required this.value, required this.onChanged, required this.label, this.depth = 0, this.profileFor = typedTextProfile, super.key});

  final Object? value;
  final void Function(Object? value) onChanged;

  /// Names the controls for assistive technology ("Value of speed").
  final String label;
  final int depth;

  /// What text completes, by the key it sits under (a list item uses its
  /// list's key); nested objects and lists pass it on.
  final FieldProfile Function(String key) profileFor;

  @override
  Widget build(BuildContext context) {
    final TypedKind kind = typedKindOf(value);
    final bool nests = kind == TypedKind.list || kind == TypedKind.object;
    // A kind picker as wide as its longest kind; a list or object drops
    // under it, indented.
    final Widget picker = SizedBox(
      width: 128,
      child: AnimusSelect(value: kind.name, hiddenLabel: 'Kind of $label', options: _kindOptions, onChanged: (String next) => onChanged(convertTyped(value, TypedKind.parse(next) ?? kind))),
    );
    return dom.div(classes: 'animus-typed-value', <Widget>[
      if (nests)
        Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          gapSize: Gap.sm,
          children: <Widget>[
            picker,
            Padding(padding: const EdgeInsets.only(left: 12), child: _control(kind)),
          ],
        )
      else
        Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          gapSize: Gap.sm,
          children: <Widget>[
            picker,
            Expanded(child: _control(kind)),
          ],
        ),
    ]);
  }

  Widget _control(TypedKind kind) {
    final Object? current = value;
    if (depth >= typedEditorDepth && (kind == TypedKind.list || kind == TypedKind.object)) {
      return KeptJsonEditor(value: current, label: label, onChanged: onChanged);
    }
    return switch (kind) {
      TypedKind.text => SuggestInput(value: current! as String, label: label, profile: profileFor(label), onChanged: onChanged),
      TypedKind.number => NumberInput(value: current! as num, integer: false, label: label, onChanged: (num? next) => onChanged(next ?? 0)),
      TypedKind.boolean => ArcaneToggleSwitch(value: current! as bool, label: label, onChanged: onChanged, size: ComponentSize.sm),
      TypedKind.list => TypedListEditor(items: current! as List<Object?>, label: label, depth: depth + 1, profile: profileFor(label), onChanged: onChanged),
      TypedKind.object => KeyValueEditor(value: current! as JsonMap, label: label, depth: depth + 1, profileFor: profileFor, onChanged: onChanged),
      TypedKind.empty => noteLine('null'),
    };
  }
}

/// A list whose items each have their own kind.
class TypedListEditor extends StatelessWidget {
  const TypedListEditor({required this.items, required this.onChanged, required this.label, this.depth = 0, this.profile = FieldProfile.richText, super.key});

  final List<Object?> items;
  final void Function(List<Object?> items) onChanged;
  final String label;
  final int depth;

  /// What the items' text completes.
  final FieldProfile profile;

  @override
  Widget build(BuildContext context) {
    final TypedKind last = items.isEmpty ? TypedKind.text : typedKindOf(items.last);
    return dom.div(
      attributes: <String, String>{'role': 'group', 'aria-label': label},
      <Widget>[
        stack(<Widget>[
          for (int index = 0; index < items.length; index++)
            Row(
              key: ValueKey<String>('item:$index'),
              crossAxisAlignment: CrossAxisAlignment.start,
              gapSize: Gap.sm,
              children: <Widget>[
                Expanded(
                  child: TypedValueEditor(
                    value: items[index],
                    label: '$label item ${index + 1}',
                    depth: depth,
                    profileFor: (String _) => profile,
                    onChanged: (Object? next) => onChanged(<Object?>[...items]..[index] = next),
                  ),
                ),
                AppMenu(
                  icon: ArcaneIcon.ellipsis(),
                  alignEnd: true,
                  attributes: <String, String>{'data-item-menu': '${index + 1}'},
                  entries: <AppMenuEntry>[
                    AppMenuEntry('Move up', index == 0 ? null : () => onChanged(moveItem(items, index, index - 1))),
                    AppMenuEntry('Move down', index == items.length - 1 ? null : () => onChanged(moveItem(items, index, index + 1))),
                    const AppMenuEntry.separator(),
                    AppMenuEntry('Remove item', () => onChanged(<Object?>[...items]..removeAt(index)), destructive: true),
                  ],
                ),
              ],
            ),
          ActionRow(
            dense: true,
            secondary: <RowAction>[
              RowAction('Add item', () => onChanged(<Object?>[...items, convertTyped(null, last)]), attributes: <String, String>{'aria-label': 'Add an item to $label'}),
            ],
          ),
        ]),
      ],
    );
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
    this.profileFor = typedTextProfile,
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

  /// What an entry's text completes, by its key.
  final FieldProfile Function(String key) profileFor;

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
    return dom.div(
      attributes: <String, String>{'role': 'group', 'aria-label': component.label},
      <Widget>[
        stack(<Widget>[
          if (_advanced)
            KeptJsonEditor(
              value: value,
              label: '${component.label} as JSON',
              onChanged: (Object? next) {
                if (next is Map<String, Object?>) component.onChanged(next);
              },
            )
          else ...<Widget>[
            for (int index = 0; index < keys.length; index++)
              // The key with its Remove, the value under it, indented.
              stack(key: ValueKey<String>('entry:$index'), spacing: 4, <Widget>[
                Row(crossAxisAlignment: CrossAxisAlignment.start, gapSize: Gap.sm, children: <Widget>[
                  Expanded(
                    child: _KeyField(
                      name: keys[index],
                      label: '${component.label} key ${index + 1}',
                      problemFor: (String to) => renameProblem(value, keys[index], to),
                      onRename: (String to) => component.onChanged(renameKey(value, keys[index], to)),
                    ),
                  ),
                  AppIconButton(icon: ArcaneIcon.x(), label: 'Remove ${keys[index]}', onPressed: () => component.onChanged(removeEntry(value, keys[index]))),
                ]),
                Padding(
                  padding: const EdgeInsets.only(left: 12),
                  child: component.valueControl?.call(keys[index], value[keys[index]], (Object? next) => _put(keys[index], next)) ??
                      TypedValueEditor(value: value[keys[index]], label: keys[index], depth: component.depth, profileFor: component.profileFor, onChanged: (Object? next) => _put(keys[index], next)),
                ),
              ]),
            if (keys.isEmpty) noteLine('No fields.'),
          ],
          // One row per level: a nested level names whose field it adds,
          // and only the outermost offers Edit as JSON, so the rows that end
          // a deep value never stack up as identical pairs.
          ActionRow(
            dense: true,
            secondary: <RowAction>[
              if (!_advanced)
                RowAction(
                  component.depth == 0 ? component.addLabel : 'Add to ${component.label}',
                  () => component.onChanged(putEntry(value, freshKey(value), '')),
                  attributes: <String, String>{'aria-label': '${component.addLabel} to ${component.label}'},
                ),
              if (component.depth == 0) RowAction('Edit as JSON', () => setState(() => _advanced = !_advanced), pressed: _advanced),
            ],
          ),
        ]),
      ],
    );
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
    // Enter (the input's submit) and leaving rename; Escape puts the key back.
    return dom.div(
      events: <String, void Function(web.Event)>{
        'keydown': (web.Event event) {
          if ((event as web.KeyboardEvent).key != 'Escape' || _typed == null) return;
          event.stopPropagation();
          setState(() {
            _typed = null;
            _problem = null;
          });
          (event.target! as web.HTMLInputElement).value = component.name;
        },
      },
      <Widget>[
        TextInput(
          value: _typed ?? component.name,
          size: controlSize(DensityScope.of(context)),
          fullWidth: true,
          error: _problem,
          attributes: <String, String>{'aria-label': component.label, 'spellcheck': 'false', 'autocomplete': 'off'},
          onChanged: (String text) {
            _typed = text;
            if (_problem != null) setState(() => _problem = null);
          },
          onSubmitted: (String _) => _commit(),
          onBlur: _commit,
        ),
      ],
    );
  }
}
