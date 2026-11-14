/// One offer's form, open under its row. Simple: the kind, what it takes
/// and gives (item picker and count), the price, commands, and its name
/// and lore under More. Advanced adds the id, page and slot, item details,
/// sale prices and the rules (stock, limits, permission, purchase
/// switches). In Simple a line counts the advanced settings in use, with
/// Show to edit them here.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import '../../config/docs_links.dart';
import '../../logic/gui/gui_json.dart';
import '../../logic/gui/item_components.dart';
import '../../logic/gui/shop_board.dart';
import '../../logic/gui/shop_entries.dart';
import '../../logic/suggest/completion_sources.dart';
import '../../logic/suggest/field_profile.dart';
import '../../logic/suggest/field_profiles.dart';
import '../../model/animus_document.dart';
import '../../ui/advanced_in_use.dart';
import '../../ui/action_row.dart';
import '../../ui/icon_button.dart';
import '../../ui/alert.dart';
import '../../ui/mode.dart';
import '../../ui/section.dart';
import '../mc/item_picker.dart';
import 'component_editor.dart' show withField;
import 'gui_fields.dart';
import 'gui_rows.dart';
import '../select/animus_select.dart';
import '../suggest/autocomplete_input.dart';
import '../../ui/field.dart';
import '../../ui/number_input.dart';
import 'gui_stack.dart';
import '../../ui/stack.dart';

/// Offer kinds by plain name.
const Map<String, String> offerKindLabels = <String, String>{
  'trade': 'Trade (items for items)',
  'buy': 'Buy (player pays money)',
  'sell': 'Sell (shop pays money)',
  'command': 'Command (runs commands)',
  'static': 'Decoration',
};

