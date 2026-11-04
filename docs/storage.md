# Editable JSON and recovery

Plugin settings use `config.toml`. NPC definitions and generated recovery data use JSON. Each definition lives at `npcs/<group>/<id>.json` with a stable UUID and an increasing revision. Commands use the same definition validation and optimistic hash checks as reload.

Accepted definitions have a generated last-valid checkpoint under `state/npc-definitions/last-valid/`, referencing a hashed JSON object under `state/objects/npc-definitions/`. A command edit or deletion updates the source and checkpoint in one recoverable transaction. Checkpoints are internal recovery data; edit the source definition instead.

If an existing, previously accepted file has a malformed or stale edit at startup, Animus retains its last valid definition, including spawn intent, and reports the exact source field error. It leaves the invalid source bytes unchanged. Normal edits still conflict with that file until the administrator repairs it and reloads. A new malformed file, duplicate identity or corrupt checkpoint fails startup explicitly. Deleting a source file removes that NPC; recovery never intentionally restores a missing source.

Live reload validates the entire candidate registry before publication. Known native navigation settings share core validation across commands, files and the converter; a malformed enabled waypoint module cannot replace its valid checkpoint. Unknown modules and disabled review data remain preserved. An invalid reload retains the active registry. `/animus validate` reports source errors, and status reports the number of files needing repair. Checkpoints preserve definitions, not live health, temporary routes or interrupted behavior stacks. Missing or invalid skin assets have their own presentation diagnostics; definition recovery is not a backup of all referenced assets.

## Transactions and rollback

The process lock prevents simultaneous writable stores. A committed `state/pending.json` journal rolls forward before runtime loading; conflicts with outside edits stop recovery. Source writes, recovery pointers and inverse backup receipts use atomic replacement on the local filesystem. Newly created directory entries are synchronized through their parents before a dependent journal is published. Rollback refuses to overwrite intervening changes and creates another receipt so the rollback itself can be undone.

Small transactions retain the original inline journal format. Larger transactions use schema2 metadata with SHA-256 references to raw before/after payloads under `state/objects/transaction-data/`. Those objects are written and synchronized before journal publication. Recovery validates every referenced object and conflict before changing any target; missing/corrupt payloads preserve the pending journal and stop recovery. Rollback reads both formats. Preserve backups together with their referenced objects, including original malformed JSON that a rollback must restore exactly.

Raw document transactions, including import rollback, invalidate an existing source checkpoint in the same journal unless they explicitly update that pointer. This prevents a later malformed file at a deleted import path from resurrecting the old NPC. Accepted batch applications include an explicit pointer change even when its bytes are unchanged, preserving rollback after source reinsertion and restart. Callers that use the raw store must validate their replacement definitions first; the repository performs validation for ordinary commands. Immutable objects may remain unreferenced because older receipts can still need them. Automatic object/backup collection is not implemented.

The converter's `validate` operation opens a read-only store: it does not create lock files, activate NPCs, write checkpoints or recover pending transactions. Pending recovery is reported as an error with files unchanged. An existing server lock prevents concurrent CLI validation; a never-opened export has no lock, so its validation describes the files observed rather than an atomic snapshot against an external editor.

## Bounds and current limits

Documents and encoded journals are limited to16MiB. Transactions contain at most10,000 entries, including generated checkpoints/receipts, and256MiB of logical before/after payload. Recovery and rollback enforce this budget incrementally before retaining excessive decoded content, even if many entries reference one object. JSON nesting is limited to64 levels. A definition/checkpoint tree permits at most100,000 JSON documents,250,000 total entries and8 relative directory levels; traversal stops before collecting or sorting excess entries. Symlinks and non-directory document roots are rejected.

Verification covers semantic corruption across restart, revision regression, duplicate recovered identities, tampered immutable objects, simulated interrupted roll-forward between source and pointer, independent undo, import rollback, deletion/path reuse and read-only validation. New local tests cover compact-journal recovery, corrupt later payload preflight, rollback expansion bounds and orphan checkpoint reinsertion. Exact server evidence is recorded separately in [implementation evidence](implementation.md). Power-loss fault injection, network-filesystem durability and automatic definition-schema upgrades remain unverified. Cross-process external editors still have a final-write race after optimistic preflight.

## Import admission

Import application uses the same bounded NPC-directory scan as normal loading: at most250,000 entries,100,000 JSON documents and8 relative directory levels. Non-JSON entries still consume the traversal budget; directory and dangling symlinks are rejected. Existing plus incoming definitions must fit the100,000-definition limit. Validation completes before any import transaction writes. An ordinary directory ending in `.json` is not parsed as a definition.

