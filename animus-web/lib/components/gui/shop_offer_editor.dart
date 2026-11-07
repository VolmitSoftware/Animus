/// One offer edited in place on the shop board: its kind, page and slot,
/// what it takes and gives through the item picker with count steppers and
/// item details, the money price where one applies, commands, the display
/// name and lore, and its rules (stock, limits, permission and the
/// purchase switches), each with a short tip on its label.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../config/docs_links.dart';
import '../../logic/gui/gui_json.dart';
import '../../logic/gui/item_components.dart';
import '../../logic/gui/shop_board.dart';
import '../../logic/gui/shop_entries.dart';
import '../../logic/suggest/completion_sources.dart';
import '../../logic/suggest/field_profile.dart';
import '../../logic/suggest/field_profiles.dart';
import '../../model/animus_document.dart';
import '../mc/item_picker.dart';
import 'component_editor.dart' show withField;
import 'gui_fields.dart';

/// Offer kinds by plain name.
const Map<String, String> offerKindLabels = <String, String>{
  'trade': 'Trade (items for items)',
  'buy': 'Buy (player pays money)',
  'sell': 'Sell (shop pays money)',
  'command': 'Command (runs commands)',
  'static': 'Decoration',
};

/// Short names for the board's kind tag.
const Map<String, String> offerKindTags = <String, String>{'trade': 'Trade', 'buy': 'Buy', 'sell': 'Sell', 'command': 'Command', 'static': 'Decoration'};

/// Writes [value] at [steps] of the shop; edits sharing [coalesce] undo
/// together.
typedef ShopWrite = void Function(List<JsonStep> steps, Object? value, {String? coalesce});

/// A number with step buttons, clamped to [min] and [max].
class CountStepper extends StatelessWidget {
  const CountStepper({required this.value, required this.onChanged, required this.label, this.min = 1, this.max = 99, this.id, super.key});

  final int? value;
  final void Function(int? value) onChanged;
  final String label;
  final int min;
  final int max;
  final String? id;

  @override
  Widget build(BuildContext context) {
    final int shown = value ?? min;
    return dom.div(classes: 'animus-shop-stepper', attributes: <String, String>{'role': 'group', 'aria-label': label}, <Widget>[
      GuiIconButton(icon: ArcaneIcon.minus(size: IconSize.sm), label: 'Fewer: $label', disabled: shown <= min, onPressed: () => onChanged((shown - 1).clamp(min, max))),
      GuiNumberInput(id: id, value: value, label: label, digits: '$max'.length, onChanged: (num? next) => onChanged(next?.toInt())),
      GuiIconButton(icon: ArcaneIcon.plus(size: IconSize.sm), label: 'More: $label', disabled: shown >= max, onPressed: () => onChanged((shown + 1).clamp(min, max))),
    ]);
  }
}

/// An item's enchantments, custom model number and other components, read
/// from and written to its `components` text.
class ItemDetailsEditor extends StatelessWidget {
  const ItemDetailsEditor({required this.item, required this.onChanged, required this.label, super.key});

  final JsonMap item;
  final void Function(JsonMap item) onChanged;

  /// Names the item in control labels, such as "Takes 1".
  final String label;

  void _put(String? components) => onChanged(withField(item, 'components', components));

