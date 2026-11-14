/// The one item field: the item's inventory icon in the box, and a search
/// list over every item of the bundled registry (`registries.json`,
/// fetched once per session), each row with its icon, plain name and id.
/// The list draws only the rows in view, so all 1,400 items scroll
/// smoothly and only the icons in sight are rendered. A row is taken with
/// Enter or a click. Advanced shows the id and writes what is typed. Simple
/// shows the item's name, and typed words only search: they are written
/// once a row is taken, or on leaving the field when they name an item. An
/// id or an expression (modded items, placeholders) is written as typed in
/// either mode.
library;

import 'dart:convert';
import 'dart:js_interop';
import 'dart:math' as math;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;
import 'package:web/web.dart' as web;

import '../../logic/inspect/schema_fields.dart';
import '../../logic/item_ids.dart';
import '../../mc/assets/mc_pack_manifest.dart';
import '../../ui/density.dart';
import '../../ui/mode.dart';
import '../../ui/stack.dart';
import '../gui/gui_stack.dart' show blockTruncated;
import '../select/select_popup.dart' show PopupPlacement, placePopup, watchOutside;
import 'mc_asset_loader.dart';
import 'mc_item_icons.dart';

/// The bundled item registry, loaded on first use; empty when the pack has none.
final class ItemRegistry {
  ItemRegistry._();

  static List<String> _ids = const <String>[];
  static Set<String> _known = const <String>{};
  static Future<List<String>>? _loading;

  static List<String> get ids => _ids;

  static bool has(String id) => _known.contains(id);

  static Future<List<String>> load() => _loading ??= _fetch().then((List<String> ids) {
    _known = ids.toSet();
    return _ids = ids;
  });

  static Future<List<String>> _fetch() async {
    try {
      final web.Response response = await web.window.fetch('${mcPackBaseUrl(McAssetLoader.version)}/registries.json'.toJS).toDart;
      if (!response.ok) return const <String>[];
      return parseItemRegistry(jsonDecode((await response.text().toDart).toDart));
    } on Object {
      return const <String>[];
    }
  }
}

/// Rows of the list, in px; the list shows [_visibleRows] at once.
const double _rowHeight = 32;
const int _visibleRows = 9;

int _nextPickerId = 0;

class ItemPicker extends StatefulWidget {
  const ItemPicker({
    required this.value,
    required this.onChanged,
    required this.label,
    this.id,
    this.placeholder = 'minecraft:stone',
    this.preferred = const <String>[],
    this.icon = true,
    super.key,
  });

  final String value;
  final void Function(String value) onChanged;

  /// The field's accessible name.
  final String label;
  final String? id;
  final String placeholder;

  /// Ids offered before the registry, such as armour for a helmet slot.
  final List<String> preferred;

  /// False where the row draws the icon itself.
  final bool icon;

  @override
  State<ItemPicker> createState() => _ItemPickerState();
}

class _ItemPickerState extends State<ItemPicker> {
  late final String _baseId = component.id ?? 'animus-item-${_nextPickerId++}';
  bool _open = false;
  int _active = -1;
  int _first = 0;
  String? _typed;
  PopupPlacement _placement = const PopupPlacement(sheet: false);
  void Function()? _stopWatching;

  /// The search the list was last built for, and its rows.
  String? _builtFor;
  List<String> _rows = const <String>[];

  String get _rootId => '$_baseId-root';
  String get _popupId => '$_baseId-popup';
  String get _listId => '$_baseId-listbox';
  String get _scrollId => '$_baseId-scroll';

  @override
  void initState() {
    super.initState();
    McItemIcons.addListener(_refresh);
    if (ItemRegistry.ids.isEmpty) {
      ItemRegistry.load().then((List<String> _) {
        _builtFor = null;
        _refresh();
      });
    }
  }

  @override
  void dispose() {
    McItemIcons.removeListener(_refresh);
    _stopWatching?.call();
    super.dispose();
  }

  void _refresh() {
    if (mounted) setState(() {});
  }

  /// Every matching id: the typed search narrows it; an id taken from the
  /// list shows the whole list again.
  List<String> get _matches {
    final String text = _typed ?? '';
    final String query = ItemRegistry.has(text) ? '' : text;
    if (_builtFor != query) {
      _builtFor = query;
      _rows = <String>[
        for (final FieldOption option in itemOptions(ItemRegistry.ids, query, preferred: component.preferred, limit: 1 << 20)) option.label,
      ];
    }
    return _rows;
  }

