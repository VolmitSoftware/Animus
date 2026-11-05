# Editable JSON and recovery

Plugin settings use `config.toml`. NPC definitions, behaviors, behavior packs under `packs/<pack-id>/` and generated recovery and state data use JSON. Each definition lives at `npcs/<group>/<id>.json` with a stable UUID and an increasing revision. Commands use the same definition validation and optimistic hash checks as reload.

Accepted definitions have a generated last-valid checkpoint under `state/npc-definitions/last-valid/`, referencing a hashed JSON object under `state/objects/npc-definitions/`. A command edit or deletion updates the source and checkpoint in one recoverable transaction. Checkpoints are internal recovery data; edit the source definition instead.

If an existing, previously accepted file has a malformed or stale edit at startup, Animus retains its last valid definition, including spawn intent, and reports the exact source field error. It leaves the invalid source bytes unchanged. Normal edits still conflict with that file until the administrator repairs it and reloads. A new malformed file, duplicate identity or corrupt checkpoint fails startup explicitly. Deleting a source file removes that NPC; recovery never intentionally restores a missing source.

Live reload validates the entire candidate registry before publication. Known native navigation settings share core validation across commands, files and the converter; a malformed enabled waypoint module cannot replace its valid checkpoint. Unknown modules and disabled review data remain preserved. An invalid reload retains the active registry. `/animus validate` reports source errors, and status reports the number of files needing repair. Checkpoints preserve definitions, not live health, temporary routes or interrupted behavior stacks. Missing or invalid skin assets have their own presentation diagnostics; definition recovery is not a backup of all referenced assets.

## Behavior checkpoints

