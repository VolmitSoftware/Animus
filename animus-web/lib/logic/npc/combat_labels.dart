/// The words the Combat tab shows for every key of `animus:combat` and
/// `animus:health`: a label of one to three words and a one-line tip. The
/// engine key itself only appears inside the tip, as "Key: melee.reach".
library;

import '../gui/gui_json.dart';

/// A field's label and the sentence behind its (i).
class KeyWords {
  const KeyWords(this.label, this.tip);

  final String label;
  final String tip;
}

/// Combat keys by their place in the module: a root key (`victimDrops`),
/// `<section>.<key>` for a settings section (`melee.reach`), `rule.<key>`
/// for a target rule, `patterns.<key>`, `items.<key>`, `events.<key>` and
/// `effect.<key>` for the objects inside them.
const Map<String, KeyWords> combatKeyWords = <String, KeyWords>{
  'enabled': KeyWords('Fights', 'Combat runs only while this is on. Off keeps every setting.'),
  'targets': KeyWords('Targets', 'Who it attacks. Any matching rule makes a target.'),
  'ignores': KeyWords('Ignores', 'Who it never attacks, even when a target rule matches.'),
  'avoids': KeyWords('Avoids', 'Who it keeps away from, within the avoidance range.'),
  'targeting': KeyWords('Targeting', 'How it finds and keeps targets.'),
  'avoidance': KeyWords('Avoidance', 'How far it keeps from what it avoids, and where it runs.'),
  'squad': KeyWords('Squad', 'NPCs of one squad never target each other and call each other for help.'),
  'guard': KeyWords('Guard', 'A player or NPC it follows and protects.'),
  'pursuit': KeyWords('Pursuit', 'How it chases a target and when it gives up.'),
  'melee': KeyWords('Melee', 'Close attacks.'),
  'ranged': KeyWords('Ranged', 'Shots: the projectile, its damage, range and speed.'),
  'greetings': KeyWords('Greetings', 'Lines it says to players nearby: hostile to targets, friendly to everyone else.'),
  'targetableByMobs': KeyWords('Mobs attack it', 'Idle hostile mobs nearby may attack it.'),
  'victimDrops': KeyWords('Victims drop loot', 'What it kills drops loot.'),
  'preservedSettings': KeyWords('Imported settings', 'Settings kept from an import, as written.'),
  'historicalStatistics': KeyWords('Imported statistics', 'Statistics kept from an import, as written.'),
  'migrationReport': KeyWords('Import report', 'Notes from the import, as written.'),
  // Targeting.
  'targeting.scanRange': KeyWords('Scan range', 'Blocks it looks for targets in. Capped by the server config.'),
  'targeting.scanIntervalTicks': KeyWords('Scan every', 'How often it looks for targets.'),
  'targeting.reactionTicks': KeyWords('Reaction time', 'How long it waits before attacking a new target.'),
  'targeting.memoryTicks': KeyWords('Memory', 'How long it keeps chasing a target it cannot see.'),
  'targeting.retainTarget': KeyWords('Keep target', 'Stays on its target while a closer one appears.'),
  'targeting.fightBack': KeyWords('Fights back', 'Attacks whoever hurts it.'),
  'targeting.lineOfSight': KeyWords('Line of sight', 'Whether it must see a target. Field of view is a 140 degree cone.'),
  // Avoidance.
  'avoidance.range': KeyWords('Range', 'Blocks it keeps from what it avoids.'),
  'avoidance.fleeWhenAttacked': KeyWords('Flee when hit', 'Runs from whoever hurts it.'),
  'avoidance.returnPoint': KeyWords('Return point', 'Where it runs to. Click the grid or drag the point.'),
  // Squad.
  'squad.name': KeyWords('Squad name', 'NPCs with the same squad name are one squad.'),
  'squad.alertRange': KeyWords('Alert range', 'Blocks within which it calls its squad.'),
  // Guard.
  'guard.player': KeyWords('Guard player', 'The UUID of the player it guards. Set a player or an NPC, not both.'),
  'guard.npc': KeyWords('Guard NPC', 'The UUID of the NPC it guards. Set a player or an NPC, not both.'),
  'guard.followDistance': KeyWords('Follow distance', 'Blocks it stays behind its charge.'),
  'guard.pointRange': KeyWords('Point range', 'Blocks it may stray from its follow point.'),
  'guard.assist': KeyWords('Assist', 'Attacks whatever its charge attacks.'),
  'guard.catchUpDistance': KeyWords('Catch-up distance', 'Teleports near its charge beyond this. 0 never teleports.'),
  'guard.replanDistance': KeyWords('Replan distance', 'Blocks its charge moves before it plans a new path.'),
  'guard.protect': KeyWords('Protect', 'Attacks whatever hurts its charge. Off only follows.'),
  // Pursuit.
  'pursuit.closeChase': KeyWords('Chase in melee', 'Runs after a target to hit it.'),
  'pursuit.rangedChase': KeyWords('Chase when ranged', 'Runs after a target it shoots at.'),
  'pursuit.speed': KeyWords('Speed', 'Blocks per second while chasing.'),
  'pursuit.giveUpRange': KeyWords('Give-up range', 'Stops chasing a target this far away.'),
  'pursuit.leashRange': KeyWords('Leash range', 'Stops chasing this far from where the fight began.'),
  'pursuit.returnToPost': KeyWords('Return to post', 'Walks back to where it stood after a fight.'),
  'pursuit.teleportWhenStuck': KeyWords('Teleport when stuck', 'Teleports to its target when no path reaches it.'),
  'pursuit.replanTicks': KeyWords('Replan every', 'How often it plans a new path to its target.'),
  'pursuit.region': KeyWords('Region', 'A protection region the fight stays in. Targets outside it are skipped.'),
  // Melee.
  'melee.enabled': KeyWords('Melee attacks', 'Hits targets in reach.'),
  'melee.damage': KeyWords('Damage', 'Health taken per hit.'),
  'melee.reach': KeyWords('Reach', 'Blocks from the body center.'),
  'melee.cooldownTicks': KeyWords('Cooldown', 'Time between hits.'),
  'melee.knockback': KeyWords('Knockback', 'How far a hit pushes.'),
  'melee.fromEquipment': KeyWords('Use held weapon', 'Damage comes from the held item instead.'),
  'melee.weaponDamage': KeyWords('Weapon damage', 'Damage by held item. Overrides melee and ranged damage.'),
  // Ranged.
  'ranged.enabled': KeyWords('Ranged attacks', 'Shoots at targets in range.'),
  'ranged.weapon': KeyWords('Weapon', 'What it shoots.'),
  'ranged.damage': KeyWords('Damage', 'Health taken per shot.'),
  'ranged.range': KeyWords('Range', 'Blocks it shoots from.'),
  'ranged.minimumRange': KeyWords('Minimum range', 'It does not shoot targets closer than this.'),
  'ranged.cooldownTicks': KeyWords('Cooldown', 'Time between shots.'),
  'ranged.velocity': KeyWords('Shot speed', 'How fast a shot flies.'),
  'ranged.inaccuracy': KeyWords('Spread', 'How far shots stray. 0 is exact.'),
  'ranged.safeShot': KeyWords('Safe shots', 'Holds fire while a friend is in the way.'),
  'ranged.knockback': KeyWords('Knockback', 'How far a shot pushes.'),
  'ranged.effect': KeyWords('Potion effect', 'What a thrown potion applies.'),
  'ranged.fromEquipment': KeyWords('Use held weapon', 'Shoots with the held bow, crossbow or trident.'),
  'ranged.useAmmo': KeyWords('Uses ammo', 'Each shot takes one from its inventory.'),
  'effect.id': KeyWords('Effect', 'A status effect id. Helpful effects never count as an attack.'),
  'effect.amplifier': KeyWords('Strength', '0 is level I.'),
  'effect.durationTicks': KeyWords('Duration', 'How long the effect lasts.'),
  // Greetings.
  'greetings.friendly': KeyWords('Friendly line', 'Said to players it does not target.'),
  'greetings.hostile': KeyWords('Hostile line', 'Said to its targets.'),
  'greetings.range': KeyWords('Range', 'Blocks within which it greets.'),
  'greetings.cooldownTicks': KeyWords('Cooldown', 'Time before it greets the same player again.'),
  // Target rules.
  'rule.categories': KeyWords('Kinds', 'A mob counts as Mobs and as Monsters or Animals.'),
  'rule.entityTypes': KeyWords('Mob types', 'Entity ids, such as minecraft:zombie.'),
  'rule.players': KeyWords('Players', 'Player names (any case) or UUIDs.'),
  'rule.npcs': KeyWords('NPCs', 'NPC ids or names.'),
  'rule.names': KeyWords('Names', 'Custom names of mobs.'),
  'rule.squads': KeyWords('Squads', "Other NPCs' squad names."),
  'rule.owner': KeyWords('Its owner', 'The player who owns this NPC.'),
  'rule.patterns': KeyWords('Name patterns', 'Regular expressions over names.'),
  'rule.items': KeyWords('Held items', 'Item id patterns in the hands or worn.'),
  'rule.healthAbove': KeyWords('Health above', 'Percent of max health.'),
  'rule.healthBelow': KeyWords('Health below', 'Percent of max health.'),
  'rule.permissions': KeyWords('Permissions', 'Permission nodes an online player has.'),
  'rule.groups': KeyWords('Permission groups', 'Permission groups an online player is in.'),
  'rule.effects': KeyWords('Effects', 'Status effect ids it has.'),
  'rule.all': KeyWords('All of', 'Rule sets one entity must match together.'),
  'rule.together': KeyWords('Together', 'Rule sets matched by entities near each other.'),
  'rule.events': KeyWords('Fights nearby', 'Attackers in nearby fights become targets.'),
  'rule.chat': KeyWords('Chat words', 'Chat text that makes the speaker a target.'),
  'patterns.players': KeyWords('Player patterns', 'Patterns over player names.'),
  'patterns.npcs': KeyWords('NPC patterns', 'Patterns over NPC ids and names.'),
  'patterns.names': KeyWords('Mob name patterns', 'Patterns over custom mob names.'),
  'items.mainHand': KeyWords('Main hand', 'Item id patterns in the main hand.'),
  'items.offHand': KeyWords('Off hand', 'Item id patterns in the off hand.'),
  'items.worn': KeyWords('Worn', 'Item id patterns of worn armor.'),
  'events.kinds': KeyWords('Fight kinds', 'Which fights count.'),
  'events.playerAttacks': KeyWords('Player attackers', 'Rules a player attacker must match.'),
  'events.anyAttacks': KeyWords('Any attackers', 'Rules any attacker must match.'),
};

