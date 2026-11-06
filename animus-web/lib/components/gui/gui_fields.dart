/// Inspector fields for the GUI editors: labelled rows, text fields with
/// expression checking and completion, numbers, choices, switches and line
/// lists. Every field reports a whole new value; the editors write it into
/// the document.
library;

import 'dart:js_interop';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;
import 'package:web/web.dart' as web;

import '../../logic/gui/gui_expression.dart';
import '../../logic/gui/gui_json.dart';
import '../../logic/gui/gui_text.dart';
import '../../mc/npc/mc_text.dart';
import '../../model/animus_document.dart';

/// What a text field holds, which decides its checks and completion.
enum GuiTextMode {
  plain,

  /// `&` codes, MiniMessage and `{{ expr }}` holes.
  template,

  /// One whole expression.
  expression,
}

/// How an editor reports a whole new document value; edits sharing
/// [coalesce] close together undo as one step.
typedef JsonEdit = void Function(Object? json, {String? coalesce});

int _nextFieldId = 0;

/// A labelled row: the control, an optional help line and the diagnostics
/// the document has at [path].
class GuiRow extends StatelessWidget {
  const GuiRow({required this.label, required this.child, this.help, this.problems = const <Diagnostic>[], super.key});

  final String label;
  final Widget child;
  final String? help;
  final List<Diagnostic> problems;

