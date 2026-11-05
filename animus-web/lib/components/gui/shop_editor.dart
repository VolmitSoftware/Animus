/// The shop editor: pages, the buy, sell and trade grids painted as the
/// shop window with its controls, and an inspector for the shop and for the
/// selected entry (prices, limits with resets, stock, permissions, discounts,
/// trades and command entries).
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/gui/gui_json.dart';
import '../../logic/gui/gui_layout.dart';
import '../../logic/gui/gui_validation.dart';
import '../../model/animus_document.dart';
import 'chest_window.dart';
import 'component_editor.dart';
import 'gui_fields.dart';

const Map<String, String> _modeLabels = <String, String>{'buy': 'Buy', 'sell': 'Sell', 'trade': 'Trade'};

List<String> _enabledModes(JsonMap shop) {
  final List<String> modes = stringList(shop['modes']);
  return modes.isEmpty ? shopModes : modes;
}

class ShopPreview extends StatelessWidget {
  const ShopPreview({required this.document, this.page = 0, this.mode, super.key});

  final AnimusDocument document;
  final int page;
  final String? mode;

  @override
  Widget build(BuildContext context) {
    final JsonMap shop = asMap(document.json);
    final String shown = mode ?? asString(shop['defaultMode']) ?? _enabledModes(shop).first;
    return dom.section(classes: 'animus-panel animus-gui-preview', <Widget>[
      ChestWindow(layout: layoutShop(shop, page: page, mode: shown), label: 'Shop window ${document.id}'),
    ]);
  }
}

class ShopEditor extends StatefulWidget {
  const ShopEditor({required this.document, required this.onJson, super.key});

  final AnimusDocument document;
  final JsonEdit onJson;

  @override
  State<ShopEditor> createState() => _ShopEditorState();
}

class _ShopEditorState extends State<ShopEditor> {
  int _page = 0;
  String? _mode;
  int? _slot;
  bool _shopTab = false;

  JsonMap get _shop => asMap(component.document.json);
  List<Diagnostic> get _diagnostics => component.document.diagnostics;

  void _write(List<JsonStep> steps, Object? value, {String? coalesce}) =>
      component.onJson(writeIn(_shop, steps, value), coalesce: coalesce ?? steps.join('.'));

  String get _shownMode {
    final List<String> modes = _enabledModes(_shop);
    final String? mode = _mode;
    if (mode != null && modes.contains(mode)) return mode;
    return asString(_shop['defaultMode']) ?? modes.first;
  }

