# JSON behaviors

The core compiler, interpreter, typed argument binding, file catalog and module-schema checks are integrated. Manual commands and lifecycle wiring are integrated; their final shutdown review and new managed server acceptance run are pending. Runtime10's published server results cover the earlier artifact. Built-in platform actions are described under [built-in capabilities](#built-in-capabilities); they pass local tests and have not yet run on a managed server. Automatic triggers and durable quest state remain separate work.

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

This example requires the [reference Java module](../examples/modules/reference/README.md). Its action returns a JSON message to the engine; it does not send NPC dialogue to players. It is an example of module execution, not one of the planned release behavior packs. For actions that reach the world and players, see [built-in capabilities](#built-in-capabilities).

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

The manual runtime uses its own engine thread and immutable definition/catalog observations. The owner itself grants no world access: built-in actions reach the world only through their bounded owner-thread queue. General Folia NPC rendering/navigation/combat and automatic behavior triggers remain outside this manual module/data gate.


## Built-in capabilities

Behaviors can use the `animus:` capabilities below as soon as the plugin starts, with no Java module installed. They share the registry and schema checks used for modules, and `/animus modules capabilities` lists them with their availability. Unknown argument fields are rejected. Coordinates are absolute and use the NPC's current world.

This greeter faces the player who started it, asks a question, and records that they met:

```json
{
  "schemaVersion": 1,
  "revision": 1,
  "id": "town:greeter",
  "entry": "main",
  "routines": {
    "main": {
      "type": "condition",
      "capability": "animus:player-in-range",
      "arguments": {"radius": 8},
      "then": {
        "type": "sequence",
        "steps": [
          {"type": "action", "capability": "animus:look", "arguments": {"target": "player"}},
          {"type": "action", "capability": "animus:say", "arguments": {"text": "Welcome to town."}},
          {"type": "action", "capability": "animus:choices", "arguments": {
            "id": "greeting",
            "prompt": "Do you need directions?",
            "options": [{"id": "yes", "text": "Yes, please"}, {"id": "no", "text": "No, thanks"}]
          }},
          {"type": "action", "capability": "animus:set-flag", "arguments": {"name": "met-guard", "scope": "relationship"}}
        ]
      }
    }
  }
}
```

### Conditions

| Capability | Arguments | True when |
| --- | --- | --- |
| `animus:has-permission` | `permission` | The triggering player holds the permission. False with no triggering player. |
| `animus:npc-state` | At least one of `spawned`, `minimumHealth`, `maximumHealth` | The NPC matches all of them. A dormant or removed NPC has `spawned` false and health 0. |
| `animus:player-in-range` | `radius` (1-64), optional `anyPlayer` | The triggering player, or any player with `anyPlayer`, is within the radius in the NPC's world. |
| `animus:flag-state` | `name`, optional `scope`, optional `value` (default true) | The flag has that value. An unset flag is false. |
| `animus:quest-state` | `name`, optional `scope`, at least one of `equals`, `atLeast`, `atMost` | The stage matches all of them. An unset quest is stage 0. |

### Actions

| Capability | Arguments | Result |
| --- | --- | --- |
| `animus:say` | `text` (up to 1,024 characters), optional `audience`, `radius` | Sends `Name: text`. |
| `animus:choices` | `id`, `prompt`, `options` (1-8 of `id` and `text`), optional `lifetimeSeconds` (1-600) | Shows clickable options to the triggering player. Completes when shown and does not wait for the answer. |
| `animus:command` | `command` (no leading slash), optional `as`: `console` (default) or `player` | Runs the command. Requires the allow-list below. |
| `animus:navigate` | Either `x`, `y`, `z` with optional `mode` (`walk`, `swim`, `fly`) and `speed` (1-5), or `route` | Starts a route or follows `behaviors/routes/<route>.json`. Completes when requested and does not wait for arrival. The limits in [the runtime guide](runtime.md) apply. |
| `animus:stop-navigation` | None | Stops any route. Reports `stopped`. |
| `animus:look` | Either `x`, `y`, `z`, or `"target": "player"` | Turns the NPC. The target must be within 64 blocks in the same world. |
| `animus:set-appearance` | Any of `name` (up to 16 characters) and `skin` (a skin file hash), or `"reset": true` | Shows a new name or skin on a player body. Viewers receive a fresh body. |
| `animus:set-equipment` | `slot` (`main_hand`, `off_hand`, `head`, `chest`, `legs`, `feet`, `body`), optional `item` | Shows the item, such as `minecraft:iron_sword`. Without `item`, clears the slot. |
| `animus:play-sound` | `sound`, optional `volume` (0-4), `pitch` (0.5-2), `audience`, `radius` | Plays the sound at the NPC for its audience only. |
| `animus:spawn-particle` | `particle`, optional `count` (1-64), `spread` (0-8), `audience`, `radius` | Shows the particle at the NPC to its audience only. Particles that need extra data are rejected. |
| `animus:combat-control` | `operation` (`enable`, `disable`, `target`, `release`), `target` (an entity UUID) for `target` only | Uses the same manual combat adapter as `/animus target`. Combat policy, review flags and protection checks still apply. |
| `animus:set-flag` | `name`, optional `scope`, optional `value` (default true) | Sets the flag. |
| `animus:set-quest-state` | `name`, `value`, optional `scope` | Sets the stage. |
| `animus:advance-quest-state` | `name`, optional `scope`, `by` (default 1), `maximum` | Adds to the stage without passing `maximum`. Reports `value`. |

`audience` is `trigger` (default) or `nearby`; `radius` applies to `nearby`, from 1 to 64 with a default of 16. `scope` is `player` (default), `npc`, `relationship` or `global`. A capability that addresses the triggering player fails with `no-triggering-player` when a behavior has none, as with a console `/animus behaviors run`.

Appearance and equipment changes last until restart or until the NPC's definition changes, and they do not edit the definition. Changing the definition during a run cancels that run, which is why these actions stay out of the file.

Flags and quest stages are currently held in memory, limited to 4,096 values, and lost on restart. They are not yet durable.

### Unavailable and invalid

These are reported differently, so a problem with the file is not confused with a problem with the server:

- **Invalid**: literal arguments fail the capability's checks, and `/animus behaviors validate` reports `behavior-arguments-invalid`. An invalid edit retains the previous accepted program. Arguments filled from variables are checked when the action runs.
- **Unavailable**: the program is accepted but cannot run here, reported as `behavior-capability-unavailable` with the reason. This covers a missing module, a server without the NPC runtime, and a command without a grant.

General NPC runtime is disabled on Folia. The capabilities still appear there, but everything that touches the world or a player reports as unavailable and nothing is scheduled. `animus:flag-state`, `animus:quest-state` and the flag and quest actions keep working.

### Dialogue choices

Each prompt has a random single-use token. A click runs `/animus choice <token> <option>`, which any player may send and which does nothing without a matching prompt. A click is rejected unless the token is live, it comes from the player who was asked, the option exists, the NPC is still spawned and the player is within 64 blocks. Every rejection shows the same message and publishes nothing. A rejected click from the wrong player, on an unknown option or from too far away leaves the prompt open for its rightful answer. A new prompt from the same NPC to the same player replaces the earlier one.

An accepted click fires `VirtualNpcDialogueChoiceEvent`. Behaviors do not yet start from that answer on their own.

### Commands

Commands are denied by default. A behavior can use `animus:command` only when its exact ID or its namespace is listed in `config.toml`. Each run logs the behavior ID, the NPC UUID, the sender and the command. Commands never run in a preview, and `/animus modules action` cannot reach them. Anyone who can edit an allow-listed behavior can run console commands, so grant as narrowly as you can.

```toml
[behaviors.actions]
actionsPerTick = 32
actionsPerNpcPerTick = 4
queuedActions = 256
pendingChoices = 256
choiceLifetimeSeconds = 60
commandBehaviors = ["town:gate/open"]
commandNamespaces = []
```

Accepted ranges are 1-256 actions per tick, 1-64 per NPC per tick and no more than the tick budget, 1-1,024 queued actions, 1-4,096 pending choices and a lifetime of 1-600 seconds. Each list holds at most 256 entries. The listed values other than the example allow-list are the defaults. Restart after changing them.

### Limits and previews

World and player work waits in a bounded queue and runs on the server's owner thread. Past the per-tick or per-NPC budget, an action waits for a later tick. A full queue fails the action as `BUSY`, which ends that run. Waiting is included in the module invocation deadline (`modules.invocationTimeoutMillis`). An action that misses the deadline fails its run and is dropped without running, and unlike a Java module the built-in capabilities are not suspended.

A preview run refuses `command`, `combat-control`, `navigate`, `stop-navigation`, `look`, `set-appearance`, `set-equipment`, `choices` and every flag or quest write. `say`, `play-sound` and `spawn-particle` work in a preview only for the viewer's own `trigger` audience. Conditions still read normally.

## Commands and settings

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

Catalog loading waits for module initialization and shares the existing bounded file worker. A successful scan may publish a catalog containing unavailable or invalid sources; inspect the reported counts and field diagnostics. Repeated catalog operations report busy while a scan is pending. Replies return through the sender's owning scheduler. Console execution carries no player identity. Player execution captures only the sender UUID, which identifies the triggering player and grants no privileged authority.

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
