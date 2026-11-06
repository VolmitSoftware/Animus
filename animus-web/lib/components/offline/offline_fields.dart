/// Small form pieces the offline pages share: a labelled field, a group of
/// option cards and an inline confirmation for deletes.
library;

import 'dart:js_interop';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;
import 'package:web/web.dart' as web;

/// A visible label around one control, with a help line and a problem.
class FieldRow extends StatelessWidget {
  const FieldRow({required this.label, required this.child, this.help, this.error, super.key});

  final String label;
  final Widget child;
  final String? help;
  final String? error;

  @override
  Widget build(BuildContext context) => dom.div(classes: 'animus-field${error == null ? '' : ' has-error'}', <Widget>[
        // Only the label text names the control; help and problems sit
        // outside the label so they do not join its accessible name.
        dom.label(classes: 'animus-field-control', <Widget>[
          dom.span(classes: 'animus-field-label', <Widget>[Component.text(label)]),
          child,
        ]),
        if (help != null) dom.span(classes: 'animus-field-help', <Widget>[Component.text(help!)]),
        if (error != null) dom.span(classes: 'animus-field-error', attributes: const <String, String>{'role': 'alert'}, <Widget>[Component.text(error!)]),
      ]);
}

/// A text box inside a [FieldRow].
class OfflineTextField extends StatelessWidget {
  const OfflineTextField({
    required this.label,
    required this.value,
    required this.onChanged,
    this.help,
    this.error,
    this.placeholder,
    this.id,
    this.onSubmit,
    this.onCancel,
    super.key,
  });

  final String label;
  final String value;
  final void Function(String value) onChanged;
  final String? help;
  final String? error;
  final String? placeholder;
  final String? id;

  /// Enter in the box.
  final void Function()? onSubmit;

  /// Escape in the box.
  final void Function()? onCancel;

  void _keys(web.Event raw) {
    final web.KeyboardEvent event = raw as web.KeyboardEvent;
    if (event.key == 'Escape' && onCancel != null) {
      event.preventDefault();
      event.stopPropagation();
      onCancel!();
    } else if (event.key == 'Enter' && onSubmit != null) {
      event.preventDefault();
      onSubmit!();
    }
  }

  @override
  Widget build(BuildContext context) {
    final Widget input = TextInput(id: id, value: value, placeholder: placeholder, size: ComponentSize.sm, onChanged: onChanged);
    return FieldRow(
      label: label,
      help: help,
      error: error,
      child: onCancel == null && onSubmit == null
          ? input
          : dom.span(classes: 'animus-field-keys', events: <String, void Function(web.Event)>{'keydown': _keys}, <Widget>[input]),
    );
  }
}

/// One option of an [OptionCards] group.
class CardOption<T> {
  const CardOption(this.value, this.title, this.detail);

  final T value;
  final String title;
  final String detail;
}

/// Mutually exclusive options shown as cards with a line of detail each.
class OptionCards<T> extends StatelessWidget {
  const OptionCards({required this.label, required this.options, required this.value, required this.onChanged, super.key});

  final String label;
  final List<CardOption<T>> options;
  final T value;
  final void Function(T value) onChanged;

  @override
  Widget build(BuildContext context) => dom.div(classes: 'animus-option-group', <Widget>[
        dom.span(classes: 'animus-field-label', attributes: const <String, String>{'aria-hidden': 'true'}, <Widget>[Component.text(label)]),
        dom.div(classes: 'animus-option-cards', attributes: <String, String>{'role': 'radiogroup', 'aria-label': label}, <Widget>[
          for (final CardOption<T> option in options)
            dom.button(
              classes: 'animus-option-card${option.value == value ? ' is-selected' : ''}',
              attributes: <String, String>{'type': 'button', 'role': 'radio', 'aria-checked': '${option.value == value}'},
              events: <String, void Function(web.Event)>{'click': (web.Event _) => onChanged(option.value)},
              <Widget>[
                dom.strong(<Widget>[Component.text(option.title)]),
                dom.span(classes: 'animus-muted', <Widget>[Component.text(option.detail)]),
              ],
            ),
        ]),
      ]);
}

/// "Delete X?" with the consequence and two buttons, shown in place of the
/// button that asked. Escape keeps the thing.
class ConfirmRow extends StatefulWidget {
  const ConfirmRow({required this.question, required this.confirmLabel, required this.onConfirm, required this.onCancel, this.detail, super.key});

  final String question;
  final String? detail;
  final String confirmLabel;
  final void Function() onConfirm;
  final void Function() onCancel;

  @override
  State<ConfirmRow> createState() => _ConfirmRowState();
}

int _nextConfirmId = 0;

class _ConfirmRowState extends State<ConfirmRow> {
  final String _id = 'animus-confirm-${_nextConfirmId++}';

  @override
  void initState() {
    super.initState();
    // The question takes focus, so Tab reaches its two buttons next.
    web.window.setTimeout((() => (web.document.getElementById(_id) as web.HTMLElement?)?.focus()).toJS, 0.toJS);
  }

  /// Escape counts only while focus is inside this confirmation, so one key
  /// press never cancels a confirmation elsewhere on the page.
  void _keys(web.Event raw) {
    final web.KeyboardEvent event = raw as web.KeyboardEvent;
    if (event.key != 'Escape') return;
    event.preventDefault();
    event.stopPropagation();
    component.onCancel();
  }

  @override
  Widget build(BuildContext context) => dom.div(
        id: _id,
        classes: 'animus-confirm',
        attributes: <String, String>{'role': 'alertdialog', 'aria-labelledby': '$_id-question', 'tabindex': '-1'},
        events: <String, void Function(web.Event)>{'keydown': _keys},
        <Widget>[
          dom.span(<Widget>[
            dom.strong(id: '$_id-question', <Widget>[Component.text(component.question)]),
            if (component.detail != null) dom.span(classes: 'animus-muted', <Widget>[Component.text(' ${component.detail}')]),
          ]),
          dom.div(classes: 'animus-row', <Widget>[
            Button.destructive(label: component.confirmLabel, size: ButtonSize.small, onPressed: component.onConfirm),
            Button.ghost(label: 'Cancel', size: ButtonSize.small, onPressed: component.onCancel),
          ]),
        ],
      );
}
