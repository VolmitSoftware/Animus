# JSON behaviors

The core compiler, interpreter, typed argument binding, file catalog and module-schema checks are integrated. Manual commands and lifecycle wiring are integrated; their final shutdown review and new managed server acceptance run are pending. Runtime10's published server results cover the earlier artifact. Scoped variables, durable state and last-valid checkpoints are covered by local tests and have not run on a live server. Automatic triggers, dialogue UI and platform actions remain separate work.

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

Each accepted program also gets a generated last-valid checkpoint, so these rules hold across restarts. A malformed or stale source after a restart keeps the last accepted program and reports its exact field errors at startup, in the startup log and through `/animus behaviors validate`. A changed source still needs a higher revision than the checkpoint. A source deleted while the server is stopped stays deleted: its checkpoint is removed at the next catalog load, and a later malformed file at that path starts with nothing to fall back on. A source deleted while running makes the program unavailable and removes its checkpoint the same way. An unusable checkpoint is reported as a warning and ignored; it never stops startup, and a valid source replaces it. See [storage](storage.md) for the files.

Restarts never resume work in progress. Declared persistent variables survive. Execution stacks, execution variables, transient variables, waits and terminal history do not, and an interrupted behavior simply does not continue.

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

Variables declare `type`, `initial` and optional `scope`. Supported types are boolean, signed 64-bit integer, finite decimal and valid UTF-8 string up to 4 KiB. There is no coercion, arbitrary Java expression, string interpolation or implicit access to other players' variables.

```json
"variables": {
  "attempts": {"type": "integer", "initial": 0},
  "mood": {"type": "string", "initial": "calm", "scope": "npc", "lifetime": "transient"},
  "introduced": {"type": "boolean", "initial": false, "scope": "player", "lifetime": "persistent"},
  "trust": {"type": "decimal", "initial": 0.5, "scope": "relationship", "lifetime": "persistent"},
  "phase": {"type": "integer", "initial": 1, "scope": "global", "lifetime": "persistent"}
}
```

| `scope` | One value per | Needs a player |
| --- | --- | --- |
| `execution` (default) | Run of the behavior. Declares no `lifetime`. | No |
| `npc` | Behavior and NPC UUID | No |
| `player` | Behavior and player UUID | Yes |
| `relationship` | Behavior, NPC UUID and player UUID | Yes |
| `global` | Behavior | No |

Every scope other than `execution` requires a `lifetime`: `transient` values last until the server stops, and `persistent` values are stored. Scope and lifetime words are exact, and the compiler names the field it rejects, such as `$.variables.trust.lifetime`. All scopes belong to one behavior ID, so two behaviors never share a variable by name. A variable keeps its `initial` value until something writes it, and an unwritten variable takes no storage.

A run with no player cannot start a behavior declaring a `player` or `relationship` variable; it is refused with `behavior-state-player-required` and the variable's field. Reads see the most recent write from any run. There is no compare-and-set, so the last write wins when runs of the same behavior write the same key. A preview run reads stored values and never writes them.

If a new revision changes a stored variable's type, the stored value is neither converted nor discarded. Runs for that subject are refused with `behavior-state-type-mismatch` until the declaration is restored or the state is purged. Removing a declaration leaves its stored value in place, still counting against the budgets below. Changing only the `lifetime` selects a different key.

Parallel branches start with independent copies of the current variables, including scoped ones, and they keep those copies: a branch sees its own writes and no one else's until the join. Nonoverlapping writes merge on join. Two branches writing the same variable fail with a conflict, including identical writes. Completed external side effects cannot be rolled back by that conflict.

Scoped writes follow the same rule. Outside a parallel node a `set` takes effect immediately. Inside one, it stays private to the branch and becomes visible to other runs only when the outermost join completes, when all the branches' scoped writes apply together or not at all. A conflict, a budget refusal at the join, a cancellation or a failure before the join discards the branches' scoped writes. Writes already applied outside the parallel node are not undone when the run later fails.

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

The manual runtime uses its own engine thread and immutable definition/catalog observations. It grants no Bukkit/Folia world ownership, preview permission or privileged command capability. General Folia NPC rendering/navigation/combat and automatic behavior triggers remain outside this manual module/data gate.


The current administrative commands require `animus.admin`:

