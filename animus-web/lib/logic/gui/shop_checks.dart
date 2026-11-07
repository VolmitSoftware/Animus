/// Shop checks the editor adds to the schema and [validateGui]: the notes
/// core's ShopNotes gives for offers a window style cannot show exactly,
/// and offer problems (no item, a zero count, limits that work against each
/// other, a discount that costs more). Notes never refuse the shop.
library;

import '../../model/animus_document.dart';
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

/// Offer problems in [shop]. [knownItem] says whether an item id is in the
/// bundled registry; null skips that check (registry not loaded).
List<Diagnostic> shopOfferChecks(JsonMap shop, {bool Function(String id)? knownItem}) {
  final List<Diagnostic> out = <Diagnostic>[];
  void error(String path, String message) => out.add(Diagnostic(DiagnosticSeverity.error, path, message, code: offerCheckCode));
  void warning(String path, String message) => out.add(Diagnostic(DiagnosticSeverity.warning, path, message, code: offerCheckCode));
  void item(JsonMap value, String path) {
    final String id = (asString(value['id']) ?? '').trim();
    if (id.isEmpty) {
      error(childPath(path, 'id'), 'pick an item');
    } else if (knownItem != null && id.startsWith('minecraft:') && !knownItem(id)) {
      warning(childPath(path, 'id'), '$id is not a Minecraft item');
    }
    final Object? count = value['count'];
    if (count is num && count < 1) error(childPath(path, 'count'), 'a count of $count gives nothing; use 1 to 99');
  }

  for (final BoardOffer offer in boardOffers(shop)) {
    final String path = offer.ref.path;
    final JsonMap entry = offer.entry;
    item(asMap(entry['item']), childPath(path, 'item'));
    if (offer.type == 'trade') {
      for (final String side in const <String>['needs', 'gives']) {
        final JsonList goods = asList(entry[side]);
        for (int index = 0; index < goods.length; index++) {
          final String at = indexPath(childPath(path, side), index);
          final JsonMap one = asMap(goods[index]);
          item(asMap(one['item']), childPath(at, 'item'));
          final Object? amount = one['amount'];
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
