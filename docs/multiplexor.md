# Animus checks through ServerMultiplexor

Animus declares real Multiplexor build targets in `gradle/multiplexor.gradle`. Server checks use a dedicated isolated Multiplexor workspace, its artifact deployment, and its runtime start/console/stop operations. `qa/multiplexor/definitions.mjs` runs through the standard `gameplay run` scenario runner. `qa/packet-client.mjs` is an external exact-codec client attached to a managed server; it is not a Mineflayer scenario.

The native client is necessary because the installed Multiplexor Mineflayer harness supports 26.1.2 but reports missing usable protocol data for 26.2 and 26.3. Keep those doctor failures in the evidence. Do not substitute a different protocol version. Native client adapters compile against and verify the exact Minecraft jar version.

## Build a captured source revision

Use a sibling ServerMultiplexor checkout and Java 25. The capture helper copies Git-visible Animus and VolmLib sources into a new directory, including uncommitted changes, and records every copied file hash. Ignored QA output and service-account files are excluded. VolmLib stays fixed while other agents work in its development checkout. Multiplexor then makes its own per-target build snapshots from that pinned input.

From the Animus root:

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
export MULTIPLEXOR_BUILD_HOME="$PWD/.qa/multiplexor-build-cache"
mkdir -p .qa/multiplexor-workspace
ln -s "$PWD/../ServerMultiplexor/MultiplexorApp" .qa/multiplexor-workspace/MultiplexorApp
python3 qa/multiplexor/capture-sources.py \
  --output .qa/multiplexor-sources \
  --manifest .qa/multiplexor-workspace/source-snapshot.json
../ServerMultiplexor/multiplexor --root "$PWD/.qa/multiplexor-workspace" storage keep-downloads on
../ServerMultiplexor/multiplexor --root "$PWD/.qa/multiplexor-workspace" projects scan "$PWD/.qa/multiplexor-sources" --json
../ServerMultiplexor/multiplexor --root "$PWD/.qa/multiplexor-workspace" projects build animus \
  --target bukkit,converter --jobs 2 --memory 8192 --workers 4 --packaging local --wait --json
../ServerMultiplexor/multiplexor --root "$PWD/.qa/multiplexor-workspace" projects status --json
```

Save the emitted run ID and preserve each source capture. Multiplexor's current catalog rejects duplicate project IDs when scanning a new source root into an existing workspace; it has no supported unregister command. Prefer a fresh QA workspace for a fresh capture. The development runtime-1/runtime-2 runs reused existing instances through a backed-up internal catalog edit that removed only the previous Animus/VolmLib project registrations before rescanning. Those backups are retained in the local workspace; this is an internal workaround, not a supported CLI operation, and should not be applied to a shared workspace.

Source capture and build output are distinct evidence: only a successful Multiplexor run proves the build executed. The `bukkit` build runs the exact Spigot API compilation matrix and packet tests; its required `core` target builds the standalone API and runs core tests. The core target records the API JAR with artifact role `api`; only runtime-role artifacts are deployed as plugins. Provenance collection distinguishes those artifact types and includes API sources in test-input lineage. `converter` builds the local CLI. The individually selectable `bukkit-api-26.1.2`, `bukkit-api-26.2`, and `bukkit-api-26.3` targets provide narrower compile checks.

Use the same `MULTIPLEXOR_BUILD_HOME` for later artifact delivery. Omitting it after overriding it during the build makes Multiplexor correctly reject an artifact outside the expected run directory.

## Managed server lifecycle

`qa/platform-smoke.py` downloads exact official Paper/Folia jars and verifies their published SHA-256. Its own standalone test results are separate from Multiplexor results. `qa/multiplexor/managed-runtime.py` consumes those verified downloads, creates fresh isolated managed instances, deploys the successful Multiplexor artifact, starts each server, sends actual console commands through Multiplexor's tmux console, and stops it. It does not launch Java directly.

Provision the requested exact versions with `platform-smoke.py` first. Stable is its default. Folia 26.2 build 7 requires explicit `--channel BETA --build 7`; Folia 26.3 build 6 requires `--channel ALPHA --build 6`. Keep these channel labels in reports. Reuse an existing accepted QA EULA file; the helper does not ask the user to accept a new agreement implicitly.

```sh
python3 qa/multiplexor/managed-runtime.py \
  --binary ../ServerMultiplexor/multiplexor \
  --workspace .qa/multiplexor-workspace \
  --build-home .qa/multiplexor-build-cache \
  --build-run RUN_ID \
  --java-home "$JAVA_HOME" \
  --accepted-eula /path/to/already-accepted/eula.txt
