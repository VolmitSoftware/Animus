// The default skin Minecraft gives a player profile without one, from its
// UUID, as lib/logic/npc/default_skins.dart works it out:
// floorMod(UUID.hashCode(), 18) over the nine characters slim, then wide.
const CHARACTERS = ['alex', 'ari', 'efe', 'kai', 'makena', 'noor', 'steve', 'sunny', 'zuri'];

function defaultSkinOf(uuid) {
  const hex = uuid.replace(/-/g, '');
  let hash = 0;
  for (let word = 0; word < 4; word++) hash ^= parseInt(hex.slice(word * 8, word * 8 + 8), 16);
  const index = ((hash | 0) % 18 + 18) % 18;
  const character = CHARACTERS[index % 9];
  return {
    texture: `entity/player/${index < 9 ? 'slim' : 'wide'}/${character}`,
    slim: index < 9,
    label: character[0].toUpperCase() + character.slice(1),
  };
}

module.exports = {defaultSkinOf};
