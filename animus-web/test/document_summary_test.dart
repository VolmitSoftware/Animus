import 'dart:convert';

import 'package:animus_editor/model/document_kind.dart';
import 'package:animus_editor/model/document_summary.dart';
import 'package:test/test.dart';

void main() {
  test('should summarize an NPC with its plain name, place, folder and features in protocol order', () {
    final DocumentSummary summary = summarizeDocument(DocumentKind.npc, 'town/market/baker', <String, Object?>{
      'name': '&6<bold>Master</bold>   Baker',
      'type': 'minecraft:villager',
      'spawned': true,
      'position': <String, Object?>{'world': 'world', 'x': 4.5, 'y': 64, 'z': -2.5},
      'tags': <Object?>['market', 7, 'bread'],
      'behaviors': <Object?>['town:greet'],
      'skin': 'ab' * 32,
      'modules': <String, Object?>{
        'animus:combat': <String, Object?>{'enabled': true},
        'animus:navigation': <String, Object?>{'route': 'town:loop'},
        'animus:mount': <String, Object?>{},
      },
    });
    expect(summary.name, 'Master Baker');
    expect(summary.folder, 'town/market');
    expect(summary.world, 'world');
    expect(summary.y, 64);
    expect(summary.tags, <String>['market', 'bread']);
    expect(summary.features, <String>['route', 'combat', 'behavior', 'mount']);
    expect(summary.skin, 'ab' * 32);
    expect(summarizeDocument(DocumentKind.npc, 'loner', <String, Object?>{'name': '&f'}).fields, <String, Object?>{'folder': '', 'features': <String>[]},
        reason: 'no visible name: the list shows the id');
  });

  test('should count the points of an NPC route only when the NPC holds the route itself', () {
    final List<Object?> points = <Object?>[
      for (final (int, int) corner in <(int, int)>[(1, 3), (4, 3), (4, 6), (1, 6)]) <String, Object?>{'x': corner.$1, 'y': 2, 'z': corner.$2},
    ];
    int? pointsOf(Map<String, Object?> navigation) =>
        summarizeDocument(DocumentKind.npc, 'town/porter', <String, Object?>{'modules': <String, Object?>{'animus:navigation': navigation}}).count('points');
    expect(pointsOf(<String, Object?>{'points': points}), 4);
    expect(pointsOf(<String, Object?>{'route': 'patrol', 'points': points}), isNull, reason: 'a named route counts in the route document');
    expect(pointsOf(<String, Object?>{'recording': 'stroll', 'points': points}), isNull);
    expect(pointsOf(<String, Object?>{'points': <Object?>[]}), isNull);
  });

  test('should drop Gloss animation tokens and leftover tags from names', () {
    expect(plainName('|animation.sparkle|&6Bakery'), 'Bakery');
    expect(plainName('<unknown:tag>Shop</unknown:tag>'), 'Shop');
    expect(plainName('x' * 80), 'x' * 64);
    final DocumentSummary hologram = summarizeDocument(DocumentKind.hologram, 'shop:sign', <String, Object?>{
      'anchor': <String, Object?>{'world': 'world', 'position': <Object?>[4.5, 65, 2.5]},
      'lines': <Object?>['|animation.sparkle|', '&6Bakery', '&7Fresh bread'],
    });
    expect(hologram.fields, <String, Object?>{'name': 'Bakery', 'lines': 3, 'world': 'world', 'x': 4.5, 'y': 65.0, 'z': 2.5});
  });

  test('should read a skin texture from Mojang only, over https', () {
    String raw(String url, {bool slim = false}) => base64.encode(utf8.encode(jsonEncode(<String, Object?>{
          'textures': <String, Object?>{
            'SKIN': <String, Object?>{'url': url, if (slim) 'metadata': <String, Object?>{'model': 'slim'}},
          },
        })));
    expect(summarizeDocument(DocumentKind.skin, 'x', <String, Object?>{'textureRaw': raw('http://textures.minecraft.net/texture/abc123', slim: true)}).fields,
        <String, Object?>{'texture': 'https://textures.minecraft.net/texture/abc123', 'slim': true});
    expect(summarizeDocument(DocumentKind.skin, 'x', <String, Object?>{'textureRaw': raw('https://example.com/skin.png')}).fields, isEmpty);
  });

  test('should collapse and trim whitespace as the server does, keeping non-breaking spaces', () {
    expect(plainName('\u00a0Guard \t\n Captain\u00a0'), '\u00a0Guard Captain\u00a0');
    expect(plainName('  Guard\u2003'), 'Guard', reason: 'an em space is whitespace to the server');
  });

  test('should read an unpadded skin property as the server does, and refuse the URL-safe alphabet', () {
    final String textures = jsonEncode(<String, Object?>{
      'textures': <String, Object?>{'SKIN': <String, Object?>{'url': 'http://textures.minecraft.net/texture/ab12', 'metadata': <String, Object?>{'model': 'slim'}}},
    });
    final String padded = base64.encode(utf8.encode('$textures '));
    expect(padded, endsWith('='));
    final String unpadded = padded.replaceAll('=', '');
    expect(summarizeDocument(DocumentKind.skin, 'a' * 64, <String, Object?>{'textureRaw': unpadded}).fields,
        <String, Object?>{'texture': 'https://textures.minecraft.net/texture/ab12', 'slim': true});
    // A property whose standard encoding has '+' or '/', so the URL-safe one has '-' or '_'.
    final List<int> bytes = <List<int>>[
      for (int pad = 0; pad < 3; pad++)
        utf8.encode(jsonEncode(<String, Object?>{
          'textures': <String, Object?>{'SKIN': <String, Object?>{'url': 'http://textures.minecraft.net/texture/ab12', 'note': '${'x' * pad}>>>???'}},
        })),
    ].firstWhere((List<int> candidate) => base64.encode(candidate).contains(RegExp(r'[+/]')));
    expect(summarizeDocument(DocumentKind.skin, 'a' * 64, <String, Object?>{'textureRaw': base64.encode(bytes)}).fields,
        <String, Object?>{'texture': 'https://textures.minecraft.net/texture/ab12', 'slim': false});
    expect(summarizeDocument(DocumentKind.skin, 'a' * 64, <String, Object?>{'textureRaw': base64Url.encode(bytes)}).fields, <String, Object?>{});
  });

  test('should leave out mount when enabled is any value other than true, as the server reads it', () {
    List<String> features(Object? enabled) =>
        summarizeDocument(DocumentKind.npc, 'n', <String, Object?>{'modules': <String, Object?>{'animus:mount': <String, Object?>{'enabled': enabled}}}).features;
    expect(features(true), <String>['mount']);
    expect(features('TRUE'), <String>['mount']);
    expect(features(null), <String>['mount']);
    expect(features(false), isEmpty);
    expect(features('false'), isEmpty);
    expect(features(0), isEmpty);
    expect(features(1), isEmpty);
  });

  test('should count shop offers on the trade, buy and sell grids only', () {
    final DocumentSummary shop = summarizeDocument(DocumentKind.shop, 'town:bakery', <String, Object?>{
      'title': '<gold>Bakery',
      'pages': <Object?>[
        <String, Object?>{'name': 'Page 1', 'rows': 2, 'trade': <String, Object?>{'0': <String, Object?>{}, '1': <String, Object?>{}}, 'sell': <String, Object?>{'4': <String, Object?>{}}},
        <String, Object?>{'name': 'Page 2', 'buy': <String, Object?>{'0': <String, Object?>{}}},
      ],
    });
    expect(shop.fields, <String, Object?>{'name': 'Bakery', 'offers': 4, 'pages': 2});
    expect(summarizeDocument(DocumentKind.inventory, 'shop:counter', <String, Object?>{'resolution': '9x3', 'mask': <Object?>['a', 'b']}).count('rows'), 3);
    expect(summarizeDocument(DocumentKind.menu, 'lobby:main', <String, Object?>{'title': 'Main'}).fields, isEmpty);
  });
}
