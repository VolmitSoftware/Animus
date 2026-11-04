# Combat

An NPC fights when its definition enables `modules["animus:combat"]`. The NPC body stays virtual: it is never a world entity, it never becomes a fake attacker in Bukkit events, and only server-side rules decide what it does. Combat runs on Spigot and Paper 26.1.2, 26.2 and 26.3. On Folia the general NPC runtime is disabled, so `/animus combat status` reports combat as unavailable and nothing runs.

## Settings

Settings live in the NPC definition as JSON. Every section and field is optional; the defaults target nothing, so `{"enabled": true}` alone makes an NPC that can be hurt and fights back but never starts a fight. `enabled` must be the JSON value `true`. An imported NPC also needs `"reviewAccepted": true`. Unknown keys and out-of-range values are rejected with the exact field, such as `$.modules.animus:combat.melee.reach`, and the definition is refused like any other invalid edit.

```json
"modules": {
  "animus:combat": {
    "enabled": true,
    "targets": {"categories": ["monsters"], "entityTypes": ["minecraft:pillager"], "players": ["Griefer"], "npcs": ["bandit"], "squads": ["raiders"]},
    "ignores": {"categories": ["animals"]},
    "avoids": {"entityTypes": ["minecraft:creeper"]},
    "targeting": {"scanRange": 16, "scanIntervalTicks": 10, "reactionTicks": 0, "memoryTicks": 60, "retainTarget": false, "fightBack": true, "lineOfSight": "required"},
    "avoidance": {"range": 10, "fleeWhenAttacked": false, "returnPoint": {"world": "world", "x": 0, "y": 64, "z": 0}},
    "squad": {"name": "town", "alertRange": 16},
    "guard": {"player": "00000000-0000-0000-0000-000000000001", "followDistance": 7, "pointRange": 4},
    "pursuit": {"closeChase": true, "rangedChase": false, "speed": 4, "giveUpRange": 32, "leashRange": 64, "returnToPost": true, "teleportWhenStuck": false, "replanTicks": 20},
    "melee": {"enabled": true, "damage": 4, "reach": 3, "cooldownTicks": 20, "knockback": 0.4},
    "ranged": {"enabled": true, "weapon": "arrow", "damage": 3, "range": 24, "minimumRange": 4, "cooldownTicks": 40, "velocity": 1.6, "inaccuracy": 0, "safeShot": true, "knockback": 0.2},
    "defense": {"maximumHealth": 40, "armor": 0.2, "invincible": false, "knockback": true, "protectedFromIgnored": false, "protectFromRange": 0, "healRateTicks": 100, "invulnerableTicks": 10},
    "death": {"drops": [{"item": "minecraft:iron_ingot", "amount": 2, "chance": 0.5}], "experience": 5, "victimDrops": true},
    "respawn": {"delayTicks": 100, "locations": [{"world": "world", "x": 0, "y": 64, "z": 0}]}
  }
}
```

| Section | Meaning |
| --- | --- |
| `targets`, `ignores`, `avoids` | Rule lists. Categories are `players`, `npcs`, `mobs`, `monsters` and `animals`. `players` takes names or UUIDs, `npcs` takes NPC IDs or display names, `names` takes custom mob names, `squads` takes other NPCs' squads. Names ignore case. An ignore always beats a target. |
| `targeting` | `scanRange` 1–64 blocks. `lineOfSight` is `required`, `ignored` or `field_of_view` (a 140-degree cone in front of the NPC, plus line of sight). `memoryTicks` keeps a target that went behind a wall. `reactionTicks` delays the first attack on a new target. `retainTarget` keeps the current target when a closer one appears. `fightBack` targets whoever hurts the NPC. |
| `avoidance` | The NPC runs from anything in `avoids` within `range`, toward `returnPoint` when set. `fleeWhenAttacked` makes it run from attackers instead of fighting. |
| `squad` | Members never target each other or each other's guarded player. A member that picks a target alerts idle members within `alertRange`. |
| `guard` | Either `player` or `npc` (a UUID). The guard follows to a random point within `pointRange` once farther than `followDistance`, and attacks whatever hurts its charge. It never targets its charge. |
| `pursuit` | `speed` is 1–5 blocks per second. A target farther than `giveUpRange` is dropped. `leashRange` is measured from where the fight began; past it the NPC drops the target and, with `returnToPost`, walks back before its waypoint patrol resumes. `teleportWhenStuck` moves the NPC to its goal after three refused path requests. |
| `melee` | Reach is measured from the NPC's body center to the nearest point of the target's bounds, at most 6 blocks. |
| `ranged` | Real arrows only. `minimumRange` makes the NPC switch to melee or walk closer. `safeShot` holds fire when a friend is in the line and makes an arrow that hits anything other than its target do nothing. |
| `defense` | `maximumHealth` 1–2048 replaces the default of 20. A new activation starts at full health; a definition change keeps current health, lowering it only to fit a smaller maximum. `armor` 0–1 removes that fraction of each hit. `invulnerableTicks` ignores repeat hits for that long. `protectedFromIgnored` blocks damage from anything in `ignores`. `protectFromRange` ignores attackers farther than that. `healRateTicks` restores 1 health per interval; 0 never heals. |
| `death` | Items drop with independent chances. `victimDrops: false` stops mobs killed by this NPC from dropping loot or experience. |
| `respawn` | `delayTicks` −1 never respawns. Otherwise the NPC comes back at full health at a random listed location, or where it died. The spawn trigger fires again. |

