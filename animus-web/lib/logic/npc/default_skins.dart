/// The skin a player body shows when it wears none. Minecraft draws one of
/// its nine default characters, in slim or wide arms, picked from the
/// profile's UUID, which is the NPC's `uuid` (`DefaultPlayerSkin.get` in the
/// 26.3 client). The preview and the NPC lists draw the same one.
library;

import '../../mc/assets/mc_pack_manifest.dart';
import '../../mc/npc/npc_scene.dart';

/// One of the client's default skins.
final class DefaultSkin {
  const DefaultSkin(this.character, {required this.slim});

  /// The character's file name: `alex`, `steve`, `zuri`.
  final String character;
  final bool slim;

  /// The pack texture, bound by the stage as is.
  String get texture => 'entity/player/${slim ? 'slim' : 'wide'}/$character';

  /// The PNG's address relative to the site.
  String get url => '${mcPackBaseUrl(mcPackVersion)}/$texture.png';

  /// The character's name as players know it: "Alex", "Makena".
  String get label => '${character[0].toUpperCase()}${character.substring(1)}';
}

const List<String> _characters = <String>['alex', 'ari', 'efe', 'kai', 'makena', 'noor', 'steve', 'sunny', 'zuri'];

/// Every default skin in the client's order: the nine slim, then the nine wide.
final List<DefaultSkin> vanillaDefaultSkins = <DefaultSkin>[
  for (final String character in _characters) DefaultSkin(character, slim: true),
  for (final String character in _characters) DefaultSkin(character, slim: false),
];

final RegExp _uuid = RegExp(r'^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$');

/// The default skin a profile with [uuid] shows: `floorMod(uuid.hashCode(),
/// 18)`, Java's UUID hash being the four 32-bit words XORed. Wide Steve when
/// the NPC has no UUID yet.
DefaultSkin vanillaDefaultSkin(String? uuid) {
  if (uuid == null || !_uuid.hasMatch(uuid)) return const DefaultSkin('steve', slim: false);
  final String hex = uuid.replaceAll('-', '');
  int hash = 0;
  for (int word = 0; word < 4; word++) {
    hash ^= int.parse(hex.substring(word * 8, word * 8 + 8), radix: 16);
  }
  // As a signed 32-bit int, the same on the VM and in JavaScript.
  hash &= 0xffffffff;
  if (hash >= 0x80000000) hash -= 0x100000000;
  return vanillaDefaultSkins[hash % vanillaDefaultSkins.length];
}

/// The stage's skin for a player body that wears none: [uuid]'s default.
NpcSkinChoice npcDefaultSkinChoice(Object? uuid) {
  final DefaultSkin skin = vanillaDefaultSkin(uuid is String ? uuid : null);
  return NpcSkinChoice(textureId: skin.texture, slim: skin.slim);
}
