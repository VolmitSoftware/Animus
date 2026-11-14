/// Field editors driven by [FieldSpec]s: the NPC inspector and the behavior
/// node editor render schema and completions.json fields through these.
/// Help comes from the schema descriptions; values outside what a typed
/// field can show are edited as JSON and kept.
library;

import 'dart:convert';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;
import 'package:web/web.dart' as web;

import '../../logic/gui/gui_json.dart';
import '../../logic/inspect/named_ids.dart';
import '../../logic/inspect/schema_fields.dart';
import '../../logic/inspect/typed_values.dart';
import '../../logic/suggest/field_profile.dart';
import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../../mc/npc/npc_objects.dart' show mcPaintings;
import '../../ui/mode.dart';
import '../gui/gui_fields.dart';
import '../mc/item_picker.dart';
import '../../ui/density.dart';
import '../../ui/field.dart';
import '../select/suggest_input.dart';
import 'typed_values_editor.dart';
import '../select/animus_select.dart';
import '../../ui/action_row.dart';
import '../../ui/number_input.dart';
import '../../ui/stack.dart';
import '../../ui/toggle_choice.dart';
import 'inspect_lines.dart';
import '../../ui/problem_text.dart';

/// A label with a required marker; keys read as words (`waitTicks` is "Wait ticks").
String fieldLabel(FieldSpec field, {String? label}) => '${label ?? humanizeKey(field.key)}${field.required ? ' *' : ''}';

/// An element id for the control at [path], so a row label can name it.
String controlId(String path) => 'animus-field-${path.replaceAll(RegExp(r'[^A-Za-z0-9_-]+'), '-')}';

/// Diagnostics for a field: the document's at [path] and the field's own
/// bounds check of [value].
List<Diagnostic> fieldProblems(FieldSpec field, Object? value, String path, List<Diagnostic> all, {bool nested = false}) {
  final List<Diagnostic> found = diagnosticsAt(all, path, nested: nested);
  final String? local = field.check(value);
  if (local != null && found.isEmpty) return <Diagnostic>[Diagnostic(DiagnosticSeverity.warning, path, local, code: 'field')];
  return found;
}

/// One field: a labelled row with the control its kind needs.
class InspectField extends StatelessWidget {
  const InspectField({
    required this.field,
    required this.value,
    required this.path,
    required this.onChanged,
    this.diagnostics = const <Diagnostic>[],
    this.suggestions = const <FieldOption>[],
    this.schema,
    this.label,
    this.profile = FieldProfile.none,
    this.bounds = true,
    super.key,
  });

  final FieldSpec field;
  final Object? value;

  /// What a text value completes beyond [suggestions].
  final FieldProfile profile;

  /// Shows the schema's bounds under the control ("1 to 64").
  final bool bounds;

  /// The field's path, as diagnostics name it.
  final String path;

  /// A whole new value; null removes the key.
  final void Function(Object? value) onChanged;
  final List<Diagnostic> diagnostics;

  /// Values to offer while typing a string (workspace ids, routine names).
  final List<FieldOption> suggestions;

  /// For object fields: the schema document, to render nested fields.
  final SchemaView? schema;
  final String? label;

  @override
  Widget build(BuildContext context) {
    final bool nested = field.kind == FieldKind.object || field.kind == FieldKind.array || field.kind == FieldKind.any;
    return Field(
      label: fieldLabel(field, label: label),
      forId: InspectControl.focusable(field, value) ? controlId(path) : null,
      help: bounds ? field.boundsHint : null,
      // Simple's pickers name the ids a description spells out.
      tip: ModeScope.advanced(context) ? field.description ?? '' : withoutIdSentences(bodiesInWords(field.description ?? '')),
      problems: fieldProblems(field, value, path, diagnostics, nested: nested),
      child: InspectControl(
        field: field,
        value: value,
        path: path,
        onChanged: onChanged,
        diagnostics: diagnostics,
        suggestions: suggestions,
        schema: schema,
        profile: profile,
        label: label,
      ),
    );
  }
}

