/// The conversation editor's text field: it reports every keystroke, hands
/// keys to the editor first (Enter adds the next line, Backspace on an empty
/// field removes it, Alt+arrows move it) and shows its own text until the
/// save of that text comes back, even after it loses focus, so a slow save
/// never rewinds it.
library;

import 'dart:js_interop';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:web/web.dart' as web;

import '../../logic/suggest/field_profile.dart';
import '../suggest/autocomplete_input.dart';

/// Handles a key in a conversation field; returns true when it used it.
typedef ConversationKey = bool Function(web.KeyboardEvent event, web.HTMLInputElement input);

class ConversationInput extends StatefulWidget {
  const ConversationInput({
    required this.value,
    required this.label,
    required this.onChanged,
    this.onKey,
    this.placeholder,
    this.code = false,
    this.profile = FieldProfile.none,
    this.id,
    super.key,
  });

  /// What the field completes (placeholders, tags, ids).
  final FieldProfile profile;

  final String? id;
  final String value;

  /// The accessible name.
  final String label;
  final void Function(String value) onChanged;
  final ConversationKey? onKey;
  final String? placeholder;

  /// Ids and expressions: monospace, no spell checking.
  final bool code;

  @override
  State<ConversationInput> createState() => _ConversationInputState();
}

class _ConversationInputState extends State<ConversationInput> {
  String? _typed;

  /// Texts typed since the shown value last matched, so the save of an
  /// earlier keystroke is told apart from an edit made elsewhere (undo).
  final Set<String> _sent = <String>{};

  @override
  void didUpdateWidget(ConversationInput oldComponent) {
    super.didUpdateWidget(oldComponent);
    if (_typed == null) return;
    // A reused state may hold another field now (a line turned question),
    // whose value can be one of the texts typed on the way.
    if (component.id != oldComponent.id || component.value == _typed || !_sent.contains(component.value)) {
      _typed = null;
      _sent.clear();
    }
  }

  @override
  Widget build(BuildContext context) {
    final ConversationKey? handle = component.onKey;
    return AutocompleteInput(
      id: component.id,
      code: component.code,
      value: _typed ?? component.value,
      profile: component.profile,
      label: component.label,
      placeholder: component.placeholder,
      attributes: <String, String>{'spellcheck': component.code ? 'false' : 'true', 'data-conv-input': ''},
      onChanged: (String text) {
        if (_typed == null) _sent.add(component.value);
        _typed = text;
        _sent.add(text);
        component.onChanged(text);
      },
      onKey: handle == null ? null : (web.KeyboardEvent event) => handle(event, event.target! as web.HTMLInputElement),
    );
  }
}

/// The one field for formatted step text: a line, a question, an answer
/// and its hover, the text of a message, action bar or title step, and a
/// text value a step stores. Every one of them is built here, so changing
/// how formatted text is edited is a change to this function alone.
Widget formattedTextInput({
  required String value,
  required String label,
  required void Function(String value) onChanged,
  String? id,
  ConversationKey? onKey,
  String? placeholder,
  FieldProfile profile = FieldProfile.richText,
}) =>
    ConversationInput(id: id, value: value, label: label, onChanged: onChanged, onKey: onKey, placeholder: placeholder, profile: profile);

/// Inserts [text] at the caret of the field [id] and reports it as typed.
void insertAtCaret(String id, String text) {
  final web.HTMLInputElement? input = web.document.getElementById(id) as web.HTMLInputElement?;
  if (input == null) return;
  final int start = input.selectionStart ?? input.value.length;
  final int end = input.selectionEnd ?? start;
  input.setRangeText(text, start, end, 'end');
  input.focus();
  input.dispatchEvent(web.Event('input', web.EventInit(bubbles: true)));
}

/// Focuses the field [id] once a re-render shows it with [value], giving up
/// after about a second.
void focusField(String id, {String? value, bool atEnd = true}) {
  int tries = 0;
  void attempt() {
    final web.HTMLInputElement? input = web.document.getElementById(id) as web.HTMLInputElement?;
    if (input != null && (value == null || input.value == value || tries > 60)) {
      input.focus();
      if (atEnd) input.setSelectionRange(input.value.length, input.value.length);
      return;
    }
    if (++tries > 70) return;
    web.window.setTimeout(attempt.toJS, 16.toJS);
  }

  web.window.setTimeout(attempt.toJS, 0.toJS);
}

/// Moves focus to the conversation field before ([step] -1) or after (+1)
/// [from] in page order. Returns whether one was there.
bool focusNeighbour(web.Element from, int step) {
  final web.NodeList fields = web.document.querySelectorAll('[data-conv-input]');
  for (int index = 0; index < fields.length; index++) {
    if (identical(fields.item(index), from)) {
      final int next = index + step;
      if (next < 0 || next >= fields.length) return false;
      final web.HTMLInputElement target = fields.item(next)! as web.HTMLInputElement;
      target.focus();
      target.setSelectionRange(target.value.length, target.value.length);
      return true;
    }
  }
  return false;
}
