/// What a chest GUI or shop window shows, slot by slot, as the server lays
/// it out: mask keys, explicit slots over them, list cells over everything
/// (docs/gui.md), the chosen variant's replacements, and for shops the
/// entries of one page and mode with the bottom-row controls (docs/shops.md).
/// Each cell records where its component is defined so a click edits it.
library;

import 'gui_expr.dart';
import 'gui_expression.dart';
import 'gui_json.dart';
import 'gui_validation.dart';

enum ChestCellSource { empty, mask, slot, list, entry, control }

/// What one slot draws. [definition] is the path of the component (or shop
/// entry) in the document; null for empty cells and shop controls.
final class ChestCell {
  const ChestCell({
    required this.slot,
    required this.source,
    this.icon = const <String, Object?>{},
    this.definition,
    this.maskChar,
    this.hidden = false,
    this.label,
  });

  final int slot;
  final ChestCellSource source;

  /// The icon drawn: `item`, `count`, `name`, `lore`, `player`, `type`.
  final JsonMap icon;
  final List<JsonStep>? definition;
  final String? maskChar;

  /// The component's `show` is false with sample data; the editor dims it.
  final bool hidden;

  /// A short marker for list cells and controls.
  final String? label;

  String? get itemId {
    if (asString(icon['type']) == 'playerHead') return 'minecraft:player_head';
    return asString(icon['item']);
  }
}

final class ChestLayout {
  const ChestLayout({required this.width, required this.rows, required this.title, required this.cells});

  final int width;
  final int rows;

  /// The raw title text (codes and holes not yet rendered).
  final String title;
  final List<ChestCell> cells;

  ChestCell cell(int slot) => cells[slot];
}

/// The `presentation` a variant replaces fields with, or the document.
JsonMap _presentation(JsonMap doc, int? variant) {
  if (variant == null) return doc;
  final JsonList variants = asList(doc['variants']);
  if (variant < 0 || variant >= variants.length) return doc;
  return asMap(asMap(variants[variant])['presentation']);
}

/// Where field [name] of the shown presentation lives: the variant when it
/// replaces the field, otherwise the document.
List<JsonStep> chestFieldOwner(JsonMap doc, int? variant, String name) {
  if (variant == null) return const <JsonStep>[];
  final JsonMap presentation = _presentation(doc, variant);
  return presentation.containsKey(name) ? <JsonStep>['variants', variant, 'presentation'] : const <JsonStep>[];
}

/// The icon a component shows with sample data: a toggle shows its true or
/// false side.
JsonMap componentIcon(JsonMap component, {PExprScope? scope}) {
  if (asString(component['type']) == 'toggle') {
    final Object? condition = component['condition'];
    bool on = sampleCondition(condition, scope: scope);
    final Object? expected = component['expectedValue'];
    if (expected == false || expected == 'false') on = !on;
    return asMap(on ? component['trueIcon'] : component['falseIcon']);
  }
  return asMap(component['icon']);
}

