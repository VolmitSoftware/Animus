# Changelog

## 0.1.0 - Unreleased

### Added

- Initial Animus project scaffold based on Husk 0.1.2 (`82c39ef`).
- Shared runtime, TOML plugin settings, lossless legacy JSON settings migration, and Animus startup branding.
- Java 25 builds for exactly Minecraft 26.1.2, 26.2, and 26.3; Bukkit-first distribution, with experimental mod-loader scaffolds.
- Gradle wrapper, runtime tests, shaded dependency isolation, artifact verification, and Multiplexor build targets.
- Immutable JSON NPC definitions, revision/hash conflict checks, atomic transaction journal, backups, rollback, and last-valid registry reloads.
- Shared local/server Citizens conversion with staged previews, complete source/record preservation, per-field reports, skin deduplication, identity collision checks, and dormant apply.
- Permission-gated administration, bounded file-worker queue, and platform-aware command replies.
- Storage/import/configuration/command tests and a reproducible live console/restart scenario.
- Versioned Citizens migration with nested world remapping, known NPC reference resolution, JSON Pointer diagnostics and separate combat statistics.
- Virtual state, hitboxes, viewer authority, spatial visibility and bounded navigation foundations.
- Opt-in player/non-player packet proof with signed skins, movement, static passengers, virtual interactions and cancellable preview events; no world-registered NPC bodies.
- Bounded Bukkit terrain capture and asynchronous path diagnostics, with explicit cancellation and no forced chunk loading.
- Exact-codec packet clients, third-party event fixtures, and real Multiplexor build, deployment, lifecycle and player scenarios.

- Persisted NPC spawn/despawn, delivered-chunk visibility, fair bounded viewer updates, selection, signed skins and shared virtual health on exact Spigot/Paper targets.
- Manual supported Bukkit damage with cancellable Animus events and no fabricated real attacker; bounded ordinary-zombie targeting with restoration of its original goals, target and route.
- Official exact Spigot builds and managed restart, production-runtime and combat verification scenarios.

- Durable last-valid definition recovery across restart, atomic checkpoint invalidation on raw/import rollback, read-only converter validation and bounded document traversal.
- Initial kinematic route execution, conservative live collision checks, look commands and reusable JSON waypoint routes; exact managed verification recorded separately.
- Bounded unmounted preview control using semantic native input and heartbeat expiry.

