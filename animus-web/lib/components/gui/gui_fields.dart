/// Inspector fields for the GUI editors: labelled rows, text fields with
/// expression checking and completion, numbers, choices, switches and line
/// lists. Every field reports a whole new value; the editors write it into
/// the document.
library;

import 'dart:convert';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;
import 'package:web/web.dart' as web;

import '../../config/docs_links.dart';
import '../../logic/gui/gui_expression.dart';
import '../../logic/gui/gui_json.dart';
import '../../logic/gui/gui_text.dart';
import '../../mc/npc/mc_text.dart';
import '../../logic/select/select_model.dart';
import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../../logic/help_text.dart';
import '../../logic/suggest/field_profile.dart';
import '../info_tip.dart';
import '../select/animus_select.dart';
import '../suggest/autocomplete_input.dart';

/// What a text field holds, which decides its checks and completion.
enum GuiTextMode {
  plain,

  /// `&` codes, MiniMessage and `{{ expr }}` holes.
  template,

  /// One whole expression.
  expression,
}

/// How an editor reports an edit, run on the latest document (usually
/// [writeAt]); edits sharing [coalesce] close together undo as one step.
typedef JsonEdit = void Function(JsonUpdate update, {String? coalesce});

int _nextFieldId = 0;

/// Whether the system asks for reduced motion; animated previews start
/// paused when it does.
bool prefersReducedMotion() => web.window.matchMedia('(prefers-reduced-motion: reduce)').matches;

/// A fresh element id for a control and the label that names it.
String guiFieldId() => 'animus-field-${_nextFieldId++}';

/// A stable element id for the control at a document [path], so a [GuiRow]
/// label can point at it with `for`.
String guiPathId(String path) => 'animus-at-${path.replaceAll(RegExp(r'[^A-Za-z0-9_-]+'), '-')}';

/// A labelled row: the control, an optional help line and the diagnostics
/// the document has at [path]. With [forId] the label is a `<label for>`
/// naming the control with that id.
class GuiRow extends StatelessWidget {
  const GuiRow({required this.label, required this.child, this.help, this.tip, this.problems = const <Diagnostic>[], this.forId, super.key});

  final String label;
  final Widget child;
  final String? help;

  /// Tip text on the label; with it, [help] always stays inline.
  final String? tip;
  final List<Diagnostic> problems;
  final String? forId;

