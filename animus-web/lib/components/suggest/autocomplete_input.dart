/// The one inline autocomplete for text and expression fields. What it
/// offers comes from the field's [FieldProfile] and the [SuggestScope]:
/// typing a trigger (`{`, `<`, `%`, a name in an expression, an id) opens
/// the list, Ctrl+Space or Cmd+Space opens it anywhere. Arrow keys move,
/// Enter or Tab accept, Escape closes; snippet stops are visited with Tab.
/// Unknown names are underlined with the reason as the hover tip.
///
/// The input is an ARIA combobox that owns a listbox; the active row is its
/// `aria-activedescendant`.
library;

import 'dart:async';
import 'dart:js_interop';
import 'dart:math' as math;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;
import 'package:web/web.dart' as web;

import '../../logic/gui/gui_expression.dart';
import '../../logic/select/popup_fit.dart';
import '../../logic/suggest/completion_item.dart';
import '../../logic/suggest/field_profile.dart';
import '../../logic/suggest/text_completion.dart';
import '../../logic/suggest/text_hints.dart';
import '../select/select_popup.dart' show fitBelowTopBar, sheetWidth;
import 'suggest_list.dart';
import 'suggest_scope.dart';

int _nextId = 0;

class AutocompleteInput extends StatefulWidget {
  const AutocompleteInput({
    required this.value,
    required this.onChanged,
    required this.profile,
    this.label,
    this.id,
    this.placeholder,
    this.multiline = false,
    this.rows = 3,
    this.oneLine = false,
    this.behavior,
    this.gui,
    this.classes = 'animus-gui-input',
    this.invalid = false,
    this.describedBy,
    this.onKey,
    this.attributes = const <String, String>{},
    super.key,
  });

  /// Keys the list did not use go here first; true means it used the key
  /// (the conversation editor's Enter and Backspace).
  final bool Function(web.KeyboardEvent event)? onKey;

  /// Extra attributes of the input element.
  final Map<String, String> attributes;

  final String value;
  final void Function(String value) onChanged;
  final FieldProfile profile;

  /// The accessible name.
  final String? label;

  /// The element id; a `<label for>` with the same id names it.
  final String? id;
  final String? placeholder;

  /// A text area instead of a one-line input.
  final bool multiline;
  final int rows;

  /// A wrapping text area that never takes a line break (expressions).
  final bool oneLine;

  /// Behavior names for this field; the scope's when null.
  final BehaviorNames? behavior;

  /// GUI names for this field (list entries, animations).
  final ExpressionNames? gui;

  /// Classes of the input element.
  final String classes;
  final bool invalid;

  /// Ids of elements that describe the field (a problem line).
  final String? describedBy;

  @override
  State<AutocompleteInput> createState() => _AutocompleteInputState();
}

class _AutocompleteInputState extends State<AutocompleteInput> {
  final String _ownId = 'animus-ac-${_nextId++}';

  /// Follows the component: a reused state may get a field with a new id.
  String get _id => component.id ?? _ownId;
  String get _listId => '$_id-list';
  String get _hintsId => '$_id-hints';

  CompletionResult _result = CompletionResult.none;
  bool _open = false;
  int _active = 0;
  List<TabStop> _stops = const <TabStop>[];
  String _status = '';

  /// Where the open list sits: above the field, and its height cap.
  PopupFit? _fit;

  /// What the user typed while focused; saves land after later keystrokes,
  /// so the field keeps its own text until blur.
  String? _typed;

  String get _text => _typed ?? component.value;

  @override
  void didUpdateWidget(AutocompleteInput oldComponent) {
    super.didUpdateWidget(oldComponent);
    if (component.id == oldComponent.id) return;
    _typed = null;
    _open = false;
    _stops = const <TabStop>[];
  }

  bool get _textArea => component.multiline || component.oneLine;

  CompletionEnv _env(BuildContext context) {
    final CompletionEnv scoped = SuggestScope.of(context, behavior: component.behavior);
    return CompletionEnv(sources: scoped.sources, behavior: scoped.behavior, gui: component.gui ?? ExpressionNames.standard);
  }

