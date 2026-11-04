# Compatibility inventory

This is a development inventory, not a release-parity claim. Counts were produced by the Java converter against the private 495-record reference on 2026-11-04. The private input and generated records are not repository fixtures.

| Capability | Data conversion | Runtime status |
|---|---|---|
| UUID, readable ID, Citizens numeric ID | Converted; explicit ID map | Definition identity supported |
| Name, type, main world/location | Converted; optional world mapping | Editable data only; world/type existence not checked against a running server |
| Skins (489 traits, 409 unique complete documents) | Texture/signature/settings preserved and deduplicated | Signed textures in opt-in packet proof; production appearance pending |
| Equipment (123), inventory (121) | Preserved for review | No equipment/inventory simulation |
| Waypoints (318), look behavior (495) | Preserved for review | Known nested locations remapped; bounded snapshot planner implemented, live movement pending |
| Text (2), holograms (91), poses (3) | Preserved for review | No presentation or dialogue runtime |
| Ownership (388) | Preserved for review | Administration uses plugin permissions; per-NPC ownership pending |
| Sentinel (103) | All settings preserved; disabled pending review | No combat module; historical statistics separated from settings |
| Denizen flags (24), assignment/trigger records (8 each) | Preserved; requires integration | External scripts required to translate behaviors |
| Mounts (2), controllable (1), dragons (3 bodies) | Preserved for review | Mount UUIDs resolved; static passengers and dragon packets proven on26.3; control and gameplay pending |
| Missing/unknown modules and traits | Preserved as JSON and in original archive | No module loader or executable integration |
| Citizens-dependent plugins | No binary compatibility | Explicit adaptations required |

Entity types: player 483, armor stand 3, ender dragon 3, horse 2, turtle 1, frog 1, llama 1, armadillo 1. All 495 are represented and validate as definitions. All eight body types were decoded by an exact26.3 protocol client in the isolated packet proof. Production activation and visual/client frame-time validation remain open.

## Every observed trait

| Trait | Records | Classification beyond converted fields |
|---|---:|---|
| age | 2 | Unsupported, preserved |
| armorstandtrait | 3 | Unsupported, preserved |
| assignment | 8 | Requires integration |
| attributetrait | 13 | Unsupported, preserved |
| controllable | 1 | Requires review |
| denizen_flags | 24 | Requires integration |
| equipment | 123 | Requires review |
| gravity | 4 | Unsupported, preserved |
| hologramtrait | 91 | Requires review |
| horsemodifiers | 4 | Unsupported, preserved |
| inventory | 121 | Requires review |
| location | 495 | Main world/coordinates converted; other fields preserved for review |
| lookclose | 495 | Requires review |
| mounttrait | 2 | Requires review |
| owner | 388 | Requires review |
| poses | 3 | Requires review |
| rotationtrait | 1 | Unsupported, preserved |
| scoreboardtrait | 495 | Unsupported, preserved |
| sentinel | 103 | Requires integration |
| sittrait | 1 | Unsupported, preserved |
| skintrait | 489 | Converted to skin documents; opt-in signed-texture proof |
| sneak | 1 | Unsupported, preserved |
| sneaking | 1 | Unsupported, preserved |
| spawned | 11 | Requires review; candidate spawned=false |
| targetable | 24 | Unsupported, preserved |
| text | 2 | Requires review |
| triggers | 8 | Requires integration |
| type | 495 | Converted identifier; opt-in packet proof for all observed types |
| waypoints | 318 | Requires review |

## Every observed Sentinel field

All are preserved as source data and marked `REQUIRES_INTEGRATION` in per-field reports. None is silently interpreted as working Animus combat. The complete original Sentinel object remains in the archive. The disabled module configuration separates reviewable settings from historical statistics.

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

The [Citizens NPC API](https://raw.githubusercontent.com/CitizensDev/CitizensAPI/master/src/main/java/net/citizensnpcs/api/npc/NPC.java) exposes `getEntity()` and entity-backed operations. Animus cannot transparently satisfy those with purely virtual bodies. [Sentinel's documentation](https://github.com/mcmonkeyprojects/Sentinel) is the capability reference for future targeting, guarding, weapons, and integrations. A [pinned upstream inventory](upstream-inventory.md) now maps all fixture traits to 101 declared Citizens traits and 69 persisted Sentinel fields, with explicit capability gaps. Runtime parity still requires scenario evidence.

## Platform evidence

| Platform/check | 26.1.2 | 26.2 | 26.3 |
|---|---|---|---|
| Spigot API compilation | Pass | Pass | Pass |
| Spigot live gameplay | Not run | Not run | Not run |
| Native descriptor probe (30 checks) | Pass | Pass | Pass |
| Paper startup, packet binding, commands, restart | Stable74 pass | Stable135 pass | Stable240 pass |
| Folia startup, commands, restart | Stable8 pass | Beta7 pass | Alpha6 pass |
| Folia packet rendering and region movement | Disabled | Disabled | Disabled |
| Multiplexor managed lifecycle/restart | Paper/Folia pass | Paper/Folia pass | Paper/Folia pass |
| Native client attached to managed Paper | 38 checks pass | 38 checks pass | 38 checks pass |
| Fabric/Forge/NeoForge NPC runtime | Deferred | Deferred | Deferred |

Exact server and plugin hashes, commands, and machine-readable direct-test evidence are retained locally under `.qa/platform-tests/evidence-index.json`. Separate Multiplexor reports live under `.qa/multiplexor-workspace/animus-evidence/`; see the [runner guide](multiplexor.md). Reproduce with `qa/platform-smoke.py`; the selected release channel is explicit. Lifecycle checks use synthetic dormant definitions and are separate from gameplay checks. All six owned servers were shut down after verification.

Supplemental Leaf26.3-17/af63114 coverage includes console administration, invalid-reload retention, TOML migration, restart persistence, and an exact-protocol777 client proof. The client decodes player profiles, signed textures, all eight body types, movement, static passengers, despawn, accepted/rejected interactions, block occlusion, and virtual health. No graphical-client rendering, real-target damage, vanilla-mob targeting, controllable mounts, combat parity, bandwidth benchmark, or Citizens performance comparison is claimed. See [implementation evidence](implementation.md) for completed scenario results and runner details.

Benchmark planning must include Citizens default mode, Citizens packet trait mode, and Animus as separate baselines. The pinned source inventory documents Citizens’ existing packet controller; no architecture-only performance claim is justified.
