# Trusted Java modules

Animus now has a standalone Java 25 API, startup module discovery, a capability registry and asynchronous definition services. Module workflows pass live checks on all nine Paper, Spigot and Folia targets (26.1.2, 26.2 and 26.3): 12 checks each, covering rejected modules, stale edit conflicts, binary replacement after restart and cleanup after plugin disable. JSON behaviors, with manual commands and automatic triggers, pass their own live checks on the same targets. See [JSON behaviors](behaviors.md).

Build with `./gradlew build`. The API, sources and JavaDoc are prepared under `build/api/`; the main artifact is `animus-api-1.1.0-SNAPSHOT.jar`. Its public Java package stays `art.arcane.animus.api` even when the plugin uses a custom project group. Compile extensions against only this API and the JDK, and exclude the API classes from the module JAR. The [reference module](../examples/modules/reference/README.md) provides an executable example and build receipt.

Put module JARs directly under `plugins/Animus/modules/` and restart the server. Modules load once in dependency order. The `animus` namespace is reserved for [built-in capabilities](behaviors.md#built-in-capabilities), which remain available when `modules.enabled` is false. Installing, replacing or removing Java binaries requires a server restart; `/animus reload` does not reload Java classes. Each JAR contains generated `animus-module.json` metadata declaring schema version, identity, version, compatible API majors, platforms, dependencies, entrypoint and exported packages. Its declaration must exactly match `TrustedModule.manifest()`.

Plugin settings remain TOML. These defaults apply without rewriting older settings files:

```toml
[files]
maximumOperations = 32
deliveryBudgetMillis = 250

[modules]
enabled = true
maximumInvocations = 64
invocationTimeoutMillis = 1000
deliveryBudgetMillis = 250
```

`files.maximumOperations` accepts 1–128 and counts admitted commands, imports, reads and API operations, including API results whose synchronous listeners are still running. `modules.maximumInvocations` accepts 1–256. Millisecond settings accept 1–60000. Delivery budgets report slow callbacks; they do not interrupt Java code. An invocation deadline requests cancellation, reports a timeout and suspends its module. Dependent capabilities then become unavailable. Restart after changing these settings.

Administrators can inspect and explicitly invoke registered capabilities:

```text
/animus modules status
/animus modules list [page]
/animus modules capabilities [page]
/animus modules action reference:hello <npc-id> {}
/animus modules condition <namespace:name> <npc-id> <JSON object>
```

These commands require `animus.admin`. Dispatch uses an existing NPC UUID, an optional player UUID and bounded JSON arguments. It grants no implicit platform thread ownership or privileged console-command capability, so `animus:command` is unavailable from here. A module fires a trigger it registered with `context.triggers().publish(key, npc, player)`. The call is thread-safe and nonblocking: `OK(true)` means the event was queued, `OK(false)` means the NPC is dormant or has no bound triggers, `BUSY` means it was dropped on overflow, and `NOT_FOUND` means this module did not register that trigger. Where dispatch cannot run, as on Folia, it returns `UNAVAILABLE` with the reason. Trigger events carry no payload. Capability schemas compile once and validate resolved argument objects on the bounded module worker before handler admission. Unsupported schema keywords fail registration explicitly. Types, enum/const, basic numeric/size/object/array constraints and combinators are supported; references, regex, format and full dialect conformance remain unavailable. See [JSON behaviors](behaviors.md) for the current language and limits.

The API exposes immutable handles, definition snapshots with revision/hash tokens, field diagnostics, action/condition/trigger registration, trusted lifecycle contracts and bounded scheduling. Definitions are full JSON replacement drafts. They retain UUID, readable ID/group and expected revision, validate before writing, increment the revision and preserve unmodified unknown module configuration. Stale tokens conflict. API activation is currently unavailable: replacement definitions must be dormant. Ordinary server spawn commands still perform their platform activation checks.

One engine-owned file lane serializes each complete validation → durable commit → repository publication operation across commands, imports and API callers. Result delivery uses two separate workers. Blocking both result workers can consume the shared admission budget, causing `BUSY` responses; it cannot occupy the mutation worker or force storage to close during a commit. API results preserve an already committed outcome. A timeout or cancellation of an enclosing module action does not roll back a definition edit that already committed; inspect its accepted revision when recovering from an interrupted action.

Module handlers use two bounded workers, one deadline worker and two result-delivery workers. Capacity remains reserved until both the actual handler/stage and synchronous result listeners finish, including after timeout. A broken completion stage that throws while attaching its observer does not prove its work ended: the module is suspended and its unobserved work remains admitted until an actual observer reports settlement. Without that callback, the slot and archive can remain retained until process exit. There is no per-module fairness or forced preemption. Lifecycle construction, enable, disable and registered resource cleanup run on a separate management worker. Status reports lifecycle phase/duration and outstanding invocation/result work while a callback is slow.

Shutdown immediately revokes engine admission without waiting for lifecycle code. Queued file work cancels, while an existing durable commit finishes before its store lock is released. Lifecycle shutdown revokes registrations and cleans dependencies/resources in reverse order. Archive bytes remain retained until lifecycle cleanup and every tracked handler/result callback return. One queued cleanup retry backs off from one second to at most thirty seconds; it continues while tracked work remains. `STOPPING` does not mean cleanup finished. Released archive bytes do not imply Java classes were unloaded, and arbitrary module-created threads remain the module author's responsibility.

Discovery accepts at most 64 archives and 4096 directory entries, with 16 MiB compressed per JAR, 32 MiB expanded per JAR, 8 MiB per entry, a 64 KiB manifest, 4096 entries per JAR, 128 MiB expanded across the batch and 16384 aggregate entries. Invalid archives consume budget too. Symlink paths, duplicate entries, bundled Animus/API classes, nested JARs, multi-release archives and external manifest class paths are rejected. Metadata and dependency validation complete before entrypoint initialization; independent compatible modules can still load after a per-module rejection or startup failure.

Class resolution preserves the parent-owned API and allows JDK classes, the module's own classes and explicitly exported packages from direct declared dependencies. Ordinary access to private Animus, Bukkit, NMS and bundled implementation libraries is unavailable. This is compatibility isolation for trusted Java code, not a security sandbox: Java extensions still have process privileges. Use `ModuleContext.onClose` for resources acquired during enable and keep callbacks nonblocking. Platform integrations use only the hooks below.

## API versions and compatibility

`AnimusApi.API_VERSION` is the contract version, currently 1.1.0. The published artifact version follows it. The rules:

- A major version may remove or change contracts. A module declares the majors it supports in `ApiRange`; the loader refuses it on any other major.
- A minor version only adds types and methods the engine implements, such as new `ModuleContext` methods. A module built against 1.0 runs unchanged on every 1.x engine.
- A module that calls a 1.1 addition and also declares support for older 1.x engines must check `AnimusApi.API_VERSION.minor()` at enable time. The constant is read from the running engine.
- Modules never implement `ModuleContext`, `DefinitionService` or the registrars, so the engine can add methods to them in a minor version.

API 1.1 added interaction subscriptions, import converters and integration hooks.

## Interaction notices

`context.onInteraction(listener)` subscribes during enable, at most 8 listeners per module. Each accepted NPC click becomes one immutable `InteractionNotice`: NPC handle, player UUID, `ATTACK` or `INTERACT`, distance, a preview flag and a sequence number. Notices are sent only after the platform's authority checks and the `VirtualNpcInteractionEvent` listeners pass. Listeners cannot cancel the click. Bukkit cancellation stays with that event.

Notices run as module work: the platform owner only queues them. They use the same bounded workers, invocation slots and deadline as actions. A listener that throws fails only that notice. A listener past the invocation deadline suspends its module. Shutdown cancels running listeners through their `Cancellation`. When a module has no free slot, its notice is dropped and counted; `/animus modules status` shows the count. Notices can arrive out of order, so use `sequence` to order them. Paper, Spigot, Folia, Fabric, Forge and NeoForge all send notices. Preview notices (`preview=true`) come only from the opt-in packet and Folia proof bodies, which have no definition.

## Import converters

`context.imports().converter(descriptor, converter)` registers a converter under the module's own namespace. A converter has an id (`namespace:name`), the source plugin's name, `detect` and `convert`. Both run on the bounded module worker with the module deadline. They must not wait on definition service results, because the import command holds the file lane while it waits. Raise `modules.invocationTimeoutMillis` for large files.

```text
/animus import preview <inbox-file> [converter=<namespace:name>]
```

A `.yml` Citizens saves file still uses the built-in Citizens converter. For any other file, or with `converter=`, the named converter runs. Without `converter=`, the one available converter whose `detect` recognizes the file runs. When none or several recognize it, the command lists the reasons. The converter's records go into the same reviewed bundle under `imports/<import-id>/`: `report.json`, per-record reports, and dormant definitions named `<namespace>-<name>-<sourceId>`. Then `/animus import apply`, `import stage` with `reject`, `replace` or `duplicate`, and `import apply-plan` work exactly as for Citizens. The engine sets the id, revision, `spawned=false` and migration provenance. It keeps a valid `uuid` or derives a stable one from the converter and source id, and disables imported combat and patrols until they are reviewed. Records that fail validation are listed in `report.json` and block apply. Module imports cannot bundle skins.

## Integration hooks

`context.integrations()` registers engine-called extension points. One module may provide each point; a second provider fails to enable.

| Point | Called for | Combined with the platform |
|---|---|---|
| `protection` | NPC combat (`NPC_ATTACK`, `PLAYER_ATTACK`, `MOB_ATTACK`, `MOB_CONTROL`) NPC build block changes (`BLOCK_PLACE`, `BLOCK_BREAK`) and doors, fence gates and trapdoors an NPC opens on its route (`BLOCK_USE`) | Both must allow: WorldGuard on Bukkit, nothing on mod servers |
| `economy` | Shop and GUI money prices | Used only where the platform has no provider: always on mod servers, on Bukkit without Vault |

Hooks run synchronously on the platform owner thread that needs the answer, so they must be quick, nonblocking and thread-safe. A throw or a null answer denies the action or refuses the payment. A call past the module invocation deadline suspends the module. A registered provider whose module is suspended or stopped fails closed: protection denies and money prices are refused. Hooks take effect when modules finish enabling after startup. `/animus modules status` lists the bound hooks, converters and hook failures.

## Publishing the API

The build has `maven-publish` for `animus-api` with its sources and JavaDoc jars. It publishes only to the local Maven repository:

```sh
JAVA_HOME=/path/to/java25 ./gradlew :animus-api:publishToMavenLocal
```

This writes `art.arcane.animus:animus-api:1.1.0-SNAPSHOT` to `~/.m2/repository`. A module build on the same machine can then use `mavenLocal()` and `compileOnly 'art.arcane.animus:animus-api:1.1.0-SNAPSHOT'`.

For a public release, push a commit or tag of this repository to GitHub. `jitpack.yml` runs the same task with Java 25 and the JitPack VolmLib. As for VolmLib in Iris, consumers then add `maven { url = 'https://jitpack.io' }` and `compileOnly 'com.github.VolmitSoftware.Animus:animus-api:<tag or commit>'`. Before a release, set the version in `animus-api/build.gradle` to the release value, which must match `AnimusApi.API_VERSION`. Nothing is published automatically.

## Missing modules

Missing or rejected modules do not cause their JSON configuration or their behaviors' stored state to be deleted. Preservation alone does not make that configuration executable. `Capabilities.Invocation.behavior()` names the JSON behavior making a call. A pack that needs a module names it in its `pack.json`. While that module is missing, the pack's behaviors are unavailable, and the manifest, including the module's `configuration`, is kept as written; see [packs](behaviors.md#packs). `/animus behaviors schema` includes the capabilities of every module loaded at that moment.

