/// Shared pieces of the GUI editors: field ids and problems by document
/// path, text modes, line lists, formatted game text and notes.
/// Every field reports a whole new value; the editors write it into the
/// document.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;
import 'package:web/web.dart' as web;

import '../../logic/gui/gui_expression.dart';
import '../../logic/gui/gui_json.dart';
import '../../logic/gui/gui_text.dart';
import '../../mc/npc/mc_text.dart';
import '../../model/animus_document.dart';
import '../../logic/suggest/field_profile.dart';
import '../suggest/autocomplete_input.dart';
import '../../ui/alert.dart';
import '../../ui/density.dart';
import '../../ui/icon_button.dart';
import 'gui_stack.dart';
import '../../ui/stack.dart';

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

/// Whether the system asks for reduced motion; animated previews start
/// paused when it does.
bool prefersReducedMotion() => web.window.matchMedia('(prefers-reduced-motion: reduce)').matches;

/// A stable element id for the control at a document [path], so a field's
/// label can point at it with `for`.
String guiPathId(String path) => 'animus-at-${path.replaceAll(RegExp(r'[^A-Za-z0-9_-]+'), '-')}';

/// Diagnostics at exactly [path], or inside it when [nested].
List<Diagnostic> diagnosticsAt(List<Diagnostic> all, String path, {bool nested = false}) => <Diagnostic>[
      for (final Diagnostic diagnostic in all)
        if (diagnostic.path == path || (nested && (diagnostic.path.startsWith('$path.') || diagnostic.path.startsWith('$path['))))
          diagnostic,
    ];

/// The completion profile, and with it the whole-text check, of a mode.
FieldProfile guiTextProfile(GuiTextMode mode) => switch (mode) {
      GuiTextMode.plain => FieldProfile.none,
      GuiTextMode.template => FieldProfile.guiText,
      GuiTextMode.expression => FieldProfile.guiExpression,
    };

/// The default inventory title colour (`0x404040`).
const int mcInventoryTitleColor = 0x404040;

bool _formatted(String text) => text.contains('&') || text.contains('<') || text.contains('{{');

/// A GUI template's formatted sample, when it has codes, tags or holes.
Widget? templateSample(String text) => _formatted(text) ? GuiStyledText(text) : null;

/// A window title's sample, drawn as the game draws it: dark grey on the
/// window's grey.
Widget? windowTitleSample(String text) =>
    _formatted(text) ? dom.span(classes: 'animus-gui-window-title', <Widget>[GuiStyledText(text, defaultColor: mcInventoryTitleColor)]) : null;

/// An item name's sample, italic until a colour code as the game draws it.
Widget? itemNameSample(String text) => _formatted(text) ? GuiStyledText(text, item: true) : null;

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
    return stack(<Widget>[
      for (int index = 0; index < lines.length; index++)
        fieldLine(<Widget>[
          AutocompleteInput(
            key: ValueKey<String>('line-$index'),
            value: lines[index],
            profile: guiTextProfile(mode),
            preview: mode == GuiTextMode.template ? templateSample : null,
            gui: names,
            label: 'Line ${index + 1}',
            onChanged: (String value) => onChanged(<String>[...lines]..[index] = value),
          ),
          AppIconButton(
            icon: ArcaneIcon.arrowUp(size: IconSize.sm),
            label: 'Move line ${index + 1} up',
            disabled: index == 0,
            onPressed: () => onChanged(<String>[for (final Object? line in moveItem(lines, index, index - 1)) line! as String]),
          ),
          AppIconButton(
            icon: ArcaneIcon.arrowDown(size: IconSize.sm),
            label: 'Move line ${index + 1} down',
            disabled: index == lines.length - 1,
            onPressed: () => onChanged(<String>[for (final Object? line in moveItem(lines, index, index + 1)) line! as String]),
          ),
          AppIconButton(
            icon: ArcaneIcon.x(size: IconSize.sm),
            label: 'Remove line ${index + 1}',
            onPressed: () => onChanged(<String>[...lines]..removeAt(index)),
          ),
        ]),
      Button.outline(label: addLabel, size: buttonSize(DensityScope.of(context)), onPressed: () => onChanged(<String>[...lines, ''])),
    ]);
  }
}

/// Formatted Minecraft text with sample values in its holes. Uncoloured
/// text is [defaultColor]; with null it takes the theme's text colour, for
/// text on the editor's own surfaces (the Test chat) rather than a game
/// window's.
class GuiStyledText extends StatelessWidget {
  const GuiStyledText(this.text, {this.defaultColor = mcDefaultTextColor, this.item = false, super.key}) : runs = null;

  /// Runs already read, such as one row of a text display whose style
  /// carries on from the rows above it ([guiDisplayRows]).
  const GuiStyledText.runs(List<McTextRun> this.runs, {super.key})
      : text = '',
        defaultColor = mcDefaultTextColor,
        item = false;

  final String text;
  final int? defaultColor;

  /// An item's name or lore line: italic until a colour or reset code.
  final bool item;
  final List<McTextRun>? runs;

  @override
  Widget build(BuildContext context) {
    final int? themed = defaultColor;
    return dom.span(classes: 'animus-mc-styled', <Widget>[
      for (final McTextRun run in runs ?? guiTextRuns(text, defaultColor: themed ?? mcDefaultTextColor, item: item))
        dom.span(
          styles: dom.Styles(raw: <String, String>{
            if (themed != null || run.color != mcDefaultTextColor) 'color': run.cssColor,
            if (run.bold) 'font-weight': '700',
            if (run.italic) 'font-style': 'italic',
            if (run.underlined || run.strikethrough)
              'text-decoration': '${run.underlined ? 'underline ' : ''}${run.strikethrough ? 'line-through' : ''}'.trim(),
          }),
          <Widget>[Component.text(run.text)],
        ),
    ]);
  }
}

/// A problem or note inside a row's form, where a boxed alert would be a
/// box in a box: one line in its tone's colour, and an optional action.
class GuiNote extends StatelessWidget {
  const GuiNote({required this.message, this.tone = AlertTone.info, this.action, super.key});

  final String message;
  final AlertTone tone;
  final Widget? action;

  @override
  Widget build(BuildContext context) => dom.div(
        attributes: <String, String>{'data-problem': tone.name, if (tone == AlertTone.error || tone == AlertTone.warning) 'role': 'alert'},
        <Widget>[
          Wrap(spacing: Gap.sm.value, runSpacing: Gap.xs.value, crossAxisAlignment: WrapCrossAlignment.center, children: <Widget>[
            Text(message, size: FontSize.sm, color: switch (tone) { AlertTone.error => TextColor.error, AlertTone.warning => TextColor.warning, AlertTone.ok => TextColor.success, AlertTone.info => TextColor.muted }),
            ?action,
          ]),
        ],
      );
}

/// Reads a string list from JSON, dropping non-strings.
List<String> stringList(Object? value) => <String>[for (final Object? item in asList(value)) if (item is String) item];

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
    return AutocompleteInput(
      id: id,
      value: text,
      profile: FieldProfile.guiExpression,
      gui: names,
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
