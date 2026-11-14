/// The editor's dropdown, on arcane_jaspr's shadcn combobox: group
/// headings, a colour swatch or skin face before an option, a search field
/// past [searchThreshold] options and, for a document reference, "+ New rig"
/// and "Manage rigs" rows under the list. The arcane runtime opens it,
/// moves through it with the keyboard and closes it on Escape.
library;

import 'dart:js_interop';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;
import 'package:web/web.dart' as web;

import '../../logic/select/select_model.dart';
import '../../model/document_kind.dart';
import '../../state/document_return.dart';
import '../../ui/density.dart';
import 'picker_scope.dart';
import 'select_popup.dart';

int _nextSelectId = 0;

class AnimusSelect extends StatefulWidget {
  const AnimusSelect({
    required this.value,
    required this.options,
    required this.onChanged,
    this.placeholder = 'Pick one',
    this.id,
    this.label,
    this.hiddenLabel,
    this.references,
    this.field,
    this.disabled = false,
    this.onNew,
    super.key,
  });

  /// A choice among plain [values], shown by their [labels] where given. With
  /// [allowNone] (or no [value]) a first [noneLabel] row clears it, and
  /// [onChanged] gets null; a [value] not in [values] stays listed.
  factory AnimusSelect.values({
    required String? value,
    required List<String> values,
    required void Function(String? value) onChanged,
    bool allowNone = false,
    String noneLabel = 'default',
    Map<String, String> labels = const <String, String>{},
    Map<String, String> details = const <String, String>{},
    Map<String, String> groups = const <String, String>{},
    Map<String, String> skinFaces = const <String, String>{},
    Set<String> unavailable = const <String>{},
    bool disabled = false,
    DocumentKind? references,
    String? id,
    String? label,
    String? hiddenLabel,
    void Function(String search)? onNew,
    Key? key,
  }) {
    final List<String> all = <String>[...values, if (value != null && value.isNotEmpty && !values.contains(value)) value];
    return AnimusSelect(
      key: key,
      id: id,
      label: label,
      hiddenLabel: hiddenLabel,
      value: value ?? '',
      placeholder: noneLabel,
      references: references,
      disabled: disabled,
      onNew: onNew,
      options: <SelectOption>[
        if (allowNone || value == null) SelectOption(label: noneLabel, value: ''),
        for (final String option in all)
          if (option.isNotEmpty)
            SelectOption(
              label: labels[option] ?? option,
              value: option,
              description: details[option],
              group: groups[option],
              skinFace: skinFaces[option],
              disabled: unavailable.contains(option),
              keywords: labels[option] == null ? const <String>[] : <String>[option],
            ),
      ],
      onChanged: (String next) => onChanged(next.isEmpty ? null : next),
    );
  }

  /// The chosen option's value; one not in [options] shows [placeholder].
  final String? value;
  final List<SelectOption> options;
  final void Function(String value) onChanged;
  final String placeholder;

  /// The trigger's element id, so a `<label for>` can name it.
  final String? id;

  /// A visible label above the trigger.
  final String? label;

  /// A name for assistive technology where the layout already shows it.
  final String? hiddenLabel;

  /// The document kind this dropdown picks, which adds the footer rows.
  final DocumentKind? references;

  /// Names this dropdown for the trip back from "+ New"; defaults to its
  /// label or placeholder, which stay the same when the screen is built again.
  final String? field;
  final bool disabled;

  /// Makes the [references] document here instead of going to the New page.
  final void Function(String search)? onNew;

  @override
  State<AnimusSelect> createState() => _AnimusSelectState();
}

class _AnimusSelectState extends State<AnimusSelect> {
  final String _ownId = 'animus-select-${_nextSelectId++}';

  String get _baseId => component.id ?? _ownId;

  String get _field => component.field ?? component.label ?? component.hiddenLabel ?? component.placeholder;

  /// A form is built again on every keystroke; a dropdown whose options and
  /// value did not change keeps its DOM (a long list is hundreds of nodes).
  /// The callbacks read [component] when they run, so they stay current.
  @override
  bool shouldRebuild(AnimusSelect newComponent) {
    final AnimusSelect now = component;
    return now.value != newComponent.value ||
        now.placeholder != newComponent.placeholder ||
        now.id != newComponent.id ||
        now.label != newComponent.label ||
        now.hiddenLabel != newComponent.hiddenLabel ||
        now.references != newComponent.references ||
        now.field != newComponent.field ||
        now.disabled != newComponent.disabled ||
        (now.onNew == null) != (newComponent.onNew == null) ||
        !sameOptions(now.options, newComponent.options);
  }

