import 'dart:js_interop';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;
import 'package:web/web.dart' as web;

/// Moves focus to the element with [id] once the next frame has rendered it.
void focusSoon(String id) =>
    web.window.setTimeout((() => (web.document.getElementById(id) as web.HTMLElement?)?.focus()).toJS, 0.toJS);

/// An inline "write this to the server?" row, shown in place of the action
/// that asked. It takes focus, Escape cancels, and either answer gives focus
/// back to the element [returnFocus] names (the action's button).
class LiveConfirm extends StatefulWidget {
  const LiveConfirm({
    required this.question,
    required this.confirmLabel,
    required this.onConfirm,
    required this.onCancel,
    required this.returnFocus,
    this.detail,
    this.destructive = false,
    super.key,
  });

  final String question;
  final String? detail;
  final String confirmLabel;
  final void Function() onConfirm;
  final void Function() onCancel;

  /// The id of the element to focus after an answer.
  final String returnFocus;

  /// Red when the write throws something away (a draft, a server copy).
  final bool destructive;

  @override
  State<LiveConfirm> createState() => _LiveConfirmState();
}

int _nextId = 0;

class _LiveConfirmState extends State<LiveConfirm> {
  final String _id = 'animus-live-confirm-${_nextId++}';
  web.EventListener? _keys;

  @override
  void initState() {
    super.initState();
    _keys = ((web.KeyboardEvent event) {
      if (event.key == 'Escape') _cancel();
    }).toJS;
    web.window.addEventListener('keydown', _keys);
    focusSoon(_id);
  }

  @override
  void dispose() {
    web.window.removeEventListener('keydown', _keys);
    super.dispose();
  }

  void _cancel() {
    component.onCancel();
    focusSoon(component.returnFocus);
  }

  void _confirm() {
    component.onConfirm();
    focusSoon(component.returnFocus);
  }

  @override
  Widget build(BuildContext context) => dom.div(
        id: _id,
        classes: 'animus-confirm animus-live-confirm',
        attributes: const <String, String>{'role': 'alertdialog', 'aria-live': 'assertive', 'tabindex': '-1'},
        <Widget>[
          dom.span(<Widget>[
            dom.strong(<Widget>[Component.text(component.question)]),
            if (component.detail != null) dom.span(classes: 'animus-muted', <Widget>[Component.text(' ${component.detail}')]),
          ]),
          dom.div(classes: 'animus-row', <Widget>[
            Button(
              label: component.confirmLabel,
              size: ButtonSize.small,
              variant: component.destructive ? ButtonVariant.destructive : ButtonVariant.primary,
              onPressed: _confirm,
            ),
            Button.ghost(label: 'Cancel', size: ButtonSize.small, onPressed: _cancel),
          ]),
        ],
      );
}
