import 'dart:convert';

import 'package:animus_editor/logic/gui/gui_json.dart';
import 'package:animus_editor/logic/gui/shop_board.dart';
import 'package:animus_editor/model/animus_document.dart';
import 'package:animus_editor/model/document_kind.dart';
import 'package:test/test.dart';

JsonMap _item(String id, [int? count]) => <String, Object?>{'id': id, 'count': ?count};

JsonMap _goods(String id, int amount) => <String, Object?>{'item': _item(id), 'amount': amount};

JsonMap _shop() => <String, Object?>{
      'schemaVersion': 1,
      'id': 'town:market',
      'modes': <Object?>['trade', 'buy'],
      'pages': <Object?>[
        <String, Object?>{
          'name': 'Main',
          'rows': 3,
          'trade': <String, Object?>{
            '11': <String, Object?>{
              'id': 'book',
              'type': 'trade',
              'item': _item('minecraft:enchanted_book'),
              'needs': <Object?>[_goods('minecraft:emerald', 2), _goods('minecraft:book', 1)],
              'gives': <Object?>[_goods('minecraft:enchanted_book', 1)],
            },
            '13': <String, Object?>{
              'id': 'bread',
              'type': 'trade',
              'item': _item('minecraft:bread', 4),
              'needs': <Object?>[_goods('minecraft:emerald', 1)],
              'gives': <Object?>[_goods('minecraft:bread', 4)],
            },
            '15': <String, Object?>{'id': 'pane', 'type': 'static', 'item': _item('minecraft:glass_pane')},
          },
          'buy': <String, Object?>{
            '4': <String, Object?>{'id': 'cake', 'type': 'buy', 'item': _item('minecraft:cake'), 'price': 12.5},
          },
          'sell': <String, Object?>{
            '0': <String, Object?>{'id': 'wheat', 'type': 'sell', 'item': _item('minecraft:wheat', 16), 'price': 4},
          },
        },
      ],
    };

List<String> _ids(JsonMap shop) => <String>[for (final BoardOffer offer in boardOffers(shop)) offer.id];

