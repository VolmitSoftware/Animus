# Trusted Java modules

Animus now has a standalone Java 25 API, startup module discovery, a capability registry and asynchronous definition services. Module workflows pass live checks on all nine Paper, Spigot and Folia targets (26.1.2, 26.2 and 26.3): 12 checks each, covering rejected modules, stale edit conflicts, binary replacement after restart and cleanup after plugin disable. JSON behaviors, with manual commands and automatic triggers, pass their own live checks on the same targets. See [JSON behaviors](behaviors.md).

Build with `./gradlew build`. The API, sources and JavaDoc are prepared under `build/api/`; the main artifact is `animus-api-1.0.0-SNAPSHOT.jar`. Its public Java package stays `art.arcane.animus.api` even when the plugin uses a custom project group. No remote Maven publication is configured. Compile extensions against only this API and the JDK, and exclude the API classes from the module JAR. The [reference module](../examples/modules/reference/README.md) provides an executable example and build receipt.

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

Class resolution preserves the parent-owned API and allows JDK classes, the module's own classes and explicitly exported packages from direct declared dependencies. Ordinary access to private Animus, Bukkit, NMS and bundled implementation libraries is unavailable. This is compatibility isolation for trusted Java code, not a security sandbox: Java extensions still have process privileges. Use `ModuleContext.onClose` for resources acquired during enable and keep callbacks nonblocking. Platform integrations require dedicated supported contracts.

Missing or rejected modules do not cause their JSON configuration or their behaviors' stored state to be deleted. Preservation alone does not make that configuration executable. `Capabilities.Invocation.behavior()` names the JSON behavior making a call. A pack that needs a module names it in its `pack.json`. While that module is missing, the pack's behaviors are unavailable, and the manifest, including the module's `configuration`, is kept as written; see [packs](behaviors.md#packs). `/animus behaviors schema` includes the capabilities of every module loaded at that moment. Owner-safe interaction subscriptions and import-converter extensions remain open milestones.

