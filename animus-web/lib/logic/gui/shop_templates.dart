/// The starting points the New shop step offers: an empty page, villager
/// style item trades, a money shop and a command shop.
library;

import 'gui_json.dart';

enum ShopTemplate {
  blank('Blank', 'One empty page. Add offers in the editor.'),
  villager('Villager trades', 'Emeralds for goods, shown on the trading screen.'),
  money('Money shop', 'Buy and sell for money. Needs Vault and an economy plugin.'),
  command('Command shop', 'Sells commands, such as a rank. Needs the command allow-list.');

  const ShopTemplate(this.label, this.description);

  final String label;
  final String description;
}

JsonMap _item(String id, [int? count]) => <String, Object?>{'id': id, 'count': ?count};

JsonMap _goods(String id, int amount) => <String, Object?>{'item': _item(id), 'amount': amount};

/// A new shop with [id] from [template], titled [title] when given.
JsonMap shopFromTemplate(ShopTemplate template, String id, {String? title}) {
  final String? named = title == null || title.trim().isEmpty ? null : title.trim();
  final JsonMap base = <String, Object?>{'schemaVersion': 1, 'revision': 1, 'id': id};
  switch (template) {
    case ShopTemplate.blank:
      return <String, Object?>{
        ...base,
        'title': named ?? '&6Shop',
        'pages': <Object?>[<String, Object?>{'name': 'Page 1', 'rows': 3}],
      };
    case ShopTemplate.villager:
      return <String, Object?>{
        ...base,
        'title': named ?? '&2Trader',
        'style': 'native',
        'modes': <Object?>['trade'],
        'pages': <Object?>[
          <String, Object?>{
            'name': 'Trades',
            'rows': 3,
            'trade': <String, Object?>{
              '10': <String, Object?>{
                'id': 'bread',
                'type': 'trade',
                'item': _item('minecraft:bread', 6),
                'needs': <Object?>[_goods('minecraft:emerald', 1)],
                'gives': <Object?>[_goods('minecraft:bread', 6)],
              },
              '11': <String, Object?>{
                'id': 'wheat',
                'type': 'trade',
                'item': _item('minecraft:emerald'),
                'needs': <Object?>[_goods('minecraft:wheat', 20)],
                'gives': <Object?>[_goods('minecraft:emerald', 1)],
                'stock': <String, Object?>{'maximum': 16, 'restockSeconds': 1200},
              },
              '12': <String, Object?>{
                'id': 'mending',
                'type': 'trade',
                'item': <String, Object?>{'id': 'minecraft:enchanted_book', 'components': '[stored_enchantments={"minecraft:mending":1}]'},
                'needs': <Object?>[_goods('minecraft:emerald', 24), _goods('minecraft:book', 1)],
                'gives': <Object?>[
                  <String, Object?>{'item': <String, Object?>{'id': 'minecraft:enchanted_book', 'components': '[stored_enchantments={"minecraft:mending":1}]'}, 'amount': 1},
                ],
                'limit': <String, Object?>{'perPlayer': 1, 'resetSeconds': 86400},
              },
            },
          },
        ],
      };
    case ShopTemplate.money:
      return <String, Object?>{
        ...base,
        'title': named ?? '&6Market',
        'modes': <Object?>['buy', 'sell'],
        'confirm': true,
        'pages': <Object?>[
          <String, Object?>{
            'name': 'Goods',
            'rows': 3,
            'buy': <String, Object?>{
              '11': <String, Object?>{'id': 'bread', 'type': 'buy', 'item': _item('minecraft:bread', 4), 'price': 12, 'allowStack': true},
              '13': <String, Object?>{
                'id': 'torches',
                'type': 'buy',
                'item': _item('minecraft:torch', 16),
                'price': 8,
                'limit': <String, Object?>{'perPlayer': 64, 'resetSeconds': 86400},
              },
            },
            'sell': <String, Object?>{
              '11': <String, Object?>{'id': 'wheat', 'type': 'sell', 'item': _item('minecraft:wheat', 16), 'price': 4, 'sellPartial': true},
            },
          },
        ],
      };
    case ShopTemplate.command:
      return <String, Object?>{
        ...base,
        'title': named ?? '&dRanks',
        'modes': <Object?>['buy'],
        'confirm': true,
        'pages': <Object?>[
          <String, Object?>{
            'name': 'Ranks',
            'rows': 3,
            'buy': <String, Object?>{
              '13': <String, Object?>{
                'id': 'vip',
                'type': 'command',
                'item': _item('minecraft:diamond'),
                'display': <String, Object?>{'name': '&bVIP rank', 'lore': <Object?>['&7Once per player.']},
                'price': 500,
                'limit': <String, Object?>{'perPlayer': 1},
                'commands': <Object?>[<String, Object?>{'command': 'lp user {player} parent add vip', 'as': 'console'}],
                'broadcast': '&b{player} is now VIP.',
              },
            },
          },
        ],
      };
  }
}
