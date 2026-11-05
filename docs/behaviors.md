# JSON behaviors

The core compiler, interpreter, typed argument binding, file catalog and module-schema checks are integrated. Manual commands, automatic triggers and lifecycle wiring are integrated; their final shutdown review and new managed server acceptance run are pending. Runtime10's published server results cover the earlier artifact. Scoped variables, durable state, last-valid checkpoints and [built-in capabilities](#built-in-capabilities) are covered by local tests and have not run on a live server.

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

This example requires the [reference Java module](../examples/modules/reference/README.md). Its action returns a JSON message to the engine; it does not send NPC dialogue to players. It is an example of module execution; the [example packs](../examples/packs/README.md) use built-in capabilities only. For actions that reach the world and players, see [built-in capabilities](#built-in-capabilities).

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

The behavior runtime uses its own engine thread and immutable definition/catalog observations. The owner itself grants no world access: built-in actions reach the world only through their bounded owner-thread queue. On Folia that queue hands each action to the region that owns its NPC; Folia combat stays unavailable.

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
| `death` | None. | Authoritative NPC health reaches zero. No despawn trigger follows a death. `say`, `play-sound` and `spawn-particle` still come from where the NPC fell; actions that need a living body are unavailable until it respawns. |
| `waypoint` | Optional `waypoint`: -1 to 4,095. | The NPC arrives at a route point; -1 is a manually requested destination. Without the field, any arrival fires. |
| `timer` | `intervalTicks`: 1 to 72,000. | The interval passes while the NPC is active. |
| `dialogue_choice` | `dialogue`. Optional `choice`. | A viewer's click on that prompt from this NPC passes validation. |
| `module` | `key`: a `namespace:name` trigger. | The owning module publishes that trigger. |

Interaction, proximity and dialogue executions carry the viewer's UUID, and module executions carry the UUID the module supplied. The other events carry no player. Triggers pass no other event data to the routine. Trigger-started runs use the same scoped variables as manual runs, and `player` and `relationship` variables resolve from the triggering player. Every routine a behavior's triggers start shares that behavior's variables. A trigger with no player cannot start a behavior declaring such a variable: that start is counted under `START_FAILED`, and `/animus behaviors triggers` shows the most recent refusal with its exact field. Spawn, despawn, damage, death, waypoint and timer triggers never have a player, so `/animus behaviors validate` warns with `behavior-trigger-needs-player` on each such trigger in that behavior. The behavior is still accepted, since its other triggers may work. A `module` trigger depends on what its module supplies and is not flagged.

Only active NPCs fire. Dormant NPCs, which includes applied imports until they are spawned, hold no bindings and fire nothing. Preview presentations never fire triggers. Editing an active NPC's definition is neither a despawn nor a spawn. A player disconnecting fires no `leave`. Deleting an NPC fires no `despawn`, because no definition remains to run against. Stopping the server fires nothing.

Replacing the behavior, editing or despawning the NPC, or making the behavior unavailable cancels its running executions and drops its queued starts. As with manual runs, cancellation cannot undo an action that already happened. A `module` trigger whose module is missing makes the whole behavior unavailable.

Timers, cooldowns and queue lifetimes count behavior-owner ticks, which are about 50 ms with the default `tickMillis`. Cooldowns apply per trigger on each NPC and are not per player: a second player entering a `proximity` radius during the cooldown does not fire it.

Proximity follows viewers through the spatial NPC index. A pass does no scanning when no active NPC has a proximity trigger, and an NPC with no viewer in range is never visited.

Triggers need the general NPC runtime. On Folia they run on each NPC's region; damage and death triggers need combat, which Folia lacks. Where the runtime is unavailable, or when `behaviors.triggers.enabled` is false, `/animus behaviors triggers` shows `UNAVAILABLE` with the reason, nothing runs and module publishes return `UNAVAILABLE` with the same reason. Manual runs still work there.

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

A valid click on [`animus:choices`](#dialogue-choices) publishes the dialogue event. `dialogue` matches the prompt's `id` and `choice` matches the option's `id`.


## Built-in capabilities

Behaviors can use the `animus:` capabilities below as soon as the plugin starts, with no Java module installed. They share the registry and schema checks used for modules. `/animus modules capabilities` lists them after any module capabilities, and it and `/animus modules status` count the two separately. Unknown argument fields are rejected. Coordinates are absolute and use the NPC's current world.

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
| `animus:command` | `command` (no leading slash), optional `as`: `console` (default) or `player` | Runs the command. Each `{player}` becomes the triggering player's UUID, which vanilla selectors accept, such as `clear {player} minecraft:emerald 1`. Requires the allow-list below. |
| `animus:navigate` | Either `x`, `y`, `z` with optional `mode` (`walk`, `swim`, `fly`) and `speed` (1-5), or `route`. Optional `wait`, and with it `timeoutTicks` (20-12,000, default 1,200). | Starts a route or follows `behaviors/routes/<route>.json`. Completes when requested unless `wait` is true; see [walking a patrol](#walking-a-patrol). The limits in [the runtime guide](runtime.md) apply. |
| `animus:stop-navigation` | None | Stops any route. Reports `stopped`. |
| `animus:look` | Either `x`, `y`, `z`, or `"target": "player"` | Turns the NPC. The target must be within 64 blocks in the same world. |
| `animus:set-appearance` | Any of `name` (up to 16 characters) and `skin` (a skin file hash), or `"reset": true` | Shows a new name on any body, or a skin on a player body. Viewers receive a fresh body. |
| `animus:set-equipment` | `slot` (`main_hand`, `off_hand`, `head`, `chest`, `legs`, `feet`, `body`), optional `item` | Shows the item, such as `minecraft:iron_sword`. Without `item`, clears the slot. Equipment declared in the definition returns after restart or a definition change. |
| `animus:play-sound` | `sound`, optional `volume` (0-4), `pitch` (0.5-2), `audience`, `radius` | Plays the sound at the NPC for its audience only. |
| `animus:spawn-particle` | `particle`, optional `count` (1-64), `spread` (0-8), `audience`, `radius` | Shows the particle at the NPC to its audience only. Particles that need extra data are rejected. |
| `animus:combat-control` | `operation` (`enable`, `disable`, `target`, `release`, `guard`), `target` (an entity or NPC UUID, or `player` for the triggering player): required for `target`, optional for `guard`, refused otherwise | Drives the [combat module](combat.md#behaviors-and-commands). Needs enabled combat settings; protection still applies. |
| `animus:open-shop` | `shop` (a shop id such as `town:baker`) | Opens the [shop](shops.md) for the triggering player. Reports `delivered` 0 when they are offline. |
| `animus:open-gui` | `gui` (a chest GUI id such as `town:menu`) | Opens the [chest GUI](gui.md) for the triggering player. |
| `animus:set-flag` | `name`, optional `scope`, optional `value` (default true) | Sets the flag. |
| `animus:set-quest-state` | `name`, `value`, optional `scope` | Sets the stage. |
| `animus:advance-quest-state` | `name`, optional `scope`, `by` (default 1), `maximum` | Adds to the stage without passing `maximum`. Reports `value`. |

`audience` is `trigger` (default) or `nearby`; `radius` applies to `nearby`, from 1 to 64 with a default of 16. `scope` is `player` (default), `npc`, `relationship` or `global`. A capability that addresses the triggering player fails with `no-triggering-player` when a behavior has none, as with a console `/animus behaviors run`.

Appearance and equipment changes last until restart or until the NPC's definition changes, and they do not edit the definition. Changing the definition during a run cancels that run, which is why these actions stay out of the file.

### Flags and quests

Flags and quest stages are durable behavior state. `scope` defaults to `player`, so a quest follows the player from NPC to NPC. Use `relationship` for a value between one player and one NPC, `npc` for one NPC, and `global` for the whole pack.

They belong to the behavior's namespace rather than to one behavior: `town:greeter` and `town:quest/gatekeeper` see the same `main` quest, and `harbor:greeter` sees its own. A `name` uses lowercase letters, digits, `_` or `-`, up to 64 characters. Flags and quests have separate names, and calling one directly with `/animus modules action` is refused with `no-behavior-namespace`.

`[behaviors.state]` budgets apply, and a write over a budget fails its run with the same diagnostics as a variable. One namespace holds at most 128 flags and 128 quests per subject. Values are stored as `animus:flags/<namespace>` and `animus:quests/<namespace>`, which behaviors cannot use as their own IDs. To remove them, use `/animus behaviors state purge`, for example `state purge behavior animus:quests/town`; the `npc`, `player`, `relationship` and `all` forms reach them too.

### Walking a patrol

With `"wait": true`, `animus:navigate` completes on arrival, so steps run one after another:

```json
{"type": "repeat", "count": 8, "body": {"type": "sequence", "steps": [
  {"type": "action", "capability": "animus:navigate", "arguments": {"x": 10, "y": 64, "z": 4, "wait": true}},
  {"type": "wait", "ticks": 40},
  {"type": "action", "capability": "animus:navigate", "arguments": {"x": 16, "y": 64, "z": 4, "wait": true}},
  {"type": "wait", "ticks": 40}
]}}
```

The run fails with a diagnostic if the route ends any other way:

| Code | Cause |
| --- | --- |
| `navigation-blocked`, `navigation-failed` | Something blocks the path, or no path exists. |
| `navigation-unavailable` | Terrain is not loaded. A waiting route stops rather than retrying, and the same applies to a failed route read. |
| `navigation-cancelled` | Another route replaced this one, the route was stopped, or the NPC was despawned or replaced. |
| `navigation-timeout` | `timeoutTicks` passed; the route stops and the message gives its last status. Ticks are server ticks. |
| `navigation-invalid` | A reusable route point is out of range, or the route has `loop` set, which never finishes. |

Cancelling the run stops the route. With a reusable `route`, it completes at the last point.

A waiting action holds one of `behaviors.engine.inFlight` and one of `modules.maximumInvocations` while the NPC walks, and other actions wait when `inFlight` is used up. `behaviors.actions.navigationWaits` caps waiting actions at 8 by default so walkers cannot starve everything else; past that, the action fails as `navigation-wait-capacity`. Raise it along with `inFlight` for more walkers.

### Unavailable and invalid

These are reported differently, so a problem with the file is not confused with a problem with the server:

- **Invalid**: literal arguments fail the capability's checks, and `/animus behaviors validate` reports `behavior-arguments-invalid`. An invalid edit retains the previous accepted program. Arguments filled from variables are checked when the action runs.
- **Unavailable**: the program is accepted but cannot run here, reported as `behavior-capability-unavailable` with the reason. This covers a missing module, a server without the NPC runtime, and a command without a grant.

On Folia, actions run on the region that owns the NPC; an NPC waiting for its chunk to load refuses them as unavailable. Messages, sounds, particles, choices and commands for a player another region owns are handed to that player's scheduler and not awaited, so a rejected player command is not reported there. `nearby` audiences include only players on the NPC's region, and `animus:has-permission` and looking at a player fail with `player-in-other-region` for a player on another region. `animus:combat` is unavailable. See [persisted NPCs on Folia](folia-runtime.md).

### Dialogue choices

Each prompt has a random single-use token. A click runs `/animus choice <token> <option>`, which any player may send and which does nothing without a matching prompt. A click is rejected unless the token is live, it comes from the player who was asked, the option exists, the NPC is still spawned and the player is within 64 blocks. Every rejection shows the same message and publishes nothing. A rejected click from the wrong player, on an unknown option or from too far away leaves the prompt open for its rightful answer. A new prompt from the same NPC to the same player replaces the earlier one.

An accepted click starts any matching [`dialogue_choice` trigger](#automatic-triggers) with the clicking player, then fires `VirtualNpcDialogueChoiceEvent`. An NPC needs the behavior in its `behaviors` list, and the trigger's cooldown and queue limits apply. This pairs a question with its answers:

```json
"routines": {
  "main": {"type": "action", "capability": "animus:choices", "arguments": {"id": "greeting", "prompt": "Need directions?", "options": [{"id": "yes", "text": "Yes"}, {"id": "no", "text": "No"}]}},
  "directions": {"type": "action", "capability": "animus:say", "arguments": {"text": "The market is north."}}
},
"triggers": [{"id": "asked", "event": "dialogue_choice", "dialogue": "greeting", "choice": "yes", "routine": "directions"}]
```

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
navigationWaits = 8
```

Accepted ranges are 1-256 actions per tick, 1-64 per NPC per tick and no more than the tick budget, 1-1,024 queued actions, 1-4,096 pending choices and a lifetime of 1-600 seconds. Each list holds at most 256 entries, and `navigationWaits` accepts 1-64. The listed values other than the example allow-list are the defaults. Restart after changing them.

### Limits and previews

World and player work waits in a bounded queue and runs on the server's owner thread. Past the per-tick or per-NPC budget, an action waits for a later tick. A full queue fails the action as `BUSY`, which ends that run. Waiting is included in the module invocation deadline (`modules.invocationTimeoutMillis`), except for a navigate with `wait`, which has its own timeout. An action that misses the deadline fails its run and is dropped without running, and unlike a Java module the built-in capabilities are not suspended.

A preview run refuses `command`, `combat-control`, `navigate`, `stop-navigation`, `look`, `set-appearance`, `set-equipment`, `choices`, `open-shop`, `open-gui` and every flag or quest write. `say`, `play-sound` and `spawn-particle` work in a preview only for the viewer's own `trigger` audience. Conditions still read normally.

## Packs

A pack keeps related behaviors together under `packs/<pack-id>/`:

```text
packs/town/
  pack.json
  behaviors/town/greeter.json
  behaviors/town/gate/open.json
```

A behavior in a pack follows the same rules as one under `behaviors/`: `town:greeter` lives at `packs/town/behaviors/town/greeter.json`, and its ID must match that path. An ID may live in one place only. The same ID in two packs, or in a pack and under `behaviors/`, selects no winner and both are unavailable. Moving a behavior from `behaviors/` into a pack, or between packs, keeps its ID and its stored state. The pack ID and its namespaces are separate names, so one pack can hold several namespaces.

```json
{
  "schemaVersion": 1,
  "id": "town",
  "name": "Town",
  "version": "1.2.0",
  "description": "Greeters and gate keepers for the town.",
  "modules": {
    "gatekeeper": {"required": true, "configuration": {"openTicks": 200}}
  }
}
```

`id` must match the directory name. `name`, `version` and `description` are for people. `modules` names the Java modules the pack uses; `required` is true unless set to false, and `configuration` is the module's own object, kept as written. Animus reads `pack.json` and never rewrites it.

`/animus behaviors reload` loads new, changed and removed packs without a restart, under the revision rules above. A pack directory without `pack.json` is reported as `pack-manifest-missing`, and an unreadable or mismatched one as `pack-manifest-invalid`. Neither loads new behaviors from that pack, while behaviors it had already accepted keep their last valid program, as with any invalid edit.

When a required module is not installed or not active, every behavior in the pack stays accepted but is unavailable, reported as `pack-module-unavailable`, and its manifest, configuration, sources and stored state stay untouched. Installing the module and reloading makes them available again with no edits. An optional module only affects the behaviors that call its capabilities, which are unavailable on their own. Only `pack.json` and `behaviors/` are read; anything else in a pack, such as example NPC definitions, is left alone. See the [example packs](../examples/packs/README.md).

## Schemas and editor support

`/animus behaviors schema` writes generated files under `plugins/Animus/schemas/`:

| File | Contents |
| --- | --- |
| `behavior.schema.json` | JSON Schema 2020-12 for behavior documents, including every capability registered now, with its arguments. |
| `npc.schema.json` | NPC definitions and their built-in `modules` settings. |
| `pack.schema.json` | Pack manifests. |
| `completions.json` | Capability IDs with kind, description and each argument's type, range, allowed values and whether it is required; trigger events and their fields; node types; variable types, scopes and lifetimes. |

They are made from the same registry the server uses to validate, so they list built-in capabilities and those of the Java modules loaded now. Run the command again after adding a module. Each file is limited to 2 MiB and the set to 4 MiB; a larger result is refused and nothing is written. The converter's `schema` command writes the same files with built-in capabilities only.

Point an editor at them by path. In Visual Studio Code, for example:

```json
"json.schemas": [
  {"fileMatch": ["**/behaviors/**/*.json"], "url": "./schemas/behavior.schema.json"},
  {"fileMatch": ["**/npcs/**/*.json"], "url": "./schemas/npc.schema.json"},
  {"fileMatch": ["**/packs/*/pack.json"], "url": "./schemas/pack.schema.json"}
]
```

The schema catches most mistakes as you type, and the compiler still decides. Some rules need the whole document or a capability's own checks and are left to `/animus behaviors validate`: that `entry`, `call` and trigger routines exist, that `set` names a declared variable of the same type, that trigger IDs are unique, a flag or quest `name`'s characters, a command's leading slash and repeated choice option IDs. Arguments filled by `bindings` are checked when the action runs.

Diagnostics say what a position accepts. A misspelled field lists the allowed fields and suggests the nearest one, such as `unsupported field for action; allowed: type, capability, arguments, bindings; did you mean capability?`, and the same holds for node types, trigger events, routines, variables, argument names and capability IDs. Value problems name the expected type, range, choices or alternatives.

## Checking behaviors offline

The converter checks a directory without a server, using the same catalog, compiler and built-in capability checks:

```sh
converter/build/install/converter/bin/converter validate-behaviors /path/to/Animus
converter/build/install/converter/bin/converter validate-behaviors examples/packs/shop
converter/build/install/converter/bin/converter schema /path/to/output
```

The directory may be a data folder, with `behaviors/`, `packs/` and `npcs/`, or a single pack. It reads only: no checkpoints, locks or state are written. It reports each problem with its file, code and field, checks NPC definitions under `npcs/` and in packs, and exits with status 1 when anything is invalid. It cannot check:

- Java module capabilities, their arguments and their triggers, since only built-in capabilities are known offline. Each one used is listed.
- Whether a pack's required modules are installed.
- The command allow-list, and the platform: on Folia combat is unavailable and some player actions depend on which region owns the player.
- Revisions against programs a server already accepted. A changed file there still needs a higher revision.
- Arguments filled from variables.
- Worlds, coordinates, reusable routes, skins, permissions, sounds, particles, items and whether a player is present.

## Moving imported flags and assignments

Imported NPCs keep their Denizen flags, assignments and trigger records as preserved data, and Animus does not run or translate them. To bring that logic across, write a behavior and add it to the NPC's `behaviors` list. The concepts map like this:

| Imported | Behavior |
| --- | --- |
| Assignment script | A behavior document listed in the NPC's `behaviors`. |
| Click trigger | An `interaction` trigger. Damage, proximity and spawn triggers map to `damage`, `proximity` and `spawn`. |
| Chat trigger | `animus:choices` with a `dialogue_choice` trigger per answer. Free-typed chat has no equivalent. |
| Player flag, true or false | `animus:set-flag` and `animus:flag-state` with `"scope": "player"`. |
| NPC flag | The same with `"scope": "npc"`. A flag about one player and one NPC uses `"scope": "relationship"`. |
| Server flag | The same with `"scope": "global"`. |
| Numbered quest step | `animus:set-quest-state`, `animus:advance-quest-state` and `animus:quest-state`. |
| Other flag values | A declared variable with the same scope and `"lifetime": "persistent"`. |
| `narrate` or `chat` | `animus:say`. |
| `execute` | `animus:command`, behind the allow-list. |
| Expiring flags, lists, maps, tags and arbitrary script commands | No equivalent. |

Existing flag values are not copied into Animus state; behaviors start from unset flags. Flags and quest stages belong to the behavior's namespace, so every behavior of one pack shares them.

A click-and-answer dialogue:

```json
{
  "schemaVersion": 1,
  "revision": 1,
  "id": "harbor:ferryman",
  "entry": "main",
  "routines": {
    "main": {"type": "sequence", "steps": [
      {"type": "action", "capability": "animus:look", "arguments": {"target": "player"}},
      {"type": "action", "capability": "animus:choices", "arguments": {
        "id": "ferry", "prompt": "Need a crossing?",
        "options": [{"id": "yes", "text": "Yes, please"}, {"id": "no", "text": "No, thanks"}]
      }}
    ]},
    "cross": {"type": "action", "capability": "animus:say", "arguments": {"text": "Climb aboard. We leave at dusk."}},
    "decline": {"type": "action", "capability": "animus:say", "arguments": {"text": "Suit yourself."}}
  },
  "triggers": [
    {"id": "click", "event": "interaction", "cooldownTicks": 40},
    {"id": "yes", "event": "dialogue_choice", "dialogue": "ferry", "choice": "yes", "routine": "cross"},
    {"id": "no", "event": "dialogue_choice", "dialogue": "ferry", "choice": "no", "routine": "decline"}
  ]
}
```

A quest whose step was a numbered player flag:

```json
{
  "schemaVersion": 1,
  "revision": 1,
  "id": "harbor:nets",
  "entry": "main",
  "routines": {
    "main": {"type": "condition", "capability": "animus:quest-state", "arguments": {"name": "nets", "equals": 0},
      "then": {"type": "sequence", "steps": [
        {"type": "action", "capability": "animus:say", "arguments": {"text": "Bring me three nets from the shed, would you?"}},
        {"type": "action", "capability": "animus:set-quest-state", "arguments": {"name": "nets", "value": 1}}
      ]},
      "else": {"type": "condition", "capability": "animus:quest-state", "arguments": {"name": "nets", "atMost": 1},
        "then": {"type": "action", "capability": "animus:say", "arguments": {"text": "The shed is behind the inn."}},
        "else": {"type": "action", "capability": "animus:say", "arguments": {"text": "Thanks again for the nets."}}
      }
    }
  },
  "triggers": [{"id": "click", "event": "interaction"}]
}
```

Another behavior in the `harbor` namespace, such as the shed keeper's, can move the quest on with `{"type": "action", "capability": "animus:advance-quest-state", "arguments": {"name": "nets", "maximum": 2}}`.

Player, NPC and server flags with a permission check, as a gate keeper:

```json
{
  "schemaVersion": 1,
  "revision": 1,
  "id": "harbor:gatekeeper",
  "entry": "main",
  "variables": {
    "greeting": {"type": "string", "initial": "Halt.", "scope": "npc", "lifetime": "persistent"}
  },
  "routines": {
    "main": {"type": "condition", "capability": "animus:flag-state", "arguments": {"name": "lockdown", "scope": "global"},
      "then": {"type": "action", "capability": "animus:say", "arguments": {"text": "The harbor is closed."}},
      "else": {"type": "condition", "capability": "animus:has-permission", "arguments": {"permission": "harbor.dock"},
        "then": {"type": "sequence", "steps": [
          {"type": "action", "capability": "animus:say", "arguments": {"text": null}, "bindings": {"/text": {"variable": "greeting"}}},
          {"type": "action", "capability": "animus:set-flag", "arguments": {"name": "visited-harbor"}},
          {"type": "action", "capability": "animus:set-flag", "arguments": {"name": "busy", "scope": "npc"}}
        ]},
        "else": {"type": "action", "capability": "animus:say", "arguments": {"text": "Dock workers only."}}
      }
    },
    "lock": {"type": "action", "capability": "animus:set-flag", "arguments": {"name": "lockdown", "scope": "global"}}
  },
  "triggers": [
    {"id": "click", "event": "interaction"},
    {"id": "hit", "event": "damage", "routine": "lock", "cooldownTicks": 200}
  ]
}
```

`visited-harbor` uses the default player scope, `busy` belongs to this NPC, and `lockdown` is shared by the whole `harbor` namespace. The NPC-scoped `greeting` variable stands in for a flag holding text.

## Commands and settings

The current administrative commands require `animus.admin`:

| Command | Result |
| --- | --- |
| `/animus behaviors status` | Catalog state/generation, runtime work, retained calls, bounded delivery counts and failures. |
| `/animus behaviors list [page]` | Up to 20 observed sources, availability, accepted/observed revisions and accepted hashes. |
| `/animus behaviors validate [behavior-id]` | Scan and compile a candidate without replacing accepted programs or changing source files. With an ID, show only that behavior's problems. |
| `/animus behaviors reload` | Publish validated source observations, including packs; preserve the preceding accepted program for invalid edits. |
| `/animus behaviors schema` | Write the generated [schemas and completions](#schemas-and-editor-support) under `schemas/`. |
| `/animus behaviors packs` | Each pack with its manifest state and any missing required modules. |
| `/animus behaviors run <npc-id> <behavior-id>` | Admit manual execution against the captured definition and accepted program. Returns an execution UUID. |
| `/animus behaviors inspect <execution-uuid>` | Inspect active or retained terminal state, source identity, waits, diagnostics and recent traces. |
| `/animus behaviors trace <npc-id-or-uuid>` | Show each active run of that NPC, manual or trigger-started, with its origin, waits and last step, plus its last four finished runs. Use `inspect` for one run's full recent steps. A deleted NPC's UUID still works while its runs are retained. |
| `/animus behaviors cancel <execution-uuid>` | Cancel that execution and revoke its pending calls. |
| `/animus behaviors triggers [page]` | Trigger availability and totals, drop reasons, binding problems and up to 20 bindings with last fired, fired, active, queued, suppressed and dropped counts. |
| `/animus behaviors state status` | Stored and transient key counts against their budgets, documents waiting to be written, write failures, and each invalid stored file with its exact reason. |
| `/animus behaviors state purge behavior <behavior-id>` | Remove every variable of that behavior. |
| `/animus behaviors state purge npc <npc-id-or-uuid>` | Remove that NPC's `npc` and `relationship` variables across all behaviors. |
| `/animus behaviors state purge player <player-uuid>` | Remove that player's `player` and `relationship` variables across all behaviors. |
| `/animus behaviors state purge relationship <npc-id-or-uuid> <player-uuid>` | Remove the variables of that one pair. |
| `/animus behaviors state purge all confirm` | Remove all stored and transient variables, including invalid or unrecognized stored files. |

Catalog loading waits for module initialization and shares the existing bounded file worker. A successful scan may publish a catalog containing unavailable or invalid sources; inspect the reported counts and field diagnostics. Repeated catalog operations report busy while a scan is pending. Replies return through the sender's owning scheduler. Console execution carries no player identity. Player execution captures only the sender UUID, which identifies the triggering player and grants no privileged authority.

Manual execution permits one active manual run per NPC, including a dormant definition. Trigger-started executions are counted separately under their own budgets. It captures the exact accepted definition and behavior. Definition replacement, deletion or despawn cancels that authority. A no-op NPC file reload also conservatively cancels because it publishes a new definition object. An unchanged accepted behavior retains its identity and continues. Edited behavior bytes require a higher revision; accepted replacement cancels runs using the earlier program. Missing or duplicate behavior sources make the program unavailable. A rejected edit can continue using the preceding valid program within the same process.

Variables are never removed implicitly. Deleting or disabling a behavior, deleting an NPC, or losing a module leaves the state in place, so restoring the same behavior ID or NPC UUID finds it again. Cloning an NPC gives the clone a new UUID and no state. Removal happens only through `state purge`. A running behavior whose state is purged reads its `initial` values from then on and can write new ones.

Each execution keeps a fixed ring of recent steps sized by `traceEntries`, and its report lists current waits, such as `condition:example:allowed@$.routines.main` or `tick:1200@$.routines.main.steps[1]`. `inspect` and `trace` are answered by the behavior owner on its next turn. Java callers use `BehaviorRuntime.observe(uuid)` and `observeNpc(idOrUuid)`. Recording a step only fills ring slots; text and reports are built when someone asks. `inspect(uuid)` and `snapshot()` return the last published views, which are refreshed for two seconds after each such call and whenever a run starts or ends.

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
