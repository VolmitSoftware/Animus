/// The JSON Code view: a monospace text area with a line gutter that marks
/// lines holding diagnostics, a list of diagnostics that jump to their line
/// (server ones marked), and completion of keys and values at the caret
/// from the schema layer (Ctrl+Space, or as you type in a string).
library;

import 'dart:async';
import 'dart:js_interop';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;
import 'package:web/web.dart' as web;

import '../../logic/inspect/completion_catalog.dart';
import '../../logic/inspect/json_completion.dart';
import '../../logic/inspect/json_outline.dart';
import '../../logic/inspect/schema_fields.dart';
import '../../model/animus_document.dart';

int _nextEditorId = 0;

/// A diagnostic with where it came from and the line it maps to.
class LocatedDiagnostic {
  const LocatedDiagnostic(this.diagnostic, this.line, {this.server = false});

  final Diagnostic diagnostic;
  final int? line;
  final bool server;
}

/// [browser] and [server] diagnostics mapped to lines of [outline].
List<LocatedDiagnostic> locateDiagnostics(JsonOutline outline, List<Diagnostic> browser, List<Diagnostic> server) => <LocatedDiagnostic>[
      for (final Diagnostic diagnostic in browser) LocatedDiagnostic(diagnostic, outline.lineForPath(diagnostic.path)),
      for (final Diagnostic diagnostic in server) LocatedDiagnostic(diagnostic, outline.lineForPath(diagnostic.path), server: true),
    ];

class JsonCodeEditor extends StatefulWidget {
  const JsonCodeEditor({
    required this.text,
    required this.onChanged,
    this.schema,
    this.catalog,
    this.hints,
    this.diagnostics = const <Diagnostic>[],
    this.serverDiagnostics = const <Diagnostic>[],
    super.key,
  });

  final String text;
  final void Function(String text) onChanged;

  /// The document's schema; null turns completion off.
  final SchemaView? schema;
  final CompletionCatalog? catalog;
  final ValueHints? hints;
  final List<Diagnostic> diagnostics;
  final List<Diagnostic> serverDiagnostics;

  @override
  State<JsonCodeEditor> createState() => _JsonCodeEditorState();
}

class _JsonCodeEditorState extends State<JsonCodeEditor> {
  final String _id = 'animus-code-${_nextEditorId++}';
  List<JsonSuggestion> _suggestions = const <JsonSuggestion>[];
  int _active = 0;
  int _caretLine = 1;
  int _caretColumn = 0;
  double _scroll = 0;
  double _height = 0;

  /// The text this editor last reported or showed; a different incoming
  /// text (discard, a visual edit in Split) is written into the element.
  late String _shown = component.text;

  String? _outlineText;
  JsonOutline? _outline;

  JsonOutline get _scan {
    if (_outlineText != component.text || _outline == null) {
      _outline = scanJson(component.text);
      _outlineText = component.text;
    }
    return _outline!;
  }

  web.HTMLTextAreaElement? get _area {
    final web.Element? element = web.document.getElementById(_id);
    return element != null && element.isA<web.HTMLTextAreaElement>() ? element as web.HTMLTextAreaElement : null;
  }

  @override
  void didUpdateWidget(JsonCodeEditor oldComponent) {
    super.didUpdateWidget(oldComponent);
    if (component.text != _shown) {
      _shown = component.text;
      scheduleMicrotask(() {
        final web.HTMLTextAreaElement? area = _area;
        if (area != null && area.value != component.text) area.value = component.text;
      });
    }
  }

  double get _lineHeight {
    final web.HTMLTextAreaElement? area = _area;
    if (area == null) return 18;
    final double? parsed = double.tryParse(web.window.getComputedStyle(area).lineHeight.replaceAll('px', ''));
    return parsed ?? 18;
  }

  void _track(web.HTMLTextAreaElement area) {
    final String before = area.value.substring(0, area.selectionStart.clamp(0, area.value.length));
    _caretLine = '\n'.allMatches(before).length + 1;
    _caretColumn = before.length - (before.lastIndexOf('\n') + 1);
    _scroll = area.scrollTop;
    _height = area.clientHeight.toDouble();
  }

  /// The completion list's top, kept inside the text area when the caret
  /// line is scrolled out of view.
  double get _listTop {
    final double line = _lineHeight;
    final double top = _caretLine * line - _scroll;
    final double bottom = _height > 3 * line ? _height - 2 * line : top;
    return top.clamp(line, bottom < line ? line : bottom);
  }