  web.HTMLElement? get _element => web.document.getElementById(_id) as web.HTMLElement?;

  (String, int, int) _read() {
    final web.HTMLElement? element = _element;
    if (element != null && element.isA<web.HTMLTextAreaElement>()) {
      final web.HTMLTextAreaElement area = element as web.HTMLTextAreaElement;
      return (area.value, area.selectionStart, area.selectionEnd);
    }
    if (element != null && element.isA<web.HTMLInputElement>()) {
      final web.HTMLInputElement input = element as web.HTMLInputElement;
      final int end = input.value.length;
      return (input.value, input.selectionStart ?? end, input.selectionEnd ?? end);
    }
    return (_text, _text.length, _text.length);
  }

  void _write(String text, int start, int end) {
    final web.HTMLElement? element = _element;
    if (element != null && element.isA<web.HTMLTextAreaElement>()) {
      final web.HTMLTextAreaElement area = element as web.HTMLTextAreaElement;
      area.value = text;
      area.setSelectionRange(start, end);
    } else if (element != null && element.isA<web.HTMLInputElement>()) {
      final web.HTMLInputElement input = element as web.HTMLInputElement;
      input.value = text;
      input.setSelectionRange(start, end);
    }
    element?.focus();
  }

  /// Works out the rows at the caret; opens the list when typing calls for
  /// it, [forced] by Ctrl+Space, or keeps it open while it was.
  void _suggest({bool forced = false}) {
    if (component.profile.isNone) return;
    final (String text, int caret, int _) = _read();
    final CompletionResult next = completeText(text, caret, component.profile, _env(context));
    final bool open = next.items.isNotEmpty && (forced || next.auto || _open);
    if (open && !_open) _fit = _place(next.items.length);
    setState(() {
      _result = next;
      _open = open;
      _active = 0;
      _status = open
          ? '${next.items.length} ${next.items.length == 1 ? 'suggestion' : 'suggestions'}'
          : (forced ? 'Nothing to complete here' : '');
    });
  }

  /// A one-line field's list opens whole between the top bar and the
  /// window's bottom, scrolling the field up first when neither side has
  /// room. A text area's list stays under it.
  PopupFit? _place(int rows) {
    final web.HTMLElement? element = _element;
    if (component.multiline || element == null) return null;
    final double rowHeight = web.window.innerWidth <= sheetWidth ? 46 : 36;
    return fitBelowTopBar(element, math.min(suggestListHeight, rows * rowHeight + 2)).$1;
  }

  void _close() {
    if (_open) setState(() => _open = false);
  }

  void _accept(int index) {
    final List<CompletionItem> items = _result.items;
    if (index < 0 || index >= items.length) return;
    final CompletionItem item = items[index];
    final (String text, int _, int _) = _read();
    final AppliedCompletion applied = _result.apply(text, item);
    _write(applied.text, applied.selectionStart, applied.selectionEnd);
    _typed = applied.text;
    _stops = applied.stops;
    component.onChanged(applied.text);
    setState(() {
      _open = false;
      _status = item.label;
    });
    if (item.reopen) _suggest(forced: true);
  }

  void _nextStop() {
    final TabStop stop = _stops.first;
    _stops = _stops.sublist(1);
    final (String text, int _, int _) = _read();
    _write(text, stop.start.clamp(0, text.length), stop.end.clamp(0, text.length));
  }

  void _move(int by) {
    final int count = _result.items.length;
    setState(() => _active = (_active + by + count) % count);
    final String option = suggestOptionId(_listId, _active);
    scheduleMicrotask(() => web.document.getElementById(option)?.scrollIntoView(<String, String>{'block': 'nearest'}.jsify()! as JSObject));
  }