/// The control alone, for rows that lay out their own label.
class InspectControl extends StatelessWidget {
  const InspectControl({
    required this.field,
    required this.value,
    required this.path,
    required this.onChanged,
    this.diagnostics = const <Diagnostic>[],
    this.suggestions = const <FieldOption>[],
    this.schema,
    this.profile = FieldProfile.none,
    this.references,
    this.label,
    super.key,
  });

  /// The row's visible label, which screen readers hear for the control;
  /// defaults to the key in words.
  final String? label;

  final FieldSpec field;
  final Object? value;
  final String path;
  final void Function(Object? value) onChanged;
  final List<Diagnostic> diagnostics;
  final List<FieldOption> suggestions;
  final SchemaView? schema;

  /// What a text value completes beyond [suggestions].
  final FieldProfile profile;
  /// The document kind a text value names, for "+ New" and "Manage".
  final DocumentKind? references;

  /// True when the control for [value] is one input or select carrying
  /// [controlId], so a row label can focus it.
  static bool focusable(FieldSpec field, Object? value) => switch (field.kind) {
        FieldKind.choice => field.options.every((FieldOption option) => option.value is String) &&
            !(field.options.length == 1 && (value == null || value == field.options.first.value)),
        FieldKind.integer || FieldKind.number => value is num || value == null,
        FieldKind.string => value is String || value == null,
        _ => false,
      };