void main() {
  group('board order', () {
    test('should list enabled modes in order, then modes that are off', () {
      final List<BoardOffer> offers = boardOffers(_shop());
      expect(_ids(_shop()), <String>['book', 'bread', 'pane', 'cake', 'wheat']);
      expect(offers.last.modeOn, isFalse);
      expect(offers.first.ref, const OfferRef(0, 'trade', 11));
    });

    test('should round-trip an offer key', () {
      const OfferRef ref = OfferRef(2, 'sell', 40);
      expect(OfferRef.parse(ref.key), ref);
      expect(OfferRef.parse('x:sell:1'), isNull);
      expect(ref.path, r'$.pages[2].sell["40"]');
    });
  });

  group('reading offers', () {
    test('should read a trade as what the player gets for what it costs', () {
      final JsonMap entry = asMap(readIn(_shop(), const <JsonStep>['pages', 0, 'trade', '11']));
      expect(offerSentence(entry), '1 enchanted book for 2 emeralds + 1 book');
    });

    test('should name many of an item in the plural, and goods counted as a mass as they are', () {
      String trade(String item, int amount) => offerSentence(<String, Object?>{
            'type': 'trade',
            'needs': <Object?>[_goods('minecraft:emerald', 6)],
            'gives': <Object?>[_goods(item, amount)],
          });
      expect(trade('minecraft:lead', 3), '3 leads for 6 emeralds');
      expect(trade('minecraft:hay_block', 8), '8 hay blocks for 6 emeralds');
      expect(trade('minecraft:torch', 4), '4 torches for 6 emeralds');
      expect(trade('minecraft:sweet_berries', 4), '4 sweet berries for 6 emeralds');
      expect(trade('minecraft:shears', 2), '2 shears for 6 emeralds');
      expect(trade('minecraft:iron_horse_armor', 2), '2 iron horse armor for 6 emeralds');
      expect(trade('minecraft:wheat', 16), '16 wheat for 6 emeralds');
      expect(trade('minecraft:potato', 5), '5 potatoes for 6 emeralds');
      expect(trade('minecraft:saddle', 1), '1 saddle for 6 emeralds');
    });

    test('should read money, sales, commands and decorations', () {
      final JsonMap page = asMap(asList(_shop()['pages']).first);
      expect(offerSentence(asMap(asMap(page['buy'])['4'])), '1 cake for 12.5 money');
      expect(offerSentence(asMap(asMap(page['sell'])['0'])), '4 money for 16 wheat');
      expect(offerSentence(asMap(asMap(page['trade'])['15'])), 'Decoration: glass pane');
      expect(
        offerSentence(<String, Object?>{
          'type': 'command',
          'item': _item('minecraft:paper'),
          'price': 0,
          'commands': <Object?>[<String, Object?>{'command': 'say hi'}],
        }),
        '1 paper for free, runs 1 command',
      );
    });

    test('should add a trade money price to what it takes', () {
      final OfferSides sides = offerSides(<String, Object?>{
        'type': 'trade',
        'price': 3,
        'needs': <Object?>[_goods('minecraft:iron_ingot', 5)],
        'gives': <Object?>[_goods('minecraft:shield', 1)],
      });
      expect(sides.takes.map((g) => g.label ?? g.item), <String>['minecraft:iron_ingot', '3']);
    });
  });

  group('write round-trip', () {
    test('should keep every untouched field when an offer moves', () {
      final JsonMap shop = _shop();
      final ShopEdit edit = nudgeOffer(shop, const OfferRef(0, 'trade', 13), -1);
      final JsonMap back = nudgeOffer(edit.shop, edit.ref!, 1).shop;
      expect(jsonEncode(back), jsonEncode(shop));
    });

    test('should restore removed offers to their slots', () {
      final JsonMap shop = _shop();
      final removed = removeOffers(shop, const <OfferRef>[OfferRef(0, 'trade', 11), OfferRef(0, 'buy', 4)]);
      expect(_ids(removed.shop), <String>['bread', 'pane', 'wheat']);
      expect(asMap(asList(removed.shop['pages']).first).containsKey('buy'), isFalse, reason: 'an emptied grid goes');
      final JsonMap restored = restoreOffers(removed.shop, removed.removed);
      expect(_ids(restored), _ids(shop));
      expect(readIn(restored, const <JsonStep>['pages', 0, 'buy', '4', 'price']), 12.5);
    });
  });

  group('reorder', () {
    test('should swap neighbours and keep the slots', () {
      final ShopEdit edit = nudgeOffer(_shop(), const OfferRef(0, 'trade', 13), -1);
      expect(_ids(edit.shop).take(3), <String>['bread', 'book', 'pane']);
      expect(edit.ref, const OfferRef(0, 'trade', 11));
      expect(asMap(readIn(edit.shop, const <JsonStep>['pages', 0, 'trade'])).keys, <String>['11', '13', '15']);
    });

    test('should rotate the offers between a drag start and its drop', () {
      final ShopEdit edit = reorderOffer(_shop(), const OfferRef(0, 'trade', 15), 0);
      expect(_ids(edit.shop).take(3), <String>['pane', 'book', 'bread']);
      expect(edit.ref, const OfferRef(0, 'trade', 11));
    });

    test('should refuse a move past either end', () {
      expect(nudgeOffer(_shop(), const OfferRef(0, 'trade', 11), -1).ref, isNull);
      expect(nudgeOffer(_shop(), const OfferRef(0, 'trade', 15), 1).ref, isNull);
      expect(offerPosition(_shop(), const OfferRef(0, 'trade', 13)), (1, 3));
    });
  });

  group('duplicate and add', () {
    test('should copy an offer with a fresh id into the next free slot', () {
      final ShopEdit edit = duplicateOffer(_shop(), const OfferRef(0, 'trade', 11));
      expect(edit.ref, const OfferRef(0, 'trade', 12));
      expect(readIn(edit.shop, const <JsonStep>['pages', 0, 'trade', '12', 'id']), 'book-copy');
      expect(readIn(edit.shop, const <JsonStep>['pages', 0, 'trade', '12', 'needs', 0, 'amount']), 2);
      final ShopEdit again = duplicateOffer(edit.shop, const OfferRef(0, 'trade', 11));
      expect(readIn(again.shop, <JsonStep>[...again.ref!.steps, 'id']), 'book-copy-2');
    });

    test('should add an offer of each kind to its grid', () {
      JsonMap shop = _shop();
      for (final String kind in shopOfferKinds) {
        final ShopEdit edit = addOffer(shop, kind);
        expect(edit.ref!.grid, switch (kind) { 'buy' || 'command' => 'buy', 'sell' => 'sell', _ => 'trade' }, reason: kind);
        expect(readIn(edit.shop, <JsonStep>[...edit.ref!.steps, 'type']), kind);
        shop = edit.shop;
      }
      expect(readIn(shop, const <JsonStep>['pages', 0, 'buy', '0', 'price']), 1);
    });

    test('should grow a full page, then start a new one', () {
      JsonMap shop = <String, Object?>{'id': 'a:b', 'pages': <Object?>[<String, Object?>{'rows': 1}]};
      for (int i = 0; i < 6; i++) {
        shop = addOffer(shop, 'trade').shop;
      }
      expect(readIn(shop, const <JsonStep>['pages', 0, 'rows']), 2, reason: 'a 1-row page has 5 free slots beside its controls');
      final JsonMap full = <String, Object?>{
        'id': 'a:b',
        'pages': <Object?>[
          <String, Object?>{
            'rows': 6,
            'trade': <String, Object?>{for (final int slot in offerSlots(6)) '$slot': <String, Object?>{'id': 'o$slot', 'type': 'static', 'item': _item('minecraft:stone')}},
          },
        ],
      };
      final ShopEdit edit = addOffer(full, 'trade');
      expect(edit.ref, const OfferRef(1, 'trade', 0));
      expect(asList(edit.shop['pages']).length, 2);
    });

    test('should start the first page of an empty shop', () {
      final ShopEdit edit = addOffer(<String, Object?>{'id': 'a:b', 'pages': <Object?>[]}, 'buy');
      expect(edit.ref, const OfferRef(0, 'buy', 0));
      expect(asMap(asList(edit.shop['pages']).first)['name'], 'Page 1');
    });
  });

  group('kind, page and slot', () {
    test('should move an offer to the grid of its new kind', () {
      final ShopEdit edit = setOfferKind(_shop(), const OfferRef(0, 'trade', 13), 'buy');
      expect(edit.ref!.grid, 'buy');
      final JsonMap entry = asMap(readIn(edit.shop, edit.ref!.steps));
      expect(entry['type'], 'buy');
      expect(entry.containsKey('needs'), isFalse);
      expect(entry['price'], 1);
      expect(readIn(edit.shop, const <JsonStep>['pages', 0, 'trade', '13']), isNull);
    });

    test('should swap with an offer already in the target slot', () {
      final ShopEdit edit = moveOfferToSlot(_shop(), const OfferRef(0, 'trade', 11), 13);
      expect(readIn(edit.shop, const <JsonStep>['pages', 0, 'trade', '13', 'id']), 'book');
      expect(readIn(edit.shop, const <JsonStep>['pages', 0, 'trade', '11', 'id']), 'bread');
      expect(moveOfferToSlot(_shop(), const OfferRef(0, 'trade', 11), 27).ref, isNull, reason: 'outside 3 rows');
    });

    test('should move an offer to another page', () {
      final JsonMap shop = <String, Object?>{..._shop(), 'pages': <Object?>[...asList(_shop()['pages']), <String, Object?>{'name': 'Two'}]};
      final ShopEdit edit = moveOfferToPage(shop, const OfferRef(0, 'buy', 4), 1);
      expect(edit.ref, const OfferRef(1, 'buy', 0));
      expect(readIn(edit.shop, const <JsonStep>['pages', 1, 'buy', '0', 'id']), 'cake');
    });
  });

  test('should make ids valid and unique', () {
    expect(freshEntryId(_shop(), 'Book'), 'book-2');
    expect(freshEntryId(_shop(), '  '), 'offer');
    expect(freshEntryId(_shop(), 'Iron Sword!'), 'iron-sword');
  });

  group('the Shops list', () {
    AnimusDocument npc(String id, String name, String? shop) {
      final JsonMap json = <String, Object?>{
        'name': name,
        if (shop != null) 'modules': <String, Object?>{'animus:shop': <String, Object?>{'shop': shop}},
      };
      return AnimusDocument(kind: DocumentKind.npc, id: id, path: 'npcs/$id.json', source: jsonEncode(json), json: json, diagnostics: const <Diagnostic>[]);
    }

    test('should name the NPCs that open each shop by their plain names', () {
      final Map<String, List<String>> openers = shopOpeners(<AnimusDocument>[
        npc('town/baker', '&6Baker', 'town:bakery'),
        npc('town/helper', '', 'town:bakery'),
        npc('town/guard', 'Guard', null),
      ]);
      expect(openers, <String, List<String>>{'town:bakery': <String>['Baker', 'town/helper']});
    });

    test('should read a shop row as its offers and who opens it', () {
      expect(shopListLine(12, const <String>['Baker']), '12 offers, opened by Baker');
      expect(shopListLine(1, const <String>['Baker', 'Smith', 'Guard']), '1 offer, opened by Baker and 2 more');
      expect(shopListLine(null, const <String>[]), isNull);
      expect(shopListLine(0, const <String>[]), 'No offers');
    });
  });
}
