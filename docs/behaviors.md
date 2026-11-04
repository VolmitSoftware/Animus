# JSON behaviors

The core compiler, interpreter, typed argument binding, file catalog and module-schema checks are integrated. Manual commands, automatic triggers and lifecycle wiring are integrated; their final shutdown review and new managed server acceptance run are pending. Runtime10's published server results cover the earlier artifact. Durable quest state, dialogue UI and platform actions remain separate work.

Behavior documents are JSON. Plugin-wide budgets are TOML. A behavior named `reference:module-sequence` lives at `plugins/Animus/behaviors/reference/module-sequence.json`:

```json
{
  "schemaVersion": 1,
  "revision": 1,
  "id": "reference:module-sequence",
  "entry": "main",
  "routines": {
    "main": {
      "type": "repeat",
      "count": 3,
      "body": {
        "type": "sequence",
        "steps": [
          {"type": "action", "capability": "reference:hello", "arguments": {}},
          {"type": "wait", "ticks": 20}
        ]
      }
    }
  }
}
```

This example requires the [reference Java module](../examples/modules/reference/README.md). Its action returns a JSON message to the engine; it does not send NPC dialogue to players. It is an example of module execution, not one of the planned release behavior packs.

Each namespace and path segment uses 1–64 lowercase ASCII letters, digits, `_` or `-`; the complete logical ID is limited to 256 characters. Nested paths are supported. Empty path segments, dots, backslashes and reserved Windows device names are rejected. IDs must exactly match their file paths. Secure catalog scanning currently requires a filesystem provider supporting Java `SecureDirectoryStream`; unsupported providers report unavailable rather than using an unsafe fallback.

`revision` is a positive signed 64-bit document revision. Any changed bytes, including whitespace, require a revision greater than the last accepted one. Validation constructs a candidate without publishing it or changing files. Explicit reload publishes accepted programs. Invalid edits retain the last valid program in this process and report the rejected fields. Duplicate IDs select no winner; missing source disables that behavior. An explicit matching revision/hash removal token can forget an absent program. Files and missing-module data are never silently deleted.

The catalog does not yet write durable last-valid behavior checkpoints. A fresh process cannot recover the preceding process's accepted program from a malformed source file. Execution stacks are transient and are not resumed after restart.

The implemented language is:

| Node | Fields and behavior |
| --- | --- |
| `sequence` | `steps`: ordered child nodes. |
| `action` | `capability`, optional object `arguments`, optional `bindings`; await the module result. |
| `condition` | Capability/arguments/bindings plus `then` and optional `else`. |
| `wait` | Integer `ticks` from 1 to 72,000. |
| `repeat` | Literal `count` from 0 to 128 and a `body`. |
| `parallel` | One to eight independent `branches`, then join. |
| `call` | `routine`: a reusable routine in this document. |
| `set` | `variable` and either a typed literal `value` or `{"variable":"source"}`. |

Variables declare `type`, `initial` and optional `scope: "execution"`. Supported types are boolean, signed 64-bit integer, finite decimal and valid UTF-8 string up to 4 KiB. There is no coercion, arbitrary Java expression, string interpolation or implicit access to other players' variables. Persistent NPC/player/relationship/global scopes are not yet implemented.

Parallel branches start with independent copies of the current variables. Nonoverlapping writes merge on join. Two branches writing the same variable fail with a conflict, including identical writes. Completed external side effects cannot be rolled back by that conflict.

Action and condition arguments can bind declared values into existing null placeholders:

```json
{
  "type": "action",
  "capability": "example:record",
  "arguments": {"amount": null},
  "bindings": {"/amount": {"variable": "amount"}}
}
```

This shape requires a module providing `example:record` and a declared `amount` variable. Binding keys are JSON Pointers resolved during compilation. They cannot create keys, extend arrays, overlap, or replace a non-null literal. At execution, the current branch value becomes a JSON scalar of the same type. Unrelated variables are not injected. The final argument object must fit within 64 KiB of UTF-8 and pass the capability's compiled schema before its handler is entered.

Module schemas use an explicitly bounded subset of JSON Schema 2020-12. Types, enum/const, numeric and size constraints, object properties/required/additionalProperties, array items/prefixItems, and basic combinators/conditionals are supported. References, regex, format and other unsupported keywords fail registration with a field diagnostic. This is not full JSON Schema conformance. The same descriptor supplies registry metadata and runtime validation. See [module contracts](modules.md) for invocation and privilege boundaries.

Compiler bounds include 256 KiB of source, 2,048 syntax nodes, depth 32, 32 routines and 32 variables. Argument binding adds at most 32 slots and 16 pointer segments. The catalog defaults to 1,024 retained/observed documents, 4,096 tree entries, depth eight and a 16 MiB aggregate source budget. Two bounded filesystem observations detect changes during reload; external edits after the final observation remain possible. This is not an atomic filesystem snapshot.

