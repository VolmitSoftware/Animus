# Pinned import source inventory

Audit date: **2026-11-04 UTC**. This source inventory documents Citizens import formats and Sentinel settings for [the migration compatibility matrix](compatibility.md). It records conversion coverage and gaps in translating imported behavior. Fixture comparisons use only its published aggregate counts: 495 records, 29 trait keys and 29 top-level Sentinel keys. No private names, positions, UUIDs, text, textures, scripts or raw records appear here.

## Source pins and inventory method

| Repository | Commit | Commit time (UTC) |
|---|---|---|
| Citizens2 | [62b9265d46c366ce6258b7ccc8a1018e38849d70](https://github.com/CitizensDev/Citizens2/commit/62b9265d46c366ce6258b7ccc8a1018e38849d70) | 2026-11-01T17:52:38Z |
| CitizensAPI | [e7761413005f611e1f64a728864040204f80e566](https://github.com/CitizensDev/CitizensAPI/commit/e7761413005f611e1f64a728864040204f80e566) | 2026-10-20T02:03:51Z |
| Sentinel | [96672f1b03f2182d11f8e3188cae24b9cdaf14a8](https://github.com/mcmonkeyprojects/Sentinel/commit/96672f1b03f2182d11f8e3188cae24b9cdaf14a8) | 2026-06-14T23:25:26Z |

Fetched complete recursive Git trees and pinned raw Java files into ignored `.qa/upstream-inventory/`; each tree reported `truncated=false`. Counted `@TraitName("...")` declarations in Citizens2 and CitizensAPI, checked registrations separately, and extracted persisted top-level keys from SentinelTrait. Nested objects, command flags, NPC metadata, navigator parameters, templates and third-party traits are **not** additional top-level trait declarations. This is an exhaustive declaration/registration and persisted-key inventory for the stated scope, not an exhaustive audit of every upstream behavior.

| Check | Result |
|---|---:|
| Unique Citizens trait declarations across both repositories | 101 |
| Common registrations in CitizensTraitFactory | 61 |
| Additional registrations in v26_1_R1 / v26_2_R1 / v26_3_R1 | 38 / 39 / 39 |
| Total explicitly registered traits in those adapters | 99 / 100 / 100 |
| Fixture trait keys matching current Citizens declarations | 24 of 29 |
| Declared Citizens traits absent from the fixture | 77 |
| SentinelTrait persisted top-level keys | 69: 53 settings + 16 statistics |
| Fixture Sentinel keys matching pinned persisted keys | 29: 22 settings + 7 statistics |

Registration evidence: [common factory](https://github.com/CitizensDev/Citizens2/blob/62b9265d46c366ce6258b7ccc8a1018e38849d70/main/src/main/java/net/citizensnpcs/npc/CitizensTraitFactory.java), [26.1](https://github.com/CitizensDev/Citizens2/blob/62b9265d46c366ce6258b7ccc8a1018e38849d70/v26_1_R1/src/main/java/net/citizensnpcs/nms/v26_1_R1/util/NMSImpl.java), [26.2](https://github.com/CitizensDev/Citizens2/blob/62b9265d46c366ce6258b7ccc8a1018e38849d70/v26_2_R1/src/main/java/net/citizensnpcs/nms/v26_2_R1/util/NMSImpl.java), [26.3](https://github.com/CitizensDev/Citizens2/blob/62b9265d46c366ce6258b7ccc8a1018e38849d70/v26_3_R1/src/main/java/net/citizensnpcs/nms/v26_3_R1/util/NMSImpl.java). `SnifferTrait` is declared but not registered in any of these three adapter methods; `SulfurCubeTrait` is absent from the 26.1 registration set. These source adapter labels do not themselves validate any exact server release.

## Every fixture trait mapped to current source

Classification remains the converter classification in [compatibility.md](compatibility.md); a source-class match is not an executable translation. The current importer retains review data/original archives and installs dormant definitions.

| Fixture key | Records | Current source class | Remaining capability or boundary |
|---|---:|---|---|
| `age` | 2 | [Age](https://github.com/CitizensDev/Citizens2/blob/62b9265d46c366ce6258b7ccc8a1018e38849d70/main/src/main/java/net/citizensnpcs/trait/Age.java) | Negative age converts to `baby: true` on bodies with a baby form and shows for every viewer; the age lock has no effect in Animus, where NPCs never grow. |
| `armorstandtrait` | 3 | [ArmorStandTrait](https://github.com/CitizensDev/Citizens2/blob/62b9265d46c366ce6258b7ccc8a1018e38849d70/main/src/main/java/net/citizensnpcs/trait/ArmorStandTrait.java) | Limb angles, visibility, arms, base plate and size convert to `armorStand` and render; marker and gravity are preserved only. |
| `assignment` | 8 | Not a Citizens built-in at these pins | Denizen assignment reference preserved; external scripts and an explicit JSON translation are required. |
| `attributetrait` | 13 | [AttributeTrait](https://github.com/CitizensDev/Citizens2/blob/62b9265d46c366ce6258b7ccc8a1018e38849d70/main/src/main/java/net/citizensnpcs/trait/AttributeTrait.java) | `step_height` converts to `movement.stepHeight` (at most 1.5); other attributes are preserved with a reason. |
| `controllable` | 1 | [Controllable](https://github.com/CitizensDev/Citizens2/blob/62b9265d46c366ce6258b7ccc8a1018e38849d70/main/src/main/java/net/citizensnpcs/trait/Controllable.java) | An enabled trait becomes `animus:riding`, off until accepted; a disabled trait converts to nothing. Native riding is input-driven ground riding; air control pending. |
| `denizen_flags` | 24 | Not a Citizens built-in at these pins | Denizen state preserved. Animus has typed, scoped behavior state; translating Denizen scripts needs the external script files. |
| `equipment` | 123 | [Equipment](https://github.com/CitizensDev/CitizensAPI/blob/e7761413005f611e1f64a728864040204f80e566/src/main/java/net/citizensnpcs/api/trait/trait/Equipment.java) | All slots convert to definition `equipment` and render for every viewer; cosmetic items replace their slot. Converter version 10 turns Citizens item meta into item components (names, lore, enchantments, models, patterns, colours, potions, maps, persistent data); meta with no component stays in `imported`. No combat-equipment effect. |
| `gravity` | 4 | [Gravity](https://github.com/CitizensDev/Citizens2/blob/62b9265d46c366ce6258b7ccc8a1018e38849d70/main/src/main/java/net/citizensnpcs/trait/Gravity.java) | An enabled trait (no gravity) converts to `gravity: false`; the NPC hovers instead of falling. |
| `hologramtrait` | 91 | [HologramTrait](https://github.com/CitizensDev/Citizens2/blob/62b9265d46c366ce6258b7ccc8a1018e38849d70/main/src/main/java/net/citizensnpcs/trait/HologramTrait.java) | Lines convert top line first (Citizens stores them bottom-up), with margins, line height, view range, billboard, shadow, see-through and interpolation. Per-viewer placeholders are not filled in. |
| `horsemodifiers` | 4 | [HorseModifiers](https://github.com/CitizensDev/Citizens2/blob/62b9265d46c366ce6258b7ccc8a1018e38849d70/main/src/main/java/net/citizensnpcs/trait/HorseModifiers.java) | Saddle and armour fill the `saddle` and `body` equipment slots; colour, style and chest require review. |
| `inventory` | 121 | [Inventory](https://github.com/CitizensDev/CitizensAPI/blob/e7761413005f611e1f64a728864040204f80e566/src/main/java/net/citizensnpcs/api/trait/trait/Inventory.java) | Contents, in map or list form, convert slot for slot to definition `inventory` with item components; no inventory UI, item transactions or rendering. |
| `location` | 495 | [CurrentLocation](https://github.com/CitizensDev/Citizens2/blob/62b9265d46c366ce6258b7ccc8a1018e38849d70/main/src/main/java/net/citizensnpcs/trait/CurrentLocation.java) | Main position converted, including the world id and body yaw (one facing for head and body); known nested locations remapped. |
| `lookclose` | 495 | [LookClose](https://github.com/CitizensDev/Citizens2/blob/62b9265d46c366ce6258b7ccc8a1018e38849d70/main/src/main/java/net/citizensnpcs/trait/LookClose.java) | Enabled, range, realistic looking, disable-while-navigating, NPC targets and random looking convert to `animus:look-close`; head-only, linked-body, per-player and filters are reported. |
| `mounttrait` | 2 | [MountTrait](https://github.com/CitizensDev/Citizens2/blob/62b9265d46c366ce6258b7ccc8a1018e38849d70/main/src/main/java/net/citizensnpcs/trait/MountTrait.java) | UUID references resolved and applied from definitions; passengers follow their vehicle. A resolved reference reports as converted; unresolved ones stay preserved and reported. |
| `owner` | 388 | [Owner](https://github.com/CitizensDev/CitizensAPI/blob/e7761413005f611e1f64a728864040204f80e566/src/main/java/net/citizensnpcs/api/trait/trait/Owner.java) | Player UUID converts to definition `owner`, exposed for plugin and module permission rules; Animus administration still uses its own permission. |
| `poses` | 3 | [Poses](https://github.com/CitizensDev/Citizens2/blob/62b9265d46c366ce6258b7ccc8a1018e38849d70/main/src/main/java/net/citizensnpcs/trait/Poses.java) | The default named pose sets the definition facing; other named poses are preserved, since Animus has no named poses. |
| `rotationtrait` | 1 | [RotationTrait](https://github.com/CitizensDev/Citizens2/blob/62b9265d46c366ce6258b7ccc8a1018e38849d70/main/src/main/java/net/citizensnpcs/trait/RotationTrait.java) | Immediate and default rotation match Animus, which turns NPCs at once; gradual, head-only or pitch-locked rotation requires review. |
| `scoreboardtrait` | 495 | [ScoreboardTrait](https://github.com/CitizensDev/Citizens2/blob/62b9265d46c366ce6258b7ccc8a1018e38849d70/main/src/main/java/net/citizensnpcs/trait/ScoreboardTrait.java) | Colour converts to `teamColor` (glow and name colour); tags convert to definition `tags`, without Citizens' own `CITIZENS_NPC` marker. |
| `sentinel` | 103 | [SentinelTrait](https://github.com/mcmonkeyprojects/Sentinel/blob/96672f1b03f2182d11f8e3188cae24b9cdaf14a8/src/main/java/org/mcmonkey/sentinel/SentinelTrait.java) | SentinelTrait confirmed separately; all fields retained in disabled combat review data. |
| `sittrait` | 1 | [SitTrait](https://github.com/CitizensDev/Citizens2/blob/62b9265d46c366ce6258b7ccc8a1018e38849d70/main/src/main/java/net/citizensnpcs/trait/SitTrait.java) | Sitting converts to `pose: sitting` on player bodies, rendered on a packet-only seat at the NPC's position; `sittingAt` is not used for placement. |
| `skintrait` | 489 | [SkinTrait](https://github.com/CitizensDev/Citizens2/blob/62b9265d46c366ce6258b7ccc8a1018e38849d70/main/src/main/java/net/citizensnpcs/trait/SkinTrait.java) | Complete documents deduplicated; signed-texture proof only. Refresh/default fetching and newer body/cape/elytra/model settings remain pending. |
| `sneak` | 1 | [SneakTrait](https://github.com/CitizensDev/Citizens2/blob/62b9265d46c366ce6258b7ccc8a1018e38849d70/main/src/main/java/net/citizensnpcs/trait/SneakTrait.java) | Sneaking converts to `pose: sneaking` on player bodies and renders as crouching. |
| `sneaking` | 1 | Not a Citizens built-in at these pins | Not a Citizens built-in at these pins. Converter version 10 treats `sneaking: true` like `sneak` and converts it to `pose: sneaking`. |
| `spawned` | 11 | [Spawned](https://github.com/CitizensDev/CitizensAPI/blob/e7761413005f611e1f64a728864040204f80e566/src/main/java/net/citizensnpcs/api/trait/trait/Spawned.java) | Original state retained and used when the import is applied; imported definitions are installed dormant. |
| `targetable` | 24 | [TargetableTrait](https://github.com/CitizensDev/Citizens2/blob/62b9265d46c366ce6258b7ccc8a1018e38849d70/main/src/main/java/net/citizensnpcs/trait/TargetableTrait.java) | `false` matches Animus. `true` is preserved: hostile mobs never target packet-only bodies. |
| `text` | 2 | [Text](https://github.com/CitizensDev/Citizens2/blob/62b9265d46c366ce6258b7ccc8a1018e38849d70/main/src/main/java/net/citizensnpcs/trait/text/Text.java) | Lines, talk-close, range, delay and random talker convert to a generated behavior; per-player cooldowns, speech bubbles and held-item patterns remain. |
| `triggers` | 8 | Not a Citizens built-in at these pins | Extension trigger records preserved; no executable Denizen-compatible dispatch. |
| `type` | 495 | [MobType](https://github.com/CitizensDev/CitizensAPI/blob/e7761413005f611e1f64a728864040204f80e566/src/main/java/net/citizensnpcs/api/trait/trait/MobType.java) | Identifier converted; observed body types have packet proof, not every entity trait or gameplay behavior. |
| `waypoints` | 318 | [Waypoints](https://github.com/CitizensDev/Citizens2/blob/62b9265d46c366ce6258b7ccc8a1018e38849d70/main/src/main/java/net/citizensnpcs/trait/waypoint/Waypoints.java) | Linear (with cycle), guided and wander providers and delay triggers convert to `animus:navigation` routes; the other triggers and straight-line movement remain. |

Waypoint scope includes three providers (`linear`, `wander`, `guided`) and arrival triggers for animation, chat, command, delay, speed and teleport. All three providers and the delay trigger convert; the other triggers are preserved and reported. Sources: [provider registration](https://github.com/CitizensDev/Citizens2/blob/62b9265d46c366ce6258b7ccc8a1018e38849d70/main/src/main/java/net/citizensnpcs/trait/waypoint/Waypoints.java), [trigger implementations](https://github.com/CitizensDev/Citizens2/tree/62b9265d46c366ce6258b7ccc8a1018e38849d70/main/src/main/java/net/citizensnpcs/trait/waypoint/triggers).

## Capabilities the fixture does not exercise

The 77 declarations below are absent as trait keys; this does not establish that their default behavior or related entity types are absent. For example, dragon/frog/llama/armadillo bodies occur without their corresponding versioned trait keys. Each imported behavior family needs deliberate acceptance tests before being reported as translated.

| Capability family | Explicit remaining work |
|---|---|
| Behavior, commands and commerce | Translate behavior trees/memory and command permissions/cooldowns; implement shops with inventory, currency, permissions, purchase limits and transactional outcomes. JSON actions alone do not imply these semantics. |
| Visibility and presentation | Player filters, mirroring, disguises, display/text/item transforms, boss bars, skin layers and entity poses require per-viewer capability decisions and packet validation. |
| Navigation and physical interactions | Follow/home/anchors, path pausing, leash, forcefield, configurable bounding boxes and chunk-ticket behavior need explicit virtual equivalents. Automatic terrain loading remains outside the agreed default. |
| Entity variants and state | Species metadata, size, equipment-like modifiers, special animations and entity-specific interactions need adapters; merely spawning the correct entity type is insufficient. |
| Lifecycle and integrations | Drops, scaled health, effects, shop providers and extension registrations need module contracts and failure/cleanup behavior. |

Concrete scope evidence: [behavior tree and memory lifecycle](https://github.com/CitizensDev/Citizens2/blob/62b9265d46c366ce6258b7ccc8a1018e38849d70/main/src/main/java/net/citizensnpcs/trait/BehaviorTrait.java), [command execution](https://github.com/CitizensDev/Citizens2/blob/62b9265d46c366ce6258b7ccc8a1018e38849d70/main/src/main/java/net/citizensnpcs/trait/CommandTrait.java), [shops and purchase state](https://github.com/CitizensDev/Citizens2/blob/62b9265d46c366ce6258b7ccc8a1018e38849d70/main/src/main/java/net/citizensnpcs/trait/ShopTrait.java), [viewer filtering](https://github.com/CitizensDev/CitizensAPI/blob/e7761413005f611e1f64a728864040204f80e566/src/main/java/net/citizensnpcs/api/trait/trait/PlayerFilter.java).

### Common declarations absent from the fixture (37)

| Trait key | Class |
|---|---|
| `anchors` | `Anchors` |
| `battrait` | `BatTrait` |
| `behavior` | `BehaviorTrait` |
| `boundingbox` | `BoundingBoxTrait` |
| `chunktickettrait` | `ChunkTicketTrait` |
| `clickredirecttrait` | `ClickRedirectTrait` |
| `commandtrait` | `CommandTrait` |
| `disguise` | `DisguiseTrait` |
| `dropstrait` | `DropsTrait` |
| `endercrystaltrait` | `EnderCrystalTrait` |
| `endermantrait` | `EndermanTrait` |
| `entitypose` | `EntityPoseTrait` |
| `followtrait` | `FollowTrait` |
| `forcefieldtrait` | `ForcefieldTrait` |
| `gamemodetrait` | `GameModeTrait` |
| `hometrait` | `HomeTrait` |
| `itemframe` | `ItemFrameTrait` |
| `leashedtrait` | `LeashedTrait` |
| `mirrortrait` | `MirrorTrait` |
| `ocelotmodifiers` | `OcelotModifiers` |
| `packet` | `PacketNPC` |
| `paintingtrait` | `PaintingTrait` |
| `pausepathfinding` | `PausePathfindingTrait` |
| `playerfilter` | `PlayerFilter` |
| `powered` | `Powered` |
| `profession` | `VillagerProfession` |
| `rabbittype` | `RabbitType` |
| `saddle` | `Saddle` |
| `scaledhealthtrait` | `ScaledMaxHealthTrait` |
| `sheeptrait` | `SheepTrait` |
| `shop` | `ShopTrait` |
| `skinlayers` | `SkinLayers` |
| `sleeptrait` | `SleepTrait` |
| `slimesize` | `SlimeSize` |
| `withertrait` | `WitherTrait` |
| `wolfmodifiers` | `WolfModifiers` |
| `woolcolor` | `WoolColor` |

### Versioned declarations absent from the fixture (40)

| Trait key | Class |
|---|---|
| `allaytrait` | `AllayTrait` |
| `areaeffectcloudtrait` | `AreaEffectCloudTrait` |
| `armadillotrait` | `ArmadilloTrait` |
| `axolotltrait` | `AxolotlTrait` |
| `beetrait` | `BeeTrait` |
| `boattrait` | `BoatTrait` |
| `bossbar` | `BossBarTrait` |
| `cameltrait` | `CamelTrait` |
| `cattrait` | `CatTrait` |
| `chickentrait` | `ChickenTrait` |
| `coppergolemtrait` | `CopperGolemTrait` |
| `cowtrait` | `CowTrait` |
| `displaytrait` | `DisplayTrait` |
| `enderdragontrait` | `EnderDragonTrait` |
| `foxtrait` | `FoxTrait` |
| `frogtrait` | `FrogTrait` |
| `goattrait` | `GoatTrait` |
| `interactiontrait` | `InteractionTrait` |
| `itemdisplaytrait` | `ItemDisplayTrait` |
| `llamatrait` | `LlamaTrait` |
| `mannequintrait` | `MannequinTrait` |
| `mushroomcowtrait` | `MushroomCowTrait` |
| `pandatrait` | `PandaTrait` |
| `parrottrait` | `ParrotTrait` |
| `phantomtrait` | `PhantomTrait` |
| `piglintrait` | `PiglinTrait` |
| `pigtrait` | `PigTrait` |
| `polarbeartrait` | `PolarBearTrait` |
| `potioneffects` | `PotionEffectsTrait` |
| `pufferfishtrait` | `PufferFishTrait` |
| `shulkertrait` | `ShulkerTrait` |
| `sniffertrait` | `SnifferTrait` |
| `snowmantrait` | `SnowmanTrait` |
| `spellcastertrait` | `SpellcasterTrait` |
| `sulfurcubetrait` | `SulfurCubeTrait` |
| `textdisplaytrait` | `TextDisplayTrait` |
| `tropicalfishtrait` | `TropicalFishTrait` |
| `vextrait` | `VexTrait` |
| `villagertrait` | `VillagerTrait` |
| `wardentrait` | `WardenTrait` |

Sources for the catalogue: [Citizens2 trait sources](https://github.com/CitizensDev/Citizens2/tree/62b9265d46c366ce6258b7ccc8a1018e38849d70/main/src/main/java/net/citizensnpcs/trait/), [CitizensAPI trait sources](https://github.com/CitizensDev/CitizensAPI/tree/e7761413005f611e1f64a728864040204f80e566/src/main/java/net/citizensnpcs/api/trait/trait).

## Sentinel settings and combat surface

All 29 observed keys match persisted fields at the Sentinel pin. The 103 Sentinel configurations convert to disabled combat settings; the field-by-field mapping is in [compatibility.md](compatibility.md#sentinel-settings). Seven observed `stats_*` keys are retained as historical counters rather than mutable combat settings. The source has 40 additional keys absent from this fixture. [Persisted fields and field semantics](https://github.com/mcmonkeyprojects/Sentinel/blob/96672f1b03f2182d11f8e3188cae24b9cdaf14a8/src/main/java/org/mcmonkey/sentinel/SentinelTrait.java).

| Capability | Observed settings (22 total) | Additional upstream settings absent from fixture (31 total) |
|---|---|---|
| Target selection and avoidance | `allTargets`, `allIgnores`, `avoidRange`, `enemyTargetTime`, `fightback`, `realistic` | `allAvoids`, `runaway`, `retain_target`, `reaction_slowdown`, `ignore_los` |
| Pursuit, guarding and groups | `chaseRange`, `speed`, `squad`, `guard_distance_minimum`, `guard_selection_range` | `close_chase`, `ranged_chase`, `guardedNPC`, `guardingUpper`, `guardingLower`, `avoidReturnPoint`, `disable_teleporting`, `worldguard_region` |
| Attacks and weapons | `damage`, `attackRate`, `range`, `reach`, `safeShot` | `attackRateRanged`, `needsAmmo`, `autoswitch`, `accuracy`, `projectileRange`, `weapon_damage_map`, `weapon_redirect_map` |
| Defense, death and respawn | `health`, `protected`, `allow_knockback`, `respawnTime`, `spawnPoint` | `armor`, `invincible`, `protectFromRange`, `healRate`, `drops`, `dropChances`, `enemyDrops`, `death_xp` |
| Greetings | `warning_text` | `greeting_text`, `greet_range`, `greet_rate` |

Migration semantics that need explicit tests: `attackRate`, `attackRateRanged`, `healRate`, `respawnTime`, `enemyTargetTime`, `greet_rate` and `reaction_slowdown` use ticks; `health` is maximum health; `damage=-1` and `armor=-1` request equipment-derived values. `protected` means protection from ignored targets, not a WorldGuard protection decision. The two guarding UUID halves and `guardedNPC` are different identity formats. [Sentinel field definitions](https://github.com/mcmonkeyprojects/Sentinel/blob/96672f1b03f2182d11f8e3188cae24b9cdaf14a8/src/main/java/org/mcmonkey/sentinel/SentinelTrait.java).

The nested target-list schema has 14 persisted keys: `targets`, `byPlayerName`, `byNpcName`, `byEntityName`, `byHeldItem`, `byOffhandItem`, `byEquippedItem`, `byInventoryItem`, `byGroup`, `byEvent`, `byStatus`, `byOther`, `byAllInOne`, `byMultiple`. This includes regex/name/item/status/event/group matching, extension predicates and composite lists; retaining `allTargets` as JSON does not implement these rules. Nested fixture coverage was not counted by this audit. [Target-list persistence and evaluation](https://github.com/mcmonkeyprojects/Sentinel/blob/96672f1b03f2182d11f8e3188cae24b9cdaf14a8/src/main/java/org/mcmonkey/sentinel/targeting/SentinelTargetList.java).

Weapon code covers melee/shields and arrows, firework-capable arrow launching, tridents, potions, snowballs, eggs, pearls, fireballs, skulls, llama spit, shulker bullets and evoker fangs. Animus supports melee and arrows only, with one damage authority per hit; see [combat.md](combat.md). [Attack dispatch](https://github.com/mcmonkeyprojects/Sentinel/blob/96672f1b03f2182d11f8e3188cae24b9cdaf14a8/src/main/java/org/mcmonkey/sentinel/SentinelAttackHelper.java), [Weapon implementations](https://github.com/mcmonkeyprojects/Sentinel/blob/96672f1b03f2182d11f8e3188cae24b9cdaf14a8/src/main/java/org/mcmonkey/sentinel/SentinelWeaponHelper.java).

Nine additional historical keys: `stats_potionsThrow` (persisted spelling differs from Java field `stats_potionsThrown`), `stats_fireballsFired`, `stats_snowballsThrown`, `stats_eggsThrown`, `stats_skullsThrown`, `stats_llamaSpitShot`, `stats_shulkerBulletsShot`, `stats_evokerFangsSpawned`, `stats_pearlsUsed`. Preserve persisted spellings. [Counter persistence](https://github.com/mcmonkeyprojects/Sentinel/blob/96672f1b03f2182d11f8e3188cae24b9cdaf14a8/src/main/java/org/mcmonkey/sentinel/SentinelTrait.java).

Integration source contains 14 classes: `SentinelCrackShot`, `SentinelFactions`, `SentinelHealth`, `SentinelNPCOwnedBy`, `SentinelPermissions`, `SentinelPotion`, `SentinelSBScore`, `SentinelSBTeams`, `SentinelSimpleClans`, `SentinelSimplePets`, `SentinelSquads`, `SentinelTowny`, `SentinelUUID`, `SentinelWar`. Vault groups and WorldGuard are handled separately. Implement only declared Animus adapters; availability of an upstream integration class does not mean its dependency is installed or working. [Integration classes](https://github.com/mcmonkeyprojects/Sentinel/tree/96672f1b03f2182d11f8e3188cae24b9cdaf14a8/src/main/java/org/mcmonkey/sentinel/integration), [Conditional registration and Vault/WorldGuard discovery](https://github.com/mcmonkeyprojects/Sentinel/blob/96672f1b03f2182d11f8e3188cae24b9cdaf14a8/src/main/java/org/mcmonkey/sentinel/SentinelPlugin.java).

The WorldGuard helper examined here constrains path destinations to a configured region through `SentinelWantsToPathEvent`; that code alone does not prove a complete PvP/protection policy. Animus still needs independent damage/protection decisions. The event surface also includes attack, chase start/end, combat-state change and no-more-targets hooks, all requiring Animus equivalents or explicit integration limits. [WorldGuard helper](https://github.com/mcmonkeyprojects/Sentinel/blob/96672f1b03f2182d11f8e3188cae24b9cdaf14a8/src/main/java/org/mcmonkey/sentinel/utilities/SentinelWorldGuardHelper.java), [Sentinel events](https://github.com/mcmonkeyprojects/Sentinel/tree/96672f1b03f2182d11f8e3188cae24b9cdaf14a8/src/main/java/org/mcmonkey/sentinel/events).

## Reproduction and acceptance follow-up

1. Fetch the three commits above using GitHub’s `GET /repos/{owner}/{repo}/git/trees/{commit}?recursive=1`, assert `truncated=false`, and download selected files from `https://raw.githubusercontent.com/{owner}/{repo}/{commit}/{path}`. The cache here retains the trees, source files, initial file hashes, extracted trait names and Sentinel keys; it contains only upstream public source.
2. Scan Citizens2 `main/src/main/java/net/citizensnpcs/trait/**/*.java` and CitizensAPI `src/main/java/net/citizensnpcs/api/trait/trait/*.java` for `@TraitName("name")`. Count unique names and associate each with its public class. Expect 101.
3. Extract `TraitInfo.create(Class.class)` from `CitizensTraitFactory.java` and `registerTraitWithCommand(manager, Class.class)` from the three pinned NMSImpl files. Compare class sets, not directory file counts. Expect 61 common and 38/39/39 adapter registrations.
4. Extract `@Persist("key")` fields from SentinelTrait; bare `@Persist` uses the field name (`avoidRange`). Expect 69 top-level keys; partition `stats_` into 16 counters and the other 53 settings. Inspect SentinelTargetList separately for its 14 nested keys.
5. Compare against the aggregate tables in `docs/compatibility.md`; expect 24 matching Citizens declarations plus five other fixture keys, and all 29 Sentinel keys matching. Do not copy the private fixture or generated records into this repository.
6. Turn each remaining capability family into runtime scenarios before marking parity. Prioritize the observed 24 built-ins, resolve the unmatched `sneaking` provenance, translate Denizen scripts when provided, then implement/review the 103 disabled combat configurations. Add synthetic fixtures for absent settings/providers and exact-platform integration checks.
