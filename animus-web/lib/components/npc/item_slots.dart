/// Item slots drawn like an in-game inventory: a grid of slots with item
/// icons and stack counts, and the selected slot's editor below it. Click,
/// Enter or Space selects a slot and focuses its item field, arrow keys move
/// the selection, a right click or Delete empties a slot, and dragging a
/// filled slot onto another trades their items. Each edit writes once.
library;

import 'dart:js_interop';
import 'dart:js_interop_unsafe';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;
import 'package:web/web.dart' as web;

import '../../config/docs_links.dart';
import '../../logic/gui/gui_json.dart';
import '../../logic/minecraft_ids.dart';
import '../../logic/npc/item_slots.dart';
import '../gui/gui_fields.dart';
import '../info_tip.dart';
import '../mc/item_picker.dart';
import '../mc/mc_item_icons.dart';

/// A problem shown on a slot: `error` or `warning`, and its text.
typedef SlotProblem = ({String severity, String message});

/// One slot of an [ItemSlots] grid.
final class ItemSlotCell {
  const ItemSlotCell({
    required this.key,
    required this.column,
    required this.row,
    required this.name,
    required this.fieldLabel,
    this.item,
    this.emptyText,
    this.placeholder,
    this.preferred = const <String>[],
    this.help,
    this.problems = const <SlotProblem>[],
  });

  /// The slot's key in the stored object, also its `data-slot`.
  final String key;
  final int column;
  final int row;

  /// "Slot 3", "Head".
  final String name;

  /// The item field's accessible name.
  final String fieldLabel;
  final JsonMap? item;

  /// Faint text in the empty slot, such as its name.
  final String? emptyText;
  final String? placeholder;
  final List<String> preferred;
  final String? help;
  final List<SlotProblem> problems;

  String get id => asString(item?['id']) ?? '';

  bool get filled => id.trim().isNotEmpty;

  /// `error`, `warning` or null: the worst of [problems].
  String? get severity => problems.any((SlotProblem problem) => problem.severity == 'error')
      ? 'error'
      : problems.isEmpty
      ? null
      : 'warning';
}

class ItemSlots extends StatefulWidget {
  const ItemSlots({
    required this.id,
    required this.label,
    required this.cells,
    required this.onPut,
    required this.onMove,
    this.tip,
    this.components = false,
    this.selectAs,
    super.key,
  });

  /// Prefix of the element ids.
  final String id;

  /// The grid's accessible name.
  final String label;
  final List<ItemSlotCell> cells;

  /// The new item for a slot, null to empty it.
  final void Function(String key, JsonMap? item) onPut;

  /// Trades the items of two slots.
  final void Function(String from, String to) onMove;

  /// The tip on the editor's slot name.
  final String? tip;

  /// Offers the `components` text.
  final bool components;

  /// The slot a click or arrow on [key] selects instead, for a list that
  /// fills from the start.
  final String Function(String key)? selectAs;

  @override
  State<ItemSlots> createState() => _ItemSlotsState();
}

class _ItemSlotsState extends State<ItemSlots> {
  String? _selected;
  String? _dragFrom;
  String? _dropOn;
  double _startX = 0, _startY = 0;
  bool _dragging = false;

  /// Set after a drag so the click that ends it does not select.
  bool _dropped = false;
  JSFunction? _release;

  String get _pickerId => '${component.id}-item';

  @override
  void initState() {
    super.initState();
    McItemIcons.addListener(_refresh);
    _release = ((web.Event _) => _endDrag()).toJS;
    web.window.addEventListener('pointerup', _release);
    web.window.addEventListener('pointercancel', _release);
  }

  @override
  void dispose() {
    McItemIcons.removeListener(_refresh);
    web.window.removeEventListener('pointerup', _release);
    web.window.removeEventListener('pointercancel', _release);
    super.dispose();
  }

  void _refresh() {
    if (mounted) setState(() {});
  }

  void _endDrag() {
    final String? from = _dragFrom, to = _dropOn;
    final bool dragging = _dragging;
    _dragFrom = null;
    _dragging = false;
    if (!dragging) return;
    _dropped = true;
    if (mounted) setState(() => _dropOn = null);
    if (from != null && to != null && from != to) {
      component.onMove(from, to);
      // A list that fills from the start puts the item elsewhere.
      if (mounted) setState(() => _selected = component.selectAs == null ? to : null);
    }
  }

  static double _number(web.Event event, String property) {
    final JSAny? value = (event as JSObject).getProperty<JSAny?>(property.toJS);
    return value != null && value.isA<JSNumber>() ? (value as JSNumber).toDartDouble : 0;
  }

