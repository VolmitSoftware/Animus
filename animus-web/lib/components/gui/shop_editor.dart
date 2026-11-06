/// The shop editor: pages, the buy, sell and trade grids painted as the
/// shop window with its controls, and an inspector for the shop (modes in
/// order, owner and containers) and for the selected entry (prices, limits
/// with resets, stock, permissions, discounts, trades and command entries).
/// A shop with containers trades items for items, so the editor offers
/// only trade and static entries there, without price or stock. A shop
/// set to the native style also shows its offers as the villager trading
/// screen.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/gui/gui_json.dart';
import '../../logic/gui/gui_layout.dart';
import '../../logic/gui/gui_validation.dart';
import '../../logic/gui/shop_entries.dart';
import '../../logic/gui/shop_offers.dart';
import '../../logic/inspect/schema_fields.dart';
import '../../model/animus_document.dart';
import '../inspect/inspect_fields.dart';
import 'chest_window.dart';
import 'component_editor.dart';
import 'gui_fields.dart';
import 'merchant_window.dart';

const Map<String, String> _modeLabels = <String, String>{'buy': 'Buy', 'sell': 'Sell', 'trade': 'Trade'};

const Map<String, String> _typeLabels = <String, String>{
  'buy': 'buy (the player pays)',
  'command': 'command (runs commands when bought)',
  'sell': 'sell (the shop pays)',
  'trade': 'trade (items for items)',
  'static': 'static (decoration)',
};

const Map<String, String> _styleLabels = <String, String>{'native': 'native (villager trading screen)', 'chest': 'chest (chest window)'};

List<String> _enabledModes(JsonMap shop) {
  final List<String> modes = stringList(shop['modes']);
  return modes.isEmpty ? shopModes : modes;
}

/// The shop's offers as a native shop shows them.
Widget _merchantWindow(AnimusDocument document) {
  final JsonMap shop = asMap(document.json);
  return MerchantWindow(
    title: asString(shop['title']) ?? document.id,
    offers: nativeShopOffers(shop),
    label: 'Villager trading screen ${document.id}',
  );
}

/// The page and mode tabs over a shop window.
class _ShopSwitch extends StatelessWidget {
  const _ShopSwitch({required this.shop, required this.page, required this.mode, required this.onPage, required this.onMode, this.trailing = const <Widget>[]});

  final JsonMap shop;
  final int page;
  final String mode;
  final void Function(int page) onPage;
  final void Function(String mode) onMode;
  final List<Widget> trailing;

  @override
  Widget build(BuildContext context) {
    final JsonList pages = asList(shop['pages']);
    final List<String> modes = _enabledModes(shop);
    return dom.div(classes: 'animus-gui-toolbar', <Widget>[
      if (pages.isNotEmpty)
        GuiTabs(
          label: 'Pages',
          tabs: <String>[for (int index = 0; index < pages.length; index++) asString(asMap(pages[index])['name']) ?? 'Page ${index + 1}'],
          selected: page,
          onSelect: onPage,
        ),
      ...trailing,
      GuiTabs(label: 'Modes', tabs: <String>[for (final String one in modes) _modeLabels[one] ?? one], selected: modes.indexOf(mode), onSelect: (int index) => onMode(modes[index])),
    ]);
  }
}

class ShopPreview extends StatefulWidget {
  const ShopPreview({required this.document, super.key});

  final AnimusDocument document;

  @override
  State<ShopPreview> createState() => _ShopPreviewState();
}

class _ShopPreviewState extends State<ShopPreview> {
  int _page = 0;
  String? _mode;
  int? _slot;

  @override
  Widget build(BuildContext context) {
    final JsonMap shop = asMap(component.document.json);
    final String? style = asString(shop['style']);
    if (style == 'native') {
      return dom.section(classes: 'animus-panel animus-gui-preview', <Widget>[
        _merchantWindow(component.document),
        const dom.p(classes: 'animus-muted', <Widget>[Component.text('Native style: the villager trading screen, offers in mode, page and slot order.')]),
      ]);
    }
    final int pages = asList(shop['pages']).length;
    if (_page >= pages) _page = pages == 0 ? 0 : pages - 1;
    final List<String> modes = _enabledModes(shop);
    final String shown = _mode != null && modes.contains(_mode) ? _mode! : (asString(shop['defaultMode']) ?? modes.first);
    return dom.section(classes: 'animus-panel animus-gui-preview', <Widget>[
      _ShopSwitch(
        shop: shop,
        page: _page,
        mode: shown,
        onPage: (int page) => setState(() {
          _page = page;
          _slot = null;
        }),
        onMode: (String mode) => setState(() {
          _mode = mode;
          _slot = null;
        }),
      ),
      ChestWindow(
        layout: layoutShop(shop, page: _page, mode: shown),
        label: 'Shop window ${component.document.id}',
        selected: _slot,
        onSlot: (int slot) => setState(() => _slot = _slot == slot ? null : slot),
      ),
      dom.p(classes: 'animus-muted', <Widget>[
        Component.text('${style == null ? 'Server default style. ' : ''}Select a slot for its name and lore.'),
      ]),
    ]);
  }
}

