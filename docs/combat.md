# Combat

An NPC fights when its definition enables `modules["animus:combat"]`. The NPC body stays virtual: it is never a world entity, it never becomes a fake attacker in Bukkit events, and only server-side rules decide what it does. Combat runs on Spigot and Paper 26.1.2, 26.2 and 26.3, and on Fabric, NeoForge and Forge servers, where no protection adapter is installed and mob control is unavailable; see [mod servers](mod-servers.md). On Folia each combat NPC runs on its region owner; see [combat on Folia](folia-runtime.md#combat) for what differs there.

## Settings

Settings live in the NPC definition as JSON. Every section and field is optional; the defaults target nothing, so `{"enabled": true}` alone makes an NPC that can be hurt and fights back but never starts a fight. `enabled` must be the JSON value `true`. An imported NPC also needs `"reviewAccepted": true`. Unknown keys and out-of-range values are rejected with the exact field, such as `$.modules.animus:combat.melee.reach`, and the definition is refused like any other invalid edit.

```json
"modules": {
  "animus:combat": {
    "enabled": true,
    "targets": {"categories": ["monsters"], "entityTypes": ["minecraft:pillager"], "players": ["Griefer"], "npcs": ["bandit"], "squads": ["raiders"]},
    "ignores": {"categories": ["animals"], "owner": true},
    "avoids": {"entityTypes": ["minecraft:creeper"]},
    "targeting": {"scanRange": 16, "scanIntervalTicks": 10, "reactionTicks": 0, "memoryTicks": 60, "retainTarget": false, "fightBack": true, "lineOfSight": "required"},
    "avoidance": {"range": 10, "fleeWhenAttacked": false, "returnPoint": {"world": "world", "x": 0, "y": 64, "z": 0}},
    "squad": {"name": "town", "alertRange": 16},
    "guard": {"player": "00000000-0000-0000-0000-000000000001", "followDistance": 7, "pointRange": 4, "assist": false, "catchUpDistance": 24, "replanDistance": 1.5},
    "pursuit": {"closeChase": true, "rangedChase": false, "speed": 4, "giveUpRange": 32, "leashRange": 64, "returnToPost": true, "teleportWhenStuck": false, "replanTicks": 20, "region": "castle"},
    "melee": {"enabled": true, "damage": 4, "reach": 3, "cooldownTicks": 20, "knockback": 0.4, "fromEquipment": false, "weaponDamage": {"minecraft:stick": 6}},
    "ranged": {"enabled": true, "weapon": "arrow", "damage": 3, "range": 24, "minimumRange": 4, "cooldownTicks": 40, "velocity": 1.6, "inaccuracy": 0, "safeShot": true, "knockback": 0.2, "fromEquipment": false, "useAmmo": false},
    "defense": {"maximumHealth": 40, "armor": 0.2, "armorFromEquipment": false, "invincible": false, "knockback": true, "protectedFromIgnored": false, "protectFromRange": 0, "healRateTicks": 100, "invulnerableTicks": 10},
    "death": {"drops": [{"item": "minecraft:iron_ingot", "amount": 2, "chance": 0.5}], "experience": 5, "victimDrops": true},
    "respawn": {"delayTicks": 100, "locations": [{"world": "world", "x": 0, "y": 64, "z": 0}], "deleteOnDeath": false},
    "greetings": {"friendly": "Welcome to town.", "hostile": "Leave now.", "range": 10, "cooldownTicks": 100}
  }
}
```

| Section | Meaning |
| --- | --- |
| `targets`, `ignores`, `avoids` | Rule lists; see [Target rules](#target-rules). An ignore always beats a target. |
| `targeting` | `scanRange` 1–256 blocks, limited further by `maximumScanRange` in `config.toml`. `lineOfSight` is `required`, `ignored` or `field_of_view` (a 140-degree cone in front of the NPC, plus line of sight). `memoryTicks` keeps a target that went behind a wall. `reactionTicks` delays the first attack on a new target. `retainTarget` keeps the current target when a closer one appears. `fightBack` targets whoever hurts the NPC. |
| `avoidance` | The NPC runs from anything in `avoids` within `range`, toward `returnPoint` when set. `fleeWhenAttacked` makes it run from attackers instead of fighting. |
| `squad` | Members never target each other or each other's guarded player. A member that picks a target alerts idle members within `alertRange`. |
| `guard` | Either `player` or `npc` (a UUID). Once farther than `followDistance` (1-64, default 7) the guard walks to a point `pointRange` (0-16, default 4) behind its charge, against the way the charge last moved, and walks faster when more than twice `followDistance` behind. It replans when the charge moved more than `replanDistance` (0.25-16, default 1.5) since the last plan or the plan is `replanTicks` old, at most every 5 ticks. Beyond `catchUpDistance` (0-256, default 24; 0 turns it off), in another world or after a teleport, it stops fighting and teleports to clear ground beside its charge. It attacks whatever hurts its charge and never targets it. With `assist`, it also attacks whatever its charge attacks. |
| `pursuit` | `speed` is 1–10 blocks per second, limited further by `maximumSpeed` in `config.toml`. A target farther than `giveUpRange` (at most 256) is dropped. `leashRange` is measured from where the fight began; past it the NPC drops the target and, with `returnToPost`, walks back before its waypoint patrol resumes. `teleportWhenStuck` moves the NPC to its goal after three refused path requests. `region` names a protection region: targets outside it are not chosen, and a target that leaves it is dropped (checked every 10 ticks). |
| `melee` | Reach is measured from the NPC's body center to the nearest point of the target's bounds, at most 6 blocks. `fromEquipment` takes damage from the definition's `main_hand` item (see [Equipment](#equipment)). `weaponDamage` maps a held item to its damage and replaces any other damage, for melee and ranged, while that item is held. |
| `ranged` | `weapon` is `arrow`, `spectral_arrow`, `trident`, `snowball`, `egg`, `fireball`, `small_fireball`, `wither_skull`, `llama_spit` or `potion`. A potion needs `effect`; see [Weapons](#weapons). `minimumRange` makes the NPC switch to melee or walk closer. `safeShot` holds fire when a friend is in the line and makes a shot that hits anything other than its target do nothing. `fromEquipment` and `useAmmo` are described below. |
| `defense` | `maximumHealth` 1–2048 replaces the default of 20. A new activation starts at full health; a definition change keeps current health, lowering it only to fit a smaller maximum. `armor` 0–1 removes that fraction of each hit; `armorFromEquipment` takes it from worn armor instead. `invulnerableTicks` ignores repeat hits for that long. `protectedFromIgnored` blocks damage from anything in `ignores`. `protectFromRange` ignores attackers farther than that. `healRateTicks` restores 1 health per interval; 0 never heals. |
| `death` | Items drop with independent chances. `victimDrops: false` stops mobs killed by this NPC from dropping loot or experience. |
| `respawn` | `delayTicks` −1 never respawns. Otherwise the NPC comes back at full health at a random listed location, or where it died. The spawn trigger fires again. With `deleteOnDeath` (which needs `delayTicks` −1) the definition is deleted when the NPC dies; see [Deletion on death](#deletion-on-death). |
| `greetings` | When a player the NPC can see comes within `range` (1–64), it says `hostile` to a player it would target and `friendly` to anyone else, as `[NPC name]: text` in that player's chat. A player is spoken to again only after leaving range and coming back, and at most once per `cooldownTicks`. Either line may be left out. |

Current health, targets, cooldowns, ammunition and greeting memory are runtime state. They are never written into the definition and do not survive a restart or despawn.

## Target rules

A rule list matches a candidate when any one rule matches it.

| Rule | Matches |
| --- | --- |
| `categories` | `players`, `npcs`, `mobs`, `monsters` and `animals` |
| `entityTypes` | Namespaced entity types such as `minecraft:zombie` |
| `players`, `npcs`, `names`, `squads` | Player names or UUIDs, NPC IDs or display names, custom mob names, other NPCs' squads. Names ignore case. |
| `owner` | `true` matches the player in the definition's `owner` field |
| `patterns` | `players`, `npcs` and `names` lists of regular expressions, matched anywhere in the name and ignoring case. A mob with no custom name is matched by its type, such as `zombie`. |
| `items` | `mainHand`, `offHand` and `worn` lists of patterns matched against item ids such as `minecraft:diamond_sword`. An empty hand holds `minecraft:air`. |
| `healthAbove`, `healthBelow` | Percentage of the target's maximum health |
| `permissions` | Permission nodes an online player has |
| `groups` | Permission groups an online player is in, read as the permission `group.<name>` that permission plugins grant to members |
| `effects` | Status effect ids such as `minecraft:strength` |
| `all` | Rule sets that one candidate must match completely: every rule listed in the set, such as a player holding a sword |
| `together` | Groups of 2–8 rule sets. A group matches when different candidates in range match each set at the same scan; then every member is a target. |
| `events` | `kinds` of fights nearby: `player_hurts_player`, `player_hurts_mob`, `mob_hurts_mob`, `player_hurts_npc` and `npc_hurts_npc`. `playerAttacks` (a player hurt something matching that rule set) and `anyAttacks` (a player or mob hurt something matching it). The one who started the fight becomes a target, or a threat in `avoids`. |
| `chat` | Text that, found in a nearby player's chat message (ignoring case), makes that player a target |

Operators hold every permission node that no plugin defines, on Bukkit and on mod servers, so they match `permissions` and `groups` rules unless a permission plugin says otherwise. A `together` group is filled from the nearest candidates first.

`together`, `events` and `chat` are allowed in `targets` and `avoids`; `ignores` refuses them. Sets inside `all`, `together` and `events` hold only the plain rules above. A fight or chat message counts only when its source is within `scanRange` (or `avoidance.range` for avoids), visible under the NPC's `lineOfSight` rule and not ignored; an NPC already fighting keeps its target.

Patterns are compiled once when the definition is read. A pattern is at most 128 characters and an invalid one is refused with its field, such as `$.modules.animus:combat.targets.patterns.players[1]: invalid pattern`. Matching reads at most 256 characters of a name and stops after a fixed number of steps, so no pattern can stall the server.

## Weapons

Every ranged weapon is a real projectile with no shooter, tagged as a combat helper, not saved with the chunk and not collectable. A shot leaves the module's ledger the first time anything touches it: virtual collision, a real hit, a block hit, expiry (`helperLifetimeTicks`), its entity vanishing, or a chunk unload. Whichever comes first is the only one that can apply damage; later callbacks find nothing. Every projectile an NPC owns is removed when it disengages, dies, despawns or loses combat, and every projectile is removed when the plugin stops.

The projectile's own damage, explosion, splash, fire and hatching never happen. Fireballs and skulls never break blocks. Arrows, tridents, snowballs, eggs and potions arc with gravity, so the shot is aimed higher with distance; fireballs, skulls and spit fly straight.

A `potion` applies its effect once to whatever it hits:

```json
"ranged": {"enabled": true, "weapon": "potion", "effect": {"id": "minecraft:instant_health", "amplifier": 1, "durationTicks": 1}, "range": 12, "cooldownTicks": 40}
```

A helpful effect (instant health, regeneration, speed, strength, resistance, fire resistance, absorption, health boost, saturation, night vision, water breathing, invisibility, jump boost, haste, luck, slow falling, conduit power, dolphin's grace, hero of the village) is not an attack: it needs no protection check, it may land on the NPC's charge or squad, and an NPC with such a weapon never uses melee. Any other effect is an attack and passes protection like a hit. On another combat NPC, instant health heals 4 times 2 to the amplifier and instant damage deals 6 times 2 to the amplifier through its defense; other effects do nothing to NPCs. A healer is a potion NPC whose targets select the players it should help, for example `"targets": {"all": [{"categories": ["players"], "healthBelow": 50}]}`.

With `useAmmo`, each shot uses one matching item counted from the definition's stored inventory: arrows (plain, tipped or spectral) for `arrow`, the item itself for `spectral_arrow`, `trident`, `snowball` and `egg`, fire charges for fireballs, wither skeleton skulls for `wither_skull`, and splash or lingering potions for `potion`. `llama_spit` needs none. The count is runtime state: the stored inventory is never changed, the count starts again when the definition changes or the server restarts, and an NPC with none left stops shooting and uses melee or closes in. `/animus combat <npc-id> status` shows `ammunition=<count>`.

## Equipment

`melee.fromEquipment` reads the `main_hand` item of the definition's `equipment`:

| Held item | Damage |
| --- | --- |
| Netherite, diamond, iron, copper and stone, golden or wooden sword | 8, 7, 6, 5 and 4 |
| Axes | 10 netherite, 9 diamond, iron, copper and stone, 7 golden and wooden |
| Trident | 8 |
| Mace | 6 |
| Bow or crossbow | 1 |
| Anything else or nothing | 1 |

`ranged.fromEquipment` gives 6 for a bow or crossbow and otherwise the same table. `defense.armorFromEquipment` adds the worn `head`, `chest`, `legs` and `feet` pieces: a full diamond or netherite set is 0.8, iron 0.6, chainmail 0.48, golden 0.44, copper 0.4 and leather 0.28; the total is at most 0.8. Enchantments and item components are not read.

## Who can hurt whom

| Attacker | Victim | How |
| --- | --- | --- |
| NPC | Player or mob | Bukkit damage with a damage location and no causing entity. `VirtualNpcDamageEvent` can cancel or change it first. |
| NPC | NPC | Authoritative virtual damage. |
| Player | NPC | A hit that passed the runtime's reach, line-of-sight and rate checks. The amount is the player's attack damage attribute scaled by attack charge, like vanilla. Enchantments and potions are not added. |
| Controlled mob | NPC | The `/animus target` adapter, or a mob drawn by `targetableByMobs`, subject to the NPC's defense. Zombies, husks, drowned, zombie villagers and vindicators. |
| Projectile from an NPC | Player or mob | The projectile's own effect is cancelled and the hit is applied once through the combat module. |
| Projectile from an NPC | NPC | Projectiles pass through virtual bodies, so each flight segment is tested against nearby NPC bounds. |

An NPC without enabled combat cannot be hurt by any of these paths. Every hit, in every direction, asks protection first, then applies defense, then changes health exactly once. Damage and death of an NPC fire the `damage` and `death` behavior triggers.

## Deletion on death

An NPC with `respawn.deleteOnDeath` processes its death normally (drops, experience, the `death` trigger) and then its definition is deleted on the file lane as one ordinary transaction, the same as `/animus delete`. The server log names the transaction:

```text
Combat NPC guard died and was deleted (respawn.deleteOnDeath); undo with /animus rollback <transaction>
```

`/animus rollback <transaction>` restores the definition exactly. If the definition was already deleted or replaced, nothing happens. `/animus combat status` counts deletions as `deleted-on-death`.

## Protection

Protection is a separate adapter that answers allow or deny for each hit. If it throws or gives no answer, the hit is denied and counted under `protection-failures` in `/animus combat status`.

With WorldGuard installed, Animus reads its region flags. NPCs have no WorldGuard identity and are queried as non-members; players are queried as themselves.

| Hit | Flag that must not be denied |
| --- | --- |
| Anything hurting a player or an NPC | `pvp`, where both sides stand. A player in an `invincible` region is never hurt. |
| An NPC hurting an animal | `damage-animals` where the animal stands |
| A mob hurting or being controlled against an NPC | `mob-damage` where the NPC stands |

Hits on monsters are not limited by WorldGuard. If WorldGuard is installed but disabled (at startup or later) or its API cannot be used, every hit is denied. Region membership, region priorities and other plugins' protection are not consulted except through these flags.

`pursuit.region` is a WorldGuard region in the NPC's world. Where no protection plugin provides regions (servers without WorldGuard, and every mod server), or the region does not exist, the region leash cannot be checked: the NPC fights within its normal ranges, the server log says so once per NPC, and `/animus combat status` counts it as `region-unavailable`.

## Behaviors and commands

The built-in [`animus:combat-control`](behaviors.md#actions) action drives combat at runtime:

| `operation` | `target` | Effect |
| --- | --- | --- |
| `enable` | — | Clears a disengage, so rules choose targets again. |
| `disable` | — | Drops the target, removes the NPC's projectiles and stops rule targeting until `enable`. Damage still reaches the NPC. |
| `target` | Required | Attacks this player, mob or combat NPC regardless of rules, within `giveUpRange` and `leashRange`. |
| `release` | — | Drops the current target; rules keep running. |
| `guard` | Optional | Guards this player or NPC; without `target`, stops guarding. Replaces the definition's guard until despawn. |

`target` is a UUID, or `player` for the player who started the run, so a hire dialogue can guard whoever answered it. The action fails with a reason when the NPC has no enabled combat, is dead, or the target is not available in its world. Use the `damage` and `death` triggers to react to fights.

Administrators have the same operations:

```text
/animus combat status
/animus combat <npc-id> status
/animus combat <npc-id> engage|disengage|release
/animus combat <npc-id> target <uuid>
/animus combat <npc-id> guard <uuid>|stop
```

`/animus attack` and `/animus target` remain the explicit manual adapter described in [runtime.md](runtime.md#initial-real-combat-adapter).

## Work limits

Plugin settings are TOML and need a restart:

```toml
[combat]
enabled = true
maximumNpcs = 256
brainsPerTick = 48
targetScansPerTick = 8
scanCandidates = 32
scanCells = 32
squadAlertsPerTick = 8
pathRequestsPerTick = 2
maximumHelpers = 64
helpersPerNpc = 4
helperUpdatesPerTick = 32
helperLifetimeTicks = 100
idleSleepTicks = 40
eventsPerTick = 8
maximumScanRange = 100
maximumSpeed = 8
```

Every NPC with accepted combat is a combatant, however many there are (up to 10,000). `maximumNpcs` (1–4096) is how many of them fight or scan at once. A guard with nothing to fight and no player within its scan range plus 16 blocks sleeps: it does not scan and holds no slot, and it checks again every `idleSleepTicks`. When every slot is taken, guards that want one wait in line and get the next free slot in the order they asked; a guard that only scans gives its slot to the next one in line after each scan, while a guard that fights keeps it until the fight ends. The console says once when guards start waiting and once when a slot is free again, and `/animus combat status` shows `fighting-or-scanning=<used>/<maximumNpcs>`, `waiting`, `sleeping` and `peak-waiting`. `/animus import citizens review` and `accept` say when more guards are accepted than may fight at once.

Each tick wakes at most `brainsPerTick` NPCs and runs at most `targetScansPerTick` target scans, each looking at no more than `scanCandidates` entities and `scanCells` chunks of the NPC index. An NPC that finds nothing doubles its wait between scans up to `idleSleepTicks`; damage, an alert or an operation wakes it at once. Scans read loaded chunks only and never load terrain. Discovering newly enabled NPCs reads at most 64 definitions per tick after a definition change.

Fights and chat messages wait in a queue of 64 and at most `eventsPerTick` (1–64) are examined per tick, each with at most 8 line-of-sight checks; a message that finds the queue full is dropped and counted as `dropped-events`. `maximumScanRange` (16–256) caps every NPC's `scanRange`, and `maximumSpeed` (1–10 blocks per second) caps every `pursuit.speed`.

## Limits

- Movement uses the NPC navigator: walking only, on full blocks, at most 30 blocks per path request. A path that fails after it was accepted is not reported back, so such a target is dropped only by give-up range, leash or line-of-sight memory.
- Knockback and stuck teleports move an NPC only onto loaded, clear, supported ground.
- A patrol resumes from its first waypoint after a fight.
- Lightning, ender pearls, shulker bullets, evoker fangs and gun plugins are not weapons. Item display names, lore and whole-inventory contents are not target rules.
- Greetings and target rules see only what one scan saw: at most `scanCandidates` entities, and idle NPCs scan less often.
- Player damage against NPCs ignores enchantments, potions and critical hits.
- Real mobs fight an NPC only through mob control: the `/animus target` adapter, or `targetableByMobs`. With `"targetableByMobs": true` in the module, every second the NPC draws up to 3 zombies, husks, drowned, zombie villagers or vindicators within 16 blocks that have no target of their own and can see it. Each walks to the NPC and attacks with its own attack damage every second for up to 10 seconds, then gets its own goals, path and target back. Brain-driven and ranged mobs (piglins, skeletons) are not drawn. Paper and Spigot only: on Folia and mod servers real mobs cannot be driven, and the setting does nothing. Citizens' `targetable: true` converts to it.

Settings imported from Sentinel are described in [compatibility.md](compatibility.md#sentinel-settings).
