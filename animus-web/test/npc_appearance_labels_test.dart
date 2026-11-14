import 'package:animus_editor/logic/npc/npc_appearance.dart';
import 'package:test/test.dart';

void main() {
  group('bodyLabel', () {
    test('should read a vanilla body as words', () {
      expect(bodyLabel('minecraft:player'), 'Player');
      expect(bodyLabel('minecraft:iron_golem'), 'Iron golem');
      expect(bodyLabel('minecraft:glow_item_frame'), 'Glow item frame');
    });

    test('should name another namespace in brackets', () {
      expect(bodyLabel('mythic:king_slime'), 'King slime (mythic)');
    });

    test('should give nothing for an empty type', () {
      expect(bodyLabel(''), '');
      expect(bodyLabel('minecraft:'), '');
    });
  });
}