class ShopEditor extends StatefulWidget {
  const ShopEditor({required this.document, required this.onJson, this.layouts = const <String>[], super.key});

  final AnimusDocument document;
  final JsonEdit onJson;

  /// Chest GUI ids in the workspace, offered for the layout.
  final List<String> layouts;

  @override
  State<ShopEditor> createState() => _ShopEditorState();
}

enum _Tab { entry, page, shop }

class _ShopEditorState extends State<ShopEditor> {
  int _page = 0;
  String? _mode;
  int? _slot;
  _Tab _tab = _Tab.page;

  JsonMap get _shop => asMap(component.document.json);
  List<Diagnostic> get _diagnostics => component.document.diagnostics;
  bool get _container => isContainerShop(_shop);

  void _write(List<JsonStep> steps, Object? value, {String? coalesce}) =>
      component.onJson(writeAt(_shop, steps, value), coalesce: coalesce ?? steps.join('.'));

  String get _shownMode {
    final List<String> modes = _enabledModes(_shop);
    final String? mode = _mode;
    if (mode != null && modes.contains(mode)) return mode;
    final String? first = asString(_shop['defaultMode']);
    return first != null && modes.contains(first) ? first : modes.first;
  }

  Widget _row(String label, String path, Widget Function(String id) child, {String? help, bool nested = false}) {
    final String id = guiPathId(path);
    return GuiRow(label: label, forId: id, help: help, problems: diagnosticsAt(_diagnostics, path, nested: nested), child: child(id));
  }

  @override
  Widget build(BuildContext context) {
    final JsonMap shop = _shop;
    final JsonList pages = asList(shop['pages']);
    if (_page >= pages.length) _page = pages.isEmpty ? 0 : pages.length - 1;
    final String mode = _shownMode;
    final ChestLayout layout = layoutShop(shop, page: _page, mode: mode);
    final _Tab tab = _tab == _Tab.entry && _slot == null ? _Tab.page : _tab;
    return dom.div(classes: 'animus-gui-editor', <Widget>[
      dom.section(classes: 'animus-panel animus-gui-stage', <Widget>[
        _ShopSwitch(
          shop: shop,
          page: _page,
          mode: mode,
          onPage: (int page) => setState(() {
            _page = page;
            _slot = null;
          }),
          onMode: (String one) => setState(() {
            _mode = one;
            _slot = null;
          }),
          trailing: <Widget>[
            GuiIconButton(
              icon: ArcaneIcon.plus(size: IconSize.sm),
              label: 'Add page',
              disabled: pages.length >= 64,
              onPressed: () {
                _write(<JsonStep>['pages'], <Object?>[...pages, <String, Object?>{'name': 'Page ${pages.length + 1}', 'rows': 3}]);
                setState(() {
                  _page = pages.length;
                  _tab = _Tab.page;
                });
              },
            ),
          ],
        ),
        ChestWindow(
          layout: layout,
          selected: _slot,
          label: 'Shop window ${component.document.id}',
          onSlot: (int slot) => setState(() {
            _slot = slot;
            _tab = _Tab.entry;
          }),
        ),
        const dom.p(classes: 'animus-muted', <Widget>[
          Component.text('Bottom row: previous (2), close (4), next (6), mode (8).'),
        ]),
        if (shop['style'] == 'native') ...<Widget>[
          const dom.p(classes: 'animus-muted', <Widget>[Component.text('Native style: players see these offers on the villager trading screen.')]),
          _merchantWindow(component.document),
        ],
      ]),
      dom.section(classes: 'animus-panel animus-gui-inspector', <Widget>[
        GuiTabs(
          label: 'Shop sections',
          tabs: <String>[_slot == null ? 'Entry' : 'Slot $_slot', 'Page', 'Shop'],
          selected: tab.index,
          disabled: <int>{if (_slot == null) 0},
          onSelect: (int index) => setState(() => _tab = _Tab.values[index]),
        ),
        switch (tab) {
          _Tab.shop => _shopFields(shop),
          _Tab.page => _pageFields(pages),
          _Tab.entry => _entry(layout, mode),
        },
      ]),
    ]);
  }

