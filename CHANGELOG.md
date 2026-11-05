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
- Gloss holograms, holographic menus and text animations, drawn with packet-only displays for each player on Bukkit, Folia and mod servers; the `modules["animus:gui"]` NPC binding; `animus:open-gui` takes `kind`; Gloss actions `teleport`, `bossbar`, `broadcast`, `effect`, `particle`, `parallel` and `repeat`; Gloss text animation functions and `time.*`.
- Optional Gloss bridge, and Gloss folder and gloss-web bundle import and export through `/animus gui import|export gloss|file`; Gloss `[RRGGBB]` colour shorthand.
- Editor: chest GUI, shop, holographic menu and hologram editors with in-game window and 3D previews, expression checks and completion, refused features flagged by field; Gloss Web workspace bundle import and export.
- The chest GUI schema accepts teleport, bossbar, broadcast, effect, particle, parallel and repeat.
- Rigs and animations shown as packet-only display bones riding the NPC body, on Bukkit, Folia and the mod loaders; `animus:play-animation` and `animus:stop-animation` actions, the `animation_finished` trigger, automatic idle and walk clips; `[rigs]` settings, `/animus rigs` and `/animus pack build` for turning Blockbench models into a reproducible resource pack shared with the editor (layout in `docs/runtime.md`).
- Editor: pairing from `/animus editor pair` links with pinned server fingerprints, per-server tokens, direct and relay connections with failover and reconnect; live editing of a connected server with server validation, viewer-private previews, apply with revisions, three-way conflict merge, change status after reconnect, undo and refresh from server events; schema-driven NPC inspector, behavior node editor and a JSON code view with completion and inline diagnostics; `tool/fake_server.dart` for local development.
- Web editor previews show candidate NPCs to the pairing player only, as inert packet-only bodies on every platform.
- Drop-in Citizens migration with `/animus import citizens` (preview, apply, review, accept, rollback), with dtlTraders shops and Builder schematics found beside the save; `docs/migrating-from-citizens.md`.
- Editor: local-only browser Citizens converter, byte-identical to the Java converter.
- Citizens waypoints convert to navigation routes (accepted with `/animus import citizens accept patrols`), look-close converts to `animus:look-close`, and Citizens text and commands convert to generated behaviors in `behaviors/citizens/` (commands stay denied until allow-listed).
- Navigation routes take `order` (`sequence`, `pingpong`, `random`, `wander`), per-point `waitTicks`, `guides`, `linkDistance` and `wander`; `animus:set-quest-state` accepts `random`; `animus:say` and `animus:command` fill in `{player_name}`.
- Shopkeepers `save.yml` import through `/animus import citizens`, with Citizens links resolved in either import order, mob shopkeepers as dormant NPCs, and a matching browser converter; `converter shopkeepers-preview` and `shopkeepers-apply`.
- Owned container shops (`owner`, `containers`) with atomic, protection-checked item-for-item trades; shop messages `containerFull` and `containerUnavailable`.
- Editor: import a Citizens saves.yml, Shopkeepers save.yml and config.yml, and dtlTraders files on a paired server through `/v1/imports`, then review, apply, accept categories and roll back; hologram and menu previews play `|animation.id|` text animations; `preview-response` lists the live NPCs a preview hides.
- NPC definitions accept an optional `tags` list (at most 32 unique lowercase labels).
- Editor: NPC browser with search, folders, tags and bulk edits as one undoable change set, plus spawn and despawn on a paired server; typed editors for equipment, skin, route (top-down grid and 3D stage), combat, shop and schema-described modules; a conversation tree for dialogue behaviors.
- `[npcs] packetsPerTick`, `pendingSpawnsPerViewer` and `lookUpdatesPerTick` in `config.toml`.
- NPC definitions, behaviors and shops upgrade to the current `schemaVersion` at startup in one journaled transaction with a rollback receipt; documents from a newer Animus are logged and left unchanged.
- Modules receive NPC interaction notices as bounded module work on every platform, register import converters (`/animus import preview <file> [converter=ns:name]`), and provide protection and economy hooks that fail closed.
- `animus-api` maven-publish setup with sources and javadoc, and `jitpack.yml`.
- Riding, NPC-on-NPC mounts, combat and dragon wing phases on Folia: a mounted group has one owner and hands off in one transfer, rides run on the NPC's region, and cross-region hits go to their owners with a validity check.
- Editing any data file applies it live, with a note block ding and per-key messages for holders of `animus.hotload.notify` (operators by default); invalid hand edits are refused with the exact field error and the last valid version stays live; `[hotload]` settings.

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
- `/animus gui` covers menus, holograms and animations; ids without a namespace resolve in the referring document's namespace.
- `/animus reload` also reloads rigs and animations.
- Pairing codes accept `ws://` relay URLs on loopback for local development.
- Converter version 7 puts imported NPCs in `npcs/<world>-r<x>_<z>/` folders; controllable NPCs import with riding disabled, imported shops wait for review, and imported NPCs stay despawned while Citizens is enabled.
- The Citizens converter is version 8, so every import ID changes.
- The editor uses the server's `namespace:path` ids for holograms and text animations, including Gloss bundles; saved workspaces are moved on load.
- Holographic menus use the server's `namespace:path` ids in the editor and editor API (workspaces migrate to version 3); Gloss bundle ids for chest GUIs and menus match `/animus gui import|export gloss`; behavior JSON completes `animus:open-gui` and `animus:open-shop` ids.
- Under packet budget pressure despawns go first, then moves and spawns; look-close turns, rig bones and timed hologram refreshes wait; `/animus status` shows `presentation=`.
- Finding players near a point uses a chunk index instead of scanning every player.
- Docs give the real editor, relay and `relayUrl` deployment steps.
- Animus API version 1.1.0; compatibility rules in `docs/modules.md`.
- Servers connect to the Volmit editor relay by default (`relayEnabled = true`, `relayUrl` set to the deployed relay).
- `[combat]` limits apply per region tick on Folia.
- Shop messages, price format, economy and the command allow-lists apply without a restart; shops, chest GUIs, menus, holograms and text animations that fail to reload keep their last valid version.