/// Lays out a chest GUI. [variant] indexes `variants` (null for the base),
/// [listEntries] is how many entries the list preview fills.
ChestLayout layoutChest(JsonMap doc, {int? variant, int listEntries = 1 << 20}) {
  final (int width, int rows) = chestSize(asString(doc['resolution']));
  final JsonMap shown = _presentation(doc, variant);
  JsonMap pick(String name) => asMap(shown.containsKey(name) ? shown[name] : doc[name]);
  JsonList pickList(String name) => asList(shown.containsKey(name) ? shown[name] : doc[name]);
  final List<JsonStep> keysOwner = <JsonStep>[...chestFieldOwner(doc, variant, 'keys'), 'keys'];
  final List<JsonStep> slotsOwner = <JsonStep>[...chestFieldOwner(doc, variant, 'slots'), 'slots'];
  final JsonMap keys = pick('keys');
  final JsonMap slots = pick('slots');
  final JsonList mask = pickList('mask');
  final String title = asString(shown['title']) ?? asString(doc['title']) ?? '';
  final JsonMap list = asMap(doc['list']);
  final String? area = asString(list['area']);
  final String variable = asString(list['var']) ?? 'entry';

  final List<ChestCell> cells = <ChestCell>[];
  for (int slot = 0; slot < width * rows; slot++) {
    final int row = slot ~/ width;
    final int column = slot % width;
    final String? line = row < mask.length ? asString(mask[row]) : null;
    final String? char = line != null && column < line.length && line[column] != ' ' ? line[column] : null;
    ChestCell cell = ChestCell(slot: slot, source: ChestCellSource.empty, maskChar: char);
    final JsonMap? keyed = char == null ? null : (keys[char] is Map<String, Object?> ? keys[char]! as JsonMap : null);
    if (keyed != null) {
      cell = ChestCell(
        slot: slot,
        source: ChestCellSource.mask,
        icon: componentIcon(keyed),
        definition: <JsonStep>[...keysOwner, char!],
        maskChar: char,
        hidden: !sampleCondition(keyed['show']),
      );
    }
    final Object? explicit = slots['$slot'];
    if (explicit is Map<String, Object?>) {
      cell = ChestCell(
        slot: slot,
        source: ChestCellSource.slot,
        icon: componentIcon(explicit),
        definition: <JsonStep>[...slotsOwner, '$slot'],
        maskChar: char,
        hidden: !sampleCondition(explicit['show']),
      );
    }
    cells.add(cell);
  }
  if (area != null && list['template'] is Map<String, Object?>) {
    final JsonMap template = list['template']! as JsonMap;
    final List<int> areaSlots = <int>[for (final ChestCell cell in cells) if (cell.maskChar == area) cell.slot];
    final int pageSize = asInt(list['pageSize']) ?? areaSlots.length;
    final Object? source = list['source'] is String ? sampleValue(list['source']! as String) : list['source'];
    final List<Object?> entries = source is List<Object?> ? source : <Object?>[for (int i = 0; i < areaSlots.length; i++) null];
    final int filled = <int>[areaSlots.length, pageSize, entries.length, listEntries].reduce((int a, int b) => a < b ? a : b);
    for (int index = 0; index < filled; index++) {
      final Object? entry = entries[index];
      final GuiSampleScope scope = GuiSampleScope(extra: <String, Object?>{
        if (entry != null) variable: entry is num ? entry.toDouble() : entry,
      });
      final JsonMap icon = componentIcon(template, scope: scope);
      final int slot = areaSlots[index];
      cells[slot] = ChestCell(
        slot: slot,
        source: ChestCellSource.list,
        icon: <String, Object?>{
          ...icon,
          if (icon['name'] is String) 'name': renderTemplate(icon['name']! as String, scope: scope),
        },
        definition: const <JsonStep>['list', 'template'],
        maskChar: area,
        hidden: !sampleCondition(template['show'], scope: scope),
        label: '${index + 1}',
      );
    }
  }
  return ChestLayout(width: width, rows: rows, title: title, cells: cells);
}

/// The entries of one shop page and mode, with the controls a player would
/// see: previous and next page, close, and the mode switch.
ChestLayout layoutShop(JsonMap shop, {required int page, required String mode}) {
  final JsonList pages = asList(shop['pages']);
  final JsonMap data = page >= 0 && page < pages.length ? asMap(pages[page]) : const <String, Object?>{};
  final int rows = (asInt(data['rows']) ?? 3).clamp(1, 6);
  final JsonMap grid = asMap(data[mode]);
  final List<String> modes = <String>[for (final Object? one in asList(shop['modes'])) if (one is String) one];
  final List<String> enabled = modes.isEmpty ? shopModes : modes;
  final List<String> withEntries = <String>[
    for (final String one in enabled)
      if (pages.any((Object? p) => asMap(asMap(p)[one]).isNotEmpty)) one,
  ];
  final Map<int, (String, String)> controls = <int, (String, String)>{
    if (page > 0) 2: ('minecraft:arrow', '&ePrevious page'),
    4: ('minecraft:barrier', '&cClose'),
    if (page < pages.length - 1) 6: ('minecraft:arrow', '&eNext page'),
    if (withEntries.length > 1) 8: ('minecraft:hopper', '&bSwitch mode'),
  };
  final int bottom = (rows - 1) * 9;
  final List<ChestCell> cells = <ChestCell>[];
  for (int slot = 0; slot < rows * 9; slot++) {
    final (String, String)? control = slot >= bottom ? controls[slot - bottom] : null;
    if (control != null) {
      cells.add(ChestCell(
        slot: slot,
        source: ChestCellSource.control,
        icon: <String, Object?>{'type': 'item', 'item': control.$1, 'name': control.$2},
        label: 'control',
      ));
      continue;
    }
    final Object? entry = grid['$slot'];
    if (entry is Map<String, Object?>) {
      cells.add(ChestCell(
        slot: slot,
        source: ChestCellSource.entry,
        icon: shopEntryIcon(entry),
        definition: <JsonStep>['pages', page, mode, '$slot'],
      ));
    } else {
      cells.add(ChestCell(slot: slot, source: ChestCellSource.empty));
    }
  }
  final String title = asString(shop['title']) ?? asString(shop['id']) ?? '';
  final String pageName = asString(data['name']) ?? 'Page ${page + 1}';
  return ChestLayout(width: 9, rows: rows, title: '$title &8- $pageName', cells: cells);
}