  void _onKey(web.Event event) {
    if (!event.isA<web.KeyboardEvent>()) return;
    final web.KeyboardEvent key = event as web.KeyboardEvent;
    if (key.key == ' ' && (key.ctrlKey || key.metaKey)) {
      key.preventDefault();
      _suggest(forced: true);
      return;
    }
    if (_open && _result.items.isNotEmpty) {
      switch (key.key) {
        case 'ArrowDown':
          key.preventDefault();
          _move(1);
        case 'ArrowUp':
          key.preventDefault();
          _move(-1);
        case 'Enter' || 'Tab':
          if (key.shiftKey && key.key == 'Tab') return;
          key.preventDefault();
          _accept(_active);
        case 'Escape':
          key.preventDefault();
          key.stopPropagation();
          setState(() {
            _open = false;
            _status = '';
          });
        case 'ArrowLeft' || 'ArrowRight' || 'Home' || 'End':
          _close();
      }
      return;
    }
    if (key.key == 'Tab' && !key.shiftKey && _stops.isNotEmpty) {
      key.preventDefault();
      _nextStop();
      return;
    }
    if (key.key == 'Escape' && _stops.isNotEmpty) {
      _stops = const <TabStop>[];
      return;
    }
    final bool Function(web.KeyboardEvent event)? handle = component.onKey;
    if (handle != null && handle(key)) {
      key.preventDefault();
      return;
    }
    if (key.key == 'ArrowDown' && !component.multiline && (key.altKey || component.profile.values.isNotEmpty)) {
      key.preventDefault();
      _suggest(forced: true);
      return;
    }
    if (key.key == 'Enter' && component.oneLine) key.preventDefault();
  }

  void _onInput(web.Event _) {
    final (String raw, int caret, int _) = _read();
    String text = raw;
    if (component.oneLine && raw.contains('\n')) {
      text = raw.replaceAll('\n', ' ');
      _write(text, caret, caret);
    }
    final int delta = text.length - _text.length;
    if (_stops.isNotEmpty && delta != 0) _stops = shiftStops(_stops, caret - (delta > 0 ? delta : 0), delta);
    _typed = text;
    component.onChanged(text);
    _suggest();
    _syncMirror();
  }

  /// Keeps the underline layer's box, font and scroll on the field's.
  void _syncMirror() {
    scheduleMicrotask(() {
      final web.HTMLElement? element = _element;
      final web.HTMLElement? mirror = web.document.getElementById('$_id-mirror') as web.HTMLElement?;
      if (element == null || mirror == null) return;
      final web.CSSStyleDeclaration style = web.window.getComputedStyle(element);
      for (final String property in const <String>[
        'font-family', 'font-size', 'font-weight', 'letter-spacing', 'line-height', 'padding-top', 'padding-right', 'padding-bottom', 'padding-left', //
        'border-top-width', 'border-right-width', 'border-bottom-width', 'border-left-width', 'text-indent', 'tab-size',
      ]) {
        mirror.style.setProperty(property, style.getPropertyValue(property));
      }
      mirror.style.setProperty('width', '${element.offsetWidth}px');
      mirror.style.setProperty('height', '${element.offsetHeight}px');
      mirror.scrollLeft = element.scrollLeft;
      mirror.scrollTop = element.scrollTop;
    });
  }

  /// Shows the reason of the hint under the pointer as the field's tip.
  void _onPointer(web.Event event, List<TextHint> hints) {
    if (hints.isEmpty || !event.isA<web.MouseEvent>()) return;
    final web.MouseEvent mouse = event as web.MouseEvent;
    final web.HTMLElement? element = _element;
    if (element == null) return;
    String tip = '';
    for (int index = 0; index < hints.length; index++) {
      final web.Element? mark = web.document.getElementById('$_id-hint-$index');
      final web.DOMRect? box = mark?.getBoundingClientRect();
      if (box != null && mouse.clientX >= box.left && mouse.clientX <= box.right && mouse.clientY >= box.top && mouse.clientY <= box.bottom) {
        tip = hints[index].message;
        break;
      }
    }
    if (element.getAttribute('title') != tip) element.setAttribute('title', tip);
  }

