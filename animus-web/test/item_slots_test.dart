import 'package:animus_editor/logic/gui/gui_json.dart';
import 'package:animus_editor/logic/npc/item_slots.dart';
import 'package:animus_editor/mc/npc/npc_look.dart';
import 'package:test/test.dart';

const JsonMap _bread = <String, Object?>{'id': 'minecraft:bread', 'count': 5};
const JsonMap _arrow = <String, Object?>{'id': 'minecraft:arrow'};

void main() {
  group('equipment lines', () {
    test('should name the body, never its id', () {
      expect(noEquipmentLine('minecraft:cat'), 'Cat bodies show no equipment.');
      expect(unshownSlotsLine('minecraft:player', <NpcSlot>[NpcSlot.body, NpcSlot.saddle]), 'Player bodies do not show body, saddle. Clear them or change the body type.');
      expect(unshownSlotLine('mymod:dragon'), 'Dragon (mymod) bodies do not show this slot. Clear it or change the body type.');
    });

    test('should describe every slot without engine keys', () {
      for (final String help in slotHelp.values) {
        expect(help, isNot(matches(RegExp(r'[a-z]+\.[a-zA-Z]+'))), reason: help);
      }
    });
  });

  group('counts', () {
    test('should clamp a count to 1..99', () {
      expect(clampCount(0), 1);
      expect(clampCount(-4), 1);
      expect(clampCount(42), 42);
      expect(clampCount(150), 99);
    });

    test('should read 1 when an item has no count', () {
      expect(itemCount(_arrow), 1);
      expect(itemCount(_bread), 5);
    });

    test('should drop the count key at 1 and clamp others', () {
      expect(withCount(_bread, 1), <String, Object?>{'id': 'minecraft:bread'});
      expect(withCount(_arrow, 7), <String, Object?>{'id': 'minecraft:arrow', 'count': 7});
      expect(withCount(_arrow, 300), <String, Object?>{'id': 'minecraft:arrow', 'count': 99});
    });
  });

  group('inventory', () {
    test('should lay out whole rows of the size', () {
      expect(inventoryGrid(<String, Object?>{'size': 18, 'slots': <String, Object?>{}}), List<int>.generate(18, (int slot) => slot));
    });

    test('should add rows for slots past the size', () {
      final JsonMap inventory = <String, Object?>{
        'size': 9,
        'slots': <String, Object?>{'12': _arrow},
      };
      expect(inventoryGrid(inventory), List<int>.generate(18, (int slot) => slot));
    });

    test('should put and remove an item by slot', () {
      final JsonMap inventory = <String, Object?>{'size': 9, 'slots': <String, Object?>{}};
      final JsonMap put = putInventoryItem(inventory, 3, _bread);
      expect(put['slots'], <String, Object?>{'3': _bread});
      expect(put['size'], 9);
      expect(putInventoryItem(put, 3, null)['slots'], <String, Object?>{});
      expect(putInventoryItem(put, 3, const <String, Object?>{'id': ''})['slots'], <String, Object?>{});
    });

    test('should swap two filled slots', () {
      final JsonMap inventory = <String, Object?>{
        'size': 9,
        'slots': <String, Object?>{'0': _bread, '4': _arrow},
      };
      expect(swapInventorySlots(inventory, 0, 4)['slots'], <String, Object?>{'0': _arrow, '4': _bread});
    });

    test('should move an item into an empty slot', () {
      final JsonMap inventory = <String, Object?>{
        'size': 9,
        'slots': <String, Object?>{'0': _bread},
      };
      expect(swapInventorySlots(inventory, 0, 8)['slots'], <String, Object?>{'8': _bread});
    });

    test('should keep the inventory when moving a slot onto itself', () {
      final JsonMap inventory = <String, Object?>{
        'size': 9,
        'slots': <String, Object?>{'0': _bread},
      };
      expect(swapInventorySlots(inventory, 0, 0), inventory);
    });
  });

  group('equipment', () {
    test('should show the player slots on a player body', () {
      expect(shownEquipmentSlots('minecraft:player', const <String, Object?>{}), <NpcSlot>[
        NpcSlot.mainHand, NpcSlot.offHand, NpcSlot.head, NpcSlot.chest, NpcSlot.legs, NpcSlot.feet, //
      ]);
    });

    test('should show body and saddle on a horse, body on a llama', () {
      expect(shownEquipmentSlots('minecraft:horse', const <String, Object?>{}), <NpcSlot>[NpcSlot.body, NpcSlot.saddle]);
      expect(shownEquipmentSlots('minecraft:llama', const <String, Object?>{}), <NpcSlot>[NpcSlot.body]);
    });

    test('should also show a set slot the body cannot draw, and name it unsupported', () {
      const JsonMap equipment = <String, Object?>{'head': _arrow};
      expect(shownEquipmentSlots('minecraft:horse', equipment), <NpcSlot>[NpcSlot.head, NpcSlot.body, NpcSlot.saddle]);
      expect(unsupportedEquipmentSlots('minecraft:horse', equipment), <NpcSlot>[NpcSlot.head]);
      expect(unsupportedEquipmentSlots('minecraft:player', equipment), isEmpty);
    });

    test('should place armor in a column and the hands beside its first rows', () {
      final Map<NpcSlot, (int, int)> places = <NpcSlot, (int, int)>{
        for (final SlotPlace place in equipmentPlaces(shownEquipmentSlots('minecraft:player', const <String, Object?>{}))) place.slot: (place.column, place.row),
      };
      expect(places, <NpcSlot, (int, int)>{
        NpcSlot.head: (0, 0),
        NpcSlot.chest: (0, 1),
        NpcSlot.legs: (0, 2),
        NpcSlot.feet: (0, 3),
        NpcSlot.mainHand: (1, 0),
        NpcSlot.offHand: (1, 1),
      });
      expect(<NpcSlot>[for (final SlotPlace place in equipmentPlaces(shownEquipmentSlots('minecraft:player', const <String, Object?>{}))) place.slot],
          <NpcSlot>[NpcSlot.head, NpcSlot.chest, NpcSlot.legs, NpcSlot.feet, NpcSlot.mainHand, NpcSlot.offHand], reason: 'read column by column');
    });

    test('should close up empty columns and rows', () {
      List<(NpcSlot, int, int)> places(String type) => <(NpcSlot, int, int)>[
        for (final SlotPlace place in equipmentPlaces(shownEquipmentSlots(type, const <String, Object?>{}))) (place.slot, place.column, place.row),
      ];
      expect(places('minecraft:horse'), <(NpcSlot, int, int)>[(NpcSlot.saddle, 0, 0), (NpcSlot.body, 0, 1)]);
      expect(places('minecraft:llama'), <(NpcSlot, int, int)>[(NpcSlot.body, 0, 0)]);
      expect(places('minecraft:turtle'), isEmpty);
    });

    test('should put an item and return null once every slot is empty', () {
      final JsonMap? put = putEquipmentItem(const <String, Object?>{}, NpcSlot.head, _arrow);
      expect(put, <String, Object?>{'head': _arrow});
      expect(putEquipmentItem(put!, NpcSlot.head, null), isNull);
      expect(putEquipmentItem(put, NpcSlot.head, const <String, Object?>{'id': ' '}), isNull);
    });

    test('should swap or move items between slots, keeping other keys', () {
      const JsonMap equipment = <String, Object?>{'main_hand': _bread, 'off_hand': _arrow, 'custom': 1};
      expect(swapEquipment(equipment, NpcSlot.mainHand, NpcSlot.offHand), <String, Object?>{'main_hand': _arrow, 'off_hand': _bread, 'custom': 1});
      expect(swapEquipment(const <String, Object?>{'main_hand': _bread}, NpcSlot.mainHand, NpcSlot.head), <String, Object?>{'head': _bread});
    });

    test('should give each slot a fitting placeholder', () {
      expect(slotPlaceholder(NpcSlot.mainHand, 'minecraft:player'), 'minecraft:iron_sword');
      expect(slotPlaceholder(NpcSlot.offHand, 'minecraft:player'), 'minecraft:shield');
      expect(slotPlaceholder(NpcSlot.head, 'minecraft:player'), 'minecraft:iron_helmet');
      expect(slotPlaceholder(NpcSlot.feet, 'minecraft:player'), 'minecraft:iron_boots');
      expect(slotPlaceholder(NpcSlot.saddle, 'minecraft:horse'), 'minecraft:saddle');
      expect(slotPlaceholder(NpcSlot.body, 'minecraft:horse'), 'minecraft:iron_horse_armor');
      expect(slotPlaceholder(NpcSlot.body, 'minecraft:llama'), 'minecraft:white_carpet');
      expect(slotPlaceholder(NpcSlot.body, 'minecraft:wolf'), 'minecraft:wolf_armor');
      final Set<String> placeholders = <String>{for (final NpcSlot slot in NpcSlot.values) slotPlaceholder(slot, 'minecraft:zombie')};
      expect(placeholders, hasLength(NpcSlot.values.length), reason: 'no shared sword placeholder');
    });
  });

  group('hire items', () {
    test('should append an item put past the end, and stop at six', () {
      expect(putHireItem(const <Object?>[], 4, _arrow), <Object?>[_arrow]);
      final List<Object?> six = List<Object?>.filled(6, _bread);
      expect(putHireItem(six, 6, _arrow), six);
    });

    test('should replace and remove an item by index', () {
      const List<Object?> items = <Object?>[_bread, _arrow];
      expect(putHireItem(items, 0, _arrow), <Object?>[_arrow, _arrow]);
      expect(putHireItem(items, 0, null), <Object?>[_arrow]);
    });

    test('should swap two items, or move one to the end of the row', () {
      const List<Object?> items = <Object?>[_bread, _arrow];
      expect(moveHireItem(items, 0, 1), <Object?>[_arrow, _bread]);
      expect(moveHireItem(items, 0, 5), <Object?>[_arrow, _bread]);
      expect(moveHireItem(items, 4, 0), items, reason: 'an empty slot moves nothing');
    });
  });

  group('grid neighbors', () {
    final List<(int, int)> nine = <(int, int)>[for (int slot = 0; slot < 18; slot++) (slot % 9, slot ~/ 9)];

    test('should step through a grid and stop at its edges', () {
      expect(gridNeighbor(nine, 0, 1, 0), 1);
      expect(gridNeighbor(nine, 0, 0, 1), 9);
      expect(gridNeighbor(nine, 0, -1, 0), isNull);
      expect(gridNeighbor(nine, 17, 0, 1), isNull);
    });

    test('should find the nearest cell across gaps', () {
      final List<(int, int)> player = <(int, int)>[(0, 0), (0, 1), (0, 2), (0, 3), (1, 3), (2, 3)];
      expect(gridNeighbor(player, 0, 1, 0), 4, reason: 'right of head is the off hand');
      expect(gridNeighbor(player, 4, -1, 0), 3);
      expect(gridNeighbor(player, 4, 0, -1), 2, reason: 'up from the off hand drifts to the nearest row above');
      expect(gridNeighbor(player, 0, 0, -1), isNull);
    });
  });
}
