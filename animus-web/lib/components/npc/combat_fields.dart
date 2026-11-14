/// The rows the Combat tab is built from. A [ModuleForm] is one module being
/// edited (`animus:combat` or `animus:health`); each row edits one value by
/// its path in that module, labelled in words with the engine key only in
/// its (i) tip. A value of another type than the row expects is kept and
/// edited as typed JSON, and keys the module does not know stay under
/// "Other fields".
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../config/docs_links.dart';
import '../../logic/diagnostic_text.dart';
import '../../logic/gui/gui_json.dart';
import '../../logic/inspect/schema_fields.dart';
import '../../logic/inspect/typed_values.dart';
import '../../logic/npc/combat_forms.dart';
import '../../logic/npc/combat_labels.dart';
import '../../logic/npc/combat_presets.dart';
import '../../logic/npc/npc_display.dart';
import '../../logic/suggest/field_profile.dart';
import '../../model/animus_document.dart';
import '../../ui/action_row.dart';
import '../../ui/field.dart';
import '../../ui/icon_button.dart';
import '../../ui/section.dart';
import '../../ui/stack.dart';
import '../gui/gui_fields.dart';
import '../inspect/typed_values_editor.dart';
import '../select/suggest_input.dart';
import '../select/animus_select.dart';
import '../../ui/number_input.dart';
import 'npc_layout.dart';
import '../gui/gui_stack.dart';
import '../../ui/problem_text.dart';

/// One module of the NPC (or trait group) being edited: reads and writes by
/// path, and its diagnostics by path.
class ModuleForm {
  ModuleForm({required this.key, required this.value, required this.onChanged, List<Diagnostic> diagnostics = const <Diagnostic>[]})
      : _problems = <(List<JsonStep>, Diagnostic)>[
          for (final Diagnostic diagnostic in diagnostics)
            if (moduleSteps(diagnostic) case final List<JsonStep> steps when steps.first == key) (steps.sublist(1), diagnostic),
        ];

  /// The module key, such as `animus:combat`.
  final String key;
  final Object? value;

  /// The whole new module; null removes it.
  final void Function(Object? value) onChanged;
  final List<(List<JsonStep>, Diagnostic)> _problems;

  JsonMap get map => asMap(value);

  Object? read(List<JsonStep> steps) => readIn(value, steps);

  /// Writes [next] at [steps]; an emptied key and the sections it leaves
  /// empty go away.
  void put(List<JsonStep> steps, Object? next) => onChanged(withModuleField(map, steps, next));

  /// The problems at [steps] (and inside it when [nested]), with paths
  /// relative to it, so a row reads "Must be within 1..6" under its label.
  List<Diagnostic> problems(List<JsonStep> steps, {bool nested = false}) => <Diagnostic>[
        for (final (List<JsonStep> at, Diagnostic diagnostic) in _problems)
          if (_within(at, steps) && (nested || at.length == steps.length))
            Diagnostic(diagnostic.severity, formatPath(at.sublist(steps.length)), diagnostic.message, code: diagnostic.code),
      ];

  /// Every problem of the module.
  List<Diagnostic> get allProblems => <Diagnostic>[for (final (List<JsonStep> _, Diagnostic diagnostic) in _problems) diagnostic];

  static bool _within(List<JsonStep> at, List<JsonStep> steps) {
    if (at.length < steps.length) return false;
    for (int index = 0; index < steps.length; index++) {
      if (at[index] != steps[index]) return false;
    }
    return true;
  }
}

/// The element id of the control at [steps] of [form].
String fieldId(ModuleForm form, List<JsonStep> steps) => guiPathId('${form.key}.${steps.join('.')}');

/// [value] without a trailing `.0`.
String plainNumber(num value) => value == value.roundToDouble() ? value.toInt().toString() : value.toString();

/// [steps] as the key people see in the JSON: `melee.reach`, `targets.chat[0]`.
String keyPath(List<JsonStep> steps) {
  final StringBuffer out = StringBuffer();
  for (final JsonStep step in steps) {
    if (step is int) {
      out.write('[$step]');
    } else {
      if (out.isNotEmpty) out.write('.');
      out.write(step);
    }
  }
  return out.toString();
}

/// The (i) text of a row: what it does, [extra] (a range, a default), and its key.
String fieldTip(KeyWords words, List<JsonStep> steps, [String extra = '']) =>
    <String>[if (words.tip.isNotEmpty) words.tip, if (extra.isNotEmpty) extra, 'Key: ${keyPath(steps)}'].join(' ');

