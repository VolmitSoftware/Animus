# Manual behavior acceptance harness

This harness tests explicitly requested JSON programs against existing NPC definitions on the private behavior owner thread. Targets are Paper, Spigot and Folia 26.1.2, 26.2 and 26.3. All physical NPC services stay disabled. It grants no world access, automatic trigger, preview, privileged command, durable quest state or general Folia simulation.

`acceptance.json` lists the 26 required live checks. All 26 pass on Paper, Spigot and Folia 26.1.2, 26.2 and 26.3 on merged master, run through `qa/multiplexor/gate.py`.

## Build inputs

Use Java 25 and the reviewed standalone public API JAR:

```sh
python3 qa/behaviors/build-fixtures.py --api-jar animus-api/build/libs/animus-api-1.1.0-SNAPSHOT.jar
python3 qa/behaviors/verify-fixtures.py --module-fixtures <generated-fixture-directory>
python3 qa/behaviors/build-plugin-fixture.py --spigot-api <exact-api-jar> --plugin <reviewed-plugin-jar>
python3 -m unittest discover -s qa/behaviors -p 'test_*.py' -v
```

`build-fixtures.py` produces four trusted module JARs with generated JSON manifests and a hash receipt. Compilation uses only the public API/JDK and an explicit shared fixture dependency. The observer builder compiles only against the Spigot API/JDK and attributes its artifact to the exact reviewed plugin. These are separate fixtures; no private core dependency is on a module compilation classpath. Plugin and observer settings are TOML; behavior documents, receipts and snapshots are JSON.

- `qabprobe` owns a 256-event recorder and at most 64 held raw futures. It exports one fixture package, creates no threads and accesses no world or definition data. Its disable hook releases remaining holds.
- `qabehavior` exposes a typed record action, true/false conditions, hold and intentional failure actions. Cancellation changes its captured token without completing the raw future.
- `qabehaviorctl` independently depends on `qabprobe`, providing inspect, explicit release and reset. Reset rejects unfinished holds. The fixture map is bookkeeping; actual host/engine counters must establish quiescence.
- `qabehaviorbad` intentionally stages one valid capability and then an unsupported schema keyword. Production startup must fail the module and publish neither capability.

The three valid modules publish eight capabilities. `ProbeHarness.java` performs 14 fixture self-checks through a plain JDK loader/fake registrar, including bounded retention and raw-future cancellation semantics. This validates the fixtures, not production discovery, schema validation or interpreter behavior. The Python checks reject misleading evidence parsing, unchanged/rejected reload generations, preexisting-file cleanup and symlink ancestors.

## Managed scenario

After the production source and harness are frozen and actual Multiplexor has produced a reviewed artifact:

```sh
python3 qa/multiplexor/behaviors.py \
  --build-run <reviewed-run> --artifact-sha256 <reviewed-sha256> \
  --project paper --version 26.3 \
  --module-fixtures <generated-fixture-directory> \
  --observer <generated-AnimusBehaviorFixture.jar>
```

The wrapper uses fresh `animus-behaviors-<platform>-<version>` isolated instances, pinned exact server bytes and actual Multiplexor deployment/start/stop. It performs three starts:

1. **Cold disable:** empty module/behavior/NPC fixture sets, then official plugin disable from the supported global management scheduler immediately after the observer enables. No status, run, inspect, action, engine-node setup or diagnostic prewarming occurs before disable. The enabled service already owns its coordinator. The observer reads existing fields and exact primitive/zero-duration method handles, avoiding closed-archive method scans. It records the ordinary scheduler race and waits for coordinator/module/file quiescence and zero behavior threads. Deterministic close-before-owner-start classloader tests remain separate evidence.
2. **Manual execution:** real module/schema startup, read-only validation, dormant execution, typed values, branches/repetition/routines/parallel join, logical final wait, missing capabilities, argument refusal, execution budgets, cancellation with retained raw work, behavior reload/revision/source changes, definition edit/despawn/delete and bounded invocation capacity. Physical Folia despawn is not asserted: the test records the persisted `spawned` intent and revision transition.
3. **Restart and pending disable:** unchanged accepted definition/behavior bytes survive a real stop/restart, while interrupted transient execution UUIDs do not resume. A new explicit held execution then faces official plugin disable. The observer verifies prompt admission closure, post-disable refusal, actual module cleanup, raw-stage completion and coordinator/file quiescence.

The basic source contains ten record actions/two conditions, exact `Long.MAX_VALUE`, decimal, boolean and Unicode bindings, three routine iterations and two joined branches. The root-only wait is 60 logical ticks so the harness can observe its reported due tick; no wall-clock completion SLA is inferred. Four invalid sources are installed only during read-only validation. Malformed and same-revision edits must produce an **APPLIED** scan with a strictly advancing generation, exact INVALID/CONFLICT entry and diagnostics, retaining the prior accepted program. A scan-wide rejection cannot satisfy these cases.

The configured engine has four in-flight slots. Cancelled raw handlers continue occupying all four; a fifth execution must report invocation-capacity waiting without entering its handler. Explicit release must then drain actual host/engine counters. Fixture release and process exit alone do not prove this.

## Evidence and cleanup

Every attempt gets a new immutable evidence directory with three phase logs, raw command offsets, snapshots, exact server/plugin/API/module hashes, fixture JARs and source archives. The wrapper rehashes current inputs and archived copies before passing. Failed/latest attempts remain visible and cannot borrow an older pass.

Only paths reserved by the current attempt after verifying an empty owned data tree enter cleanup ownership. Symlink ancestors are rejected before read/write/unlink. After the final actual disable, observed quiescence and managed stop, planned offline cleanup archives and removes those exact synthetic definition files, last-valid pointers and behavior sources. This ordering preserves the pending execution for the shutdown test. Immutable object/backups are retained as evidence. Emergency cleanup is reported separately and cannot produce a passing attempt. Preexisting rejected data is never claimed or deleted. All owned ports must be closed and unexplained error/exception logs fail the scenario.
