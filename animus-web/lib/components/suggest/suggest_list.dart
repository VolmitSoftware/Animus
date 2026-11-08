/// The completion listbox: one row per suggestion with its kind, label,
/// type and a one-line description, colour rows with a swatch. Rows take
/// the pointer on mousedown so the field keeps focus and its caret.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;
import 'package:web/web.dart' as web;

import '../../logic/suggest/completion_item.dart';

/// The element id of row [index] of the list [listId], for
/// `aria-activedescendant`.
String suggestOptionId(String listId, int index) => '$listId-$index';

/// The list's tallest, in px (its CSS `max-height`, 16rem).
const double suggestListHeight = 256;

class SuggestList extends StatelessWidget {
  const SuggestList({
    required this.id,
    required this.items,
    required this.active,
    required this.onPick,
    required this.onHover,
    this.title = 'Suggestions',
    this.classes,
    this.styles,
    super.key,
  });

  final String id;
  final List<CompletionItem> items;
  final int active;
  final void Function(int index) onPick;
  final void Function(int index) onHover;

  /// The listbox's accessible name.
  final String title;
  final String? classes;
  final dom.Styles? styles;

  @override
  Widget build(BuildContext context) {
    return dom.ul(
      id: id,
      classes: 'animus-suggest-list${classes == null ? '' : ' $classes'}',
      styles: styles,
      attributes: <String, String>{'role': 'listbox', 'aria-label': title},
      <Widget>[
        for (int index = 0; index < items.length; index++)
          dom.li(
            id: suggestOptionId(id, index),
            classes: index == active ? 'is-active' : null,
            attributes: <String, String>{'role': 'option', 'aria-selected': '${index == active}'},
            events: <String, void Function(web.Event)>{
              'mousedown': (web.Event event) {
                event.preventDefault();
                onPick(index);
              },
              'mousemove': (web.Event _) {
                if (index != active) onHover(index);
              },
            },
            _row(items[index]),
          ),
      ],
    );
  }

  List<Widget> _row(CompletionItem item) => <Widget>[
        dom.span(classes: 'animus-suggest-kind is-${item.kind.name}', <Widget>[Component.text(item.kind.short)]),
        if (item.swatch != null)
          dom.span(
            classes: 'animus-suggest-swatch',
            styles: dom.Styles(raw: <String, String>{'background': item.swatch!}),
            attributes: const <String, String>{'aria-hidden': 'true'},
            const <Widget>[],
          ),
        dom.code(classes: 'animus-suggest-label', <Widget>[Component.text(item.label)]),
        if (item.type.isNotEmpty) dom.span(classes: 'animus-suggest-type', <Widget>[Component.text(item.type)]),
        if (item.detail.isNotEmpty) dom.span(classes: 'animus-suggest-detail', <Widget>[Component.text(item.detail)]),
      ];
}
