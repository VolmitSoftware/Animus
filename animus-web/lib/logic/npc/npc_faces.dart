/// What an NPC's face in a list is drawn from, matching what the stage
/// draws: a player body wears its skin, or else the default skin its UUID
/// gives it ([vanillaDefaultSkin]); any other body shows its spawn egg, or
/// its own item (armor stands, item frames, paintings).
library;

import 'default_skins.dart';

sealed class NpcFaceSource {
  const NpcFaceSource();
}

/// A player body's face: [skin] (a skin document id) when it wears one and
/// it loads, else the PNG at [fallback].
final class SkinFaceSource extends NpcFaceSource {
  const SkinFaceSource(this.skin, this.fallback);

  final String? skin;
  final String fallback;
}

/// Another body's icon: the first of [items] (bare item keys) the pack
/// draws; none for a modded body.
final class BodyFaceSource extends NpcFaceSource {
  const BodyFaceSource(this.type, this.items);

  final String type;
  final List<String> items;
}

const Set<String> _playerBodies = <String>{'', 'minecraft:player', 'minecraft:mannequin'};

/// The face of NPC [npcId] with body [type] wearing [skin]; [uuid] picks a
/// player body's default skin.
NpcFaceSource npcFaceSource(String npcId, {String? type, String? skin, String? uuid}) {
  final String body = type ?? '';
  if (_playerBodies.contains(body)) return SkinFaceSource(skin, vanillaDefaultSkin(body == 'minecraft:mannequin' ? null : uuid).url);
  final int colon = body.indexOf(':');
  if (colon >= 0 && body.substring(0, colon) != 'minecraft') return BodyFaceSource(body, const <String>[]);
  final String key = body.substring(colon + 1);
  return BodyFaceSource(body, <String>['${key}_spawn_egg', key]);
}
