/// The editor's dropdown: a button with the combobox role that opens a
/// listbox, square and flat like every other control. It filters with a
/// search field past [searchThreshold] options, works fully from the
/// keyboard, opens as a sheet on phones and, for a document reference,
/// ends with "+ New rig" and "Manage rigs" style rows.
library;

import 'dart:js_interop';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;
import 'package:web/web.dart' as web;

import '../../logic/select/select_model.dart';
import '../../model/document_kind.dart';
import '../../state/document_return.dart';
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

  /// Makes the [references] document here from the search text instead of
  /// going to the New page; the search field then always shows.
  final void Function(String search)? onNew;

  @override
  State<AnimusSelect> createState() => _AnimusSelectState();
}

class _AnimusSelectState extends State<AnimusSelect> {
  final String _ownId = 'animus-select-${_nextSelectId++}';
  SelectState _state = const SelectState();
  PopupPlacement _placement = const PopupPlacement(sheet: false);
  void Function()? _stopWatching;

  String get _baseId => component.id ?? _ownId;
  String get _rootId => '$_baseId-root';
  String get _popupId => '$_baseId-popup';
  String get _searchId => '$_baseId-search';

  String get _field => component.field ?? component.label ?? component.hiddenLabel ?? component.placeholder;

  String get _name => component.hiddenLabel ?? component.label ?? component.placeholder;

  bool get _searchable => component.onNew != null || searchable(component.options.length);

  List<SelectAction> _actions(PickerScope? scope) {
    final DocumentKind? kind = component.references;
    return kind == null || scope == null ? const <SelectAction>[] : scope.actionsFor(kind, query: _state.query);
  }

  SelectRows _rows(PickerScope? scope) => SelectRows(filterOptions(component.options, _state.query), _actions(scope).length);

  @override
  void dispose() {
    _stopWatching?.call();
    super.dispose();
  }

  void _focus(String id) => web.window.requestAnimationFrame(((JSNumber _) {
        final web.Element? element = web.document.getElementById(id);
        if (element == null) return;
        (element as web.HTMLElement).focus();
        if (element.isA<web.HTMLInputElement>()) {
          final web.HTMLInputElement input = element as web.HTMLInputElement;
          input.setSelectionRange(input.value.length, input.value.length);
        }
      }).toJS);

  void _setState(SelectState next, PickerScope? scope) {
    final bool opening = next.open && !_state.open;
    final bool closing = !next.open && _state.open;
    if (opening) {
      final SelectRows rows = SelectRows(filterOptions(component.options, next.query), _actions(scope).length);
      final int groups = rows.options.map((SelectOption option) => option.group).whereType<String>().toSet().length;
      final double wanted = popupHeight(options: rows.options.length, groups: groups, actions: rows.actionCount, search: _searchable);
      _placement = placePopup(_baseId, wanted);
      _stopWatching = watchOutside(
        _rootId,
        _popupId,
        _baseId,
        () {
          if (mounted && _state.open) setState(() => _close());
        },
        wanted: wanted,
        sheet: _placement.sheet,
        onMove: (PopupPlacement moved) {
          if (mounted && _state.open) setState(() => _placement = moved);
        },
      );
      if (_searchable) _focus(_searchId);
    }
    if (closing) _close(keepState: true);
    setState(() => _state = next);
    if (next.open) revealRow(_baseId, next.active);
  }

  void _close({bool keepState = false}) {
    _stopWatching?.call();
    _stopWatching = null;
    if (!keepState) _state = const SelectState();
  }

  void _toggle(PickerScope? scope) {
    if (component.disabled) return;
    if (_state.open) {
      _setState(const SelectState(), scope);
      return;
    }
    _setState(SelectState(open: true, active: openingRow(_rows(scope), component.value)), scope);
  }

  void _pick(int index, PickerScope? scope) {
    final SelectRows rows = _rows(scope);
    if (index < 0 || index >= rows.length || !rows.enabled(index)) return;
    final String query = _state.query;
    _setState(const SelectState(), scope);
    _focus(_baseId);
    if (index < rows.options.length) {
      final String value = rows.options[index].value;
      if (value != component.value) component.onChanged(value);
      return;
    }
    final SelectAction action = _actions(scope)[index - rows.options.length];
    final DocumentKind? kind = component.references;
    if (action.id == recordInGameActionId) {
      ArcaneSonner.info('Record in game with /animus record start <id>.');
    } else if (action.id == newActionId && component.onNew != null) {
      component.onNew!(query);
    } else if (kind != null && scope != null) {
      scope.run(action.id, kind, _field, query);
    }
  }

  void _key(web.Event raw, PickerScope? scope, {bool inSearch = false}) {
    final web.KeyboardEvent event = raw as web.KeyboardEvent;
    final SelectRows rows = _rows(scope);
    if (_state.open && _searchable && !inSearch && event.key.length == 1 && !event.ctrlKey && !event.metaKey) {
      // Letters typed before focus reaches the search field still search.
      event.preventDefault();
      event.stopPropagation();
      _setState(searchChanged(_state, component.options, '${_state.query}${event.key}', rows.actionCount), scope);
      _focus(_searchId);
      return;
    }
    final SelectStep step = selectKey(
      _state,
      rows,
      event.key,
      value: component.value,
      alt: event.altKey,
      modifier: event.ctrlKey || event.metaKey,
      inSearch: inSearch,
      now: DateTime.now().millisecondsSinceEpoch,
    );
    if (!step.handled) {
      if (step.effect == SelectEffect.dismiss) _setState(step.state, scope);
      return;
    }
    event.preventDefault();
    event.stopPropagation();
    switch (step.effect) {
      case SelectEffect.commit || SelectEffect.action:
        _pick(_state.active, scope);
      case SelectEffect.close:
        _setState(step.state, scope);
        _focus(_baseId);
      case SelectEffect.dismiss || SelectEffect.none:
        if (!_state.open && step.state.open && _searchable && event.key.length == 1) {
          // A letter on a closed searchable dropdown starts the search.
          _setState(searchChanged(step.state, component.options, event.key, rows.actionCount).copyWith(open: true), scope);
        } else {
          _setState(step.state, scope);
        }
    }
  }

