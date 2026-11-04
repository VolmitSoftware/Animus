Run the package verifier after the canonical artifacts have been built and their production sources are stable:

```sh
python3 qa/modules/packaged-api.py --build-log /path/to/canonical-build.log
```

It uses `animus-api/build/libs/animus-api-1.0.0-SNAPSHOT.jar` and the Bukkit Shadow archive by default. `--api-jar`, `--runtime-jar`, `--java-home`, `--volmlib` and `--output` accept explicit inputs. The default VolmLib source is the retained runtime-9 pin; provide another reviewed source snapshot when reproducing elsewhere. The output directory must be new. Coordinate the Gradle lane before running: the verifier builds a complete custom-group Shadow archive from captured source in its own directory.

Each immutable result records source and artifact hashes, Java commands, logs, an API-only reference-module build, and separate-JVM checks against the canonical and custom-group runtime archives. The reference builder is `examples/modules/reference/build.py`. The test JVM classpath contains only the QA probe and one runtime archive; neither the standalone API nor the external module is on that classpath. Discovery loads the external module and proves the parent API identity, invocation, private-core refusal, shutdown and archive release. An archive containing a bundled public API class must be rejected in both variants.

The custom group is `qa.animus.relocated`. The verifier checks that implementation classes and the plugin entrypoint move, every public API class remains at its stable name with identical bytecode, and the exact packaged description is `A Manager for all things NPC.`. `jdeps` must report only `java.base` for the standalone API. Source inputs and captured copies are rehashed before a passing result is written. Failed attempts remain intact.

The SPIGOT, PAPER and FOLIA iterations are manifest and module-lifecycle checks in an ordinary JVM. They do not start those servers or prove gameplay, platform ownership, graphical appearance, sandboxing, or binary hot replacement. Managed server acceptance is separate.
