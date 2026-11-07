import 'package:animus_editor/logic/gui/gui_json.dart';
import 'package:animus_editor/logic/gui/shop_checks.dart';
import 'package:animus_editor/model/animus_document.dart';
import 'package:test/test.dart';

JsonMap _goods(String id, int amount) => <String, Object?>{'item': <String, Object?>{'id': id}, 'amount': amount};

JsonMap _shop(Map<String, JsonMap> trade, {Map<String, JsonMap> buy = const <String, JsonMap>{}, String? layout}) => <String, Object?>{
      'id': 'town:market',
      'layout': ?layout,
      'pages': <Object?>[
        <String, Object?>{'rows': 3, 'trade': trade, 'buy': buy},
      ],
    };

Map<String, String> _byPath(List<Diagnostic> all) => <String, String>{for (final Diagnostic d in all) d.path: d.message};

void main() {
  group('style notes', () {
    final JsonMap shop = _shop(
      <String, JsonMap>{
        '1': <String, Object?>{
          'id': 'big',
          'type': 'trade',
          'item': <String, Object?>{'id': 'minecraft:shield'},
          'price': 2,
          'needs': <Object?>[_goods('minecraft:iron_ingot', 1), _goods('minecraft:oak_planks', 1), _goods('minecraft:string', 1)],
          'gives': <Object?>[_goods('minecraft:shield', 1)],
        },
        '2': <String, Object?>{'id': 'pane', 'type': 'static', 'item': <String, Object?>{'id': 'minecraft:glass_pane'}},
      },
      buy: <String, JsonMap>{
        '3': <String, Object?>{'id': 'cake', 'type': 'buy', 'item': <String, Object?>{'id': 'minecraft:cake'}, 'price': 5},
        '4': <String, Object?>{'id': 'free', 'type': 'buy', 'item': <String, Object?>{'id': 'minecraft:bread'}, 'price': 0},
      },
      layout: 'town:list',
    );

    test('should note what the trading screen cannot show, as core does', () {
      final Map<String, String> notes = _byPath(shopStyleNotes(shop, 'native'));
      expect(notes[r'$.pages[0].trade["1"]'], contains('money price'));
      expect(notes[r'$.pages[0].trade["2"]'], 'static entries do not show in the trading screen');
      expect(notes[r'$.pages[0].buy["3"]'], contains('token item'));
      expect(notes.containsKey(r'$.pages[0].buy["4"]'), isFalse, reason: 'a free offer shows exactly');
      expect(notes.containsKey(r'$.layout'), isFalse, reason: 'the validator already says a layout is for chests');
      final List<Diagnostic> trade = shopStyleNotes(shop, 'native').where((Diagnostic d) => d.path == r'$.pages[0].trade["1"]').toList();
      expect(trade.map((Diagnostic d) => d.message), contains(contains('two needs and one result')));
      expect(trade.every((Diagnostic d) => d.severity == DiagnosticSeverity.info), isTrue);
    });

    test('should note a layout the shop cannot use in the chest style', () {
      expect(_byPath(shopStyleNotes(shop, 'chest', hasList: (_) => null))[r'$.layout'], contains('not in this workspace'));
      expect(_byPath(shopStyleNotes(shop, 'chest', hasList: (_) => false))[r'$.layout'], contains('no list area'));
      expect(shopStyleNotes(shop, 'chest', hasList: (_) => true), isEmpty);
    });

    test('should say a shop of decorations does not open natively', () {
      final JsonMap decorations = _shop(<String, JsonMap>{'1': <String, Object?>{'id': 'a', 'type': 'static', 'item': <String, Object?>{'id': 'minecraft:stone'}}});
      expect(_byPath(shopStyleNotes(decorations, 'native'))[r'$.pages'], contains('does not open'));
    });

    test('should pick the NPC style, then the shop style, then chest', () {
      expect(effectiveShopStyle(<String, Object?>{}), 'chest');
      expect(effectiveShopStyle(<String, Object?>{'style': 'native'}), 'native');
      expect(effectiveShopStyle(<String, Object?>{'style': 'native'}, override: 'chest'), 'chest');
    });
  });

  group('offer checks', () {
    test('should flag a missing item and zero counts', () {
      final Map<String, String> problems = _byPath(shopOfferChecks(_shop(<String, JsonMap>{
        '1': <String, Object?>{
          'id': 'a',
          'type': 'trade',
          'item': <String, Object?>{'id': ''},
          'needs': <Object?>[_goods('minecraft:emerald', 0)],
          'gives': <Object?>[<String, Object?>{'item': <String, Object?>{'id': 'minecraft:bread', 'count': 0}, 'amount': 1}],
        },
      })));
      expect(problems[r'$.pages[0].trade["1"].item.id'], 'pick an item');
      expect(problems[r'$.pages[0].trade["1"].needs[0].amount'], contains('trades nothing'));
      expect(problems[r'$.pages[0].trade["1"].gives[0].item.count'], contains('gives nothing'));
    });

    test('should warn about an item id the registry lacks only for vanilla ids', () {
      final JsonMap shop = _shop(<String, JsonMap>{
        '1': <String, Object?>{'id': 'a', 'type': 'static', 'item': <String, Object?>{'id': 'minecraft:brick_sword'}},
        '2': <String, Object?>{'id': 'b', 'type': 'static', 'item': <String, Object?>{'id': 'mymod:widget'}},
      });
      final Map<String, String> problems = _byPath(shopOfferChecks(shop, knownItem: (String id) => id == 'minecraft:stone'));
      expect(problems[r'$.pages[0].trade["1"].item.id'], contains('not a Minecraft item'));
      expect(problems.containsKey(r'$.pages[0].trade["2"].item.id'), isFalse);
      expect(shopOfferChecks(shop), isEmpty, reason: 'no registry, no item check');
    });

    test('should flag limits that work against each other', () {
      final Map<String, String> problems = _byPath(shopOfferChecks(_shop(const <String, JsonMap>{}, buy: <String, JsonMap>{
        '1': <String, Object?>{
          'id': 'a',
          'type': 'buy',
          'item': <String, Object?>{'id': 'minecraft:cake'},
          'price': 5,
          'limit': <String, Object?>{'perPlayer': 10, 'global': 4},
          'stock': <String, Object?>{'maximum': 2},
          'discount': <String, Object?>{'price': 6},
        },
        '2': <String, Object?>{'id': 'b', 'type': 'buy', 'item': <String, Object?>{'id': 'minecraft:cake'}, 'price': 1, 'limit': <String, Object?>{'resetSeconds': 60}},
      })));
      expect(problems[r'$.pages[0].buy["1"].limit.perPlayer'], contains('above the global limit of 4'));
      expect(problems[r'$.pages[0].buy["1"].limit.global'], contains('stock runs out first'));
      expect(problems[r'$.pages[0].buy["1"].discount.price'], contains('not below the price of 5'));
      expect(problems[r'$.pages[0].buy["2"].limit.resetSeconds'], contains('resets nothing'));
    });

    test('should drop checks at a path the schema already reports', () {
      const Diagnostic schema = Diagnostic(DiagnosticSeverity.error, r'$.a', 'minimum 1');
      const Diagnostic mine = Diagnostic(DiagnosticSeverity.error, r'$.a', 'zero');
      const Diagnostic other = Diagnostic(DiagnosticSeverity.error, r'$.b', 'other');
      expect(withoutDuplicatePaths(<Diagnostic>[schema], <Diagnostic>[mine, other]), <Diagnostic>[other]);
    });
  });
}