Current health, targets and cooldowns are runtime state. They are never written into the definition and do not survive a restart or despawn.

## Who can hurt whom

| Attacker | Victim | How |
| --- | --- | --- |
| NPC | Player or mob | Bukkit damage with a damage location and no causing entity. `VirtualNpcDamageEvent` can cancel or change it first. |
| NPC | NPC | Authoritative virtual damage. |
| Player | NPC | A hit that passed the runtime's reach, line-of-sight and rate checks. The amount is the player's attack damage attribute scaled by attack charge, like vanilla. Enchantments and potions are not added. |
| Controlled zombie | NPC | The existing `/animus target` adapter, now subject to the NPC's defense. |
| Arrow from an NPC | Player or mob | The arrow's own damage is cancelled and the hit is applied once through the combat module. |
| Arrow from an NPC | NPC | Arrows pass through virtual bodies, so each flight segment is tested against nearby NPC bounds. |

An NPC without enabled combat cannot be hurt by any of these paths. Every hit, in every direction, asks protection first, then applies defense, then changes health exactly once. Damage and death of an NPC fire the `damage` and `death` behavior triggers.

### Arrows

Each arrow an NPC fires is a real entity with no shooter, tagged as a combat helper, not saved with the chunk and not collectable. A shot leaves the module's ledger the first time anything touches it: virtual collision, a real hit, a block hit, expiry (`helperLifetimeTicks`), its entity vanishing, or a chunk unload. Whichever comes first is the only one that can apply damage; later callbacks find nothing. Every arrow an NPC owns is removed when it disengages, dies, despawns or loses combat, and every arrow is removed when the plugin stops.

## Protection

Protection is a separate adapter that answers allow or deny for each hit. If it throws or gives no answer, the hit is denied and counted under `protection-failures` in `/animus combat status`.

With WorldGuard installed, Animus reads its region flags. NPCs have no WorldGuard identity and are queried as non-members; players are queried as themselves.

| Hit | Flag that must not be denied |
| --- | --- |
| Anything hurting a player or an NPC | `pvp`, where both sides stand. A player in an `invincible` region is never hurt. |
| An NPC hurting an animal | `damage-animals` where the animal stands |
| A mob hurting or being controlled against an NPC | `mob-damage` where the NPC stands |

Hits on monsters are not limited by WorldGuard. If WorldGuard is installed but its API cannot be used, every hit is denied. Region membership, region priorities and other plugins' protection are not consulted except through these flags.

## Behaviors and commands

The built-in [`animus:combat-control`](behaviors.md#actions) action drives combat at runtime:

| `operation` | `target` | Effect |
| --- | --- | --- |
| `enable` | — | Clears a disengage, so rules choose targets again. |
| `disable` | — | Drops the target, removes the NPC's arrows and stops rule targeting until `enable`. Damage still reaches the NPC. |
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
maximumNpcs = 64
brainsPerTick = 16
targetScansPerTick = 4
scanCandidates = 32
scanCells = 32
squadAlertsPerTick = 8
pathRequestsPerTick = 2
maximumHelpers = 64
helpersPerNpc = 4
helperUpdatesPerTick = 32
helperLifetimeTicks = 100
idleSleepTicks = 40
```

Each tick wakes at most `brainsPerTick` NPCs and runs at most `targetScansPerTick` target scans, each looking at no more than `scanCandidates` entities and `scanCells` chunks of the NPC index. An NPC that finds nothing doubles its wait between scans up to `idleSleepTicks`; damage, an alert or an operation wakes it at once. Scans read loaded chunks only and never load terrain. Discovering newly enabled NPCs reads at most 64 definitions per tick after a definition change.

## Limits

- Movement uses the NPC navigator: walking only, on full blocks, at most 30 blocks per path request. A path that fails after it was accepted is not reported back, so such a target is dropped only by give-up range, leash or line-of-sight memory.
- Knockback and stuck teleports move an NPC only onto loaded, clear, supported ground.
- A patrol resumes from its first waypoint after a fight.
- Only arrows are supported as ranged weapons. Equipment does not change damage or armor, and needs no ammunition.
- Player damage against NPCs ignores enchantments, potions and critical hits.
- Real mobs do not fight NPCs on their own; only the ordinary-zombie adapter makes a mob attack one.
- NPCs do not greet or warn players.

Settings imported from Sentinel are described in [compatibility.md](compatibility.md#sentinel-settings).
