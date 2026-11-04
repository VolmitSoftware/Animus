# Compatibility inventory

This is a development inventory, not a release-parity claim. Counts were produced by the Java converter against the private 495-record reference on 2026-11-04. The private input and generated records are not repository fixtures.

| Capability | Data conversion | Runtime status |
|---|---|---|
| UUID, readable ID, Citizens numeric ID | Converted; explicit ID map | Definition identity supported |
| Name, type, main world/location | Converted; optional world mapping | Editable data only; world/type existence not checked against a running server |
| Skins (489 traits, 409 unique complete documents) | Texture/signature/settings preserved and deduplicated | No skin renderer |
| Equipment (123), inventory (121) | Preserved for review | No equipment/inventory simulation |
| Waypoints (318), look behavior (495) | Preserved for review | No navigation/look runtime; nested worlds not remapped yet |
| Text (2), holograms (91), poses (3) | Preserved for review | No presentation or dialogue runtime |
| Ownership (388) | Preserved for review | Administration uses plugin permissions; per-NPC ownership pending |
| Sentinel (103) | All settings preserved; disabled pending review | No combat module; historical stats not separated yet |
| Denizen flags (24), assignment/trigger records (8 each) | Preserved; requires integration | External scripts required to translate behaviors |
| Mounts (2), controllable (1), dragons (3 bodies) | Preserved for review | High-risk proof outstanding |
| Missing/unknown modules and traits | Preserved as JSON and in original archive | No module loader or executable integration |
| Citizens-dependent plugins | No binary compatibility | Explicit adaptations required |

Entity types: player 483, armor stand 3, ender dragon 3, horse 2, turtle 1, frog 1, llama 1, armadillo 1. All 495 are represented and validate as definitions. This does not establish that any of those bodies can currently render.

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
| skintrait | 489 | Converted to skin documents; rendering not implemented |
| sneak | 1 | Unsupported, preserved |
| sneaking | 1 | Unsupported, preserved |
| spawned | 11 | Requires review; candidate spawned=false |
| targetable | 24 | Unsupported, preserved |
| text | 2 | Requires review |
| triggers | 8 | Requires integration |
| type | 495 | Converted identifier; no body renderer |
| waypoints | 318 | Requires review |

## Every observed Sentinel field

All are preserved as source data and marked `REQUIRES_INTEGRATION` in per-field reports. None is silently interpreted as working Animus combat. The complete original Sentinel object remains in the archive as well as the disabled module configuration.

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

The [Citizens NPC API](https://raw.githubusercontent.com/CitizensDev/CitizensAPI/master/src/main/java/net/citizensnpcs/api/npc/NPC.java) exposes `getEntity()` and entity-backed operations. Animus cannot transparently satisfy those with purely virtual bodies. [Sentinel's documentation](https://github.com/mcmonkeyprojects/Sentinel) is the capability reference for future targeting, guarding, weapons, and integrations. Both upstream references were read on 2026-11-04; an exhaustive upstream source audit remains open.

## Platform evidence

| Platform | 26.1.2 | 26.2 | 26.3 |
|---|---|---|---|
| Spigot API compilation | Pass | Pass | Pass |
| Spigot live gameplay | Not run | Not run | Not run |
| Paper live gameplay | Not run | Not run | Not run |
| Folia scheduling/cleanup | Not run | Not run | Not run |
| Fabric/Forge/NeoForge NPC runtime | Deferred | Deferred | Deferred |

Supplemental smoke coverage: Leaf 26.3-17/af63114 passed console administration, invalid-reload retention, TOML migration, restart persistence, and clean shutdown using synthetic dormant definitions. It does not certify any target above. No runtime entity, combat, navigation, packet, bandwidth, or Citizens performance comparison is claimed.