  @override
  Widget build(BuildContext context) {
    final String text = asString(item['components']) ?? '';
    final List<(String, String)>? parts = readComponents(text);
    final Map<String, int>? enchantments = parts == null ? null : readEnchantments(componentValue(parts, 'enchantments'));
    final String? model = parts == null ? null : componentValue(parts, 'custom_model_data');
    final num? modelNumber = readModelNumber(model);
    void setEnchantments(Map<String, int> next) => _put(writeComponents(withComponent(parts!, 'enchantments', writeEnchantments(next))));
    final List<MapEntry<String, int>> listed = enchantments?.entries.toList() ?? const <MapEntry<String, int>>[];
    return dom.div(classes: 'animus-shop-details', <Widget>[
      if (parts != null && enchantments != null) ...<Widget>[
        for (int index = 0; index < listed.length; index++)
          dom.div(classes: 'animus-row animus-shop-goods', <Widget>[
            GuiTextInput(
              value: listed[index].key,
              label: '$label enchantment ${index + 1}',
              profile: idProfile(const ValueRef.registry(RegistryKind.enchantment)),
              placeholder: 'minecraft:sharpness',
              onChanged: (String id) {
                final String clean = id.trim();
                if (clean.isEmpty) return;
                setEnchantments(<String, int>{
                  for (int i = 0; i < listed.length; i++) i == index ? clean : listed[i].key: listed[i].value,
                });
              },
            ),
            CountStepper(
              value: listed[index].value,
              label: '$label enchantment ${index + 1} level',
              max: 255,
              onChanged: (int? level) => setEnchantments(<String, int>{...enchantments, listed[index].key: level ?? 1}),
            ),
            GuiIconButton(
              icon: ArcaneIcon.x(size: IconSize.sm),
              label: 'Remove $label enchantment ${index + 1}',
              onPressed: () => setEnchantments(<String, int>{...enchantments}..remove(listed[index].key)),
            ),
          ]),
        Button.ghost(
          label: 'Add enchantment',
          size: ButtonSize.small,
          onPressed: () => setEnchantments(<String, int>{...enchantments, if (!enchantments.containsKey('minecraft:unbreaking')) 'minecraft:unbreaking': 1 else 'minecraft:mending': 1}),
        ),
      ],
      if (parts != null && (model == null || modelNumber != null))
        GuiRow(
          label: 'Model number',
          tip: 'custom_model_data for a resource pack model. Empty for the normal look.',
          child: GuiNumberInput(
            value: modelNumber,
            integer: false,
            label: '$label model number',
            onChanged: (num? number) => _put(writeComponents(withComponent(parts, 'custom_model_data', number == null ? null : writeModelNumber(number)))),
          ),
        ),
      GuiRow(
        label: 'Components',
        tip: 'Every item component in /give syntax, such as [custom_name="Fresh"]. The fields above write here.',
        child: GuiTextInput(value: text, label: '$label components', placeholder: '[custom_name="Fresh"]', onChanged: (String next) => _put(next.trim().isEmpty ? null : next.trim())),
      ),
      if (parts == null) const dom.span(classes: 'animus-gui-problem is-warning', <Widget>[Component.text('Not a [key=value,...] list, so only the text is editable.')]),
      if (parts != null && enchantments == null) const dom.span(classes: 'animus-gui-help', <Widget>[Component.text('Enchantments use a form this editor does not read; edit them as text.')]),
    ]);
  }
}

class ShopOfferEditor extends StatelessWidget {
  const ShopOfferEditor({
    required this.shop,
    required this.offer,
    required this.diagnostics,
    required this.onWrite,
    required this.onMove,
    super.key,
  });

  final JsonMap shop;
  final BoardOffer offer;

  /// Every diagnostic of the shop; the editor shows the offer's own.
  final List<Diagnostic> diagnostics;
  final ShopWrite onWrite;

  /// Board edits that move the offer: kind, page and slot.
  final void Function(ShopEdit edit) onMove;

  List<JsonStep> get _steps => offer.ref.steps;
  String get _path => offer.ref.path;
  JsonMap get _entry => offer.entry;

  void _set(String key, Object? value, {String? coalesce}) => onWrite(<JsonStep>[..._steps, key], value, coalesce: coalesce ?? '${offer.ref.key}.$key');

  void _setIn(String group, String key, Object? value) {
    final JsonMap next = withField(asMap(_entry[group]), key, value);
    _set(group, next.isEmpty ? null : next, coalesce: '${offer.ref.key}.$group.$key');
  }

  List<Diagnostic> _at(String path, {bool nested = false}) => diagnosticsAt(diagnostics, path, nested: nested);

  /// A labelled field for [key] of the offer; dots in [key] step into
  /// objects (`limit.perPlayer`).
  Widget _row(String label, String key, Widget Function(String id) child, {String? tip, List<Diagnostic>? problems}) {
    final String path = key.split('.').fold(_path, childPath);
    final String id = guiPathId(path);
    return GuiRow(label: label, forId: id, tip: tip, problems: problems ?? _at(path, nested: true), child: child(id));
  }

