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

### Notes

Partial-block movement, Folia rendering, additional-platform NPC implementations, and the browser editor remain unfinished. Packet proofs do not establish feature parity or performance improvements.