/// A number. [ticks] adds the time it makes as the hint ("40 ticks, 2 s");
/// [hint] gives any other hint for the value (or the fallback while unset).
Widget numberField(
  ModuleForm form,
  List<JsonStep> steps,
  KeyWords words, {
  required num fallback,
  num? minimum,
  num? maximum,
  bool integer = false,
  bool ticks = false,
  String Function(num value)? hint,
  String extraTip = '',
}) {
  final Object? value = form.read(steps);
  final String id = fieldId(form, steps);
  final bool typed = value == null || value is num;
  final String range = minimum == null || maximum == null ? '' : '${plainNumber(minimum)} to ${plainNumber(maximum)}.';
  return Field(
    key: ValueKey<String>('field:${form.key}:${steps.join('.')}'),
    label: words.label,
    tip: fieldTip(words, steps, <String>[if (range.isNotEmpty) range, if (extraTip.isNotEmpty) extraTip].join(' ')),
    hint: !typed
        ? null
        : ticks
            ? ticksHint(value as num?, fallback)
            : hint?.call((value as num?) ?? fallback),
    forId: typed ? id : null,
    problems: form.problems(steps, nested: true),
    child: typed
        ? NumberInput(digits: 6, 
            id: id,
            value: value as num?,
            integer: integer,
            label: words.label,
            placeholder: plainNumber(fallback),
            onChanged: (num? next) => form.put(steps, next),
          )
        : TypedValueEditor(value: value, label: words.label, onChanged: (Object? next) => form.put(steps, next)),
  );
}

/// An on/off value that reads [fallback] while unset. With [unsetAtDefault]
/// switching back to [fallback] removes the key instead of writing it.
Widget switchField(ModuleForm form, List<JsonStep> steps, KeyWords words, {required bool fallback, bool unsetAtDefault = false}) {
  final Object? value = form.read(steps);
  if (value != null && value is! bool) return keptField(form, steps, words);
  return Field(
    key: ValueKey<String>('field:${form.key}:${steps.join('.')}'),
    label: words.label,
    tip: fieldTip(words, steps, 'Default ${fallback ? 'on' : 'off'}.'),
    problems: form.problems(steps),
    child: combatSwitch(
      value: (value as bool?) ?? fallback,
      label: words.label,
      onChanged: (bool next) => form.put(steps, unsetAtDefault && next == fallback ? null : next),
    ),
  );
}

/// A switch centred on the row's control height, named [label].
Widget combatSwitch({required bool value, required String label, required void Function(bool value) onChanged}) =>
    dom.div(styles: _switchStyles, <Widget>[ArcaneToggleSwitch(value: value, ariaLabel: label, onChanged: onChanged)]);

const dom.Styles _switchStyles = dom.Styles(raw: <String, String>{'display': 'flex', 'align-items': 'center', 'min-height': '2.25rem'});

/// A numbered place or drop's number, on its first line of fields: muted,
/// or pink and bold for the place picked on the grid.
Widget pointNumber(int number, {bool picked = false}) => dom.span(attributes: const <String, String>{'aria-hidden': 'true'}, <Widget>[
      Text(
        '$number',
        size: FontSize.sm,
        weight: picked ? FontWeight.w600 : null,
        colorCustom: picked ? 'var(--primary)' : 'var(--muted-foreground)',
        cssStyle: const ArcaneStyleData(display: Display.inlineBlock, minWidth: '1.25rem', paddingBottom: '0.5rem'),
      ),
    ]);

/// Check boxes side by side, wrapping where the form is narrow.
const dom.Styles _checksStyles = dom.Styles(raw: <String, String>{'display': 'flex', 'flex-wrap': 'wrap', 'gap': '0.5rem 1rem'});

/// The problems of a whole part (a section's object, a list), as the
/// field rows show theirs.
Widget problemLines(List<Diagnostic> problems) => problems.isEmpty
    ? const Component.fragment(<Widget>[])
    : fieldStack(<Widget>[
        for (final Diagnostic problem in problems)
          problemLine(problem.severity, diagnosticSentence(problem)),
      ]);

/// An [AppSection] that opens when its part gets a value and stays open
/// when the value is cleared again, so emptying the last field to type a
/// new one does not fold it away under the cursor.
class CombatSection extends StatefulWidget {
  const CombatSection({required this.title, required this.children, required this.open, this.tip, this.docs, this.count, this.part, super.key});

  final String title;
  final List<Widget> children;

  /// Whether the part holds a value.
  final bool open;
  final String? tip;
  final DocLink? docs;
  final int? count;

  /// `data-combat-section`, for tests.
  final String? part;

  @override
  State<CombatSection> createState() => _CombatSectionState();
}

class _CombatSectionState extends State<CombatSection> {
  late bool _open = component.open;

  @override
  void didUpdateWidget(CombatSection oldComponent) {
    super.didUpdateWidget(oldComponent);
    if (component.open) _open = true;
  }

