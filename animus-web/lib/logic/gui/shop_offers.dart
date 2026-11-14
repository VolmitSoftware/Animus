/// The offers a native (villager trading screen) shop lists, in the order
/// the server builds them: enabled modes in `modes` order, then pages, then
/// slots ascending, without static entries. Money is shown as a gold nugget
/// token labelled with the price.
library;

import 'gui_json.dart';
import 'gui_validation.dart';

/// The item that stands for money in a native offer.
const String shopMoneyToken = 'minecraft:gold_nugget';

/// One side of an offer: [count] of [item], with [label] on a money token.
class ShopGoods {
  const ShopGoods(this.item, this.count, {this.label});

  final String item;
  final int count;
  final String? label;
}

/// A merchant offer: [costA] and the optional [costB] buy [result].
class ShopOffer {
  const ShopOffer({required this.entryId, required this.costA, required this.result, this.costB, this.key = ''});

  final String entryId;

  /// Where the entry lives, `page:mode:slot`.
  final String key;
  final ShopGoods costA;
  final ShopGoods? costB;
  final ShopGoods result;
}

/// The native style's offers for [shop].
List<ShopOffer> nativeShopOffers(JsonMap shop) {
  final List<String> modes = <String>[for (final Object? mode in asList(shop['modes'])) if (mode is String) mode];
  final List<ShopOffer> offers = <ShopOffer>[];
  final JsonList pages = asList(shop['pages']);
  for (final String mode in modes.isEmpty ? shopModes : modes) {
    for (int page = 0; page < pages.length; page++) {
      final JsonMap grid = asMap(asMap(pages[page])[mode]);
      final List<int> slots = <int>[for (final String key in grid.keys) if (int.tryParse(key) != null) int.parse(key)]..sort();
      for (final int slot in slots) {
        final ShopOffer? offer = _offer(asMap(grid['$slot']), '$page:$mode:$slot');
        if (offer != null) offers.add(offer);
      }
    }
  }
  return offers;
}

ShopOffer? _offer(JsonMap entry, String key) {
  final String id = asString(entry['id']) ?? '';
  final ShopGoods item = _goods(asMap(entry['item']), null);
  final double price = asDouble(entry['price']) ?? 0;
  switch (asString(entry['type']) ?? 'static') {
    case 'trade':
      final JsonList needs = asList(entry['needs']);
      final JsonList gives = asList(entry['gives']);
      if (needs.isEmpty || gives.isEmpty) return null;
      return ShopOffer(
        entryId: id,
        key: key,
        costA: _goods(asMap(asMap(needs.first)['item']), asInt(asMap(needs.first)['amount'])),
        costB: needs.length > 1 ? _goods(asMap(asMap(needs[1])['item']), asInt(asMap(needs[1])['amount'])) : null,
        result: _goods(asMap(asMap(gives.first)['item']), asInt(asMap(gives.first)['amount'])),
      );
    case 'buy' || 'command':
      return ShopOffer(entryId: id, key: key, costA: ShopGoods(shopMoneyToken, 1, label: price == 0 ? 'Free' : _money(price)), result: item);
    case 'sell':
      return ShopOffer(entryId: id, key: key, costA: item, result: ShopGoods(shopMoneyToken, 1, label: 'Pays ${_money(price)}'));
    default:
      return null;
  }
}

ShopGoods _goods(JsonMap item, int? amount) => ShopGoods(asString(item['id']) ?? 'minecraft:barrier', amount ?? asInt(item['count']) ?? 1);

String _money(double value) => value == value.roundToDouble() ? value.toInt().toString() : value.toString();

/// Offers the trading screen shows at once: seven, as the client's list
/// does; three in the band above a form, so the window stays readable at
/// the band's height.
int tradeOffersPerPage({required bool band}) => band ? 3 : 7;

/// Page [page] (clamped) of [offers], [size] to a page, with the page count
/// (at least one).
({List<ShopOffer> offers, int page, int pages}) tradePage(List<ShopOffer> offers, {required int page, required int size}) {
  final int pages = offers.isEmpty ? 1 : (offers.length + size - 1) ~/ size;
  final int shown = page.clamp(0, pages - 1);
  final int start = shown * size;
  return (offers: offers.isEmpty ? const <ShopOffer>[] : offers.sublist(start, (start + size).clamp(0, offers.length)), page: shown, pages: pages);
}

/// The page of [size] offers that holds the offer at [key], or null.
int? tradePageOf(List<ShopOffer> offers, String key, int size) {
  final int index = offers.indexWhere((ShopOffer offer) => offer.key == key);
  return index < 0 ? null : index ~/ size;
}