  @override
  Widget build(BuildContext context) {
    final String text = _text;
    final CompletionEnv env = _env(context);
    final List<TextHint> hints = component.profile.isNone || text.isEmpty ? const <TextHint>[] : textHints(text, component.profile, env);
    final bool open = _open && _result.items.isNotEmpty;
    if (hints.isNotEmpty) _syncMirror();
    final String describedBy = <String>[if (component.describedBy != null) component.describedBy!, if (hints.isNotEmpty) _hintsId].join(' ');
    final bool completes = !component.profile.isNone;
    final Map<String, String> attributes = <String, String>{
      'autocomplete': 'off',
      'spellcheck': 'false',
      'aria-label': ?component.label,
      'placeholder': ?component.placeholder,
      if (completes) 'role': 'combobox',
      if (completes) 'aria-autocomplete': 'list',
      if (completes) 'aria-expanded': '$open',
      if (completes) 'aria-controls': _listId,
      if (open) 'aria-activedescendant': suggestOptionId(_listId, _active),
      if (component.invalid) 'aria-invalid': 'true',
      if (describedBy.isNotEmpty) 'aria-describedby': describedBy,
      ...component.attributes,
    };
    final Map<String, void Function(web.Event)> events = <String, void Function(web.Event)>{
      'input': _onInput,
      'keydown': _onKey,
      'click': (web.Event _) {
        _close();
        _stops = const <TabStop>[];
      },
      'scroll': (web.Event _) => _syncMirror(),
      'mousemove': (web.Event event) => _onPointer(event, hints),
      'blur': (web.Event _) {
        // A row's mousedown keeps focus; this covers leaving the field.
        Future<void>.delayed(const Duration(milliseconds: 150), () {
          if (!mounted || web.document.activeElement?.id == _id) return;
          setState(() {
            _open = false;
            _typed = null;
            _stops = const <TabStop>[];
            _status = '';
          });
        });
      },
    };
    final String classes = '${component.classes}${component.profile.code ? ' is-code' : ''}${component.invalid ? ' is-invalid' : ''}';
    return dom.div(classes: 'animus-ac${hints.isNotEmpty ? ' has-hints' : ''}', <Widget>[
      if (_textArea)
        dom.textarea(
          id: _id,
          classes: classes,
          rows: component.oneLine ? 1 : component.rows,
          attributes: attributes,
          events: events,
          <Widget>[Component.text(text)],
        )
      else
        dom.input(id: _id, classes: classes, value: text, attributes: attributes, events: events),
      if (hints.isNotEmpty) _mirror(text, hints),
      if (hints.isNotEmpty)
        dom.span(id: _hintsId, classes: 'animus-sr-only', <Widget>[
          Component.text(hints.map((TextHint hint) => '${text.substring(hint.start, hint.end)}: ${hint.message}').join(' ')),
        ]),
      if (open)
        SuggestList(
          id: _listId,
          items: _result.items,
          active: _active,
          title: _result.title,
          classes: _fit?.up ?? false ? 'is-up' : null,
          styles: _fit == null ? null : dom.Styles(raw: <String, String>{'max-height': '${_fit!.maxHeight.floor()}px'}),
          onPick: _accept,
          onHover: (int index) => setState(() => _active = index),
        ),
      if (completes) dom.span(classes: 'animus-sr-only', attributes: const <String, String>{'aria-live': 'polite'}, <Widget>[Component.text(_status)]),
    ]);
  }

  /// The text again, invisible, with each hint marked, laid over the field.
  Widget _mirror(String text, List<TextHint> hints) {
    final List<Widget> parts = <Widget>[];
    int at = 0;
    for (int index = 0; index < hints.length; index++) {
      final TextHint hint = hints[index];
      if (hint.start < at) continue;
      if (hint.start > at) parts.add(Component.text(text.substring(at, hint.start)));
      parts.add(dom.span(id: '$_id-hint-$index', classes: 'animus-ac-hint', <Widget>[Component.text(text.substring(hint.start, hint.end))]));
      at = hint.end;
    }
    if (at < text.length) parts.add(Component.text(text.substring(at)));
    return dom.div(
      id: '$_id-mirror',
      classes: 'animus-ac-mirror${_textArea ? ' is-area' : ''}',
      attributes: const <String, String>{'aria-hidden': 'true'},
      parts,
    );
  }
}