  @override
  Widget build(BuildContext context) => AppSection(
        title: component.title,
        tip: component.tip,
        docs: component.docs,
        count: component.count,
        open: _open,
        attributes: <String, String>{'data-combat-section': ?component.part},
        children: component.children,
      );
}

/// One of [options], shown by [labels]; unset reads "Default (Arrow)".
Widget choiceField(
  ModuleForm form,
  List<JsonStep> steps,
  KeyWords words, {
  required List<String> options,
  required Map<String, String> labels,
  required String fallback,
  void Function(String? value)? onChanged,
}) {
  final Object? value = form.read(steps);
  if (value != null && value is! String) return keptField(form, steps, words);
  final String id = fieldId(form, steps);
  return Field(
    key: ValueKey<String>('field:${form.key}:${steps.join('.')}'),
    label: words.label,
    tip: fieldTip(words, steps),
    forId: id,
    problems: form.problems(steps),
    child: AnimusSelect.values(
      id: id,
      value: value as String?,
      values: options,
      labels: labels,
      allowNone: true,
      noneLabel: 'Default (${labels[fallback] ?? fallback})',
      onChanged: onChanged ?? (String? next) => form.put(steps, next),
    ),
  );
}

/// A line of text, completing [suggestions]; empty removes it.
Widget textField(
  ModuleForm form,
  List<JsonStep> steps,
  KeyWords words, {
  List<FieldOption> suggestions = const <FieldOption>[],
  String? placeholder,
  FieldProfile profile = FieldProfile.none,
}) {
  final Object? value = form.read(steps);
  if (value != null && value is! String) return keptField(form, steps, words);
  final String id = fieldId(form, steps);
  return Field(
    key: ValueKey<String>('field:${form.key}:${steps.join('.')}'),
    label: words.label,
    tip: fieldTip(words, steps),
    forId: id,
    problems: form.problems(steps, nested: true),
    child: SuggestInput(
      id: id,
      value: value as String? ?? '',
      label: words.label,
      placeholder: placeholder,
      suggestions: suggestions,
      profile: profile,
      onChanged: (String next) => form.put(steps, next.isEmpty ? null : next),
    ),
  );
}

/// Any number of [options] picked, as checkboxes that wrap into columns,
/// written in the order of [options]; none removes the value. [all] are the
/// picks while the value is unset, and [write] replaces the plain write.
Widget checksField(
  ModuleForm form,
  List<JsonStep> steps,
  KeyWords words, {
  required Map<String, String> options,
  String? label,
  String extraTip = '',
  Set<String>? unset,
  void Function(Set<String> picked)? write,
  bool showProblems = true,
}) {
  final Object? value = form.read(steps);
  if (value != null && (value is! List<Object?> || value.any((Object? item) => item is! String))) return keptField(form, steps, words);
  final Set<String> picked = value == null && unset != null ? unset : stringList(value).toSet();
  void put(Set<String> next) {
    if (write != null) {
      write(next);
      return;
    }
    form.put(steps, <String>[
      for (final String option in options.keys)
        if (next.contains(option)) option,
      for (final String other in picked)
        if (!options.containsKey(other)) other,
    ]);
  }

  final String name = label ?? words.label;
  return Field(
    key: ValueKey<String>('field:${form.key}:${steps.join('.')}:$name'),
    label: name,
    tip: fieldTip(words, steps, extraTip),
    problems: showProblems ? form.problems(steps, nested: true) : const <Diagnostic>[],
    child: dom.div(styles: _checksStyles, attributes: <String, String>{'role': 'group', 'aria-label': name}, <Widget>[
      for (final MapEntry<String, String> option in options.entries)
        ArcaneCheckbox(
          checked: picked.contains(option.key),
          label: option.value,
          onChanged: (bool on) => put(on ? <String>{...picked, option.key} : (<String>{...picked}..remove(option.key))),
        ),
    ]),
  );
}

/// A value this tab has no row for (a setting of another type, an imported
/// record), edited by kind, with a button that removes it.
Widget keptField(ModuleForm form, List<JsonStep> steps, KeyWords words) => Field(
      key: ValueKey<String>('kept:${form.key}:${steps.join('.')}'),
      label: words.label,
      tip: fieldTip(words, steps, 'Kept as written.'),
      problems: form.problems(steps, nested: true),
      child: fieldLine(<Widget>[
        TypedValueEditor(value: form.read(steps), label: words.label, onChanged: (Object? next) => form.put(steps, next)),
        AppIconButton(icon: ArcaneIcon.x(), label: 'Remove ${words.label.toLowerCase()}', onPressed: () => form.put(steps, null)),
      ]),
    );

