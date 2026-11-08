/// Item slot edits for the NPC inventory, equipment and hire items: stack
/// counts, moves between slots, which equipment slots a body shows and where
/// they sit, and arrow-key steps through a slot grid.
library;

import 'dart:math' as math;

import '../../mc/npc/npc_look.dart';
import '../gui/gui_json.dart';

/// The largest stack the schema allows.
const int maxStack = 99;

/// Slots in one inventory row.
const int inventoryColumns = 9;

/// The `animus:hire` module key.
const String hireModule = 'animus:hire';

/// Items one hire may cost.
const int hireItemLimit = 6;

int clampCount(int count) => count.clamp(1, maxStack);

/// The stack count of [item], 1 by default.
int itemCount(JsonMap item) => asInt(item['count']) ?? 1;

/// [item] with [count] clamped; 1, the default, drops the key.
JsonMap withCount(JsonMap item, int count) {
  final int clamped = clampCount(count);
  final JsonMap next = <String, Object?>{...item};
  if (clamped == 1) {
    next.remove('count');
  } else {
    next['count'] = clamped;
  }
  return next;
}

bool _empty(JsonMap? item) => item == null || (asString(item['id']) ?? '').trim().isEmpty;

// Inventory -----------------------------------------------------------------

/// Slot numbers the grid draws: whole rows of the size, plus rows reaching
/// any slot stored past it.
List<int> inventoryGrid(JsonMap inventory) {
  final int size = asInt(inventory['size']) ?? inventoryColumns;
  int last = size - 1;
  for (final String key in asMap(inventory['slots']).keys) {
    if (int.tryParse(key) case final int slot) last = math.max(last, slot);
  }
  final int rows = (last + inventoryColumns) ~/ inventoryColumns;
  return List<int>.generate(math.max(rows, 1) * inventoryColumns, (int slot) => slot);
}

/// [inventory] with [item] in [slot]; null or a blank id empties it.
JsonMap putInventoryItem(JsonMap inventory, int slot, JsonMap? item) {
  final JsonMap slots = <String, Object?>{...asMap(inventory['slots'])};
  if (_empty(item)) {
    slots.remove('$slot');
  } else {
    slots['$slot'] = item;
  }
  return <String, Object?>{...inventory, 'slots': slots};
}

/// [inventory] with the items of [from] and [to] traded; an empty target
/// takes the item and leaves [from] empty.
JsonMap swapInventorySlots(JsonMap inventory, int from, int to) {
  if (from == to) return inventory;
  final JsonMap slots = asMap(inventory['slots']);
  final Object? moving = slots['$from'], target = slots['$to'];
  final JsonMap next = <String, Object?>{
    for (final MapEntry<String, Object?> entry in slots.entries)
      if (entry.key != '$from' && entry.key != '$to') entry.key: entry.value,
    '$to': ?moving,
    '$from': ?target,
  };
  return <String, Object?>{...inventory, 'slots': next};
}

// Equipment -----------------------------------------------------------------

/// Where a slot sits in the equipment grid.
final class SlotPlace {
  const SlotPlace(this.slot, this.column, this.row);

  final NpcSlot slot;
  final int column;
  final int row;
}

/// The player inventory's arrangement: armor down the left, mount slots
/// beside it, the off hand and main hand along the bottom.
const Map<NpcSlot, (int, int)> _places = <NpcSlot, (int, int)>{
  NpcSlot.head: (0, 0),
  NpcSlot.chest: (0, 1),
  NpcSlot.legs: (0, 2),
  NpcSlot.feet: (0, 3),
  NpcSlot.saddle: (1, 0),
  NpcSlot.body: (1, 1),
  NpcSlot.offHand: (1, 3),
  NpcSlot.mainHand: (2, 3),
};

/// Slots the body draws, plus any set slot it does not, in slot order.
List<NpcSlot> shownEquipmentSlots(String bodyType, JsonMap equipment) {
  final List<NpcSlot> drawn = mcDrawnSlots(bodyType);
  return <NpcSlot>[
    for (final NpcSlot slot in NpcSlot.values)
      if (drawn.contains(slot) || equipment.containsKey(slot.key)) slot,
  ];
}

