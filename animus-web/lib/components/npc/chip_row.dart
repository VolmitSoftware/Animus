/// A compact row of chips for a short list of ids: each chip opens its
/// document when it can, has a remove button, and with [ChipRow.onMove]
/// reorders by drag or by Alt+Left and Alt+Right.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;
import 'package:web/web.dart' as web;

import '../gui/gui_fields.dart';

class ChipItem {
  const ChipItem(this.id, {this.label, this.onOpen, this.problem});

  final String id;

  /// What the chip reads; [id] when null.
  final String? label;

  /// Opens the document; null shows the label as plain text.
  final void Function()? onOpen;

  /// A warning the chip carries, such as a group the workspace lacks.
  final String? problem;
}

class ChipRow extends StatefulWidget {
  const ChipRow({required this.items, required this.noun, this.onRemove, this.onMove, this.attribute = 'chip', super.key});

  final List<ChipItem> items;

  /// Names an item in button labels: "Remove group guards".
  final String noun;

  /// Removes the item at an index; null hides the remove buttons.
  final void Function(int index)? onRemove;

  /// Moves the item at `from` to `to`; null makes the row fixed.
  final void Function(int from, int to)? onMove;

  /// The `data-` attribute each chip carries its id in.
  final String attribute;

  @override
  State<ChipRow> createState() => _ChipRowState();
}

class _ChipRowState extends State<ChipRow> {
  int? _dragging;

  void _move(int from, int to) {
    final void Function(int, int)? move = component.onMove;
    if (move == null || to < 0 || to >= component.items.length || from == to) return;
    move(from, to);
  }

  void _key(web.Event raw, int index) {
    final web.KeyboardEvent event = raw as web.KeyboardEvent;
    if (!event.altKey || (event.key != 'ArrowLeft' && event.key != 'ArrowRight')) return;
    event.preventDefault();
    _move(index, event.key == 'ArrowLeft' ? index - 1 : index + 1);
  }

  @override
  Widget build(BuildContext context) {
    final bool movable = component.onMove != null && component.items.length > 1;
    return dom.ul(classes: 'animus-chip-row', <Widget>[
      for (int index = 0; index < component.items.length; index++) _chip(component.items[index], index, movable),
    ]);
  }

  Widget _chip(ChipItem item, int index, bool movable) {
    final String text = item.label ?? item.id;
    final Map<String, String> keys = <String, String>{if (movable) 'aria-keyshortcuts': 'Alt+ArrowLeft Alt+ArrowRight'};
    final Map<String, void Function(web.Event)> keyEvents = <String, void Function(web.Event)>{if (movable) 'keydown': (web.Event event) => _key(event, index)};
    return dom.li(
      key: ValueKey<String>('chip:${item.id}'),
      classes: 'animus-npc-tag animus-chip${item.problem != null ? ' is-warning' : ''}${_dragging == index ? ' is-dragging' : ''}',
      attributes: <String, String>{
        'data-${component.attribute}': item.id,
        if (movable) 'draggable': 'true',
        'title': ?item.problem,
      },
      events: movable
          ? <String, void Function(web.Event)>{
              'dragstart': (web.Event event) {
                (event as web.DragEvent).dataTransfer?.setData('text/plain', item.id);
                setState(() => _dragging = index);
              },
              'dragover': (web.Event event) => event.preventDefault(),
              'drop': (web.Event event) {
                event.preventDefault();
                final int? from = _dragging;
                setState(() => _dragging = null);
                if (from != null) _move(from, index);
              },
              'dragend': (web.Event _) => setState(() => _dragging = null),
            }
          : null,
      <Widget>[
        if (item.onOpen != null)
          dom.button(
            classes: 'animus-chip-label',
            attributes: <String, String>{'type': 'button', 'aria-label': 'Open ${component.noun} $text', ...keys},
            events: <String, void Function(web.Event)>{'click': (web.Event _) => item.onOpen!(), ...keyEvents},
            <Widget>[Component.text(text)],
          )
        else
          dom.span(
            classes: 'animus-chip-label',
            attributes: <String, String>{if (movable) 'tabindex': '0', if (movable) 'aria-label': '${component.noun} $text', ...keys},
            events: keyEvents,
            <Widget>[Component.text(text)],
          ),
        if (component.onRemove != null)
          GuiIconButton(
            icon: ArcaneIcon.x(size: IconSize.sm),
            label: 'Remove ${component.noun} $text',
            onPressed: () => component.onRemove!(index),
          ),
      ],
    );
  }
}