/// The icon and tooltip a shop entry shows: its display name and lore, then
/// the price, discount, stock and limit lines.
JsonMap shopEntryIcon(JsonMap entry) {
  final JsonMap item = asMap(entry['item']);
  final JsonMap display = asMap(entry['display']);
  final String type = asString(entry['type']) ?? 'static';
  final List<String> lore = <String>[for (final Object? line in asList(display['lore'])) if (line is String) line];
  final double? price = asDouble(entry['price']);
  final JsonMap discount = asMap(entry['discount']);
  if (display['showPrice'] != false && price != null && type != 'static') {
    final double? sale = asDouble(discount['price']);
    lore.add(sale == null ? '&7Price: &6${_money(price)}' : '&7Price: &m${_money(price)}&r &6${_money(sale)}');
  }
  if (type == 'trade') {
    for (final Object? need in asList(entry['needs'])) {
      final JsonMap goods = asMap(need);
      lore.add('&7Needs: &f${asInt(goods['amount']) ?? asInt(asMap(goods['item'])['count']) ?? 1}x ${_readable(asString(asMap(goods['item'])['id']))}');
    }
    for (final Object? give in asList(entry['gives'])) {
      final JsonMap goods = asMap(give);
      lore.add('&7Gives: &f${asInt(goods['amount']) ?? asInt(asMap(goods['item'])['count']) ?? 1}x ${_readable(asString(asMap(goods['item'])['id']))}');
    }
  }
  final JsonMap stock = asMap(entry['stock']);
  if (stock.isNotEmpty) lore.add('&7Stock: &f${asInt(stock['maximum']) ?? '?'}');
  final JsonMap limit = asMap(entry['limit']);
  if (display['showLimit'] != false && limit.isNotEmpty) {
    final List<String> parts = <String>[
      if (limit['perPlayer'] != null) '${limit['perPlayer']} per player',
      if (limit['global'] != null) '${limit['global']} in total',
      if (limit['resetSeconds'] != null) 'resets every ${_duration(asInt(limit['resetSeconds']) ?? 0)}',
    ];
    lore.add('&7Limit: &f${parts.join(', ')}');
  }
  if (asString(entry['permission']) != null) lore.add('&8Needs ${entry['permission']}');
  return <String, Object?>{
    'type': 'item',
    'item': asString(item['id']) ?? 'minecraft:barrier',
    'count': asInt(item['count']) ?? 1,
    'name': asString(display['name']) ?? '&f${_readable(asString(item['id']))}',
    'lore': lore,
  };
}

String _money(double value) => value == value.roundToDouble() ? value.toInt().toString() : value.toString();

String _readable(String? id) {
  if (id == null) return '?';
  final String bare = id.substring(id.indexOf(':') + 1);
  return bare.split('_').map((String word) => word.isEmpty ? word : '${word[0].toUpperCase()}${word.substring(1)}').join(' ');
}

String _duration(int seconds) {
  if (seconds % 86400 == 0) return '${seconds ~/ 86400}d';
  if (seconds % 3600 == 0) return '${seconds ~/ 3600}h';
  if (seconds % 60 == 0) return '${seconds ~/ 60}m';
  return '${seconds}s';
}
