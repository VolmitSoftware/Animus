import 'package:animus_editor/logic/npc/default_skins.dart';
import 'package:animus_editor/mc/rigs/mc_rigs.dart';
import 'package:test/test.dart';

void main() {
  test('a player body without a skin wears the default Minecraft picks from its UUID', () {
    // Values from Math.floorMod(UUID.fromString(x).hashCode(), 18) on the JVM.
    expect(vanillaDefaultSkin('d10fcfc7-13ee-4ba7-9c47-35a48a8f812c').texture, 'entity/player/wide/ari');
    expect(vanillaDefaultSkin('069a79f4-44e9-4726-a5be-fca90e38aaf5').texture, 'entity/player/slim/alex');
    expect(vanillaDefaultSkin('00000000-0000-0000-0000-000000000000').texture, 'entity/player/slim/alex');
    expect(vanillaDefaultSkin('ffffffff-ffff-ffff-ffff-fffffffffffe').texture, 'entity/player/slim/ari');
    expect(vanillaDefaultSkin('853c80ef-3c37-49fd-aa49-938b674adae6').texture, 'entity/player/slim/ari');
    expect(vanillaDefaultSkin('8667ba71-b85a-4004-af54-457a9734eed7').texture, 'entity/player/slim/alex');
    final DefaultSkin ari = vanillaDefaultSkin('d10fcfc7-13ee-4ba7-9c47-35a48a8f812c');
    expect(ari.slim, isFalse);
    expect(ari.label, 'Ari');
    expect(ari.url, 'assets/mc/26.3/entity/player/wide/ari.png');
  });

  test('without a UUID the default is Steve', () {
    expect(vanillaDefaultSkin(null).texture, mcSteveTexture);
    expect(vanillaDefaultSkin('not a uuid').label, 'Steve');
  });

  test('the pack carries every default skin', () {
    for (final DefaultSkin skin in vanillaDefaultSkins) {
      expect(mcEntityTextureIds, contains(skin.texture));
    }
    expect(vanillaDefaultSkins, hasLength(18));
  });
}
