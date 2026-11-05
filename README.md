# Animus

A Manager for all things NPC.

Animus provides durable JSON NPC definitions, administration commands, staged imports, and a bounded virtual NPC runtime on the exact Spigot/Paper targets. Create and import leave NPCs dormant; explicit spawn activates a validated definition. Initial movement, reusable waypoint routes, manual real-target combat and ordinary-zombie targeting have explicit capability limits. Folia rendering, behavior workflows and the full combat module remain unfinished. See the [runtime guide](docs/runtime.md) for commands and [implementation evidence](docs/implementation.md) for the exact tested artifacts.

The target releases are Minecraft **26.1.2, 26.2, and 26.3**, using **Java 25**. Fabric, NeoForge and Forge servers run the same runtime as a server-side mod; see [mod servers](docs/mod-servers.md). API compilation is not gameplay certification. See [compatibility](docs/compatibility.md) and [implementation evidence](docs/implementation.md).

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

The Java extension API, sources and JavaDoc are prepared separately in `build/api/`. See [trusted modules](docs/modules.md) and the [reference module](examples/modules/reference/README.md). NPCs can open [shops](docs/shops.md) and [chest GUIs](docs/gui.md) written in Gloss's inventory format; a dtlTraders importer converts existing shops. Startup module loading and canonical/custom-package verification are complete locally; the new Multiplexor server matrix is in progress.

For isolated builds, artifact delivery, managed server checks and client scenarios, follow the [Multiplexor verification guide](docs/multiplexor.md). [Benchmark preparation](docs/benchmark.md) supplies reproducible synthetic workloads; measurements are still pending. Native packet checks use exact-version clients attached to Multiplexor-managed servers where its standard client lacks protocol data.

The build automatically uses a sibling/ancestor VolmLib checkout. Override with `-PlocalVolmLibDirectory=/path/to/VolmLib`, `VOLMLIB_DIR`, or `-PuseLocalVolmLib=false` for published dependencies.

## Citizens import

To move a server from Citizens, follow [migrating from Citizens](docs/migrating-from-citizens.md): put `saves.yml` in `plugins/Animus/imports/` (or leave `plugins/Citizens/saves.yml` after removing Citizens), then run `/animus import citizens`, `/animus import citizens apply` and `/animus import citizens accept all`. The rest of this section is the lower-level converter.

Keep private saves and generated bundles outside the repository. The converter accepts at most 16 MiB of UTF-8 YAML, with a maximum nesting depth of 64. It rejects duplicate keys, aliases, unsafe construction, and non-finite values. It never changes the source file.

```sh
converter/build/install/converter/bin/converter preview /path/to/saves.yml /path/to/Animus old_world=new_world
```

Preview writes `imports/<import-id>/` containing:

