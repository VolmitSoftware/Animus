# Import compatibility and runtime capabilities

This is a development inventory, not a release-parity claim. To run a migration, see [migrating from Citizens](migrating-from-citizens.md). Counts were produced by the Java converter against the private 495-record reference on 2026-11-04. The private input and generated records are not repository fixtures.

| Capability | Data conversion | Runtime status |
|---|---|---|
| UUID, readable ID, Citizens numeric ID | Converted; explicit ID map | Definition identity supported |
| Name, type, main world/location | Converted; optional world mapping. Converter version 7 and later put each definition in `npcs/<world>-r<x>_<z>/citizens-<id>.json`, named after its world after mapping and the region file it stands in | Names up to 256 characters. Short plain player names use the player nameplate; longer or coloured player names and every non-player name show in a packet-only text display. See [appearance](runtime.md#appearance-text-and-inventory) |
| Skins (489 traits, 409 unique complete documents) | Texture/signature/settings preserved and deduplicated. Converter version 9 and later: a skin on a body other than `minecraft:player` is kept in `citizens:review` and counted as kept for review, because only player bodies show skins | Signed textures shown by the persisted runtime; all 409 skins showed correctly in a live migration of the reference save on Paper 26.3 |
| Equipment (123) | All eight slots convert to `equipment`; a cosmetic item replaces its slot. Converter version 10 and later read Citizens' item meta and write item components: name, lore, enchantments, unbreakable, damage, repair cost, custom model data, item model, dye colour, shield and banner patterns, potion, map id and persistent data (custom items such as ItemsAdder's use the model and data components). Meta keys with no component (item flags, the server's `internal` data) stay in the item's `imported` field and are reported per item. A HorseModifiers saddle or armour fills an empty `saddle` or `body` slot | Shown to every viewer from the definition, across restart; the server parses the components, so enchantment glint, patterns, colours and custom models show. Equipment does not change combat damage or armor |
| Inventory (121) | Slots 0–71 convert to `inventory` slot for slot, with the same item rules. Version 10 also reads the list form Citizens writes | Stored for behaviors and modules to read; never rendered. Shops and chest GUIs are separate documents ([shops](shops.md), [chest GUIs](gui.md)) and never read the NPC's inventory |
| Waypoints (318) | Converter version 8 and later: `linear`, `guided` and `wander` become a disabled `animus:navigation` route with world mappings applied; `delay` triggers become point waits. In the reference, 298 NPCs get a route (288 guided, 9 linear, 1 wander) with 3,963 points and 26,059 guides; the other 20 have no points. Other providers and triggers are kept and reported | `/animus import citizens accept patrols` turns routes on. Guided routes walk to random destinations through guides; legs are limited to 32 blocks across and 8 up or down, and the report counts destinations no guide reaches (37 in 29 NPCs of the reference). See [migrating](migrating-from-citizens.md#patrols) |
| Look-close (495) | Converter version 8 and later: the trait becomes `animus:look-close` with range, line of sight and look-while-walking. The reference writes only `enabled: false`, so the 493 others are on. Version 10 also converts NPC targets (`npcs`) and random looking (`randomLook` with its delay and yaw and pitch ranges, off unless Citizens had it on), and writes a disabled module for a disabled trait | Active once the NPC is spawned; no review of its own. With `npcs` it looks at the nearest other NPC when no player is in range; with `randomLook` on it turns to a random facing every delay. Head-only, linked-body, per-player and filters are reported and not run |
| Holograms (91) | Lines convert to `hologram.lines`, top line first: Citizens lists them bottom-up, and version 10 reverses them (earlier versions kept Citizens' order, which showed them upside down). At most 16 lines of 256 characters. Version 10 also converts line margins, line height, view range, billboard, shadow, see-through and interpolation | One packet-only text display for every viewer with the hologram's billboard, shadow, see-through, view range and interpolation. With a line height or margins each line gets its own display, stacked as Citizens stacks them. Placeholders are not filled in |
| Poses (3), sneak (1), sneaking (1), sit (1), armor stand (3) | The default named pose sets the facing. Version 11 converts every named pose (`name;yaw;pitch`) to the definition's `poses`, named in lowercase with other characters as `-`; an unreadable entry or a repeated name is kept with its reason. `sneak` and the `sneaking` extension trait become `sneaking`, `sittrait` becomes `sitting`; armor stand parts and angles convert | Sneaking, sleeping, swimming and sitting on player bodies; other body types stand. Armor stand size, arms, base plate, visibility and limb angles are shown. Marker and gravity are kept for review. The behavior action `animus:pose` turns the NPC to a named pose on Paper, Spigot, Folia and mod servers |
| NPC metadata and state (version 10) | `glowing`, `using-held-item`, `removefromplayerlist`, scoreboard colour and tags, `gravity`, negative `age`, cached skin owner and `protected`/`collidable`/`minecraft-ai` convert to `glowing`, `usingItem`, `listed`, `teamColor`, `tags`, `gravity: false`, `baby: true` and `migration.skin`. Citizens' own `CITIZENS_NPC` tag is dropped. Rotation's `immediate` matches Animus. `spawned` is the definition's dormant state. Version 11: `targetable: true` sets `targetableByMobs` on the NPC's `animus:combat` module, adding a module that never attacks or fights back (invincible unless Citizens' `protected` was false) when the NPC has no Sentinel. `minecraft-ai: true` on a mob body with no route becomes a disabled wander route around its spawn point (10 blocks across, 7 up or down, a pick about every 6 seconds); on a player body it does nothing, as in Citizens | Glow, team colour on the glow and name, baby bodies, a raised held item and the tab-list entry show to every viewer on Paper, Spigot, Folia and mod servers. An NPC with `gravity: false` hovers. Once its combat review is accepted, a targetable NPC draws nearby zombies, husks, drowned, zombie villagers and vindicators that have no target of their own and can see it (up to 3 at a time, for 10 seconds each) on Paper and Spigot; on Folia and mod servers real mobs cannot be driven, so `targetableByMobs` does nothing there. The vanilla-AI wander walks like any route once its review is accepted; vanilla attacking and fleeing are not reproduced |
| Navigator (495) | Version 10: speed, `avoidwater`, fall distance, pathfinding range, distance margin, stationary ticks and the default stuck action (with `disable-default-stuck-action`), `flyable` and the `step_height` attribute convert to the definition's `movement`. Version 11 converts `pathfinder-open-doors` (17) to `movement.openDoors` | Routes walk at the NPC's speed when the route sets none, cross water at the surface unless `avoidWater`, climb and drop by its step height and fall distance, count a point as reached within the margin, and teleport to the point a leg is stuck on after the stationary ticks, as Citizens' default stuck action does. With `openDoors` a walking route plans through closed wooden and copper doors, fence gates and upper trapdoors, opens each for real just before reaching it (after protection allows `use`, failing closed) and closes it once past, or when the route ends, is cancelled or the NPC despawns or sleeps. Iron doors and trapdoors stay closed. On Folia the door's region owner changes it; on mod servers the server thread does |
| Non-player NPCs (12) | Type converted | Every observed type has a packet body; names and hologram lines show in a text display; armor stand parts show; horse armor and saddle use the `body` and `saddle` slots |
| Text (2) | Converter version 8 and later: the behavior `citizens:text-<id>` in `behaviors/citizens/`, with a proximity or right-click trigger and `animus:say`; random or turn-taking lines | Runs once the NPC is spawned and behaviors are loaded. One cooldown per NPC; speech bubbles and held-item requirements are reported |
| Commands (`commandtrait`, none in the reference) | Converter version 8 and later: the behavior `citizens:commands-<id>` with right- and left-click triggers, permission checks, delays and cooldowns, using `animus:command` | Denied until the behavior is on the `commandBehaviors` allow-list. Sneak clicks, NPC-run commands, use limits and costs are reported and not converted |
| Ownership (388) | A player UUID converts to `owner`; a server owner records none | Exposed through `NpcDefinition.owner()`, the definition snapshot and `/animus inspect` for plugins and modules to use. Animus management commands still require `animus.admin` |
| dtlTraders `trader` (not in the reference) | Converter version 6 and later: `guiName` converts to `modules["animus:shop"]` naming `dtltraders:<shop>`; an old version 3 trader names `dtltraders:converted-<npc>`. Shop files, version 3 stock and `data.dat` convert with the separate [shop importer](shops.md#importing-from-dtltraders) | Converter version 7 and later write `reviewAccepted: false`, and an imported binding opens nothing until it is accepted (`/animus import citizens accept shops`). Then right click opens the shop once its shop file is installed; a missing shop says so to the player |
| Shopkeepers save (separate `save.yml`; private 107-shopkeeper reference, 2026-11-05) | Every shopkeeper becomes `shopkeepers:shop-<id>`; the reference has 107 admin shops on Citizens NPCs and 1037 offers, all converted to trade entries, 2096 items of which 235 carry components, none preserved only. All 107 Citizens links resolve against the reference Citizens save. The browser converter gives the same output hash. See [migrating from Citizens](migrating-from-citizens.md#shopkeepers) | The Citizens import accepts the bound shops with `accept shops`. Player shops run as [container shops](shops.md#container-shops); mob shopkeepers become despawned NPCs; book offers, mob settings and hire data are kept, not run |
| Sentinel (103) | Settings converted to `animus:combat`; unsupported settings and statistics kept separately; disabled pending review | [Combat module](combat.md) runs once the review is accepted. In a live migration of the reference save on Paper 26.3 (2026-11-05), 103 of 103 imported guards were combat-capable |
| Builder and Constructor (none in the reference) | Builder's `builder` trait converts to an `animus:builder` section, disabled pending review; Constructor's `constructor` trait is kept for review and its keys are reported | [Building](building.md) runs a reviewed section with `/animus build start <npc-id>`. Builder `.schematic` files must be saved again as `.schem` |
| Denizen flags (24), assignment/trigger records (8 each) | The records stay in `citizens:review`. With the Denizen folder beside the save, the import converts the scripts into `denizen:` behaviors bound to the assigned NPCs, and flag values into behavior state. See [migrating from Citizens](migrating-from-citizens.md#denizen) | Behaviors run once `accept denizen` lists them. On the private reference (2026-11-05): 157 scripts, 56 behaviors for 6 NPCs, 222 of 2,195 command lines converted, 137 flag values imported and 65 kept, all 56 behaviors valid offline; the browser converter gives the same output hash |
| Denizen scripts (separate `plugins/Denizen/scripts`) | Assignment, interact and task scripts convert; world, procedure, command and data scripts are kept with reasons | Lines using definitions, loops over computed values or tags with no placeholder are kept and listed per file and line |
| Sentinel statistics (`stats_*`) | Preserved as history, not settings | Not used |
| Mounts (2) | UUID references resolved; version 10 reports a resolved `mountedon` as converted | Resolved references apply from definitions on exact Paper/Spigot: passengers for every viewer, across restart. Unresolved ones stay preserved and reported. See [mounted NPCs](runtime.md#mounted-npcs) |
| Controllable (1) | Converter version 7 and later: an enabled trait becomes `animus:riding` with `enabled` and `reviewAccepted` false; the trait stays in `citizens:review`. A disabled trait (the reference's one) converts to nothing | `/animus import citizens accept riding` turns it on. See [riding](runtime.md#riding-an-npc) |
| Dragons (3 bodies) | Preserved; type converted | Packet body with correct facing, phase and part-based hits; no AI. See [presentation](runtime.md#ender-dragon-presentation) |
| Missing/unknown modules and traits | Preserved as JSON and in original archive | Trusted Java modules load at startup; module workflows and JSON behaviors (manual and triggered) pass live checks on all nine Paper, Spigot and Folia targets. Unknown imported settings remain unexecuted |

Entity types: player 483, armor stand 3, ender dragon 3, horse 2, turtle 1, frog 1, llama 1, armadillo 1. All 495 are represented and validate as definitions. All eight body types were decoded by an exact 26.3 protocol client. Persisted activation passes restart and viewer checks on every Paper, Spigot and Folia target. Graphical and client frame-time checks remain open; see [runtime limits](runtime.md).

## Every observed trait

| Trait | Records | Classification beyond converted fields |
|---|---:|---|
| age | 2 | Negative age converts to `baby: true` on bodies with a baby form |
| armorstandtrait | 3 | Converted to `armorStand`; marker and gravity preserved |
| assignment | 8 | Requires integration; converted from the Denizen scripts when they are present |
| attributetrait | 13 | `step_height` converted to `movement.stepHeight`; other attributes preserved |
| controllable | 1 | Converted (disabled trait) |
| denizen_flags | 24 | Requires integration; true/false and whole-number values import as NPC behavior state |
| equipment | 123 | Converted to `equipment` with item components; meta without a component preserved per item |
| gravity | 4 | Converted to `gravity` |
| hologramtrait | 91 | Lines, margins and renderer settings converted to `hologram` |
| horsemodifiers | 4 | Saddle and armour converted to equipment slots; colour, style and chest require review |
| inventory | 121 | Converted to `inventory` with item components |
| location | 495 | Converted, including the world id and body yaw |
| lookclose | 495 | Converted to `animus:look-close`; head-only, linked-body, per-player and filters require review |
| mounttrait | 2 | Converted when the reference resolves |
| owner | 388 | Player UUID converted to `owner` |
| poses | 3 | Default pose converted to the facing; named poses converted to `poses` for `animus:pose` |
| rotationtrait | 1 | Converted when immediate or default; gradual turning requires review |
| scoreboardtrait | 495 | Colour converted to `teamColor`, tags to `tags` |
| sentinel | 103 | Converted to combat settings; disabled pending review; statistics preserved |
| sittrait | 1 | Converted to `pose: sitting` |
| skintrait | 489 | Converted to skin documents for player bodies; kept in `citizens:review` on other bodies; opt-in signed-texture proof |
| sneak | 1 | Converted to `pose: sneaking` |
| sneaking | 1 | Converted to `pose: sneaking` |
| spawned | 11 | Converted; imported NPCs stay dormant until applied |
| targetable | 24 | `false` converted; `true` converted to `animus:combat` `targetableByMobs` |
| text | 2 | Converted to a generated `citizens:text-<id>` behavior |
| trader | 0 | `guiName` and `converted-to-v5` converted to `animus:shop`; version 3 stock requires the shop importer |
| triggers | 8 | Requires integration |
| type | 495 | Converted identifier; opt-in packet proof for all observed types |
| waypoints | 318 | Converted to an `animus:navigation` route, disabled pending review. Point facing and world ids, and triggers on guides (which Citizens never runs), count as converted; settings of a provider the NPC does not use and unsupported triggers are preserved |

## Sentinel settings

Each Sentinel record becomes a disabled `modules["animus:combat"]` with `enabled` and `reviewAccepted` set to `false`. Accept the review by setting both to `true` after checking the result; until then the NPC neither fights nor takes combat damage. The unchanged Sentinel record stays in `citizens:review` and in the import archive. Converter version 5 and later write this mapping (version 3 and 4 wrote an earlier one); per-field reports mark converted fields `CONVERTED`, kept fields `UNSUPPORTED_PRESERVED`, and fields unknown at the pinned source `REQUIRES_INTEGRATION`.

The module keeps three things apart:

- The converted settings, described in [combat.md](combat.md). A field missing from a save means Sentinel's own default, so those defaults are written out rather than replaced by Animus defaults.
- `preservedSettings`: every unsupported or unknown field, plus the original value of any converted field that could not be represented exactly (out of range, malformed, invalid patterns, target entries with no equivalent rule, item data, unresolvable guarded NPC). Nothing here runs.
- `historicalStatistics`: the 16 `stats_*` counters, as imported. They are not updated.

`migrationReport` lists which source fields converted, which are unsupported or unknown, and a note for every value that was adjusted.

Of the 69 persisted fields at the pinned source, 51 settings convert, 2 settings are unsupported and kept (`autoswitch`, `weapon_redirect_map`), and 16 statistics are kept separately.

| Sentinel field | Combat setting | Notes |
| --- | --- | --- |
| `allTargets`, `allIgnores`, `allAvoids` | `targets`, `ignores`, `avoids` | See the target list table below. |
| `range` | `targeting.scanRange` | Limited to 256; the server's `maximumScanRange` (default 100) applies at runtime. |
| `realistic`, `ignore_los` | `targeting.lineOfSight` | `ignore_los` gives `ignored`, else `realistic` gives `field_of_view`, else `required`. |
| `enemyTargetTime`, `reaction_slowdown`, `retain_target`, `fightback` | `targeting.memoryTicks`, `reactionTicks`, `retainTarget`, `fightBack` | Ticks. |
| `avoidRange`, `runaway`, `avoidReturnPoint` | `avoidance.range`, `fleeWhenAttacked`, `returnPoint` | |
| `squad` | `squad.name` | |
| `guardingUpper` + `guardingLower` | `guard.player` | The two halves form the player UUID. |
| `guardedNPC` | `guard.npc` | Resolved to the guarded NPC's UUID in the same import, and to the copy's UUID when applied as a copy; otherwise kept. A guarded player takes precedence. |
| `guard_distance_minimum`, `guard_selection_range` | `guard.followDistance`, `pointRange` | |
| `chaseRange` | `pursuit.leashRange`, `giveUpRange` | |
| `speed` | `pursuit.speed` | 1 means walking speed, 4.3 blocks per second; limited to 1–10. The server's `maximumSpeed` (default 8) applies at runtime. |
| `close_chase`, `ranged_chase`, `disable_teleporting` | `pursuit.closeChase`, `rangedChase`, `teleportWhenStuck` | |
| `worldguard_region` | `pursuit.region` | Checked through WorldGuard on Bukkit; unavailable with a diagnostic elsewhere. |
| `damage` | `melee.damage`, `ranged.damage` | `-1` (the default) becomes `fromEquipment: true` on both; `0` disables melee. |
| `weapon_damage_map` | `melee.weaponDamage` | Material names become `minecraft:` item ids. |
| `attackRate`, `attackRateRanged` | `melee.cooldownTicks`, `ranged.cooldownTicks` | Ticks. |
| `reach` | `melee.reach` | Limited to 6. |
| `projectileRange`, `accuracy`, `safeShot` | `ranged.range`, `inaccuracy`, `safeShot` | Ranged attacks are enabled when the imported held item is a weapon: bow or crossbow (`arrow`), spectral arrow, trident, blaze rod (`fireball`), snowball, egg or wither skeleton skull (`wither_skull`). A held potion is not converted, because its effect is item data. |
| `needsAmmo` | `ranged.useAmmo` | Counts the imported inventory. |
| `health` | `defense.maximumHealth` | Maximum, not current health. |
| `armor` | `defense.armor` | `-1` (the default) becomes `armorFromEquipment: true`. |
| `invincible`, `protected`, `protectFromRange`, `allow_knockback`, `healRate` | `defense.invincible`, `protectedFromIgnored`, `protectFromRange`, `knockback`, `healRateTicks` | `protected` means protection from ignored targets, not region protection. |
| `drops`, `dropChances` | `death.drops` | Plain items with their chances convert; items with extra data keep the whole original list. |
| `enemyDrops`, `death_xp` | `death.victimDrops`, `experience` | |
| `respawnTime`, `spawnPoint` | `respawn.delayTicks`, `locations` | `0` keeps the NPC dead (`delayTicks: -1`). Below 0 also sets `deleteOnDeath: true`, so the definition is deleted when the NPC dies, as one transaction that `/animus rollback` undoes. |
| `greeting_text`, `warning_text`, `greet_range`, `greet_rate` | `greetings.friendly`, `hostile`, `range`, `cooldownTicks` | Empty text means no line. |

Target list entries:

| Sentinel entry | Rule | Notes |
| --- | --- | --- |
| `targets`: categories and entity names | `categories`, `entityTypes` | `PLAYERS`, `NPCS`, `MONSTERS`, `MOBS`, `PASSIVE_MOBS`; about 80 entity names with plural aliases. |
| `targets`: `OWNER` | `owner: true` | Matches the definition's `owner`, which the import takes from the Citizens owner. Every new Sentinel NPC ignores its owner, so imported guards never attack it. |
| `byPlayerName`, `byNpcName`, `byEntityName` | `patterns.players`, `npcs`, `names` | Sentinel names are case-insensitive regular expressions that match anywhere, so they stay patterns. An invalid pattern is kept. |
| `byHeldItem`, `byOffhandItem`, `byEquippedItem` | `items.mainHand`, `offHand`, `worn` | `name:` and `lore:` matchers are kept. |
| `byGroup` | `groups` | |
| `byOther` `squad:`, `uuid:` | `squads`, `players` | |
| `byOther` `healthabove:`, `healthbelow:` | `healthAbove`, `healthBelow` | Several thresholds become the widest one, which matches the same targets. |
| `byOther` `potion:` | `effects` | Old Bukkit effect names such as `INCREASE_DAMAGE` become their current ids. |
| `byOther` `permission:` | `permissions` | |
| `byAllInOne` | `all` | A combined list converts only when every entry in it does. |
| `byMultiple` | `together` | Each entry becomes one part; a group needs 2 to 8 parts. |
| `byEvent` `pvp`, `pve`, `eve`, `pvnpc`, `pvsentinel`, `npcvnpc` | `events.kinds` | `player_hurts_player`, `player_hurts_mob`, `mob_hurts_mob`, `player_hurts_npc` (both `pvnpc` and `pvsentinel`, since only combat NPCs can be hurt) and `npc_hurts_npc`. Not available in ignores. |
| `byEvent` `pv:<target>`, `ev:<target>` | `events.playerAttacks`, `anyAttacks` | |
| `byEvent` `message:<text>` | `chat` | |
| `byEvent` `guarded_fight` | `guard.assist: true` | In target lists only. |
| `byInventoryItem`, `byStatus`, `byOther` from other plugins (`sbteam`, `npc_owned_by`, Towny, Factions and others) | Kept | No equivalent rule. |

Observed in the private reference, all 22 settings now convert, including `warning_text` (60 records) into `greetings.hostile`. The seven observed statistics are kept separately.

| Field | Records | Field | Records |
|---|---:|---|---:|
| allIgnores | 95 | allTargets | 82 |
| allow_knockback | 3 | attackRate | 24 |
| avoidRange | 5 | chaseRange | 82 |
| damage | 38 | enemyTargetTime | 17 |
| fightback | 3 | guard_distance_minimum | 3 |
| guard_selection_range | 3 | health | 3 |
| protected | 5 | range | 84 |
| reach | 1 | realistic | 60 |
| respawnTime | 77 | safeShot | 2 |
| spawnPoint | 80 | speed | 65 |
| squad | 77 | warning_text | 60 |
| stats_arrowsFired | 37 | stats_attackAttempts | 102 |
| stats_damageGiven | 102 | stats_damageTaken | 101 |
| stats_punches | 85 | stats_ticksSpawned | 103 |
| stats_timesSpawned | 103 | | |

## Native implementation investigation

VolmLib's `VirtualPlayers.create(Player owner, Location, int)` requires an online owner. Its implementation constructs detached player objects and sends packets, but does not provide independently configured non-player bodies or Animus interaction interception. It is reusable reference infrastructure, not a complete renderer. Explicit binding files were found for 26.2/26.3; the 26.1.2 path still needs validation.

The locally cached Mojang 26.1.2 and 26.3 classes expose `ServerboundInteractPacket` as a record with `entityId`, `hand`, `location`, and `usingSecondaryAction`. In 26.3 attacks are a separate `ServerboundAttackPacket`. Do not port a pre-26.x handler that expects a nested `ServerboundInteractPacket.Handler`. Constructor/method inspection alone is not a packet proof.

[Sentinel's documentation](https://github.com/mcmonkeyprojects/Sentinel) explains the meaning of imported fields. The [pinned import source inventory](upstream-inventory.md) maps all fixture traits to 101 declared Citizens traits and 69 persisted Sentinel fields, with explicit capability gaps. Combat parity is covered by `CombatParityTest` and the live combat checks; a live WorldGuard run is still open.

## Platform evidence

| Platform/check | 26.1.2 | 26.2 | 26.3 |
|---|---|---|---|
| Spigot API compilation | Pass | Pass | Pass |
| Spigot baseline lifecycle + native proof | Restart +38 checks pass | Restart +38 checks pass | Restart +38 checks pass |
| Native descriptor probe (82 checks) | Pass | Pass | Pass |
| Presentation codec audit: poses, armor stand parts, text displays, seats, hidden name teams (local, exact server jar codecs) | Pass | Pass | Pass |
| Paper startup, packet binding, commands, restart | Stable74 pass | Stable135 pass | Stable240 pass |
| Folia startup, commands, restart | Stable8 pass | Beta7 pass | Alpha6 pass |
| Folia general NPC runtime and region movement | Live checks pass (activation, triggers, actions, patrol across chunk owners) | Same | Same |
| Runtime-8 Folia stationary player preview |50 checks pass, stable8|50 checks pass, beta7|50 checks pass, alpha6|
| Multiplexor baseline lifecycle/restart | Paper/Folia/Spigot pass | Paper/Folia/Spigot pass | Paper/Folia/Spigot pass |
| Native client attached to managed Paper/Spigot | 38 checks each pass | 38 checks each pass | 38 checks each pass |
| Runtime-6 Paper/Spigot lifecycle and two recoveries |32 checks each pass|32 checks each pass|32 checks each pass|
| Runtime-6 Paper/Spigot manual combat |14 checks each pass|14 checks each pass|14 checks each pass|
| Runtime-6 Paper/Spigot unmounted preview control |18 checks each pass|18 checks each pass|18 checks each pass|
| Runtime-6 Paper/Spigot navigation and JSON routes |16 checks each pass|16 checks each pass|16 checks each pass|
| Runtime-6 Folia unavailable-feature rejection + recovery |27 pass, stable8|27 pass, beta7|27 pass, alpha6|
| Mounts, real riders and dragon presentation (Paper/Spigot) | Live checks pass (mounts, riding, dragon flight phase); look and feel on a real client unchecked | Same | Same |
| Mounts, real riders and dragon presentation (Folia) | Unavailable with a diagnostic | Unavailable with a diagnostic | Unavailable with a diagnostic |
| Fabric, NeoForge and Forge NPC runtime, commands, behaviors, combat ([mod servers](mod-servers.md#live-checks)) | Live basic set passes on all three loaders | Live basic set passes on all three loaders | Live full set passes on all three loaders |

The presentation codec audit runs `node qa/protocol-client/audit-presentation.mjs --version <version>` after a build. It feeds the plugin's own pose, armor stand, text display, seat and team packets through each exact vanilla server jar's codecs; the 26.1.2 and 26.2 runs used the 26.3 server libraries. It does not start a server or show anything on a client. Exact server and plugin hashes, commands, and machine-readable direct-test evidence are retained locally under `.qa/platform-tests/evidence-index.json`. Separate Multiplexor reports live under the shared workspace's `consumers/plugin-consumers/state/animus-evidence/`; see the [runner guide](multiplexor.md). Reproduce with `qa/platform-smoke.py`; the selected release channel is explicit. Lifecycle checks use synthetic dormant definitions and are separate from gameplay checks. All six owned servers were shut down after verification.

Supplemental Leaf26.3-17/af63114 coverage includes console administration, invalid-reload retention, TOML migration, restart persistence, and an exact-protocol777 client proof. The client decodes player profiles, signed textures, all eight body types, movement, static passengers, despawn, accepted/rejected interactions, block occlusion, and virtual health. These historical proof scenarios do not establish real-target damage or vanilla-mob targeting; new runtime/combat scenarios report those independently. No graphical-client rendering, combat parity, bandwidth benchmark or measured performance improvement is claimed. See [implementation evidence](implementation.md) for completed scenario results and runner details.

Benchmark Animus against a reproducible server baseline with the same world and clients. Record visible work, simulation and exact configurations; no architecture-only performance claim is justified.

Mounts, riding and dragon presentation bind to the same exact-version adapter. On Folia the NPC runtime reports mounts and riding unavailable through `/animus status`, `/animus ride status` and `/animus inspect`, and dragons keep the hovering pose, and any other Minecraft version refuses to bind. If one of the server methods riding needs is missing, only riding reports unavailable and the rest of the runtime keeps working. `NativeSignatureProbe` checks the native members they use against an exact server jar.

Current runtime details are in [runtime.md](runtime.md); combat settings, protection and limits are in [combat.md](combat.md). [Spigot provenance](spigot-verification.md) records official server builds and prior proof artifact hashes. Do not mix baseline proof evidence with later production artifact results.

Current results: every Paper, Spigot and Folia target passes its live checks on merged master; see [current status](implementation.md#current-status). Older artifact hashes and audits, and the [Folia stationary preview](folia-preview.md), are recorded in [implementation history](implementation.md).
