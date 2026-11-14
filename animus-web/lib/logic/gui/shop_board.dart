/// The shop editor's offer list: every entry of a shop as one row, in the
/// order the trading screen and layout GUIs list offers (enabled modes in
/// `modes` order, then pages, then slots), and the edits the board makes:
/// reorder within a page's grid, duplicate, add, remove and restore, change
/// kind and move to another page. Every edit returns the new shop and where
/// the offer it touched now lives.
library;

import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../../model/document_summary.dart' show plainName;
import 'gui_json.dart';
import 'gui_validation.dart' show shopControlAt, shopGridsFor, shopModes;
import 'shop_entries.dart';
import 'shop_offers.dart';

/// Offer kinds, in the order the add menu lists them.
const List<String> shopOfferKinds = <String>['trade', 'buy', 'sell', 'command', 'static'];

/// Where an entry lives: a page index, its grid (`buy`, `sell`, `trade`) and
/// slot.
final class OfferRef {
  const OfferRef(this.page, this.grid, this.slot);

  final int page;
  final String grid;
  final int slot;

  /// A stable text form, `page:grid:slot`, for element ids and selection.
  String get key => '$page:$grid:$slot';

  List<JsonStep> get steps => <JsonStep>['pages', page, grid, '$slot'];

  String get path => formatPath(steps);

  bool sameGroup(OfferRef other) => other.page == page && other.grid == grid;

  static OfferRef? parse(String key) {
    final List<String> parts = key.split(':');
    if (parts.length != 3) return null;
    final int? page = int.tryParse(parts[0]);
    final int? slot = int.tryParse(parts[2]);
    if (page == null || slot == null || !shopModes.contains(parts[1])) return null;
    return OfferRef(page, parts[1], slot);
  }

  @override
  bool operator ==(Object other) => other is OfferRef && other.page == page && other.grid == grid && other.slot == slot;

  @override
  int get hashCode => Object.hash(page, grid, slot);

  @override
  String toString() => key;
}

/// One row of the board.
final class BoardOffer {
  const BoardOffer(this.ref, this.entry, {required this.modeOn});

  final OfferRef ref;
  final JsonMap entry;

  /// False when the entry's grid is not in the shop's `modes`, so players
  /// never see it.
  final bool modeOn;

  String get type => asString(entry['type']) ?? 'static';
  String get id => asString(entry['id']) ?? '';
}

/// The new shop after a board edit and where the offer it touched lives;
/// [ref] is null when nothing changed.
typedef ShopEdit = ({JsonMap shop, OfferRef? ref});

/// Modes the shop shows, in switch order.
List<String> enabledShopModes(JsonMap shop) {
  final List<String> modes = <String>[for (final Object? mode in asList(shop['modes'])) if (mode is String && shopModes.contains(mode)) mode];
  return modes.isEmpty ? shopModes : modes;
}

int shopPageRows(JsonMap shop, int page) {
  final JsonList pages = asList(shop['pages']);
  if (page < 0 || page >= pages.length) return 3;
  return (asInt(asMap(pages[page])['rows']) ?? 3).clamp(1, 6);
}

List<int> _slotsOf(JsonMap grid) => <int>[for (final String key in grid.keys) if (int.tryParse(key) != null) int.parse(key)]..sort();

/// Every entry: enabled modes in order, then the modes that are off; within
/// a mode by page, then slot.
List<BoardOffer> boardOffers(JsonMap shop) {
  final List<String> enabled = enabledShopModes(shop);
  final List<String> order = <String>[...enabled, for (final String mode in shopModes) if (!enabled.contains(mode)) mode];
  final JsonList pages = asList(shop['pages']);
  return <BoardOffer>[
    for (final String mode in order)
      for (int page = 0; page < pages.length; page++)
        for (final int slot in _slotsOf(asMap(asMap(pages[page])[mode])))
          if (asMap(asMap(pages[page])[mode])['$slot'] is Map<String, Object?>)
            BoardOffer(OfferRef(page, mode, slot), asMap(asMap(asMap(pages[page])[mode])['$slot']), modeOn: enabled.contains(mode)),
  ];
}

