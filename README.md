# Animus

Animus is being built as a Citizens replacement with virtual, packet-rendered NPCs. It currently provides durable NPC **definitions**, administration commands, and a staged Citizens converter. **Packet rendering, navigation, interactions, behaviors, and combat are not implemented.** Installing or importing a definition does not create a visible NPC.

The target releases are Minecraft **26.1.2, 26.2, and 26.3**, using **Java 25**. Spigot/Paper/Folia are first; the inherited Fabric/Forge/NeoForge entry points remain experimental until the server milestones are complete. API compilation is not gameplay certification. See [compatibility](docs/compatibility.md) and [implementation evidence](docs/implementation.md).

## Build

```sh
# Set JAVA_HOME to your JDK 25 installation if necessary.
./gradlew build                       # Core tests, Bukkit API matrix, artifact checks, converter archives
./gradlew :converter:installDist      # Runnable local converter
./gradlew targets                     # Exact configured releases/loaders
./gradlew build -PincludeModDistributions=true  # Experimental mod scaffold builds
```

On this development machine:

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
```

The Bukkit artifact is in `bukkit/build/libs/` and `build/distributions/`. The converter is in `converter/build/install/converter/`, with archives under `converter/build/distributions/`. `build/artifacts.json` describes the Bukkit distribution (or all platform distributions when opted in).

The build automatically uses a sibling/ancestor VolmLib checkout. Override with `-PlocalVolmLibDirectory=/path/to/VolmLib`, `VOLMLIB_DIR`, or `-PuseLocalVolmLib=false` for published dependencies.

## Local migration

Keep private saves and generated bundles outside the repository. The converter accepts at most 16 MiB of UTF-8 YAML, with a maximum nesting depth of 64. It rejects duplicate keys, aliases, unsafe construction, and non-finite values. It never changes the source file.

```sh
converter/build/install/converter/bin/converter preview /path/to/saves.yml /path/to/Animus old_world=new_world
```

Preview writes `imports/<import-id>/` containing:

- `source.yml`: original input, preserved exactly for valid UTF-8.
- `records/<citizens-id>.json`: every original NPC record.
- `reports/<citizens-id>.json`: per-field data classification and exact definition errors.
- `npcs/citizens/citizens-<id>.json`: individually editable candidate definitions.
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

Apply installs definitions with `spawned: false` and imported combat disabled. Unknown traits, Denizen flags/assignments, and unimplemented settings remain preserved. Denizen script definitions are needed for future behavior translation. World remapping currently changes the main spawn location only; preserved waypoint/combat locations still require review. Numeric references remain preserved alongside the ID map; they are not executable Animus references yet.

A repeated identical preview reuses its import ID. Repeated apply reports `already-applied`; other identity/path collisions are rejected. Replacement/duplication import modes are not implemented. A modified candidate fails hash verification; import it first, then edit the installed definitions through the validation service. Rollback refuses to overwrite subsequent edits. It also removes the import receipt, allowing a fresh apply after rollback.

The local CLI cannot open a running server's data directory: both hold the same exclusive lock. Use server commands while online. Archive and transaction files may contain private data; they are not public fixtures.

## Server commands

`animus.status` is available by default; `animus.admin` defaults to operators. File work runs on one worker with a 32-request queue. Replies use the Bukkit scheduler or Folia player/global scheduler. Commands are also usable from the console.

```text
/animus status
/animus list [page]
/animus create <id> <namespace:type> <world> <x> <y> <z> <group> <name...>
/animus inspect <id>
/animus edit <id> {"name":"Updated name"}
/animus clone <id> <new-id>
/animus delete <id>
/animus validate
/animus reload
/animus import preview <filename.yml> [oldWorld=newWorld ...]
/animus import apply <import-id>
/animus rollback <transaction-id>
```

Place server import files in `plugins/Animus/imports/inbox/`. Create/clone produce dormant definitions; spawn/select/despawn commands await the renderer. Edit applies a top-level JSON patch, increments the revision, and preserves unspecified fields. Identity changes and moving a definition require a dedicated future operation. Status explicitly reports the missing renderer.

## Plugin settings

Plugin-wide configuration uses `config.toml`, consistent with other Volmit plugins and mods:

```toml
# Animus plugin settings. NPC and behavior documents use JSON.
statusMessage = "Hello from Animus"
```

Existing TOML files and comments are preserved. On first startup with only a legacy `config.json`, Animus validates it, archives it under `backups/config-<hash>.json`, and writes TOML only if a lossless conversion is possible. The original JSON remains unchanged; subsequent startup reads TOML. Invalid TOML prevents startup rather than falling back to legacy settings. Restart after changing plugin settings.

NPC definitions, behavior packs, skins, migration bundles/reports, state, and generated data use **JSON**. Original import sources are archived in their original format for recovery.

## Editable definitions and recovery

Definitions live at `npcs/<group>/<id>.json`. IDs are globally unique, even across groups; UUIDs are stable and unique. Documents include `schemaVersion` and `revision`. Unknown module data is preserved. Health/cooldowns/execution stacks do not belong in these definitions. Only schema version 1 is supported; future schemas are rejected rather than guessed.

`validate` does not change the registry. `reload` publishes an entire valid snapshot; any invalid document, duplicate identity, path mismatch, or decreasing revision leaves the previous snapshot active. Hand edits can retain the revision, but byte hashes still invalidate older drafts. Startup fails safely on invalid definitions because there is no prior in-memory snapshot after restart. File watching and on-disk last-valid snapshots are future work.

Writes use atomic replacements, a forced transaction journal, directory synchronization, and a backup containing both previous and next contents. After interruption, startup rolls the journal forward before loading definitions. If an outside edit conflicts with recovery, startup stops and preserves `state/pending.json`; retain it, inspect the conflicting file against its `before`/`after` content, and resolve the edit before restarting. Do not delete the journal to bypass recovery.

Transactions are capped at 10,000 documents and a 16 MiB encoded journal. File/directory atomicity and directory synchronization are required; this implementation is verified on local macOS APFS, not network shares or Windows. The process lock coordinates Animus writers. External editors do not participate in that lock; avoid writing during an apply. Revision/hash checks detect existing conflicts but cannot make an arbitrary outside writer transactional.

## Source layout

| Path | Purpose |
|---|---|
| `core/` | Java 25 runtime, immutable definitions, repository, journal store, migration, shared administration |
| `converter/` | Standalone converter using the same core services |
| `bukkit/` | Plugin lifecycle, permissions, bounded command queue, platform reply scheduling |
| `adapters/` | Experimental mod-loader scaffold; no NPC gameplay |
| `gradle/` | Build matrix, packaging, artifact and Multiplexor declarations |
| `qa/` | Reproducible platform scenarios |
| `docs/` | Compatibility, evidence, and remaining work |

Never register an NPC body as a world entity. NMS and future packet adapters belong outside the core. No universal Citizens binary compatibility is promised.