  void _suggest(web.HTMLTextAreaElement area, {bool forced = false}) {
    final SchemaView? schema = component.schema;
    if (schema == null) return;
    final int caret = area.selectionStart;
    final String text = area.value;
    final JsonOutline outline = scanJson(text, caret: caret);
    final CaretContext? context = outline.caret;
    final bool wanted = forced || (context != null && (context.prefix.isNotEmpty || (caret > 0 && text[caret - 1] == '"')));
    final List<JsonSuggestion> next = wanted ? suggestJson(text, caret, schema, catalog: component.catalog, hints: component.hints) : const <JsonSuggestion>[];
    _track(area);
    setState(() {
      _suggestions = next;
      _active = 0;
    });
  }

  void _close() {
    if (_suggestions.isNotEmpty) setState(() => _suggestions = const <JsonSuggestion>[]);
  }

  void _report(String text) {
    _shown = text;
    component.onChanged(text);
  }

  void _apply(JsonSuggestion suggestion) {
    final web.HTMLTextAreaElement? area = _area;
    if (area == null) return;
    final (String text, int caret) = suggestion.applyTo(area.value);
    area.value = text;
    area.setSelectionRange(caret, caret);
    area.focus();
    _report(text);
    if (suggestion.isKey) {
      _suggest(area, forced: true);
    } else {
      _close();
    }
  }

  void _onKey(web.Event event) {
    if (!event.isA<web.KeyboardEvent>()) return;
    final web.KeyboardEvent key = event as web.KeyboardEvent;
    final web.HTMLTextAreaElement? area = _area;
    if (area == null) return;
    if (key.key == ' ' && (key.ctrlKey || key.metaKey)) {
      key.preventDefault();
      _suggest(area, forced: true);
      return;
    }
    if (_suggestions.isEmpty) {
      if (key.key == 'Tab' && !key.shiftKey) {
        // Two spaces, as the files are written, instead of leaving the field.
        key.preventDefault();
        final int start = area.selectionStart, end = area.selectionEnd;
        area.setRangeText('  ', start, end, 'end');
        _report(area.value);
      }
      return;
    }
    switch (key.key) {
      case 'ArrowDown':
        key.preventDefault();
        setState(() => _active = (_active + 1) % _suggestions.length);
      case 'ArrowUp':
        key.preventDefault();
        setState(() => _active = (_active - 1 + _suggestions.length) % _suggestions.length);
      case 'Enter' || 'Tab':
        key.preventDefault();
        _apply(_suggestions[_active]);
      case 'Escape':
        key.preventDefault();
        _close();
    }
  }

  /// Puts the caret at the start of 1-based [line] and scrolls it into view.
  void _jump(int line) {
    final web.HTMLTextAreaElement? area = _area;
    if (area == null) return;
    final JsonOutline outline = scanJson(area.value);
    final int offset = outline.lineStart(line);
    area.focus();
    area.setSelectionRange(offset, offset);
    area.scrollTop = ((line - 3) * _lineHeight).clamp(0, double.infinity).toDouble();
    _syncGutter(area);
    _track(area);
    setState(() => _suggestions = const <JsonSuggestion>[]);
  }

  void _syncGutter(web.HTMLTextAreaElement area) {
    final web.Element? gutter = web.document.getElementById('$_id-gutter');
    if (gutter != null) gutter.scrollTop = area.scrollTop;
  }

