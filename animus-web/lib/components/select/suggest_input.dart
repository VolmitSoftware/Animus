/// A text field with suggestions: an editable combobox on the same listbox,
/// keyboard model and popup as [AnimusSelect]. Free text stays allowed;
/// a suggestion is taken only when the user moves to one and presses Enter
/// or clicks it. With [SuggestInput.references] it also offers the
/// "+ New" and "Manage" rows of a document picker.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:web/web.dart' as web;

import '../../logic/inspect/schema_fields.dart';
import '../../logic/select/select_model.dart';
import '../../model/document_kind.dart';
import '../../state/document_return.dart';
import 'picker_scope.dart';
import 'select_popup.dart';

int _nextSuggestId = 0;

class SuggestInput extends StatefulWidget {
  const SuggestInput({
    required this.value,
    required this.onChanged,
    this.suggestions = const <FieldOption>[],
    this.label,
    this.placeholder,
    this.onSubmit,
    this.references,
    this.id,
    super.key,
  });

  /// The text field's element id, so a row label can name it with `for`.
  final String? id;
  final String value;
  final void Function(String value) onChanged;
  final List<FieldOption> suggestions;
  final String? label;
  final String? placeholder;

  /// Enter with no suggestion active, such as adding a typed tag.
  final void Function()? onSubmit;

  /// The document kind the text names, which adds the footer rows.
  final DocumentKind? references;

  @override
  State<SuggestInput> createState() => _SuggestInputState();
}

class _SuggestInputState extends State<SuggestInput> {
  late final String _baseId = component.id ?? 'animus-suggest-${_nextSuggestId++}';
  SelectState _state = const SelectState();
  PopupPlacement _placement = const PopupPlacement(sheet: false);
  void Function()? _stopWatching;
  PickerScope? _scope;

  /// The text while focused, kept over slower saves.
  String? _typed;

  String get _rootId => '$_baseId-root';
  String get _popupId => '$_baseId-popup';
  String get _field => component.label ?? component.placeholder ?? 'text';

  bool get _listed => component.suggestions.isNotEmpty || _actions.isNotEmpty;

  List<SelectAction> get _actions {
    final DocumentKind? kind = component.references;
    final PickerScope? scope = _scope;
    return kind == null || scope == null ? const <SelectAction>[] : scope.actionsFor(kind);
  }

  List<SelectOption> get _options => <SelectOption>[
        for (final FieldOption option in component.suggestions)
          SelectOption(value: option.label, label: option.label, description: option.description),
      ];

  /// Suggestions for the text typed since focus; all of them before that.
  SelectRows get _rows => SelectRows(filterOptions(_options, _typed ?? ''), _actions.length);

  @override
  void dispose() {
    _stopWatching?.call();
    super.dispose();
  }

  void _show(SelectState next) {
    if (next.open && !_state.open) {
      final SelectRows rows = _rows;
      _placement = placePopup(_baseId, popupHeight(options: rows.options.length, groups: 0, actions: rows.actionCount, search: false), sheet: false);
      _stopWatching = watchOutside(_rootId, _popupId, _baseId, () {
        if (mounted && _state.open) _show(const SelectState());
      });
    }
    if (!next.open) {
      _stopWatching?.call();
      _stopWatching = null;
    }
    setState(() => _state = next);
    if (next.open) revealRow(_baseId, next.active);
  }

  void _take(int index) {
    final SelectRows rows = _rows;
    if (index < 0 || index >= rows.length) return;
    final String typed = _typed ?? component.value;
    _show(const SelectState());
    if (index >= rows.options.length) {
      final SelectAction action = _actions[index - rows.options.length];
      final DocumentKind? kind = component.references;
      if (action.id == recordInGameActionId) {
        ArcaneSonner.info('Record in game with /animus record start <id>.');
      } else if (kind != null) {
        _scope?.run(action.id, kind, _field, typed);
      }
      return;
    }
    final String value = rows.options[index].value;
    _typed = value;
    component.onChanged(value);
  }

