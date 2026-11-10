import 'package:animus_editor/logic/gui/gui_json.dart';
import 'package:animus_editor/logic/gui/gui_validation.dart';
import 'package:animus_editor/logic/gui/shop_entries.dart';
import 'package:animus_editor/logic/shared_schemas.dart';
import 'package:animus_editor/model/animus_document.dart';
import 'package:animus_editor/model/document_kind.dart';
import 'package:test/test.dart';

JsonMap _shop(JsonMap entry, {String grid = 'buy', bool container = false}) => <String, Object?>{
      'schemaVersion': 1,
      'id': 'town:market',
      if (container) 'owner': <String, Object?>{'uuid': '069a79f4-44e9-4726-a5be-fca90e38aaf5', 'name': 'Notch'},
      if (container) 'containers': <Object?>[<String, Object?>{'world': 'world', 'x': 1, 'y': 64, 'z': 2}],
      'pages': <Object?>[
        <String, Object?>{'rows': 3, grid: <String, Object?>{'10': entry}},
      ],
    };

/// Error messages by path; a path with several keeps the errors, not the
/// command allow-list warning.
Map<String, String> _problems(JsonMap shop) => <String, String>{
      for (final Diagnostic d in validateGui(DocumentKind.shop, shop))
        if (d.severity == DiagnosticSeverity.error) d.path: d.message,
    };

