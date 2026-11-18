/// Shop checks the editor adds to the schema and [validateGui]: the notes
/// core's ShopNotes gives for offers a window style cannot show exactly,
/// and offer problems (no item, a zero count, limits that work against each
/// other, a discount that costs more). Notes never refuse the shop.
library;

import '../../model/animus_document.dart';
import '../item_ids.dart' show itemName;
import 'gui_json.dart';
import 'shop_board.dart';

const String styleNoteCode = 'style-note';
const String offerCheckCode = 'offer-check';

/// The window style players get: `native` or `chest`. [override] is an NPC
/// module's `style`; without either the server's `[shops] style`, `chest`
/// unless changed.
String effectiveShopStyle(JsonMap shop, {String? override}) => override ?? asString(shop['style']) ?? 'chest';

/// What the editor knows about the chest GUI a shop's `layout` names: null
/// when the workspace does not have it.
typedef LayoutLookup = bool? Function(String id);

/// ShopNotes for [shop] shown in [style]. [hasList] says whether a
/// workspace chest GUI has a list area, or null when the workspace lacks it.
List<Diagnostic> shopStyleNotes(JsonMap shop, String style, {LayoutLookup? hasList}) {
  final List<Diagnostic> notes = <Diagnostic>[];
  void note(String path, String message) => notes.add(Diagnostic(DiagnosticSeverity.info, path, message, code: styleNoteCode));
  final String? layout = asString(shop['layout']);
  if (layout != null && style == 'chest') {
    final bool? listed = hasList?.call(layout);
    if (listed == null) {
      note(r'$.layout', 'chest GUI $layout is not in this workspace; if the server lacks it too, the shop shows its own pages');
    } else if (!listed) {
      note(r'$.layout', 'chest GUI $layout has no list area; the shop shows its own pages');
    }
  }
  if (style != 'native') return notes;
  final List<BoardOffer> offers = boardOffers(shop);
  if (offers.isNotEmpty && offers.every((BoardOffer offer) => offer.type == 'static' || !offer.modeOn)) {
    note(r'$.pages', 'the trading screen has nothing to show: every entry is static or its mode is off, so the shop does not open in this style');
  }
  for (final BoardOffer offer in offers) {
    final String path = offer.ref.path;
    final double price = asDouble(offer.entry['price']) ?? 0;
    switch (offer.type) {
      case 'static':
        note(path, 'static entries do not show in the trading screen');
      case 'trade':
        if (asList(offer.entry['needs']).length > 2 || asList(offer.entry['gives']).length > 1) {
          note(path, "the trading screen shows two needs and one result; the rest are listed in the result's tooltip");
        }
        if (price > 0) note(path, "the trading screen lists the money price in the result's tooltip; the money is taken from the player's balance");
      case 'sell':
        note(path, "the trading screen shows the payment as a token item; the money is paid to the player's balance");
      case 'buy' || 'command':
        if (price > 0) note(path, "the trading screen shows the price as a token item; the money is taken from the player's balance");
    }
  }
  return notes;
}

/// The item list the editor checks ids against: the paired server's list
/// covers every namespace; the list bundled with the Minecraft pack covers
/// `minecraft:` ids only.
final class ItemList {
  const ItemList._(this._server, this._vanilla);

  /// Ids without `minecraft:`, as the server sends them.
  final Set<String>? _server;

  /// Namespaced vanilla ids.
  final Set<String>? _vanilla;

  /// Whether [id] is an item: null when no list covers it.
  bool? has(String id) {
    final String full = id.contains(':') ? id : 'minecraft:$id';
    final Set<String>? server = _server;
    if (server != null) return server.contains(full.startsWith('minecraft:') ? full.substring(10) : full);
    return full.startsWith('minecraft:') ? _vanilla!.contains(full) : null;
  }

  /// Why [id] is refused, naming a vanilla item by its name as the item
  /// field does; a modded id has no other name.
  String unknown(String id) {
    final String named = id.contains(':') && !id.startsWith('minecraft:') ? id : itemName(id.contains(':') ? id : 'minecraft:$id');
    return _server != null ? '$named is not an item on this server' : '$named is not a Minecraft item';
  }
}

/// The list to check item ids against: [server] (the paired server's ids,
/// `minecraft:` left off) when it sent one, else [bundled]; null when
/// neither is loaded.
ItemList? itemsKnown({List<String>? server, Set<String>? bundled}) {
  if (server != null && server.isNotEmpty) return ItemList._(<String>{for (final String id in server) id.startsWith('minecraft:') ? id.substring(10) : id}, null);
  if (bundled != null && bundled.isNotEmpty) return ItemList._(null, bundled);
  return null;
}

/// Every item an offer shows, takes or gives in [shop], with the path of
/// its item object.
Iterable<(JsonMap, String)> _shopItems(JsonMap shop) sync* {
  for (final BoardOffer offer in boardOffers(shop)) {
    final String path = offer.ref.path;
    yield (asMap(offer.entry['item']), childPath(path, 'item'));
    if (offer.type != 'trade') continue;
    for (final String side in const <String>['needs', 'gives']) {
      final JsonList goods = asList(offer.entry[side]);
      for (int index = 0; index < goods.length; index++) {
        yield (asMap(asMap(goods[index])['item']), childPath(indexPath(childPath(path, side), index), 'item'));
      }
    }
  }
}

