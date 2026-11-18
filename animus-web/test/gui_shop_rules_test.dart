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
      expect(problems[r'$.pages[0].buy["10"].type'], 'a chest shop has only trade and static entries');
      expect(problems[r'$.pages[0].buy["10"].price'], 'a chest shop trades items for items; remove the price');
      expect(problems[r'$.pages[0].buy["10"].stock'], "a chest shop's stock is its chests");
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
      expect(problems[r'$.owner'], 'a chest shop needs an owner');
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

    test('should refuse commands in a container shop with core wording', () {
      final JsonMap trade = <String, Object?>{
        'id': 'a',
        'type': 'trade',
        'item': <String, Object?>{'id': 'minecraft:bread'},
        'needs': <Object?>[<String, Object?>{'item': <String, Object?>{'id': 'minecraft:emerald'}}],
        'gives': <Object?>[<String, Object?>{'item': <String, Object?>{'id': 'minecraft:bread'}}],
        'commands': <Object?>[<String, Object?>{'command': 'give {player} diamond'}],
      };
      expect(_problems(_shop(trade, grid: 'trade', container: true))[r'$.pages[0].trade["10"].commands'], 'a chest shop trades items for items; remove the commands');
    });

    test('should list entries a lower row count leaves outside the page', () {
      final JsonMap page = <String, Object?>{'buy': <String, Object?>{'3': <String, Object?>{}, '20': <String, Object?>{}}, 'trade': <String, Object?>{'9': <String, Object?>{}}};
      expect(shopEntriesOutside(page, 1), <String>['Sells slot 21', 'Trades slot 10'], reason: 'slots read from 1, on the shop side');
      expect(shopEntriesOutside(page, 3), isEmpty);
    });
  });

  group('player shops mirror ShopDocument', () {
    final JsonMap book = <String, Object?>{
      'id': 'b',
      'type': 'trade',
      'item': <String, Object?>{'id': 'minecraft:written_book'},
      'book': 'Tales',
      'needs': <Object?>[<String, Object?>{'item': <String, Object?>{'id': 'minecraft:emerald'}, 'amount': 5}],
    };
    JsonMap books(JsonMap entry, {String? kind = 'book'}) => <String, Object?>{..._shop(entry, grid: 'trade', container: true), 'kind': ?kind};
    List<String> merged(JsonMap shop) => <String>[
          for (final Diagnostic d in mergeGuiDiagnostics(SharedSchemas.embedded.validate(DocumentKind.shop, shop), validateGui(DocumentKind.shop, shop)))
            if (d.severity == DiagnosticSeverity.error) '${d.path}: ${d.message}',
        ];
    const String at = r'$.pages[0].trade["10"]';

    test('should name a container shop kind and refuse others with core wording', () {
      for (final String kind in shopKinds) {
        expect(_problems(books(book, kind: kind)).keys, isNot(contains(r'$.kind')), reason: kind);
      }
      expect(_problems(books(book, kind: 'renting'))[r'$.kind'], 'expected selling, buying, trading or book');
      expect(_problems(books(book, kind: ''))[r'$.kind'], 'expected selling, buying, trading or book');
      final JsonMap counter = <String, Object?>{..._shop(<String, Object?>{'id': 'a', 'type': 'buy', 'item': <String, Object?>{'id': 'minecraft:bread'}, 'price': 1}), 'kind': 'selling'};
      expect(_problems(counter)[r'$.kind'], 'a shop kind needs a chest, which holds its stock');
      expect(merged(counter), <String>[r'$.kind: a shop kind needs a chest, which holds its stock']);
    });

    test('should keep a closed reason on any shop and refuse a blank or long one', () {
      final JsonMap open = _shop(<String, Object?>{'id': 'a', 'type': 'buy', 'item': <String, Object?>{'id': 'minecraft:bread'}, 'price': 1});
      expect(merged(<String, Object?>{...open, 'closed': 'The chest is gone'}), isEmpty);
      expect(_problems(<String, Object?>{...open, 'closed': ' '})[r'$.closed'], 'expected nonblank string');
      expect(_problems(<String, Object?>{...open, 'closed': 'x' * 257})[r'$.closed'], 'at most 256 characters');
      expect(merged(<String, Object?>{...open, 'closed': 'x' * 257}), <String>[r'$.closed: at most 256 characters']);
    });

    test('should accept a book trade that names its book and needs no gives', () {
      expect(_problems(books(book)), isEmpty);
      expect(merged(books(book)), isEmpty);
    });

    test('should refuse book trades that break the book rules with core wording', () {
      expect(_problems(books(<String, Object?>{...book, 'gives': <Object?>[<String, Object?>{'item': <String, Object?>{'id': 'minecraft:diamond'}}]}))['$at.gives'],
          'a book entry gives a copy of its book; remove gives');
      expect(_problems(books(<String, Object?>{...book}..remove('book')))['$at.book'], "a book shop's trades name the book they copy");
      expect(_problems(books(book, kind: 'selling'))['$at.book'], 'book entries belong in a book shop, kind book');
      expect(_problems(_shop(book, grid: 'trade'))['$at.book'], 'book entries belong in a book shop, kind book');
      expect(_problems(books(<String, Object?>{'id': 's', 'type': 'static', 'item': <String, Object?>{'id': 'minecraft:stone'}, 'book': 'Tales'}))['$at.book'], 'only trade entries copy a book');
      expect(_problems(books(<String, Object?>{...book, 'book': 'x' * 129}))['$at.book'], 'at most 128 characters');
      expect(merged(books(<String, Object?>{...book}..remove('book'))), <String>['$at.book: a book shop\'s trades name the book they copy', '$at.gives: expected a non-empty array of items']);
      expect(merged(books(book, kind: 'selling')), <String>['$at.book: book entries belong in a book shop, kind book']);
    });

    test('should keep the book only on a book shop trade when the type changes', () {
      expect(shopEntryKeeps('trade', 'book', container: true, bookShop: true), isTrue);
      expect(shopEntryKeeps('trade', 'gives', container: true, bookShop: true), isFalse);
      expect(shopEntryKeeps('trade', 'book', container: true), isFalse);
      expect(shopEntryKeeps('static', 'book', container: true, bookShop: true), isFalse);
      final JsonMap decoration = withShopEntryType(book, 'static', container: true, bookShop: true);
      expect(decoration.keys, isNot(anyOf(contains('book'), contains('needs'))));
      final JsonMap again = withShopEntryType(<String, Object?>{'id': 'b', 'type': 'static', 'item': <String, Object?>{'id': 'minecraft:written_book'}}, 'trade', container: true, bookShop: true);
      expect(again.keys, isNot(contains('gives')));
      expect(asString(again['book'])?.trim(), isNotEmpty);
      expect(_problems(books(again)), isEmpty);
      final JsonMap swap = withShopEntryType(book, 'trade', container: true);
      expect(swap.keys, isNot(contains('book')));
      expect(asList(swap['gives']), hasLength(1));
    });
  });
  group('chest shop edits', () {
    final JsonMap trade = <String, Object?>{
      'id': 'bread',
      'type': 'trade',
      'item': <String, Object?>{'id': 'minecraft:bread'},
      'needs': <Object?>[<String, Object?>{'item': <String, Object?>{'id': 'minecraft:diamond'}, 'amount': 1}],
      'gives': <Object?>[<String, Object?>{'item': <String, Object?>{'id': 'minecraft:bread'}, 'amount': 1}],
    };
    JsonMap stall(String? kind) => <String, Object?>{..._shop(trade, grid: 'trade', container: true), 'kind': ?kind};
    List<String> errors(JsonMap shop) => <String>[
          for (final Diagnostic d in mergeGuiDiagnostics(SharedSchemas.embedded.validate(DocumentKind.shop, shop), validateGui(DocumentKind.shop, shop)))
            if (d.severity == DiagnosticSeverity.error) '${d.path}: ${d.message}',
        ];

    test('should turn trades into book copies when the kind becomes Book', () {
      final JsonMap books = withShopKind(stall('trading'), 'book');
      expect(books['kind'], 'book');
      final JsonMap entry = asMap(asMap(asMap(asList(books['pages']).first)['trade'])['10']);
      expect(entry['book'], untitledBook);
      expect(entry.containsKey('gives'), isFalse, reason: 'a book trade gives a copy, not items');
      expect(asMap(entry['item'])['id'], 'minecraft:written_book');
      expect(entry['needs'], trade['needs'], reason: 'the price stays');
      expect(errors(books), isEmpty);
    });

    test('should give book copies items again when the kind leaves Book', () {
      final JsonMap selling = withShopKind(withShopKind(stall('trading'), 'book'), 'selling');
      final JsonMap entry = asMap(asMap(asMap(asList(selling['pages']).first)['trade'])['10']);
      expect(entry.containsKey('book'), isFalse);
      expect(asList(entry['gives']), isNotEmpty);
      expect(errors(selling), isEmpty);
      expect(withShopKind(stall('trading'), null).containsKey('kind'), isFalse);
    });

    test('should narrow the modes to trades when a shop gets its first chest', () {
      final JsonMap blank = <String, Object?>{'schemaVersion': 1, 'id': 'town:market', 'pages': <Object?>[<String, Object?>{'name': 'Page 1', 'rows': 3}]};
      final JsonMap chested = withChests(blank, <Object?>[<String, Object?>{'world': 'world', 'x': 0, 'y': 64, 'z': 0}]);
      expect(chested['modes'], <Object?>['trade']);
      expect(asList(chested['containers']), hasLength(1));
      // A shop that already sells for money keeps its modes: its offers show what to change.
      final JsonMap money = <String, Object?>{...blank, 'pages': <Object?>[<String, Object?>{'rows': 3, 'buy': <String, Object?>{'1': <String, Object?>{'id': 'a', 'type': 'buy', 'item': <String, Object?>{'id': 'minecraft:cake'}, 'price': 1}}}]};
      expect(withChests(money, <Object?>[<String, Object?>{'world': 'world', 'x': 0, 'y': 64, 'z': 0}]).containsKey('modes'), isFalse);
      // The last chest gone: the containers key goes, the modes stay.
      expect(withChests(chested, const <Object?>[]).containsKey('containers'), isFalse);
      expect(withChests(chested, const <Object?>[])['modes'], <Object?>['trade']);
    });
  });
}