  void _show(bool open, {int active = -1}) {
    if (open && !_open) {
      _placement = placePopup(_baseId, _visibleRows * _rowHeight + 10, sheet: false);
      _stopWatching = watchOutside(
        _rootId,
        _popupId,
        _baseId,
        () {
          if (mounted && _open) _show(false);
        },
        wanted: _visibleRows * _rowHeight + 10,
        sheet: false,
        sheetOnPhones: false,
        onMove: (PopupPlacement moved) {
          if (mounted && _open) setState(() => _placement = moved);
        },
      );
    }
    if (!open) {
      _stopWatching?.call();
      _stopWatching = null;
    }
    setState(() {
      _open = open;
      _active = active;
      if (!open) _first = 0;
    });
    if (open && active >= 0) _reveal(active);
  }

  /// Scrolls the list so row [index] is in view.
  void _reveal(int index) {
    web.window.requestAnimationFrame(((JSNumber _) {
      final web.Element? scroll = web.document.getElementById(_scrollId);
      if (scroll == null) return;
      final double top = index * _rowHeight;
      final double height = scroll.clientHeight.toDouble();
      if (top < scroll.scrollTop) {
        scroll.scrollTop = top;
      } else if (top + _rowHeight > scroll.scrollTop + height) {
        scroll.scrollTop = top + _rowHeight - height;
      }
    }).toJS);
  }

  void _take(String id) {
    _typed = null;
    _show(false);
    component.onChanged(id);
  }

