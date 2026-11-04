# JSON behaviors

The core compiler, interpreter, typed argument binding, file catalog and module-schema checks are integrated. Manual commands and lifecycle wiring are integrated; their final shutdown review and new managed server acceptance run are pending. Runtime10's published server results cover the earlier artifact. Automatic triggers, durable quest state, dialogue UI and platform actions remain separate work.

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
| `/animus behaviors cancel <execution-uuid>` | Cancel that execution and revoke its pending calls. |

Catalog loading waits for module initialization and shares the existing bounded file worker. A successful scan may publish a catalog containing unavailable or invalid sources; inspect the reported counts and field diagnostics. Repeated catalog operations report busy while a scan is pending. Replies return through the sender's owning scheduler. Console execution carries no player identity; player execution captures only the sender UUID, with no implicit world, preview or privileged authority.

Manual execution currently permits one active run per NPC, including a dormant definition. It captures the exact accepted definition and behavior. Definition replacement, deletion or despawn cancels that authority. A no-op NPC file reload also conservatively cancels because it publishes a new definition object. An unchanged accepted behavior retains its identity and continues. Edited behavior bytes require a higher revision; accepted replacement cancels runs using the earlier program. Missing or duplicate behavior sources make the program unavailable. A rejected edit can continue using the preceding valid program within the same process.

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