Execution budgets independently bound active runs, branches, calls, instructions, lifetime, routine depth, history and trace retention. Reports preserve waits, the failed node's trace and up to 16 field diagnostics. A delivered timeout/cancellation result does not prove a Java handler stopped: its call remains counted until actual handler and result-delivery quiescence. Engine shutdown must keep bounded drain maintenance running for those retired calls. Noncooperative trusted Java work can remain unavailable until restart.

The behavior runtime uses its own engine thread and immutable definition/catalog observations. It grants no Bukkit/Folia world ownership, preview permission or privileged command capability. General Folia NPC rendering/navigation/combat and automatic triggers remain outside this manual module/data gate.

## Automatic triggers

A behavior document can declare `triggers`. An NPC runs them by listing the behavior in its definition's `behaviors` array, which needs a matching NPC definition such as `"behaviors": ["example:greeter"]`:

```json
{
  "schemaVersion": 1,
  "revision": 1,
  "id": "example:greeter",
  "entry": "main",
  "routines": {
    "main": {"type": "action", "capability": "example:wave"},
    "farewell": {"type": "action", "capability": "example:bow"}
  },
  "triggers": [
    {"id": "click", "event": "interaction", "cooldownTicks": 40},
    {"id": "near", "event": "proximity", "phase": "enter", "radius": 8},
    {"id": "away", "event": "proximity", "phase": "leave", "radius": 8, "routine": "farewell"},
    {"id": "clock", "event": "timer", "intervalTicks": 1200}
  ]
}
```

This example requires a module providing `example:wave` and `example:bow`. Every trigger has a unique `id` and an `event`. Optional `routine` starts that routine instead of `entry`. Optional `cooldownTicks` (0 to 72,000) overrides the configured default. A document holds at most 32 triggers. Compilation stops at the first invalid field and reports its path, such as `$.triggers[1].radius`.

| `event` | Other fields | Fires when |
| --- | --- | --- |
| `interaction` | Optional `action`: `interact` (default), `attack` or `any`. | A viewer's hit passes server validation and no listener cancels `VirtualNpcInteractionEvent`. |
| `proximity` | `phase`: `enter` or `leave`. `radius`: above 0, at most 64 blocks. | A viewer crosses that radius. Leaving needs half a block of extra distance. |
| `spawn` | None. | The runtime first presents the NPC. It waits for the first behavior catalog at startup. |
| `despawn` | None. | A spawned NPC is despawned. It runs against the new dormant definition. |
| `damage` | None. | Authoritative NPC health falls. |
| `death` | None. | Authoritative NPC health reaches zero. No despawn trigger follows a death. |
| `waypoint` | Optional `waypoint`: -1 to 4,095. | The NPC arrives at a route point; -1 is a manually requested destination. Without the field, any arrival fires. |
| `timer` | `intervalTicks`: 1 to 72,000. | The interval passes while the NPC is active. |
| `dialogue_choice` | `dialogue`. Optional `choice`. | Something publishes `TriggerEvent.DialogueChoice` for this NPC. |
| `module` | `key`: a `namespace:name` trigger. | The owning module publishes that trigger. |

Interaction, proximity and dialogue executions carry the viewer's UUID, and module executions carry the UUID the module supplied. The other events carry no player. Triggers pass no other event data to the routine.

Only active NPCs fire. Dormant NPCs, which includes applied imports until they are spawned, hold no bindings and fire nothing. Preview presentations never fire triggers. Editing an active NPC's definition is neither a despawn nor a spawn. A player disconnecting fires no `leave`. Deleting an NPC fires no `despawn`, because no definition remains to run against. Stopping the server fires nothing.

Replacing the behavior, editing or despawning the NPC, or making the behavior unavailable cancels its running executions and drops its queued starts. As with manual runs, cancellation cannot undo an action that already happened. A `module` trigger whose module is missing makes the whole behavior unavailable.

Timers, cooldowns and queue lifetimes count behavior-owner ticks, which are about 50 ms with the default `tickMillis`. Cooldowns apply per trigger on each NPC and are not per player: a second player entering a `proximity` radius during the cooldown does not fire it.

Proximity follows viewers through the spatial NPC index. A pass does no scanning when no active NPC has a proximity trigger, and an NPC with no viewer in range is never visited.

Triggers need the general NPC runtime. Where it is unavailable, as on Folia, or when `behaviors.triggers.enabled` is false, `/animus behaviors triggers` shows `UNAVAILABLE` with the reason, nothing runs and module publishes return `UNAVAILABLE` with the same reason. Manual runs still work there.

