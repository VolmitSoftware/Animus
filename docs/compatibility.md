# Import compatibility and runtime capabilities

This is a development inventory, not a release-parity claim. To run a migration, see [migrating from Citizens](migrating-from-citizens.md). Counts were produced by the Java converter against the private 495-record reference on 2026-11-04. The private input and generated records are not repository fixtures.

| Capability | Data conversion | Runtime status |
|---|---|---|
| UUID, readable ID, Citizens numeric ID | Converted; explicit ID map | Definition identity supported |
| Name, type, main world/location | Converted; optional world mapping. Converter version 7 and later put each definition in `npcs/<world>-r<x>_<z>/citizens-<id>.json`, named after its world after mapping and the region file it stands in | Names up to 256 characters. Short plain player names use the player nameplate; longer or coloured player names and every non-player name show in a packet-only text display. See [appearance](runtime.md#appearance-text-and-inventory) |
| Skins (489 traits, 409 unique complete documents) | Texture/signature/settings preserved and deduplicated | Signed textures in persisted runtime and opt-in proof; see artifact-specific evidence |
| Equipment (123) | All eight slots convert to `equipment`; a cosmetic item replaces its slot. Item id, count and damage convert; other item data is kept in the item's `imported` field | Shown to every viewer from the definition, across restart. Kept item data (names, lore, enchantment glint, custom model data) is not shown. Equipment does not change combat damage or armor |
| Inventory (121) | Slots 0–71 convert to `inventory` slot for slot, with the same item rules | Stored for behaviors and modules to read; never rendered. Shops and chest GUIs are separate documents ([shops](shops.md), [chest GUIs](gui.md)) and never read the NPC's inventory |
| Waypoints (318), look behavior (495) | Preserved for review | Known locations remapped; native movement/look/JSON routes verified on exact Paper/Spigot targets; imported traits are not executed |
| Holograms (91) | Lines convert to `hologram.lines` in order; at most 16 lines of 256 characters | One packet-only text display above the NPC for every viewer. Lines are evenly spaced; line margins, line height, view ranges, renderer settings and placeholders are kept for review and not applied |
| Poses (3), sneak (1), sit (1), armor stand (3) | The default named pose sets the facing; `sneak` becomes `sneaking`, `sittrait` becomes `sitting`; armor stand parts and angles convert | Sneaking, sleeping, swimming and sitting on player bodies; other body types stand. Armor stand size, arms, base plate, visibility and limb angles are shown. Marker and gravity are kept for review. Other named poses are kept and not applied |
| Non-player NPCs (12) | Type converted | Every observed type has a packet body; names and hologram lines show in a text display; armor stand parts show; horse armor and saddle use the `body` and `saddle` slots |
| Text (2) | Preserved for review | Talk lines, ranges and speech bubbles are not run. Write them as a behavior with [`animus:say`](behaviors.md#actions) |
| Ownership (388) | A player UUID converts to `owner`; a server owner records none | Exposed through `NpcDefinition.owner()`, the definition snapshot and `/animus inspect` for plugins and modules to use. Animus management commands still require `animus.admin` |
| dtlTraders `trader` (not in the reference) | Converter version 6 and later: `guiName` converts to `modules["animus:shop"]` naming `dtltraders:<shop>`; an old version 3 trader names `dtltraders:converted-<npc>`. Shop files, version 3 stock and `data.dat` convert with the separate [shop importer](shops.md#importing-from-dtltraders) | Converter version 7 and later write `reviewAccepted: false`, and an imported binding opens nothing until it is accepted (`/animus import citizens accept shops`). Then right click opens the shop once its shop file is installed; a missing shop says so to the player |
| Shopkeepers save (separate `save.yml`; private 107-shopkeeper reference, 2026-11-05) | Every shopkeeper becomes `shopkeepers:shop-<id>`; the reference has 107 admin shops on Citizens NPCs and 1037 offers, all converted to trade entries, 2096 items of which 235 carry components, none preserved only. All 107 Citizens links resolve against the reference Citizens save. The browser converter gives the same output hash. See [migrating from Citizens](migrating-from-citizens.md#shopkeepers) | The Citizens import accepts the bound shops with `accept shops`. Player shops run as [container shops](shops.md#container-shops); mob shopkeepers become despawned NPCs; book offers, mob settings and hire data are kept, not run |
| Sentinel (103) | Settings converted to `animus:combat`; unsupported settings and statistics kept separately; disabled pending review | [Combat module](combat.md) runs once the review is accepted; live gameplay checks pending |
| Builder and Constructor (none in the reference) | Builder's `builder` trait converts to an `animus:builder` section, disabled pending review; Constructor's `constructor` trait is kept for review and its keys are reported | [Building](building.md) runs a reviewed section with `/animus build start <npc-id>`. Builder `.schematic` files must be saved again as `.schem` |
| Denizen flags (24), assignment/trigger records (8 each) | Preserved; requires integration | External scripts required to translate behaviors |
| Mounts (2) | Preserved; UUID references resolved | Resolved references apply from definitions on exact Paper/Spigot: passengers for every viewer, across restart. Unresolved ones stay preserved and reported. See [mounted NPCs](runtime.md#mounted-npcs) |
| Controllable (1) | Converter version 7 and later: an enabled trait becomes `animus:riding` with `enabled` and `reviewAccepted` false; the trait stays in `citizens:review` | `/animus import citizens accept riding` turns it on. See [riding](runtime.md#riding-an-npc) |
| Dragons (3 bodies) | Preserved; type converted | Packet body with correct facing, phase and part-based hits; no AI. See [presentation](runtime.md#ender-dragon-presentation) |
| Missing/unknown modules and traits | Preserved as JSON and in original archive | Trusted Java startup loader passed runtime10 module/data workflows on all nine exact targets; manual JSON behaviors in the current source await their own managed gate. Unknown imported settings remain unexecuted |

Entity types: player 483, armor stand 3, ender dragon 3, horse 2, turtle 1, frog 1, llama 1, armadillo 1. All 495 are represented and validate as definitions. All eight body types were decoded by an exact26.3 protocol client in the isolated packet proof. Persisted activation passes dedicated restart and viewer scenarios on every exact Spigot/Paper target. Graphical and client frame-time validation remain open; see [runtime limits](runtime.md).

## Every observed trait

| Trait | Records | Classification beyond converted fields |
|---|---:|---|
| age | 2 | Unsupported, preserved |
| armorstandtrait | 3 | Converted to `armorStand`; marker and gravity preserved |
| assignment | 8 | Requires integration |
| attributetrait | 13 | Unsupported, preserved |
| controllable | 1 | Requires review |
| denizen_flags | 24 | Requires integration |
| equipment | 123 | Converted to `equipment`; extra item data preserved |
| gravity | 4 | Unsupported, preserved |
| hologramtrait | 91 | Lines converted to `hologram`; layout settings preserved |
| horsemodifiers | 4 | Unsupported, preserved |
| inventory | 121 | Converted to `inventory`; extra item data preserved |
| location | 495 | Main world/coordinates converted; other fields preserved for review |
| lookclose | 495 | Requires review |
| mounttrait | 2 | Requires review |
| owner | 388 | Player UUID converted to `owner` |
| poses | 3 | Default pose converted to the facing; other poses preserved |
| rotationtrait | 1 | Unsupported, preserved |
| scoreboardtrait | 495 | Unsupported, preserved |
| sentinel | 103 | Converted to combat settings; disabled pending review |
| sittrait | 1 | Converted to `pose: sitting` |
| skintrait | 489 | Converted to skin documents; opt-in signed-texture proof |
| sneak | 1 | Converted to `pose: sneaking` |
| sneaking | 1 | Unsupported, preserved |
| spawned | 11 | Requires review; candidate spawned=false |
| targetable | 24 | Unsupported, preserved |
| text | 2 | Requires review |
| trader | 0 | `guiName` and `converted-to-v5` converted to `animus:shop`; version 3 stock requires the shop importer |
| triggers | 8 | Requires integration |
| type | 495 | Converted identifier; opt-in packet proof for all observed types |
| waypoints | 318 | Requires review |

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

[Sentinel's documentation](https://github.com/mcmonkeyprojects/Sentinel) explains the meaning of imported fields. The [pinned import source inventory](upstream-inventory.md) maps all fixture traits to 101 declared Citizens traits and 69 persisted Sentinel fields, with explicit capability gaps. Runtime parity still requires scenario evidence.

## Platform evidence

| Platform/check | 26.1.2 | 26.2 | 26.3 |
|---|---|---|---|
| Spigot API compilation | Pass | Pass | Pass |
| Spigot baseline lifecycle + native proof | Restart +38 checks pass | Restart +38 checks pass | Restart +38 checks pass |
| Native descriptor probe (82 checks) | Pass | Pass | Pass |
| Presentation codec audit: poses, armor stand parts, text displays, seats, hidden name teams (local, exact server jar codecs) | Pass | Pass | Pass |
| Paper startup, packet binding, commands, restart | Stable74 pass | Stable135 pass | Stable240 pass |
| Folia startup, commands, restart | Stable8 pass | Beta7 pass | Alpha6 pass |
| Folia general NPC runtime and region movement | Source and unit tests only; live check pending | Source and unit tests only; live check pending | Source and unit tests only; live check pending |
| Runtime-8 Folia stationary player preview |50 checks pass, stable8|50 checks pass, beta7|50 checks pass, alpha6|
| Multiplexor baseline lifecycle/restart | Paper/Folia/Spigot pass | Paper/Folia/Spigot pass | Paper/Folia/Spigot pass |
| Native client attached to managed Paper/Spigot | 38 checks each pass | 38 checks each pass | 38 checks each pass |
| Runtime-6 Paper/Spigot lifecycle and two recoveries |32 checks each pass|32 checks each pass|32 checks each pass|
| Runtime-6 Paper/Spigot manual combat |14 checks each pass|14 checks each pass|14 checks each pass|
| Runtime-6 Paper/Spigot unmounted preview control |18 checks each pass|18 checks each pass|18 checks each pass|
| Runtime-6 Paper/Spigot navigation and JSON routes |16 checks each pass|16 checks each pass|16 checks each pass|
| Runtime-6 Folia unavailable-feature rejection + recovery |27 pass, stable8|27 pass, beta7|27 pass, alpha6|
| Mounts, real riders and dragon presentation (Paper/Spigot) | Unit tests and exact-jar checks; not yet run on a live server | Same | Same |
| Mounts, real riders and dragon presentation (Folia) | Unavailable with a diagnostic | Unavailable with a diagnostic | Unavailable with a diagnostic |
| Fabric, NeoForge and Forge NPC runtime, commands, behaviors, combat ([mod servers](mod-servers.md#live-checks)) | Live smoke set passes on all three loaders | Live smoke set passes on all three loaders | Live full set passes on all three loaders |

The presentation codec audit runs `node qa/protocol-client/audit-presentation.mjs --version <version>` after a build. It feeds the plugin's own pose, armor stand, text display, seat and team packets through each exact vanilla server jar's codecs; the 26.1.2 and 26.2 runs used the 26.3 server libraries. It does not start a server or show anything on a client. Exact server and plugin hashes, commands, and machine-readable direct-test evidence are retained locally under `.qa/platform-tests/evidence-index.json`. Separate Multiplexor reports live under the shared workspace's `consumers/plugin-consumers/state/animus-evidence/`; see the [runner guide](multiplexor.md). Reproduce with `qa/platform-smoke.py`; the selected release channel is explicit. Lifecycle checks use synthetic dormant definitions and are separate from gameplay checks. All six owned servers were shut down after verification.

Supplemental Leaf26.3-17/af63114 coverage includes console administration, invalid-reload retention, TOML migration, restart persistence, and an exact-protocol777 client proof. The client decodes player profiles, signed textures, all eight body types, movement, static passengers, despawn, accepted/rejected interactions, block occlusion, and virtual health. These historical proof scenarios do not establish real-target damage or vanilla-mob targeting; new runtime/combat scenarios report those independently. No graphical-client rendering, combat parity, bandwidth benchmark or measured performance improvement is claimed. See [implementation evidence](implementation.md) for completed scenario results and runner details.

Benchmark Animus against a reproducible server baseline with the same world and clients. Record visible work, simulation and exact configurations; no architecture-only performance claim is justified.

Mounts, riding and dragon presentation bind to the same exact-version adapter. On Folia the NPC runtime reports mounts and riding unavailable through `/animus status`, `/animus ride status` and `/animus inspect`, and dragons keep the hovering pose, and any other Minecraft version refuses to bind. If one of the server methods riding needs is missing, only riding reports unavailable and the rest of the runtime keeps working. `NativeSignatureProbe` checks the native members they use against an exact server jar.

Current runtime details are in [runtime.md](runtime.md); combat settings, protection and limits are in [combat.md](combat.md). [Spigot provenance](spigot-verification.md) records official server builds and prior proof artifact hashes. Do not mix baseline proof evidence with later production artifact results.

Latest fully verified runtime artifact: `c89adc35796afe5c33d85cf3e0f853ae2cd0f68123e3c1c16a4e77abd93f9576`, Multiplexor build `run-1793803535545514-80447`. Independent audit passed480 native assertions and81 Folia lifecycle assertions. All nine final ports are closed, final logs have no errors/movement warnings, and owned definitions/checkpoints were removed. The later stationary Folia proof has separate runtime8 evidence below. Earlier native38-check proofs remain tied to their earlier artifact; see [current evidence](implementation.md).

The separate runtime8 stationary-preview artifact is `5bd4a77deef1dc7ce6fa6b0de1809305fe080775aa516a3da6ea37594ceab2cf`, build `run-1793807521311830-32660`, with150 exact Folia checks and an independent passing audit. It does not relabel runtime6's Paper/Spigot results. [Folia preview scope and limits](folia-preview.md) exclude general NPC ownership/transfer, combat, graphical fidelity and performance; actual unowned command-boundary admission, partial four-chunk ownership and transient ticket absence remain unverified.