/// The board row at [ref], or null.
BoardOffer? boardOfferAt(JsonMap shop, OfferRef ref) {
  final Object? entry = readIn(shop, ref.steps);
  if (entry is! Map<String, Object?>) return null;
  return BoardOffer(ref, entry, modeOn: enabledShopModes(shop).contains(ref.grid));
}

/// Slots of a page with [rows] rows that an offer can take: every slot not
/// under a bottom-row control.
List<int> offerSlots(int rows) => <int>[for (int slot = 0; slot < rows * 9; slot++) if (shopControlAt(slot, rows) == null) slot];

JsonList _pages(JsonMap shop) => <Object?>[for (final Object? page in asList(shop['pages'])) deepCopy(page)];

JsonMap _withPages(JsonMap shop, JsonList pages) => <String, Object?>{...shop, 'pages': pages};

/// [grid] rebuilt with [entries] by slot, numeric keys ascending after the
/// keys that are not slots.
JsonMap _grid(JsonMap grid, Map<int, Object?> entries) {
  final List<int> slots = entries.keys.toList()..sort();
  return <String, Object?>{
    for (final MapEntry<String, Object?> field in grid.entries)
      if (int.tryParse(field.key) == null) field.key: field.value,
    for (final int slot in slots) '$slot': entries[slot],
  };
}

Map<int, Object?> _entries(JsonMap grid) => <int, Object?>{for (final int slot in _slotsOf(grid)) slot: grid['$slot']};

/// The offer at [from] moved to position [to] among the offers of its page
/// and grid; the others shift and every offer keeps one of the same slots.
ShopEdit reorderOffer(JsonMap shop, OfferRef from, int to) {
  final JsonList pages = _pages(shop);
  if (from.page < 0 || from.page >= pages.length) return (shop: shop, ref: null);
  final JsonMap page = asMap(pages[from.page]);
  final JsonMap grid = asMap(page[from.grid]);
  final List<int> slots = _slotsOf(grid);
  final int index = slots.indexOf(from.slot);
  if (index < 0 || to < 0 || to >= slots.length) return (shop: shop, ref: null);
  if (index == to) return (shop: shop, ref: from);
  final List<Object?> ordered = <Object?>[for (final int slot in slots) grid['$slot']];
  final Object? moved = ordered.removeAt(index);
  ordered.insert(to, moved);
  final Map<int, Object?> placed = <int, Object?>{for (int i = 0; i < slots.length; i++) slots[i]: ordered[i]};
  pages[from.page] = <String, Object?>{...page, from.grid: _grid(grid, placed)};
  return (shop: _withPages(shop, pages), ref: OfferRef(from.page, from.grid, slots[to]));
}

/// The offer at [ref] one place earlier (negative [delta]) or later among
/// the offers of its page and grid.
ShopEdit nudgeOffer(JsonMap shop, OfferRef ref, int delta) {
  final List<int> slots = _slotsOf(asMap(readIn(shop, <JsonStep>['pages', ref.page, ref.grid])));
  final int index = slots.indexOf(ref.slot);
  if (index < 0) return (shop: shop, ref: null);
  return reorderOffer(shop, ref, index + delta);
}

/// Where the offer at [ref] sits among the offers of its page and grid,
/// and how many there are.
(int, int) offerPosition(JsonMap shop, OfferRef ref) {
  final List<int> slots = _slotsOf(asMap(readIn(shop, <JsonStep>['pages', ref.page, ref.grid])));
  return (slots.indexOf(ref.slot), slots.length);
}

/// Every entry id in the shop.
Set<String> shopEntryIds(JsonMap shop) => <String>{
      for (final BoardOffer offer in boardOffers(shop))
        if (offer.id.isNotEmpty) offer.id,
    };