  @override
  Widget build(BuildContext context) {
    final Object? current = value;
    final String name = label ?? humanizeKey(field.key);
    switch (field.kind) {
      case FieldKind.choice:
        final List<String> options = <String>[for (final FieldOption option in field.options) option.label];
        final bool textual = field.options.every((FieldOption option) => option.value is String);
        if (field.options.length == 1 && (current == null || current == field.options.first.value)) {
          return dom.code(classes: 'animus-inspect-const', <Widget>[Component.text(jsonEncode(field.options.first.value))]);
        }
        if (!textual) {
          final Map<String, Object> byLabel = <String, Object>{for (final FieldOption option in field.options) option.label: option.value};
          return AnimusSelect.values(
            id: controlId(path),
            value: current == null ? null : '$current',
            values: options,
            allowNone: !field.required,
            noneLabel: field.required ? 'Pick ${humanizeKey(field.key).toLowerCase()}' : 'Not set',
            onChanged: (String? next) => onChanged(next == null ? null : byLabel[next]),
          );
        }
        if (field.openChoice) {
          return SuggestInput(
            id: controlId(path),
            value: current is String ? current : '',
            label: name,
            profile: profile,
            references: references,
            suggestions: <FieldOption>[...field.options, ...suggestions],
            onChanged: (String next) => onChanged(next.isEmpty && !field.required ? null : next),
          );
        }
        return AnimusSelect.values(
          id: controlId(path),
          value: current is String ? current : null,
          values: options,
          allowNone: !field.required,
          noneLabel: field.required ? 'Pick ${humanizeKey(field.key).toLowerCase()}' : 'Not set',
          labels: <String, String>{for (final FieldOption option in field.options) option.label: option.label},
          onChanged: onChanged,
        );
      case FieldKind.boolean:
        if (current is bool || current == null) {
          if (field.required) return ArcaneToggleSwitch(value: current == true, ariaLabel: name, onChanged: onChanged, size: ComponentSize.sm);
          // An optional switch has three states, so it is one control of
          // three choices rather than a switch with an Unset button under it.
          final Object? fallback = field.schema['default'];
          return ToggleChoice<bool?>(
            label: name,
            options: <ChoiceOption<bool?>>[
              ChoiceOption<bool?>(null, fallback is bool ? 'Default (${fallback ? 'on' : 'off'})' : 'Default'),
              const ChoiceOption<bool?>(true, 'On'),
              const ChoiceOption<bool?>(false, 'Off'),
            ],
            value: current as bool?,
            onChanged: onChanged,
          );
        }
      case FieldKind.integer || FieldKind.number:
        if (current is num || current == null) {
          return NumberInput(
            id: controlId(path),
            value: current as num?,
            integer: field.kind == FieldKind.integer,
            label: name,
            digits: field.digits,
            onChanged: onChanged,
          );
        }
      case FieldKind.string:
        if (current is String || current == null) {
          final NamedId? named = ModeScope.advanced(context) ? null : namedIdOf(path, field.key, field.schema);
          if (named != null) return _named(named, current as String? ?? '', name);
          return SuggestInput(
            id: controlId(path),
            value: current as String? ?? '',
            label: name,
            profile: profile,
            references: references,
            suggestions: suggestions,
            onChanged: (String next) => onChanged(next.isEmpty && !field.required ? null : next),
          );
        }
      case FieldKind.object:
        final SchemaView? view = schema;
        if (current is Map<String, Object?> || current == null) {
          void put(JsonMap next) => onChanged(next.isEmpty && !field.required ? null : next);
          if (view != null && view.fields(field.schema, current).isNotEmpty) {
            return InspectObject(schema: view, node: field.schema, value: asMap(current), path: path, diagnostics: diagnostics, onChanged: put);
          }
          final Map<String, Object?>? values = view?.additional(field.schema, current, '');
          return KeyValueEditor(
            value: asMap(current),
            label: humanizeKey(field.key),
            onChanged: put,
            valueControl: view == null || values == null || values.isEmpty
                ? null
                : (String key, Object? entry, void Function(Object? value) onEntry) => InspectControl(
                      field: view.field(key, values),
                      value: entry,
                      path: childPath(path, key),
                      diagnostics: diagnostics,
                      schema: view,
                      onChanged: onEntry,
                    ),
          );
        }
      case FieldKind.array:
        final Map<String, Object?> items = schema?.resolve(field.schema['items']) ?? const <String, Object?>{};
        if (current is List<Object?> || current == null) {
          if (items['type'] == 'string' && !items.containsKey('enum') && asList(current).every((Object? item) => item is String)) {
            return GuiLinesEditor(
              lines: stringList(current),
              mode: GuiTextMode.plain,
              addLabel: 'Add',
              onChanged: (List<String> lines) => onChanged(lines.isEmpty && !field.required ? null : lines),
            );
          }
          final SchemaView? view = schema;
          if (view != null && items.isNotEmpty) {
            return InspectArray(
              schema: view,
              items: view.field('${field.key} item', items),
              value: asList(current),
              path: path,
              diagnostics: diagnostics,
              onChanged: (List<Object?> next) => onChanged(next.isEmpty && !field.required ? null : next),
            );
          }
        }
      case FieldKind.any:
        break;
    }
    return TypedValueEditor(value: current, label: humanizeKey(field.key), onChanged: onChanged);
  }

  /// Simple's picker for a text field holding a game id: items and blocks
  /// by name (a picked block drops the old block's state), paintings from
  /// the vanilla list. The id it writes is the one picked.
  Widget _named(NamedId named, String current, String name) {
    void put(String next) => onChanged(next.isEmpty && !field.required ? null : next);
    return switch (named) {
      NamedId.item => ItemPicker(id: controlId(path), value: current, label: name, onChanged: put),
      NamedId.block => ItemPicker(
          id: controlId(path),
          value: blockOfState(current),
          label: name,
          onChanged: (String next) => put(blockOfState(next) == blockOfState(current) ? current : next),
        ),
      NamedId.painting => _painting(current, name, put),
    };
  }

  Widget _painting(String current, String name, void Function(String next) put) {
    final String painting = current.isEmpty || current.contains(':') ? current : 'minecraft:$current';
    final List<String> vanilla = <String>[for (final String key in mcPaintings.keys) 'minecraft:$key'];
    return AnimusSelect.values(
      id: controlId(path),
      hiddenLabel: name,
      value: painting.isEmpty ? null : painting,
      values: <String>[...vanilla, if (painting.isNotEmpty && !vanilla.contains(painting)) painting],
      labels: <String, String>{for (final String each in <String>[...vanilla, if (painting.isNotEmpty) painting]) each: paintingName(each)},
      allowNone: !field.required,
      noneLabel: 'Not set',
      onChanged: (String? next) => put(next ?? ''),
    );
  }
}

