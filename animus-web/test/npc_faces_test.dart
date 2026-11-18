import 'package:animus_editor/logic/npc/npc_faces.dart';
import 'package:animus_editor/logic/npc/default_skins.dart';
import 'package:test/test.dart';

void main() {
  group('npcFaceSource', () {
    test('should give a player body without a skin the default skin players see', () {
      final NpcFaceSource face = npcFaceSource('examples/greeter', type: 'minecraft:player', uuid: 'd10fcfc7-13ee-4ba7-9c47-35a48a8f812c');
      expect(face, isA<SkinFaceSource>());
      final SkinFaceSource skin = face as SkinFaceSource;
      expect(skin.skin, isNull);
      expect(skin.fallback, 'assets/mc/26.3/entity/player/wide/ari.png');
    });

    test('should keep a worn skin and fall back to the same default', () {
      final SkinFaceSource skin = npcFaceSource('town/baker', type: 'minecraft:player', skin: 'abc123', uuid: '069a79f4-44e9-4726-a5be-fca90e38aaf5') as SkinFaceSource;
      expect(skin.skin, 'abc123');
      expect(skin.fallback, vanillaDefaultSkin('069a79f4-44e9-4726-a5be-fca90e38aaf5').url);
      expect((npcFaceSource('town/mannequin', type: 'minecraft:mannequin', uuid: '069a79f4-44e9-4726-a5be-fca90e38aaf5') as SkinFaceSource).fallback,
          vanillaDefaultSkin(null).url, reason: 'a mannequin is drawn as Steve');
    });

    test('should treat a missing body and a mannequin as players', () {
      expect(npcFaceSource('a'), isA<SkinFaceSource>());
      expect(npcFaceSource('a', type: ''), isA<SkinFaceSource>());
      expect(npcFaceSource('a', type: 'minecraft:mannequin'), isA<SkinFaceSource>());
    });

    test('should draw another body from its spawn egg, else its own item', () {
      final BodyFaceSource villager = npcFaceSource('a', type: 'minecraft:villager') as BodyFaceSource;
      expect(villager.items, <String>['villager_spawn_egg', 'villager']);
      expect((npcFaceSource('a', type: 'minecraft:armor_stand') as BodyFaceSource).items, <String>['armor_stand_spawn_egg', 'armor_stand']);
      expect((npcFaceSource('a', type: 'mymod:dragon') as BodyFaceSource).items, isEmpty, reason: 'a modded body has no item in the pack');
    });
  });
}
