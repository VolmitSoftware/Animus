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
import '../../logic/inspect/schema_fields.dart';
import '../../model/animus_document.dart';
import '../gui/gui_fields.dart';

int _nextListId = 0;

/// A label with a required marker.
String fieldLabel(FieldSpec field, {String? label}) => '${label ?? humanizeKey(field.key)}${field.required ? ' *' : ''}';

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
    super.key,
  });

  final FieldSpec field;
  final Object? value;

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
    return GuiRow(
      label: fieldLabel(field, label: label),
      help: field.help,
      problems: fieldProblems(field, value, path, diagnostics, nested: nested),
      child: InspectControl(field: field, value: value, path: path, onChanged: onChanged, diagnostics: diagnostics, suggestions: suggestions, schema: schema),
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
    super.key,
  });

  final FieldSpec field;
  final Object? value;
  final String path;
  final void Function(Object? value) onChanged;
  final List<Diagnostic> diagnostics;
  final List<FieldOption> suggestions;
  final SchemaView? schema;

  @override
  Widget build(BuildContext context) {
    final Object? current = value;
    switch (field.kind) {
      case FieldKind.choice:
        final List<String> options = <String>[for (final FieldOption option in field.options) option.label];
        final bool textual = field.options.every((FieldOption option) => option.value is String);
        if (field.options.length == 1 && (current == null || current == field.options.first.value)) {
          return dom.code(classes: 'animus-inspect-const', <Widget>[Component.text(jsonEncode(field.options.first.value))]);
        }
        if (!textual) break;
        if (field.openChoice) {
          return SuggestInput(
            value: current is String ? current : '',
            label: field.key,
            suggestions: <FieldOption>[...field.options, ...suggestions],
            onChanged: (String next) => onChanged(next.isEmpty && !field.required ? null : next),
          );
        }
        return GuiSelect(
          value: current is String ? current : null,
          options: options,
          allowNone: !field.required,
          noneLabel: field.required ? 'Pick ${humanizeKey(field.key).toLowerCase()}' : 'Not set',
          label: humanizeKey(field.key),
          labels: <String, String>{for (final FieldOption option in field.options) option.label: option.label},
          onChanged: onChanged,
        );
      case FieldKind.boolean:
        if (current is bool || current == null) {
          return dom.div(classes: 'animus-row', <Widget>[
            GuiSwitch(value: current == true, label: field.key, onChanged: onChanged),
            if (current != null && !field.required)
              Button.ghost(label: 'Unset', size: ButtonSize.small, onPressed: () => onChanged(null)),
          ]);
        }
      case FieldKind.integer || FieldKind.number:
        if (current is num || current == null) {
          return GuiNumberInput(
            value: current as num?,
            integer: field.kind == FieldKind.integer,
            label: field.key,
            placeholder: field.bounds.isEmpty ? null : field.bounds,
            onChanged: onChanged,
          );
        }
      case FieldKind.string:
        if (current is String || current == null) {
          return SuggestInput(
            value: current as String? ?? '',
            label: field.key,
            suggestions: suggestions,
            placeholder: field.bounds.isEmpty ? null : field.bounds,
            onChanged: (String next) => onChanged(next.isEmpty && !field.required ? null : next),
          );
        }
      case FieldKind.object:
        final SchemaView? view = schema;
        if (view != null && (current is Map<String, Object?> || current == null) && field.schema.containsKey('properties')) {
          return InspectObject(
            schema: view,
            node: field.schema,
            value: asMap(current),
            path: path,
            diagnostics: diagnostics,
            onChanged: (JsonMap next) => onChanged(next.isEmpty && !field.required ? null : next),
          );
        }
      case FieldKind.array:
        final Map<String, Object?> items = schema?.resolve(field.schema['items']) ?? const <String, Object?>{};
        if (items['type'] == 'string' && (current is List<Object?> || current == null) && asList(current).every((Object? item) => item is String)) {
          return GuiLinesEditor(
            lines: stringList(current),
            mode: GuiTextMode.plain,
            addLabel: 'Add',
            onChanged: (List<String> lines) => onChanged(lines.isEmpty && !field.required ? null : lines),
          );
        }
      case FieldKind.any:
        break;
    }
    return KeptJsonEditor(value: current, label: field.key, onChanged: onChanged);
  }
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

  @override
  Widget build(BuildContext context) {
    final List<FieldSpec> fields = schema.fields(node, value);
    final Set<String> known = <String>{for (final FieldSpec field in fields) field.key};
    void put(String key, Object? next) => onChanged(asMap(writeIn(value, <JsonStep>[key], next)));
    return dom.div(classes: 'animus-inspect-object', <Widget>[
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
      for (final MapEntry<String, Object?> entry in value.entries)
        if (!known.contains(entry.key) && !skip.contains(entry.key))
          GuiRow(
            key: ValueKey<String>('kept:${entry.key}'),
            label: entry.key,
            help: 'Kept as written; the server reads it.',
            problems: diagnosticsAt(diagnostics, childPath(path, entry.key), nested: true),
            child: KeptJsonEditor(value: entry.value, label: entry.key, onChanged: (Object? next) => put(entry.key, next)),
          ),
    ]);
  }
}

/// A text field that offers [suggestions] through the browser's list.
class SuggestInput extends StatefulWidget {
  const SuggestInput({
    required this.value,
    required this.onChanged,
    this.suggestions = const <FieldOption>[],
    this.label,
    this.placeholder,
    super.key,
  });

  final String value;
  final void Function(String value) onChanged;
  final List<FieldOption> suggestions;
  final String? label;
  final String? placeholder;

  @override
  State<SuggestInput> createState() => _SuggestInputState();
}

class _SuggestInputState extends State<SuggestInput> {
  final String _list = 'animus-suggest-${_nextListId++}';

  /// The text while focused, kept over slower saves.
  String? _typed;

  @override
  Widget build(BuildContext context) {
    final bool listed = component.suggestions.isNotEmpty;
    return dom.div(classes: 'animus-gui-text', <Widget>[
      dom.input(
        classes: 'animus-gui-input',
        value: _typed ?? component.value,
        attributes: <String, String>{
          'autocomplete': 'off',
          'spellcheck': 'false',
          'aria-label': ?component.label,
          'placeholder': ?component.placeholder,
          if (listed) 'list': _list,
        },
        events: <String, void Function(web.Event)>{
          'input': (web.Event event) {
            final String text = (event.target! as web.HTMLInputElement).value;
            _typed = text;
            component.onChanged(text);
          },
          'blur': (web.Event _) {
            if (mounted) setState(() => _typed = null);
          },
        },
      ),
      if (listed)
        dom.datalist(id: _list, <Widget>[
          for (final FieldOption option in component.suggestions)
            dom.option(value: option.label, label: option.description ?? option.label, const <Widget>[]),
        ]),
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
    return dom.div(classes: 'animus-gui-text', <Widget>[
      dom.textarea(
        classes: 'animus-gui-input is-code animus-kept-json${_error != null ? ' is-invalid' : ''}',
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
      if (_error != null) dom.span(classes: 'animus-gui-problem is-error', <Widget>[Component.text('Invalid JSON: $_error')]),
    ]);
  }
}