- `source.yml`: original input, preserved exactly for valid UTF-8.
- `records/<citizens-id>.json`: every original NPC record.
- `reports/<citizens-id>.json`: per-field data classification and exact definition errors.
- `npcs/<world>-r<x>_<z>/citizens-<id>.json`: individually editable candidate definitions, one folder per world (after world mapping) and region file, such as `npcs/world-r0_-1/` for `region/r.0.-1.mca`; see [where the files go](docs/migrating-from-citizens.md#where-the-files-go).
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

Apply installs definitions with `spawned: false` and imported combat disabled. Unknown traits, Denizen flags/assignments, and unimplemented settings remain preserved. Denizen script definitions are needed for future behavior translation. Version 2 remaps known spawn, waypoint, Sentinel, and sitting locations and resolves known mount/guard references through the UUID map. Unknown references remain preserved with diagnostics. Reviewable transformed traits live in `modules["citizens:review"]`; original records remain unchanged in the archive. Sentinel historical statistics are separated from settings. These data transformations do not execute behavior.

A repeated identical preview reuses its versioned import ID. Version 1 bundles remain readable; version 2 conversion uses a distinct identity. Repeated default apply reports `already-applied`; other identity/path collisions are rejected. A modified candidate fails hash verification; import it first, then edit installed definitions through the validation service. Rollback refuses to overwrite subsequent edits and removes the receipt, allowing a fresh apply afterward.

Explicit application previews support replacement or duplication. Review the generated plan before applying it:

```sh
converter/build/install/converter/bin/converter stage /path/to/Animus <import-id> replace
converter/build/install/converter/bin/converter stage /path/to/Animus <import-id> duplicate town-copy
converter/build/install/converter/bin/converter apply-plan /path/to/Animus <import-id> <application-id>
```

Replacement retains the existing path, UUID and readable ID, increments its revision, and installs it dormant with imported combat disabled. Duplication requires a label and creates fresh deterministic identities, remapping known normalized references within the copied collection. Original archives and unknown review data remain untouched. Staging changes no active definitions. Apply verifies the plan and current hashes; an intervening edit requires a new plan. See [import application plans](docs/import-applications.md) for limits, server commands and rollback details.

The local CLI cannot open a running server's data directory: both hold the same exclusive lock. Use server commands while online. Archive and transaction files may contain private data; they are not public fixtures.

## Server commands

`animus.status` is available by default; `animus.admin` defaults to operators. Commands, imports and API edits share one serial file worker, with 32 admitted operations by default. Separate bounded workers deliver API results. Replies use the Bukkit scheduler or Folia player/global scheduler. Commands are also usable from the console.

```text
/animus status
/animus list [page]
/animus create <id> <namespace:type> <world> <x> <y> <z> <group> <name...>
/animus select [id]
/animus spawn [id]
/animus despawn [id]
/animus inspect [id]
/animus edit <id> {"name":"Updated name"}
/animus clone <id> <new-id>
/animus delete <id>
/animus validate
/animus reload
/animus navigate <id> <x> <y> <z> [walk|swim|fly] [speed]
/animus navigation <id> [status|stop]
/animus look <id> <x> <y> <z>
/animus import citizens [reject|replace|duplicate <label>] [oldWorld=newWorld ...]
/animus import citizens apply | review | accept <spawn|combat|riding|shops|builder|all> | rollback
/animus import preview <filename.yml> [oldWorld=newWorld ...]
/animus import apply <import-id>
/animus rollback <transaction-id>
/animus modules status
/animus modules list [page]
/animus modules capabilities [page]
/animus modules action <namespace:name> <npc-id> <JSON object>
/animus modules condition <namespace:name> <npc-id> <JSON object>
```

Place server import files in `plugins/Animus/imports/inbox/`. Create/clone produce dormant definitions. Spawn validates live world/type/name capabilities before saving activation intent. A player can select an ID or click a visible NPC; spawn/despawn/inspect/delete accept the selected ID when omitted. Console commands require explicit IDs. Edit applies a top-level JSON patch, increments the revision, and preserves unspecified fields. Identity and file-path changes require a dedicated future operation. Status and inspect distinguish active runtime state, dormant definitions, and unavailable capabilities.

Accepted definitions have durable last-valid recovery across restart. Invalid source edits remain untouched and visible in diagnostics; deleting a source still deletes its NPC. See [storage and rollback](docs/storage.md) for recovery, conflict handling and read-only CLI validation.

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

[files]
maximumOperations = 32
deliveryBudgetMillis = 250

[modules]
enabled = true
maximumInvocations = 64
invocationTimeoutMillis = 1000
deliveryBudgetMillis = 250
```

Existing TOML files and comments are preserved. On first startup with only a legacy `config.json`, Animus validates it, archives it under `backups/config-<hash>.json`, and writes TOML only if a lossless conversion is possible. The original JSON remains unchanged; subsequent startup reads TOML. Invalid TOML prevents startup rather than falling back to legacy settings. Restart after changing plugin settings. Older TOML files without `[npcs]` use these defaults without rewriting comments. Limits and live behavior are described in the [runtime guide](docs/runtime.md).

NPC definitions, behavior packs, skins, migration bundles/reports, state, and generated data use **JSON**. Original import sources are archived in their original format for recovery.

Older files use defaults for missing `[files]` and `[modules]` tables. See [module and file-work budgets](docs/modules.md) for limits and shutdown semantics. Java modules load from `modules/` at startup; changing a binary requires a server restart.

## Experimental packet proof

Set `packetProofEnabled = true` in TOML and restart an isolated Bukkit/Paper test server. The proof requires `animus.admin`, is viewer-only, and expires after two minutes. It does not activate persisted definitions. Folia deliberately reports it unavailable until region-safe world snapshots and ownership transfer are implemented.

Folia has a separate, limited `/animus foliaproof` stationary player preview. See [Folia preview commands and limits](docs/folia-preview.md) and the exact verification status in [implementation evidence](docs/implementation.md). Saved NPCs on Folia run on the region that owns their chunk, including riding, mounted groups and combat. See [persisted NPCs on Folia](docs/folia-runtime.md).

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

`validate` does not change the registry. `reload` publishes an entire valid snapshot; any invalid document, duplicate identity, path mismatch, or decreasing revision leaves the previous snapshot active. Hand edits can retain the revision, but byte hashes still invalidate older drafts. Accepted files have durable last-valid checkpoints: a malformed edit at startup retains the previous accepted definition without changing the invalid bytes. Repair the source and reload before editing through commands again. New malformed files, duplicate identities and corrupt checkpoints fail startup explicitly. Deleting the source removes the NPC. See [storage and recovery](docs/storage.md); file watching and automatic schema upgrades remain future work.

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
| `docs/` | Compatibility, evidence, and remaining work |

Never register an NPC body as a world entity. NMS and future packet adapters belong outside the core.
