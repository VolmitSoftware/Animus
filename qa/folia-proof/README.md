# Folia stationary preview acceptance

This suite exercises only Animus's opt-in, viewer-private stationary player preview. It does not enable or certify the general Folia NPC runtime, navigation, remote control, combat, graphical appearance, or performance parity.

The wrapper uses existing isolated ServerMultiplexor instances and a recorded build run. Every attempt preserves exact server/plugin/fixture/harness hashes, native wire receipts, server logs, commands, gate status, and closed-port checks. `result.json` updates while a run is active; an interrupted or failed newest attempt cannot fall back to an older success. Historical runtime6 evidence is separate.

Compile the separate Folia fixture against the selected artifact and an exact cached Folia dependency directory:

```sh
python3 qa/folia-proof/build-fixture.py \
  --plugin '<recorded artifact.jar>' \
  --libraries '.qa/platform-tests/folia-alpha/folia-26.3-6/libraries'
```

Run all three phases with the fixture path returned above:

```sh
python3 qa/multiplexor/folia-proof.py \
  --build-run '<recorded run ID>' --version 26.3 \
  --fixture '<fixture output>/AnimusFoliaFixture.jar' \
  --skin-file '<local signed skin JSON>' --phase all
```

`main` exercises separate regions, actual packet presentation, server-authoritative interaction, cancellation, and lifecycle invalidation. `bounds` exercises concurrent viewer/file/callback limits and exhausts the lifetime admission ceiling while the synthetic connection's Netty loop is deliberately stalled. `shutdown` disables the actual Animus plugin through Folia's global management scheduler while skin, watch, and owner callbacks are pending. The fixture remains enabled to inspect pure counters and event-loop cleanup before Multiplexor stops the server.

All fixture world/entity access runs on the synthetic viewer's owner, with explicit rectangle admission before terrain access. Fixture setup intentionally teleports synthetic creative players and prepares small arenas; those setup loads are excluded from no-load claims. The distant-footprint probe calls the actual production terrain admission method, observes owner-thread chunk events, and compares locked native ticket-count snapshots. It does not claim to detect every transient ticket or reproduce an actual three-block-ahead command at an unowned boundary. The separate join probe uses actual delivery flags without modifying them.

The IO/Netty/owner stalls are QA-only, bounded by watchdogs, and operate only on these isolated servers. No skin texture or signature is printed. All native codecs come from each exact 26.1.2/26.2/26.3 server jar; no protocol alias or packet-schema fallback is used. The strict auditor independently determines whether the nine scoped gates are complete, partial, or failed.

## Recorded runtime8 checkpoint

The full three-phase suite passed on the recorded Multiplexor build `run-1793807521311830-32660`, plugin SHA-256 `5bd4a77deef1dc7ce6fa6b0de1809305fe080775aa516a3da6ea37594ceab2cf`. Each target passed 34 main, 11 bounds, and 5 shutdown checks. This establishes the limited stationary-preview behavior covered above, with the stated terrain/graphical limitations.

| Exact target | Native checks | Evidence directory under the retired QA workspace's `animus-evidence/` |
|---|---:|---|
| Folia 26.1.2 build 8 STABLE | 50 | `animus-folia-26-1-2/folia-proof-1793807854819201000` |
| Folia 26.2 build 7 BETA | 50 | `animus-folia-26-2/folia-proof-1793807854825572000` |
| Folia 26.3 build 6 ALPHA | 50 | `animus-folia-26-3/folia-proof-1793807854822298000` |

The final logs contain no errors, exceptions, or movement warnings. All three loopback ports were independently rechecked closed. The receipt `runtime-8-folia-qa-cleanup-1793808085426333000.json` records zero remaining owned NPC definitions/recovery pointers and zero active fixture resources. Earlier failed attempts remain intact, including runtime7's genuine eventless-teleport lifecycle regression; runtime8 verifies nearby and same-position teleport invalidation for queued, visible, and reentrant interactions.

The independent strict audit passed all 150 checks: `audits/folia-runtime-8-final-1793808200574002000/audit.json`, SHA-256 `71cae1000a7052bb25ba4c9270b1e78cc1c5aee0c8882350c8d27d2edf723fd1`. It verifies the exact deployed artifact, current and archived harness sources, native transcripts, fixture provenance, logs, cleanup, and closed ports while preserving the stated scope limits.