  @override
  Widget build(BuildContext context) {
    final String type = offer.type;
    final bool container = isContainerShop(shop);
    final bool money = type == 'buy' || type == 'sell' || type == 'command' || (type == 'trade' && !container);
    final List<String> kinds = <String>[for (final String kind in shopOfferKinds) if (!container || kind == 'trade' || kind == 'static') kind];
    final JsonList pages = asList(shop['pages']);
    final int rows = shopPageRows(shop, offer.ref.page);
    return dom.div(classes: 'animus-shop-offer-editor', attributes: <String, String>{'data-path': _path}, <Widget>[
      dom.div(classes: 'animus-shop-fields is-head', <Widget>[
        GuiRow(
          label: 'Kind',
          forId: '${guiPathId(_path)}-kind',
          tip: 'Changing it keeps the fields both kinds share and moves the offer to that kind\'s grid.',
          child: GuiSelect(
            id: '${guiPathId(_path)}-kind',
            hiddenLabel: 'Kind',
            value: type,
            options: kinds,
            labels: offerKindLabels,
            onChanged: (String? kind) {
              if (kind != null && kind != type) onMove(setOfferKind(shop, offer.ref, kind));
            },
          ),
        ),
        _row('Id', 'id', (String id) => GuiTextInput(id: id, value: offer.id, label: 'Offer id', onChanged: (String v) => _set('id', v.trim())),
            tip: 'Unique in the shop. Limits and stock are kept under it, so keep it once players have bought.'),
        if (pages.length > 1)
          GuiRow(
            label: 'Page',
            forId: '${guiPathId(_path)}-page',
            child: GuiSelect(
              id: '${guiPathId(_path)}-page',
              hiddenLabel: 'Page',
              value: '${offer.ref.page}',
              options: <String>[for (int i = 0; i < pages.length; i++) '$i'],
              labels: <String, String>{for (int i = 0; i < pages.length; i++) '$i': asString(asMap(pages[i])['name']) ?? 'Page ${i + 1}'},
              onChanged: (String? page) {
                if (page != null) onMove(moveOfferToPage(shop, offer.ref, int.parse(page)));
              },
            ),
          ),
        GuiRow(
          label: 'Slot',
          forId: '${guiPathId(_path)}-slot',
          tip: 'Where the chest window draws it, 0 at the top left. An offer already there swaps places.',
          problems: _at(_path),
          child: GuiNumberInput(
            id: '${guiPathId(_path)}-slot',
            value: offer.ref.slot,
            label: 'Slot',
            digits: 2,
            onChanged: (num? slot) {
              if (slot != null && slot >= 0 && slot < rows * 9) onMove(moveOfferToSlot(shop, offer.ref, slot.toInt()));
            },
          ),
        ),
      ]),
      if (type == 'trade') ...<Widget>[
        _goodsList('needs', 'Takes', 'What the player hands over. The trading screen shows the first two.'),
        _goodsList('gives', 'Gives', 'What the player gets. The trading screen shows the first.'),
      ] else
        _itemRow(type),
      if (money) _money(type, container),
      if (type == 'command') _commands(),
      _display(type),
      if (type != 'static') _rules(type, container),
    ]);
  }

  Widget _itemRow(String type) {
    final JsonMap item = asMap(_entry['item']);
    final String path = childPath(_path, 'item');
    final String label = switch (type) { 'sell' => 'Takes', 'static' => 'Shows', _ => 'Gives' };
    void put(JsonMap next) => _set('item', next);
    return GuiSection(title: label, tip: type == 'command' ? 'The icon players click. A command offer sells only its commands.' : 'The item and how many, 1 to 99.', children: <Widget>[
      dom.div(classes: 'animus-row animus-shop-goods', <Widget>[
        ItemPicker(
          id: guiPathId(childPath(path, 'id')),
          value: asString(item['id']) ?? '',
          label: '$label item',
          onChanged: (String id) => put(<String, Object?>{...item, 'id': id.trim()}),
        ),
        CountStepper(value: asInt(item['count']), label: '$label count', onChanged: (int? count) => put(withField(item, 'count', count == 1 ? null : count))),
      ]),
      for (final Diagnostic problem in _at(path, nested: true)) _problem(problem),
      GuiSection(title: 'Details', open: (asString(item['components']) ?? '').isNotEmpty, tip: 'Enchantments, custom model number and other item components.', docs: Docs.shopEntries, children: <Widget>[
        ItemDetailsEditor(item: item, label: label, onChanged: put),
      ]),
    ]);
  }

