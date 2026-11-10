/// Shop entry rules as core's ShopDocument reads them: which fields each
/// entry type keeps, what a container shop allows, and the writer that
/// changes an entry's type without leaving fields the new type refuses.
library;

import 'gui_json.dart';

/// Entry types, in the order the editor offers them.
const List<String> shopEntryTypes = <String>['buy', 'command', 'sell', 'trade', 'static'];

/// Whether [shop] stocks from chests: it lists `containers`.
bool isContainerShop(JsonMap shop) => asList(shop['containers']).isNotEmpty;

/// The types a slot of [grid] may hold; a container shop trades items for
/// items, so it keeps only trade and static entries.
List<String> shopTypesFor(String grid, {required bool container}) => <String>[
      if (grid == 'buy' && !container) ...<String>['buy', 'command'],
      if (grid == 'sell' && !container) 'sell',
      if (grid == 'trade') 'trade',
      'static',
    ];

/// Whether an entry of [type] may carry [key] (core's per-type checks).
bool shopEntryKeeps(String type, String key, {bool container = false}) => switch (key) {
      'sellPartial' => type == 'sell',
      'needs' || 'gives' => type == 'trade',
      'commands' => type == 'command',
      'limit' => type != 'static',
      'stock' => !container && (type == 'buy' || type == 'command' || type == 'trade'),
      'price' => !container,
      'levels' => !container && (type == 'buy' || type == 'command' || type == 'trade'),
      _ => true,
    };

/// [entry] as a [type] entry: fields the new type refuses are removed and
/// the fields it needs are added with starting values; everything else
/// stays.
JsonMap withShopEntryType(JsonMap entry, String type, {bool container = false}) {
  final JsonMap out = <String, Object?>{
    for (final MapEntry<String, Object?> field in entry.entries)
      if (field.key == 'type' || shopEntryKeeps(type, field.key, container: container)) field.key: field.value,
  };
  out['type'] = type;
  if (!container && (type == 'buy' || type == 'sell' || type == 'command') && out['price'] == null) out['price'] = 1;
  if (type == 'command' && asList(out['commands']).isEmpty) {
    out['commands'] = <Object?>[<String, Object?>{'command': '', 'as': 'console'}];
  }
  if (type == 'trade') {
    final String item = asString(asMap(entry['item'])['id']) ?? 'minecraft:bread';
    if (asList(out['needs']).isEmpty) out['needs'] = <Object?>[<String, Object?>{'item': <String, Object?>{'id': 'minecraft:emerald'}, 'amount': 1}];
    if (asList(out['gives']).isEmpty) out['gives'] = <Object?>[<String, Object?>{'item': <String, Object?>{'id': item}, 'amount': 1}];
  }
  return out;
}

/// Entries of [page] whose slot falls outside a page of [rows] rows, as
/// `grid slot` labels.
List<String> shopEntriesOutside(JsonMap page, int rows) => <String>[
      for (final String grid in const <String>['buy', 'sell', 'trade'])
        for (final String slot in asMap(page[grid]).keys)
          if ((int.tryParse(slot) ?? -1) >= rows * 9) '$grid $slot',
    ];