/// Keys of the object at [steps] outside [known]: the server refuses them,
/// so they are listed apart, to rename, edit or remove.
Widget otherFields(ModuleForm form, List<JsonStep> steps, Set<String> known) {
  final JsonMap object = asMap(steps.isEmpty ? form.value : form.read(steps));
  final JsonMap unknown = unknownEntries(object, known);
  if (unknown.isEmpty) return const Component.fragment(<Widget>[]);
  return Field(
    key: ValueKey<String>('others:${form.key}:${steps.join('.')}'),
    label: 'Other fields',
    tip: 'Keys the server does not know. It refuses them, so rename or remove them.',
    problems: <Diagnostic>[for (final String key in unknown.keys) ...form.problems(<JsonStep>[...steps, key], nested: true)],
    child: KeyValueEditor(
      value: unknown,
      label: 'Other fields',
      onChanged: (JsonMap next) {
        final JsonMap merged = withUnknownEntries(object, known, next);
        if (steps.isEmpty) {
          form.onChanged(merged);
        } else {
          form.put(steps, merged);
        }
      },
    ),
  );
}

/// A list of texts (mob types, player names, squads): one field per entry
/// with a remove button, and a field to add one more.
class StringListField extends StatefulWidget {
  const StringListField({
    required this.form,
    required this.steps,
    required this.words,
    required this.noun,
    this.suggestions = const <FieldOption>[],
    this.placeholder,
    this.profile = FieldProfile.none,
    super.key,
  });

  final ModuleForm form;
  final List<JsonStep> steps;
  final KeyWords words;

  /// One entry, for the buttons: "mob type" gives "Add mob type".
  final String noun;
  final List<FieldOption> suggestions;
  final String? placeholder;
  final FieldProfile profile;

  @override
  State<StringListField> createState() => _StringListFieldState();
}

class _StringListFieldState extends State<StringListField> {
  String _draft = '';

  /// The page shown of a list longer than [listPageSize].
  int _page = 0;

  @override
  Widget build(BuildContext context) {
    final StringListField field = component;
    final Object? raw = field.form.read(field.steps);
    if (raw != null && (raw is! List<Object?> || raw.any((Object? item) => item is! String))) return keptField(field.form, field.steps, field.words);
    final List<String> items = stringList(raw);
    final String id = fieldId(field.form, field.steps);
    final String draft = _draft.trim();
    final ListPage shown = pageOf(items.length, _page);
    void add() {
      if (draft.isEmpty || items.contains(draft)) return;
      field.form.put(field.steps, <String>[...items, draft]);
      setState(() => _draft = '');
    }

    return Field(
      label: field.words.label,
      tip: fieldTip(field.words, field.steps),
      forId: id,
      problems: field.form.problems(field.steps, nested: true),
      child: fieldStack(<Widget>[
        for (int index = shown.from; index < shown.to; index++)
          fieldStack(key: ValueKey<String>('$id-$index'), <Widget>[
            fieldLine(<Widget>[
              SuggestInput(
                value: items[index],
                label: '${field.words.label} ${index + 1}',
                suggestions: field.suggestions,
                profile: field.profile,
                onChanged: (String next) => field.form.put(<JsonStep>[...field.steps, index], next),
              ),
              AppIconButton(
                icon: ArcaneIcon.x(),
                label: 'Remove ${items[index]}',
                onPressed: () => field.form.put(field.steps, <String>[...items]..removeAt(index)),
              ),
            ]),
          ]),
        if (shown.pages > 1)
          ActionRow(
            dense: true,
            label: '${field.words.label} pages',
            leading: <Widget>[mutedText('${shown.from + 1}-${shown.to} of ${items.length}')],
            secondary: <RowAction>[
              RowAction('Previous ${field.noun}s', shown.page == 0 ? null : () => setState(() => _page = shown.page - 1), icon: ArcaneIcon.chevronLeft(), iconOnly: true),
              RowAction('Next ${field.noun}s', shown.page == shown.pages - 1 ? null : () => setState(() => _page = shown.page + 1), icon: ArcaneIcon.chevronRight(), iconOnly: true),
            ],
          ),
        fieldLine(<Widget>[
          SuggestInput(
            id: id,
            value: _draft,
            placeholder: field.placeholder,
            profile: field.profile,
            suggestions: <FieldOption>[
              for (final FieldOption option in field.suggestions)
                if (!items.contains(option.label)) option,
            ],
            onChanged: (String next) => setState(() => _draft = next),
            onSubmit: add,
          ),
          AppIconButton(icon: ArcaneIcon.plus(), label: 'Add ${field.noun}', disabled: draft.isEmpty || items.contains(draft), onPressed: add),
        ]),
      ]),
    );
  }
}