Accepted behavior programs follow the same design. A pointer under `state/behavior-catalog/last-valid/behaviors/<namespace>/<path>.json`, or `state/behavior-catalog/last-valid/packs/<pack>/behaviors/<namespace>/<path>.json` for a behavior in a [pack](behaviors.md#packs), records the path, revision and source hash, and names a copy of the accepted source under `state/objects/behavior-catalog/<hash>.json`. The catalog writes both on the shared file lane before publishing, and a failed write rejects the reload with `catalog-checkpoint-failed`, leaving the previous catalog active. Validation writes nothing. Pointers are removed when their source file is gone, which is why a deleted behavior does not return. Superseded objects stay on disk, as definition objects do.

## Behavior state

Persistent [behavior variables](behaviors.md) live under `state/behavior-state/`, one JSON document per behavior and subject:

```text
state/behavior-state/npc/<npc-uuid>/<namespace>/<path>.json
state/behavior-state/player/<player-uuid>/<namespace>/<path>.json
state/behavior-state/relationship/<npc-uuid>/<player-uuid>/<namespace>/<path>.json
state/behavior-state/global/<namespace>/<path>.json
```

```json
{
  "schemaVersion": 1,
  "behavior": "example:quest",
  "scope": "relationship",
  "npc": "00000000-0000-0000-0000-00000000000a",
  "player": "00000000-0000-0000-0000-0000000000a1",
  "values": {
    "trust": {"type": "decimal", "value": 0.75},
    "visited": {"type": "list", "value": ["harbor", "mill"], "expires": 1730000000000}
  }
}
```

Each entry names the value's own type (`boolean`, `integer`, `decimal`, `string`, `list` or `map`) and holds its JSON; a decimal always keeps its point. `expires`, when present, is the epoch millisecond from which the entry reads as unset. Expired entries are removed from memory a bounded number at a time and leave the file at the next commit.

These files are generated. Do not edit them; use `/animus behaviors state purge` to remove state. Definitions and behavior sources never hold this data.

All values are loaded at startup and held in memory, so reads and writes do no file work on the behavior thread. A `set` changes memory only. The first change after a quiet period starts a `flushMillis` timer, further changes join it, and when it expires one task on the shared file lane commits the changed documents. Large sets go out in several commits. If the lane is full or a commit fails, the values stay in memory, the failure shows in `/animus behaviors state status`, and the commit is retried on the next interval. Clean shutdown refuses new writes, then flushes everything still waiting before the store closes.

Each commit uses the journal described below, so a crash during a write rolls forward at the next start and a document is never left half-written. State commits leave no rollback receipt under `backups/`, because frequent flushes would otherwise accumulate receipts indefinitely, so `/animus rollback` does not apply to them. A crash loses the changes since the last completed commit, up to `flushMillis`, and the previous values remain.

A stored file that fails validation is left byte-for-byte in place and reported with its exact reason. Runs that need that one document are refused with `behavior-state-document-invalid`; everything else keeps working. A file whose path names no subject is reported and removed only by `state purge all confirm`. A purge removes matching keys from memory at once and from disk in the same operation.

## Transactions and rollback

The process lock prevents simultaneous writable stores. A committed `state/pending.json` journal rolls forward before runtime loading; conflicts with outside edits stop recovery. Source writes, recovery pointers and inverse backup receipts use atomic replacement on the local filesystem. Newly created directory entries are synchronized through their parents before a dependent journal is published. Rollback refuses to overwrite intervening changes and creates another receipt so the rollback itself can be undone.

Small transactions retain the original inline journal format. Larger transactions use schema2 metadata with SHA-256 references to raw before/after payloads under `state/objects/transaction-data/`. Those objects are written and synchronized before journal publication. Recovery validates every referenced object and conflict before changing any target; missing/corrupt payloads preserve the pending journal and stop recovery. Rollback reads both formats. Preserve backups together with their referenced objects, including original malformed JSON that a rollback must restore exactly.

Generated state commits use this same journal without a receipt, and always inline. Raw document transactions, including import rollback, invalidate an existing source checkpoint in the same journal unless they explicitly update that pointer. This prevents a later malformed file at a deleted import path from resurrecting the old NPC. Accepted batch applications include an explicit pointer change even when its bytes are unchanged, preserving rollback after source reinsertion and restart. Callers that use the raw store must validate their replacement definitions first; the repository performs validation for ordinary commands. Immutable objects may remain unreferenced because older receipts can still need them. Automatic object/backup collection is not implemented.

The converter's `validate` operation opens a read-only store: it does not create lock files, activate NPCs, write checkpoints or recover pending transactions. Pending recovery is reported as an error with files unchanged. An existing server lock prevents concurrent CLI validation; a never-opened export has no lock, so its validation describes the files observed rather than an atomic snapshot against an external editor.

## Schema versions and migrations

NPC definitions (`npcs/`), behaviors (`behaviors/` and `packs/<pack>/behaviors/`) and shops (`shops/`) carry a `schemaVersion`. Every kind is at version 1 today. At startup, before any of these load, `SchemaMigrations` checks each document. A document at the current version is not rewritten. An older document is upgraded step by step, and all upgraded documents are written in one journaled transaction with a rollback receipt, so `/animus rollback <transaction>` restores the original bytes. The log names the transaction. A document with a newer version than this build reads, or one whose upgrade step fails, is logged, left byte-for-byte in place, and then reported by the normal loader. A missing or malformed `schemaVersion` is left for the loader to report.

### Adding a schema migration

1. Write a `SchemaMigrations.Step` that turns a version N document into version N+1. It receives a copy, returns the new document and throws `IOException` with a clear message when it cannot convert the document. It must not drop data silently: keep unknown fields, or fail. The framework sets `schemaVersion` after the step.
2. Append it to the kind's chain in `SchemaMigrations.STANDARD` with `.step(kind, step)` after the existing steps. Never reorder or remove a released step.
3. Raise the accepted version in the kind's parser (`NpcDefinition`, `BehaviorCompiler`, `ShopDocument`), its generated JSON schema, and the writers that create new documents.
4. Add tests with a real document of the old version that check the upgraded result, a second run that changes nothing, and a failing input that stays unchanged. `SchemaMigrationsTest` shows the pattern with a version 2 step that exists only in test code.

## Bounds and current limits

Documents and encoded journals are limited to16MiB. Transactions contain at most10,000 entries, including generated checkpoints/receipts, and256MiB of logical before/after payload. Recovery and rollback enforce this budget incrementally before retaining excessive decoded content, even if many entries reference one object. JSON nesting is limited to64 levels. A definition/checkpoint tree permits at most100,000 JSON documents,250,000 total entries and8 relative directory levels; traversal stops before collecting or sorting excess entries. Symlinks and non-directory document roots are rejected.

Verification covers semantic corruption across restart, revision regression, duplicate recovered identities, tampered immutable objects, simulated interrupted roll-forward between source and pointer, independent undo, import rollback, deletion/path reuse and read-only validation. New local tests cover compact-journal recovery, corrupt later payload preflight, rollback expansion bounds and orphan checkpoint reinsertion. Exact server evidence is recorded separately in [implementation evidence](implementation.md). Power-loss fault injection, network-filesystem durability and automatic definition-schema upgrades remain unverified. Cross-process external editors still have a final-write race after optimistic preflight.

## Import admission

Import application uses the same bounded NPC-directory scan as normal loading: at most250,000 entries,100,000 JSON documents and8 relative directory levels. Non-JSON entries still consume the traversal budget; directory and dangling symlinks are rejected. Existing plus incoming definitions must fit the100,000-definition limit. Validation completes before any import transaction writes. An ordinary directory ending in `.json` is not parsed as a definition.

