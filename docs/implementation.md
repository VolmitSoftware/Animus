# Implementation evidence and continuation

Updated 2026-11-04. The complete user plan and live progress record are on the development machine's Desktop as `Animus-Implementation-Plan.md` and `Animus-Implementation-Handoff.md`. No milestone acceptance gate has passed yet.

Implemented source:

- `core/config`: TOML plugin settings using VolmLib, with backed-up lossless legacy JSON migration. NPC/behavior/generated data remains JSON.
- `core/storage`: strict bounded JSON; process lock; atomic files; optimistic hashes; recoverable multi-document journal; backups and inverse transactions.
- `core/npc`: immutable definitions, UUID/ID/revision validation, last-valid registry reloads and draft apply.
- `core/migration`: safe bounded YAML; staged conversion; complete source/record archives; per-field classification; fixture inventories; skin deduplication; collision rejection; explicit dormant application.
- `core/admin`, `bukkit`: shared administration and import services, permissions, bounded filesystem worker, Bukkit/Folia reply scheduler.
- `converter`: local preview/apply/validate/rollback commands using the same core.
- `gradle`: Java 25, exactly three 26.x targets, Bukkit-first default distributions and relocated YAML dependency.

## Automated verification

`JAVA_HOME=<JDK25> ./gradlew build :converter:installDist` passes. This includes 87 core tests, compilation against Spigot 26.1.2/26.2/26.3, shaded artifact checks, and distribution manifest checks.

Meaningful cases cover malformed/ambiguous JSON, YAML duplicate keys, unsafe tags, aliases, nesting and size limits; unsupported traits; missing/invalid locations; same-source repeated previews; changed staged documents; UUID/ID collisions; import rollback/reapply; independent skin references; command edit/clone/delete and restart; unknown-module preservation; file locking; path traversal/symlinks; rollback conflicts; partial journal recovery; malformed journals; recovery conflicts with no partial overwrite; invalid reload retention; stale drafts.

The private 8.1 MiB fixture produced 495 valid definitions, 409 distinct skin documents, a complete 29-trait inventory, and 29 Sentinel field counts. Local explicit apply installed 495 dormant definitions; validation returned 495; rollback completed successfully. An independent archive audit verified all 236,939 classified leaf fields, all 904 bundle hashes, exact source bytes, and preservation of every original trait. This validates storage and conversion only.

## Live smoke evidence

A dedicated local Leaf 26.3-17 (commit af63114) server, Java 25.0.5 on macOS ARM64, passed startup, console status/create/edit/inspect/clone/delete/validate/reload/rollback, invalid-edit last-valid retention, two clean shutdowns, and definition persistence after restart. Existing JSON plugin settings migrated to TOML; the restarted server used TOML. No production server or private NPC collection was used. The scenario uses synthetic dormant definitions and is reproducible with:

```sh
python3 qa/runtime-smoke.py --server-dir /path/to/isolated-server --java /path/to/jdk25/bin/java
```

Provide the server jar, accepted EULA, loopback server properties, and built `plugins/Animus.jar`. Test NPC IDs are `qa-smoke` and `qa-smoke-copy`; use a dedicated server directory. Logs live at `<server-dir>/animus-smoke.log`. This is supplemental Leaf console/lifecycle evidence, not Spigot/Paper/Folia certification, player permission testing, or packet gameplay proof.

## Remaining executable work

1. Complete Milestone 0: native semantic packet transport, independently configured player/non-player presentation, inbound attack/use interception, custom skins, authoritative hitboxes, real-target damage, mob targeting, mounts, controllable bodies, and dragons. Validate each exact platform/release with no registered NPC bodies.
2. Add runtime spatial subscriptions, lifecycle/cleanup, simulation state and ownership scheduling; finish create/select/spawn/despawn management.
3. Resolve mapped NPC references, nested world locations, missing worlds, replacement/duplication import policy, historical combat statistics, and schema migration/last-valid-on-disk behavior.
4. Continue navigation, versioned public API/modules, bounded JSON behaviors and examples, combat/protection, benchmark evidence, additional platforms, and then the arcane_jaspr/Firebase editor.

Keep platform compilation separate from live runtime results. Keep preservation separate from behavior translation. Do not check entire plan items when only part is implemented.

## Known storage limits

Files are limited to 16 MiB and 64 levels of JSON/YAML nesting. Transactions allow at most 10,000 documents and a 16 MiB serialized journal. Rollback creates another backup. Backup retention is currently manual. Atomic moves and directory synchronization are required and have only been exercised on local macOS APFS. An external editor can race the final write after optimistic checks; use offline edits or serialize maintenance with apply.

An invalid edit retains the last valid in-memory snapshot, but that snapshot is not separately persisted for a restart. Unsupported schema versions prevent loading. Staged reports contain field classifications but there is no machine-generated editor schema yet. Imported unknown data is preserved, not automatically executable. File reload is explicit.