  @override
  Widget build(BuildContext context) {
    final JsonMap shop = _shop;
    final JsonList pages = asList(shop['pages']);
    if (_page >= pages.length) _page = pages.isEmpty ? 0 : pages.length - 1;
    final String mode = _shownMode;
    final ChestLayout layout = layoutShop(shop, page: _page, mode: mode);
    return dom.div(classes: 'animus-gui-editor', <Widget>[
      dom.section(classes: 'animus-panel animus-gui-stage', <Widget>[
        dom.div(classes: 'animus-gui-toolbar', <Widget>[
          dom.div(classes: 'animus-segmented', attributes: const <String, String>{'role': 'tablist', 'aria-label': 'Pages'}, <Widget>[
            for (int index = 0; index < pages.length; index++)
              Button(
                label: asString(asMap(pages[index])['name']) ?? 'Page ${index + 1}',
                size: ButtonSize.small,
                variant: index == _page ? ButtonVariant.primary : ButtonVariant.ghost,
                attributes: <String, String>{'role': 'tab', 'aria-selected': '${index == _page}'},
                onPressed: () => setState(() {
                  _page = index;
                  _slot = null;
                }),
              ),
            GuiIconButton(
              icon: ArcaneIcon.plus(size: IconSize.sm),
              label: 'Add page',
              disabled: pages.length >= 64,
              onPressed: () {
                _write(<JsonStep>['pages'], <Object?>[...pages, <String, Object?>{'name': 'Page ${pages.length + 1}', 'rows': 3}]);
                setState(() => _page = pages.length);
              },
            ),
          ]),
          dom.div(classes: 'animus-segmented', attributes: const <String, String>{'role': 'tablist', 'aria-label': 'Modes'}, <Widget>[
            for (final String one in _enabledModes(shop))
              Button(
                label: _modeLabels[one] ?? one,
                size: ButtonSize.small,
                variant: one == mode ? ButtonVariant.primary : ButtonVariant.ghost,
                attributes: <String, String>{'role': 'tab', 'aria-selected': '${one == mode}'},
                onPressed: () => setState(() {
                  _mode = one;
                  _slot = null;
                }),
              ),
          ]),
        ]),
        ChestWindow(
          layout: layout,
          selected: _slot,
          label: 'Shop window ${component.document.id}',
          onSlot: (int slot) => setState(() {
            _slot = slot;
            _shopTab = false;
          }),
        ),
        const dom.p(classes: 'animus-muted', <Widget>[
          Component.text('The bottom row holds previous page (slot 2), close (4), next page (6) and the mode switch (8); each shows only when it does something.'),
        ]),
      ]),
      dom.section(classes: 'animus-panel animus-gui-inspector', <Widget>[
        dom.div(classes: 'animus-segmented animus-gui-tabs', attributes: const <String, String>{'role': 'tablist'}, <Widget>[
          Button(
            label: _slot == null ? 'Entry' : 'Slot $_slot',
            size: ButtonSize.small,
            disabled: _slot == null,
            variant: !_shopTab && _slot != null ? ButtonVariant.primary : ButtonVariant.ghost,
            attributes: <String, String>{'role': 'tab', 'aria-selected': '${!_shopTab && _slot != null}'},
            onPressed: () => setState(() => _shopTab = false),
          ),
          Button(
            label: 'Page',
            size: ButtonSize.small,
            variant: !_shopTab && _slot == null ? ButtonVariant.primary : ButtonVariant.ghost,
            attributes: <String, String>{'role': 'tab', 'aria-selected': '${!_shopTab && _slot == null}'},
            onPressed: () => setState(() {
              _shopTab = false;
              _slot = null;
            }),
          ),
          Button(
            label: 'Shop',
            size: ButtonSize.small,
            variant: _shopTab ? ButtonVariant.primary : ButtonVariant.ghost,
            attributes: <String, String>{'role': 'tab', 'aria-selected': '$_shopTab'},
            onPressed: () => setState(() => _shopTab = true),
          ),
        ]),
        if (_shopTab) _shopFields(shop) else if (_slot == null) _pageFields(pages) else _entry(layout, mode),
      ]),
    ]);
  }

  Widget _shopFields(JsonMap shop) {
    final List<String> modes = stringList(shop['modes']);
    return dom.div(classes: 'animus-gui-fields', <Widget>[
      GuiRow(
        label: 'Title',
        problems: diagnosticsAt(_diagnostics, r'$.title'),
        child: GuiTextInput(value: asString(shop['title']) ?? '', mode: GuiTextMode.template, label: 'Shop title', onChanged: (String v) => _write(<JsonStep>['title'], v.isEmpty ? null : v)),
      ),
      GuiRow(
        label: 'Permission',
        help: 'Needed to open the shop.',
        problems: diagnosticsAt(_diagnostics, r'$.permission'),
        child: GuiTextInput(value: asString(shop['permission']) ?? '', label: 'Shop permission', onChanged: (String v) => _write(<JsonStep>['permission'], v.isEmpty ? null : v)),
      ),
      GuiRow(
        label: 'Modes',
        help: 'In switch order. None ticked means all three.',
        problems: diagnosticsAt(_diagnostics, r'$.modes'),
        child: dom.div(classes: 'animus-row', <Widget>[
          for (final String mode in shopModes)
            GuiSwitch(
              value: modes.contains(mode),
              label: _modeLabels[mode]!,
              onChanged: (bool on) {
                final List<String> next = on ? <String>[...modes, mode] : (<String>[...modes]..remove(mode));
                _write(<JsonStep>['modes'], next.isEmpty ? null : next);
              },
            ),
        ]),
      ),
      GuiRow(
        label: 'First mode',
        problems: diagnosticsAt(_diagnostics, r'$.defaultMode'),
        child: GuiSelect(value: asString(shop['defaultMode']), options: _enabledModes(shop), allowNone: true, noneLabel: 'first in modes', onChanged: (String? v) => _write(<JsonStep>['defaultMode'], v)),
      ),
      GuiRow(
        label: 'Item matching',
        help: 'strict compares every piece of item data; legacy is what imports use.',
        child: GuiSelect(value: asString(shop['match']), options: const <String>['strict', 'legacy'], allowNone: true, noneLabel: 'strict', onChanged: (String? v) => _write(<JsonStep>['match'], v)),
      ),
      GuiRow(
        label: 'Purchases',
        child: dom.div(classes: 'animus-row', <Widget>[
          GuiSwitch(value: shop['confirm'] == true, label: 'Confirm each', onChanged: (bool on) => _write(<JsonStep>['confirm'], on ? true : null)),
          GuiSwitch(value: shop['closeAfterPurchase'] == true, label: 'Close after', onChanged: (bool on) => _write(<JsonStep>['closeAfterPurchase'], on ? true : null)),
          GuiSwitch(value: shop['log'] == true, label: 'Log', onChanged: (bool on) => _write(<JsonStep>['log'], on ? true : null)),
        ]),
      ),
      GuiRow(
        label: 'Revision',
        child: GuiNumberInput(value: asInt(shop['revision']), label: 'Revision', onChanged: (num? v) => _write(<JsonStep>['revision'], v)),
      ),
    ]);
  }

