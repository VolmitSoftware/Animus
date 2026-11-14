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
import '../../logic/item_ids.dart';
import '../../logic/minecraft_ids.dart';
import '../../logic/npc/item_slots.dart';
import '../../model/server_binding.dart' show jsonEquals;
import '../info_tip.dart';
import '../mc/item_picker.dart';
import '../mc/mc_item_icons.dart';
import '../../logic/suggest/field_profile.dart';
import '../suggest/autocomplete_input.dart';
import '../../ui/number_input.dart';
import '../../ui/icon_button.dart';
import 'npc_layout.dart';
import '../../ui/action_row.dart';
import '../../ui/mode.dart';
import '../../ui/stack.dart';
import '../../ui/problem_text.dart';
import '../../model/animus_document.dart';

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
    this.captioned = false,
    this.selectAs,
    super.key,
  });

  /// Prefix of the element ids.
  final String id;

  /// The grid's accessible name.
  final String label;
  final List<ItemSlotCell> cells;

  /// The new item for a slot (null empties it), and the slot's item as this
  /// editor saw it: its own last write while [cells] do not show that yet.
  final void Function(String key, JsonMap? item, JsonMap? seen) onPut;

  /// Trades the items of two slots.
  final void Function(String from, String to) onMove;

  /// The tip on the editor's slot name.
  final String? tip;

  /// Offers the `components` text.
  final bool components;

  /// Each slot with its name beside it on the page, without the game
  /// window (equipment, whose few slots name a body part each).
  final bool captioned;

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

  /// Items this editor put in each slot that [ItemSlots.cells] do not show
  /// yet, oldest first. Until they do, the next edit starts from the newest
  /// and passes it on as what was seen, so typing back to the item the page
  /// still shows is written too.
  final Map<String, List<JsonMap?>> _ahead = <String, List<JsonMap?>>{};

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

  @override
  void didUpdateWidget(ItemSlots oldComponent) {
    super.didUpdateWidget(oldComponent);
    for (final String key in _ahead.keys.toList()) {
      final List<JsonMap?> ahead = _ahead[key]!;
      final JsonMap? now = _itemOf(component, key);
      final int shown = ahead.lastIndexWhere((JsonMap? item) => jsonEquals(item, now));
      if (shown >= 0) {
        ahead.removeRange(0, shown + 1);
        if (ahead.isEmpty) _ahead.remove(key);
      } else if (!jsonEquals(now, _itemOf(oldComponent, key))) {
        // Changed by something else, such as Undo.
        _ahead.remove(key);
      }
    }
  }

  static JsonMap? _itemOf(ItemSlots slots, String key) => slots.cells.where((ItemSlotCell cell) => cell.key == key).firstOrNull?.item;

  /// The slot's item as this editor last saw or wrote it.
  JsonMap? _seen(ItemSlotCell cell) {
    final List<JsonMap?>? ahead = _ahead[cell.key];
    return ahead == null ? cell.item : ahead.last;
  }

  void _put(ItemSlotCell cell, JsonMap? item) {
    final JsonMap? seen = _seen(cell);
    (_ahead[cell.key] ??= <JsonMap?>[]).add(item);
    component.onPut(cell.key, item, seen);
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
      if (cell.filled) _put(cell, null);
    }
  }

  /// The window (centred), then the picked slot's editor. A size
  /// container, so the slots share its width (a chest row of 9 fits a
  /// phone).
  static const dom.Styles _slotsStyles = dom.Styles(raw: <String, String>{
    'display': 'grid',
    'grid-template-columns': 'minmax(0, 1fr)',
    'gap': '0.5rem',
    'min-width': '0',
    'container-type': 'inline-size',
  });

  @override
  Widget build(BuildContext context) {
    final List<ItemSlotCell> cells = component.cells;
    final ItemSlotCell? selected = cells.where((ItemSlotCell cell) => cell.key == _selected).firstOrNull;
    final int columns = cells.fold(1, (int most, ItemSlotCell cell) => cell.column + 1 > most ? cell.column + 1 : most);
    final String focusable = selected?.key ?? cells.firstOrNull?.key ?? '';
    final bool captioned = component.captioned;
    return dom.div(styles: _slotsStyles, <Widget>[
      dom.div(
        classes: captioned ? 'animus-item-slots-captioned' : 'animus-chest-screen animus-item-slots-screen',
        attributes: <String, String>{'role': 'group', 'aria-label': component.label},
        <Widget>[
          dom.div(
            classes: captioned ? 'animus-item-slots-grid' : 'animus-chest animus-item-slots-grid',
            styles: dom.Styles(raw: <String, String>{'grid-template-columns': 'repeat($columns, ${captioned ? 'max-content' : 'var(--animus-slot)'})'}),
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
            <Widget>[
              for (final ItemSlotCell cell in cells)
                if (captioned)
                  dom.div(
                    key: ValueKey<String>('pair:${cell.key}'),
                    classes: 'animus-item-slot-pair',
                    styles: _place(cell),
                    <Widget>[_slot(cell, focusable), dom.span(attributes: const <String, String>{'aria-hidden': 'true'}, <Widget>[Component.text(cell.name)])],
                  )
                else
                  _slot(cell, focusable, placed: true),
            ],
          ),
        ],
      ),
      if (selected != null) _editor(selected),
    ]);
  }

  static dom.Styles _place(ItemSlotCell cell) => dom.Styles(raw: <String, String>{'grid-column': '${cell.column + 1}', 'grid-row': '${cell.row + 1}'});

  Widget _slot(ItemSlotCell cell, String focusable, {bool placed = false}) {
    final String id = cell.id;
    final String? key = McItemIcons.keyOf(id);
    final String? url = key == null ? null : McItemIcons.url(key);
    final int count = cell.item == null ? 1 : itemCount(cell.item!);
    final bool selected = cell.key == _selected;
    final String named = itemFieldText(id, advanced: ModeScope.advanced(context));
    final String described = '${cell.name}, ${cell.filled ? '$named${count > 1 ? ', $count' : ''}' : 'empty'}';
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
      styles: placed ? _place(cell) : null,
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
          if (cell.filled) _put(cell, null);
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
            dom.span(classes: 'animus-chest-missing', <Widget>[Component.text(_short(id))]),
          if (cell.filled && count > 1) dom.span(classes: 'animus-chest-count', <Widget>[Component.text('$count')]),
        ]),
      ],
    );
  }

  Widget _editor(ItemSlotCell cell) {
    final JsonMap item = _seen(cell) ?? const <String, Object?>{};
    final String id = asString(item['id']) ?? '';
    final bool filled = id.trim().isNotEmpty;
    final int count = itemCount(item);
    void put(JsonMap next) => _put(cell, next);
    return dom.div(
      key: ValueKey<String>('editor:${cell.key}'),
      classes: 'animus-item-slot-editor',
      styles: stackStyles(),
      attributes: <String, String>{'role': 'group', 'aria-label': cell.name, 'data-editing': cell.key},
      <Widget>[
        Wrap(spacing: Gap.sm.value, runSpacing: Gap.xs.value, crossAxisAlignment: WrapCrossAlignment.center, children: <Widget>[
          dom.strong(<Widget>[withTip(Component.text(cell.name), component.tip, docs: Docs.npcAppearance)]),
          if (cell.help != null) mutedText(cell.help!),
        ]),
        ItemPicker(
          id: _pickerId,
          value: id,
          label: cell.fieldLabel,
          placeholder: cell.placeholder ?? 'minecraft:stone',
          preferred: cell.preferred,
          onChanged: (String text) => text.trim().isEmpty ? _put(cell, null) : put(<String, Object?>{...item, 'id': text.trim()}),
        ),
        if (filled)
          ActionRow(dense: true, label: 'Count', leading: <Widget>[
            dom.div(attributes: const <String, String>{'role': 'group', 'aria-label': 'Count'}, <Widget>[
              Row(mainAxisSize: MainAxisSize.min, gapSize: Gap.xs, children: <Widget>[
              AppIconButton(icon: ArcaneIcon.minus(size: IconSize.sm), label: 'One fewer', disabled: count <= 1, onPressed: () => put(withCount(item, count - 1))),
              NumberInput(
                id: '${component.id}-count',
                value: count,
                label: '${cell.fieldLabel} count',
                digits: 2,
                onChanged: (num? next) {
                  if (next != null) put(withCount(item, next.toInt()));
                },
              ),
              AppIconButton(icon: ArcaneIcon.plus(size: IconSize.sm), label: 'One more', disabled: count >= maxStack, onPressed: () => put(withCount(item, count + 1))),
              ]),
            ]),
          ], secondary: <RowAction>[RowAction('Clear', () => _put(cell, null))]),
        if (filled && component.components)
          AutocompleteInput(
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
            profile: FieldProfile.none,
          ),
        if (id.isNotEmpty && !validMinecraftId(id))
          problemText('Use an item id such as iron_sword or minecraft:iron_sword.'),
        for (final SlotProblem problem in cell.problems)
          problemText(problem.message, severity: DiagnosticSeverity.values.byName(problem.severity)),
      ],
    );
  }

  static String _short(String item) {
    final String bare = item.substring(item.indexOf(':') + 1).replaceAll('_', ' ');
    return bare.length > 14 ? '${bare.substring(0, 13)}.' : bare;
  }
}