  /// The slot of this grid under the pointer, for drags that leave the
  /// pressed button (touch keeps the pointer captured on it).
  String? _slotAt(web.Event event) {
    final web.Element? hit = web.document.elementFromPoint(_number(event, 'clientX'), _number(event, 'clientY'));
    final web.Element? slot = hit?.closest('[data-slot-grid="${component.id}"] [data-slot]');
    return slot?.getAttribute('data-slot');
  }

  void _select(String picked, {required bool edit}) {
    final String key = component.selectAs?.call(picked) ?? picked;
    setState(() => _selected = key);
    if (edit) {
      web.window.requestAnimationFrame(((JSNumber _) => (web.document.getElementById(_pickerId) as web.HTMLElement?)?.focus()).toJS);
    } else {
      (web.document.querySelector('[data-slot-grid="${component.id}"] [data-slot="$key"]') as web.HTMLElement?)?.focus();
    }
  }

  void _key(ItemSlotCell cell, web.Event event) {
    final String key = (event as web.KeyboardEvent).key;
    final List<ItemSlotCell> cells = component.cells;
    final (int, int)? step = switch (key) {
      'ArrowLeft' => (-1, 0),
      'ArrowRight' => (1, 0),
      'ArrowUp' => (0, -1),
      'ArrowDown' => (0, 1),
      _ => null,
    };
    if (step != null) {
      event.preventDefault();
      final int? next = gridNeighbor(<(int, int)>[for (final ItemSlotCell each in cells) (each.column, each.row)], cells.indexOf(cell), step.$1, step.$2);
      if (next != null) _select(cells[next].key, edit: false);
    } else if (key == 'Delete' || key == 'Backspace') {
      event.preventDefault();
      if (cell.filled) component.onPut(cell.key, null);
    }
  }

  @override
  Widget build(BuildContext context) {
    final List<ItemSlotCell> cells = component.cells;
    final ItemSlotCell? selected = cells.where((ItemSlotCell cell) => cell.key == _selected).firstOrNull;
    final int columns = cells.fold(1, (int most, ItemSlotCell cell) => cell.column + 1 > most ? cell.column + 1 : most);
    final String focusable = selected?.key ?? cells.firstOrNull?.key ?? '';
    return dom.div(classes: 'animus-item-slots', <Widget>[
      dom.div(
        classes: 'animus-chest-screen animus-item-slots-screen',
        attributes: <String, String>{'role': 'group', 'aria-label': component.label},
        <Widget>[
          dom.div(
            classes: 'animus-chest animus-item-slots-grid',
            styles: dom.Styles(raw: <String, String>{'grid-template-columns': 'repeat($columns, var(--animus-slot))'}),
            attributes: <String, String>{'data-slot-grid': component.id},
            events: <String, void Function(web.Event)>{
              'pointermove': (web.Event event) {
                if (_dragFrom == null || (_number(event, 'buttons').toInt() & 1) == 0) return;
                if (!_dragging) {
                  final double dx = _number(event, 'clientX') - _startX, dy = _number(event, 'clientY') - _startY;
                  if (dx * dx + dy * dy < 16) return;
                  _dragging = true;
                }
                final String? over = _slotAt(event);
                if (over != _dropOn) setState(() => _dropOn = over);
              },
            },
            <Widget>[for (final ItemSlotCell cell in cells) _slot(cell, focusable)],
          ),
        ],
      ),
      if (selected != null) _editor(selected),
    ]);
  }