  Widget _shopFields(JsonMap shop) {
    final List<String> modes = stringList(shop['modes']);
    final List<String> ordered = modes.isEmpty ? shopModes : modes;
    void setModes(List<String> next) => _write(<JsonStep>['modes'], next.isEmpty ? null : next);
    return dom.div(classes: 'animus-gui-fields', <Widget>[
      _row('Title', r'$.title', (String id) => GuiTextInput(id: id, value: asString(shop['title']) ?? '', mode: GuiTextMode.template, label: 'Shop title', onChanged: (String v) => _write(<JsonStep>['title'], v.isEmpty ? null : v))),
      _row('Permission', r'$.permission', (String id) => GuiTextInput(id: id, value: asString(shop['permission']) ?? '', label: 'Shop permission', placeholder: 'town.shop.baker', onChanged: (String v) => _write(<JsonStep>['permission'], v.isEmpty ? null : v)),
          help: 'Needed to open'),
      _row('Style', r'$.style', (String id) => GuiSelect(id: id, value: asString(shop['style']), options: const <String>['native', 'chest'], allowNone: true, noneLabel: 'server default', labels: _styleLabels, onChanged: (String? v) => _write(<JsonStep>['style'], v)),
          help: 'Unset uses the server default'),
      GuiRow(
        label: 'Layout',
        help: 'Chest style only',
        problems: diagnosticsAt(_diagnostics, r'$.layout'),
        child: SuggestInput(
          value: asString(shop['layout']) ?? '',
          label: 'Layout',
          placeholder: 'town:shop-list',
          suggestions: <FieldOption>[for (final String id in component.layouts) FieldOption(id, description: 'chest GUI')],
          onChanged: (String v) => _write(<JsonStep>['layout'], v.trim().isEmpty ? null : v.trim()),
        ),
      ),
      GuiRow(
        label: 'Modes',
        help: 'Cycle order. None means buy, sell, trade.',
        problems: diagnosticsAt(_diagnostics, r'$.modes', nested: true),
        child: dom.div(classes: 'animus-gui-lines', <Widget>[
          for (int index = 0; index < ordered.length; index++)
            dom.div(classes: 'animus-gui-line', <Widget>[
              GuiSwitch(
                value: modes.contains(ordered[index]),
                label: _modeLabels[ordered[index]]!,
                onChanged: (bool on) => setModes(on ? <String>[...modes, ordered[index]] : (<String>[...modes]..remove(ordered[index]))),
              ),
              if (modes.contains(ordered[index]))
                ...guiMoveButtons(
                  index: modes.indexOf(ordered[index]),
                  length: modes.length,
                  noun: '${_modeLabels[ordered[index]]} mode',
                  onMove: (int to) => setModes(<String>[for (final Object? one in moveItem(modes, modes.indexOf(ordered[index]), to)) one! as String]),
                ),
            ]),
          for (final String mode in shopModes)
            if (!ordered.contains(mode))
              dom.div(classes: 'animus-gui-line', <Widget>[
                GuiSwitch(value: false, label: _modeLabels[mode]!, onChanged: (bool on) => setModes(<String>[...modes, mode])),
              ]),
        ]),
      ),
      _row('First mode', r'$.defaultMode', (String id) => GuiSelect(id: id, value: asString(shop['defaultMode']), options: _enabledModes(shop), allowNone: true, noneLabel: 'first in modes', labels: _modeLabels, onChanged: (String? v) => _write(<JsonStep>['defaultMode'], v))),
      _row('Item matching', r'$.match', (String id) => GuiSelect(id: id, value: asString(shop['match']), options: const <String>['strict', 'legacy'], allowNone: true, noneLabel: 'strict', onChanged: (String? v) => _write(<JsonStep>['match'], v)),
          help: 'strict checks all data; legacy for imports'),
      GuiRow(
        label: 'Purchases',
        child: dom.div(classes: 'animus-row', <Widget>[
          GuiSwitch(value: shop['confirm'] == true, label: 'Confirm each', onChanged: (bool on) => _write(<JsonStep>['confirm'], on ? true : null)),
          GuiSwitch(value: shop['closeAfterPurchase'] == true, label: 'Close after', onChanged: (bool on) => _write(<JsonStep>['closeAfterPurchase'], on ? true : null)),
          GuiSwitch(value: shop['log'] == true, label: 'Log', onChanged: (bool on) => _write(<JsonStep>['log'], on ? true : null)),
        ]),
      ),
      _ownerSection(shop),
      _containersSection(shop),
      _row('Revision', r'$.revision', (String id) => GuiNumberInput(id: id, value: asInt(shop['revision']), label: 'Revision', onChanged: (num? v) => _write(<JsonStep>['revision'], v))),
    ]);
  }