/// Set slots the body does not draw.
List<NpcSlot> unsupportedEquipmentSlots(String bodyType, JsonMap equipment) {
  final List<NpcSlot> drawn = mcDrawnSlots(bodyType);
  return <NpcSlot>[
    for (final NpcSlot slot in NpcSlot.values)
      if (!drawn.contains(slot) && equipment.containsKey(slot.key)) slot,
  ];
}

/// Grid places for [shown], with columns and rows nothing uses closed up.
List<SlotPlace> equipmentPlaces(List<NpcSlot> shown) {
  final List<int> columns = <int>{for (final NpcSlot slot in shown) _places[slot]!.$1}.toList()..sort();
  final List<int> rows = <int>{for (final NpcSlot slot in shown) _places[slot]!.$2}.toList()..sort();
  final List<SlotPlace> places = <SlotPlace>[
    for (final NpcSlot slot in shown) SlotPlace(slot, columns.indexOf(_places[slot]!.$1), rows.indexOf(_places[slot]!.$2)),
  ];
  return places..sort((SlotPlace a, SlotPlace b) => a.row != b.row ? a.row - b.row : a.column - b.column);
}

/// [equipment] with [item] in [slot]; null once nothing is left.
JsonMap? putEquipmentItem(JsonMap equipment, NpcSlot slot, JsonMap? item) {
  final JsonMap next = <String, Object?>{...equipment};
  if (_empty(item)) {
    next.remove(slot.key);
  } else {
    next[slot.key] = item;
  }
  return next.isEmpty ? null : next;
}

/// [equipment] with the items of [from] and [to] traded.
JsonMap swapEquipment(JsonMap equipment, NpcSlot from, NpcSlot to) {
  final Object? moving = equipment[from.key], target = equipment[to.key];
  final JsonMap next = <String, Object?>{...equipment}
    ..remove(from.key)
    ..remove(to.key);
  if (moving != null) next[to.key] = moving;
  if (target != null) next[from.key] = target;
  return next;
}

/// What each slot holds, for the editor's help line.
const Map<NpcSlot, String> slotHelp = <NpcSlot, String>{
  NpcSlot.mainHand: 'Right hand. Used by melee.fromEquipment.',
  NpcSlot.offHand: 'Held in the left hand.',
  NpcSlot.head: 'Helmet, head or block.',
  NpcSlot.chest: 'Chestplate or elytra.',
  NpcSlot.legs: 'Leggings.',
  NpcSlot.feet: 'Boots.',
  NpcSlot.body: 'Horse armor, llama carpet or wolf armor.',
  NpcSlot.saddle: 'Saddle on horses, pigs and striders.',
};

/// A label for a slot key: `main_hand` reads "Main hand".
String slotLabel(NpcSlot slot) => '${slot.key[0].toUpperCase()}${slot.key.substring(1).replaceAll('_', ' ')}';

/// An example id that fits [slot] on [bodyType].
String slotPlaceholder(NpcSlot slot, String bodyType) => switch (slot) {
  NpcSlot.mainHand => 'minecraft:iron_sword',
  NpcSlot.offHand => 'minecraft:shield',
  NpcSlot.head => 'minecraft:iron_helmet',
  NpcSlot.chest => 'minecraft:iron_chestplate',
  NpcSlot.legs => 'minecraft:iron_leggings',
  NpcSlot.feet => 'minecraft:iron_boots',
  NpcSlot.body => switch (bodyType) {
    'minecraft:llama' || 'minecraft:trader_llama' => 'minecraft:white_carpet',
    'minecraft:wolf' => 'minecraft:wolf_armor',
    _ => 'minecraft:iron_horse_armor',
  },
  NpcSlot.saddle => 'minecraft:saddle',
};

