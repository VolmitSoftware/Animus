# Exact Spigot verification

Spigot runtime verification uses Multiplexor's managed BuildTools provider, followed by isolated managed server lifecycle and native-codec client suites. API compilation alone does not establish runtime compatibility.

- [x] Locate and inspect Multiplexor's managed BuildTools implementation and documentation.
- [x] Resolve official manifests for 26.1.2, 26.2, and 26.3; each accepts Java 25.
- [x] Produce executable Spigot jars through the managed provider and retain provenance.
- [x] Validate startup, dormant definition persistence, restart, and cleanup through Multiplexor.
- [x] Run exact-version native packet suites on the actual Spigot servers.
- [x] Exercise the production NPC lifecycle through managed restart and exact native clients.
- [x] Verify coordinate and inline-navigation errors retain the last valid definition across two managed restarts.
- [x] Exercise bounded remote-control input, executed navigation, and reusable waypoint files on every exact Spigot target.
- [x] Verify reviewed import applications, duplication, stale-edit rejection and rollback across actual restarts on every exact Spigot target.

The official manifests resolve to Jenkins builds 4632, 4648, and 4664 respectively. BuildTools Jenkins 201 is pinned before provisioning. Each executable jar's SHA-256 matches the official manifest's `hashes.Spigot`; the provisioner also verifies the embedded server hash, exact Minecraft version, and bootstrap entry point.

| Exact target | Executable Spigot SHA-256 | Baseline lifecycle | Baseline packet suite |
| --- | --- | --- | --- |
| 26.1.2 | `95f871fd6d055ba10b5a058768ddad43b0be0286480c8eba435c835c95d5f19c` | Passed, including restart | 38 passed |
| 26.2 | `675c766c5e4d13184a10316fbc89224de975b0c83d404a01462618a0742d406a` | Passed, including restart | 38 passed |
| 26.3 | `d64fb48abc0a02c09273487248b641005b363c5070860d97bc13a35c8808a4e4` | Passed, including restart | 38 passed |

These 2026-11-04 baseline runs used Animus artifact `ea0ef82d32cfa16594fad2fb96f615462fdab32be161cdcb415d636a19ae0a6f`. They cover signed skin payloads, eight entity types, interaction authority, static passengers, navigation planning, synchronized registry metadata, visibility isolation, cancellation, and cleanup. They do not establish production persisted-NPC or combat behavior. The first client attempt identified the QA runner's incorrect dependency path; Spigot uses `bundler/libraries`. Failed attempts remain in the evidence archive, followed by the passing reruns.

Provenance is in `.qa/spigot-builds/source-evidence.json`; native results were under `animus-evidence/animus-spigot-*/native-*/result.json` in the retired separate QA workspace. All three owned servers stopped and their ports closed after the suites.

The runtime6 production runs use Multiplexor build `run-1793803535545514-80447`, Animus SHA-256 `c89adc35796afe5c33d85cf3e0f853ae2cd0f68123e3c1c16a4e77abd93f9576`, and the packaged description `A Manager for all things NPC.`. Each target passed 80 checks: 12 preparation, 6 intermediate recovery, 14 final lifecycle/recovery, 14 combat, 18 remote-control proof, and 16 navigation checks. The earlier runtime-3 results remain archived with their original artifact hashes.

| Target | Lifecycle and recovery | Combat | Remote-control proof | Navigation | Total |
| --- | --- | --- | --- | --- | --- |
| Spigot 26.1.2 | 32 passed | 14 passed | 18 passed | 16 passed | 80 |
| Spigot 26.2 | 32 passed | 14 passed | 18 passed | 16 passed | 80 |
| Spigot 26.3 | 32 passed | 14 passed | 18 passed | 16 passed | 80 |

The lifecycle scenario preserves identity across two actual managed restarts. An invalid coordinate file first exercises durable recovery; an invalid enabled navigation module is then rejected during live reload and retained unchanged across the second restart. In both cases the same last-valid NPC remains available, edits conflict until repair, and restoring the exact valid bytes permits reload and subsequent edits. Private previews and persisted NPC packet handlers also coexist with distinct IDs and events; clearing a preview preserves the production NPC.

| Target | Final production evidence directory under `animus-evidence/` |
| --- | --- |
| Spigot 26.1.2 | `animus-spigot-26-1-2/production-1793805099059386000` |
| Spigot 26.2 | `animus-spigot-26-2/production-1793805112453440000` |
| Spigot 26.3 | `animus-spigot-26-3/production-1793805300353871000` |

Combat coverage includes real-target damage without a fabricated entity attacker, both event cancellation paths, reach/obstruction checks, ordinary-zombie pursuit, bounded replanning, restoration of original goals/target/path, virtual health, closed-bridge cleanup, import review gates, and despawn cleanup. This is the manual combat and zombie adapter foundation; it does not establish complete combat parity, ranged weapons, WorldGuard protection, or graphical appearance.