  Widget _ownerSection(JsonMap shop) {
    final JsonMap owner = asMap(shop['owner']);
    void set(String key, String value) {
      final JsonMap next = withField(owner, key, value.trim());
      _write(<JsonStep>['owner'], next.isEmpty ? null : next, coalesce: 'owner.$key');
    }

    return GuiSection(title: 'Owner', open: owner.isNotEmpty || _container, children: <Widget>[
      const dom.p(classes: 'animus-muted', <Widget>[
        Component.text('Owns the stock chests and gets the takings. Required with containers.'),
      ]),
      _row('UUID', r'$.owner.uuid', (String id) => GuiTextInput(id: id, value: asString(owner['uuid']) ?? '', label: 'Owner UUID', placeholder: '00000000-0000-0000-0000-000000000000', onChanged: (String v) => set('uuid', v)),
          help: 'Lowercase with dashes'),
      _row('Name', r'$.owner.name', (String id) => GuiTextInput(id: id, value: asString(owner['name']) ?? '', label: 'Owner name', onChanged: (String v) => set('name', v))),
      for (final Diagnostic problem in diagnosticsAt(_diagnostics, r'$.owner'))
        dom.p(classes: 'animus-gui-problem is-${problem.severity.name}', <Widget>[Component.text(problem.message)]),
    ]);
  }

  Widget _containersSection(JsonMap shop) {
    final JsonList containers = asList(shop['containers']);
    void setAll(JsonList next) => _write(<JsonStep>['containers'], next.isEmpty ? null : next, coalesce: 'containers-structure');
    return GuiSection(title: 'Containers', count: containers.length, open: containers.isNotEmpty, children: <Widget>[
      const dom.p(classes: 'animus-muted', <Widget>[
        Component.text('Stock and payment chests. With containers, entries are trade or static, '
            'with no price or stock.'),
      ]),
      for (final Diagnostic problem in diagnosticsAt(_diagnostics, r'$.containers'))
        dom.p(classes: 'animus-gui-problem is-${problem.severity.name}', <Widget>[Component.text(problem.message)]),
      for (int index = 0; index < containers.length; index++)
        dom.div(classes: 'animus-row animus-gui-goods', attributes: <String, String>{'data-path': '\$.containers[$index]'}, <Widget>[
          GuiTextInput(
            value: asString(asMap(containers[index])['world']) ?? '',
            label: 'Container ${index + 1} world',
            placeholder: 'world',
            onChanged: (String v) => _write(<JsonStep>['containers', index, 'world'], v.isEmpty ? null : v),
          ),
          for (final String axis in const <String>['x', 'y', 'z'])
            GuiNumberInput(
              value: asInt(asMap(containers[index])[axis]),
              label: 'Container ${index + 1} $axis',
              placeholder: axis,
              onChanged: (num? v) => _write(<JsonStep>['containers', index, axis], v),
            ),
          GuiIconButton(icon: ArcaneIcon.trash(size: IconSize.sm), label: 'Remove container ${index + 1}', onPressed: () => setAll(<Object?>[...containers]..removeAt(index))),
          for (final Diagnostic problem in diagnosticsAt(_diagnostics, '\$.containers[$index]', nested: true))
            dom.span(classes: 'animus-gui-problem is-${problem.severity.name}', <Widget>[Component.text('${problem.path} ${problem.message}')]),
        ]),
      Button.secondary(
        label: 'Add container',
        size: ButtonSize.small,
        disabled: containers.length >= 8,
        onPressed: () => setAll(<Object?>[...containers, <String, Object?>{'world': 'world', 'x': 0, 'y': 64, 'z': 0}]),
      ),
    ]);
  }