const List<String> _materials = <String>['leather', 'chainmail', 'iron', 'golden', 'diamond', 'netherite', 'copper'];
const List<String> _toolMaterials = <String>['wooden', 'stone', 'copper', 'iron', 'golden', 'diamond', 'netherite'];
const List<String> _colors = <String>[
  'white', 'orange', 'magenta', 'light_blue', 'yellow', 'lime', 'pink', 'gray',
  'light_gray', 'cyan', 'purple', 'blue', 'brown', 'green', 'red', 'black',
];

/// Item ids offered first for [slot].
List<String> slotSuggestions(NpcSlot slot) => switch (slot) {
  NpcSlot.mainHand || NpcSlot.offHand => <String>[
    for (final String material in _toolMaterials) ...<String>['minecraft:${material}_sword', 'minecraft:${material}_axe', 'minecraft:${material}_pickaxe'],
    'minecraft:bow', 'minecraft:crossbow', 'minecraft:trident', 'minecraft:mace', 'minecraft:shield', 'minecraft:torch',
    'minecraft:fishing_rod', 'minecraft:book', 'minecraft:bread', 'minecraft:emerald', 'minecraft:lantern',
  ],
  NpcSlot.head => <String>[
    for (final String material in _materials) 'minecraft:${material}_helmet',
    'minecraft:turtle_helmet', 'minecraft:carved_pumpkin', 'minecraft:player_head', 'minecraft:skeleton_skull', 'minecraft:zombie_head',
  ],
  NpcSlot.chest => <String>[for (final String material in _materials) 'minecraft:${material}_chestplate', 'minecraft:elytra'],
  NpcSlot.legs => <String>[for (final String material in _materials) 'minecraft:${material}_leggings'],
  NpcSlot.feet => <String>[for (final String material in _materials) 'minecraft:${material}_boots'],
  NpcSlot.body => <String>[
    for (final String material in <String>['leather', 'iron', 'golden', 'diamond', 'netherite', 'copper']) 'minecraft:${material}_horse_armor',
    for (final String color in _colors) 'minecraft:${color}_carpet',
    'minecraft:wolf_armor',
  ],
  NpcSlot.saddle => const <String>['minecraft:saddle'],
};

// Hire items ----------------------------------------------------------------

/// [items] with [item] at [index]: past the end it is appended (up to
/// [hireItemLimit]), and null or a blank id removes the entry.
List<Object?> putHireItem(List<Object?> items, int index, JsonMap? item) {
  final List<Object?> next = <Object?>[...items];
  if (_empty(item)) {
    if (index < next.length) next.removeAt(index);
  } else if (index < next.length) {
    next[index] = item;
  } else if (next.length < hireItemLimit) {
    next.add(item);
  }
  return next;
}

/// [items] with [from] and [to] traded; an empty target sends the item to
/// the end of the row, since the list has no gaps.
List<Object?> moveHireItem(List<Object?> items, int from, int to) {
  if (from == to || from >= items.length) return items;
  final List<Object?> next = <Object?>[...items];
  if (to < next.length) {
    final Object? moving = next[from];
    next[from] = next[to];
    next[to] = moving;
  } else {
    next.add(next.removeAt(from));
  }
  return next;
}

// Grid steps ----------------------------------------------------------------

/// The index in [cells] (column, row) one arrow step from [from] along
/// ([dx], [dy]): the nearest cell in that direction, preferring its own
/// line; null at the edge.
int? gridNeighbor(List<(int, int)> cells, int from, int dx, int dy) {
  final (int column, int row) = cells[from];
  int? best;
  int bestScore = 0;
  for (int index = 0; index < cells.length; index++) {
    final (int c, int r) = cells[index];
    final int along = (c - column) * dx + (r - row) * dy;
    if (along <= 0) continue;
    final int across = dx != 0 ? (r - row).abs() : (c - column).abs();
    final int score = along + across * 2;
    if (best == null || score < bestScore) {
      best = index;
      bestScore = score;
    }
  }
  return best;
}
