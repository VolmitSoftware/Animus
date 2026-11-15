import 'package:animus_editor/logic/npc/mount_seat.dart';
import 'package:animus_editor/logic/npc/npc_catalog.dart';
import 'package:test/test.dart';

void main() {
  const String uuid = '0a1b2c3d-0000-4000-8000-000000000003';

  group('mount', () {
    test('should read what a mount module rides', () {
      expect(mountKindOf(null), MountKind.none);
      expect(mountKindOf(<String, Object?>{'vehicle': 'horse'}), MountKind.npc);
      expect(mountKindOf(<String, Object?>{'enabled': false}), MountKind.npc);
      expect(mountKindOf(<String, Object?>{'entity': 'minecraft:horse'}), MountKind.entity);
      expect(mountKindOf(<String, Object?>{'entity': uuid, 'vehicle': 'horse'}), MountKind.entity);
    });

    test('should switch what it rides, dropping the old target and keeping enabled', () {
      expect(withMountKind(<String, Object?>{'vehicle': 'horse'}, MountKind.none), isNull);
      expect(withMountKind(<String, Object?>{'vehicle': 'horse', 'enabled': false}, MountKind.entity), <String, Object?>{'enabled': false, 'entity': 'minecraft:horse'});
      expect(withMountKind(<String, Object?>{'entity': 'minecraft:pig', 'range': 4}, MountKind.npc, vehicle: 'cart'), <String, Object?>{'enabled': true, 'vehicle': 'cart'});
      expect(withMountKind(null, MountKind.npc), <String, Object?>{'enabled': true, 'vehicle': ''});
      final Map<String, Object?> same = <String, Object?>{'entity': 'minecraft:pig', 'range': 4};
      expect(withMountKind(same, MountKind.entity), same);
    });

    test('should keep the range only for an entity type', () {
      expect(withMountEntity(<String, Object?>{'entity': 'minecraft:horse', 'range': 4}, 'minecraft:pig'), <String, Object?>{'entity': 'minecraft:pig', 'range': 4});
      expect(withMountEntity(<String, Object?>{'entity': 'minecraft:horse', 'range': 4, 'enabled': true}, uuid), <String, Object?>{'entity': uuid, 'enabled': true});
      expect(isEntityUuid(uuid), isTrue);
      expect(isEntityUuid('minecraft:horse'), isFalse);
    });

    test('should offer the vehicles players ride by name', () {
      expect(commonVehicles, containsAll(<String>['minecraft:horse', 'minecraft:oak_boat', 'minecraft:minecart', 'minecraft:happy_ghast']));
      expect(commonVehicles, isNot(contains('minecraft:player')));
    });
  });

  group('seat', () {
    test('should read whether players sit on the NPC', () {
      expect(seatOn(null), isFalse);
      expect(seatOn(<String, Object?>{}), isTrue);
      expect(seatOn(<String, Object?>{'enabled': false}), isFalse);
      expect(ridingOn(<String, Object?>{'animus:riding': <String, Object?>{'enabled': true}}), isTrue);
      expect(ridingOn(<String, Object?>{'animus:riding': <String, Object?>{}}), isFalse);
    });

    test('should turn the seat on and off and keep its settings', () {
      expect(seatSwitched(null, on: true, riding: false), <String, Object?>{'enabled': true});
      expect(seatSwitched(<String, Object?>{'stack': 2}, on: false, riding: false), <String, Object?>{'stack': 2, 'enabled': false});
      expect(toggleableModules, contains(seatModuleKey), reason: 'the NPC list turns seats on and off in bulk');
    });

    test('should leave a plain right-click to riding when it turns a seat on', () {
      expect(seatSwitched(null, on: true, riding: true), <String, Object?>{'enabled': true, 'click': 'sneak-right'});
      expect(seatSwitched(<String, Object?>{'click': 'right'}, on: true, riding: true), <String, Object?>{'click': 'right', 'enabled': true},
          reason: 'a click someone chose stays');
    });
  });
}