  @override
  Widget build(BuildContext context) {
    final PickerScope? scope = PickerScope.of(context);
    _claimReturn(scope);
    final SelectRows rows = _rows(scope);
    final SelectOption? chosen = component.options.where((SelectOption option) => option.value == component.value).firstOrNull;
    final bool open = _state.open;
    final String activeId = open && _state.active >= 0 ? rowId(_baseId, _state.active) : '';
    final String? hidden = component.hiddenLabel;
    return dom.div(id: _rootId, classes: 'animus-select${open ? ' is-open' : ''}${component.disabled ? ' is-disabled' : ''}', <Widget>[
      if (component.label != null) dom.label(htmlFor: _baseId, classes: 'animus-select-caption', <Widget>[Component.text(component.label!)]),
      dom.button(
        id: _baseId,
        classes: 'animus-select-trigger${chosen == null ? ' is-placeholder' : ''}',
        attributes: <String, String>{
          'type': 'button',
          'role': 'combobox',
          'aria-haspopup': 'listbox',
          'aria-expanded': '$open',
          'aria-controls': '$_baseId-listbox',
          'data-value': component.value ?? '',
          'aria-label': ?hidden,
          if (activeId.isNotEmpty && !_searchable) 'aria-activedescendant': activeId,
          if (component.disabled) 'disabled': '',
        },
        events: <String, void Function(web.Event)>{
          'click': (web.Event _) => _toggle(scope),
          'keydown': (web.Event event) => _key(event, scope),
          // Space handled on keydown must not also click the button on keyup.
          'keyup': (web.Event event) {
            if ((event as web.KeyboardEvent).key == ' ') event.preventDefault();
          },
        },
        <Widget>[
          if (chosen?.swatch != null) dom.span(classes: 'animus-select-swatch', attributes: <String, String>{'aria-hidden': 'true', 'style': 'background:${chosen!.swatch}'}, const <Widget>[]),
          if (chosen?.skinFace != null) faceThumb(chosen!.skinFace!),
          dom.span(classes: 'animus-select-value', <Widget>[Component.text(chosen?.label ?? component.placeholder)]),
          dom.span(classes: 'animus-select-chevron', attributes: const <String, String>{'aria-hidden': 'true'}, <Widget>[ArcaneIcon.chevronDown(size: IconSize.sm)]),
        ],
      ),
      if (open && _placement.sheet)
        dom.div(
          classes: 'animus-select-scrim',
          attributes: const <String, String>{'aria-hidden': 'true'},
          events: <String, void Function(web.Event)>{
            'click': (web.Event event) {
              event
                ..preventDefault()
                ..stopPropagation();
              _setState(const SelectState(), scope);
              _focus(_baseId);
            },
          },
          const <Widget>[],
        ),
      if (open)
        dom.div(
          id: _popupId,
          classes: 'animus-select-popup${_placement.classes}',
          styles: dom.Styles(raw: _placement.styles),
          // A click inside the list must not activate an enclosing <label>,
          // which would click the trigger again and reopen the list.
          events: <String, void Function(web.Event)>{
            'click': (web.Event event) => event
              ..preventDefault()
              ..stopPropagation(),
          },
          <Widget>[
            if (_placement.sheet) dom.div(classes: 'animus-select-sheet-title', attributes: const <String, String>{'aria-hidden': 'true'}, <Widget>[Component.text(_name)]),
            if (_searchable)
              dom.input(
                id: _searchId,
                classes: 'animus-select-search',
                value: _state.query,
                attributes: <String, String>{
                  'type': 'text',
                  'role': 'combobox',
                  'autocomplete': 'off',
                  'spellcheck': 'false',
                  'placeholder': 'Search',
                  'aria-label': 'Search $_name',
                  'aria-autocomplete': 'list',
                  'aria-expanded': 'true',
                  'aria-controls': '$_baseId-listbox',
                  if (activeId.isNotEmpty) 'aria-activedescendant': activeId,
                },
                events: <String, void Function(web.Event)>{
                  'input': (web.Event event) {
                    final String text = (event.target! as web.HTMLInputElement).value;
                    _setState(searchChanged(_state, component.options, text, rows.actionCount), scope);
                  },
                  'keydown': (web.Event event) => _key(event, scope, inSearch: true),
                },
              ),
            SelectListbox(
              baseId: _baseId,
              label: _name,
              rows: rows,
              active: _state.active,
              selected: <String>{?component.value},
              actions: _actions(scope),
              query: _state.query,
              onPick: (int index) => _pick(index, scope),
              onHover: (int index) => setState(() => _state = _state.copyWith(active: index)),
            ),
          ],
        ),
    ]);
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
}