  Widget _slot(ItemSlotCell cell, String focusable) {
    final String id = cell.id;
    final String? key = McItemIcons.keyOf(id);
    final String? url = key == null ? null : McItemIcons.url(key);
    final int count = cell.item == null ? 1 : itemCount(cell.item!);
    final bool selected = cell.key == _selected;
    final String described = '${cell.name}, ${cell.filled ? '$id${count > 1 ? ', $count' : ''}' : 'empty'}';
    final String? severity = cell.severity;
    final List<String> classes = <String>[
      'animus-chest-slot',
      'animus-item-slot',
      if (cell.filled) 'is-filled',
      if (selected) 'is-selected',
      if (severity != null) 'is-$severity',
      if (_dragging && cell.key == _dragFrom) 'is-dragging',
      if (_dragging && cell.key == _dropOn && _dropOn != _dragFrom) 'is-drop-target',
    ];
    return dom.button(
      key: ValueKey<String>('slot:${cell.key}'),
      classes: classes.join(' '),
      styles: dom.Styles(raw: <String, String>{'grid-column': '${cell.column + 1}', 'grid-row': '${cell.row + 1}'}),
      attributes: <String, String>{
        'type': 'button',
        'aria-label': described,
        'aria-pressed': '$selected',
        'title': described,
        'data-slot': cell.key,
        'tabindex': cell.key == focusable ? '0' : '-1',
      },
      events: <String, void Function(web.Event)>{
        'click': (web.Event _) {
          if (_dropped) {
            _dropped = false;
            return;
          }
          _select(cell.key, edit: true);
        },
        'keydown': (web.Event event) => _key(cell, event),
        'contextmenu': (web.Event event) {
          event.preventDefault();
          if (cell.filled) component.onPut(cell.key, null);
        },
        'pointerdown': (web.Event event) {
          _dropped = false;
          if (_number(event, 'button') != 0 || !cell.filled) return;
          _dragFrom = cell.key;
          _dragging = false;
          _startX = _number(event, 'clientX');
          _startY = _number(event, 'clientY');
        },
      },
      <Widget>[
        dom.span(classes: 'animus-chest-face', <Widget>[
          if (url != null)
            dom.img(src: url, alt: '', attributes: const <String, String>{'draggable': 'false'})
          else if (cell.filled)
            dom.span(classes: 'animus-chest-missing', <Widget>[Component.text(_short(id))])
          else if (cell.emptyText != null)
            dom.span(classes: 'animus-item-slot-name', <Widget>[Component.text(cell.emptyText!)]),
          if (cell.filled && count > 1) dom.span(classes: 'animus-chest-count', <Widget>[Component.text('$count')]),
        ]),
      ],
    );
  }

  Widget _editor(ItemSlotCell cell) {
    final JsonMap item = cell.item ?? const <String, Object?>{};
    final String id = cell.id;
    final int count = itemCount(item);
    void put(JsonMap next) => component.onPut(cell.key, next);
    return dom.div(
      key: ValueKey<String>('editor:${cell.key}'),
      classes: 'animus-item-slot-editor',
      attributes: <String, String>{'role': 'group', 'aria-label': cell.name, 'data-editing': cell.key},
      <Widget>[
        dom.div(classes: 'animus-item-slot-head', <Widget>[
          dom.strong(<Widget>[withTip(Component.text(cell.name), component.tip, docs: Docs.npcAppearance)]),
          if (cell.help != null) dom.span(classes: 'animus-gui-help', <Widget>[Component.text(cell.help!)]),
        ]),
        ItemPicker(
          id: _pickerId,
          value: id,
          label: cell.fieldLabel,
          placeholder: cell.placeholder ?? 'minecraft:stone',
          preferred: cell.preferred,
          onChanged: (String text) => text.trim().isEmpty ? component.onPut(cell.key, null) : put(<String, Object?>{...item, 'id': text.trim()}),
        ),
        if (cell.filled)
          dom.div(classes: 'animus-item-slot-line', <Widget>[
            dom.div(classes: 'animus-item-slot-count', attributes: const <String, String>{'role': 'group', 'aria-label': 'Count'}, <Widget>[
              GuiIconButton(icon: ArcaneIcon.minus(size: IconSize.sm), label: 'One fewer', disabled: count <= 1, onPressed: () => put(withCount(item, count - 1))),
              GuiNumberInput(
                id: '${component.id}-count',
                value: count,
                label: '${cell.fieldLabel} count',
                digits: 2,
                onChanged: (num? next) {
                  if (next != null) put(withCount(item, next.toInt()));
                },
              ),
              GuiIconButton(icon: ArcaneIcon.plus(size: IconSize.sm), label: 'One more', disabled: count >= maxStack, onPressed: () => put(withCount(item, count + 1))),
            ]),
            Button.ghost(label: 'Clear', size: ButtonSize.small, onPressed: () => component.onPut(cell.key, null)),
          ]),
        if (cell.filled && component.components)
          GuiTextInput(
            value: asString(item['components']) ?? '',
            label: '${cell.fieldLabel}.components',
            placeholder: '[enchantment_glint_override=true]',
            onChanged: (String text) {
              final JsonMap next = <String, Object?>{...item};
              if (text.trim().isEmpty) {
                next.remove('components');
              } else {
                next['components'] = text.trim();
              }
              put(next);
            },
          ),
        if (id.isNotEmpty && !validMinecraftId(id))
          const dom.span(classes: 'animus-gui-problem is-error', <Widget>[Component.text('Use an item id such as iron_sword or minecraft:iron_sword.')]),
        for (final SlotProblem problem in cell.problems)
          dom.span(classes: 'animus-gui-problem is-${problem.severity}', <Widget>[Component.text(problem.message)]),
      ],
    );
  }

  static String _short(String item) {
    final String bare = item.substring(item.indexOf(':') + 1).replaceAll('_', ' ');
    return bare.length > 14 ? '${bare.substring(0, 13)}.' : bare;
  }
}