  Widget _pageFields(JsonList pages) {
    if (pages.isEmpty) {
      return const dom.p(classes: 'animus-muted', <Widget>[Component.text('No pages yet. Use Add page.')]);
    }
    final JsonMap page = asMap(pages[_page]);
    final String path = '\$.pages[$_page]';
    final int rows = asInt(page['rows']) ?? 3;
    final List<String> outside = shopEntriesOutside(page, rows);
    return dom.div(classes: 'animus-gui-fields', <Widget>[
      dom.p(classes: 'animus-muted', <Widget>[Component.text('Page ${_page + 1}. Select a slot to edit.')]),
      _row('Name', '$path.name', (String id) => GuiTextInput(id: id, value: asString(page['name']) ?? '', label: 'Page name', onChanged: (String v) => _write(<JsonStep>['pages', _page, 'name'], v.isEmpty ? null : v))),
      _row('Rows', '$path.rows', (String id) => GuiSelect(
            id: id,
            value: '$rows',
            options: const <String>['1', '2', '3', '4', '5', '6'],
            onChanged: (String? v) => _write(<JsonStep>['pages', _page, 'rows'], v == null ? null : int.parse(v)),
          )),
      if (outside.isNotEmpty)
        dom.p(classes: 'animus-gui-problem is-warning', attributes: const <String, String>{'role': 'alert'}, <Widget>[
          Component.text('${outside.length} ${outside.length == 1 ? 'entry is' : 'entries are'} outside $rows rows (${outside.join(', ')}). '
              'Add rows or move ${outside.length == 1 ? 'it' : 'them'}.'),
        ]),
      _row('Permission', '$path.permission', (String id) => GuiTextInput(id: id, value: asString(page['permission']) ?? '', label: 'Page permission', onChanged: (String v) => _write(<JsonStep>['pages', _page, 'permission'], v.isEmpty ? null : v)),
          help: 'Others skip this page'),
      dom.div(classes: 'animus-row', <Widget>[
        ...guiMoveButtons(index: _page, length: pages.length, noun: 'page', onMove: (int to) {
          _write(<JsonStep>['pages'], moveItem(pages, _page, to));
          setState(() => _page = to);
        }),
        Button.ghost(
          label: 'Remove page',
          size: ButtonSize.small,
          disabled: pages.length <= 1,
          onPressed: () {
            final int removed = _page;
            _write(<JsonStep>['pages'], <Object?>[...pages]..removeAt(removed));
            setState(() {
              _page = removed >= pages.length - 1 ? removed - 1 : removed;
              _slot = null;
            });
          },
        ),
      ]),
    ]);
  }