/// Kinds in a sentence, for "Add ..." entries.
const Map<String, String> offerKindNouns = <String, String>{'trade': 'trade', 'buy': 'buy offer', 'sell': 'sell offer', 'command': 'command offer', 'static': 'decoration'};

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
    return dom.div(attributes: <String, String>{'role': 'group', 'aria-label': label}, <Widget>[
      Row(mainAxisSize: MainAxisSize.min, gapSize: Gap.xs, children: <Widget>[
        AppIconButton(icon: ArcaneIcon.minus(), label: 'Fewer: $label', disabled: shown <= min, onPressed: () => onChanged((shown - 1).clamp(min, max))),
        NumberInput(id: id, value: value, label: label, digits: '$max'.length, onChanged: (num? next) => onChanged(next?.toInt())),
        AppIconButton(icon: ArcaneIcon.plus(), label: 'More: $label', disabled: shown >= max, onPressed: () => onChanged((shown + 1).clamp(min, max))),
      ]),
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
    return stack(spacing: 16, <Widget>[
      if (parts != null && enchantments != null) ...<Widget>[
        OrderedRows(
          label: '$label enchantments',
          onDelete: (int index) => setEnchantments(<String, int>{...enchantments}..remove(listed[index].key)),
          rows: <OrderedRow>[
            for (int index = 0; index < listed.length; index++)
              OrderedRow(
                key: '$index',
                name: 'enchantment ${index + 1}',
                line: fieldLine(<Widget>[
            AutocompleteInput(
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
                ]),
              ),
          ],
        ),
        ActionRow(
          dense: true,
          label: '$label enchantments',
          secondary: <RowAction>[
            RowAction(
              'Add enchantment',
              () => setEnchantments(<String, int>{...enchantments, if (!enchantments.containsKey('minecraft:unbreaking')) 'minecraft:unbreaking': 1 else 'minecraft:mending': 1}),
              icon: ArcaneIcon.plus(),
            ),
          ],
        ),
      ],
      if (parts != null && (model == null || modelNumber != null))
        Field(
          label: 'Model number',
          tip: 'custom_model_data for a resource pack model. Empty for the normal look.',
          child: NumberInput(
            value: modelNumber,
            integer: false,
            label: '$label model number',
            onChanged: (num? number) => _put(writeComponents(withComponent(parts, 'custom_model_data', number == null ? null : writeModelNumber(number)))),
          ),
        ),
      Field(
        label: 'Components',
        tip: 'Every item component in /give syntax, such as [custom_name="Fresh"]. The fields above write here.',
        child: AutocompleteInput(value: text, label: '$label components', placeholder: '[custom_name="Fresh"]', onChanged: (String next) => _put(next.trim().isEmpty ? null : next.trim()), profile: FieldProfile.none),
      ),
      if (parts == null) const AppAlert(tone: AlertTone.warning, message: 'Not a [key=value,...] list, so only the text is editable.'),
      if (parts != null && enchantments == null) const AppAlert(message: 'Enchantments use a form this editor does not read; edit them as text.'),
    ]);
  }
}

class ShopOfferEditor extends StatefulWidget {
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

  /// Every diagnostic of the shop; the form shows the offer's own.
  final List<Diagnostic> diagnostics;
  final ShopWrite onWrite;

  /// Board edits that move the offer: kind, page and slot.
  final void Function(ShopEdit edit) onMove;

  @override
  State<ShopOfferEditor> createState() => _ShopOfferEditorState();
}

/// Offer keys only Advanced edits, each with its default left out.
int offerAdvancedInUse(JsonMap entry) {
  final JsonMap display = asMap(entry['display']);
  return <bool>[
    entry['levels'] != null,
    entry['discount'] != null,
    entry['stock'] != null,
    asMap(entry['limit']).isNotEmpty,
    entry['permission'] != null,
    entry['allowStack'] == true,
    entry['sellPartial'] == true,
    entry['dropWhenFull'] == true,
    entry['closeAfterPurchase'] == true,
    entry['broadcast'] != null,
    entry['match'] != null,
    display['showPrice'] == false,
    display['showLimit'] == false,
    (asString(asMap(entry['item'])['components']) ?? '').isNotEmpty,
    for (final String key in const <String>['needs', 'gives'])
      for (final Object? goods in asList(entry[key])) (asString(asMap(asMap(goods)['item'])['components']) ?? '').isNotEmpty,
  ].where((bool on) => on).length;
}

class _ShopOfferEditorState extends State<ShopOfferEditor> {
  /// Simple shows the advanced parts of this offer after Show.
  bool _reveal = false;

  JsonMap get shop => component.shop;
  BoardOffer get offer => component.offer;
  List<JsonStep> get _steps => offer.ref.steps;
  String get _path => offer.ref.path;
  JsonMap get _entry => offer.entry;

  void _set(String key, Object? value, {String? coalesce}) => component.onWrite(<JsonStep>[..._steps, key], value, coalesce: coalesce ?? '${offer.ref.key}.$key');

  void _setIn(String group, String key, Object? value) {
    final JsonMap next = withField(asMap(_entry[group]), key, value);
    _set(group, next.isEmpty ? null : next, coalesce: '${offer.ref.key}.$group.$key');
  }

  List<Diagnostic> _at(String path, {bool nested = false}) => diagnosticsAt(component.diagnostics, path, nested: nested);

  /// A labelled field for [key] of the offer; dots in [key] step into
  /// objects (`limit.perPlayer`).
  Widget _row(String label, String key, Widget Function(String id) child, {String? tip, List<Diagnostic>? problems}) {
    final String path = key.split('.').fold(_path, childPath);
    final String id = guiPathId(path);
    return Field(label: label, forId: id, tip: tip, problems: problems ?? _at(path, nested: true), child: child(id));
  }

  @override
  Widget build(BuildContext context) {
    final bool advanced = ModeScope.advanced(context) || _reveal;
    final String type = offer.type;
    final bool container = isContainerShop(shop);
    final bool money = type == 'buy' || type == 'sell' || type == 'command' || (type == 'trade' && !container);
    final List<String> kinds = <String>[for (final String kind in shopOfferKinds) if (!container || kind == 'trade' || kind == 'static') kind];
    final int hidden = advanced ? 0 : offerAdvancedInUse(_entry);
    return dom.div(attributes: <String, String>{'data-path': _path}, <Widget>[stack(spacing: 16, <Widget>[
      for (final Diagnostic problem in _at(_path)) AppAlert(tone: problem.severity == DiagnosticSeverity.error ? AlertTone.error : AlertTone.warning, message: problem.message),
      Field(
        label: 'Kind',
        forId: '${guiPathId(_path)}-kind',
        tip: 'Changing it keeps the fields both kinds share and moves the offer to that kind\'s mode.',
        problems: _at(childPath(_path, 'type')),
        child: AnimusSelect.values(
          id: '${guiPathId(_path)}-kind',
          value: type,
          values: kinds,
          labels: offerKindLabels,
          onChanged: (String? kind) {
            if (kind != null && kind != type) component.onMove(setOfferKind(shop, offer.ref, kind));
          },
        ),
      ),
      if (type == 'trade') ...<Widget>[
        _goodsList('needs', 'Takes', 'What the player hands over. The trading screen shows the first two.', advanced),
        _goodsList('gives', 'Gives', 'What the player gets. The trading screen shows the first.', advanced),
      ] else
        _item(type, advanced),
      if (money) _price(type, advanced),
      if (type == 'command') _commands(),
      MoreFields(label: 'Name and lore', open: asMap(_entry['display']).isNotEmpty && !advanced, children: <Widget>[_display(type, advanced)]),
      if (hidden > 0)
        AdvancedInUse(count: hidden, onShow: () => setState(() => _reveal = true)),
      if (advanced) ...<Widget>[
        _placement(),
        if (type != 'static') _rules(type, container),
      ],
    ])]);
  }

  Widget _placement() {
    final JsonList pages = asList(shop['pages']);
    final int rows = shopPageRows(shop, offer.ref.page);
    return AppSection(title: 'Placement', open: false, children: <Widget>[
      _row('Id', 'id', (String id) => AutocompleteInput(id: id, value: offer.id, label: 'Offer id', onChanged: (String v) => _set('id', v.trim()), profile: FieldProfile.none),
          tip: 'Unique in the shop. Limits and stock are kept under it, so keep it once players have bought.'),
      if (pages.length > 1)
        Field(
          label: 'Page',
          forId: '${guiPathId(_path)}-page',
          child: AnimusSelect.values(
            id: '${guiPathId(_path)}-page',
            value: '${offer.ref.page}',
            values: <String>[for (int i = 0; i < pages.length; i++) '$i'],
            labels: <String, String>{for (int i = 0; i < pages.length; i++) '$i': asString(asMap(pages[i])['name']) ?? 'Page ${i + 1}'},
            onChanged: (String? page) {
              if (page != null) component.onMove(moveOfferToPage(shop, offer.ref, int.parse(page)));
            },
          ),
        ),
      Field(
        label: 'Slot',
        forId: '${guiPathId(_path)}-slot',
        tip: 'Where the chest window draws it, 1 at the top left. An offer already there swaps places.',
        child: NumberInput(
          id: '${guiPathId(_path)}-slot',
          value: offer.ref.slot + 1,
          label: 'Slot',
          digits: 2,
          onChanged: (num? shown) {
            if (shown != null && shown >= 1 && shown <= rows * 9) component.onMove(moveOfferToSlot(shop, offer.ref, shown.toInt() - 1));
          },
        ),
      ),
    ]);
  }

  Widget _details(JsonMap item, String label, void Function(JsonMap next) put) =>
      AppSection(title: 'Details', open: (asString(item['components']) ?? '').isNotEmpty, tip: 'Enchantments, custom model number and other item components.', docs: Docs.shopEntries, children: <Widget>[
        ItemDetailsEditor(item: item, label: label, onChanged: put),
      ]);

  Widget _item(String type, bool advanced) {
    final JsonMap item = asMap(_entry['item']);
    final String path = childPath(_path, 'item');
    final String label = switch (type) { 'sell' => 'Takes', 'static' => 'Shows', _ => 'Gives' };
    void put(JsonMap next) => _set('item', next);
    return stack(<Widget>[
      Field(
        label: label,
        forId: guiPathId(childPath(path, 'id')),
        tip: type == 'command' ? 'The icon players click. A command offer sells only its commands.' : 'The item and how many, 1 to 99.',
        problems: _at(path, nested: true),
        child: fieldLine(<Widget>[
          ItemPicker(
            id: guiPathId(childPath(path, 'id')),
            value: asString(item['id']) ?? '',
            label: '$label item',
            onChanged: (String id) => put(<String, Object?>{...item, 'id': id.trim()}),
          ),
          CountStepper(value: asInt(item['count']), label: '$label count', onChanged: (int? count) => put(withField(item, 'count', count == 1 ? null : count))),
        ]),
      ),
      if (advanced) _details(item, label, put),
    ]);
  }

  Widget _goodsList(String key, String title, String tip, bool advanced) {
    final JsonList goods = asList(_entry[key]);
    final String path = childPath(_path, key);
    void setAll(JsonList next) => _set(key, next, coalesce: '${offer.ref.key}.$key-list');
    return Field(
      label: title,
      tip: tip,
      problems: _at(path),
      child: stack(<Widget>[
        OrderedRows(
          label: title,
          dense: false,
          rows: <OrderedRow>[for (int index = 0; index < goods.length; index++) _goods(key, title, goods, index, advanced)],
          onMove: (int from, int to) => setAll(moveItem(goods, from, to)),
          onDelete: goods.length <= 1 ? null : (int index) => setAll(<Object?>[...goods]..removeAt(index)),
        ),
        ActionRow(
          dense: true,
          label: title,
          secondary: <RowAction>[
            RowAction(
              'Add item',
              goods.length >= 27 ? null : () => setAll(<Object?>[...goods, <String, Object?>{'item': <String, Object?>{'id': key == 'needs' ? 'minecraft:emerald' : 'minecraft:bread'}, 'amount': 1}]),
              icon: ArcaneIcon.plus(),
              attributes: <String, String>{'aria-label': 'Add an item to $title'},
            ),
          ],
        ),
      ]),
    );
  }

  OrderedRow _goods(String key, String title, JsonList goods, int index, bool advanced) {
    final JsonMap one = asMap(goods[index]);
    final JsonMap item = asMap(one['item']);
    final String path = indexPath(childPath(_path, key), index);
    final String name = '$title ${index + 1}';
    void put(JsonMap next) => component.onWrite(<JsonStep>[..._steps, key, index], next, coalesce: '${offer.ref.key}.$key.$index');
    final List<Diagnostic> problems = _at(path, nested: true);
    return OrderedRow(
      key: '$key-$index',
      name: name.toLowerCase(),
      open: advanced || problems.isNotEmpty,
      attributes: <String, String>{'data-path': path},
      line: fieldLine(<Widget>[
        ItemPicker(
          id: guiPathId(childPath(childPath(path, 'item'), 'id')),
          value: asString(item['id']) ?? '',
          label: '$name item',
          onChanged: (String id) => put(<String, Object?>{...one, 'item': <String, Object?>{...item, 'id': id.trim()}}),
        ),
        CountStepper(value: asInt(one['amount']), label: '$name amount', max: 2304, onChanged: (int? amount) => put(<String, Object?>{...one, 'amount': amount ?? 1})),
      ]),
      body: stack(<Widget>[
        for (final Diagnostic problem in problems) AppAlert(tone: problem.severity == DiagnosticSeverity.error ? AlertTone.error : AlertTone.warning, message: problem.message),
        if (advanced) _details(item, name, (JsonMap next) => put(<String, Object?>{...one, 'item': next})),
      ]),
    );
  }

  Widget _price(String type, bool advanced) {
    final JsonMap discount = asMap(_entry['discount']);
    return stack(<Widget>[
      _row(type == 'trade' ? 'Also costs' : (type == 'sell' ? 'Pays' : 'Price'), 'price',
          (String id) => NumberInput(id: id, value: asDouble(_entry['price']), integer: false, label: type == 'sell' ? 'Pays' : 'Price', placeholder: type == 'trade' ? 'none' : '0', onChanged: (num? v) => _set('price', v)),
          tip: type == 'trade' ? 'Money on top of the items. Empty for none.' : 'Money for the listed count. 0 is free. Paid through Vault on Bukkit servers.'),
      if (advanced) ...<Widget>[
        if (type != 'sell')
          _row('Levels', 'levels',
              (String id) => NumberInput(id: id, value: asDouble(_entry['levels']), integer: true, label: 'Levels', placeholder: 'none', onChanged: (num? v) => _set('levels', v == null || v == 0 ? null : v.toInt())),
              tip: 'Experience levels taken with the price. Empty for none.'),
        _row('Sale price', 'discount', (String id) => NumberInput(id: id, value: asDouble(discount['price']), integer: false, label: 'Sale price', placeholder: 'none', onChanged: (num? v) {
              final JsonMap next = withField(discount, 'price', v);
              _set('discount', next['price'] == null ? null : next, coalesce: '${offer.ref.key}.discount.price');
            }), tip: 'A lower price between the start and end times.', problems: <Diagnostic>[..._at(childPath(_path, 'discount')), ..._at(childPath(childPath(_path, 'discount'), 'price'))]),
        if (discount.isNotEmpty) ...<Widget>[
          _row('Sale starts', 'discount.start', (String id) => AutocompleteInput(id: id, value: asString(discount['start']) ?? '', label: 'Sale starts', placeholder: '2026-12-20T00:00:00Z', onChanged: (String v) => _setIn('discount', 'start', v.trim()), profile: FieldProfile.none),
              tip: 'A date and time such as 2026-12-20T00:00:00Z. Empty starts now.'),
          _row('Sale ends', 'discount.end', (String id) => AutocompleteInput(id: id, value: asString(discount['end']) ?? '', label: 'Sale ends', placeholder: '2026-12-27T00:00:00Z', onChanged: (String v) => _setIn('discount', 'end', v.trim()), profile: FieldProfile.none),
              tip: 'A date and time such as 2026-12-27T00:00:00Z. Empty never ends.'),
        ],
      ],
    ]);
  }

  Widget _commands() {
    final JsonList commands = asList(_entry['commands']);
    final String path = childPath(_path, 'commands');
    return Field(
      label: 'Commands',
      tip: 'Run after the purchase. {player}, {uuid}, {amount} and {price} fill in.',
      problems: _at(path),
      child: stack(<Widget>[
        OrderedRows(
          label: 'Commands',
          dense: false,
          rows: <OrderedRow>[
            for (int index = 0; index < commands.length; index++)
              OrderedRow(
                key: '$index',
                name: 'command ${index + 1}',
                line: fieldLine(<Widget>[
                  AutocompleteInput(
                    value: asString(asMap(commands[index])['command']) ?? '',
                    label: 'Command ${index + 1}',
                    profile: shopCommandProfile,
                    placeholder: 'give {player} diamond 1',
                    onChanged: (String v) => component.onWrite(<JsonStep>[..._steps, 'commands', index, 'command'], v, coalesce: '${offer.ref.key}.commands.$index'),
                  ),
                  AnimusSelect.values(
                    value: asString(asMap(commands[index])['as']),
                    values: const <String>['console', 'player'],
                    labels: const <String, String>{'console': 'Console', 'player': 'Player'},
                    allowNone: true,
                    noneLabel: 'Console',
                    hiddenLabel: 'Command ${index + 1} runs as',
                    onChanged: (String? v) => component.onWrite(<JsonStep>[..._steps, 'commands', index, 'as'], v),
                  ),
                ]),
              ),
          ],
          onMove: (int from, int to) => _set('commands', moveItem(commands, from, to)),
          onDelete: commands.length <= 1 ? null : (int index) => _set('commands', <Object?>[...commands]..removeAt(index)),
        ),
        ActionRow(
          dense: true,
          label: 'Commands',
          secondary: <RowAction>[
            RowAction('Add command', commands.length >= 16 ? null : () => _set('commands', <Object?>[...commands, <String, Object?>{'command': '', 'as': 'console'}]), icon: ArcaneIcon.plus()),
          ],
        ),
      ]),
    );
  }

  Widget _display(String type, bool advanced) {
    final JsonMap display = asMap(_entry['display']);
    return stack(<Widget>[
      _row('Name', 'display.name', (String id) => AutocompleteInput(id: id, value: asString(display['name']) ?? '', profile: FieldProfile.guiText, preview: itemNameSample, label: 'Display name', placeholder: '&6Fresh bread', onChanged: (String v) => _setIn('display', 'name', v))),
      Field(label: 'Lore', tip: 'Lines under the name, before the price and limit lines.', child: GuiLines(label: 'Lore', lines: stringList(display['lore']), onChanged: (List<String> lines) => _setIn('display', 'lore', lines.isEmpty ? null : lines))),
      if (advanced && type != 'static')
        Field(
          label: 'Shows',
          child: switchRow(<Widget>[
            ArcaneToggleSwitch(value: display['showPrice'] != false, label: 'Price line', onChanged: (bool on) => _setIn('display', 'showPrice', on ? null : false), size: ComponentSize.sm),
            ArcaneToggleSwitch(value: display['showLimit'] != false, label: 'Limit line', onChanged: (bool on) => _setIn('display', 'showLimit', on ? null : false), size: ComponentSize.sm),
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
    return AppSection(title: 'Rules', open: open, tip: 'Stock, limits and who may buy. Counts survive restarts.', children: <Widget>[
      if (stocked) ...<Widget>[
        _row('Stock', 'stock', (String id) => NumberInput(id: id, value: asInt(stock['maximum']), label: 'Stock', placeholder: 'unlimited', onChanged: (num? v) {
              final JsonMap next = withField(stock, 'maximum', v);
              _set('stock', next['maximum'] == null ? null : next, coalesce: '${offer.ref.key}.stock.maximum');
            }), tip: 'Units for everyone together. Empty is unlimited.'),
        if (stock.isNotEmpty)
          _row('Restock', 'stock.restockSeconds', (String id) => NumberInput(id: id, value: asInt(stock['restockSeconds']), label: 'Restock seconds', placeholder: 'never', onChanged: (num? v) => _setIn('stock', 'restockSeconds', v)),
              tip: 'Seconds until stock refills. 3600 is an hour.'),
      ],
      _row('Per player', 'limit.perPlayer', (String id) => NumberInput(id: id, value: asInt(limit['perPlayer']), label: 'Per player limit', placeholder: 'none', onChanged: (num? v) => _setIn('limit', 'perPlayer', v)),
          tip: 'How many $unit each player may take.'),
      _row('All players', 'limit.global', (String id) => NumberInput(id: id, value: asInt(limit['global']), label: 'Global limit', placeholder: 'none', onChanged: (num? v) => _setIn('limit', 'global', v)),
          tip: 'How many $unit all players together may take.'),
      if (limit['perPlayer'] != null || limit['global'] != null || limit['resetSeconds'] != null)
        _row('Limits reset', 'limit.resetSeconds', (String id) => NumberInput(id: id, value: asInt(limit['resetSeconds']), label: 'Limit reset seconds', placeholder: 'never', onChanged: (num? v) => _setIn('limit', 'resetSeconds', v)),
            tip: 'Seconds after the first purchase. 86400 is a day.'),
      _row('Permission', 'permission', (String id) => AutocompleteInput(id: id, value: asString(_entry['permission']) ?? '', label: 'Offer permission', onChanged: (String v) => _set('permission', v.trim().isEmpty ? null : v.trim()), profile: FieldProfile.none),
          tip: 'Needed to buy. Others see it crossed out.'),
      Field(
        label: 'On purchase',
        tip: 'Shift buys a full stack; a sale may take fewer items; items that do not fit drop at the feet.',
        child: switchRow(<Widget>[
          if (type == 'buy') ArcaneToggleSwitch(value: _entry['allowStack'] == true, label: 'Shift buys a stack', onChanged: (bool on) => _set('allowStack', on ? true : null), size: ComponentSize.sm),
          if (type == 'sell') ArcaneToggleSwitch(value: _entry['sellPartial'] == true, label: 'Sell partial', onChanged: (bool on) => _set('sellPartial', on ? true : null), size: ComponentSize.sm),
          if (type != 'sell') ArcaneToggleSwitch(value: _entry['dropWhenFull'] == true, label: 'Drop when full', onChanged: (bool on) => _set('dropWhenFull', on ? true : null), size: ComponentSize.sm),
          ArcaneToggleSwitch(value: _entry['closeAfterPurchase'] == true, label: 'Close after', onChanged: (bool on) => _set('closeAfterPurchase', on ? true : null), size: ComponentSize.sm),
        ]),
      ),
      _row('Broadcast', 'broadcast', (String id) => AutocompleteInput(id: id, value: asString(_entry['broadcast']) ?? '', label: 'Broadcast', profile: shopBroadcastProfile, placeholder: '&6{player} bought {item}', onChanged: (String v) => _set('broadcast', v.isEmpty ? null : v)),
          tip: 'A chat line to everyone after a purchase. {player}, {item} and {price} fill in.'),
      _row('Item matching', 'match', (String id) => AnimusSelect.values(
            id: id,
            value: asString(_entry['match']),
            values: const <String>['strict', 'legacy'],
            labels: const <String, String>{'strict': 'Exact', 'legacy': 'Loose (imported shops)'},
            allowNone: true,
            noneLabel: 'As the shop',
            onChanged: (String? v) => _set('match', v),
          ), tip: 'How sold and traded items are compared.'),
    ]);
  }
}