  Widget _goodsList(String key, String title, String tip) {
    final JsonList goods = asList(_entry[key]);
    final String path = childPath(_path, key);
    void setAll(JsonList next) => _set(key, next, coalesce: '${offer.ref.key}.$key-list');
    return GuiSection(title: title, count: goods.length, tip: tip, children: <Widget>[
      for (final Diagnostic problem in _at(path)) _problem(problem),
      for (int index = 0; index < goods.length; index++) _goods(key, title, goods, index),
      Button.ghost(
        label: '+ Add item',
        size: ButtonSize.small,
        disabled: goods.length >= 27,
        attributes: <String, String>{'aria-label': 'Add an item to $title'},
        onPressed: () => setAll(<Object?>[...goods, <String, Object?>{'item': <String, Object?>{'id': key == 'needs' ? 'minecraft:emerald' : 'minecraft:bread'}, 'amount': 1}]),
      ),
    ]);
  }

  Widget _goods(String key, String title, JsonList goods, int index) {
    final JsonMap one = asMap(goods[index]);
    final JsonMap item = asMap(one['item']);
    final String path = indexPath(childPath(_path, key), index);
    final String name = '$title ${index + 1}';
    void put(JsonMap next) => onWrite(<JsonStep>[..._steps, key, index], next, coalesce: '${offer.ref.key}.$key.$index');
    return dom.div(classes: 'animus-shop-goods-block', attributes: <String, String>{'data-path': path}, <Widget>[
      dom.div(classes: 'animus-row animus-shop-goods', <Widget>[
        ItemPicker(
          id: guiPathId(childPath(childPath(path, 'item'), 'id')),
          value: asString(item['id']) ?? '',
          label: '$name item',
          onChanged: (String id) => put(<String, Object?>{...one, 'item': <String, Object?>{...item, 'id': id.trim()}}),
        ),
        CountStepper(value: asInt(one['amount']), label: '$name amount', max: 2304, onChanged: (int? amount) => put(<String, Object?>{...one, 'amount': amount ?? 1})),
        GuiIconButton(
          icon: ArcaneIcon.x(size: IconSize.sm),
          label: 'Remove $name',
          disabled: goods.length <= 1,
          onPressed: () => _set(key, <Object?>[...goods]..removeAt(index), coalesce: '${offer.ref.key}.$key-list'),
        ),
      ]),
      for (final Diagnostic problem in _at(path, nested: true)) _problem(problem),
      GuiSection(title: 'Details', open: (asString(item['components']) ?? '').isNotEmpty, tip: 'Enchantments, custom model number and other item components.', docs: Docs.shopEntries, children: <Widget>[
        ItemDetailsEditor(item: item, label: name, onChanged: (JsonMap next) => put(<String, Object?>{...one, 'item': next})),
      ]),
    ]);
  }

  Widget _money(String type, bool container) {
    final JsonMap discount = asMap(_entry['discount']);
    return GuiSection(
      title: 'Money',
      tip: 'Paid through Vault on Bukkit servers. Mod servers have no economy, so priced offers refuse there.',
      children: <Widget>[
        _row(type == 'trade' ? 'Also costs' : (type == 'sell' ? 'Pays' : 'Price'), 'price',
            (String id) => GuiNumberInput(id: id, value: asDouble(_entry['price']), integer: false, label: type == 'sell' ? 'Pays' : 'Price', placeholder: type == 'trade' ? 'none' : '0', onChanged: (num? v) => _set('price', v)),
            tip: type == 'trade' ? 'Money on top of the items. Empty for none.' : 'For the listed count, up to 4 decimals. 0 is free.'),
        _row('Sale price', 'discount', (String id) => GuiNumberInput(id: id, value: asDouble(discount['price']), integer: false, label: 'Sale price', placeholder: 'none', onChanged: (num? v) {
              final JsonMap next = withField(discount, 'price', v);
              _set('discount', next['price'] == null ? null : next, coalesce: '${offer.ref.key}.discount.price');
            }), tip: 'A lower price between the start and end times.', problems: <Diagnostic>[..._at(childPath(_path, 'discount')), ..._at(childPath(childPath(_path, 'discount'), 'price'))]),
        if (discount.isNotEmpty) ...<Widget>[
          _row('Sale starts', 'discount.start', (String id) => GuiTextInput(id: id, value: asString(discount['start']) ?? '', label: 'Sale starts', placeholder: '2026-12-20T00:00:00Z', onChanged: (String v) => _setIn('discount', 'start', v.trim())),
              tip: 'ISO-8601 time. Empty starts now.'),
          _row('Sale ends', 'discount.end', (String id) => GuiTextInput(id: id, value: asString(discount['end']) ?? '', label: 'Sale ends', placeholder: '2026-12-27T00:00:00Z', onChanged: (String v) => _setIn('discount', 'end', v.trim())),
              tip: 'ISO-8601 time. Empty never ends.'),
        ],
      ],
    );
  }