  Widget _entry(ChestLayout layout, String mode) {
    final int slot = _slot!;
    final ChestCell cell = layout.cell(slot.clamp(0, layout.cells.length - 1));
    final List<JsonStep> steps = <JsonStep>['pages', _page, mode, '$slot'];
    final bool container = _container;
    if (cell.source == ChestCellSource.control) {
      return const dom.p(classes: 'animus-muted', <Widget>[
        Component.text('Shop control slot. Pick another slot for entries.'),
      ]);
    }
    final JsonMap entry = asMap(readIn(_shop, steps));
    if (entry.isEmpty) {
      final List<String> types = shopTypesFor(mode, container: container).where((String type) => type != 'static').toList();
      return dom.div(classes: 'animus-gui-fields', <Widget>[
        dom.p(classes: 'animus-muted', <Widget>[
          Component.text('Slot $slot is empty. Add an entry.'
              '${container && mode != 'trade' ? ' Container shops take decorations only here.' : ''}'),
        ]),
        for (final String type in types)
          Button.secondary(
            label: 'Add a $type entry',
            size: ButtonSize.small,
            onPressed: () => _write(steps, withShopEntryType(<String, Object?>{'id': _freshId(), 'item': <String, Object?>{'id': 'minecraft:bread'}}, type, container: container)),
          ),
        Button.ghost(
          label: 'Add a decoration',
          size: ButtonSize.small,
          onPressed: () => _write(steps, <String, Object?>{'id': _freshId(), 'type': 'static', 'item': <String, Object?>{'id': 'minecraft:gray_stained_glass_pane'}}),
        ),
      ]);
    }
    final String path = formatPath(steps);
    final String type = asString(entry['type']) ?? 'static';
    void set(String key, Object? value, {String? coalesce}) => _write(<JsonStep>[...steps, key], value, coalesce: coalesce);
    void setIn(String group, String key, Object? value) {
      final JsonMap next = withField(asMap(entry[group]), key, value);
      set(group, next.isEmpty ? null : next, coalesce: '$path.$group.$key');
    }

    final JsonMap item = asMap(entry['item']);
    final JsonMap display = asMap(entry['display']);
    final JsonMap limit = asMap(entry['limit']);
    final JsonMap stock = asMap(entry['stock']);
    final JsonMap discount = asMap(entry['discount']);
    final bool priced = type != 'static';
    final bool showPrice = !container && priced || entry.containsKey('price') && container;
    final bool showStock = shopEntryKeeps(type, 'stock', container: container) || entry.containsKey('stock');
    return dom.div(classes: 'animus-gui-fields', attributes: <String, String>{'data-path': path}, <Widget>[
      for (final Diagnostic problem in diagnosticsAt(_diagnostics, path))
        dom.p(classes: 'animus-gui-problem is-${problem.severity.name}', <Widget>[Component.text(problem.message)]),
      _row('Id *', '$path.id', (String id) => GuiTextInput(id: id, value: asString(entry['id']) ?? '', label: 'Entry id', onChanged: (String v) => set('id', v)),
          help: 'Unique; keys limits and stock'),
      _row('Type', '$path.type', (String id) => GuiSelect(
            id: id,
            value: type,
            options: shopTypesFor(mode, container: container),
            labels: <String, String>{
              ..._typeLabels,
              if (!shopTypesFor(mode, container: container).contains(type)) type: '${_typeLabels[type] ?? type}, not allowed here',
            },
            onChanged: (String? v) {
              if (v == null || v == type) return;
              _write(steps, withShopEntryType(entry, v, container: container));
            },
          ),
          help: 'Drops fields the new type lacks'),
      GuiSection(title: 'Item', children: <Widget>[
        _row('Item *', '$path.item.id', (String id) => GuiTextInput(id: id, value: asString(item['id']) ?? '', label: 'Item id', onChanged: (String v) => setIn('item', 'id', v))),
        _row('Count', '$path.item.count', (String id) => GuiNumberInput(id: id, value: asInt(item['count']), label: 'Item count', onChanged: (num? v) => setIn('item', 'count', v)),
            help: '1 to 99, priced per this many'),
        _row('Components', '$path.item.components', (String id) => GuiTextInput(id: id, value: asString(item['components']) ?? '', label: 'Item components', onChanged: (String v) => setIn('item', 'components', v)),
            help: '/give syntax: [custom_name="Fresh"]'),
      ]),
      GuiSection(title: 'Display', open: display.isNotEmpty, children: <Widget>[
        _row('Name', '$path.display.name', (String id) => GuiTextInput(id: id, value: asString(display['name']) ?? '', mode: GuiTextMode.template, label: 'Display name', onChanged: (String v) => setIn('display', 'name', v))),
        GuiRow(label: 'Lore', child: GuiLinesEditor(lines: stringList(display['lore']), onChanged: (List<String> lines) => setIn('display', 'lore', lines.isEmpty ? null : lines))),
        GuiRow(
          label: 'Lines',
          child: dom.div(classes: 'animus-row', <Widget>[
            GuiSwitch(value: display['showPrice'] != false, label: 'Show price', onChanged: (bool on) => setIn('display', 'showPrice', on ? null : false)),
            GuiSwitch(value: display['showLimit'] != false, label: 'Show limit', onChanged: (bool on) => setIn('display', 'showLimit', on ? null : false)),
          ]),
        ),
      ]),
      if (showPrice)
        _row(type == 'trade' ? 'Money price' : 'Price *', '$path.price', (String id) => GuiNumberInput(id: id, value: asDouble(entry['price']), integer: false, label: 'Price', onChanged: (num? v) => set('price', v)),
            help: container ? 'Container shops take no money. Clear it.' : 'Up to 4 decimals'),
      if (type == 'trade') ...<Widget>[
        _goods(entry, steps, 'needs', 'Needs'),
        _goods(entry, steps, 'gives', 'Gives'),
      ],
      if (type == 'command') _commands(entry, steps, path),
      if (type == 'buy' || type == 'sell')
        GuiRow(
          label: 'Options',
          child: dom.div(classes: 'animus-row', <Widget>[
            if (type == 'buy') GuiSwitch(value: entry['allowStack'] == true, label: 'Shift buys a stack', onChanged: (bool on) => set('allowStack', on ? true : null)),
            if (type == 'sell') GuiSwitch(value: entry['sellPartial'] == true, label: 'Sell partial', onChanged: (bool on) => set('sellPartial', on ? true : null)),
            GuiSwitch(value: entry['dropWhenFull'] == true, label: 'Drop when full', onChanged: (bool on) => set('dropWhenFull', on ? true : null)),
          ]),
        ),
      if (priced) ...<Widget>[
        GuiSection(title: 'Limit', open: limit.isNotEmpty, children: <Widget>[
          const dom.p(classes: 'animus-muted', <Widget>[
            Component.text('Counts items for buy and sell, purchases otherwise.'),
          ]),
          _row('Per player', '$path.limit.perPlayer', (String id) => GuiNumberInput(id: id, value: asInt(limit['perPlayer']), label: 'Per player limit', onChanged: (num? v) => setIn('limit', 'perPlayer', v))),
          _row('Global', '$path.limit.global', (String id) => GuiNumberInput(id: id, value: asInt(limit['global']), label: 'Global limit', onChanged: (num? v) => setIn('limit', 'global', v))),
          _row('Resets after', '$path.limit.resetSeconds', (String id) => GuiNumberInput(id: id, value: asInt(limit['resetSeconds']), label: 'Limit reset seconds', onChanged: (num? v) => setIn('limit', 'resetSeconds', v)),
              help: 'Seconds, 86400 is a day'),
        ]),
        if (showStock)
          GuiSection(title: 'Stock', open: stock.isNotEmpty, children: <Widget>[
            _row('Maximum', '$path.stock.maximum', (String id) => GuiNumberInput(id: id, value: asInt(stock['maximum']), label: 'Stock maximum', onChanged: (num? v) => setIn('stock', 'maximum', v)),
                help: '1 to 1,000,000,000'),
            _row('Restock after', '$path.stock.restockSeconds', (String id) => GuiNumberInput(id: id, value: asInt(stock['restockSeconds']), label: 'Restock seconds', onChanged: (num? v) => setIn('stock', 'restockSeconds', v)),
                help: 'Seconds'),
          ]),
        GuiSection(title: 'Discount', open: discount.isNotEmpty, children: <Widget>[
          _row('Price', '$path.discount.price', (String id) => GuiNumberInput(id: id, value: asDouble(discount['price']), integer: false, label: 'Discount price', onChanged: (num? v) => setIn('discount', 'price', v))),
          _row('Starts', '$path.discount.start', (String id) => GuiTextInput(id: id, value: asString(discount['start']) ?? '', label: 'Discount start', placeholder: '2026-12-20T00:00:00Z', onChanged: (String v) => setIn('discount', 'start', v)),
              help: 'ISO-8601 time'),
          _row('Ends', '$path.discount.end', (String id) => GuiTextInput(id: id, value: asString(discount['end']) ?? '', label: 'Discount end', onChanged: (String v) => setIn('discount', 'end', v))),
        ]),
      ],
      GuiSection(title: 'Access and messages', open: entry['permission'] != null || entry['broadcast'] != null, children: <Widget>[
        _row('Permission', '$path.permission', (String id) => GuiTextInput(id: id, value: asString(entry['permission']) ?? '', label: 'Entry permission', onChanged: (String v) => set('permission', v.isEmpty ? null : v)),
            help: 'Needed to buy'),
        _row('Broadcast', '$path.broadcast', (String id) => GuiTextInput(id: id, value: asString(entry['broadcast']) ?? '', label: 'Broadcast', onChanged: (String v) => set('broadcast', v.isEmpty ? null : v)),
            help: 'Uses {player}, {item}, {price}'),
        GuiRow(label: 'After buying', child: GuiSwitch(value: entry['closeAfterPurchase'] == true, label: 'Close the shop', onChanged: (bool on) => set('closeAfterPurchase', on ? true : null))),
        _row('Matching', '$path.match', (String id) => GuiSelect(id: id, value: asString(entry['match']), options: const <String>['strict', 'legacy'], allowNone: true, noneLabel: 'as the shop', onChanged: (String? v) => set('match', v))),
      ]),
      Button.ghost(label: 'Remove entry', size: ButtonSize.small, onPressed: () => _write(steps, null)),
    ]);
  }