/// Health keys: a root key (`armor`), `death.<key>`, `respawn.<key>`,
/// `drop.<key>` for one drop and `location.<key>` for a place on the grid.
const Map<String, KeyWords> healthKeyWords = <String, KeyWords>{
  'killable': KeyWords('Can be hurt', 'Players, mobs and NPCs can hurt and kill it.'),
  'maximumHealth': KeyWords('Max health', 'Its health when full. 2 is one heart.'),
  'armor': KeyWords('Armor', 'Share of each hit it absorbs, 0 to 1.'),
  'armorFromEquipment': KeyWords('Worn armor', 'The armor it wears sets the share instead.'),
  'invulnerableTicks': KeyWords('Hit cooldown', 'Repeat hits are ignored this long.'),
  'knockback': KeyWords('Knockback', 'Hits push it back.'),
  'regenerate': KeyWords('Regenerates', 'Heals over time.'),
  'regenerateTicks': KeyWords('Heal every', 'Time per 1 health healed.'),
  'sources': KeyWords('Hurt by', 'Attacks hurt it by default; fire, lava and falls only when picked.'),
  'attackerPermission': KeyWords('Attacker permission', 'Players need this permission node to hurt it.'),
  'showHealth': KeyWords('Show health', "Shows its health on the attacker's action bar."),
  'protectFromRange': KeyWords('Hit range', 'Only attackers this close can hurt it. 0 is any distance.'),
  'protectedFromIgnored': KeyWords('Safe from ignored', 'Who combat ignores cannot hurt it.'),
  'death': KeyWords('Death', 'What it drops when it dies.'),
  'respawn': KeyWords('Respawn', 'Whether it comes back, when and where.'),
  'dead': KeyWords('Dead', 'Set when it died and stays dead. Revive clears it.'),
  'death.drops': KeyWords('Drops', 'Items it drops. Each rolls its own chance.'),
  'death.experience': KeyWords('Experience', 'Experience orbs it drops.'),
  'death.dropEquipment': KeyWords('Drop equipment', 'Its worn and held items drop too.'),
  'respawn.afterDeath': KeyWords('After death', 'Respawn brings it back. Stay dead waits for Revive. Delete NPC removes its file.'),
  'respawn.delayTicks': KeyWords('Delay', 'Time before it comes back.'),
  'respawn.points': KeyWords('Points', 'Where it comes back. None is its own position.'),
  'respawn.order': KeyWords('Order', 'How the next point is picked.'),
  'drop.item': KeyWords('Item', 'The item that drops.'),
  'drop.count': KeyWords('Count', 'The fewest that drop.'),
  'drop.maxCount': KeyWords('Up to', 'The most that drop.'),
  'drop.chance': KeyWords('Chance', 'From 0 to 1. 1 always drops.'),
  'drop.components': KeyWords('Components', 'Item components, such as [enchantment_glint_override=true].'),
  'location.world': KeyWords('World', 'The world the point is in.'),
  'location.x': KeyWords('X', 'East and west.'),
  'location.y': KeyWords('Y', 'Height.'),
  'location.z': KeyWords('Z', 'North and south.'),
};