These defaults live under `[behaviors.triggers]` in `config.toml` and require restart:

```toml
[behaviors.triggers]
enabled = true
eventQueue = 256
eventsPerTick = 32
startsPerTick = 8
maximumQueued = 128
queuePerNpc = 4
queuePerTrigger = 2
queueLifetimeTicks = 100
concurrentTotal = 32
concurrentPerNpc = 2
concurrentPerTrigger = 1
defaultCooldownTicks = 20
npcCooldownTicks = 0
maximumBindings = 4096
maximumTimers = 1024
minimumTimerTicks = 20
maximumProximityRadius = 32
proximityIntervalTicks = 5
proximityViewersPerTick = 16
proximityCandidates = 32
proximityTrackedPerViewer = 64
```

An event that finds its cooldown running is suppressed. An event that finds a full queue is dropped. A start waiting on a concurrency limit stays queued until `queueLifetimeTicks`, then is dropped. All of these are counted and never queue without limit. A timer below `minimumTimerTicks`, a radius above `maximumProximityRadius`, and bindings or timers beyond their maximums stay unbound and are listed as diagnostics. `concurrentTotal` also leaves engine capacity for manual runs when it is below `behaviors.engine.executions`.

Dialogue is a contract for now: nothing in Animus publishes `TriggerEvent.DialogueChoice` yet.


The current administrative commands require `animus.admin`:

| Command | Result |
| --- | --- |
| `/animus behaviors status` | Catalog state/generation, runtime work, retained calls, bounded delivery counts and failures. |
| `/animus behaviors list [page]` | Up to 20 observed sources, availability, accepted/observed revisions and accepted hashes. |
| `/animus behaviors validate` | Scan and compile a candidate without replacing accepted programs or changing source files. |
| `/animus behaviors reload` | Publish validated source observations; preserve the preceding accepted program for invalid edits. |
| `/animus behaviors run <npc-id> <behavior-id>` | Admit manual execution against the captured definition and accepted program. Returns an execution UUID. |
| `/animus behaviors inspect <execution-uuid>` | Inspect active or retained terminal state, source identity, waits, diagnostics and recent traces. |
| `/animus behaviors cancel <execution-uuid>` | Cancel that execution and revoke its pending calls. |
| `/animus behaviors triggers [page]` | Trigger availability and totals, drop reasons, binding problems and up to 20 bindings with last fired, fired, active, queued, suppressed and dropped counts. |

Catalog loading waits for module initialization and shares the existing bounded file worker. A successful scan may publish a catalog containing unavailable or invalid sources; inspect the reported counts and field diagnostics. Repeated catalog operations report busy while a scan is pending. Replies return through the sender's owning scheduler. Console execution carries no player identity; player execution captures only the sender UUID, with no implicit world, preview or privileged authority.

Manual execution permits one active manual run per NPC, including a dormant definition. Trigger-started executions are counted separately under their own budgets. It captures the exact accepted definition and behavior. Definition replacement, deletion or despawn cancels that authority. A no-op NPC file reload also conservatively cancels because it publishes a new definition object. An unchanged accepted behavior retains its identity and continues. Edited behavior bytes require a higher revision; accepted replacement cancels runs using the earlier program. Missing or duplicate behavior sources make the program unavailable. A rejected edit can continue using the preceding valid program within the same process.

Source authority is checked at admission, on owner ticks and immediately before a queued module handler starts. A held handler receives a cancellation token that also observes revocation. Cancellation cannot undo an action already performed or forcibly stop trusted Java code. Retired calls remain counted until their actual handler and delivery work finishes. Execution stacks and bounded terminal history are not resumed after restart.

The following defaults live in `config.toml` and require restart to change. Existing configuration files retain their comments and unknown settings; omitted values use defaults.

```toml
[behaviors]
enabled = true
maximumOperations = 64
operationsPerTick = 8
tickMillis = 50
deliveryBudgetMillis = 250

[behaviors.engine]
executions = 64
fibers = 128
inFlight = 16
workPerTick = 256
totalSteps = 4096
lifetimeTicks = 20000
callDepth = 8
traceEntries = 64
historyEntries = 64

[behaviors.catalog]
maximumDocuments = 1024
maximumEntries = 4096
maximumDepth = 8
maximumDocumentBytes = 262144
maximumAggregateBytes = 16777216
maximumCapabilityChecks = 8192
```

Wait durations count behavior-owner ticks, rather than platform world ticks. The owner schedules its next turn after the configured interval; work and scheduling delays can make wall-clock waits longer. Result callbacks retain their operation admission until they return; slow callbacks are counted and never moved into an unbounded overflow queue.