  /// What the user typed in the list's search box, so "+ New" starts from it.
  String _search() {
    final web.Element? input = web.document.querySelector('[data-arcane-command-input="$_baseId-list"]');
    return input != null && input.isA<web.HTMLInputElement>() ? (input as web.HTMLInputElement).value.trim() : '';
  }

  void _run(SelectAction action, PickerScope? scope) {
    final DocumentKind? kind = component.references;
    if (action.id == recordInGameActionId) {
      ArcaneSonner.info('Record in game with /animus record start <id>.');
    } else if (action.id == newActionId && component.onNew != null) {
      component.onNew!(_search());
    } else if (kind != null && scope != null) {
      scope.run(action.id, kind, _field, _search());
    }
  }

  /// Applies the id made by this dropdown's "+ New" trip, once, after the
  /// screen it returned to is built.
  void _claimReturn(PickerScope? scope) {
    final DocumentKind? kind = component.references;
    if (kind == null || scope == null) return;
    final String? made = documentReturn.claim(scope.route, _field, kind);
    if (made == null) return;
    context.binding.addPostFrameCallback(() {
      if (!mounted) return;
      component.onChanged(made);
      revealAndFocus(_baseId);
    });
  }

  Widget? _picture(SelectOption option) {
    if (option.skinFace != null) return faceThumb(option.skinFace!);
    if (option.swatch != null) {
      return dom.span(classes: 'animus-select-swatch', styles: dom.Styles(raw: <String, String>{'background': option.swatch!}), const <Widget>[]);
    }
    return null;
  }

  /// [options] of a document dropdown named as their list rows name them;
  /// the id stays a search word. Options given a label keep it.
  List<SelectOption> _named(DocumentKind? kind, PickerScope? scope) {
    final String? Function(DocumentKind, String)? nameOf = scope?.nameOf;
    if (kind == null || nameOf == null) return component.options;
    return <SelectOption>[
      for (final SelectOption option in component.options)
        if (option.value.isNotEmpty && option.label == option.value)
          SelectOption(
            value: option.value,
            label: nameOf(kind, option.value) ?? option.value,
            group: option.group,
            description: option.description,
            disabled: option.disabled,
            keywords: <String>[...option.keywords, option.value],
            swatch: option.swatch,
            skinFace: option.skinFace,
          )
        else
          option,
    ];
  }

  @override
  Widget build(BuildContext context) {
    final PickerScope? scope = PickerScope.of(context);
    _claimReturn(scope);
    final DocumentKind? kind = component.references;
    final List<SelectOption> options = _named(kind, scope);
    final List<SelectAction> actions = kind == null || scope == null ? const <SelectAction>[] : scope.actionsFor(kind);
    final bool known = options.any((SelectOption option) => option.value == component.value);
    final String? name = component.label == null ? component.hiddenLabel : null;
    return dom.div(classes: 'animus-select', <Widget>[
      if (name != null) dom.label(htmlFor: _baseId, classes: 'animus-sr-only', <Widget>[Component.text(name)]),
      ArcaneCombobox<String>(
        id: '$_baseId-list',
        triggerId: _baseId,
        size: DensityScope.isDense(context) ? ComboboxSize.sm : ComboboxSize.md,
        label: component.label,
        value: known ? component.value : null,
        placeholder: component.placeholder,
        searchable: component.onNew != null || searchable(options.length),
        searchPlaceholder: 'Search',
        disabled: component.disabled,
        emptyMessage: 'No matches',
        options: <ComboboxOption<String>>[
          for (final SelectOption option in options)
            ComboboxOption<String>(
              value: option.value,
              label: option.label,
              description: option.description,
              disabled: option.disabled,
              keywords: option.keywords,
              group: option.group,
              leading: _picture(option),
            ),
        ],
        footer: actions.isEmpty
            ? null
            : dom.div(classes: 'animus-select-actions', <Widget>[
                for (final SelectAction action in actions)
                  Button.ghost(label: action.label, size: ButtonSize.sm, fullWidth: true, onPressed: () => _run(action, scope)),
              ]),
        onChanged: (String? next) {
          if (next != null && next != component.value) component.onChanged(next);
        },
      ),
    ]);
  }
}