  void _key(web.Event raw) {
    final web.KeyboardEvent event = raw as web.KeyboardEvent;
    final List<String> rows = _matches;
    switch (event.key) {
      case 'ArrowDown' || 'ArrowUp':
        event.preventDefault();
        if (rows.isEmpty) return;
        final int delta = event.key == 'ArrowDown' ? 1 : -1;
        final int next = !_open || _active < 0 ? (delta > 0 ? 0 : rows.length - 1) : (_active + delta).clamp(0, rows.length - 1);
        _show(true, active: next);
      case 'PageDown' || 'PageUp':
        if (!_open || rows.isEmpty) return;
        event.preventDefault();
        _show(true, active: (_active + (event.key == 'PageDown' ? _visibleRows : -_visibleRows)).clamp(0, rows.length - 1));
      case 'Enter':
        if (!_open || _active < 0 || _active >= rows.length) return;
        event.preventDefault();
        _take(rows[_active]);
      case 'Escape':
        if (!_open) return;
        event.preventDefault();
        event.stopPropagation();
        _show(false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final String value = component.value;
    final String? key = McItemIcons.keyOf(value);
    final String? icon = component.icon && key != null ? McItemIcons.url(key) : null;
    final bool unknown = !ItemRegistry.has(value) && ItemRegistry.ids.isNotEmpty && value.startsWith('minecraft:') && isItemId(value);
    final List<String> rows = _open ? _matches : const <String>[];
    final bool listed = _open && rows.isNotEmpty;
    final bool dense = DensityScope.of(context) == Density.dense;
    final bool advanced = ModeScope.advanced(context);
    return dom.div(id: _rootId, classes: 'animus-item-picker${dense ? ' ui-dense' : ''}', <Widget>[
      Row(gapSize: Gap.sm, children: <Widget>[
        // The item drawn in a game slot as tall as the field.
        if (component.icon)
          dom.span(classes: 'animus-item-picker-icon', attributes: const <String, String>{'aria-hidden': 'true'}, <Widget>[
            dom.span(classes: 'animus-chest-face', <Widget>[
              if (icon != null) dom.img(src: icon, alt: '') else if (value.isNotEmpty && key != null) const dom.span(classes: 'animus-chest-missing', <Widget>[Component.text('?')]),
            ]),
          ]),
        Expanded(
          child: dom.input(
        id: _baseId,
        classes: 'arcane-text-input',
        styles: rowControlHeight,
        value: _typed ?? itemFieldText(value, advanced: advanced),
        attributes: <String, String>{
          'type': 'text',
          'autocomplete': 'off',
          'spellcheck': 'false',
          'aria-label': component.label,
          'placeholder': itemFieldText(component.placeholder, advanced: advanced),
          'role': 'combobox',
          'aria-autocomplete': 'list',
          'aria-expanded': '$listed',
          'aria-controls': _listId,
          if (listed && _active >= 0) 'aria-activedescendant': '$_baseId-row-$_active',
        },
        events: <String, void Function(web.Event)>{
          'input': (web.Event event) {
            final String text = (event.target! as web.HTMLInputElement).value;
            _typed = text;
            if (advanced || !itemSearchOnly(text)) component.onChanged(text);
            _show(true, active: text.isEmpty ? -1 : 0);
          },
          'focus': (web.Event _) {
            if (advanced) _typed = value;
          },
          'click': (web.Event event) {
            // Simple selects the name, so typing starts a new search.
            if (!advanced && _typed == null) (event.target! as web.HTMLInputElement).select();
            if (!_open) _show(true, active: _matches.indexOf(value));
          },
          'keydown': _key,
          'blur': (web.Event _) {
            if (!mounted) return;
            final String? typed = _typed;
            if (_open) _show(false);
            if (!advanced && typed != null && itemSearchOnly(typed)) {
              final String? named = itemNamed(typed, <String>[...component.preferred, ...ItemRegistry.ids]);
              if (named != null && named != value) component.onChanged(named);
            }
            setState(() => _typed = null);
          },
        },
          ),
        ),
      ]),
      if (unknown) mutedText('Not a 26.3 item.', small: true),
      if (listed) _popup(rows),
    ]);
  }

  Widget _popup(List<String> rows) {
    final int shown = math.min(rows.length, _visibleRows);
    final int first = math.max(0, math.min(_first, rows.length - shown));
    final int last = math.min(rows.length, first + shown + 4);
    return dom.div(
      id: _popupId,
      classes: 'animus-select-popup${_placement.classes}',
      styles: dom.Styles(raw: _placement.styles),
      events: <String, void Function(web.Event)>{
        // Keep focus in the field while a row is pressed.
        'pointerdown': (web.Event event) => event.preventDefault(),
      },
      <Widget>[
        dom.div(
          id: _scrollId,
          classes: 'animus-select-scroll',
          styles: dom.Styles(raw: <String, String>{'height': '${shown * _rowHeight + 8}px'}),
          events: <String, void Function(web.Event)>{
            'scroll': (web.Event event) {
              final int top = ((event.target! as web.Element).scrollTop / _rowHeight).floor();
              if (top != _first) setState(() => _first = top);
            },
          },
          <Widget>[
            dom.div(
              id: _listId,
              styles: dom.Styles(raw: <String, String>{'position': 'relative', 'height': '${rows.length * _rowHeight}px'}),
              attributes: <String, String>{'role': 'listbox', 'aria-label': 'Items for ${component.label}'},
              <Widget>[
                for (int index = first; index < last; index++) _row(rows[index], index, rows.length),
              ],
            ),
          ],
        ),
      ],
    );
  }

  Widget _row(String id, int index, int count) {
    final String? key = McItemIcons.keyOf(id);
    final String? url = key == null ? null : McItemIcons.url(key);
    final bool vanilla = id.startsWith('minecraft:');
    return dom.div(
      id: '$_baseId-row-$index',
      classes: 'animus-select-option${index == _active ? ' is-active' : ''}${id == component.value ? ' is-selected' : ''}',
      // Only the rows in view are drawn, each at its place in the full list.
      styles: dom.Styles(raw: <String, String>{'position': 'absolute', 'inset-inline': '0', 'top': '${index * _rowHeight}px', 'height': '${_rowHeight}px'}),
      attributes: <String, String>{
        'role': 'option',
        'aria-selected': '${id == component.value}',
        'aria-setsize': '$count',
        'aria-posinset': '${index + 1}',
        'data-value': id,
      },
      events: <String, void Function(web.Event)>{
        'click': (web.Event _) => _take(id),
        'pointermove': (web.Event _) {
          if (_active != index) setState(() => _active = index);
        },
      },
      <Widget>[
        if (url != null)
          dom.img(src: url, alt: '', styles: const dom.Styles(raw: <String, String>{'image-rendering': 'pixelated'}), attributes: const <String, String>{'loading': 'lazy', 'width': '20', 'height': '20'})
        else
          const SizedBox(width: 20, height: 20),
        blockTruncated(itemName(id)),
        if (!vanilla) ...<Widget>[const Spacer(), Text(id, size: FontSize.xs, color: TextColor.muted)],
        if (id == component.value) ...<Widget>[if (vanilla) const Spacer(), ArcaneIcon.check(size: IconSize.sm)],
      ],
    );
  }
}