Remote control is an explicitly unmounted, viewer-private proof. Exact native key packets exercise held/released input, diagonal normalization, opposing directions, fresh generations, heartbeat expiry, flooding, wall/support collision, leash bounds, foreign viewers, and logout cleanup. These results do not establish mounted riding, camera possession, or production NPC control.

Navigation coverage includes authoritative positions matching native movement packets, moving interactions and cancellation, changing obstacles, detours, whole-block rise/fall, swimming, flying, waypoint ordering/waits/loops, reusable JSON routes, completed-route reload behavior, invalid-route retention, and despawn cancellation. The unloaded-destination scenario observes both a loaded source and an absent destination before and after suspension. The movement model remains conservative and kinematic; partial blocks, graphical animation, and complete vanilla movement parity are not certified.

The independent `animus-evidence/runtime-6-qa-cleanup.json` records the nine target report hashes, closed ports, clean logs, and absence of owned NPC sources and recovery pointers. All final logs contain no `ERROR`, `Exception`, or movement warnings. Earlier attempts retain their original outcomes. Each production evidence directory includes fixture source/dependency hashes, the compiled fixture hash, wrapper source, per-phase native-client source hashes, and immutable transcripts. The same artifact passed all three Paper suites (80 checks each) and all three Folia storage/restart/unsupported-operation suites (27 console checks each); Folia rendering, combat, and navigation remain explicitly unavailable.

Strict final acceptance passed in `animus-evidence/audits/runtime-6-final-1793805746971943000/audit.json` (SHA-256 `a6c92b514588352ff6167f4e404b3f02e1018299d603289b18123b79f790f862`). It independently verifies all six complete native suites, three Folia suites, deployed artifacts, exact harness/fixture provenance, recovery bytes, cleanup, and closed ports. All 26 failed historical attempts remain preserved.

One earlier Paper diagnostic found a saved synthetic peer at zero health: its preserved runtime-5 log records that an ambient zombie killed it. The native client now observes health and requests a normal respawn when necessary; test arenas disable natural spawning and freeze daytime. No NPC-runtime change was needed for that incident. A separate Spigot 26.3 retry accepted the verified “already at time marker” response to the same setup command. The exact one-line matcher difference and two immutable wrapper hashes are reviewed in `animus-evidence/runtime6-wrapper-receipt-review/approved-harness.json`; gameplay commands and assertions did not change.

