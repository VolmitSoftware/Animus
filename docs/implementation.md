# Implementation evidence and continuation

Updated 2026-11-04. The complete user plan and live progress record are on the development machine's Desktop as `Animus-Implementation-Plan.md` and `Animus-Implementation-Handoff.md`. No milestone acceptance gate has passed yet.

Implemented source:

- `core/config`: TOML plugin settings using VolmLib, with backed-up lossless legacy JSON migration. NPC/behavior/generated data remains JSON.
- `core/storage`: strict bounded JSON; process lock; atomic files; optimistic hashes; recoverable multi-document journal; backups and inverse transactions.
- `core/npc`: immutable definitions, UUID/ID/revision validation, last-valid registry reloads and draft apply.
- `core/migration`: safe bounded YAML; versioned staged conversion; complete source/record archives; JSON Pointer field classification; skin deduplication; known nested world remapping and UUID references; separate combat statistics; collision rejection; explicit dormant application.
- `core/admin`, `bukkit`: shared administration and import services, permissions, bounded filesystem worker, Bukkit/Folia reply scheduler.
- `core/virtual`: independent mutable runtime state with immutable snapshots, ray/hit validation, viewer sessions, spatial index and bounded subscription deltas.
- `core/navigation`: immutable voxel snapshots, bounded/cancellable path searches and explicit movement modes. Live movement remains unfinished.
- `bukkit/packet`: native semantic packet adapter with unique IDs, no native entity bodies, bounded queues, presentation leases, signed skins, passengers, head rotation and inbound interception.
- `bukkit/PacketProofService`: opt-in viewer-isolated proof, lifecycle teardown, virtual health and cancellable third-party preview events.
- `bukkit/navigation`: bounded owner-thread capture of loaded terrain, immutable snapshots for one planner worker, cancellation and shutdown; diagnostic `proof path` only. No route execution or Folia capture yet.
- `converter`: local preview/apply/validate/rollback commands using the same core.
- `gradle`: Java 25, exactly three 26.x targets, Bukkit-first default distributions and relocated YAML dependency.
- `docs/upstream-inventory.md`: pinned Citizens/API/Sentinel source inventory, all observed traits mapped, persisted combat fields and integration gaps, and separate default/packet Citizens benchmark baselines.

## Automated verification

`JAVA_HOME=<JDK25> ./gradlew build :converter:installDist` passes. This includes 128 core and 18 Bukkit tests, compilation against Spigot 26.1.2/26.2/26.3, shaded artifact checks, and distribution manifest checks.

Meaningful cases cover malformed/ambiguous JSON, YAML duplicate keys, unsafe tags, aliases, nesting and size limits; unsupported traits; missing/invalid locations; same-source repeated previews; changed staged documents; UUID/ID collisions; import rollback/reapply; independent skin references; command edit/clone/delete and restart; unknown-module preservation; file locking; path traversal/symlinks; rollback conflicts; partial journal recovery; malformed journals; recovery conflicts with no partial overwrite; invalid reload retention; stale drafts.

The private 8.1 MiB fixture produced 495 valid definitions, 409 distinct skin documents, a complete 29-trait inventory, and 29 Sentinel field counts. Local explicit apply installed 495 dormant definitions; validation returned 495; rollback completed successfully. An independent archive audit verified all 236,939 classified leaf fields, all 904 bundle hashes, exact source bytes, and preservation of every original trait. Version 2 additionally remapped 30,598 known nested locations and resolved both mount references with zero unresolved fixture references. Apply/repeat/rollback passed independently; version 1 archives remain supported. This validates storage and conversion only.

## Live smoke evidence

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

The above tests were direct Gradle/isolated-server checks. Final actual Multiplexor build `run-1793776425697665-40479` passed core, Bukkit and converter targets. All six managed Paper/Folia lifecycle suites passed on that artifact, together with the standard 26.1.2 player scenario and **38 exact-codec checks on each managed Paper release**. The expanded checks cover bounded world capture, blocked routes, cancellation, delayed skin cleanup, synchronized registries, NPC metadata and an ambient-cow metadata regression. The native client loads the server’s configuration registries and tags before binding play codecs. All final native logs are free of errors, exceptions and movement warnings; every owned server stopped and its port closed.

Plugin SHA-256: `ea0ef82d32cfa16594fad2fb96f615462fdab32be161cdcb415d636a19ae0a6f`; the root artifact matches the Multiplexor artifact. Aggregate evidence is `.qa/multiplexor-workspace/animus-evidence/index.json`; source capture and final build status are beside it in the workspace. Earlier infrastructure failures and the client registry failure remain archived; the final reruns resolve them. See [Multiplexor commands and evidence](multiplexor.md). Direct historical tests remain separately labeled.

## Remaining executable work

1. Complete Milestone 0 beyond the opt-in packet proof: real-target damage, bounded mob targeting, controllable bodies, mount/dragon behavior, graphical validation and exact platform/release gameplay checks with no registered NPC bodies.
2. Connect core spatial subscriptions/runtime state to durable definition activation, visibility, live movement and ownership scheduling; finish create/select/spawn/despawn management and Folia-safe capture/transfer.
3. Add live missing-world validation, explicit replacement/duplication import modes, schema migration and last-valid-on-disk recovery. Known nested locations/references and statistics separation are implemented; runtime translation remains open.
4. Continue navigation, versioned public API/modules, bounded JSON behaviors and examples, combat/protection, benchmark evidence, additional platforms, and then the arcane_jaspr/Firebase editor.

Keep platform compilation separate from live runtime results. Keep preservation separate from behavior translation. Do not check entire plan items when only part is implemented.

## Known storage limits

Files are limited to 16 MiB and 64 levels of JSON/YAML nesting. Transactions allow at most 10,000 documents and a 16 MiB serialized journal. Rollback creates another backup. Backup retention is currently manual. Atomic moves and directory synchronization are required and have only been exercised on local macOS APFS. An external editor can race the final write after optimistic checks; use offline edits or serialize maintenance with apply.

An invalid edit retains the last valid in-memory snapshot, but that snapshot is not separately persisted for a restart. Unsupported schema versions prevent loading. Staged reports contain field classifications but there is no machine-generated editor schema yet. Imported unknown data is preserved, not automatically executable. File reload is explicit.