| Command | Result |
| --- | --- |
| `/animus behaviors status` | Catalog state/generation, runtime work, retained calls, bounded delivery counts and failures. |
| `/animus behaviors list [page]` | Up to 20 observed sources, availability, accepted/observed revisions and accepted hashes. |
| `/animus behaviors validate` | Scan and compile a candidate without replacing accepted programs or changing source files. |
| `/animus behaviors reload` | Publish validated source observations; preserve the preceding accepted program for invalid edits. |
| `/animus behaviors run <npc-id> <behavior-id>` | Admit manual execution against the captured definition and accepted program. Returns an execution UUID. |
| `/animus behaviors inspect <execution-uuid>` | Inspect active or retained terminal state, source identity, waits, diagnostics and recent traces. |
| `/animus behaviors trace <npc-id-or-uuid>` | Show that NPC's active run with its waits and recent steps, plus its last four finished runs. A deleted NPC's UUID still works while its runs are retained. |
| `/animus behaviors cancel <execution-uuid>` | Cancel that execution and revoke its pending calls. |
| `/animus behaviors state status` | Stored and transient key counts against their budgets, documents waiting to be written, write failures, and each invalid stored file with its exact reason. |
| `/animus behaviors state purge behavior <behavior-id>` | Remove every variable of that behavior. |
| `/animus behaviors state purge npc <npc-id-or-uuid>` | Remove that NPC's `npc` and `relationship` variables across all behaviors. |
| `/animus behaviors state purge player <player-uuid>` | Remove that player's `player` and `relationship` variables across all behaviors. |
| `/animus behaviors state purge relationship <npc-id-or-uuid> <player-uuid>` | Remove the variables of that one pair. |
| `/animus behaviors state purge all confirm` | Remove all stored and transient variables, including invalid or unrecognized stored files. |

Catalog loading waits for module initialization and shares the existing bounded file worker. A successful scan may publish a catalog containing unavailable or invalid sources; inspect the reported counts and field diagnostics. Repeated catalog operations report busy while a scan is pending. Replies return through the sender's owning scheduler. Console execution carries no player identity; player execution captures only the sender UUID, with no implicit world, preview or privileged authority.

Manual execution currently permits one active run per NPC, including a dormant definition. It captures the exact accepted definition and behavior. Definition replacement, deletion or despawn cancels that authority. A no-op NPC file reload also conservatively cancels because it publishes a new definition object. An unchanged accepted behavior retains its identity and continues. Edited behavior bytes require a higher revision; accepted replacement cancels runs using the earlier program. Missing or duplicate behavior sources make the program unavailable. A rejected edit can continue using the preceding valid program within the same process.

Variables are never removed implicitly. Deleting or disabling a behavior, deleting an NPC, or losing a module leaves the state in place, so restoring the same behavior ID or NPC UUID finds it again. Cloning an NPC gives the clone a new UUID and no state. Removal happens only through `state purge`. A running behavior whose state is purged reads its `initial` values from then on and can write new ones.

Each execution keeps a fixed ring of recent steps sized by `traceEntries`, and its report lists current waits, such as `condition:example:allowed@$.routines.main` or `tick:1200@$.routines.main.steps[1]`. `inspect` and `trace` are answered by the behavior owner on its next turn. Java callers use `ManualBehaviorRuntime.observe(uuid)` and `observeNpc(idOrUuid)`. Recording a step only fills ring slots; text and reports are built when someone asks. `inspect(uuid)` and `snapshot()` return the last published views, which are refreshed for two seconds after each such call and whenever a run starts or ends.

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

[behaviors.state]
maximumKeys = 65536
maximumTransientKeys = 65536
maximumValueBytes = 4096
maximumBytes = 16777216
flushMillis = 5000
```

`maximumKeys` and `maximumTransientKeys` cap the stored and transient variables that hold a written value, up to 1,048,576. `maximumValueBytes` caps one string value, up to 4,096 bytes. `maximumBytes` caps the logical size of each lifetime's values and names, up to 256 MiB. One behavior holds at most 128 keys per subject. A write over a budget fails that run with `behavior-state-key-budget`, `behavior-state-byte-budget` or `behavior-state-value-size` naming the variable, and writes nothing. Lowering a budget below existing data keeps the data: overwrites still work, and only growth is refused. `flushMillis` is the longest a changed value waits in memory, from 100 to 300,000.

Wait durations count behavior-owner ticks, rather than platform world ticks. The owner schedules its next turn after the configured interval; work and scheduling delays can make wall-clock waits longer. Result callbacks retain their operation admission until they return; slow callbacks are counted and never moved into an unbounded overflow queue.