- Automatic behavior triggers for interaction, proximity, spawn, despawn, damage, death, waypoint, timer, dialogue choice and module events, with cooldown, queue and concurrency budgets under `[behaviors.triggers]`.
- `ModuleContext.triggers()` lets modules publish triggers they registered.
- `/animus behaviors triggers [page]` lists bindings, last fired, dropped counts and the most recent start refusal.
- Behavior variables can use `npc`, `player`, `relationship` and `global` scopes with `transient` or `persistent` lifetimes.
- Persistent behavior state under `state/behavior-state/` with coalesced journaled writes, flush on clean shutdown, and `[behaviors.state]` budgets.
- Accepted behaviors keep last-valid checkpoints across restarts, and deleted sources do not return.
- `/animus behaviors trace`, `/animus behaviors state status` and `/animus behaviors state purge`.
- Persisted NPC-on-NPC mounts from native and imported definitions, with cycle and bound validation and exact field diagnostics.
- Opt-in real-player riding through validated interaction, with semantic steering and server-side movement checks.
- Packet-only ender dragon presentation with facing, synced phases and part-based hit validation.
- Built-in `animus:` behavior conditions and actions for dialogue, clickable choices, navigation, look, appearance, equipment, sound, particles, combat control, and flag and quest state.
- Privileged `animus:command`, denied by default, enabled by a TOML allow-list of behavior IDs or namespaces, with every run logged.
- `[behaviors.actions]` settings for action budgets, the queue, pending choices and navigation waits.
- `animus:navigate` can wait for arrival, with a timeout and cancellation.
- Durable flags and quest stages per behavior namespace, covered by `[behaviors.state]` budgets and `state purge`.
- Dialogue clicks start `dialogue_choice` triggers and fire `VirtualNpcDialogueChoiceEvent`, through the player-facing `/animus choice` click target.
- `Capabilities.Invocation.behavior()` in the module API.
- `behavior-trigger-needs-player` validation warning.
- Combat module: target, ignore and avoid rules, line of sight, squads, guarding, pursuit with leash and return, melee and tracked arrows, armor, healing, knockback, drops and respawn. NPCs fight players, mobs and other NPCs.
- WorldGuard protection for combat (`pvp`, `invincible`, `damage-animals`, `mob-damage`). Combat damage is denied when no decision can be made.
- `guard` operation for `animus:combat-control`, and `/animus combat <npc-id> status|engage|disengage|release|target|guard`.
- `[combat]` work limits in `config.toml`.
- Generated JSON Schemas for behaviors, NPC definitions and pack manifests, plus editor completion data, from the live capability registry (`/animus behaviors schema`).
- Behavior packs under `packs/<pack-id>/` with `pack.json`, module requirements, reload without restart and preserved module configuration.
- `/animus behaviors packs`, `/animus behaviors validate [behavior-id]` and tab completion for behavior commands.
- Converter `validate-behaviors` and `schema` commands for offline checks.
- Seven example packs: greeter, patrol, branching dialogue, quest giver, shop, town guard and personal guard.
- Guide for moving imported Denizen flags and assignments to behaviors.
- Definitions declare equipment in all eight slots, hologram text lines, nameplate visibility, poses (sneaking, sleeping, swimming, sitting), armor stand parts and angles, a stored inventory and an owner UUID, with exact field diagnostics.
- Names up to 256 characters. Long or coloured player names, non-player names and hologram lines render in a packet-only text display.
- Citizens equipment, inventory, hologram, sneak, sit, default pose, armor stand, owner and nameplate visibility data convert to definition fields (converter version 4).
- Persisted NPCs run on Folia, each owned by the region that owns its chunk, with explicit handoff between chunks. Triggers, behavior actions, navigation and interactions work there.
- Fabric, NeoForge and Forge servers for 26.1.2, 26.2 and 26.3 run the persisted NPC runtime, commands, behaviors, modules, navigation, mounts, riding and combat as a server-side mod. Worlds are named by dimension; Bukkit world names still resolve.
- Combat greetings and warnings; pattern, item, health, permission, group, effect, combined, fight-event and chat target rules; guard assist.
- Trident, fireball, small fireball, snowball, egg, wither skull, llama spit, spectral arrow and potion weapons; healing potions; damage and armor from equipment; weapon damage map; ammunition.
- WorldGuard pursuit regions; combat scan range up to 256 and speed up to 10, capped by `maximumScanRange` and `maximumSpeed`; `eventsPerTick`.
- NPCs build Sponge `.schem` (v1-3) and structure `.nbt` schematics with `/animus build`, in phase order, with rotation, mirroring, patterns, layers and excavation; creative, donated and container materials; bounded undo; pause, resume, cancel; teams; resume after restart.
- `animus:build` action, `build_finished` and `build_paused` triggers, `[builds]` settings and the `animus.build.donate` permission.
- Builder trait import into `animus:builder`; Constructor traits kept and reported.
- Chest GUIs in the Gloss inventory format, run natively without Gloss.
- Shops with buy, sell and trade grids, limits, stock, discounts, command entries and confirmation, bound with the `animus:shop` module; `animus:open-shop` and `animus:open-gui` actions; `/animus shop` and `/animus gui`.
- Vault economy on Bukkit; money prices fail closed elsewhere.
- dtlTraders importer for shop files, `guis.yml`, trader traits, version 3 stock and `data.dat`.
- Editor relay service (`animus-web/relay`) that connects the web editor to servers with no inbound ports, with Ed25519 server registration, per-browser routing, event streams, limits, an exact origin allowlist and a Cloud Run image.
- Outbound editor relay client for all platforms, configured by the relay keys in `[editor]` of `config.toml` and off by default; server editor identity in `state/editor/identity.json`.
- `animus:hire` NPC module: a chest window to hire a bodyguard for items or money, with durable contracts, renewal and hirer-only dismissal.
- Answer buttons in `animus:choices` take optional `color`, `hover` and `style`; guard settings `catchUpDistance` and `replanDistance`.
- Editor: 3D NPC preview covering every presented body type, the five definition poses, all eight equipment slots, armor stand parts and angles, nameplates and text display lines, with an orbit camera; skins from skin documents (arm model) or a PNG file or URL; `tool/extract_minecraft_world_assets.dart` generates the preview's Minecraft assets from a 26.3 client jar, not committed.
- Web editor server API at `/animus/v1` with pairing, scoped tokens, validate, preview, all-or-nothing applies with conflicts, retry-safe status and undo, imports, spawn and live events; optional embedded listener with operator-supplied TLS (plain HTTP only on 127.0.0.1); `/animus editor pair|tokens|revoke|status`; `[editor]` settings.
- Editor: rig editor with a bone tree, display bone inspector, stage gizmos, keyframe timeline with easing and loop modes, and playback on the shared sampler; Blockbench `.bbmodel` import into rigs, animations and item model sources; workspace zip export and per-rig server files export.