```

Each target validates exact-version startup and explicit packet capability, creates a synthetic dormant definition, validates it, restarts, checks the same UUID persisted, deletes it, and stops. Logs, deployed jar hashes, console commands and results live under `.qa/multiplexor-workspace/animus-evidence/`. Fresh readiness must come from the current server start; a previous `latest.log` cannot satisfy readiness. Failed attempts remain as `prior-runtime-*.json` and preserved logs when repeated.

## Multiplexor player scenario

Install the pinned gameplay harness through `gameplay setup`, then retain exact-version `gameplay doctor --version VERSION --json` results. Only 26.1.2 currently passes its protocol capability check.

```sh
../ServerMultiplexor/multiplexor --root "$PWD/.qa/multiplexor-workspace" gameplay setup
../ServerMultiplexor/multiplexor --root "$PWD/.qa/multiplexor-workspace" gameplay doctor --version 26.1.2 --json
../ServerMultiplexor/multiplexor --root "$PWD/.qa/multiplexor-workspace" gameplay run \
  "$PWD/qa/multiplexor/definitions.mjs" animus-paper-26-1-2 \
  --version 26.1.2 --start --stop-after --no-viewer --username AnimusCommandsQA --command prepare --json
../ServerMultiplexor/multiplexor --root "$PWD/.qa/multiplexor-workspace" gameplay run \
  "$PWD/qa/multiplexor/definitions.mjs" animus-paper-26-1-2 \
  --version 26.1.2 --start --stop-after --no-viewer --username AnimusCommandsQA --command verify --json
```

The two phases exercise create, edit, clone, inspect, validate, reload and delete through a real player, preserving UUID and revision across the managed restart. Multiplexor records the scenario hash, installed plugin hash, messages, steps and server version in its normal gameplay reports.

Generate the standard Multiplexor feature matrix with the Animus manifest:

```sh
node ../ServerMultiplexor/MultiplexorApp/tool/mineflayer/src/coverage.mjs \
  .qa/multiplexor-workspace/consumers/plugin-consumers/state/gameplay-tests \
  qa/multiplexor/coverage.json
```

This matrix covers the standard `gameplay run` scenarios. External native-codec packet results have their own reports.

## Native packet proof on managed servers

After the managed lifecycle suite has created and deployed the Paper instances, run the native client through the managed helper:

```sh
python3 qa/multiplexor/packet-proof.py \
  --binary ../ServerMultiplexor/multiplexor \
  --workspace .qa/multiplexor-workspace \
  --build-home .qa/multiplexor-build-cache \
  --version 26.3 \
  --skin-file /private/qa/skins/HASH.json \
  --fixture .qa/event-fixture/AnimusProofFixture.jar \
  --navigation --skin-race