/// Items in [shop] whose id [items] says is not an item: errors at the
/// item ("Item: Brick sword is not a Minecraft item"), since the offer
/// cannot work on that server.
List<Diagnostic> shopUnknownItems(JsonMap shop, ItemList? items) {
  if (items == null) return const <Diagnostic>[];
  return <Diagnostic>[
    for (final (JsonMap item, String path) in _shopItems(shop))
      if ((asString(item['id']) ?? '').trim() case final String id when id.isNotEmpty && items.has(id) == false)
        Diagnostic(DiagnosticSeverity.error, path, items.unknown(id), code: offerCheckCode),
  ];
}

/// The rule core's item reader applies before the id pattern: an item whose
/// id is empty is missing. One diagnostic at the item, in place of the
/// schema's pattern message at its id.
List<Diagnostic> shopItemRules(Object? json) {
  if (json is! Map<String, Object?>) return const <Diagnostic>[];
  return <Diagnostic>[
    for (final (JsonMap item, String path) in _shopItems(json))
      if (item['id'] is String && (item['id']! as String).trim().isEmpty) Diagnostic(DiagnosticSeverity.error, path, 'is required', code: offerCheckCode),
  ];
}

/// [schema] with [shopItemRules] of [json] in place of what the schema says
/// at those items' ids.
List<Diagnostic> withShopItemRules(List<Diagnostic> schema, Object? json) {
  final List<Diagnostic> rules = shopItemRules(json);
  if (rules.isEmpty) return schema;
  final Set<String> ids = <String>{for (final Diagnostic rule in rules) childPath(rule.path, 'id')};
  return <Diagnostic>[
    for (final Diagnostic diagnostic in schema)
      if (!ids.contains(diagnostic.path)) diagnostic,
    ...rules,
  ];
}

/// Offer problems in [shop]. [knownItem] tells real items from the item
/// list in use; null skips that check (no list loaded). An emptied item is
/// the document's own problem ([shopItemRules]).
List<Diagnostic> shopOfferChecks(JsonMap shop, {ItemList? knownItem}) {
  final List<Diagnostic> out = <Diagnostic>[...shopUnknownItems(shop, knownItem)];
  void error(String path, String message) => out.add(Diagnostic(DiagnosticSeverity.error, path, message, code: offerCheckCode));
  void warning(String path, String message) => out.add(Diagnostic(DiagnosticSeverity.warning, path, message, code: offerCheckCode));
  for (final (JsonMap item, String path) in _shopItems(shop)) {
    final Object? count = item['count'];
    if (count is num && count < 1) error(childPath(path, 'count'), 'a count of $count gives nothing; use 1 to 99');
  }

  for (final BoardOffer offer in boardOffers(shop)) {
    final String path = offer.ref.path;
    final JsonMap entry = offer.entry;
    if (offer.type == 'trade') {
      for (final String side in const <String>['needs', 'gives']) {
        final JsonList goods = asList(entry[side]);
        for (int index = 0; index < goods.length; index++) {
          final String at = indexPath(childPath(path, side), index);
          final Object? amount = asMap(goods[index])['amount'];
          if (amount is num && amount < 1) error(childPath(at, 'amount'), 'an amount of $amount trades nothing; use 1 or more');
        }
      }
    }
    final JsonMap limit = asMap(entry['limit']);
    final int? perPlayer = asInt(limit['perPlayer']);
    final int? global = asInt(limit['global']);
    final String limitPath = childPath(path, 'limit');
    if (perPlayer != null && global != null && perPlayer > global) {
      warning(childPath(limitPath, 'perPlayer'), 'above the global limit of $global, so no player reaches it');
    }
    if (limit.containsKey('resetSeconds') && perPlayer == null && global == null) {
      warning(childPath(limitPath, 'resetSeconds'), 'resets nothing: set a per-player or global limit');
    }
    final int? stock = asInt(asMap(entry['stock'])['maximum']);
    if (stock != null && global != null && global > stock && !asMap(entry['stock']).containsKey('restockSeconds')) {
      warning(childPath(limitPath, 'global'), 'above the stock of $stock, which never restocks, so stock runs out first');
    }
    final double? price = asDouble(entry['price']);
    final double? sale = asDouble(asMap(entry['discount'])['price']);
    if (price != null && sale != null && sale >= price) {
      warning(childPath(childPath(path, 'discount'), 'price'), 'the discount price is not below the price of ${moneyText(price)}');
    }
  }
  return out;
}

/// [extra] without the ones at a path [existing] already reports, so a
/// schema error and an editor check never say the same thing twice.
List<Diagnostic> withoutDuplicatePaths(List<Diagnostic> existing, List<Diagnostic> extra) {
  final Set<String> taken = <String>{for (final Diagnostic diagnostic in existing) diagnostic.path};
  return <Diagnostic>[for (final Diagnostic diagnostic in extra) if (!taken.contains(diagnostic.path)) diagnostic];
}
