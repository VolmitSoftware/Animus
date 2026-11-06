import 'package:animus_editor/logic/gui/gui_json.dart';
import 'package:animus_editor/logic/gui/gui_validation.dart';
import 'package:animus_editor/logic/gui/shop_offers.dart';
import 'package:animus_editor/logic/shared_schemas.dart';
import 'package:animus_editor/model/animus_document.dart';
import 'package:animus_editor/model/document_kind.dart';
import 'package:test/test.dart';

JsonMap _item(String id, [int? count]) => <String, Object?>{'id': id, 'count': ?count};

final JsonMap _shop = <String, Object?>{
  'schemaVersion': 1,
  'id': 'town:market',
  'title': '&6Market',
  'modes': <Object?>['trade', 'buy', 'sell'],
  'pages': <Object?>[
    <String, Object?>{
      'rows': 3,
      'buy': <String, Object?>{
        '12': <String, Object?>{'id': 'kit', 'type': 'command', 'item': _item('minecraft:chest'), 'price': 0},
        '3': <String, Object?>{'id': 'bread', 'type': 'buy', 'item': _item('minecraft:bread', 4), 'price': 12.5},
        '5': <String, Object?>{'id': 'glass', 'type': 'static', 'item': _item('minecraft:glass_pane')},
      },
      'sell': <String, Object?>{
        '1': <String, Object?>{'id': 'wheat', 'type': 'sell', 'item': _item('minecraft:wheat', 16), 'price': 4},
      },
      'trade': <String, Object?>{
        '7': <String, Object?>{
          'id': 'gem',
          'type': 'trade',
          'item': _item('minecraft:diamond'),
          'needs': <Object?>[
            <String, Object?>{'item': _item('minecraft:emerald'), 'amount': 8},
            <String, Object?>{'item': _item('minecraft:book'), 'amount': 1},
          ],
          'gives': <Object?>[<String, Object?>{'item': _item('minecraft:diamond'), 'amount': 2}],
        },
      },
    },
    <String, Object?>{
      'rows': 1,
      'trade': <String, Object?>{
        '0': <String, Object?>{
          'id': 'later',
          'type': 'trade',
          'item': _item('minecraft:apple'),
          'needs': <Object?>[<String, Object?>{'item': _item('minecraft:gold_ingot'), 'amount': 1}],
          'gives': <Object?>[<String, Object?>{'item': _item('minecraft:apple'), 'amount': 3}],
        },
      },
    },
  ],
};

String _goods(ShopGoods? goods) => goods == null ? '-' : '${goods.count}x ${goods.item}${goods.label == null ? '' : ' (${goods.label})'}';

List<String> _offers(JsonMap shop) => <String>[
      for (final ShopOffer offer in nativeShopOffers(shop)) '${offer.entryId}: ${_goods(offer.costA)} + ${_goods(offer.costB)} -> ${_goods(offer.result)}',
    ];

void main() {
  group('native offers', () {
    test('should walk modes in order, then pages, then slots, skipping static entries', () {
      expect(_offers(_shop), <String>[
        'gem: 8x minecraft:emerald + 1x minecraft:book -> 2x minecraft:diamond',
        'later: 1x minecraft:gold_ingot + - -> 3x minecraft:apple',
        'bread: 1x minecraft:gold_nugget (12.5) + - -> 4x minecraft:bread',
        'kit: 1x minecraft:gold_nugget (Free) + - -> 1x minecraft:chest',
        'wheat: 16x minecraft:wheat + - -> 1x minecraft:gold_nugget (Pays 4)',
      ]);
    });

    test('should use buy, sell, trade when modes is unset and skip disabled modes', () {
      final JsonMap unset = <String, Object?>{..._shop}..remove('modes');
      expect(_offers(unset).map((String line) => line.split(':').first), <String>['bread', 'kit', 'wheat', 'gem', 'later']);
      final JsonMap sellOnly = <String, Object?>{..._shop, 'modes': <Object?>['sell']};
      expect(_offers(sellOnly).map((String line) => line.split(':').first), <String>['wheat']);
    });
  });

  group('style and layout', () {
    List<Diagnostic> all(JsonMap shop) => mergeGuiDiagnostics(SharedSchemas.embedded.validate(DocumentKind.shop, shop), validateGui(DocumentKind.shop, shop));
    final JsonMap base = <String, Object?>{'schemaVersion': 1, 'id': 'town:market', 'pages': <Object?>[<String, Object?>{'rows': 1}]};

    test('should accept both styles and a chest GUI layout id', () {
      expect(all(<String, Object?>{...base, 'style': 'native'}), isEmpty);
      expect(all(<String, Object?>{...base, 'style': 'chest', 'layout': 'town:market/list'}), isEmpty);
      expect(all(<String, Object?>{...base, 'layout': 'town:market'}), isEmpty);
    });

    test('should refuse an unknown style and a malformed layout id', () {
      expect(all(<String, Object?>{...base, 'style': 'villager'}).map((Diagnostic d) => d.path), contains(r'$.style'));
      expect(all(<String, Object?>{...base, 'layout': 'Town Market'}).map((Diagnostic d) => d.path), contains(r'$.layout'));
    });

    test('should warn that a native shop ignores its layout', () {
      final Diagnostic warning = all(<String, Object?>{...base, 'style': 'native', 'layout': 'town:market'}).single;
      expect(warning.severity, DiagnosticSeverity.warning);
      expect(warning.path, r'$.layout');
    });
  });
}