  @override
  Widget build(BuildContext context) {
    return dom.div(classes: 'animus-gui-row${problems.any((Diagnostic d) => d.severity == DiagnosticSeverity.error) ? ' has-error' : ''}', <Widget>[
      dom.span(classes: 'animus-gui-label', <Widget>[Component.text(label)]),
      dom.div(classes: 'animus-gui-control', <Widget>[
        child,
        if (help != null && help!.isNotEmpty) dom.span(classes: 'animus-gui-help', <Widget>[Component.text(help!)]),
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

/// Diagnostics at exactly [path], or inside it when [nested].
List<Diagnostic> diagnosticsAt(List<Diagnostic> all, String path, {bool nested = false}) => <Diagnostic>[
      for (final Diagnostic diagnostic in all)
        if (diagnostic.path == path || (nested && (diagnostic.path.startsWith('$path.') || diagnostic.path.startsWith('$path['))))
          diagnostic,
    ];

/// A text field. Template and expression fields show their problems as you
/// type and offer completions for names and functions.
class GuiTextInput extends StatefulWidget {
  const GuiTextInput({
    required this.value,
    required this.onChanged,
    this.mode = GuiTextMode.plain,
    this.names = ExpressionNames.standard,
    this.placeholder,
    this.multiline = false,
    this.label,
    super.key,
  });

  final String value;
  final void Function(String value) onChanged;
  final GuiTextMode mode;
  final ExpressionNames names;
  final String? placeholder;
  final bool multiline;

  /// The accessible name.
  final String? label;

  @override
  State<GuiTextInput> createState() => _GuiTextInputState();
}

class _GuiTextInputState extends State<GuiTextInput> {
  final String _id = 'animus-field-${_nextFieldId++}';
  List<ExpressionCompletion> _completions = const <ExpressionCompletion>[];
  int _active = 0;
  bool _focused = false;

  /// What the user typed while focused. Saves land after later keystrokes,
  /// so the field shows its own text until blur instead of a stale value.
  String? _typed;

  String get _text => _typed ?? component.value;

  web.HTMLElement? get _element => web.document.getElementById(_id) as web.HTMLElement?;

  (String, int) _read(web.Event event) {
    final web.EventTarget? target = event.target;
    if (target != null && target.isA<web.HTMLTextAreaElement>()) {
      final web.HTMLTextAreaElement area = target as web.HTMLTextAreaElement;
      return (area.value, area.selectionStart);
    }
    if (target != null && target.isA<web.HTMLInputElement>()) {
      final web.HTMLInputElement input = target as web.HTMLInputElement;
      return (input.value, input.selectionStart ?? input.value.length);
    }
    return (_text, _text.length);
  }

  void _suggest(String text, int caret) {
    if (component.mode == GuiTextMode.plain) return;
    final List<ExpressionCompletion> next = completeExpression(
      text,
      caret,
      template: component.mode == GuiTextMode.template,
      names: component.names,
    );
    setState(() {
      _completions = next;
      _active = 0;
    });
  }

  void _onInput(web.Event event) {
    final (String text, int caret) = _read(event);
    _typed = text;
    component.onChanged(text);
    _suggest(text, caret);
  }

  void _apply(ExpressionCompletion completion) {
    final web.HTMLElement? element = _element;
    String current = _text;
    if (element != null && component.multiline) {
      current = (element as web.HTMLTextAreaElement).value;
    } else if (element != null) {
      current = (element as web.HTMLInputElement).value;
    }
    final (String text, int caret) = completion.applyTo(current);
    if (element != null && component.multiline) {
      final web.HTMLTextAreaElement area = element as web.HTMLTextAreaElement;
      area.value = text;
      area.setSelectionRange(caret, caret);
      area.focus();
    } else if (element != null) {
      final web.HTMLInputElement input = element as web.HTMLInputElement;
      input.value = text;
      input.setSelectionRange(caret, caret);
      input.focus();
    }
    _typed = text;
    component.onChanged(text);
    _suggest(text, caret);
  }

  void _onKey(web.Event event) {
    if (_completions.isEmpty || !event.isA<web.KeyboardEvent>()) return;
    switch ((event as web.KeyboardEvent).key) {
      case 'ArrowDown':
        event.preventDefault();
        setState(() => _active = (_active + 1) % _completions.length);
      case 'ArrowUp':
        event.preventDefault();
        setState(() => _active = (_active - 1 + _completions.length) % _completions.length);
      case 'Enter' || 'Tab':
        event.preventDefault();
        _apply(_completions[_active]);
      case 'Escape':
        setState(() => _completions = const <ExpressionCompletion>[]);
    }
  }

  String? get _problem {
    final String value = _text;
    if (value.isEmpty) return null;
    return switch (component.mode) {
      GuiTextMode.plain => null,
      GuiTextMode.template => checkTemplate(value, names: component.names).firstOrNull,
      GuiTextMode.expression => checkExpression(value, names: component.names),
    };
  }

  @override
  Widget build(BuildContext context) {
    final String? problem = _problem;
    final bool open = _focused && _completions.isNotEmpty;
    final Map<String, String> attributes = <String, String>{
      'autocomplete': 'off',
      'spellcheck': 'false',
      if (component.placeholder != null) 'placeholder': component.placeholder!,
      if (component.label != null) 'aria-label': component.label!,
      if (component.mode != GuiTextMode.plain) 'aria-autocomplete': 'list',
      if (open) 'aria-expanded': 'true',
      if (problem != null) 'aria-invalid': 'true',
    };
    final Map<String, void Function(web.Event)> events = <String, void Function(web.Event)>{
      'input': _onInput,
      'keydown': _onKey,
      'focus': (web.Event event) {
        _focused = true;
        final (String text, int caret) = _read(event);
        _suggest(text, caret);
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
    };
    final String classes = 'animus-gui-input${component.mode == GuiTextMode.plain ? '' : ' is-code'}${problem != null ? ' is-invalid' : ''}';
    return dom.div(classes: 'animus-gui-text', <Widget>[
      if (component.multiline)
        dom.textarea(
          id: _id,
          classes: classes,
          rows: 3,
          attributes: attributes,
          events: events,
          <Widget>[Component.text(_text)],
        )
      else
        dom.input(id: _id, classes: classes, value: _text, attributes: attributes, events: events),
      if (component.mode == GuiTextMode.template && (_text.contains('&') || _text.contains('<') || _text.contains('{{')))
        dom.span(classes: 'animus-gui-sample', <Widget>[GuiStyledText(_text)]),
      if (problem != null) dom.span(classes: 'animus-gui-problem is-error', <Widget>[Component.text(problem)]),
      if (open)
        dom.ul(classes: 'animus-gui-completions', attributes: const <String, String>{'role': 'listbox'}, <Widget>[
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

/// A number field; empty clears the value.
class GuiNumberInput extends StatefulWidget {
  const GuiNumberInput({required this.value, required this.onChanged, this.integer = true, this.label, this.placeholder, super.key});

  final num? value;
  final void Function(num? value) onChanged;
  final bool integer;
  final String? label;
  final String? placeholder;

  @override
  State<GuiNumberInput> createState() => _GuiNumberInputState();
}

class _GuiNumberInputState extends State<GuiNumberInput> {
  /// The raw text while focused, kept over slower saves.
  String? _typed;

  @override
  Widget build(BuildContext context) {
    final num? shown = component.value;
    return dom.input(
      classes: 'animus-gui-input is-number',
      type: dom.InputType.number,
      value: _typed ?? (shown == null ? '' : (shown == shown.roundToDouble() ? shown.toInt().toString() : shown.toString())),
      attributes: <String, String>{
        if (component.integer) 'step': '1' else 'step': 'any',
        'aria-label': ?component.label,
        'placeholder': ?component.placeholder,
      },
      events: <String, void Function(web.Event)>{
        'input': (web.Event event) {
          final String raw = (event.target! as web.HTMLInputElement).value.trim();
          _typed = raw;
          if (raw.isEmpty) {
            component.onChanged(null);
            return;
          }
          final num? parsed = component.integer ? int.tryParse(raw) : num.tryParse(raw);
          if (parsed != null) component.onChanged(parsed);
        },
        'blur': (web.Event _) {
          if (mounted) setState(() => _typed = null);
        },
      },
    );
  }
}

/// A choice from [options]. With [allowNone], an empty first entry clears it.
class GuiSelect extends StatelessWidget {
  const GuiSelect({
    required this.value,
    required this.options,
    required this.onChanged,
    this.allowNone = false,
    this.noneLabel = 'default',
    this.labels = const <String, String>{},
    this.id,
    this.label,
    super.key,
  });

  final String? value;
  final List<String> options;
  final void Function(String? value) onChanged;
  final bool allowNone;
  final String noneLabel;
  final Map<String, String> labels;

  /// The element id, so a [GuiRow] label can point at it with `for`.
  final String? id;

  /// A visible label rendered by the select itself; use it where no row label
  /// names the control.
  final String? label;

  @override
  Widget build(BuildContext context) {
    final List<String> all = <String>[...options, if (value != null && !options.contains(value)) value!];
    return ArcaneSelect(
      size: ComponentSize.sm,
      id: id,
      label: label,
      value: value ?? '',
      options: <ArcaneSelectOption>[
        if (allowNone || value == null) ArcaneSelectOption(label: noneLabel, value: ''),
        for (final String option in all) ArcaneSelectOption(label: labels[option] ?? option, value: option),
      ],
      onChanged: (String next) => onChanged(next.isEmpty ? null : next),
    );
  }
}

/// A boolean with three states when [tristate]: unset, on, off.
class GuiSwitch extends StatelessWidget {
  const GuiSwitch({required this.value, required this.onChanged, required this.label, super.key});

  final bool value;
  final void Function(bool value) onChanged;
  final String label;

  @override
  Widget build(BuildContext context) => ArcaneCheckbox(checked: value, label: label, size: ComponentSize.sm, onChanged: onChanged);
}

/// An editable list of text lines (lore, hologram text, mask rows).
class GuiLinesEditor extends StatelessWidget {
  const GuiLinesEditor({
    required this.lines,
    required this.onChanged,
    this.mode = GuiTextMode.template,
    this.names = ExpressionNames.standard,
    this.addLabel = 'Add line',
    super.key,
  });

  final List<String> lines;
  final void Function(List<String> lines) onChanged;
  final GuiTextMode mode;
  final ExpressionNames names;
  final String addLabel;

  @override
  Widget build(BuildContext context) {
    return dom.div(classes: 'animus-gui-lines', <Widget>[
      for (int index = 0; index < lines.length; index++)
        dom.div(classes: 'animus-gui-line', <Widget>[
          GuiTextInput(
            key: ValueKey<String>('line-$index'),
            value: lines[index],
            mode: mode,
            names: names,
            label: 'Line ${index + 1}',
            onChanged: (String value) => onChanged(<String>[...lines]..[index] = value),
          ),
          GuiIconButton(
            icon: ArcaneIcon.arrowUp(size: IconSize.sm),
            label: 'Move line ${index + 1} up',
            disabled: index == 0,
            onPressed: () => onChanged(<String>[for (final Object? line in moveItem(lines, index, index - 1)) line! as String]),
          ),
          GuiIconButton(
            icon: ArcaneIcon.x(size: IconSize.sm),
            label: 'Remove line ${index + 1}',
            onPressed: () => onChanged(<String>[...lines]..removeAt(index)),
          ),
        ]),
      Button.ghost(label: addLabel, size: ButtonSize.small, onPressed: () => onChanged(<String>[...lines, ''])),
    ]);
  }
}

class GuiIconButton extends StatelessWidget {
  const GuiIconButton({required this.icon, required this.label, required this.onPressed, this.disabled = false, super.key});

  final ArcaneGlyph icon;
  final String label;
  final void Function() onPressed;
  final bool disabled;

  @override
  Widget build(BuildContext context) => Button.ghost(
        icon: icon,
        size: ButtonSize.small,
        disabled: disabled,
        attributes: <String, String>{'aria-label': label, 'title': label},
        onPressed: onPressed,
      );
}

/// Formatted Minecraft text with sample values in its holes.
class GuiStyledText extends StatelessWidget {
  const GuiStyledText(this.text, {this.defaultColor = mcDefaultTextColor, super.key});

  final String text;
  final int defaultColor;

  @override
  Widget build(BuildContext context) => dom.span(classes: 'animus-mc-styled', <Widget>[
        for (final McTextRun run in guiTextRuns(text, defaultColor: defaultColor))
          dom.span(
            styles: dom.Styles(raw: <String, String>{
              'color': run.cssColor,
              if (run.bold) 'font-weight': '700',
              if (run.italic) 'font-style': 'italic',
              if (run.underlined || run.strikethrough)
                'text-decoration': '${run.underlined ? 'underline ' : ''}${run.strikethrough ? 'line-through' : ''}'.trim(),
            }),
            <Widget>[Component.text(run.text)],
          ),
      ]);
}

/// A collapsible group of fields.
class GuiSection extends StatelessWidget {
  const GuiSection({required this.title, required this.children, this.open = true, this.count, super.key});

  final String title;
  final List<Widget> children;
  final bool open;
  final int? count;

  @override
  Widget build(BuildContext context) => dom.details(
        classes: 'animus-gui-section',
        attributes: <String, String>{if (open) 'open': ''},
        <Widget>[
          dom.summary(<Widget>[
            Component.text(title),
            if (count != null) dom.span(classes: 'animus-count', <Widget>[Component.text('$count')]),
          ]),
          dom.div(classes: 'animus-gui-section-body', children),
        ],
      );
}

/// Reads a string list from JSON, dropping non-strings.
List<String> stringList(Object? value) => <String>[for (final Object? item in asList(value)) if (item is String) item];