/// A list whose items share the schema [items]: one control per item, or a
/// form per item for objects, with move, remove and add.
class InspectArray extends StatelessWidget {
  const InspectArray({required this.schema, required this.items, required this.value, required this.path, required this.onChanged, this.diagnostics = const <Diagnostic>[], super.key});

  final SchemaView schema;
  final FieldSpec items;
  final List<Object?> value;
  final String path;
  final void Function(List<Object?> value) onChanged;
  final List<Diagnostic> diagnostics;

  /// A starting value for a new item of [items].
  Object? _blank() => switch (items.kind) {
        FieldKind.choice => items.options.isEmpty ? '' : items.options.first.value,
        FieldKind.string => '',
        FieldKind.integer || FieldKind.number => items.minimum ?? 0,
        FieldKind.boolean => false,
        FieldKind.object => <String, Object?>{
            for (final FieldSpec field in schema.fields(items.schema, const <String, Object?>{}))
              if (field.required && field.kind == FieldKind.choice && field.options.isNotEmpty) field.key: field.options.first.value,
          },
        FieldKind.array => <Object?>[],
        FieldKind.any => '',
      };

  @override
  Widget build(BuildContext context) {
    final String noun = humanizeKey(items.key);
    // A one-control item keeps its move and remove buttons on its own row;
    // an object or list item has them on a row under its fields.
    final bool inline = items.kind != FieldKind.object && items.kind != FieldKind.array && items.kind != FieldKind.any;
    return dom.div(attributes: <String, String>{'role': 'group', 'aria-label': noun}, <Widget>[
      Column(crossAxisAlignment: CrossAxisAlignment.stretch, mainAxisSize: MainAxisSize.min, gapSize: Gap.sm, children: <Widget>[
        for (int index = 0; index < value.length; index++)
          Column(key: ValueKey<String>('item:$index'), crossAxisAlignment: CrossAxisAlignment.stretch, mainAxisSize: MainAxisSize.min, gapSize: Gap.xs, children: <Widget>[
            if (inline)
              ActionRow(dense: true, label: '$noun ${index + 1}', leading: <Widget>[Expanded(child: _item(index))], secondary: _actions(noun, index))
            else ...<Widget>[
              _item(index),
              ActionRow(dense: true, label: '$noun ${index + 1}', secondary: _actions(noun, index)),
            ],
            for (final Diagnostic problem in diagnosticsAt(diagnostics, indexPath(path, index)))
              problemLine(problem.severity, problem.message),
          ]),
        ActionRow(
          dense: DensityScope.of(context) == Density.dense,
          secondary: <RowAction>[RowAction('Add', () => onChanged(<Object?>[...value, _blank()]), attributes: <String, String>{'aria-label': 'Add $noun'})],
        ),
      ]),
    ]);
  }

  Widget _item(int index) => InspectControl(
        field: items,
        value: value[index],
        path: indexPath(path, index),
        diagnostics: diagnostics,
        schema: schema,
        onChanged: (Object? next) => onChanged(<Object?>[...value]..[index] = next),
      );

  List<RowAction> _actions(String noun, int index) => <RowAction>[
        RowAction('Move $noun ${index + 1} up', index == 0 ? null : () => onChanged(moveItem(value, index, index - 1)), icon: ArcaneIcon.arrowUp(), iconOnly: true),
        RowAction('Move $noun ${index + 1} down', index == value.length - 1 ? null : () => onChanged(moveItem(value, index, index + 1)), icon: ArcaneIcon.arrowDown(), iconOnly: true),
        RowAction('Remove $noun ${index + 1}', () => onChanged(<Object?>[...value]..removeAt(index)), icon: ArcaneIcon.x(), iconOnly: true),
      ];
}