/// [base] made a valid entry id the shop does not use yet.
String freshEntryId(JsonMap shop, String base) {
  final Set<String> taken = shopEntryIds(shop);
  String clean = base.toLowerCase().replaceAll(RegExp(r'[^a-z0-9_-]+'), '-').replaceAll(RegExp(r'^-+|-+$'), '');
  if (clean.isEmpty) clean = 'offer';
  if (clean.length > 56) clean = clean.substring(0, 56);
  if (!taken.contains(clean)) return clean;
  int n = 2;
  while (taken.contains('$clean-$n')) {
    n++;
  }
  return '$clean-$n';
}

/// [entry] placed on [page] of the shop in [grid]: the first free slot
/// after [after] when given, else the first free slot. A full page grows a
/// row, up to 6; a full 6-row page sends the offer to a new page.
ShopEdit placeOffer(JsonMap shop, int page, String grid, JsonMap entry, {int? after}) {
  final JsonList pages = _pages(shop);
  if (pages.isEmpty) pages.add(<String, Object?>{'name': 'Page 1', 'rows': 3});
  int target = page.clamp(0, pages.length - 1);
  while (true) {
    final JsonMap data = asMap(pages[target]);
    final JsonMap cells = asMap(data[grid]);
    int rows = (asInt(data['rows']) ?? 3).clamp(1, 6);
    while (true) {
      final List<int> free = <int>[for (final int slot in offerSlots(rows)) if (!cells.containsKey('$slot')) slot];
      if (free.isNotEmpty) {
        final int? near = after;
        final int slot = near == null ? free.first : free.firstWhere((int s) => s > near, orElse: () => free.first);
        final Map<int, Object?> placed = <int, Object?>{..._entries(cells), slot: entry};
        pages[target] = <String, Object?>{...data, 'rows': rows, grid: _grid(cells, placed)};
        if (asInt(data['rows']) == null && rows == 3) (pages[target] as JsonMap).remove('rows');
        return (shop: _withPages(shop, pages), ref: OfferRef(target, grid, slot));
      }
      if (rows >= 6) break;
      rows++;
    }
    if (target == pages.length - 1) pages.add(<String, Object?>{'name': 'Page ${pages.length + 1}', 'rows': 3});
    target++;
    after = null;
  }
}

/// A new entry of [kind] with [id], with the fields that kind needs.
JsonMap starterOffer(String kind, String id, {bool container = false}) => switch (kind) {
      'trade' => withShopEntryType(<String, Object?>{'id': id, 'item': <String, Object?>{'id': 'minecraft:bread'}}, 'trade', container: container),
      'buy' => withShopEntryType(<String, Object?>{'id': id, 'item': <String, Object?>{'id': 'minecraft:bread'}}, 'buy', container: container),
      'sell' => withShopEntryType(<String, Object?>{'id': id, 'item': <String, Object?>{'id': 'minecraft:wheat', 'count': 16}}, 'sell', container: container),
      'command' => withShopEntryType(<String, Object?>{'id': id, 'item': <String, Object?>{'id': 'minecraft:paper'}}, 'command', container: container),
      _ => <String, Object?>{'id': id, 'type': 'static', 'item': <String, Object?>{'id': 'minecraft:gray_stained_glass_pane'}},
    };

/// The grid a [kind] offer goes in; a decoration goes in [current] when it
/// is a grid, else the shop's first mode.
String gridForKind(JsonMap shop, String kind, {String? current}) {
  if (kind == 'static') return current != null && shopModes.contains(current) ? current : enabledShopModes(shop).first;
  return shopGridsFor(kind).first;
}

/// A new [kind] offer on [page].
ShopEdit addOffer(JsonMap shop, String kind, {int page = 0}) {
  final bool container = isContainerShop(shop);
  final String id = freshEntryId(shop, kind == 'static' ? 'decoration' : kind);
  return placeOffer(shop, page, gridForKind(shop, kind), starterOffer(kind, id, container: container));
}