  Widget _goods(JsonMap entry, List<JsonStep> steps, String key, String title) {
    final JsonList goods = asList(entry[key]);
    final String path = formatPath(<JsonStep>[...steps, key]);
    return GuiSection(title: title, count: goods.length, children: <Widget>[
      for (final Diagnostic problem in diagnosticsAt(_diagnostics, path))
        dom.p(classes: 'animus-gui-problem is-${problem.severity.name}', <Widget>[Component.text(problem.message)]),
      for (int index = 0; index < goods.length; index++)
        dom.div(classes: 'animus-row animus-gui-goods', <Widget>[
          GuiTextInput(
            value: asString(asMap(asMap(goods[index])['item'])['id']) ?? '',
            label: '$title ${index + 1} item',
            onChanged: (String v) => _write(<JsonStep>[...steps, key, index, 'item', 'id'], v),
          ),
          GuiNumberInput(
            value: asInt(asMap(goods[index])['amount']),
            label: '$title ${index + 1} amount',
            placeholder: 'amount',
            onChanged: (num? v) => _write(<JsonStep>[...steps, key, index, 'amount'], v),
          ),
          GuiIconButton(icon: ArcaneIcon.trash(size: IconSize.sm), label: 'Remove $title ${index + 1}', onPressed: () => _write(<JsonStep>[...steps, key], <Object?>[...goods]..removeAt(index))),
        ]),
      Button.ghost(
        label: 'Add item',
        size: ButtonSize.small,
        disabled: goods.length >= 27,
        onPressed: () => _write(<JsonStep>[...steps, key], <Object?>[...goods, <String, Object?>{'item': <String, Object?>{'id': 'minecraft:emerald'}, 'amount': 1}]),
      ),
    ]);
  }