/// The fields of an object under [node], then its keys the schema does not
/// name, kept and edited as JSON.
class InspectObject extends StatelessWidget {
  const InspectObject({
    required this.schema,
    required this.node,
    required this.value,
    required this.path,
    required this.onChanged,
    this.diagnostics = const <Diagnostic>[],
    this.skip = const <String>{},
    this.unknownLabel = 'Other fields',
    super.key,
  });

  final SchemaView schema;
  final Map<String, Object?> node;
  final JsonMap value;
  final String path;
  final void Function(JsonMap value) onChanged;
  final List<Diagnostic> diagnostics;

  /// Keys another part of the inspector shows.
  final Set<String> skip;

  /// The label over the keys the schema does not name.
  final String unknownLabel;


  @override
  Widget build(BuildContext context) {
    final List<FieldSpec> fields = schema.fields(node, value);
    // Simple keeps an item's component text and import record as written.
    final Set<String> hidden = ModeScope.advanced(context) ? const <String>{} : simpleHiddenKeys(path, <String>{for (final FieldSpec field in fields) field.key});
    final Set<String> skip = <String>{...this.skip, ...hidden};
    final Set<String> known = <String>{for (final FieldSpec field in fields) field.key, ...skip};
    final JsonMap unknown = unknownEntries(value, known);
    void put(String key, Object? next) => onChanged(asMap(writeIn(value, <JsonStep>[key], next)));
    return dom.div(<Widget>[
      for (final FieldSpec field in fields)
        if (!skip.contains(field.key))
          InspectField(
            key: ValueKey<String>('field:${field.key}'),
            field: field,
            value: value[field.key],
            path: childPath(path, field.key),
            diagnostics: diagnostics,
            schema: schema,
            onChanged: (Object? next) => put(field.key, next),
          ),
      if (unknown.isNotEmpty)
        Field(
          key: const ValueKey<String>('other-fields'),
          label: unknownLabel,
          tip: 'Keys this form does not know. They are kept as written and the server reads them.',
          problems: <Diagnostic>[for (final String key in unknown.keys) ...diagnosticsAt(diagnostics, childPath(path, key), nested: true)],
          child: KeyValueEditor(
            value: unknown,
            label: unknownLabel,
            onChanged: (JsonMap next) => onChanged(withUnknownEntries(value, known, next)),
          ),
        ),
    ]);
  }
}

/// A JSON value edited as text. Each parseable edit is reported; text that
/// does not parse shows its error and changes nothing.
class KeptJsonEditor extends StatefulWidget {
  const KeptJsonEditor({required this.value, required this.onChanged, this.label, this.rows, super.key});

  final Object? value;
  final void Function(Object? value) onChanged;
  final String? label;
  final int? rows;

  @override
  State<KeptJsonEditor> createState() => _KeptJsonEditorState();
}

class _KeptJsonEditorState extends State<KeptJsonEditor> {
  String? _typed;
  String? _error;

  String get _shown => const JsonEncoder.withIndent('  ').convert(component.value);

  @override
  Widget build(BuildContext context) {
    final String text = _typed ?? _shown;
    final int rows = component.rows ?? text.split('\n').length.clamp(1, 12);
    return stack(spacing: 4, <Widget>[
      dom.textarea(
        classes: 'arcane-textarea animus-kept-json',
        styles: codeFont,
        rows: rows,
        attributes: <String, String>{'spellcheck': 'false', 'aria-label': ?component.label, if (_error != null) 'aria-invalid': 'true'},
        events: <String, void Function(web.Event)>{
          'input': (web.Event event) {
            final String raw = (event.target! as web.HTMLTextAreaElement).value;
            _typed = raw;
            try {
              final Object? parsed = jsonDecode(raw);
              if (_error != null) setState(() => _error = null);
              component.onChanged(parsed);
            } on FormatException catch (problem) {
              setState(() => _error = problem.message);
            }
          },
          'blur': (web.Event _) {
            if (mounted && _error == null) setState(() => _typed = null);
          },
        },
        <Widget>[Component.text(text)],
      ),
      if (_error != null) problemLine(DiagnosticSeverity.error, 'Invalid JSON: $_error'),
    ]);
  }
}