/// A copy of the offer at [ref] with a fresh id, in the next free slot of
/// the same page and grid.
ShopEdit duplicateOffer(JsonMap shop, OfferRef ref) {
  final Object? entry = readIn(shop, ref.steps);
  if (entry is! Map<String, Object?>) return (shop: shop, ref: null);
  final JsonMap copy = asMap(deepCopy(entry));
  copy['id'] = freshEntryId(shop, '${asString(entry['id']) ?? 'offer'}-copy');
  return placeOffer(shop, ref.page, ref.grid, copy, after: ref.slot);
}

/// An entry the board removed, kept so Undo can put it back.
final class RemovedOffer {
  const RemovedOffer(this.ref, this.entry);

  final OfferRef ref;
  final JsonMap entry;
}

/// The shop without the entries at [refs].
({JsonMap shop, List<RemovedOffer> removed}) removeOffers(JsonMap shop, Iterable<OfferRef> refs) {
  final JsonList pages = _pages(shop);
  final List<RemovedOffer> removed = <RemovedOffer>[];
  for (final OfferRef ref in refs) {
    if (ref.page < 0 || ref.page >= pages.length) continue;
    final JsonMap page = asMap(pages[ref.page]);
    final JsonMap grid = asMap(page[ref.grid]);
    final Object? entry = grid['${ref.slot}'];
    if (entry is! Map<String, Object?>) continue;
    removed.add(RemovedOffer(ref, entry));
    final JsonMap next = <String, Object?>{...grid}..remove('${ref.slot}');
    final JsonMap updated = <String, Object?>{...page, ref.grid: next};
    if (next.isEmpty) updated.remove(ref.grid);
    pages[ref.page] = updated;
  }
  return (shop: removed.isEmpty ? shop : _withPages(shop, pages), removed: removed);
}

/// [removed] put back: each at its old slot when that is still free, else
/// in the first free slot of its page and grid.
JsonMap restoreOffers(JsonMap shop, List<RemovedOffer> removed) {
  JsonMap out = shop;
  for (final RemovedOffer offer in removed) {
    final JsonList pages = asList(out['pages']);
    final bool pageThere = offer.ref.page < pages.length;
    if (pageThere && readIn(out, offer.ref.steps) == null) {
      final JsonMap grid = asMap(readIn(out, <JsonStep>['pages', offer.ref.page, offer.ref.grid]));
      final JsonList next = _pages(out);
      next[offer.ref.page] = <String, Object?>{
        ...asMap(next[offer.ref.page]),
        offer.ref.grid: _grid(grid, <int, Object?>{..._entries(grid), offer.ref.slot: offer.entry}),
      };
      out = _withPages(out, next);
    } else {
      out = placeOffer(out, offer.ref.page, offer.ref.grid, offer.entry).shop;
    }
  }
  return out;
}

/// The offer at [ref] as a [kind] offer: fields the kind refuses go, the
/// ones it needs are added, and it moves to that kind's grid when needed.
ShopEdit setOfferKind(JsonMap shop, OfferRef ref, String kind) {
  final Object? entry = readIn(shop, ref.steps);
  if (entry is! Map<String, Object?>) return (shop: shop, ref: null);
  final JsonMap changed = withShopEntryType(entry, kind, container: isContainerShop(shop));
  final String grid = gridForKind(shop, kind, current: ref.grid);
  if (grid == ref.grid) return (shop: writeIn(shop, ref.steps, changed)! as JsonMap, ref: ref);
  final JsonMap without = removeOffers(shop, <OfferRef>[ref]).shop;
  return placeOffer(without, ref.page, grid, changed, after: ref.slot - 1);
}

/// The offer at [ref] moved to [page], same grid, first free slot.
ShopEdit moveOfferToPage(JsonMap shop, OfferRef ref, int page) {
  if (page == ref.page) return (shop: shop, ref: ref);
  final Object? entry = readIn(shop, ref.steps);
  if (entry is! Map<String, Object?> || page < 0 || page >= asList(shop['pages']).length) return (shop: shop, ref: null);
  return placeOffer(removeOffers(shop, <OfferRef>[ref]).shop, page, ref.grid, entry);
}

