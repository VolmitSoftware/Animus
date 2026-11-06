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
import '../../model/document_kind.dart';
import '../gui/gui_fields.dart';
import '../../logic/help_text.dart';
import '../info_tip.dart';
import '../select/suggest_input.dart';

/// A label with a required marker; keys read as words (`waitTicks` is "Wait ticks").
String fieldLabel(FieldSpec field, {String? label}) => '${label ?? humanizeKey(field.key)}${field.required ? ' *' : ''}';

/// An element id for the control at [path], so a row label can name it.
String controlId(String path) => 'animus-field-${path.replaceAll(RegExp(r'[^A-Za-z0-9_-]+'), '-')}';

/// A labelled row like [GuiRow] whose label is a `<label for>` of the control
/// [labelFor], for selects and other controls without their own name.
class InspectRow extends StatelessWidget {
  const InspectRow({required this.label, required this.labelFor, required this.child, this.help, this.tip, this.problems = const <Diagnostic>[], super.key});

  final String label;
  final String labelFor;
  final Widget child;

  /// Shown under the control when short; long help moves into the info tip.
  final String? help;

  /// Tip text on the label; with it, [help] always stays inline.
  final String? tip;
  final List<Diagnostic> problems;

  @override
  Widget build(BuildContext context) {
    final ({String? hint, String? tip}) split = tip == null ? splitHelp(help) : (hint: help == null || help!.isEmpty ? null : help, tip: tip);
    return dom.div(classes: 'animus-gui-row${problems.any((Diagnostic d) => d.severity == DiagnosticSeverity.error) ? ' has-error' : ''}', <Widget>[
      dom.label(classes: 'animus-gui-label', attributes: <String, String>{'for': labelFor}, <Widget>[withTip(Component.text(label), split.tip)]),
      dom.div(classes: 'animus-gui-control', <Widget>[
        child,
        if (split.hint != null) dom.span(classes: 'animus-gui-help', <Widget>[Component.text(split.hint!)]),
        for (final Diagnostic problem in problems)
          dom.span(
            classes: 'animus-gui-problem is-${problem.severity.name}',
            attributes: const <String, String>{'role': 'note'},
            <Widget>[
              dom.code(<Widget>[Component.text(problem.path)]),
              Component.text(' ${problem.message}'),
            ],
          ),
      ]),
    ]);
  }
}

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
    return InspectRow(
      label: fieldLabel(field, label: label),
      labelFor: controlId(path),
      help: field.boundsHint,
      tip: field.description ?? '',
      problems: fieldProblems(field, value, path, diagnostics, nested: nested),
      child: InspectControl(
        field: field,
        value: value,
        path: path,
        onChanged: onChanged,
        diagnostics: diagnostics,
        suggestions: suggestions,
        schema: schema,
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
    this.references,
    super.key,
  });

  final FieldSpec field;
  final Object? value;
  final String path;
  final void Function(Object? value) onChanged;
  final List<Diagnostic> diagnostics;
  final List<FieldOption> suggestions;
  final SchemaView? schema;

  /// The document kind a text value names, for "+ New" and "Manage".
  final DocumentKind? references;

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
            references: references,
            suggestions: <FieldOption>[...field.options, ...suggestions],
            onChanged: (String next) => onChanged(next.isEmpty && !field.required ? null : next),
          );
        }
        return GuiSelect(
          id: controlId(path),
          value: current is String ? current : null,
          options: options,
          allowNone: !field.required,
          noneLabel: field.required ? 'Pick ${humanizeKey(field.key).toLowerCase()}' : 'Not set',
          labels: <String, String>{for (final FieldOption option in field.options) option.label: option.label},
          onChanged: onChanged,
        );
      case FieldKind.boolean:
        if (current is bool || current == null) {
          return dom.div(classes: 'animus-row', <Widget>[
            GuiSwitch(value: current == true, label: humanizeKey(field.key), onChanged: onChanged),
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
            references: references,
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
