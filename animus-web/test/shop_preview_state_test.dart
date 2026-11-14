// What the shop preview draws for a state the form cannot show: the
// trading screen one page of offers at a time (the client lists seven, the
// band above a form fewer so the window stays readable at its height), the
// page that holds a picked offer, chest slots numbered from 1 for people,
// and offers a sold-out player or one without their permission sees, dimmed
// with the line the server adds.
import 'package:animus_editor/logic/gui/gui_json.dart';
import 'package:animus_editor/logic/gui/gui_layout.dart';
import 'package:animus_editor/logic/gui/shop_layout.dart';
import 'package:animus_editor/logic/gui/shop_offers.dart';
import 'package:test/test.dart';

JsonMap _trade(String id, {JsonMap? stock, JsonMap? limit, String? permission}) => <String, Object?>{
      'id': id,
      'type': 'trade',
      'item': <String, Object?>{'id': 'minecraft:bread'},
      'needs': <Object?>[<String, Object?>{'item': <String, Object?>{'id': 'minecraft:emerald'}, 'amount': 2}],
      'gives': <Object?>[<String, Object?>{'item': <String, Object?>{'id': 'minecraft:bread'}, 'amount': 1}],
      'stock': ?stock,
      'limit': ?limit,
      'permission': ?permission,
    };

List<String> _lore(ChestCell cell) => <String>[for (final Object? line in asList(cell.icon['lore'])) if (line is String) line];

void main() {
  final JsonMap shop = <String, Object?>{
    'id': 'town:market',
    'pages': <Object?>[
      <String, Object?>{
        'rows': 3,
        'trade': <String, Object?>{for (int slot = 0; slot < 18; slot++) '$slot': _trade('t$slot')},
      },
    ],
  };

  test('should show seven offers at a time at the side and fewer in the band', () {
    expect(tradeOffersPerPage(band: false), 7);
    expect(tradeOffersPerPage(band: true), lessThan(7));
  });

  test('should page the trading screen and clamp the page', () {
    final List<ShopOffer> offers = nativeShopOffers(shop);
    expect(offers, hasLength(18));
    final ({List<ShopOffer> offers, int page, int pages}) first = tradePage(offers, page: 0, size: 7);
    expect(first.pages, 3);
    expect(<String>[for (final ShopOffer offer in first.offers) offer.entryId], <String>['t0', 't1', 't2', 't3', 't4', 't5', 't6']);
    final ({List<ShopOffer> offers, int page, int pages}) last = tradePage(offers, page: 9, size: 7);
    expect(last.page, 2);
    expect(<String>[for (final ShopOffer offer in last.offers) offer.entryId], <String>['t14', 't15', 't16', 't17']);
    expect(tradePage(const <ShopOffer>[], page: 3, size: 7), (offers: const <ShopOffer>[], page: 0, pages: 1));
  });

  test('should find the page that holds a picked offer', () {
    final List<ShopOffer> offers = nativeShopOffers(shop);
    expect(tradePageOf(offers, '0:trade:15', 7), 2);
    expect(tradePageOf(offers, '0:trade:3', 4), 0);
    expect(tradePageOf(offers, '0:buy:3', 7), isNull);
  });

  test('should number slots from 1 for people', () {
    expect(slotLabel(0), 'Slot 1');
    expect(slotLabel(26), 'Slot 27');
  });

  group('offers a player cannot take', () {
    final JsonMap limited = <String, Object?>{
      'id': 'town:market',
      'pages': <Object?>[
        <String, Object?>{
          'rows': 3,
          'trade': <String, Object?>{
            '0': _trade('plain'),
            '1': _trade('stocked', stock: <String, Object?>{'maximum': 8}),
            '2': _trade('limited', limit: <String, Object?>{'perPlayer': 3}),
            '3': _trade('locked', permission: 'town.vip'),
          },
        },
      ],
    };
    final ChestLayout layout = layoutShop(limited, page: 0, mode: 'trade');

    test('should dim sold-out offers and say what the server says', () {
      final ChestLayout shown = shopShownAs(layout, limited, soldOut: true, noPermission: false);
      expect(shown.cell(0).hidden, isFalse);
      expect(shown.cell(1).hidden, isTrue);
      expect(_lore(shown.cell(1)), contains('&7Stock: &f0'));
      expect(_lore(shown.cell(1)), isNot(contains('&7Stock: &f8')));
      expect(shown.cell(2).hidden, isTrue);
      expect(_lore(shown.cell(2)), contains('&7Limit: &f3/3'));
      expect(shown.cell(3).hidden, isFalse);
      expect(shown.cell(9).source, ChestCellSource.empty);
    });

    test('should dim offers that need a permission the player lacks', () {
      final ChestLayout shown = shopShownAs(layout, limited, soldOut: false, noPermission: true);
      expect(<bool>[for (int slot = 0; slot < 4; slot++) shown.cell(slot).hidden], <bool>[false, false, false, true]);
      expect(_lore(shown.cell(3)).last, '&cNeeds permission');
    });

    test('should leave the window as it is when no state is picked', () {
      expect(identical(shopShownAs(layout, limited, soldOut: false, noPermission: false), layout), isTrue);
    });
  });
}