/// The offer at [ref] moved to [slot] of its page and grid; an offer
/// already there takes the old slot.
ShopEdit moveOfferToSlot(JsonMap shop, OfferRef ref, int slot) {
  if (slot == ref.slot) return (shop: shop, ref: ref);
  final int rows = shopPageRows(shop, ref.page);
  if (slot < 0 || slot >= rows * 9) return (shop: shop, ref: null);
  final JsonMap grid = asMap(readIn(shop, <JsonStep>['pages', ref.page, ref.grid]));
  final Object? entry = grid['${ref.slot}'];
  if (entry is! Map<String, Object?>) return (shop: shop, ref: null);
  final Map<int, Object?> placed = _entries(grid);
  final Object? other = placed[slot];
  placed[slot] = entry;
  if (other == null) {
    placed.remove(ref.slot);
  } else {
    placed[ref.slot] = other;
  }
  final JsonList pages = _pages(shop);
  pages[ref.page] = <String, Object?>{...asMap(pages[ref.page]), ref.grid: _grid(grid, placed)};
  return (shop: _withPages(shop, pages), ref: OfferRef(ref.page, ref.grid, slot));
}

// ---- reading an offer --------------------------------------------------------

/// What an offer takes and gives, for the board's icons and sentence. Money
/// is a [shopMoneyToken] with the price as its label.
final class OfferSides {
  const OfferSides(this.takes, this.gives, {this.commands = 0});

  final List<ShopGoods> takes;
  final List<ShopGoods> gives;

  /// Commands a command offer runs.
  final int commands;
}

ShopGoods _goods(Object? goods) {
  final JsonMap map = asMap(goods);
  final JsonMap item = asMap(map['item']);
  return ShopGoods(asString(item['id']) ?? '', asInt(map['amount']) ?? asInt(item['count']) ?? 1);
}

ShopGoods _item(JsonMap entry) {
  final JsonMap item = asMap(entry['item']);
  return ShopGoods(asString(item['id']) ?? '', asInt(item['count']) ?? 1);
}

/// A price as the board writes it: whole numbers without decimals.
String moneyText(double value) => value == value.roundToDouble() ? value.toInt().toString() : value.toString();

ShopGoods _money(double price) => ShopGoods(shopMoneyToken, 1, label: price == 0 ? 'Free' : moneyText(price));

OfferSides offerSides(JsonMap entry) {
  final double? price = asDouble(entry['price']);
  switch (asString(entry['type']) ?? 'static') {
    case 'trade':
      return OfferSides(
        <ShopGoods>[for (final Object? need in asList(entry['needs'])) _goods(need), if (price != null && price > 0) _money(price)],
        <ShopGoods>[for (final Object? give in asList(entry['gives'])) _goods(give)],
      );
    case 'buy':
      return OfferSides(<ShopGoods>[_money(price ?? 0)], <ShopGoods>[_item(entry)]);
    case 'command':
      return OfferSides(<ShopGoods>[_money(price ?? 0)], <ShopGoods>[_item(entry)], commands: asList(entry['commands']).length);
    case 'sell':
      return OfferSides(<ShopGoods>[_item(entry)], <ShopGoods>[_money(price ?? 0)]);
    default:
      return OfferSides(const <ShopGoods>[], <ShopGoods>[_item(entry)]);
  }
}

/// An item id as words: `minecraft:enchanted_book` reads "enchanted book".
String itemWords(String id) {
  if (id.isEmpty) return 'no item';
  return id.substring(id.indexOf(':') + 1).replaceAll('_', ' ');
}

String _say(ShopGoods goods) {
  final String? label = goods.label;
  if (label != null) return label == 'Free' ? 'free' : '$label money';
  final String words = itemWords(goods.item);
  return '${goods.count} ${goods.count == 1 ? words : itemPlural(words)}';
}