  Widget _pageFields(JsonList pages) {
    if (pages.isEmpty) return const dom.p(classes: 'animus-muted', <Widget>[Component.text('Add a page above.')]);
    final JsonMap page = asMap(pages[_page]);
    final String path = '\$.pages[$_page]';
    return dom.div(classes: 'animus-gui-fields', <Widget>[
      dom.p(classes: 'animus-muted', <Widget>[Component.text('Page ${_page + 1}. Click a slot to edit or add an entry.')]),
      GuiRow(
        label: 'Name',
        child: GuiTextInput(value: asString(page['name']) ?? '', label: 'Page name', onChanged: (String v) => _write(<JsonStep>['pages', _page, 'name'], v.isEmpty ? null : v)),
      ),
      GuiRow(
        label: 'Rows',
        problems: diagnosticsAt(_diagnostics, '$path.rows'),
        child: GuiSelect(
          value: '${asInt(page['rows']) ?? 3}',
          options: const <String>['1', '2', '3', '4', '5', '6'],
          onChanged: (String? v) => _write(<JsonStep>['pages', _page, 'rows'], v == null ? null : int.parse(v)),
        ),
      ),
      GuiRow(
        label: 'Permission',
        help: 'Players without it skip this page.',
        problems: diagnosticsAt(_diagnostics, '$path.permission'),
        child: GuiTextInput(value: asString(page['permission']) ?? '', label: 'Page permission', onChanged: (String v) => _write(<JsonStep>['pages', _page, 'permission'], v.isEmpty ? null : v)),
      ),
      dom.div(classes: 'animus-row', <Widget>[
        GuiIconButton(
          icon: ArcaneIcon.arrowUp(size: IconSize.sm),
          label: 'Move page earlier',
          disabled: _page == 0,
          onPressed: () {
            _write(<JsonStep>['pages'], moveItem(pages, _page, _page - 1));
            setState(() => _page--);
          },
        ),
        Button.ghost(
          label: 'Remove page',
          size: ButtonSize.small,
          disabled: pages.length <= 1,
          onPressed: () {
            _write(<JsonStep>['pages'], <Object?>[...pages]..removeAt(_page));
            setState(() => _page = 0);
          },
        ),
      ]),
    ]);
  }

