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
  const ShopOffer({required this.entryId, required this.costA, required this.result, this.costB});

  final String entryId;
  final ShopGoods costA;
  final ShopGoods? costB;
  final ShopGoods result;
}

/// The native style's offers for [shop].
List<ShopOffer> nativeShopOffers(JsonMap shop) {
  final List<String> modes = <String>[for (final Object? mode in asList(shop['modes'])) if (mode is String) mode];
  final List<ShopOffer> offers = <ShopOffer>[];
  for (final String mode in modes.isEmpty ? shopModes : modes) {
    for (final Object? page in asList(shop['pages'])) {
      final JsonMap grid = asMap(asMap(page)[mode]);
      final List<int> slots = <int>[for (final String key in grid.keys) if (int.tryParse(key) != null) int.parse(key)]..sort();
      for (final int slot in slots) {
        final ShopOffer? offer = _offer(asMap(grid['$slot']));
        if (offer != null) offers.add(offer);
      }
    }
  }
  return offers;
}

ShopOffer? _offer(JsonMap entry) {
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
        costA: _goods(asMap(asMap(needs.first)['item']), asInt(asMap(needs.first)['amount'])),
        costB: needs.length > 1 ? _goods(asMap(asMap(needs[1])['item']), asInt(asMap(needs[1])['amount'])) : null,
        result: _goods(asMap(asMap(gives.first)['item']), asInt(asMap(gives.first)['amount'])),
      );
    case 'buy' || 'command':
      return ShopOffer(entryId: id, costA: ShopGoods(shopMoneyToken, 1, label: price == 0 ? 'Free' : _money(price)), result: item);
    case 'sell':
      return ShopOffer(entryId: id, costA: item, result: ShopGoods(shopMoneyToken, 1, label: 'Pays ${_money(price)}'));
    default:
      return null;
  }
}

ShopGoods _goods(JsonMap item, int? amount) => ShopGoods(asString(item['id']) ?? 'minecraft:barrier', amount ?? asInt(item['count']) ?? 1);

String _money(double value) => value == value.roundToDouble() ? value.toInt().toString() : value.toString();