/// Last words of item names counted as a mass or already plural: "16 wheat",
/// "2 iron horse armor", "2 shears".
const Set<String> _uncounted = <String>{
  'armor', 'wheat', 'wool', 'glass', 'sand', 'gravel', 'dirt', 'stone', 'cobblestone', 'coal', 'redstone', 'string',
  'leather', 'bread', 'beef', 'porkchop', 'mutton', 'chicken', 'rabbit', 'cod', 'salmon', 'gunpowder', 'sugar', 'dust',
  'meal', 'flesh', 'ice', 'snow', 'clay', 'obsidian', 'netherrack', 'terracotta', 'concrete', 'powder', 'kelp', 'seagrass',
  'moss', 'bamboo', 'cactus', 'scrap', 'quartz', 'lapis', 'lazuli', 'netherite', 'copper', 'iron', 'gold', 'ore', 'mud',
  'honeycomb', 'milk', 'water', 'lava', 'fish', 'cane', 'stew', 'soup', 'steel', 'ink', 'wax', 'silk',
};

/// [words] (an item's name, "hay block") for more than one: "hay blocks",
/// "torches", "sweet berries"; a mass or plural name stays as it is.
String itemPlural(String words) {
  final int space = words.lastIndexOf(' ');
  final String last = words.substring(space + 1);
  final String head = words.substring(0, space + 1);
  if (last.isEmpty || _uncounted.contains(last) || last.endsWith('s')) return words;
  if (RegExp(r'(ch|sh|x|z)$').hasMatch(last) || last == 'potato' || last == 'tomato') return '$head${last}es';
  if (RegExp(r'[^aeiou]y$').hasMatch(last)) return '$head${last.substring(0, last.length - 1)}ies';
  return '$head${last}s';
}

/// The offer as one line: "2 emerald + 1 book → 1 enchanted book".
String offerSentence(JsonMap entry) {
  final OfferSides sides = offerSides(entry);
  final String type = asString(entry['type']) ?? 'static';
  if (type == 'static') return 'Decoration: ${itemWords(sides.gives.first.item)}';
  final String takes = sides.takes.isEmpty ? 'nothing' : sides.takes.map(_say).join(' + ');
  final String gives = sides.gives.isEmpty ? 'nothing' : sides.gives.map(_say).join(' + ');
  final String runs = sides.commands == 0 ? '' : ', runs ${sides.commands} command${sides.commands == 1 ? '' : 's'}';
  return '$gives for $takes$runs';
}

/// The plain names of the NPCs whose shop module opens each shop, by shop
/// id; an NPC without a visible name goes by its id.
Map<String, List<String>> shopOpeners(Iterable<AnimusDocument> documents) {
  final Map<String, List<String>> openers = <String, List<String>>{};
  for (final AnimusDocument document in documents) {
    if (document.kind != DocumentKind.npc) continue;
    final JsonMap json = asMap(document.json);
    final String? shop = asString(asMap(asMap(json['modules'])['animus:shop'])['shop']);
    if (shop == null) continue;
    final String? name = plainName(json['name']);
    (openers[shop] ??= <String>[]).add(name == null || name.trim().isEmpty ? document.id : name.trim());
  }
  return openers;
}

/// A shop's line in the Shops list: how many offers and who opens it
/// ("12 offers, opened by Baker"); null when neither is known.
String? shopListLine(int? offers, List<String> openers) {
  final String? count = offers == null ? null : (offers == 0 ? 'No offers' : (offers == 1 ? '1 offer' : '$offers offers'));
  final String? by = openers.isEmpty ? null : 'opened by ${openers.first}${openers.length > 1 ? ' and ${openers.length - 1} more' : ''}';
  if (count == null) return by == null ? null : '${by[0].toUpperCase()}${by.substring(1)}';
  return by == null ? count : '$count, $by';
}
