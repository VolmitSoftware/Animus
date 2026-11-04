# Reproducible Animus workload preparation

This tooling prepares synthetic idle workloads and an identical world/client baseline with no NPC plugin. It does not run servers, gather timings, or establish a performance result. Animus is **A Manager for all things NPC.**

Generate all cases into a new directory:

```sh
python3 qa/benchmarks/generate.py --output .qa/benchmark-workloads-v1 --case all
python3 -m unittest discover -s qa/benchmarks -v
```

The generator uses fixed coordinates and UUIDv5 identities derived from an explicit seed. The same arguments produce identical bytes and SHA-256 inventories. Existing output directories are rejected. Every name, UUID and position is synthetic; no private migration fixture is read. Plugin settings use `config.toml`; definitions, manifests, expectations and eventual measurements use JSON.

| Case | Definitions | Active | Dormant | Observers | Expected visible per observer |
|---|---:|---:|---:|---:|---|
| `reference-495` | 495 | 495 | 0 | 8 | Seven groups of 64, one group of 47 |
| `dormant-5000` | 5,000 | 64 | 4,936 | 1 | 64 |
| `dense-32` | 32 | 32 | 0 | 1 | 32 |
| `dense-64` | 64 | 64 | 0 | 1 | 64 |
| `dense-128` | 128 | 128 | 0 | 1 | 128 |

The 495 case includes 483 player bodies, three armor stands, three dragons, two horses, and one each of turtle, frog, llama and armadillo. It is a mixed presentation workload, not a behavioral reconstruction. Other cases use player bodies. There are no scripts, combat, navigation, equipment, holograms or custom skins. Mixed-type visual differences must be recorded before that case can support a performance conclusion.

Each case contains `workload.json`, `animus/npcs/benchmark/*.json`, `animus/config.toml`, `no-plugin/expectations.json` and `manifest.json`. The manifest supplies observer positions and exact expected UUID sets, preparation settings, declared sample windows, run order, metrics and validation gates. Copy the generated `animus/` contents into an isolated server's `plugins/Animus/` only after preparing that world's floor and loaded chunks. Definitions in these test bundles deliberately activate at startup. Do not install them on a production server.

Validate generated definitions with the existing local converter distribution:

```sh
JAVA_HOME=/path/to/java25 converter/build/install/converter/bin/converter \
  validate .qa/benchmark-workloads-v1/reference-495/animus
```

Repeat validation for every case. Validation checks stored definitions; it does not prove runtime behavior, loaded terrain, wire counts or visibility.

## Visibility and equivalent baseline

All observers are within 48 blocks of their assigned bodies and 256 blocks from the next sector. The generated TOML explicitly sets `maximumVisible=128`; retaining the normal 64-body limit would invalidate the 128-body case. The generator rejects any observer assignment exceeding 128 NPCs or 256 reserved packet entity IDs, counting dragon parts. Actual client receipts must still match every expected UUID and type after all presentation queues settle. Missing bodies, extra bodies, wrong chunks, or invisible capacity truncation fail the workload; they must never be reported as lower cost.

Prepare the same flat stone floor at Y=60 around each observer sector and clear the space above it through Y=85. Use the manifest's seed and server settings, freeze weather/time and random ticks, and disable ambient mob spawning. Use a fresh copy of the prepared world for each run. Both variants use identical server/JVM versions, memory, clients, observer paths, loaded chunks, profiler and packet observer. The no-plugin variant has no NPC plugin and expects zero NPC bodies. Measurement observers, if needed, must be installed identically in both variants and their overhead disclosed. Development interaction/combat fixtures must not be installed during measurement. No texture payload is supplied; record and control any client skin fetching separately.

Dense tiers stop at 128 visible bodies because that is the present runtime limit. Larger single-viewer workloads are unavailable until the implementation supports them. Multiple sectors in the 495 case are intentional and must not be described as 495 simultaneously visible NPCs for one client.

## Environment evidence

Capture only explicitly selected artifacts and configs; the helper hashes their bytes without recording their contents. It reads no environment variables, process command lines, credentials or migration data. Declared version/build and JVM arguments still need confirmation against startup evidence.

```sh
python3 qa/benchmarks/capture.py \
  --manifest .qa/benchmark-workloads-v1/dense-64/manifest.json \
  --output .qa/benchmark-runs/example/environment.json \
  --minecraft 26.3 --server-build '<exact build>' --server-channel stable \
  --java /path/to/java25/bin/java \
  --jvm-arg=-Xms2G --jvm-arg=-Xmx2G \
  --artifact server=/path/to/server.jar \
  --artifact animus='/path/to/Animus.jar' \
  --config server-properties=/path/to/server.properties
```

The helper verifies every generated workload hash before recording OS/architecture, logical CPUs, available physical-memory metadata, Java version and executable hash, supplied JVM arguments, and input hashes. It never starts a server and refuses to overwrite evidence. Record CPU model, power/thermal policy, background work and graphical client/GPU details separately when unavailable from this capture. Hash every plugin, profiler, native client and complete server configuration used in a run.

## Measurement gate

The manifest proposes 120 seconds of warmup, 300 seconds of sampling and five repetitions with alternating variant order. Record actual durations and stabilization evidence; extend warmup when queues or compilation have not settled. Measure server-thread milliseconds per tick, total process CPU, resident and heap memory, allocation rate, packet counts and network bytes. Graphical-client frame times require a real graphical client capture; protocol clients cannot supply them. Preserve raw traces and per-run samples, then report medians, dispersion and the incremental cost over the matching no-plugin baseline. Do not infer client smoothness from server timings.

No timing fields are prefilled: result status remains `generated-not-measured` or `environment-captured-not-measured`. Exact 26.1.2, 26.2 and 26.3 server/platform builds require independent runs. Folia presentation remains unavailable. Navigation, combat, scripted behavior, signed skins, mounts, joining and teleport bursts need separate supported workload scenarios before being measured.