const Map<String, String> combatCategoryLabels = <String, String>{
  'players': 'Players',
  'npcs': 'NPCs',
  'mobs': 'Mobs',
  'monsters': 'Monsters',
  'animals': 'Animals',
};

const Map<String, String> combatWeaponLabels = <String, String>{
  'arrow': 'Arrow',
  'spectral_arrow': 'Spectral arrow',
  'trident': 'Trident',
  'snowball': 'Snowball',
  'egg': 'Egg',
  'fireball': 'Fireball',
  'small_fireball': 'Small fireball',
  'wither_skull': 'Wither skull',
  'llama_spit': 'Llama spit',
  'potion': 'Potion',
};

const Map<String, String> combatLineOfSightLabels = <String, String>{
  'required': 'Required',
  'ignored': 'Not needed',
  'field_of_view': 'Field of view',
};

const Map<String, String> combatFightKindLabels = <String, String>{
  'player_hurts_player': 'Player hurts player',
  'player_hurts_mob': 'Player hurts mob',
  'mob_hurts_mob': 'Mob hurts mob',
  'player_hurts_npc': 'Player hurts NPC',
  'npc_hurts_npc': 'NPC hurts NPC',
};

const Map<String, String> healthSourceLabels = <String, String>{
  'players': 'Players',
  'mobs': 'Mobs',
  'npcs': 'NPCs',
  'projectiles': 'Projectiles',
  'fire': 'Fire',
  'lava': 'Lava',
  'fall': 'Falls',
};