  Widget _commands() {
    final JsonList commands = asList(_entry['commands']);
    final String path = childPath(_path, 'commands');
    return GuiSection(
      title: 'Commands',
      count: commands.length,
      tip: 'Run after the purchase. {player}, {uuid}, {amount} and {price} fill in. The shop needs its namespace on the command allow-list.',
      children: <Widget>[
        for (final Diagnostic problem in _at(path)) _problem(problem),
        for (int index = 0; index < commands.length; index++)
          dom.div(classes: 'animus-row animus-shop-goods', <Widget>[
            GuiTextInput(
              value: asString(asMap(commands[index])['command']) ?? '',
              label: 'Command ${index + 1}',
              profile: shopCommandProfile,
              placeholder: 'no leading slash',
              onChanged: (String v) => onWrite(<JsonStep>[..._steps, 'commands', index, 'command'], v, coalesce: '${offer.ref.key}.commands.$index'),
            ),
            GuiSelect(
              value: asString(asMap(commands[index])['as']),
              options: const <String>['console', 'player'],
              allowNone: true,
              noneLabel: 'console',
              hiddenLabel: 'Command ${index + 1} runs as',
              onChanged: (String? v) => onWrite(<JsonStep>[..._steps, 'commands', index, 'as'], v),
            ),
            GuiIconButton(
              icon: ArcaneIcon.x(size: IconSize.sm),
              label: 'Remove command ${index + 1}',
              disabled: commands.length <= 1,
              onPressed: () => _set('commands', <Object?>[...commands]..removeAt(index)),
            ),
          ]),
        Button.ghost(
          label: '+ Add command',
          size: ButtonSize.small,
          disabled: commands.length >= 16,
          onPressed: () => _set('commands', <Object?>[...commands, <String, Object?>{'command': 'say {player} bought this', 'as': 'console'}]),
        ),
      ],
    );
  }

  Widget _display(String type) {
    final JsonMap display = asMap(_entry['display']);
    return GuiSection(title: 'Name and lore', open: display.isNotEmpty, tip: 'What the shop shows on the icon. & codes and MiniMessage work.', children: <Widget>[
      _row('Name', 'display.name', (String id) => GuiTextInput(id: id, value: asString(display['name']) ?? '', mode: GuiTextMode.template, label: 'Display name', placeholder: '<gold>Fresh bread', onChanged: (String v) => _setIn('display', 'name', v)),
),
      GuiRow(label: 'Lore', tip: 'Lines under the name, before the price and limit lines.', child: GuiLinesEditor(lines: stringList(display['lore']), onChanged: (List<String> lines) => _setIn('display', 'lore', lines.isEmpty ? null : lines))),
      if (type != 'static')
        GuiRow(
          label: 'Shows',
          child: dom.div(classes: 'animus-row', <Widget>[
            GuiSwitch(value: display['showPrice'] != false, label: 'Price line', onChanged: (bool on) => _setIn('display', 'showPrice', on ? null : false)),
            GuiSwitch(value: display['showLimit'] != false, label: 'Limit line', onChanged: (bool on) => _setIn('display', 'showLimit', on ? null : false)),
          ]),
        ),
    ]);
  }

