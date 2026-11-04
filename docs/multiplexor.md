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

Use a new source directory for later captures. Save the emitted run ID. Source capture and build output are distinct evidence: only a successful Multiplexor run proves the build executed. The `bukkit` build runs the exact Spigot API compilation matrix and packet tests; its required `core` target runs core tests. `converter` builds the local CLI. The individually selectable `bukkit-api-26.1.2`, `bukkit-api-26.2`, and `bukkit-api-26.3` targets provide narrower compile checks.

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

After runs finish, refresh the evidence index with `python3 qa/multiplexor/report.py`. It retains links to failed attempts, uses the latest result for each target, and keeps expected Mineflayer protocol limitations distinct from runtime results.

Folia packet rendering remains deliberately unavailable until region-safe world snapshots are proven. Folia checks establish managed startup and file-command behavior only. Spigot has API compile checks but no runtime evidence here. Fabric, Forge and NeoForge remain deferred. Protocol assertions do not establish graphical fidelity, performance, controllable mounts, combat parity or production NPC activation.

## Recorded verification — 2026-11-04

Multiplexor build `run-1793776425697665-40479` passed `animus:core`, `animus:bukkit` and `animus:converter`. Its plugin SHA-256 is `ea0ef82d32cfa16594fad2fb96f615462fdab32be161cdcb415d636a19ae0a6f`, matching the independently built local artifact. Both source snapshots and the pinned VolmLib source hash are retained in the QA workspace.

| Platform | Exact Minecraft | Build/channel | Managed lifecycle | Native packet suite |
| --- | --- | --- | --- | --- |
| Paper | 26.1.2 | 74 stable | Passed, two starts | 38 checks passed |
| Paper | 26.2 | 135 stable | Passed, two starts | 38 checks passed |
| Paper | 26.3 | 240 stable | Passed, two starts | 38 checks passed |
| Folia | 26.1.2 | 8 stable | Passed, two starts | Deliberately disabled |
| Folia | 26.2 | 7 beta | Passed, two starts | Deliberately disabled |
| Folia | 26.3 | 6 alpha | Passed, two starts | Deliberately disabled |

The standard Multiplexor player scenario also passed both `prepare` and `verify` phases on Paper 26.1.2 with that artifact. All owned servers were stopped and their ports checked closed. The final native server logs contain no errors, exceptions or movement warnings.

The local evidence index is `.qa/multiplexor-workspace/animus-evidence/index.json`; it links exact server/plugin hashes, build records, per-target logs and every native result. It preserves the earlier 26.3 cow-registry decoding failure and the later passing deterministic regression. Read-only Spigot cache inventory is in `spigot-cache-inventory.json` beside the index; only API jars were available.
