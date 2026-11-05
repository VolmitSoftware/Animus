# Implementation evidence and continuation

Updated 2026-11-05. The complete user plan and live progress record are on the development machine's Desktop as `Animus-Implementation-Plan.md` and `Animus-Implementation-Handoff.md`.

## Current status

- Milestones 1 to 4 (lifecycle, storage, visibility, presentation, behaviors, combat, imports, modules) pass on merged master on all nine Paper, Spigot and Folia targets (26.1.2, 26.2 and 26.3), run with `qa/multiplexor/gate.py`: Paper and Spigot run 113 live gameplay checks each (117 on 26.3), plus native, module, import and behavior suites; Folia runs its live checks plus the stationary preview, module, import and behavior suites.
- Live gameplay on Paper 26.3 (`qa/multiplexor/live-features.mjs`) covers triggers, durable state across restarts and kill -9, behavior edits, the command allow-list, mounts, riding, dragon flight phase, combat (melee, arrows, fireballs, death and respawn) and all seven example packs. Folia 26.3 runs activation, triggers, actions and a patrol crossing chunk owners, and reports riding, mounts and combat unavailable.
- Fabric, NeoForge and Forge share the same runtime; see [mod servers](mod-servers.md#live-checks).
- A full migration of the private 495-record Citizens save on Paper 26.3 (2026-11-05) installed every NPC, 409 skins and 107 shops; 484 of 484 expected NPCs were active, 103 of 103 imported guards were combat-capable, and restart, rollback and re-apply were clean.
- The web editor is deployed at https://animus-editor.web.app; see [deployment](deployment.md).

Open: graphical checks on a real client (poses, text displays, equipment, dragon wings, riding feel), riding, mounts, combat and dragon phases on Folia, a live WorldGuard check, performance measurement ([benchmark preparation](benchmark.md)), and translating Denizen scripts, which needs the external scripts. The sections below the source list are earlier checkpoints, kept as a record; their artifacts and the separate `.qa` workspace they name were retired.

Implemented source:

- `core/config`: TOML plugin settings using VolmLib, with backed-up lossless legacy JSON migration. NPC/behavior/generated data remains JSON.
- `core/storage`: strict bounded JSON; process lock; atomic files; optimistic hashes; recoverable multi-document journal; backups and inverse transactions.
- `core/npc`: immutable definitions, UUID/ID/revision validation, durable last-valid checkpoints, registry reloads and draft apply.
- `core/migration`: safe bounded YAML; versioned staged conversion; complete source/record archives; JSON Pointer field classification; skin deduplication; known nested world remapping and UUID references; separate combat statistics; collision rejection; explicit dormant application.
- `core/admin`, `bukkit`: shared administration and import services, permissions, bounded filesystem worker, Bukkit/Folia reply scheduler.
- `core/virtual`: independent mutable runtime state with immutable snapshots, ray/hit validation, viewer sessions, spatial index and bounded subscription deltas.
- `core/navigation`: immutable voxel snapshots, bounded/cancellable path searches, kinematic route execution and live clearance/support validation contracts.
- `bukkit/packet`: native semantic packet adapter with unique IDs, no native entity bodies, bounded queues, presentation leases, signed skins, passengers, head rotation and inbound interception.
- `bukkit/runtime`: persisted activation, delivered-chunk visibility, fair bounded viewer reconciliation, shared virtual health, selection and production interaction events. On Folia, `RegionNpcRuntime` gives each NPC to its chunk's region through `RegionOwner`; riding, mounts and combat stay disabled there.
- `core/combat`, `bukkit/combat`, `bukkit/RuntimeCombatService`: the [combat module](combat.md) (target rules, state machine, squads, guards, melee and tracked arrows, defense, death and respawn, fail-closed protection with a WorldGuard adapter, bounded work) plus the manual hit and ordinary-zombie adapter. `core/migration/SentinelMapping` converts imported settings.
- `bukkit/PacketProofService`: opt-in viewer-isolated proof, lifecycle teardown, virtual health and cancellable third-party preview events.
- `bukkit/navigation`, `bukkit/runtime`: bounded owner-thread capture of loaded terrain, one planner worker, live movement checks, manual routes, inline/reusable JSON waypoints and cancellation. Partial-block physics and Folia ownership remain open.
- `core/control`, packet/proof adapters: bounded semantic input, native heartbeat leases, unmounted preview control and authoritative real-player rider sessions.
- `converter`: local preview/apply/validate/rollback commands using the same core.
- `gradle`: Java 25, exactly three 26.x targets, Bukkit-first default distributions and relocated YAML dependency.
- `docs/upstream-inventory.md`: pinned import source inventory, all observed traits mapped, persisted combat fields and gaps in translating imported behavior.

## Automated verification

`JAVA_HOME=<JDK25> ./gradlew build :converter:installDist` runs every core, Bukkit and converter test (add `-PincludeModDistributions=true` for the mod jars). At the runtime-6 checkpoint it passed 201 core and 43 Bukkit tests, compilation against Spigot26.1.2/26.2/26.3, shaded artifact checks, and distribution manifest checks. Waypoint settings validation moved into the core so files, commands and the converter apply the same native schema.

Meaningful cases cover malformed/ambiguous JSON, YAML duplicate keys, unsafe tags, aliases, nesting and size limits; unsupported traits; missing/invalid locations; same-source repeated previews; changed staged documents; UUID/ID collisions; import rollback/reapply; independent skin references; command edit/clone/delete and restart; unknown-module preservation; file locking; path traversal/symlinks; rollback conflicts; partial journal recovery; malformed journals; recovery conflicts with no partial overwrite; invalid reload retention; stale drafts.

The private 8.1 MiB fixture produced 495 valid definitions, 409 distinct skin documents, a complete 29-trait inventory, and 29 Sentinel field counts. Local explicit apply installed 495 dormant definitions; validation returned 495; rollback completed successfully. An independent archive audit verified all 236,939 classified leaf fields, all 904 bundle hashes, exact source bytes, and preservation of every original trait. Version 2 additionally remapped 30,598 known nested locations and resolved both mount references with zero unresolved fixture references. Apply/repeat/rollback passed independently; version 1 archives remain supported. This validates storage and conversion only.

## Historical direct live checks on Leaf

A dedicated local Leaf 26.3-17 (commit af63114) server, Java 25.0.5 on macOS ARM64, passed startup, console status/create/edit/inspect/clone/delete/validate/reload/rollback, invalid-edit last-valid retention, two clean shutdowns, and definition persistence after restart. Existing JSON plugin settings migrated to TOML; the restarted server used TOML. No production server or private NPC collection was used. The scenario uses synthetic dormant definitions and is reproducible with:

```sh
python3 qa/runtime-smoke.py --server-dir /path/to/isolated-server --java /path/to/jdk25/bin/java
```

Provide the server jar, accepted EULA, loopback server properties, and built `plugins/Animus.jar`. Test NPC IDs are `qa-smoke` and `qa-smoke-copy`; use a dedicated server directory. Logs live at `<server-dir>/animus-smoke.log`. This is supplemental Leaf console/lifecycle evidence, not Spigot/Paper/Folia certification, player permission testing, or packet gameplay proof.

## Exact platform and packet checks

`qa/platform-smoke.py` downloads and records exact Paper/Folia releases, hashes every server/plugin artifact, runs commands and restart persistence, and stops its owned servers. Completed results: Paper26.1.2 stable74,26.2 stable135,26.3 stable240; Folia26.1.2 stable8,26.2 beta7,26.3 alpha6. Folia explicitly disables the packet proof. Evidence: `.qa/platform-tests/evidence-index.json`. Six packet unit tests and30 native descriptor checks per release also passed. Signature checks do not prove gameplay.

The exact26.3/protocol777 Java codec client completed **30 live assertions** on Leaf26.3-17, including signed skin payload hashes, player-info ordering, all eight fixture entity types, movement, use/attack, reach and occlusion rejection/recovery, static passengers, entity/profile despawn, third-party cancellation, reentrant teardown, non-operator denial, viewer isolation, foreign/forged/stale IDs, and logout/reconnect cleanup. Evidence: `.qa/protocol-cache/results/2026-11-04T07-02-16.714Z-26.3/result.json`. All clients disconnected; the test server was then stopped. Initial client login produced movement warnings, under investigation; these are not hidden as clean-log results.

The helper uses exact vanilla codecs because current Prismarine metadata lists newer versions without their actual packet schemas. It does not pretend that an older protocol is compatible. Reproduce against a prepared isolated server with:

```sh
node qa/packet-client.mjs --version 26.3 --port 65490 --event-fixture
```

The optional QA-only event fixture is built by `qa/build-fixture.py`. Signed-skin checks additionally use `--skin <hash> --skin-file <private-json>`; payloads are compared by hash, never logged. Use synthetic owner/peer profiles and a loopback offline test server. This is wire and authority evidence, not graphical appearance, real-target damage, vanilla-mob targeting, controllable riding, or production lifecycle certification.

The above tests were direct Gradle/isolated-server checks. The earlier actual Multiplexor proof build `run-1793776425697665-40479` passed core, Bukkit and converter targets. All six managed Paper/Folia lifecycle suites passed on that artifact, together with the standard 26.1.2 player scenario and **38 exact-codec checks on each managed Paper release**. The expanded checks cover bounded world capture, blocked routes, cancellation, delayed skin cleanup, synchronized registries, NPC metadata and an ambient-cow metadata regression. The native client loads the server’s configuration registries and tags before binding play codecs. All final native logs are free of errors, exceptions and movement warnings; every owned server stopped and its port closed.

Plugin SHA-256: `ea0ef82d32cfa16594fad2fb96f615462fdab32be161cdcb415d636a19ae0a6f`; the root artifact matched that Multiplexor artifact at the proof checkpoint. Aggregate evidence is the retired QA workspace's `animus-evidence/index.json`; source capture and final build status are beside it in the workspace. Earlier infrastructure failures and the client registry failure remain archived; the final reruns resolve them. See [Multiplexor commands and evidence](multiplexor.md). Direct historical tests remain separately labeled.

## Completed runtime-3 matrix

Multiplexor build `run-1793780274008222-12539` passed core/Bukkit/converter. It has147 passing core tests from the identical, previously tested source cache and35 executed/passing Bukkit tests, including32 valid TOML endpoint combinations constructing runtime settings. All three exact Spigot API compilation tasks executed. Plugin SHA-256: `5be1c11fc676a61c738163b14273fc6da39aa93fad6ba35dbdea11ccc68fb470`. The packaged description is exactly **A Manager for all things NPC.**

Detailed test/source/cache provenance is in the retired QA workspace's `animus-evidence/build-runtime-3/summary.json`. The independent local delivery build passes and its canonical `build/distributions/` jar and artifact manifest match the tested plugin byte for byte. A later comment-only source correction produced the identical artifact; its before/after hashes and diff are recorded in `local-delivery.json` beside the build evidence.

All six exact Paper/Spigot targets passed24 persisted-lifecycle and14 combat checks each on this artifact. All three enhanced Folia lifecycle suites passed: explicit runtime/combat unavailability, unchanged definition bytes on rejected spawn, dormant edit persistence across restart and cleanup. The final independent audit confirms the same plugin hash and exact packaged description on all nine targets, every owned port closed, no error/exception/movement-warning lines, and no remaining synthetic NPC files. Evidence: the retired QA workspace's `animus-evidence/final-runtime-audit.json`; strict current-artifact aggregate `runtime-3-final.json` and `index.json` keep earlier proofs under historical evidence. Diagnostic runtime-1/runtime-2 and prior proof results remain separate.

The production checks cover restart/skin persistence, separate viewer IDs, delivered visibility, selection, cancelled interactions, foreign/stale IDs, dimensions, reconnect, Unicode edits, invalid-file retention, deletion, and simultaneous isolated preview/production routing. Combat checks cover real damage without a fabricated entity, Animus/Bukkit cancellation, callback teleport reach revalidation, solid obstruction, bounded zombie pursuit, cancelled virtual damage, native route/goal restoration, autonomous replan suppression, closed-bridge rejection and same-mob reacquisition, shared-health preservation, strict import review flags, and despawn cleanup.

Failed attempts remain preserved. Fixes included decimal vanilla teleport coordinates, no-op force-load receipts, wall clearance around the cow, direct fixture command invocation instead of nested Brigadier dispatch, exact native version identification, and a fresh living target for policy tests after restored vanilla goals killed the earlier target. Native inspection established that explicit target setters suppress Bukkit events; the final close test makes no target-restoration callback claim.

The [runtime guide](runtime.md) documents commands, TOML budgets and limits. Production scenarios use `qa/multiplexor/runtime-npcs.py`, `qa/runtime-client.mjs` and `qa/combat-client.mjs`; fixture and wrapper sources are hashed and archived with each run. The shared exact-codec transport is separate from Multiplexor's stock Mineflayer coverage.

The [benchmark preparation tools](benchmark.md) generate deterministic495-definition,5,000-definition and32/64/128-visible workloads with a matching server baseline without the plugin. Two generator tests and validation of all5,719 generated definitions passed. Environment capture is implemented; no server timing or performance result is claimed.

## Completed runtime-6 matrix

Actual Multiplexor build `run-1793803535545514-80447` passed core/Bukkit/converter. Plugin SHA-256 is `c89adc35796afe5c33d85cf3e0f853ae2cd0f68123e3c1c16a4e77abd93f9576` (2,097,278bytes). The independent local full build passes201 core and43 Bukkit tests and produces the identical canonical distribution. Source capture is `.qa/multiplexor-sources-runtime-6`; build records and local delivery evidence are under `animus-evidence/build-runtime-6/`.

This adds durable definition recovery, initial kinematic navigation, reusable JSON routes, fixed-target look commands and bounded unmounted preview control. Native waypoint schema validation now precedes checkpoint publication. Invalid inline settings are covered across reload and restart; unknown modules and disabled review data remain preserved. A strict review also closed singleton-array mode coercion, imprecise schema-version comparison and reusable-route UTF-8 byte bounds.

All six exact Paper/Spigot targets passed80 native assertions each:12 preparation,6 first-restart recovery,14 second-restart/lifecycle,14 combat,18 control and16 movement checks. Both malformed position and enabled inline-navigation edits preserve their invalid source bytes while retaining the accepted definition across managed restarts. Normal edits conflict until repair/reload. All three exact Folia targets passed27 dormant lifecycle/recovery checks each; general Folia rendering, movement and combat remain unavailable.

Independent audit `animus-evidence/audits/runtime-6-final-1793805746971943000/audit.json` passed with480 native checks and81 Folia checks. Its SHA-256 is `a6c92b514588352ff6167f4e404b3f02e1018299d603289b18123b79f790f862`. It verified deployed server/plugin hashes, exact description, all nine closed ports, no owned definition/checkpoint leftovers and no unexpected errors or movement warnings in final logs. Immutable aggregate: `animus-evidence/runtime-6-final.json`. Twenty auditor regression tests passed;26 failed attempts remain archived.

The audit explicitly records a reviewed, receipt-pattern-only wrapper variant for an unchanged vanilla time command, and a prepare-state recovery annotation added by the wrapper after native completion. Neither changes native assertions. Managed server-jar symlinks are recorded and their resolved bytes verified. Native clients now send stationary position reminders and respawn normally when a saved QA player is dead; arenas disable incidental natural spawning. These harness corrections changed no production source.

Both core and Bukkit tests executed in this Multiplexor run:201 +43 passing tests, with all three exact API compilations executed. The private495-record fixture also passed conversion, complete236,939-field accounting,409 deduplicated skins,495 recovery checkpoints and unchanged subsequent startup. Raw rollback removes all definitions and checkpoints in1,400 journal entries; largest journal12,997,078bytes remains below16MiB. Only aggregate private evidence is retained in the repository's ignored QA directory.

Runtime-5 diagnostic and earlier proof results remain tied to their historical artifacts. This checkpoint does not close an entire milestone or establish graphical fidelity, mounted control, complete combat behavior or performance improvement. A separate Folia stationary proof is now being integrated and requires a new captured artifact and live verification.

## Verified runtime-8 Folia stationary preview

Multiplexor build `run-1793807521311830-32660` produced SHA-256 `5bd4a77deef1dc7ce6fa6b0de1809305fe080775aa516a3da6ea37594ceab2cf` (2,128,971bytes), identical to the canonical local distribution. It reuses204 core tests through verified unchanged-input/cache lineage, executes61 Bukkit tests, and executes all three exact API compilations. The unchanged converter libraries link to runtime7's successful495-record private validation. Provenance lives under `animus-evidence/build-runtime-8/` and `runtime8-private-migration-lineage.json`.

Folia26.1.2 stable8,26.2 beta7 and26.3 alpha6 each passed50 native checks:34 main,11 bounds and5 shutdown. These cover private signed profiles in separate regions, owner-safe interaction, forged hit points, reach/LOS/rate checks, cancellation, lifecycle, queued work, admission limits, stalled transport, actual plugin disable with pending work, late completion rejection, and wire/profile cleanup. The actual spawn command rejects an owned/loaded footprint before delivery and succeeds after delivery. A separate lower-level production admission probe rejects an unowned footprint with no observed chunk events and unchanged bounded ticket snapshots.

The first runtime7 expanded scenario found that Folia's native same-region `teleportAsync` bypasses `PlayerTeleportEvent`. Runtime8 captures the exact connection identity and teleport counter before queueing spawn and rechecks it at admission, async publication and active-lease validation. Nearby and same-position teleports now cancel both pending requests and visible proofs, including reentrant interaction callbacks. The failed artifact and attempts remain preserved.

Independent final audit `animus-evidence/audits/folia-runtime-8-final-1793808200574002000/audit.json` passed150 checks with all three ports closed, clean final logs and exact deployed/source hashes. Audit SHA-256: `71cae1000a7052bb25ba4c9270b1e78cc1c5aee0c8882350c8d27d2edf723fd1`.21 auditor tests pass;3 failed attempts remain archived. Immutable aggregate: `animus-evidence/runtime-8-folia-final.json`. All nine previously owned server ports were also checked closed.

This verifies the documented **viewer-private stationary preview**, described in [Folia preview commands and limits](folia-preview.md). An actual unowned command-boundary case, partially owned four-chunk footprint and absence of transient tickets between snapshots remain unverified. General Folia NPC runtime, movement/transfer, control and combat remain disabled. Graphical fidelity and performance are outside this evidence. Runtime6's Paper/Spigot results remain tied to that earlier artifact.

## Verified runtime9 import applications

Multiplexor build `run-1793809087767551-52812` produced plugin SHA-256 `72c41097d3e3c7aea3ce7cd4956d5b3073b26900f27c0dddbc1db7486cf5540d`, identical to the canonical distribution. Its captured source digest is `c7d128c837804ab1dd425ed4841d5d80ce1f51ff735f09b1aaf5915777ad2b91` (232 files). The build executed 209 core and 61 Bukkit tests plus all three exact API compilations. Private conversion independently verified all 495 records in reject/replace/duplicate workflows, including restart and rollback; 47 separate CLI processes passed 11 grouped public-fixture checks.

All nine exact Paper/Spigot/Folia targets passed 14 grouped import data-workflow checks each (126 total). Each target went through three starts and two restarts: preview/stage idempotence, apply/repeat, copied reference mapping, conflicting edits, fresh replacement, exact source/checkpoint rollback, legacy apply compatibility, and an empty final restart. All 18 old/new test instances were stopped, ports closed, and synthetic definitions/recovery pointers removed.

Strict independent audit `animus-evidence/audits/imports-runtime-9-final-1793809554180223000/audit.json` passed with SHA-256 `450a1cd6625c76db786d1d5ca6ec6ec44fb88dba8bb4d1f122b9e1450ea2148c`; 16 negative/completeness auditor tests pass. It binds archived commands, receipts, plan/source hashes, immutable objects, inverse transactions, restart snapshots, server/plugin hashes and cleanup. Immutable aggregate `runtime-9-import-final.json` has SHA-256 `a38a8e5c4efb554f26960ad0a327b6ef8ebdcd41126d913b3f228d4e89371efa`. The selected evidence index separates this data workflow scope from runtime6/runtime8 native gameplay; no earlier gameplay check is relabeled as runtime9.

The [application commands](import-applications.md) now support explicit replacement and duplication. Compact journal payloads and accepted-definition checkpoints preserve restart rollback within documented limits. This does not establish imported behavior parity, automatic schema migration, or actual power-loss resilience.

## Runtime10 modules and regression scopes (earlier checkpoint)

Multiplexor build `run-1793811646388580-96533` produced plugin SHA `a6b3a8632f37595e55959c4f0cc0bac2a39ea73a7d57cbb863c5e5a7e886a601` and standalone API SHA `7d047849886f384283626ac98175a8d246634623e2f07fd22640f2f153c54911`. It executed306 core and61 Bukkit tests and all three exact API compile tasks. Its source capture contains288 Animus files, digest `2b5b7ddaa60a0395005850b02e78efdd3384df607ccf6ceef11c17c78d33806d`.

Independent acceptance covers27 managed suites and864 grouped checks:108 trusted Java module/data checks on nine targets,480 native Paper/Spigot checks on six targets,150 limited Folia stationary-preview checks on three targets, and126 import/data checks on nine targets. Immutable aggregate the retired QA workspace's `animus-evidence/runtime-10-final.json` has SHA `55c428f15716969fd0f5b053c5e0b0e809b1dd0f0df1e9abd17df09bc2a1132f`. Final current-instance cleanup verifies all27 stopped and ports closed; broader cleanup checks45 instances/46 ports. Failed attempts remain preserved. Folia's accepted QA supplement fixes explicit release of its own held file work and caches its diagnostic method before actual disable; no production workaround or exception whitelist was introduced.

The versioned JDK-only API, startup module loader, bounded capability host, shared file mutation lane and asynchronous shutdown are part of this accepted artifact. See [module contracts](modules.md) and the [reference module](../examples/modules/reference/README.md). Trusted Java extensions are not sandboxed. API replacement definitions are dormant; activation uses ordinary platform-validated administration. Public event subscriptions, import-converter extension hooks and remote API publication remain unfinished.

## Manual behavior integration (earlier checkpoint)

The active source now includes the bounded JSON compiler/interpreter, typed argument bindings, compiled module argument-schema checks, revision/hash-aware file catalog, read-only candidate validation, TOML budgets and manual `behaviors` commands. Its owner uses immutable definition/program leases and revokes changed source authority before queued handlers start. Terminal observation preserves lease cleanup even when retained history is smaller than the number of completing runs. Actual unfinished module calls remain counted after cancellation.

The local full build passes476 core and69 Bukkit tests. A final startup/shutdown review fixed late coordinator construction: enabled behavior service now owns its coordinator synchronously before asynchronous file loading, so disable always closes that same instance. Local command lifecycle tests and57 isolated coordinator/host/cold-classloader checks cover the change. Canonical/custom-group packaged API verification passed18 grouped checks at `.qa/module-packaging/1793815322952198000-59707/result.json`, SHA `5ef8df4d1f71720d26d39e6c4acbc05e4422c1bd5d92abc2cec32a761bd26b67`.

Runtime11 then built this source and passed the 26-check behavior suite on all nine targets. Automatic triggers, durable state and platform actions followed and pass the same suite and the live checks above.

## Remaining work

See [current status](#current-status). Keep platform compilation separate from live runtime results. Keep preservation separate from behavior translation. Do not check entire plan items when only part is implemented.

## Known storage limits

Files are limited to16MiB and64 levels of JSON/YAML nesting. Transactions allow at most10,000 entries and16MiB of encoded metadata; large payloads use content-addressed objects with a256MiB logical before/after bound. Application previews reserve64MiB of input/projection work. Rollback creates another backup; retention is manual. Atomic moves and directory synchronization have been exercised on local macOS APFS. An external editor can race the final write after optimistic checks; use offline edits or serialize maintenance with apply.

Accepted definitions now have durable last-valid recovery; invalid source bytes remain untouched and edits keep their optimistic conflict checks. Raw/import rollback invalidates stale checkpoints atomically. Read-only converter validation refuses pending recovery without writes. Trees are bounded to100,000 JSON documents,250,000 entries and8 relative directory levels before sorting. See [storage details](storage.md). Unsupported schema versions prevent loading unless a known file can retain its prior valid definition. Staged reports contain field classifications but there is no machine-generated editor schema yet. Imported unknown data is preserved, not automatically executable. File reload is explicit.