  Widget _rules(String type, bool container) {
    final JsonMap limit = asMap(_entry['limit']);
    final JsonMap stock = asMap(_entry['stock']);
    final bool stocked = shopEntryKeeps(type, 'stock', container: container) || _entry.containsKey('stock');
    final String unit = type == 'buy' || type == 'sell' ? 'items' : 'purchases';
    final bool open = limit.isNotEmpty || stock.isNotEmpty || _entry['permission'] != null;
    return GuiSection(title: 'Rules', open: open, tip: 'Stock, limits and who may buy. Counts survive restarts.', children: <Widget>[
      if (stocked) ...<Widget>[
        _row('Stock', 'stock', (String id) => GuiNumberInput(id: id, value: asInt(stock['maximum']), label: 'Stock', placeholder: 'unlimited', onChanged: (num? v) {
              final JsonMap next = withField(stock, 'maximum', v);
              _set('stock', next['maximum'] == null ? null : next, coalesce: '${offer.ref.key}.stock.maximum');
            }), tip: 'Units for everyone together. Empty is unlimited.'),
        if (stock.isNotEmpty)
          _row('Restock', 'stock.restockSeconds', (String id) => GuiNumberInput(id: id, value: asInt(stock['restockSeconds']), label: 'Restock seconds', placeholder: 'never', onChanged: (num? v) => _setIn('stock', 'restockSeconds', v)),
              tip: 'Seconds until stock refills. 3600 is an hour.'),
      ],
      _row('Per player', 'limit.perPlayer', (String id) => GuiNumberInput(id: id, value: asInt(limit['perPlayer']), label: 'Per player limit', placeholder: 'none', onChanged: (num? v) => _setIn('limit', 'perPlayer', v)),
          tip: 'How many $unit each player may take.'),
      _row('Global', 'limit.global', (String id) => GuiNumberInput(id: id, value: asInt(limit['global']), label: 'Global limit', placeholder: 'none', onChanged: (num? v) => _setIn('limit', 'global', v)),
          tip: 'How many $unit all players together may take.'),
      if (limit['perPlayer'] != null || limit['global'] != null || limit['resetSeconds'] != null)
        _row('Limits reset', 'limit.resetSeconds', (String id) => GuiNumberInput(id: id, value: asInt(limit['resetSeconds']), label: 'Limit reset seconds', placeholder: 'never', onChanged: (num? v) => _setIn('limit', 'resetSeconds', v)),
            tip: 'Seconds after the first purchase of a window. 86400 is a day.'),
      _row('Permission', 'permission', (String id) => GuiTextInput(id: id, value: asString(_entry['permission']) ?? '', label: 'Offer permission', placeholder: 'town.shop.vip', onChanged: (String v) => _set('permission', v.trim().isEmpty ? null : v.trim())),
          tip: 'Needed to buy. Others see it crossed out.'),
      GuiRow(
        label: 'On purchase',
        tip: 'Shift buys a full stack; a sale may take fewer items; items that do not fit drop at the feet.',
        child: dom.div(classes: 'animus-row', <Widget>[
          if (type == 'buy') GuiSwitch(value: _entry['allowStack'] == true, label: 'Shift buys a stack', onChanged: (bool on) => _set('allowStack', on ? true : null)),
          if (type == 'sell') GuiSwitch(value: _entry['sellPartial'] == true, label: 'Sell partial', onChanged: (bool on) => _set('sellPartial', on ? true : null)),
          if (type != 'sell') GuiSwitch(value: _entry['dropWhenFull'] == true, label: 'Drop when full', onChanged: (bool on) => _set('dropWhenFull', on ? true : null)),
          GuiSwitch(value: _entry['closeAfterPurchase'] == true, label: 'Close the shop', onChanged: (bool on) => _set('closeAfterPurchase', on ? true : null)),
        ]),
      ),
      _row('Broadcast', 'broadcast', (String id) => GuiTextInput(id: id, value: asString(_entry['broadcast']) ?? '', label: 'Broadcast', profile: shopBroadcastProfile, placeholder: '<gold>{player} bought {item}', onChanged: (String v) => _set('broadcast', v.isEmpty ? null : v)),
          tip: 'A chat line to everyone after a purchase. {player}, {item} and {price} fill in.'),
      _row('Matching', 'match', (String id) => GuiSelect(id: id, hiddenLabel: 'Matching', value: asString(_entry['match']), options: const <String>['strict', 'legacy'], allowNone: true, noneLabel: 'as the shop', onChanged: (String? v) => _set('match', v)),
          tip: 'How sold and traded items are compared. Legacy ignores data imports never set.'),
    ]);
  }

  Widget _problem(Diagnostic problem) => dom.span(
        classes: 'animus-gui-problem is-${problem.severity.name}',
        attributes: const <String, String>{'role': 'note'},
        <Widget>[Component.text(problem.message)],
      );
}
