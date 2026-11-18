/// Shop entry rules as core's ShopDocument reads them: which fields each
/// entry type keeps, what a container shop and a book shop allow, and the
/// writer that changes an entry's type without leaving fields the new type
/// refuses.
library;

import 'gui_json.dart';
import 'shop_words.dart' show shopModeLabels;

/// Entry types, in the order the editor offers them.
const List<String> shopEntryTypes = <String>['buy', 'command', 'sell', 'trade', 'static'];

/// Whether [shop] stocks from chests: it lists `containers`.
bool isContainerShop(JsonMap shop) => asList(shop['containers']).isNotEmpty;

/// A chest shop's `kind`, as players pick it in game.
const List<String> shopKinds = <String>['selling', 'buying', 'trading', 'book'];

/// Whether [shop] is a book shop, whose trades copy written books.
bool isBookShop(JsonMap shop) => shop['kind'] == 'book';

/// The title a new book trade starts with, until the owner names the book.
const String untitledBook = 'Untitled';

/// What [shopOwnerName] says when neither the server nor the shop knows.
const String unknownShopOwner = 'Unknown player';

/// The owner of a chest shop by name: the name the server knows for its
/// UUID in [known], else the last name the shop kept, else "Unknown player".
String shopOwnerName(JsonMap shop, Map<String, String> known) {
  final JsonMap owner = asMap(shop['owner']);
  final String? name = known[asString(owner['uuid'])] ?? asString(owner['name']);
  return name == null || name.trim().isEmpty ? unknownShopOwner : name.trim();
}

/// The types a slot of [grid] may hold; a chest shop trades items for
/// items, so it keeps only trade and static entries.
List<String> shopTypesFor(String grid, {required bool container}) => <String>[
      if (grid == 'buy' && !container) ...<String>['buy', 'command'],
      if (grid == 'sell' && !container) 'sell',
      if (grid == 'trade') 'trade',
      'static',
    ];

/// Whether an entry of [type] may carry [key] (core's per-type checks). A
/// book shop's trades name a `book` in place of `gives`.
bool shopEntryKeeps(String type, String key, {bool container = false, bool bookShop = false}) => switch (key) {
      'sellPartial' => type == 'sell',
      'needs' => type == 'trade',
      'gives' => type == 'trade' && !bookShop,
      'book' => type == 'trade' && bookShop,
      'commands' => !container && type == 'command',
      'limit' => type != 'static',
      'stock' => !container && (type == 'buy' || type == 'command' || type == 'trade'),
      'price' => !container,
      'levels' => !container && (type == 'buy' || type == 'command' || type == 'trade'),
      _ => true,
    };

/// [entry] as a [type] entry: fields the new type refuses are removed and
/// the fields it needs are added with starting values; everything else
/// stays.
JsonMap withShopEntryType(JsonMap entry, String type, {bool container = false, bool bookShop = false}) {
  final JsonMap out = <String, Object?>{
    for (final MapEntry<String, Object?> field in entry.entries)
      if (field.key == 'type' || shopEntryKeeps(type, field.key, container: container, bookShop: bookShop)) field.key: field.value,
  };
  out['type'] = type;
  if (!container && (type == 'buy' || type == 'sell' || type == 'command') && out['price'] == null) out['price'] = 1;
  if (type == 'command' && asList(out['commands']).isEmpty) {
    out['commands'] = <Object?>[<String, Object?>{'command': '', 'as': 'console'}];
  }
  if (type == 'trade') {
    final String item = asString(asMap(entry['item'])['id']) ?? 'minecraft:bread';
    if (asList(out['needs']).isEmpty) out['needs'] = <Object?>[<String, Object?>{'item': <String, Object?>{'id': 'minecraft:emerald'}, 'amount': 1}];
    if (bookShop) {
      if ((asString(out['book']) ?? '').trim().isEmpty) out['book'] = untitledBook;
    } else if (asList(out['gives']).isEmpty) {
      out['gives'] = <Object?>[<String, Object?>{'item': <String, Object?>{'id': item}, 'amount': 1}];
    }
  }
  return out;
}

/// The item a book trade shows and gives a copy of.
const String writtenBook = 'minecraft:written_book';

/// [shop] with its chest shop [kind] (null removes it). Trades follow the
/// kind as the game writes them: in a book shop each copies a book and
/// gives no items; out of one each gives items again.
JsonMap withShopKind(JsonMap shop, String? kind) {
  final bool container = isContainerShop(shop);
  final bool books = kind == 'book';
  JsonMap grid(JsonMap cells) => <String, Object?>{
        for (final MapEntry<String, Object?> cell in cells.entries)
          cell.key: switch (cell.value) {
            final JsonMap entry when entry['type'] == 'trade' && books != entry.containsKey('book') => _tradeFor(entry, books: books, container: container),
            final Object? other => other,
          },
      };
  final JsonMap out = <String, Object?>{
    ...shop,
    'pages': <Object?>[
      for (final Object? page in asList(shop['pages']))
        page is JsonMap ? <String, Object?>{for (final MapEntry<String, Object?> field in page.entries) field.key: field.value is JsonMap ? grid(field.value! as JsonMap) : field.value} : page,
    ],
  };
  if (kind == null) {
    out.remove('kind');
  } else {
    out['kind'] = kind;
  }
  return out;
}

JsonMap _tradeFor(JsonMap entry, {required bool books, required bool container}) {
  final JsonMap out = withShopEntryType(entry, 'trade', container: container, bookShop: books);
  if (books) out['item'] = <String, Object?>{'id': writtenBook};
  return out;
}

/// [shop] with [chests] as its containers (none removes the key). The first
/// chest makes it a chest shop, which trades items for items only, so its
/// modes narrow to trades unless it already has offers for money.
JsonMap withChests(JsonMap shop, JsonList chests) {
  final JsonMap out = <String, Object?>{...shop};
  if (chests.isEmpty) {
    out.remove('containers');
    return out;
  }
  out['containers'] = chests;
  final bool money = asList(shop['pages']).any((Object? page) => asMap(asMap(page)['buy']).isNotEmpty || asMap(asMap(page)['sell']).isNotEmpty);
  if (!isContainerShop(shop) && !money) {
    out['modes'] = <Object?>['trade'];
    if (out['defaultMode'] != null && out['defaultMode'] != 'trade') out.remove('defaultMode');
  }
  return out;
}

/// Entries of [page] whose slot falls outside a page of [rows] rows, as
/// "Sells slot 21" labels, slots counted from 1.
List<String> shopEntriesOutside(JsonMap page, int rows) => <String>[
      for (final String grid in const <String>['buy', 'sell', 'trade'])
        for (final String slot in asMap(page[grid]).keys)
          if ((int.tryParse(slot) ?? -1) >= rows * 9) '${shopModeLabels[grid]} slot ${int.parse(slot) + 1}',
    ];