void main() {
  group('entry type change', () {
    final JsonMap trade = <String, Object?>{
      'id': 'a',
      'type': 'trade',
      'item': <String, Object?>{'id': 'minecraft:bread'},
      'needs': <Object?>[<String, Object?>{'item': <String, Object?>{'id': 'minecraft:emerald'}, 'amount': 2}],
      'gives': <Object?>[<String, Object?>{'item': <String, Object?>{'id': 'minecraft:bread'}, 'amount': 1}],
      'stock': <String, Object?>{'maximum': 10},
      'limit': <String, Object?>{'perPlayer': 3},
      'permission': 'town.vip',
    };

    test('should strip trade goods and stock when a trade becomes a sell entry', () {
      final JsonMap sell = withShopEntryType(trade, 'sell');
      expect(sell.keys, isNot(contains('needs')));
      expect(sell.keys, isNot(contains('gives')));
      expect(sell.keys, isNot(contains('stock')));
      expect(sell['limit'], <String, Object?>{'perPlayer': 3});
      expect(sell['permission'], 'town.vip');
      expect(sell['price'], 1);
      expect(_problems(_shop(sell, grid: 'sell')), isEmpty);
    });

    test('should strip limits and commands for static and add commands for command', () {
      final JsonMap command = withShopEntryType(trade, 'command');
      expect(asList(command['commands']), hasLength(1));
      expect(command['stock'], <String, Object?>{'maximum': 10});
      final JsonMap decoration = withShopEntryType(command, 'static');
      expect(decoration.keys, isNot(anyOf(contains('commands'), contains('limit'), contains('stock'))));
      expect(_problems(_shop(decoration)), isEmpty);
    });

    test('should start a command entry with one empty command and no allow-list note', () {
      final JsonMap command = withShopEntryType(trade, 'command');
      expect(command['commands'], <Object?>[<String, Object?>{'command': '', 'as': 'console'}]);
      final List<Diagnostic> found = validateGui(DocumentKind.shop, _shop(command));
      expect(found.where((Diagnostic d) => d.code == allowListCode), isEmpty);
      expect(_problems(_shop(command))[r'$.pages[0].buy["10"].commands[0].command'], 'write the command to run, without a leading slash');
      final JsonMap shop = _shop(<String, Object?>{...command, 'price': 1});
      final List<String> merged = <String>[
        for (final Diagnostic d in mergeGuiDiagnostics(SharedSchemas.embedded.validate(DocumentKind.shop, shop), validateGui(DocumentKind.shop, shop))) '${d.path}: ${d.message}',
      ];
      expect(merged, <String>[r'$.pages[0].buy["10"].commands[0].command: write the command to run, without a leading slash']);
    });

    test('should name the config keys that allow a shop with a written command', () {
      final JsonMap command = <String, Object?>{
        ...withShopEntryType(trade, 'command'),
        'commands': <Object?>[<String, Object?>{'command': 'give {player} diamond 1'}],
      };
      final List<String> notes = <String>[
        for (final Diagnostic d in validateGui(DocumentKind.shop, _shop(command)))
          if (d.code == allowListCode) '${d.severity.name} ${d.path}: ${d.message}',
      ];
      expect(notes, <String>[
        'warning \$.pages[0].buy["10"].commands: runs commands, so the server refuses this shop until config.toml allows it: '
            'add "town" to commandNamespaces, or "town:market" to commandBehaviors, under [behaviors.actions]',
      ]);
    });

    test('should give a new trade goods from the entry item', () {
      final JsonMap made = withShopEntryType(<String, Object?>{'id': 'b', 'type': 'buy', 'item': <String, Object?>{'id': 'minecraft:apple'}, 'price': 4, 'sellPartial': true}, 'trade');
      expect(asMap(asMap(asList(made['gives']).single)['item'])['id'], 'minecraft:apple');
      expect(made.keys, isNot(contains('sellPartial')));
      expect(_problems(_shop(made, grid: 'trade')), isEmpty);
    });

    test('should keep levels only on buy, command and trade entries', () {
      final JsonMap buy = <String, Object?>{'id': 'b', 'type': 'buy', 'item': <String, Object?>{'id': 'minecraft:apple'}, 'price': 0, 'levels': 3};
      expect(withShopEntryType(buy, 'command')['levels'], 3);
      expect(withShopEntryType(buy, 'trade')['levels'], 3);
      expect(withShopEntryType(buy, 'sell').keys, isNot(contains('levels')));
      expect(withShopEntryType(buy, 'static').keys, isNot(contains('levels')));
      expect(withShopEntryType(buy, 'trade', container: true).keys, isNot(contains('levels')));
      expect(_problems(_shop(buy)), isEmpty);
      expect(_problems(_shop(<String, Object?>{...buy, 'type': 'sell'}, grid: 'sell')).values, contains('levels are a cost of buy, command and trade entries'));
    });

    test('should drop price and stock in a container shop', () {
      final JsonMap boxed = withShopEntryType(<String, Object?>{...trade, 'price': 5}, 'trade', container: true);
      expect(boxed.keys, isNot(anyOf(contains('price'), contains('stock'))));
      expect(_problems(_shop(boxed, grid: 'trade', container: true)), isEmpty);
    });

    test('should offer only trade and static in a container shop', () {
      expect(shopTypesFor('buy', container: true), <String>['static']);
      expect(shopTypesFor('trade', container: true), <String>['trade', 'static']);
      expect(shopTypesFor('buy', container: false), <String>['buy', 'command', 'static']);
    });
  });

  group('shop validation mirrors ShopDocument', () {
    test('should refuse buy entries, prices and stock in a container shop with core wording', () {
      final JsonMap shop = _shop(<String, Object?>{'id': 'a', 'type': 'buy', 'item': <String, Object?>{'id': 'minecraft:bread'}, 'price': 3, 'stock': <String, Object?>{'maximum': 4}}, container: true);
      final Map<String, String> problems = _problems(shop);
      expect(problems[r'$.pages[0].buy["10"].type'], 'a container shop has only trade and static entries');
      expect(problems[r'$.pages[0].buy["10"].price'], 'a container shop trades items for items; remove the price');
      expect(problems[r'$.pages[0].buy["10"].stock'], "a container shop's stock is its containers");
    });

    test('should need an owner for containers and check the owner and boxes', () {
      final JsonMap shop = <String, Object?>{
        ..._shop(<String, Object?>{'id': 'a', 'type': 'static', 'item': <String, Object?>{'id': 'minecraft:stone'}}),
        'containers': <Object?>[
          <String, Object?>{'world': 'world', 'x': 1, 'y': 2, 'z': 3},
          <String, Object?>{'world': 'world', 'x': 1, 'y': 2, 'z': 3},
          <String, Object?>{'world': 'world', 'x': 1.5, 'y': 2, 'z': 3},
        ],
      };
      final Map<String, String> problems = _problems(shop);
      expect(problems[r'$.owner'], 'a shop with containers needs an owner');
      expect(problems[r'$.containers[1]'], 'this container is listed twice');
      expect(problems[r'$.containers[2].x'], 'expected integer');
      expect(_problems(<String, Object?>{...shop, 'owner': <String, Object?>{'uuid': 'Notch'}})[r'$.owner.uuid'], 'expected canonical player UUID');
      expect(_problems(<String, Object?>{...shop, 'containers': <Object?>[]})[r'$.containers'], 'expected an array of 1 to 8 containers');
    });

    test('should name fields an entry type refuses', () {
      final Map<String, String> sell = _problems(_shop(<String, Object?>{'id': 'a', 'type': 'sell', 'item': <String, Object?>{'id': 'x:y'}, 'stock': <String, Object?>{'maximum': 1}, 'needs': <Object?>[]}, grid: 'sell'));
      expect(sell[r'$.pages[0].sell["10"].stock'], 'stock applies to buy, command and trade entries');
      expect(sell[r'$.pages[0].sell["10"].needs'], 'needs and gives apply only to trade entries');
      expect(sell[r'$.pages[0].sell["10"].price'], 'expected a price; use 0 for free');
      final Map<String, String> decoration = _problems(_shop(<String, Object?>{
        'id': 'a',
        'type': 'static',
        'item': <String, Object?>{'id': 'x:y'},
        'limit': <String, Object?>{'global': 1},
        'commands': <Object?>[<String, Object?>{'command': 'say hi'}],
        'sellPartial': true,
      }));
      expect(decoration[r'$.pages[0].buy["10"].limit'], 'static entries have no limits');
      expect(decoration[r'$.pages[0].buy["10"].commands'], 'static entries cannot run commands');
      expect(decoration[r'$.pages[0].buy["10"].sellPartial'], 'applies only to sell entries');
    });

    test('should list entries a lower row count leaves outside the page', () {
      final JsonMap page = <String, Object?>{'buy': <String, Object?>{'3': <String, Object?>{}, '20': <String, Object?>{}}, 'trade': <String, Object?>{'9': <String, Object?>{}}};
      expect(shopEntriesOutside(page, 1), <String>['buy 20', 'trade 9']);
      expect(shopEntriesOutside(page, 3), isEmpty);
    });
  });
}