  Widget _commands(JsonMap entry, List<JsonStep> steps, String path) {
    final JsonList commands = asList(entry['commands']);
    return GuiSection(title: 'Commands', count: commands.length, children: <Widget>[
      const dom.p(classes: 'animus-gui-note', <Widget>[
        Component.text('Run after purchase. The shop loads only if on commandBehaviors or '
            'commandNamespaces under [behaviors.actions]. Uses {player}, {uuid}, {amount}, {price}.'),
      ]),
      for (final Diagnostic problem in diagnosticsAt(_diagnostics, '$path.commands'))
        dom.p(classes: 'animus-gui-problem is-${problem.severity.name}', <Widget>[Component.text(problem.message)]),
      for (int index = 0; index < commands.length; index++)
        dom.div(classes: 'animus-row animus-gui-goods', <Widget>[
          GuiTextInput(
            value: asString(asMap(commands[index])['command']) ?? '',
            label: 'Command ${index + 1}',
            placeholder: 'no leading slash',
            onChanged: (String v) => _write(<JsonStep>[...steps, 'commands', index, 'command'], v),
          ),
          GuiSelect(
            value: asString(asMap(commands[index])['as']),
            options: const <String>['console', 'player'],
            allowNone: true,
            noneLabel: 'console',
            hiddenLabel: 'Command ${index + 1} runs as',
            onChanged: (String? v) => _write(<JsonStep>[...steps, 'commands', index, 'as'], v),
          ),
          GuiIconButton(icon: ArcaneIcon.trash(size: IconSize.sm), label: 'Remove command ${index + 1}', onPressed: () => _write(<JsonStep>[...steps, 'commands'], <Object?>[...commands]..removeAt(index))),
        ]),
      Button.ghost(
        label: 'Add command',
        size: ButtonSize.small,
        disabled: commands.length >= 16,
        onPressed: () => _write(<JsonStep>[...steps, 'commands'], <Object?>[...commands, <String, Object?>{'command': 'say {player}', 'as': 'console'}]),
      ),
    ]);
  }

  String _freshId() {
    final Set<String> taken = <String>{};
    for (final Object? page in asList(_shop['pages'])) {
      for (final String mode in shopModes) {
        for (final Object? entry in asMap(asMap(page)[mode]).values) {
          final String? id = asString(asMap(entry)['id']);
          if (id != null) taken.add(id);
        }
      }
    }
    int n = taken.length + 1;
    while (taken.contains('entry-$n')) {
      n++;
    }
    return 'entry-$n';
  }
}
