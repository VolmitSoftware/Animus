# Reviewed import applications

Default import application still rejects identity/path collisions. The new `stage` operation prepares an explicit application plan for an existing converted import, with one of three modes:

| Mode | Result |
| --- | --- |
| `reject` (default) | Install only when every incoming identity/path is available. |
| `replace` | Replace matching paths only when UUID, readable ID and migration numeric identity agree. Increment revisions; add new records; never delete records absent from the import. |
| `duplicate <label>` | Create new deterministic UUIDs and readable `citizens-N-label` IDs under `copy-label`; remap normalized known NPC references within the copy. |

Labels use1–32 lowercase letters, digits, `_` or `-`. A different copy needs a different label. Cross-path identity collisions and exhausted revisions reject replacement. Every imported definition remains dormant, and imported combat remains disabled and unreviewed. Original records and unknown review data are preserved; duplication cannot translate arbitrary embedded references whose meaning is unknown.

The [drop-in Citizens migration](migrating-from-citizens.md) runs the same steps without IDs: `/animus import citizens [mode] [old=new ...]` previews the save it finds and stages a plan, `/animus import citizens apply` applies that plan (and any dtlTraders shops), and `/animus import citizens rollback` undoes it. It records what it did in `imports/citizens/state.json`.

On the server, use an administrator account:

```text
/animus import preview sample.yml old-world=world
/animus import stage <import-id> replace
/animus import stage <import-id> duplicate town-copy
/animus import apply-plan <import-id> <application-id>
/animus rollback <transaction-id>
```

The source file belongs under `imports/inbox/`. Staging prints an application ID, mode/counts, the plan path and the exact apply command. It creates no active definitions. The converter uses the same rules: `stage <data-dir> <import-id> [mode [label]]`, then `apply-plan <data-dir> <import-id> <application-id>`.

Plans live at `imports/<import-id>/applications/<application-id>/plan.json`, referencing immutable candidate JSON wrappers under `state/objects/import-applications/`. They record candidate hashes, expected target hashes, source report identity, mode and UUID mapping. Do not hand-edit generated plans or objects: a changed plan requires staging again. Edit accepted NPC definitions through ordinary file/command validation after applying.

Apply revalidates the bundle, plan, candidates, full current identity registry and optimistic hashes. A concurrent edit or new collision aborts before installation. Definitions and their accepted recovery pointers share one transaction, so a normal restart does not break rollback. Repeat application returns `already-applied` only while its outputs remain exact; changed or missing outputs are reported instead of overwritten. Rollback removes the application receipt and restores prior definitions, while refusing subsequent edits.

Application input/projection work is limited to64MiB per operation. Staging reserves the additional candidate/metadata reads required by unchanged apply before publishing a plan. Transactions have at most10,000 entries including definition checkpoints, new skins and the receipt. Document/journal limits remain16MiB; large transactions use immutable hashed payloads with a256MiB logical before/after budget. Keep `backups/` and `state/objects/transaction-data/` together when preserving rollback history. Unreferenced candidate/payload objects may remain after rejected work; automatic garbage collection is not implemented.

Server validation, commit and repository refresh run in one serial mutation job. Any future API/import adapter must share that lane; individual synchronized store calls do not protect a complete identity-validation operation. An unrelated text editor can still race the final write, so serialize offline edits with administrative applies.

The integrated commands and storage pass209 core tests,61 Bukkit tests, all exact API compilations and a full local build. All495 private records pass three-mode application/repeat/restart/rollback against the integrated converter classes, and47 independent CLI processes cover11 grouped public-fixture checks. Multiplexor runtime9 independently verifies all nine exact Paper/Spigot/Folia targets: 14 grouped data-workflow checks each, with three starts and two intervening restarts. The final audit validates command receipts, plan/source links, exact before/after definition and checkpoint bytes, cleanup, logs and artifact hashes. Sixteen auditor tests pass. These results cover import data workflows; earlier native gameplay remains tied to its own artifacts. See [implementation evidence](implementation.md) for exact records.