  @override
  Widget build(BuildContext context) {
    final JsonOutline outline = _scan;
    final List<LocatedDiagnostic> located = locateDiagnostics(outline, component.diagnostics, component.serverDiagnostics);
    final Map<int, List<LocatedDiagnostic>> byLine = <int, List<LocatedDiagnostic>>{};
    for (final LocatedDiagnostic entry in located) {
      if (entry.line != null) byLine.putIfAbsent(entry.line!, () => <LocatedDiagnostic>[]).add(entry);
    }
    final bool open = _suggestions.isNotEmpty;
    return dom.div(classes: 'animus-code-editor', <Widget>[
      dom.div(classes: 'animus-code-frame', <Widget>[
        dom.div(
          id: '$_id-gutter',
          classes: 'animus-code-gutter',
          attributes: const <String, String>{'aria-hidden': 'true'},
          <Widget>[
            for (int line = 1; line <= outline.lineCount; line++)
              if (byLine[line] case final List<LocatedDiagnostic> marks)
                dom.div(
                  classes: 'animus-code-line has-mark is-${_worst(marks).name}${marks.any((LocatedDiagnostic m) => m.server) ? ' is-server' : ''}',
                  attributes: <String, String>{'title': marks.map((LocatedDiagnostic m) => '${m.server ? 'Server: ' : ''}${m.diagnostic.message}').join('\n'), 'data-line': '$line'},
                  events: <String, void Function(web.Event)>{'mousedown': (web.Event event) {
                    event.preventDefault();
                    _jump(line);
                  }},
                  <Widget>[Component.text('$line')],
                )
              else
                dom.div(classes: 'animus-code-line', <Widget>[Component.text('$line')]),
          ],
        ),
        dom.div(classes: 'animus-code-input', <Widget>[
          dom.textarea(
            id: _id,
            classes: 'animus-code-area',
            rows: 28,
            attributes: <String, String>{
              'spellcheck': 'false',
              'autocomplete': 'off',
              'wrap': 'off',
              'aria-label': 'Document JSON',
              'aria-autocomplete': 'list',
              if (open) 'aria-expanded': 'true',
              if (open) 'aria-controls': '$_id-list',
            },
            events: <String, void Function(web.Event)>{
              'input': (web.Event event) {
                final web.HTMLTextAreaElement area = event.target! as web.HTMLTextAreaElement;
                _report(area.value);
                _suggest(area);
              },
              'keydown': _onKey,
              'click': (web.Event event) {
                final web.HTMLTextAreaElement area = event.target! as web.HTMLTextAreaElement;
                _track(area);
                _close();
              },
              'scroll': (web.Event event) {
                final web.HTMLTextAreaElement area = event.target! as web.HTMLTextAreaElement;
                _syncGutter(area);
                if (open) setState(() => _scroll = area.scrollTop);
              },
              'blur': (web.Event _) {
                // A completion click lands before blur hides the list.
                Future<void>.delayed(const Duration(milliseconds: 150), () {
                  if (mounted) _close();
                });
              },
            },
            <Widget>[Component.text(_shown)],
          ),
          if (open)
            dom.ul(
              id: '$_id-list',
              classes: 'animus-gui-completions animus-code-completions',
              attributes: const <String, String>{'role': 'listbox'},
              styles: dom.Styles(raw: <String, String>{
                'top': 'calc(${_listTop}px + 0.5rem)',
                'left': 'max(0px, min(calc(${_caretColumn}ch + 0.75rem), calc(100% - 22rem)))',
              }),
              <Widget>[
                for (int index = 0; index < _suggestions.length; index++)
                  dom.li(
                    classes: index == _active ? 'is-active' : null,
                    attributes: <String, String>{'role': 'option', 'aria-selected': '${index == _active}'},
                    events: <String, void Function(web.Event)>{
                      'mousedown': (web.Event event) {
                        event.preventDefault();
                        _apply(_suggestions[index]);
                      },
                    },
                    <Widget>[
                      dom.code(<Widget>[Component.text(_suggestions[index].label)]),
                      dom.span(<Widget>[Component.text(_suggestions[index].detail)]),
                    ],
                  ),
              ],
            ),
        ]),
      ]),
      dom.div(classes: 'animus-code-status animus-muted', <Widget>[
        Component.text('Line $_caretLine, column ${_caretColumn + 1}. Ctrl+Space completes keys and values.'),
      ]),
      if (located.isNotEmpty)
        dom.ul(classes: 'animus-code-problems', attributes: const <String, String>{'aria-label': 'Diagnostics by line'}, <Widget>[
          for (final LocatedDiagnostic entry in located.take(100))
            dom.li(classes: 'is-${entry.diagnostic.severity.name}', <Widget>[
              Button.ghost(
                label: entry.line == null ? 'No line' : 'Line ${entry.line}',
                size: ButtonSize.small,
                disabled: entry.line == null,
                onPressed: () => _jump(entry.line!),
              ),
              if (entry.server) const dom.span(classes: 'animus-tag is-server', <Widget>[Component.text('server')]),
              dom.code(<Widget>[Component.text(entry.diagnostic.path)]),
              dom.span(<Widget>[Component.text(entry.diagnostic.message)]),
            ]),
        ]),
    ]);
  }

  DiagnosticSeverity _worst(List<LocatedDiagnostic> marks) {
    if (marks.any((LocatedDiagnostic m) => m.diagnostic.severity == DiagnosticSeverity.error)) return DiagnosticSeverity.error;
    if (marks.any((LocatedDiagnostic m) => m.diagnostic.severity == DiagnosticSeverity.warning)) return DiagnosticSeverity.warning;
    return DiagnosticSeverity.info;
  }
}