const Map<String, String> healthAfterDeathLabels = <String, String>{
  'respawn': 'Respawn',
  'permadeath': 'Stay dead',
  'delete': 'Delete NPC',
};

const Map<String, String> healthOrderLabels = <String, String>{
  'in_order': 'In order',
  'random': 'Random',
  'nearest': 'Nearest',
};

/// The words for combat key [place] (see [combatKeyWords]); unknown keys read as words.
KeyWords combatWords(String place) => combatKeyWords[place] ?? KeyWords(humanizeKey(place.split('.').last), '');

/// The words for health key [place] (see [healthKeyWords]).
KeyWords healthWords(String place) => healthKeyWords[place] ?? KeyWords(humanizeKey(place.split('.').last), '');

String _trim(double value) {
  final String fixed = value.toStringAsFixed(2);
  return fixed.contains('.') ? fixed.replaceFirst(RegExp(r'\.?0+$'), '') : fixed;
}

/// [ticks] as time: `1 s`, `0.5 s`, `5 min`, `1 h`.
String ticksAsTime(num ticks) {
  final double seconds = ticks / 20;
  if (seconds < 60) return '${_trim(seconds)} s';
  if (seconds < 3600) return '${_trim(seconds / 60)} min';
  return '${_trim(seconds / 3600)} h';
}

/// The hint under a time field: `40 ticks, 2 s`, or the default's when unset.
String ticksHint(num? value, num fallback) {
  final num ticks = value ?? fallback;
  final String count = ticks == ticks.roundToDouble() ? '${ticks.toInt()}' : '$ticks';
  return '${value == null ? 'Default ' : ''}$count ticks, ${ticksAsTime(ticks)}';
}