```

Both optional fixtures are local QA inputs. Build the event fixture with `qa/build-fixture.py`; never install it in production. Omit these flags to skip their assertions. Repeat with each exact approved version. The helper grants operator status only to its synthetic owner, prepares an arena, starts the server through Multiplexor, runs the external native client, records hashes and logs, and stops through Multiplexor even if assertions fail. It preserves separate native-client reports under `.qa/protocol-cache/results/`.

The navigation flag requires a build containing the proof path diagnostic. It verifies bounded world capture, a route result without moving the body, rejection of a solid goal, and cancellation. The skin flag verifies that an asynchronous signed-skin spawn cannot outlive an immediate clear. The helper explicitly loads its small disposable arena before provisioning, unloads it afterward, and checks that the owned server port is closed after shutdown.

The native client loads synchronized registry entries and tags before binding play codecs. Its regression fixture summons one tagged cow and verifies actual metadata decoding, then removes that cow. Cleanup targets only the synthetic tag. Native metadata decoding failures remain fatal.

After runs finish, refresh the currently selected build with `python3 qa/multiplexor/report.py`. It reads that selection from the existing index, verifies the recorded runtime artifact hash, partitions results by artifact hash, and retains failed attempts and separate audit provenance. To select a new build, use `python3 qa/multiplexor/report.py --build-status .qa/multiplexor-workspace/build-status-NAME.json --source-snapshot .qa/multiplexor-workspace/source-snapshot-NAME.json`, replacing `NAME` with the matching capture; the status file must record one successful `projects status RUN_ID --json` result. A fresh workspace requires this explicit selection. The reporter writes `index.json` atomically and leaves immutable reports such as `runtime-3-final.json` untouched; it never defaults to an older baseline. Mineflayer protocol limitations remain distinct from runtime results.

General Folia NPC rendering remains unavailable. The historical lifecycle checks here establish startup/file commands; the separate runtime8 stationary-preview suite below has its own gameplay evidence. Exact Spigot servers now have separate managed lifecycle and native proof evidence; see [Spigot provisioning](spigot-verification.md). Fabric, Forge and NeoForge remain deferred. Protocol assertions do not establish graphical fidelity, performance, controllable mounts or combat parity. The persisted-runtime suite below establishes its own narrower activation and lifecycle behavior.

## Persisted runtime and real-combat scenarios

`qa/multiplexor/runtime-npcs.py` deploys an explicit recorded build, runs a production prepare phase, performs a real managed stop/restart, and verifies the same spawned UUID and skin. It then tests delivered visibility, concurrent viewers, permission separation, stale/foreign IDs, cancellation, dimension changes, reconnect, Unicode edits, last-valid retention and deletion. Optional `--combat` adds supported real damage and bounded ordinary-zombie targeting, cancellation, native state restoration, import review policy and closed-bridge rejection and real-mob reacquisition checks. These commands never use the viewer-isolated proof for real damage.

Build `qa/build-fixture.py` with `--plugin` pointing to that exact Multiplexor artifact. Its `build.json` records the fixture, source, API and tested plugin hashes. The fixture is QA-only and must not be shipped with Animus. Run against the already provisioned isolated instance:

```sh
python3 qa/multiplexor/runtime-npcs.py \
  --binary ../ServerMultiplexor/multiplexor \
  --workspace .qa/multiplexor-workspace \
  --build-home .qa/multiplexor-build-cache \
  --build-run RECORDED_BUILD_RUN --project paper --version 26.3 \
  --fixture .qa/event-fixture/AnimusProofFixture.jar \
  --skin-file /private/qa/skins/HASH.json --combat
