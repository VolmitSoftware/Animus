# Animus

A Manager for all things NPC.

- [Documentation](https://github.com/VolmitSoftware/docs/blob/master/animus.md)

Animus provides durable JSON NPC definitions, administration commands, staged imports, a bounded virtual NPC runtime, JSON behaviors, navigation, combat, shops, chest GUIs and a browser editor on Paper, Spigot and Folia, and on Fabric, NeoForge and Forge servers. Create and import leave NPCs dormant; explicit spawn activates a validated definition. On Folia, riding, mounts, combat and builds are unavailable. The editor runs at https://animus-editor.web.app; see [deployment](dev/deployment.md). See the [runtime guide](https://github.com/VolmitSoftware/docs/blob/master/animus/04-npc-definitions.md) for commands and [implementation evidence](dev/implementation.md) for the exact tested artifacts.

The target releases are Minecraft **26.1.2, 26.2, and 26.3**, using **Java 25**. Fabric, NeoForge and Forge servers run the same runtime as a server-side mod; see [mod servers](https://github.com/VolmitSoftware/docs/blob/master/animus/16-mod-servers.md). API compilation is not gameplay certification. See [compatibility](dev/compatibility.md) and [implementation evidence](dev/implementation.md).

## Build

```sh
# Set JAVA_HOME to your JDK 25 installation if necessary.
./gradlew build                       # Core tests, Bukkit API matrix, artifact checks, converter archives
./gradlew :converter:installDist      # Runnable local converter
./gradlew targets                     # Exact configured releases/loaders
./gradlew buildFabric buildNeoForge buildForge  # Mod jars for every loader and version
./gradlew build -PincludeModDistributions=true  # Bukkit plus every mod jar in build/distributions
```

On this development machine:

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
```

Use the supported Bukkit deliverable in `build/distributions/`: `Animus v0.1.0 [CraftBukkit] 26.1.2-26.3.jar`. The converter is in `converter/build/install/converter/`, with archives under `converter/build/distributions/`. `build/artifacts.json` records the distribution's exact platforms and versions (or all platform distributions when opted in).

The Java extension API, sources and JavaDoc are prepared separately in `build/api/`. See [trusted modules](https://github.com/VolmitSoftware/docs/blob/master/animus/99-java-modules-api.md) and the [reference module](examples/modules/reference/README.md). NPCs can open [shops](https://github.com/VolmitSoftware/docs/blob/master/animus/10-shops.md) and [chest GUIs](https://github.com/VolmitSoftware/docs/blob/master/animus/11-guis-holograms-menus.md) written in Gloss's inventory format; a dtlTraders importer converts existing shops. Module loading passes live checks on every Paper, Spigot and Folia target.

For isolated builds, artifact delivery, managed server checks and client scenarios, follow the [Multiplexor verification guide](dev/multiplexor.md). [Benchmark preparation](dev/benchmark.md) supplies reproducible synthetic workloads; no performance has been measured yet. Native packet checks use exact-version clients attached to Multiplexor-managed servers where its standard client lacks protocol data.

The build automatically uses a sibling/ancestor VolmLib checkout. Override with `-PlocalVolmLibDirectory=/path/to/VolmLib`, `VOLMLIB_DIR`, or `-PuseLocalVolmLib=false` for published dependencies.

## Citizens import

To move a server from Citizens, follow [migrating from Citizens](https://github.com/VolmitSoftware/docs/blob/master/animus/13-migrating-from-citizens.md): put `saves.yml` in `plugins/Animus/imports/` (or leave `plugins/Citizens/saves.yml` after removing Citizens), then run `/animus import citizens preview`, `/animus import citizens apply` and `/animus import citizens accept all`. The rest of this section is the lower-level converter.

Keep private saves and generated bundles outside the repository. The converter accepts at most 16 MiB of UTF-8 YAML, with a maximum nesting depth of 64. It rejects duplicate keys, aliases, unsafe construction, and non-finite values. It never changes the source file.

```sh
converter/build/install/converter/bin/converter preview /path/to/saves.yml /path/to/Animus old_world=new_world
```

Preview writes `imports/<import-id>/` containing:

- `source.yml`: original input, preserved exactly for valid UTF-8.
- `records/<citizens-id>.json`: every original NPC record.
- `reports/<citizens-id>.json`: per-field data classification and exact definition errors.
- `npcs/<world>/citizens-<id>.json`: individually editable candidate definitions, one folder per world after world mapping, such as `npcs/world/`; see [where the files go](https://github.com/VolmitSoftware/docs/blob/master/animus/13-migrating-from-citizens.md#where-the-files-go).
- `skins/<sha256>.json`: deduplicated complete skin traits, including texture and signature.
- `report.json`: counts, trait/Sentinel inventories, UUID mapping, world mapping, and a document hash manifest.

No active definitions are changed during preview. An independent audit is available for a known fixture:

```sh
python3 qa/audit-import.py /path/to/Animus/imports/<import-id> --source /path/to/saves.yml --expected-records 495
```

Inspect the report, then explicitly apply:

```sh
converter/build/install/converter/bin/converter apply /path/to/Animus <import-id>
converter/build/install/converter/bin/converter validate /path/to/Animus
converter/build/install/converter/bin/converter rollback /path/to/Animus <transaction-id>
```

Apply installs definitions with `spawned: false` and imported combat disabled. Unknown traits, Denizen flags/assignments, and unimplemented settings remain preserved. Denizen scripts convert through `/animus import citizens preview` or `converter denizen-preview`; see [migrating from Citizens](https://github.com/VolmitSoftware/docs/blob/master/animus/13-migrating-from-citizens.md#denizen). Version 2 remaps known spawn, waypoint, Sentinel, and sitting locations and resolves known mount/guard references through the UUID map. Unknown references remain preserved with diagnostics. Reviewable transformed traits live in `modules["citizens:review"]`; original records remain unchanged in the archive. Sentinel historical statistics are separated from settings. These data transformations do not execute behavior.

A repeated identical preview reuses its versioned import ID. Version 1 bundles remain readable; version 2 conversion uses a distinct identity. Repeated default apply reports `already-applied`; other identity/path collisions are rejected. A modified candidate fails hash verification; import it first, then edit installed definitions through the validation service. Rollback refuses to overwrite subsequent edits and removes the receipt, allowing a fresh apply afterward.

Explicit application previews support replacement or duplication. Review the generated plan before applying it:

```sh
converter/build/install/converter/bin/converter stage /path/to/Animus <import-id> replace
converter/build/install/converter/bin/converter stage /path/to/Animus <import-id> duplicate town-copy
converter/build/install/converter/bin/converter apply-plan /path/to/Animus <import-id> <application-id>
```

Replacement retains the existing path, UUID and readable ID, increments its revision, and installs it dormant with imported combat disabled. Duplication requires a label and creates fresh deterministic identities, remapping known normalized references within the copied collection. Original archives and unknown review data remain untouched. Staging changes no active definitions. Apply verifies the plan and current hashes; an intervening edit requires a new plan. See [import application plans](https://github.com/VolmitSoftware/docs/blob/master/animus/13-migrating-from-citizens.md) for limits, server commands and rollback details.

The local CLI cannot open a running server's data directory: both hold the same exclusive lock. Use server commands while online. Archive and transaction files may contain private data; they are not public fixtures.

## Server commands

Commands run on VolmLib's Director, like the other Volmit plugins. `/animus` or `/animus help` opens the clickable help; required values are bare words in order, optional values are always `key=value`, quotes keep spaces together, and a JSON object is one value. `animus.status` is available by default; `animus.admin`, `animus.debug` and `animus.debug.mode` default to operators. `/animus debug summary`, `network`, `performance`, `npc <npc>` and `watch` show what Animus measured on this server; `/animus debug mode` logs every per-NPC route, activation and packet diagnostic until restart, which are otherwise summarized every five minutes. [Debug and React metrics](https://github.com/VolmitSoftware/docs/blob/master/animus/17-debug-react-metrics.md) lists the metrics Animus publishes to React. Commands, imports and API edits share one serial file worker, with 32 admitted operations by default. Separate bounded workers deliver API results. Replies use the Bukkit scheduler or Folia player/global scheduler. Commands are also usable from the console. The full list is in [commands and permissions](https://github.com/VolmitSoftware/docs/blob/master/animus/02-commands-permissions.md).

```text
/animus status
/animus list [page=1]
/animus create <id> <namespace:type> <world> <x> <y> <z> <group> "<name>"
/animus select <npc>
/animus spawn <npc>
/animus despawn <npc>
/animus inspect <npc>
/animus edit <npc> {"name": "Updated name"}
/animus clone <npc> <new-id>
/animus delete <npc>
/animus validate
/animus reload
/animus navigate <npc> <x> <y> <z> [mode=walk] [speed=2.0]
/animus navigation status|stop <npc>
/animus look <npc> <x> <y> <z>
/animus import citizens preview [mode=reject] [copy=<label>] [worlds=old:new,...]
/animus import citizens apply | review | accept <spawn,combat,...|all> | rollback
/animus import preview <filename.yml> [worlds=old:new,...]
/animus import apply <import-id>
/animus rollback <transaction-id>
/animus modules status
/animus modules list [page=1]
/animus modules capabilities [page=1]
/animus modules action <namespace:name> <npc> <JSON object>
/animus modules condition <namespace:name> <npc> <JSON object>
```

Place server import files in `plugins/Animus/imports/inbox/`. Create/clone produce dormant definitions. Spawn validates live world/type/name capabilities before saving activation intent. A player can select an NPC or click a visible one, then write `@selected` wherever a command takes an NPC id. Console commands require explicit IDs. Edit applies a top-level JSON patch, increments the revision, and preserves unspecified fields. Identity and file-path changes require a dedicated future operation. Status and inspect distinguish active runtime state, dormant definitions, and unavailable capabilities.

Accepted definitions have durable last-valid recovery across restart. Invalid source edits remain untouched and visible in diagnostics; deleting a source still deletes its NPC. See [storage and rollback](https://github.com/VolmitSoftware/docs/blob/master/animus/12-storage-recovery.md) for recovery, conflict handling and read-only CLI validation.

## Plugin settings

Plugin-wide configuration uses `config.toml`, consistent with other Volmit plugins and mods:

```toml
# Animus plugin settings. NPC and behavior documents use JSON.
statusMessage = "Hello from Animus"
packetProofEnabled = false

[npcs]
enabled = true
viewDistance = 48
maximumVisible = 64
maximumViewers = 256
updatesPerTick = 16
packetsPerTick = 32
pendingSpawnsPerViewer = 8
lookUpdatesPerTick = 32

[files]
maximumOperations = 32
deliveryBudgetMillis = 250

[modules]
enabled = true
maximumInvocations = 64
invocationTimeoutMillis = 1000
deliveryBudgetMillis = 250
```

Existing TOML files and comments are preserved. On first startup with only a legacy `config.json`, Animus validates it, archives it under `backups/config-<hash>.json`, and writes TOML only if a lossless conversion is possible. The original JSON remains unchanged; subsequent startup reads TOML. Invalid TOML prevents startup rather than falling back to legacy settings. Saved changes to plugin settings apply live; see [hand edits apply live](https://github.com/VolmitSoftware/docs/blob/master/animus/04-npc-definitions.md#hand-edits-apply-live). Older TOML files without `[npcs]` use these defaults without rewriting comments. Limits and live behavior are described in the [runtime guide](https://github.com/VolmitSoftware/docs/blob/master/animus/04-npc-definitions.md).

NPC definitions, behavior packs, skins, migration bundles/reports, state, and generated data use **JSON**. Original import sources are archived in their original format for recovery.

Older files use defaults for missing `[files]` and `[modules]` tables. See [module and file-work budgets](https://github.com/VolmitSoftware/docs/blob/master/animus/99-java-modules-api.md) for limits and shutdown semantics. Java modules load from `modules/` at startup; changing a module jar requires a server restart, while `[modules]` settings apply live.

## Experimental packet proof

Set `packetProofEnabled = true` in TOML and restart an isolated Bukkit/Paper test server. The proof requires `animus.admin`, is viewer-only, and expires after two minutes. It does not activate persisted definitions. Folia reports it unavailable; use the Folia preview below, or saved NPCs.

Folia has a separate, limited `/animus foliaproof` stationary player preview. See [Folia preview commands and limits](https://github.com/VolmitSoftware/docs/blob/master/animus/15-folia.md) and the exact verification status in [implementation evidence](dev/implementation.md). Saved NPCs on Folia run on the region that owns their chunk, including riding, mounted groups and combat. See [persisted NPCs on Folia](https://github.com/VolmitSoftware/docs/blob/master/animus/15-folia.md).

```text
/animus proof status
/animus proof spawn minecraft:player [skin-sha256]
/animus proof spawn minecraft:horse
/animus proof move <entity-id> <dx> <dy> <dz>
/animus proof path <entity-id> <dx> <dy> <dz>
/animus proof mount <vehicle-id> [rider-id ...]
/animus proof clear
```

Types: player, armor stand, horse, turtle, frog, llama, armadillo, and ender dragon. Skin hashes reference complete JSON skin documents under `skins/`. The adapter constructs packets without native NPC entity bodies. It supports signed textures, movement/head rotation, static passengers, despawn, and inbound use/attack interception. Static mounts must be detached before movement or interaction; controllable mounts and dragon behavior are unimplemented.

Interactions use server position and view direction, a virtual hitbox, reach/rate/visibility checks, and block line of sight. Preview attacks change only virtual health. `VirtualNpcInteractionEvent` supports cancellation and marks previews explicitly. No real damage or behavior commands run. Disconnect, teleport, world change, respawn, expiry, and shutdown invalidate the viewer session. The proof has bounded viewers, bodies, issued IDs, pending work, and packet queues. This is a technical proof; complete NPC workflows still require their own acceptance tests.

`core/virtual` provides immutable runtime snapshots, hit validation, spatial indexing, and bounded visibility deltas. `core/navigation` provides immutable voxel snapshots and bounded cancellable A* for walking, stepping, upward jumping, falling, swimming, and flight. The diagnostic `proof path` captures at most 8,192 voxels from already loaded chunks, with a shared budget of 256 capture operations per tick and eight outstanding jobs. One worker searches immutable snapshots; it never reads Bukkit worlds. The result reports its capture interval and does not move the body. Terrain can change during or after capture, so execution will require live revalidation. The planner uses whole-block occupancy; it does not prove partial-block support/collision, gap jumps, or executed physics.

## Editable definitions and recovery

Definitions live at `npcs/<group>/<id>.json`. IDs are globally unique, even across groups; UUIDs are stable and unique. Documents include `schemaVersion` and `revision`. Unknown module data is preserved. Health/cooldowns/execution stacks do not belong in these definitions. Only schema version 1 is supported; future schemas are rejected rather than guessed.

`validate` does not change the registry. `reload` publishes an entire valid snapshot; any invalid document, duplicate identity, path mismatch, or decreasing revision leaves the previous snapshot active. Hand edits can retain the revision, but byte hashes still invalidate older drafts. Accepted files have durable last-valid checkpoints: a malformed edit at startup retains the previous accepted definition without changing the invalid bytes. Repair the source and reload before editing through commands again. New malformed files, duplicate identities and corrupt checkpoints fail startup explicitly. Deleting the source removes the NPC. See [storage and recovery](https://github.com/VolmitSoftware/docs/blob/master/animus/12-storage-recovery.md); file watching and automatic schema upgrades remain future work.

Writes use atomic replacements, a forced transaction journal, directory synchronization, and backups of previous/next contents. Large transactions reference immutable payload objects; retain the backup metadata together with `state/objects/transaction-data/`. After interruption, startup rolls the journal forward before loading definitions. If an outside edit or missing payload conflicts with recovery, startup stops and preserves `state/pending.json`. Preserve the journal, backups and objects while resolving the conflict. Do not delete the journal to bypass recovery.

Transactions are capped at 10,000 entries including checkpoints/receipts, a 16 MiB encoded journal, 16 MiB per document and 256 MiB aggregate before/after payloads. File/directory atomicity and directory synchronization are required; this implementation is exercised on local macOS APFS, not network shares or Windows. The process lock excludes another writable store; server mutation commands use one serial worker. External editors do not participate in that coordination; avoid writing during apply. Revision/hash checks detect existing conflicts but cannot make an arbitrary outside writer transactional.

## Source layout

| Path | Purpose |
|---|---|
| `core/` | Java 25 runtime, immutable definitions, repository, journal store, migration, virtual state, bounded navigation, shared administration |
| `animus-api/` | Versioned JDK-only extension contracts; stable public Java packages |
| `converter/` | Standalone converter using the same core services |
| `bukkit/` | Plugin lifecycle, permissions, bounded command queue, platform reply scheduling, isolated packet proof and events |
| `adapters/` | Experimental mod-loader scaffold; no NPC gameplay |
| `gradle/` | Build matrix, packaging, artifact and Multiplexor declarations |
| `qa/` | Reproducible platform scenarios |
| `dev/` | Developer records: compatibility inventory, editor contracts and design, verification evidence |

Never register an NPC body as a world entity. NMS and future packet adapters belong outside the core.