  void _key(web.Event raw) {
    final web.KeyboardEvent event = raw as web.KeyboardEvent;
    final SelectRows rows = _rows;
    if (rows.length == 0 && event.key != 'Enter' && event.key != 'Escape') return;
    if (!_state.open && event.key == 'Enter') {
      final void Function()? submit = component.onSubmit;
      if (submit == null) return;
      event.preventDefault();
      submit();
      setState(() => _typed = null);
      return;
    }
    if (!_state.open && event.key != 'ArrowDown' && event.key != 'ArrowUp') return;
    final SelectStep step = selectKey(_state, rows, event.key, alt: event.altKey, modifier: event.ctrlKey || event.metaKey, inSearch: true);
    if (!step.handled) {
      if (step.effect == SelectEffect.dismiss) _show(step.state);
      return;
    }
    event.preventDefault();
    event.stopPropagation();
    switch (step.effect) {
      case SelectEffect.commit || SelectEffect.action:
        _take(_state.active);
      case _:
        _show(step.state);
    }
  }

  @override
  Widget build(BuildContext context) {
    _scope = PickerScope.of(context);
    _claimReturn();
    final SelectRows rows = _rows;
    final bool open = _state.open && rows.length > 0;
    final String activeId = open && _state.active >= 0 ? rowId(_baseId, _state.active) : '';
    return dom.div(id: _rootId, classes: 'animus-suggest animus-gui-text', <Widget>[
      dom.input(
        id: _baseId,
        classes: 'animus-gui-input',
        value: _typed ?? component.value,
        attributes: <String, String>{
          'type': 'text',
          'autocomplete': 'off',
          'spellcheck': 'false',
          'aria-label': ?component.label,
          'placeholder': ?component.placeholder,
          if (_listed) ...<String, String>{
            'role': 'combobox',
            'aria-autocomplete': 'list',
            'aria-expanded': '$open',
            'aria-controls': '$_baseId-listbox',
          },
          if (activeId.isNotEmpty) 'aria-activedescendant': activeId,
        },
        events: <String, void Function(web.Event)>{
          'input': (web.Event event) {
            final String text = (event.target! as web.HTMLInputElement).value;
            _typed = text;
            component.onChanged(text);
            if (!_listed) return;
            _show(text.isNotEmpty && _rows.length > 0 ? const SelectState(open: true) : const SelectState());
          },
          'keydown': _key,
          'blur': (web.Event _) {
            if (!mounted) return;
            if (_state.open) _show(const SelectState());
            setState(() => _typed = null);
          },
        },
      ),
      if (open)
        dom.div(
          id: _popupId,
          classes: 'animus-select-popup${_placement.classes}',
          styles: dom.Styles(raw: _placement.styles),
          events: <String, void Function(web.Event)>{
            'click': (web.Event event) => event
              ..preventDefault()
              ..stopPropagation(),
          },
          <Widget>[
            SelectListbox(
              baseId: _baseId,
              label: 'Suggestions${component.label == null ? '' : ' for ${component.label}'}',
              rows: rows,
              active: _state.active,
              selected: <String>{component.value},
              actions: _actions,
              query: _typed ?? '',
              onPick: _take,
              onHover: (int index) => setState(() => _state = _state.copyWith(active: index)),
            ),
          ],
        ),
    ]);
  }

  /// Takes the id made by this field's "+ New" trip, once.
  void _claimReturn() {
    final DocumentKind? kind = component.references;
    final PickerScope? scope = _scope;
    if (kind == null || scope == null) return;
    final String? made = documentReturn.claim(scope.route, _field, kind);
    if (made == null) return;
    context.binding.addPostFrameCallback(() {
      if (!mounted) return;
      component.onChanged(made);
      revealAndFocus(_baseId);
    });
  }
}
