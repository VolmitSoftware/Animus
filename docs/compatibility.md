# Import compatibility and runtime capabilities

This is a development inventory, not a release-parity claim. Counts were produced by the Java converter against the private 495-record reference on 2026-11-04. The private input and generated records are not repository fixtures.

| Capability | Data conversion | Runtime status |
|---|---|---|
| UUID, readable ID, Citizens numeric ID | Converted; explicit ID map | Definition identity supported |
| Name, type, main world/location | Converted; optional world mapping | Explicit live activation validates world/type/profile names; longer and non-player nameplates pending |
| Skins (489 traits, 409 unique complete documents) | Texture/signature/settings preserved and deduplicated | Signed textures in persisted runtime and opt-in proof; see artifact-specific evidence |
| Equipment (123), inventory (121) | Preserved for review | No equipment/inventory simulation |
| Waypoints (318), look behavior (495) | Preserved for review | Known locations remapped; native movement/look/JSON routes verified on exact Paper/Spigot targets; imported traits are not executed |
| Text (2), holograms (91), poses (3) | Preserved for review | No presentation or dialogue runtime |
| Ownership (388) | Preserved for review | Administration uses plugin permissions; per-NPC ownership pending |
| Sentinel (103) | All settings preserved; disabled pending review | Manual damage and ordinary-zombie adapter implemented; full combat module pending; statistics kept separate |
| Denizen flags (24), assignment/trigger records (8 each) | Preserved; requires integration | External scripts required to translate behaviors |
| Mounts (2) | Preserved; UUID references resolved | Resolved references apply from definitions on exact Paper/Spigot: passengers for every viewer, across restart. Unresolved ones stay preserved and reported. See [mounted NPCs](runtime.md#mounted-npcs) |
| Controllable (1) | Preserved for review | Trait not executed. A definition opts in with `animus:riding`, with `reviewAccepted: true` when imported. See [riding](runtime.md#riding-an-npc) |
| Dragons (3 bodies) | Preserved; type converted | Packet body with correct facing, phase and part-based hits; no AI. See [presentation](runtime.md#ender-dragon-presentation) |
| Missing/unknown modules and traits | Preserved as JSON and in original archive | Trusted Java startup loader passed runtime10 module/data workflows on all nine exact targets; manual JSON behaviors in the current source await their own managed gate. Unknown imported settings remain unexecuted |

Entity types: player 483, armor stand 3, ender dragon 3, horse 2, turtle 1, frog 1, llama 1, armadillo 1. All 495 are represented and validate as definitions. All eight body types were decoded by an exact26.3 protocol client in the isolated packet proof. Persisted activation passes dedicated restart and viewer scenarios on every exact Spigot/Paper target. Graphical and client frame-time validation remain open; see [runtime limits](runtime.md).

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

[Sentinel's documentation](https://github.com/mcmonkeyprojects/Sentinel) is the capability reference for future targeting, guarding, weapons, and integrations. The [pinned import source inventory](upstream-inventory.md) maps all fixture traits to 101 declared Citizens traits and 69 persisted Sentinel fields, with explicit capability gaps. Runtime parity still requires scenario evidence.

## Platform evidence

| Platform/check | 26.1.2 | 26.2 | 26.3 |
|---|---|---|---|
| Spigot API compilation | Pass | Pass | Pass |
| Spigot baseline lifecycle + native proof | Restart +38 checks pass | Restart +38 checks pass | Restart +38 checks pass |
| Native descriptor probe (30 checks) | Pass | Pass | Pass |
| Paper startup, packet binding, commands, restart | Stable74 pass | Stable135 pass | Stable240 pass |
| Folia startup, commands, restart | Stable8 pass | Beta7 pass | Alpha6 pass |
| Folia general NPC runtime and region movement | Disabled | Disabled | Disabled |
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
| Fabric/Forge/NeoForge NPC runtime | Deferred | Deferred | Deferred |

Exact server and plugin hashes, commands, and machine-readable direct-test evidence are retained locally under `.qa/platform-tests/evidence-index.json`. Separate Multiplexor reports live under `.qa/multiplexor-workspace/animus-evidence/`; see the [runner guide](multiplexor.md). Reproduce with `qa/platform-smoke.py`; the selected release channel is explicit. Lifecycle checks use synthetic dormant definitions and are separate from gameplay checks. All six owned servers were shut down after verification.

Supplemental Leaf26.3-17/af63114 coverage includes console administration, invalid-reload retention, TOML migration, restart persistence, and an exact-protocol777 client proof. The client decodes player profiles, signed textures, all eight body types, movement, static passengers, despawn, accepted/rejected interactions, block occlusion, and virtual health. These historical proof scenarios do not establish real-target damage or vanilla-mob targeting; new runtime/combat scenarios report those independently. No graphical-client rendering, combat parity, bandwidth benchmark or measured performance improvement is claimed. See [implementation evidence](implementation.md) for completed scenario results and runner details.

Benchmark Animus against a reproducible server baseline with the same world and clients. Record visible work, simulation and exact configurations; no architecture-only performance claim is justified.

Mounts, riding and dragon presentation bind to the same exact-version adapter. On Folia the NPC runtime reports them unavailable through `/animus status`, `/animus ride status` and activation errors, and any other Minecraft version refuses to bind. If one of the server methods riding needs is missing, only riding reports unavailable and the rest of the runtime keeps working. `NativeSignatureProbe` checks the native members they use against an exact server jar.

Current runtime details and explicit manual combat limits are in [runtime.md](runtime.md). [Spigot provenance](spigot-verification.md) records official server builds and prior proof artifact hashes. Do not mix baseline proof evidence with later production artifact results.

Latest fully verified runtime artifact: `c89adc35796afe5c33d85cf3e0f853ae2cd0f68123e3c1c16a4e77abd93f9576`, Multiplexor build `run-1793803535545514-80447`. Independent audit passed480 native assertions and81 Folia lifecycle assertions. All nine final ports are closed, final logs have no errors/movement warnings, and owned definitions/checkpoints were removed. The later stationary Folia proof has separate runtime8 evidence below. Earlier native38-check proofs remain tied to their earlier artifact; see [current evidence](implementation.md).

The separate runtime8 stationary-preview artifact is `5bd4a77deef1dc7ce6fa6b0de1809305fe080775aa516a3da6ea37594ceab2cf`, build `run-1793807521311830-32660`, with150 exact Folia checks and an independent passing audit. It does not relabel runtime6's Paper/Spigot results. [Folia preview scope and limits](folia-preview.md) exclude general NPC ownership/transfer, combat, graphical fidelity and performance; actual unowned command-boundary admission, partial four-chunk ownership and transient ticket absence remain unverified.