  Widget _entry(ChestLayout layout, String mode) {
    final int slot = _slot!;
    final ChestCell cell = layout.cell(slot.clamp(0, layout.cells.length - 1));
    final List<JsonStep> steps = <JsonStep>['pages', _page, mode, '$slot'];
    if (cell.source == ChestCellSource.control) {
      return const dom.p(classes: 'animus-muted', <Widget>[
        Component.text('A shop control. Entries under a shown control are hidden.'),
      ]);
    }
    final JsonMap entry = asMap(readIn(_shop, steps));
    if (entry.isEmpty) {
      final String type = mode == 'buy' ? 'buy' : mode;
      return dom.div(classes: 'animus-gui-fields', <Widget>[
        dom.p(classes: 'animus-muted', <Widget>[Component.text('Slot $slot of the ${_modeLabels[mode]} grid is empty.')]),
        Button.secondary(
          label: 'Add a $type entry',
          size: ButtonSize.small,
          onPressed: () => _write(steps, <String, Object?>{
            'id': _freshId(),
            'type': type,
            'item': <String, Object?>{'id': 'minecraft:bread'},
            if (type == 'trade') ...<String, Object?>{
              'needs': <Object?>[<String, Object?>{'item': <String, Object?>{'id': 'minecraft:emerald'}, 'amount': 1}],
              'gives': <Object?>[<String, Object?>{'item': <String, Object?>{'id': 'minecraft:bread'}, 'amount': 1}],
            } else
              'price': 1,
          }),
        ),
        if (mode == 'buy')
          Button.ghost(
            label: 'Add a command entry',
            size: ButtonSize.small,
            onPressed: () => _write(steps, <String, Object?>{
              'id': _freshId(),
              'type': 'command',
              'item': <String, Object?>{'id': 'minecraft:paper'},
              'price': 1,
              'commands': <Object?>[<String, Object?>{'command': 'say {player} bought a ticket', 'as': 'console'}],
            }),
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
    return dom.div(classes: 'animus-gui-fields', attributes: <String, String>{'data-path': path}, <Widget>[
      for (final Diagnostic problem in diagnosticsAt(_diagnostics, path))
        dom.p(classes: 'animus-gui-problem is-${problem.severity.name}', <Widget>[Component.text(problem.message)]),
      GuiRow(
        label: 'Id *',
        help: 'Unique in the shop; limits and stock are kept under it.',
        problems: diagnosticsAt(_diagnostics, '$path.id'),
        child: GuiTextInput(value: asString(entry['id']) ?? '', label: 'Entry id', onChanged: (String v) => set('id', v)),
      ),
      GuiRow(
        label: 'Type',
        problems: diagnosticsAt(_diagnostics, '$path.type'),
        child: GuiSelect(
          value: type,
          options: <String>[if (mode == 'buy') ...<String>['buy', 'command'], if (mode == 'sell') 'sell', if (mode == 'trade') 'trade', 'static'],
          onChanged: (String? v) => set('type', v),
        ),
      ),
      GuiSection(title: 'Item', children: <Widget>[
        GuiRow(
          label: 'Item *',
          problems: diagnosticsAt(_diagnostics, '$path.item.id'),
          child: GuiTextInput(value: asString(item['id']) ?? '', label: 'Item id', onChanged: (String v) => setIn('item', 'id', v)),
        ),
        GuiRow(
          label: 'Count',
          help: '1 to 99; the price is for this many.',
          problems: diagnosticsAt(_diagnostics, '$path.item.count'),
          child: GuiNumberInput(value: asInt(item['count']), label: 'Item count', onChanged: (num? v) => setIn('item', 'count', v)),
        ),
        GuiRow(
          label: 'Components',
          help: 'In /give syntax, such as [custom_name="Fresh"].',
          problems: diagnosticsAt(_diagnostics, '$path.item.components'),
          child: GuiTextInput(value: asString(item['components']) ?? '', label: 'Item components', onChanged: (String v) => setIn('item', 'components', v)),
        ),
      ]),
      GuiSection(title: 'Display', open: display.isNotEmpty, children: <Widget>[
        GuiRow(label: 'Name', child: GuiTextInput(value: asString(display['name']) ?? '', mode: GuiTextMode.template, label: 'Display name', onChanged: (String v) => setIn('display', 'name', v))),
        GuiRow(label: 'Lore', child: GuiLinesEditor(lines: stringList(display['lore']), onChanged: (List<String> lines) => setIn('display', 'lore', lines.isEmpty ? null : lines))),
        GuiRow(
          label: 'Lines',
          child: dom.div(classes: 'animus-row', <Widget>[
            GuiSwitch(value: display['showPrice'] != false, label: 'Show price', onChanged: (bool on) => setIn('display', 'showPrice', on ? null : false)),
            GuiSwitch(value: display['showLimit'] != false, label: 'Show limit', onChanged: (bool on) => setIn('display', 'showLimit', on ? null : false)),
          ]),
        ),
      ]),
      if (priced)
        GuiRow(
          label: type == 'trade' ? 'Money price' : 'Price *',
          help: 'For item.count items, 0 to 1,000,000,000,000 with at most 4 decimals.',
          problems: diagnosticsAt(_diagnostics, '$path.price'),
          child: GuiNumberInput(value: asDouble(entry['price']), integer: false, label: 'Price', onChanged: (num? v) => set('price', v)),
        ),
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
            Component.text('Items for buy and sell entries, purchases for trades and commands. A window starts at the first purchase after the last one ended.'),
          ]),
          GuiRow(label: 'Per player', problems: diagnosticsAt(_diagnostics, '$path.limit.perPlayer'), child: GuiNumberInput(value: asInt(limit['perPlayer']), label: 'Per player limit', onChanged: (num? v) => setIn('limit', 'perPlayer', v))),
          GuiRow(label: 'Global', problems: diagnosticsAt(_diagnostics, '$path.limit.global'), child: GuiNumberInput(value: asInt(limit['global']), label: 'Global limit', onChanged: (num? v) => setIn('limit', 'global', v))),
          GuiRow(
            label: 'Resets after',
            help: 'Seconds; 86400 is a day.',
            problems: diagnosticsAt(_diagnostics, '$path.limit.resetSeconds'),
            child: GuiNumberInput(value: asInt(limit['resetSeconds']), label: 'Limit reset seconds', onChanged: (num? v) => setIn('limit', 'resetSeconds', v)),
          ),
        ]),
        GuiSection(title: 'Stock', open: stock.isNotEmpty, children: <Widget>[
          GuiRow(label: 'Maximum', problems: diagnosticsAt(_diagnostics, '$path.stock.maximum'), child: GuiNumberInput(value: asInt(stock['maximum']), label: 'Stock maximum', onChanged: (num? v) => setIn('stock', 'maximum', v))),
          GuiRow(label: 'Restock after', help: 'Seconds.', child: GuiNumberInput(value: asInt(stock['restockSeconds']), label: 'Restock seconds', onChanged: (num? v) => setIn('stock', 'restockSeconds', v))),
        ]),
        GuiSection(title: 'Discount', open: discount.isNotEmpty, children: <Widget>[
          GuiRow(label: 'Price', problems: diagnosticsAt(_diagnostics, '$path.discount.price'), child: GuiNumberInput(value: asDouble(discount['price']), integer: false, label: 'Discount price', onChanged: (num? v) => setIn('discount', 'price', v))),
          GuiRow(
            label: 'Starts',
            help: 'ISO-8601, such as 2026-12-20T00:00:00Z.',
            problems: diagnosticsAt(_diagnostics, '$path.discount.start'),
            child: GuiTextInput(value: asString(discount['start']) ?? '', label: 'Discount start', onChanged: (String v) => setIn('discount', 'start', v)),
          ),
          GuiRow(
            label: 'Ends',
            problems: diagnosticsAt(_diagnostics, '$path.discount.end'),
            child: GuiTextInput(value: asString(discount['end']) ?? '', label: 'Discount end', onChanged: (String v) => setIn('discount', 'end', v)),
          ),
        ]),
        GuiSection(title: 'Access and messages', open: entry['permission'] != null || entry['broadcast'] != null, children: <Widget>[
          GuiRow(
            label: 'Permission',
            help: 'Needed to buy; others see a needs-permission line.',
            problems: diagnosticsAt(_diagnostics, '$path.permission'),
            child: GuiTextInput(value: asString(entry['permission']) ?? '', label: 'Entry permission', onChanged: (String v) => set('permission', v.isEmpty ? null : v)),
          ),
          GuiRow(
            label: 'Broadcast',
            help: '{player}, {item} and {price} are filled in.',
            child: GuiTextInput(value: asString(entry['broadcast']) ?? '', label: 'Broadcast', onChanged: (String v) => set('broadcast', v.isEmpty ? null : v)),
          ),
          GuiRow(label: 'After buying', child: GuiSwitch(value: entry['closeAfterPurchase'] == true, label: 'Close the shop', onChanged: (bool on) => set('closeAfterPurchase', on ? true : null))),
          GuiRow(
            label: 'Matching',
            child: GuiSelect(value: asString(entry['match']), options: const <String>['strict', 'legacy'], allowNone: true, noneLabel: 'as the shop', onChanged: (String? v) => set('match', v)),
          ),
        ]),
      ],
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
        Component.text('Run after the purchase completes. The shop loads only when its id or namespace is on commandBehaviors or '
            'commandNamespaces under [behaviors.actions], the allow-list behaviors use. {player}, {uuid}, {amount} and {price} are filled in.'),
      ]),
      for (final Diagnostic problem in diagnosticsAt(_diagnostics, '$path.commands'))
        dom.p(classes: 'animus-gui-problem is-${problem.severity.name}', <Widget>[Component.text(problem.message)]),
      for (int index = 0; index < commands.length; index++)
        dom.div(classes: 'animus-row animus-gui-goods', <Widget>[
          GuiTextInput(
            value: asString(asMap(commands[index])['command']) ?? '',
            label: 'Command ${index + 1}',
            placeholder: 'without the leading slash',
            onChanged: (String v) => _write(<JsonStep>[...steps, 'commands', index, 'command'], v),
          ),
          GuiSelect(
            value: asString(asMap(commands[index])['as']),
            options: const <String>['console', 'player'],
            allowNone: true,
            noneLabel: 'console',
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