```

Use `--project spigot` for the exact Spigot instance. `--combat-only` is a diagnostic shortcut; it does not prove restart or production lifecycle. Each run stores `production-*/result.json`, per-phase client receipts, scenario/codecs hashes, server logs, deployed plugin hashes and confirmed port closure. Failed attempts remain archived. A result from an older artifact must not satisfy a later artifact's coverage gate.

The shared native process harness is `qa/protocol-client/client.mjs`. Its compiled helper classes are isolated per run so parallel exact-version scenarios cannot race each other's compilation. Stock Multiplexor Mineflayer and external native-codec coverage remain separately reported.

For the movement/recovery suite, add `--control --movement --inline-recovery` to the combined command with `--combat`. It performs two managed restarts: a malformed position edit, then a malformed enabled inline waypoint setting. Both edits remain byte-identical while their previous definitions recover; repairing and reloading permits subsequent edits. The complete phases are `prepare`, `recover-position`, `verify`, `combat`, `control`, `movement`. `--motion-only` runs a diagnostic subset and cannot establish the full lifecycle gate. Larger route fixtures use JSON files and reload rather than exceeding the native chat-command length limit.

`qa/multiplexor/build-evidence.py` preserves test XML after Multiplexor removes completed build sandboxes. It requires explicit build/source records, verifies captured source and artifact hashes, and rejects missing core/Bukkit results or skipped exact API tasks. Executed test origins must uniquely match the task and job time window. Cached counts use a previously verified archive plus matching source/build/dependency inputs; the current Gradle cache key is not directly observed. Each output directory is new and immutable, with collector and archive hashes. Build evidence remains separate from live scenario evidence.

## Historical proof verification — 2026-11-04

Multiplexor build `run-1793776425697665-40479` passed `animus:core`, `animus:bukkit` and `animus:converter`. Its plugin SHA-256 is `ea0ef82d32cfa16594fad2fb96f615462fdab32be161cdcb415d636a19ae0a6f`, matching the local artifact at that historical checkpoint. Both source snapshots and the pinned VolmLib source hash are retained in the QA workspace.

| Platform | Exact Minecraft | Build/channel | Managed lifecycle | Native packet suite |
| --- | --- | --- | --- | --- |
| Paper | 26.1.2 | 74 stable | Passed, two starts | 38 checks passed |
| Paper | 26.2 | 135 stable | Passed, two starts | 38 checks passed |
| Paper | 26.3 | 240 stable | Passed, two starts | 38 checks passed |
| Folia | 26.1.2 | 8 stable | Passed, two starts | Deliberately disabled |
| Folia | 26.2 | 7 beta | Passed, two starts | Deliberately disabled |
| Folia | 26.3 | 6 alpha | Passed, two starts | Deliberately disabled |

The standard Multiplexor player scenario also passed both `prepare` and `verify` phases on Paper 26.1.2 with that artifact. All owned servers were stopped and their ports checked closed. The final native server logs contain no errors, exceptions or movement warnings.

The local evidence index is `.qa/multiplexor-workspace/animus-evidence/index.json`; it links exact server/plugin hashes, build records, per-target logs and every native result. It preserves the earlier 26.3 cow-registry decoding failure and the later passing deterministic regression. The historical `spigot-cache-inventory.json` found only API jars; later official server provisioning and live results are documented in [Spigot verification](spigot-verification.md).

## Historical runtime-3 build — 2026-11-04

Recorded build `run-1793780274008222-12539` passed core/Bukkit/converter with147 core and35 Bukkit tests and all three exact API compilation tasks. Core test results were reused only after verifying identical source hashes; Bukkit tests executed. The distribution SHA-256 is `5be1c11fc676a61c738163b14273fc6da39aa93fad6ba35dbdea11ccc68fb470`, matching the independently built canonical local distribution. Packaged metadata uses **A Manager for all things NPC.**

Detailed provenance is under `animus-evidence/build-runtime-3/`; the current aggregate reports distinguish older proof records from this runtime artifact. All six exact Paper/Spigot targets passed24 production lifecycle plus14 combat assertions each. All three enhanced Folia lifecycle suites passed with rendering/combat explicitly disabled. `animus-evidence/final-runtime-audit.json` independently verifies matching artifacts/descriptions, all nine ports closed, no remaining synthetic NPC files and no errors or movement warnings in final logs. Failed attempts remain archived.

| Runtime-3 artifact scenario | 26.1.2 | 26.2 | 26.3 |
| --- | --- | --- | --- |
| Paper persisted runtime + manual combat |24 +14 pass|24 +14 pass|24 +14 pass|
| Spigot persisted runtime + manual combat |24 +14 pass|24 +14 pass|24 +14 pass|
| Folia lifecycle, unavailable-feature rejection, restart |stable8 pass|beta7 pass|alpha6 pass|
| Folia rendering/combat/region transfer |Disabled|Disabled|Disabled|

The six combined scenarios use ordinary real cows/zombies for the initial combat adapter. They do not establish full combat-module or PvP/protection parity, graphical rendering or measured performance.


## Verified runtime-6 build — 2026-11-04

Build `run-1793803535545514-80447` executed201 core and43 Bukkit tests plus all three exact API compilations. Artifact SHA-256: `c89adc35796afe5c33d85cf3e0f853ae2cd0f68123e3c1c16a4e77abd93f9576`. Canonical local distribution matches. All six Paper/Spigot targets passed80 native assertions each; Folia26.1.2 stable8,26.2 beta7 and26.3 alpha6 each passed27 storage/recovery/capability-limit checks. Folia gameplay remains a separate unverified milestone.

`qa/multiplexor/audit-runtime.py` independently verifies all complete phases, native receipts, both recovery edits, artifact/server/source hashes, cleanup, final logs and closed ports. It rejects diagnostic subsets and never falls back from a newer failed attempt to an older passing one. Run it with explicit `--build-status`, `--artifact-sha256` and a new `--output` directory. Twenty regression tests pass.

The final immutable audit is `animus-evidence/audits/runtime-6-final-1793805746971943000/audit.json`, SHA-256 `a6c92b514588352ff6167f4e404b3f02e1018299d603289b18123b79f790f862`. It contains480 native and81 Folia checks, with26 failed attempts preserved. `runtime-6-final.json` links the independent audit and build provenance. All nine owned servers stopped with no remaining synthetic definitions or recovery pointers.

The explicit `--reviewed-harness` input for this audit is `animus-evidence/runtime6-wrapper-receipt-review/approved-harness.json`. It permits only the independently reviewed vanilla time-command receipt matcher difference, bound to this build and exact source hashes; AST and patch checks reject broader changes. A prepare-state recovery annotation is separately compared to the validated top-level recovery receipt. Managed `server.jar` symlinks are recorded, resolved and rehashed; owned definition/checkpoint symlinks still fail cleanup. These exceptions are visible in the audit rather than hiding differences.

The native test client now follows stationary position reminders and normal respawn after a saved dead-player login. Isolated arenas suppress incidental natural spawning before restoring the configured combat difficulty. Failed investigation attempts remain visible. This validates headless protocol and server authority, not graphical rendering or performance.


## Verified runtime-8 Folia stationary preview

Build `run-1793807521311830-32660`, SHA-256 `5bd4a77deef1dc7ce6fa6b0de1809305fe080775aa516a3da6ea37594ceab2cf`, passed the separate Folia suite on26.1.2 stable8,26.2 beta7 and26.3 alpha6. Each target ran34 main,11 bounds and5 shutdown checks (150 total). It uses `qa/multiplexor/folia-proof.py` and the isolated fixture/client under `qa/folia-proof/`; see that directory's README for reproduction and exact reports.

The final independent audit is `animus-evidence/audits/folia-runtime-8-final-1793808200574002000/audit.json`, SHA-256 `71cae1000a7052bb25ba4c9270b1e78cc1c5aee0c8882350c8d27d2edf723fd1`. `qa/multiplexor/audit-folia-proof.py` requires explicit artifact/build inputs, all three phases, native/source/fixture hashes, actual plugin-disable receipts, late-work rejection, final wire/profile cleanup and closed ports.21 auditor tests pass;3 failed attempts remain preserved. Immutable aggregate: `runtime-8-folia-final.json`.

Scope is viewer-private stationary previews. Actual owned/loaded-but-unsent command refusal and a separate unowned admission-helper probe are distinguished. The latter observes no target chunk events and unchanged bounded ticket counts, but does not prove an actual unowned command-boundary case, partial four-chunk ownership or absence of transient tickets. Those edges remain explicitly unverified. General Folia runtime/navigation/control/combat remain unavailable, and no graphical or performance claim is made. Earlier Paper/Spigot runtime results stay tied to their own artifact.
