# Managed Java module acceptance

This directory provides synthetic module fixtures and managed acceptance scenarios for an explicitly captured Animus artifact. Building fixtures alone does not establish live verification. All modules are trusted test code and compile against the standalone public API and JDK, with explicit dependency JARs only where the fixture tests dependency linking.

`fixtures/src/example/animus/qa/ApiModule.java` registers bounded JSON actions for synthetic dormant NPCs whose group is `qa` and whose readable ID starts with `module-qa-`. It verifies parent API identity and ordinary private-class/resource refusal, then exercises asynchronous definition lookup, token capture, validation/apply, stale-token conflict, invalid identity/coordinate rejection and activation refusal. Typed true/false conditions and an explicitly privileged action check dispatch boundaries. It uses controlled synthetic JSON only and never opens private migration data. Startup and reverse resource cleanup emit fixed receipts.

Managed phases:

- Load a valid external reference module and a module with a declared exported dependency; prove class/API identity through actual invocation.
- Reject incompatible API/platform, missing/cyclic/duplicate identities, unsafe archives and namespace violations without partial capability registration; preserve unrelated module configuration.
- Exercise API edits and administrator edits through the same mutation lane, with exact revision/source-hash acknowledgments, stale-token rejection and unchanged bytes on invalid drafts.
- Preserve accepted definitions and module startup identity across an actual restart. Java binary changes become available only after a restart.
- Request shutdown while bounded synthetic work is pending, verify immediate admission revocation, cooperative completion accounting, reverse lifecycle/resource cleanup and eventual archive release.

`build-fixtures.py` compiles 15 real module archives with exact API/dependency provenance, plus a separately stored reference replacement binary. `validate-fixtures.py --fixtures <output>` runs those actual JARs against captured active core/API artifacts without a server, verifying 15 startup outcomes, 12 committed capabilities, dependency linking, API edits/conflicts/rejections, typed dispatch, unfinished-handler accounting and cleanup. A prior helper compilation error is retained in evidence; it did not modify production source.

The QA-only `fixture/` Bukkit plugin observes pure extension status and performs actual plugin disable through the public global management scheduler (or the ordinary scheduler on Spigot). It never reads entities, terrain or chunks. A retained runtime reference verifies post-disable dispatch refusal and eventual zero invocation/archive retention. `build-plugin-fixture.py` binds its source, exact Spigot API and target Animus artifact hashes.

`qa/multiplexor/modules.py` requires explicit artifact, module fixture, reference and observer paths. It creates dedicated `animus-modules-<platform>-<version>` instances and runs 12 grouped checks across `prepare`, `restart-and-disable`, and `disabled-start`. Each phase uses an actual managed server start. It archives exact receipts, module source/build provenance, NPC/checkpoint bytes, initial/final JAR hashes and cleanup. Java replacement is observed first remaining inactive across file reload, then taking effect after restart. The wrapper results below are separate from fixture preparation.

Existing native, Folia stationary and import regressions retain their original scenario bodies. Their optional `--instance-name` accepts only a matching `animus-r<N>-<platform>-<version>` name (imports add `import-`). `prepare-recheck.py` provisions fresh isolated native/Folia instances from pinned server bytes without starting them or copying old world/player data. The defaults remain unchanged. `.qa/runtime10-wrapper-review/review.json` records prior/current wrapper hashes and identical scenario ASTs after removing only the new override argument/assignment.

These checks cover trusted extensions and dormant definition data. They do not prove a behavior interpreter, arbitrary Java isolation, graphical gameplay or full Folia simulation.

Runtime10 build `run-1793811646388580-96533` passed all 12 checks on Paper, Spigot, and Folia 26.1.2, 26.2, and 26.3: 108 checks across nine isolated instances and 27 actual managed starts. The plugin SHA-256 is `a6b3a8632f37595e55959c4f0cc0bac2a39ea73a7d57cbb863c5e5a7e886a601`; the public API SHA-256 is `7d047849886f384283626ac98175a8d246634623e2f07fd22640f2f153c54911`. All nine first-attempt scenarios passed, stopped their servers, removed their synthetic NPC definitions and recovery pointers, and preserved unrelated module configuration.

Independent acceptance is recorded under the retired QA workspace's `animus-evidence/audits/modules-runtime-10-final-1/audit.json`, SHA-256 `0406d500cdf500e1186dd4998868dd267460a1221038026ba49758bdc35b1c3d`. It verifies current and archived harness/fixture sources, exact deployed server/plugin/module bytes, phase-specific command receipts, revisions and checkpoint payloads, deliberate rejection diagnostics, reverse cleanup and closed ports. A harmless Multiplexor lock-contention retry remains recorded. These results retain the trusted-module and dormant-data scope above.