Sources: [Multiplexor build documentation](https://github.com/VolmitSoftware/docs/blob/master/servermultiplexor/04-builds-and-content.md), official Spigot manifests for [26.1.2](https://hub.spigotmc.org/versions/26.1.2.json), [26.2](https://hub.spigotmc.org/versions/26.2.json), and [26.3](https://hub.spigotmc.org/versions/26.3.json), and [BuildTools Jenkins 201](https://hub.spigotmc.org/jenkins/job/BuildTools/201/).

From the Animus checkout:

```sh
python3 qa/multiplexor/provision-spigot.py \
  --java-home /opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home \
  --output .qa/spigot-builds
```

Builds run in the shared Multiplexor workspace and its default build cache (see [Multiplexor checks](multiplexor.md#workspace)); each exact release gets only its own ignored Maven cache under `.qa/spigot-builds/`, and builds run one at a time by default. The provisioner does not change workspace settings. Java subprocesses have a 4 GiB heap cap and four active processors. The provisioner invokes `build spigot --mc EXACT`; it does not substitute another release or start a server. Its live `evidence.json` and completed `latest.json` record failures as well as successful builds. BuildTools output, official metadata, binary hashes, and command logs remain under `.qa/spigot-builds/`.

The generated `source-evidence.json` is suitable for `qa/multiplexor/managed-runtime.py --source-evidence ... --projects spigot`. Server runs must use isolated instances, an existing accepted QA EULA, Java 25, and a recorded successful Multiplexor Animus build. Production NPC, combat, graphical rendering, and performance claims require their own demonstrated scenarios.

The production scenario uses the same exact native protocol helper and a separate command script. It preserves one spawned definition across a real managed restart, then checks two viewers, stable UUIDs, fresh viewer entity IDs, dimension/distance changes, reconnect, Unicode profile names, invalid activation, invalid file reload, cancellation, and deletion. It requires the updated QA event fixture. The wrapper always stops its owned instance and records cleanup failures as failures:

```sh
python3 qa/multiplexor/runtime-npcs.py \
  --build-run RECORDED_BUILD_RUN --project spigot --version 26.3 \
  --fixture .qa/event-fixture/AnimusProofFixture.jar \
  --skin-file PATH_TO_PRIVATE_SKIN_JSON \
  --combat --control --movement --inline-recovery
```

The full 80-check suite requires the signed skin fixture and all four scenario flags shown above. Smaller diagnostic modes remain explicitly separate and do not satisfy the complete matrix gate. New results are recorded under `production-*/result.json`; prior packet proofs, failures, and production runs are preserved.

The later runtime9 import application suite uses build `run-1793809087767551-52812`, plugin SHA-256 `72c41097d3e3c7aea3ce7cd4956d5b3073b26900f27c0dddbc1db7486cf5540d`, and separate managed instances. Each exact Spigot release passed 14 data/administration checks with three real server starts. Staging leaves definitions and checkpoints unchanged; applying a reviewed plan keeps imported NPCs dormant, duplicates remap only normalized resolved references, and stale administrator edits reject the whole replacement. Rollback after restart restores exact previous definition/checkpoint bytes, and the final restart confirms that removed records remain absent.

| Target | Runtime9 import evidence under `animus-evidence/` |
| --- | --- |
| Spigot 26.1.2 | `animus-import-spigot-26-1-2/application-1793809243354054000-57041/result.json` |
| Spigot 26.2 | `animus-import-spigot-26-2/application-1793809212064204000-55743/result.json` |
| Spigot 26.3 | `animus-import-spigot-26-3/application-1793809179781614000-54282/result.json` |

The same import suite passed on all three Paper and Folia targets, for 126 checks across nine instances. The retired QA workspace's `animus-evidence/runtime-9-import-cleanup-1793809290164652000.json` records all 18 earlier/new owned ports closed, all instances stopped, clean import logs, and no remaining NPC sources or recovery pointers. These newer tests cover persistence and commands only; they do not repeat the runtime6 gameplay suite or establish broader behavior parity. Public synthetic fixtures and reproduction instructions are in [the import QA notes](../qa/import-applications/README.md).

## Runtime10 extension and regression verification

The module/API integration was built by Multiplexor as `run-1793811646388580-96533`, plugin SHA-256 `a6b3a8632f37595e55959c4f0cc0bac2a39ea73a7d57cbb863c5e5a7e886a601`. Fresh `animus-r10-*` instances preserve the earlier accepted instances and their evidence. All three exact Spigot releases passed the complete 80-check native lifecycle/recovery/combat/control/movement suite, 12 trusted-module checks, and 14 import-application checks. The corresponding Paper suites also passed. Module and import workflows additionally passed all three Folia releases; these are separate from Folia gameplay support.

| Spigot release | Native evidence under `animus-evidence/` | Native / modules / imports |
| --- | --- | --- |
| 26.1.2 | `animus-r10-spigot-26-1-2/production-1793812233794593000/result.json` | 80 / 12 / 14 passed |
| 26.2 | `animus-r10-spigot-26-2/production-1793812233160540000/result.json` | 80 / 12 / 14 passed |
| 26.3 | `animus-r10-spigot-26-3/production-1793812232917326000/result.json` | 80 / 12 / 14 passed |

Independent audits under `animus-evidence/audits/` verify exact deployment and source hashes, phase receipts, durable recovery bytes, cleanup, and closed ports:

- `native-runtime-10-final-1/audit.json`: SHA-256 `7c433284557966d1d052df358ffc108964f832b6270fcd445c336ef307dfa33e`, six suites / 480 checks.
- `modules-runtime-10-final-1/audit.json`: SHA-256 `0406d500cdf500e1186dd4998868dd267460a1221038026ba49758bdc35b1c3d`, nine suites / 108 checks.
- `imports-runtime-10-final-1/audit.json`: SHA-256 `a979ccd3f8bd2bca5891fccca4705e5040b2e9d234f3a5f194f27acf91cc0acd`, nine suites / 126 checks.

The existing scenario bodies remain unchanged. `qa/multiplexor/prepare-recheck.py --round 10` creates only a fresh isolated server definition from the exact pinned server bytes; it neither starts the server nor copies prior world/player data. Run the native wrapper with `--instance-name animus-r10-spigot-26-3` and the same complete flags above. The optional name must match the platform and exact version. Public module fixtures, lifecycle limitations, and reproduction details are in [the module QA notes](../qa/modules/README.md).

The first Folia 26.3 import attempt completed its 14 assertions but failed strict log hygiene because JLine reported `Terminal has been closed` on its server-console thread during shutdown. The unchanged retry passed with clean logs. Both records remain preserved; this did not require a production or assertion change.

Final runtime10 cleanup independently checked all 45 explicitly owned managed QA instances, including preserved older checkpoints. Every instance was stopped, all 45 managed ports plus the historical Leaf port 65490 were closed, and no owned NPC definition or last-valid pointer remained. The receipt is `animus-evidence/runtime-10-owned-cleanup-1793813614583445000.json`, SHA-256 `d187fc93d659ad2b768d3a5717fefdf1fef58d44f737e537b27faf20a9b32b5b`.

The four independent runtime10 audits are collected in `animus-evidence/runtime-10-final.json`, SHA-256 `55c428f15716969fd0f5b053c5e0b0e809b1dd0f0df1e9abd17df09bc2a1132f`: 27 accepted suites and 864 checks. Folia preview acceptance retains its narrow stationary, viewer-private scope and separately reviewed QA shutdown-fixture corrections; it does not establish general Folia simulation.