  @override
  Widget build(BuildContext context) {
    final ({String? hint, String? tip}) split = tip == null ? splitHelp(help) : (hint: help == null || help!.isEmpty ? null : help, tip: tip);
    return dom.div(classes: 'animus-gui-row${problems.any((Diagnostic d) => d.severity == DiagnosticSeverity.error) ? ' has-error' : ''}', <Widget>[
      forId != null
          ? dom.label(htmlFor: forId, classes: 'animus-gui-label', <Widget>[withTip(Component.text(label), split.tip)])
          : dom.span(classes: 'animus-gui-label', <Widget>[withTip(Component.text(label), split.tip)]),
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

/// Diagnostics at exactly [path], or inside it when [nested].
List<Diagnostic> diagnosticsAt(List<Diagnostic> all, String path, {bool nested = false}) => <Diagnostic>[
      for (final Diagnostic diagnostic in all)
        if (diagnostic.path == path || (nested && (diagnostic.path.startsWith('$path.') || diagnostic.path.startsWith('$path['))))
          diagnostic,
    ];

/// A text field. Template and expression fields show their problems as you
/// type; every field with a profile completes through [AutocompleteInput].
class GuiTextInput extends StatefulWidget {
  const GuiTextInput({
    required this.value,
    required this.onChanged,
    this.mode = GuiTextMode.plain,
    this.names = ExpressionNames.standard,
    this.profile,
    this.placeholder,
    this.multiline = false,
    this.label,
    this.id,
    super.key,
  });

  /// The element id; a [GuiRow] with the same `forId` labels it.
  final String? id;

  final String value;
  final void Function(String value) onChanged;
  final GuiTextMode mode;
  final ExpressionNames names;

  /// What the field completes; by default the [mode]'s (GUI text or
  /// expression, nothing for plain text).
  final FieldProfile? profile;
  final String? placeholder;
  final bool multiline;

  /// The accessible name.
  final String? label;

  @override
  State<GuiTextInput> createState() => _GuiTextInputState();
}

class _GuiTextInputState extends State<GuiTextInput> {
  late final String _id = component.id ?? guiFieldId();

  /// The latest typed text, ahead of the saved value.
  String? _typed;

  String get _text => _typed ?? component.value;

  @override
  void didUpdateWidget(GuiTextInput oldComponent) {
    super.didUpdateWidget(oldComponent);
    if (component.value != oldComponent.value && component.value != _typed) _typed = null;
  }

  FieldProfile get _profile =>
      component.profile ??
      switch (component.mode) {
        GuiTextMode.plain => FieldProfile.none,
        GuiTextMode.template => FieldProfile.guiText,
        GuiTextMode.expression => FieldProfile.guiExpression,
      };

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
    final String text = _text;
    return dom.div(classes: 'animus-gui-text', <Widget>[
      AutocompleteInput(
        id: _id,
        value: component.value,
        profile: _profile,
        gui: component.names,
        label: component.label,
        placeholder: component.placeholder,
        multiline: component.multiline,
        invalid: problem != null,
        describedBy: problem != null ? '$_id-problem' : null,
        onChanged: (String next) {
          setState(() => _typed = next);
          component.onChanged(next);
        },
      ),
      if (component.mode == GuiTextMode.template && (text.contains('&') || text.contains('<') || text.contains('{{')))
        dom.span(classes: 'animus-gui-sample', <Widget>[GuiStyledText(text)]),
      if (problem != null) dom.span(id: '$_id-problem', classes: 'animus-gui-problem is-error', <Widget>[Component.text(problem)]),
    ]);
  }
}

/// A number field; empty clears the value.
class GuiNumberInput extends StatefulWidget {
  const GuiNumberInput({required this.value, required this.onChanged, this.integer = true, this.label, this.placeholder, this.id, super.key});

  /// The element id; a [GuiRow] with the same `forId` labels it.
  final String? id;
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
      id: component.id,
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

/// A choice from [options]. With [allowNone], a first entry clears it.
/// [references] makes it a document picker with "+ New" and "Manage" rows.
class GuiSelect extends StatelessWidget {
  const GuiSelect({
    required this.value,
    required this.options,
    required this.onChanged,
    this.allowNone = false,
    this.noneLabel = 'default',
    this.labels = const <String, String>{},
    this.groups = const <String, String>{},
    this.skinFaces = const <String, String>{},
    this.disabled = const <String>{},
    this.inactive = false,
    this.references,
    this.id,
    this.label,
    this.hiddenLabel,
    super.key,
  });

  final String? value;
  final List<String> options;
  final void Function(String? value) onChanged;
  final bool allowNone;
  final String noneLabel;
  final Map<String, String> labels;

  /// The group heading of each option that has one.
  final Map<String, String> groups;

  /// The skin texture whose face shows beside an option.
  final Map<String, String> skinFaces;

  /// Options shown but not pickable.
  final Set<String> disabled;

  /// The whole control is disabled.
  final bool inactive;
  final DocumentKind? references;

  /// The element id, so a [GuiRow] label can point at it with `for`.
  final String? id;

  /// A visible label rendered by the select itself; use it where no row label
  /// names the control.
  final String? label;

  /// A label read by assistive technology only, for a select whose purpose
  /// the surrounding layout already shows.
  final String? hiddenLabel;

  @override
  Widget build(BuildContext context) {
    final List<String> all = <String>[...options, if (value != null && value!.isNotEmpty && !options.contains(value)) value!];
    return AnimusSelect(
      id: id,
      label: label,
      hiddenLabel: hiddenLabel,
      value: value ?? '',
      placeholder: noneLabel,
      references: references,
      disabled: inactive,
      options: <SelectOption>[
        if (allowNone || value == null) SelectOption(label: noneLabel, value: ''),
        for (final String option in all)
          if (option.isNotEmpty)
            SelectOption(
              label: labels[option] ?? option,
              value: option,
              group: groups[option],
              skinFace: skinFaces[option],
              disabled: disabled.contains(option),
              keywords: labels[option] == null ? const <String>[] : <String>[option],
            ),
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
            icon: ArcaneIcon.arrowDown(size: IconSize.sm),
            label: 'Move line ${index + 1} down',
            disabled: index == lines.length - 1,
            onPressed: () => onChanged(<String>[for (final Object? line in moveItem(lines, index, index + 1)) line! as String]),
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
class GuiSection extends StatefulWidget {
  const GuiSection({required this.title, required this.children, this.open = true, this.count, this.tip, this.docs, super.key});

  final String title;
  final List<Widget> children;

  /// Whether the section starts open.
  final bool open;
  final int? count;

  /// Tip text on the title, linking to [docs] or the surrounding page.
  final String? tip;
  final DocLink? docs;

  @override
  State<GuiSection> createState() => _GuiSectionState();
}

class _GuiSectionState extends State<GuiSection> {
  /// Kept from the user's own open and close, so an edit that builds the
  /// section again does not fold it back to [GuiSection.open].
  late bool _open = component.open;

  @override
  Widget build(BuildContext context) => dom.details(
        classes: 'animus-gui-section',
        attributes: <String, String>{if (_open) 'open': ''},
        events: <String, void Function(web.Event)>{
          'toggle': (web.Event event) {
            final bool open = (event.currentTarget! as web.HTMLDetailsElement).open;
            if (open != _open) setState(() => _open = open);
          },
        },
        <Widget>[
          dom.summary(<Widget>[
            withTip(Component.text(component.title), component.tip, docs: component.docs),
            if (component.count != null) dom.span(classes: 'animus-count', <Widget>[Component.text('${component.count}')]),
          ]),
          dom.div(classes: 'animus-gui-section-body', component.children),
        ],
      );
}

/// Reads a string list from JSON, dropping non-strings.
List<String> stringList(Object? value) => <String>[for (final Object? item in asList(value)) if (item is String) item];

/// Section tabs: a tablist whose selected tab has a raised neutral fill and
/// a pink indicator, so the pink fill stays with the primary action.
class GuiTabs extends StatelessWidget {
  const GuiTabs({required this.tabs, required this.selected, required this.onSelect, required this.label, this.disabled = const <int>{}, super.key});

  final List<String> tabs;
  final int selected;
  final void Function(int index) onSelect;
  final String label;
  final Set<int> disabled;

  @override
  Widget build(BuildContext context) => dom.div(
        // `.animus-tabs` takes the editor-wide selected-tab rule, so these
        // read the same as the document's view tabs.
        classes: 'animus-tabs animus-gui-tabs',
        attributes: <String, String>{'role': 'tablist', 'aria-label': label},
        <Widget>[
          // Native buttons, as in the view tabs: an arcane button's inline
          // style would hide the selected tab's pink border.
          for (int index = 0; index < tabs.length; index++)
            dom.button(
              classes: 'animus-tab',
              attributes: <String, String>{
                'type': 'button',
                'role': 'tab',
                'aria-selected': '${index == selected}',
                'data-tab': '$index',
                if (disabled.contains(index)) 'disabled': '',
              },
              events: <String, void Function(web.Event)>{'click': (web.Event _) => onSelect(index)},
              <Widget>[Component.text(tabs[index])],
            ),
        ],
      );
}

/// A number, or an expression that gives one: digits write a number, any
/// other text writes the expression string.
class GuiNumberOrExpression extends StatelessWidget {
  const GuiNumberOrExpression({required this.value, required this.onChanged, required this.label, this.names = ExpressionNames.standard, this.id, super.key});

  final Object? value;
  final void Function(Object? value) onChanged;
  final String label;
  final ExpressionNames names;
  final String? id;

  @override
  Widget build(BuildContext context) {
    final Object? shown = value;
    final String text = shown is num ? (shown == shown.roundToDouble() ? '${shown.toInt()}' : '$shown') : (shown is String ? shown : '');
    return GuiTextInput(
      id: id,
      value: text,
      mode: GuiTextMode.expression,
      names: names,
      label: label,
      onChanged: (String raw) {
        final String trimmed = raw.trim();
        if (trimmed.isEmpty) {
          onChanged(null);
          return;
        }
        onChanged(num.tryParse(trimmed) ?? trimmed);
      },
    );
  }
}

/// Earlier and later buttons for the item at [index] of a list of [length].
List<Widget> guiMoveButtons({required int index, required int length, required String noun, required void Function(int to) onMove}) => <Widget>[
      GuiIconButton(
        icon: ArcaneIcon.arrowUp(size: IconSize.sm),
        label: 'Move $noun earlier',
        disabled: index == 0,
        onPressed: () => onMove(index - 1),
      ),
      GuiIconButton(
        icon: ArcaneIcon.arrowDown(size: IconSize.sm),
        label: 'Move $noun later',
        disabled: index >= length - 1,
        onPressed: () => onMove(index + 1),
      ),
    ];

/// A JSON object typed as text. Nothing is written until the text parses
/// to an object; until then the field says what is wrong.
class GuiJsonObjectInput extends StatefulWidget {
  const GuiJsonObjectInput({required this.value, required this.onChanged, required this.label, this.id, this.multiline = false, super.key});

  final JsonMap? value;

  /// The parsed object, or null when the text is cleared.
  final void Function(JsonMap? value) onChanged;
  final String label;
  final String? id;
  final bool multiline;

  @override
  State<GuiJsonObjectInput> createState() => _GuiJsonObjectInputState();
}

class _GuiJsonObjectInputState extends State<GuiJsonObjectInput> {
  late final String _id = component.id ?? guiFieldId();
  String? _typed;
  String? _problem;

  String _encode(JsonMap? value) => value == null || value.isEmpty ? '' : (component.multiline ? prettyJson(value).trimRight() : jsonEncode(value));

  void _onText(String text) {
    _typed = text;
    if (text.trim().isEmpty) {
      setState(() => _problem = null);
      component.onChanged(null);
      return;
    }
    try {
      final Object? parsed = jsonDecode(text);
      if (parsed is Map<String, Object?>) {
        setState(() => _problem = null);
        component.onChanged(parsed);
      } else {
        setState(() => _problem = 'Must be a JSON object. Not saved yet.');
      }
    } on FormatException catch (error) {
      setState(() => _problem = 'Invalid JSON (${error.message}). Not saved yet.');
    }
  }

  @override
  Widget build(BuildContext context) {
    return dom.div(classes: 'animus-gui-text', <Widget>[
      GuiTextInput(
        id: _id,
        value: _typed ?? _encode(component.value),
        multiline: component.multiline,
        label: component.label,
        placeholder: '{"name": "value"}',
        onChanged: _onText,
      ),
      if (_problem != null)
        dom.span(classes: 'animus-gui-problem is-error', attributes: const <String, String>{'role': 'alert'}, <Widget>[Component.text(_problem!)]),
    ]);
  }
}
