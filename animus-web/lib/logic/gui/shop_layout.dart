/// A shop drawn in the chest GUI its `layout` names, as core's ShopScreen
/// does: the GUI's own slots, and its list area filled with every offer in
/// trading screen order (no static entries), one page of the list at a
/// time. `{{ args.title }}`, `{{ args.shop }}`, `{{ page }}` and
/// `{{ pages }}` in the title read as the shop gives them.
library;

import 'gui_json.dart';
import 'gui_layout.dart';
import 'shop_board.dart';

/// Whether [gui] has a list area a shop can fill.
bool layoutHasList(JsonMap gui) => asString(asMap(gui['list'])['area']) != null;

/// The offers a layout lists: shown modes only, static entries left out.
List<BoardOffer> layoutOffers(JsonMap shop) => <BoardOffer>[
      for (final BoardOffer offer in boardOffers(shop))
        if (offer.modeOn && offer.type != 'static') offer,
    ];

/// [shop] in [gui] on list page [page] (from 0), with the page count.
({ChestLayout layout, int pages}) layoutShopInGui(JsonMap gui, JsonMap shop, {int page = 0}) {
  final ChestLayout base = layoutChest(gui, listEntries: 0);
  final String? area = asString(asMap(gui['list'])['area']);
  final List<int> areaSlots = <int>[for (final ChestCell cell in base.cells) if (area != null && cell.maskChar == area) cell.slot];
  final List<BoardOffer> offers = layoutOffers(shop);
  final int size = areaSlots.isEmpty ? 1 : areaSlots.length;
  final int pages = offers.isEmpty ? 1 : (offers.length + size - 1) ~/ size;
  final int shown = page.clamp(0, pages - 1);
  final List<ChestCell> cells = <ChestCell>[...base.cells];
  for (int index = 0; index < areaSlots.length; index++) {
    final int at = shown * size + index;
    final int slot = areaSlots[index];
    cells[slot] = at < offers.length
        ? ChestCell(slot: slot, source: ChestCellSource.entry, icon: shopEntryIcon(offers[at].entry), definition: offers[at].ref.steps, maskChar: area)
        : ChestCell(slot: slot, source: ChestCellSource.empty, maskChar: area);
  }
  final String shopTitle = asString(shop['title']) ?? asString(shop['id']) ?? '';
  RegExp hole(String name) => RegExp('\\{\\{\\s*$name\\s*\\}\\}');
  final String title = base.title
      .replaceAll(hole(r'args\.title'), shopTitle)
      .replaceAll(hole(r'args\.shop'), asString(shop['id']) ?? '')
      .replaceAll(hole('pages'), '$pages')
      .replaceAll(hole('page'), '${shown + 1}');
  return (layout: ChestLayout(width: base.width, rows: base.rows, title: title, cells: cells), pages: pages);
}

/// The offer a cell shows, or null for any other cell.
OfferRef? offerAtCell(ChestCell cell) {
  final List<JsonStep>? steps = cell.definition;
  if (cell.source != ChestCellSource.entry || steps == null || steps.length != 4 || steps[0] != 'pages') return null;
  final Object page = steps[1];
  final int? slot = int.tryParse('${steps[3]}');
  if (page is! int || slot == null) return null;
  return OfferRef(page, steps[2] as String, slot);
}

/// [layout] as a player sees it who is sold out ([soldOut]: offers with
/// stock or a limit) or lacks the offers' permissions ([noPermission]):
/// those offers dimmed, their lore as the server writes it (stock 0, the
/// limit used up, "Needs permission"). [layout] itself when neither.
ChestLayout shopShownAs(ChestLayout layout, JsonMap shop, {required bool soldOut, required bool noPermission}) {
  if (!soldOut && !noPermission) return layout;
  final JsonList pages = asList(shop['pages']);
  return ChestLayout(
    width: layout.width,
    rows: layout.rows,
    title: layout.title,
    cells: <ChestCell>[for (final ChestCell cell in layout.cells) _shownAs(cell, pages, soldOut: soldOut, noPermission: noPermission)],
  );
}

ChestCell _shownAs(ChestCell cell, JsonList pages, {required bool soldOut, required bool noPermission}) {
  final OfferRef? ref = offerAtCell(cell);
  if (ref == null) return cell;
  final JsonMap entry = asMap(asMap(asMap(pages.elementAtOrNull(ref.page))[ref.grid])['${ref.slot}']);
  final Object? perPlayer = asMap(entry['limit'])['perPlayer'];
  final String? permission = asString(entry['permission']);
  final bool out = soldOut && (asMap(entry['stock']).isNotEmpty || asMap(entry['limit']).isNotEmpty);
  final bool locked = noPermission && permission != null;
  if (!out && !locked) return cell;
  final List<String> lore = <String>[
    for (final Object? line in asList(cell.icon['lore']))
      if (line is String && !(locked && line == '&8Needs $permission'))
        if (out && line.startsWith('&7Stock: '))
          '&7Stock: &f0'
        else if (out && perPlayer != null && line.startsWith('&7Limit: '))
          '&7Limit: &f$perPlayer/$perPlayer'
        else
          line,
    if (locked) '&cNeeds permission',
  ];
  return cell.copyWith(icon: <String, Object?>{...cell.icon, 'lore': lore}, hidden: true);
}