### Changed

- Manual runs stay limited to one per NPC; trigger-started executions are counted separately and use scoped variables, resolving player and relationship variables from the triggering player.
- `/animus behaviors inspect` answers with current waits, conditions and recent steps, and `/animus behaviors trace` lists every active run with its origin. Startup logs every behavior catalog diagnostic.
- Behavior validation rejects invalid literal capability arguments and reports unavailable capabilities separately.
- `/animus modules status` and `capabilities` count built-ins apart from module capabilities.
- Preview executions carry their preview flag through to capabilities.
- Citizens import (converter version 3) converts Sentinel settings into a disabled `animus:combat` module, keeping unsupported settings and statistics separately.
- `animus:combat-control` drives the combat module instead of the manual adapter, and WorldGuard no longer disables combat.
- The ordinary-zombie adapter's hits respect the NPC's combat defense.
- Renamed `ManualBehaviorRuntime` to `BehaviorRuntime`.
- Diagnostics name the allowed fields and values and suggest the nearest match.
- NPC movement and riding use real block collision shapes: bodies stand on slabs, stairs, paths and snow layers, avoid fire, magma and other hazards, and move by their body type (flyers fly, aquatic bodies dive, others walk and swim at the surface). Ridden flyers steer with the rider's pitch.
- The per-step collision budget grows with the body's hull, so an ender dragon or other large NPC no longer stops with `BUDGET_EXHAUSTED`.
- The town-guard and personal-guard example packs use `animus:combat`, and the shop charges the clicking player before giving goods. `animus:combat-control` accepts `"target": "player"`, and `animus:command` replaces `{player}` with the triggering player's UUID.
- `set-appearance` names apply to every body type.
- Behavior world actions run on the NPC's owner; on Folia, work for a player another region owns is handed to that player's scheduler.
- Per-tick runtime limits apply per Folia region tick.
- Riding, mounts and combat on Folia report precise reasons for staying unavailable.

- The single-owner NPC runtime, command set, behavior commands and combat module are shared by every platform.

- Sentinel import maps 51 of 69 fields (converter version 5).

- Build changes ask WorldGuard on Bukkit; mod servers allow all build changes; builds are unavailable on Folia.

- The example `shop` pack uses a shop and a chest GUI instead of commands.
- Citizens converter version 6 maps the `trader` trait.

- Reusable routes live in `routes/<id>.json` instead of `behaviors/routes/`, so route files are no longer reported as invalid behaviors.
- One formatter for all player-facing text on every platform (`&` codes, hex, MiniMessage tags, section codes, unicode escapes).
- NPC speech reads `Name » text`, and answers are coloured buttons with hover text.
- Guards walk to a point behind their charge, replan as it moves, and teleport to catch up across distance or worlds.
- Example packs restyled; personal-guard is hired through `animus:hire`.

### Fixed

- A saddled NPC can be ridden again after a ride ends.
- Hex colours render in holograms and in all mod-server text.
- Combat teleports work beyond 2 blocks.
- Swimming NPCs no longer suspend in deep water while searching for a bottom past the captured terrain.
- Packet proof bodies stay visible after the shared runtime move.
- A navigation job releases its slot before publishing its result.
- Idle NPCs fall when the blocks under them are removed by players, explosions, pistons, builds or build undo, instead of floating.
- Imported Sentinel NPCs with `respawnTime` 0 stay dead; a negative value deletes the NPC on death, undoable with `/animus rollback`; imported guards never target their owner.
- A player who logs out while riding is saved at the NPC's feet instead of beside it.
- Death-trigger routines can speak, play sounds and show particles for the NPC that died.

### Notes

Riding, mounts and combat on Folia, protection integrations on mod servers, and the browser editor remain unfinished. Packet proofs do not establish feature parity or performance improvements.