### Fixed

- Combat, builds and container shops refuse when WorldGuard is installed but disabled, at startup or later.
- NPC builds work with WorldGuard installed; NPCs are asked about as non-members, so a region without `build` allowed is closed to them.
- An NPC whose fight ends stops instead of walking the rest of its chase path.
- The editor explains why the server closed its event stream, and a change set still being written at reconnect completes when the server reports it.
- Citizens NPCs with a non-player body and a skin import without the skin and spawn; the skin is kept for review, preview counts only definitions the server can spawn as valid, and a definition that cannot activate is logged once at WARN with its field (converter version 9).
- Patrols are no longer capped at 32: routes take turns under `[navigation]` per-tick budgets, patrols with no player near sleep, a walking patrol standing in the air falls before its first leg, and navigation pressure is reported in `/animus status` and the console.
- Every accepted combat guard fights: `[combat] maximumNpcs` (default 256) limits only guards fighting or scanning, idle guards sleep, and waiting guards are reported.
- Import apply, accept and rollback, `/animus rollback` and editor applies reload the shops, GUIs, holograms, rigs and behaviors they change.
- Rolling back an import no longer warns about removed vehicles; removed generated behaviors leave the catalog with their checkpoints.
- A body packet for a player whose connection just closed no longer becomes a runtime error; runtime errors are logged once at WARN with context.
- The startup migration notice lists every detected source.
- A live NPC replaced by a preview no longer fires triggers for the previewing player or sends them its speech, sounds or particles.
- After a relay failover the editor switches back to the direct path when it recovers.
- Guards no longer freeze after stepping onto a partial block such as a dirt path, farmland or slab; catch-up teleports land on partial blocks, and a stalled guard catches up and logs why once.
- Guards no longer freeze after stepping onto a partial block such as a dirt path, farmland or slab; catch-up teleports land on partial blocks, and a stalled guard catches up and logs why once.
- Logging out while riding on a mod server no longer warns about a closed connection.
- Forge jars ship a `pack.mcmeta`, so Forge no longer reports a missing data pack.
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
