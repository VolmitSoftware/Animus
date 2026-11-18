import 'dart:convert';

import 'package:animus_editor/logic/data_folder.dart';
import 'package:animus_editor/logic/diagnostic_text.dart';
import 'package:animus_editor/logic/gui/gui_json.dart';
import 'package:animus_editor/logic/gui/shop_checks.dart';
import 'package:animus_editor/logic/shared_schemas.dart';
import 'package:animus_editor/model/animus_document.dart';
import 'package:animus_editor/model/document_kind.dart';
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
      expect(problems[r'$.pages[0].trade["1"].needs[0].amount'], contains('trades nothing'));
      expect(problems[r'$.pages[0].trade["1"].gives[0].item.count'], contains('gives nothing'));
    });

    test('should refuse an item the bundled list lacks, for vanilla ids only', () {
      final JsonMap shop = _shop(<String, JsonMap>{
        '1': <String, Object?>{'id': 'a', 'type': 'static', 'item': <String, Object?>{'id': 'minecraft:brick_sword'}},
        '2': <String, Object?>{'id': 'b', 'type': 'static', 'item': <String, Object?>{'id': 'mymod:widget'}},
        '3': <String, Object?>{'id': 'c', 'type': 'static', 'item': <String, Object?>{'id': 'minecraft:stone'}},
      });
      final List<Diagnostic> all = shopOfferChecks(shop, knownItem: itemsKnown(bundled: <String>{'minecraft:stone'}));
      final Map<String, String> problems = _byPath(all);
      expect(problems[r'$.pages[0].trade["1"].item'], 'Brick sword is not a Minecraft item', reason: 'named as the item field names it');
      expect(all.firstWhere((Diagnostic d) => d.path == r'$.pages[0].trade["1"].item').severity, DiagnosticSeverity.error);
      expect(diagnosticSentence(all.firstWhere((Diagnostic d) => d.path == r'$.pages[0].trade["1"].item')), 'Item: Brick sword is not a Minecraft item');
      expect(problems.containsKey(r'$.pages[0].trade["2"].item'), isFalse, reason: 'a modded id cannot be told from the vanilla list');
      expect(problems.containsKey(r'$.pages[0].trade["3"].item'), isFalse);
      expect(shopOfferChecks(shop), isEmpty, reason: 'no list, no item check');
      expect(shopUnknownItems(shop, itemsKnown(bundled: <String>{'minecraft:stone'})).map((Diagnostic d) => d.path), <String>[r'$.pages[0].trade["1"].item']);
    });

    test('should refuse any item the paired server lacks, modded ones too', () {
      final JsonMap shop = _shop(<String, JsonMap>{
        '1': <String, Object?>{'id': 'a', 'type': 'static', 'item': <String, Object?>{'id': 'mymod:widget'}},
        '2': <String, Object?>{'id': 'b', 'type': 'static', 'item': <String, Object?>{'id': 'mymod:gear'}},
        '3': <String, Object?>{'id': 'c', 'type': 'static', 'item': <String, Object?>{'id': 'minecraft:stone'}},
      });
      final Map<String, String> problems = _byPath(shopUnknownItems(shop, itemsKnown(server: <String>['stone', 'mymod:gear'], bundled: <String>{'minecraft:stone'})));
      expect(problems, <String, String>{r'$.pages[0].trade["1"].item': 'mymod:widget is not an item on this server'});
    });

    test('should say an emptied item is required, in place of the id pattern', () {
      final JsonMap shop = <String, Object?>{
        'schemaVersion': 1,
        'id': 'town:market',
        'pages': <Object?>[
          <String, Object?>{
            'rows': 3,
            'buy': <String, Object?>{'11': <String, Object?>{'id': 'bread', 'type': 'buy', 'item': <String, Object?>{'id': ''}, 'price': 2}},
            'trade': <String, Object?>{
              '12': <String, Object?>{
                'id': 'swap',
                'type': 'trade',
                'item': <String, Object?>{'id': 'minecraft:bread'},
                'needs': <Object?>[_goods('', 1)],
                'gives': <Object?>[_goods('minecraft:bread', 1)],
              },
            },
          },
        ],
      };
      final AnimusDocument document = validateSource(const DocumentLocation(DocumentKind.shop, 'town:market'), 'shops/town/market.json', jsonEncode(shop), SharedSchemas.embedded);
      final Map<String, String> problems = _byPath(document.diagnostics);
      expect(problems[r'$.pages[0].buy["11"].item'], 'is required');
      expect(problems[r'$.pages[0].trade["12"].needs[0].item'], 'is required');
      expect(document.diagnostics.map((Diagnostic d) => d.message).where((String m) => m.contains('does not match')), isEmpty);
      expect(diagnosticSentence(document.diagnostics.firstWhere((Diagnostic d) => d.path == r'$.pages[0].buy["11"].item')), 'Item is required');
      expect(shopOfferChecks(shop).where((Diagnostic d) => d.path.endsWith('.id')), isEmpty, reason: 'the document already says it');
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
