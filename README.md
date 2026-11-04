# Animus

NPCs that act alive instead of being fake players.

Animus is an NPC manager built on the [Husk](https://github.com/VolmitSoftware/Husk) project template (baseline `82c39ef`, Husk 0.1.2). The initial scaffold provides platform startup and shutdown, JSON configuration, a startup splash, and `/animus [status]`. NPC creation, behavior, and persistence are not implemented yet.

## Build

Run the Gradle wrapper with JDK 25. Install JDK 17 for the core tests and older mod targets, and JDK 21 for the 1.21.1 mod targets.

```sh
./gradlew test             # Shared runtime tests
./gradlew buildBukkit      # Bukkit jar and compatibility checks
./gradlew fabricJar        # Fabric jar for the default Minecraft target
./gradlew forgeJar         # Forge jar for the default Minecraft target
./gradlew neoforgeJar      # NeoForge jar for the default Minecraft target
./gradlew build            # All 15 distributions and artifact verification
./gradlew targets         # List the configured Minecraft/loader versions
```

If Gradle cannot discover Homebrew's JDK installations on macOS, pass their paths through the environment so the nested mod builds receive them too:

```sh
animus_jdks="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home"
animus_jdks="$animus_jdks,$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"
animus_jdks="$animus_jdks,$(brew --prefix openjdk@25)/libexec/openjdk.jdk/Contents/Home"
env "ORG_GRADLE_PROJECT_org.gradle.java.installations.paths=$animus_jdks" ./gradlew build
```

The inherited target matrix covers Minecraft 1.20.1, 1.21.1, 26.1.2, 26.2, and 26.3 across Spigot/Paper/Folia, Fabric, Forge, and NeoForge. NeoForge 1.20.1 uses the Forge artifact. Individual loader tasks default to 26.3; use a task such as `forgeJar1_20_1` to select another target. Exact versions and loader dependencies live in `gradle/targets.json`.

Bukkit artifacts are written to `bukkit/build/libs/`; mod artifacts to `adapters/<loader>/build/<minecraft>/libs/`. The full build collects the distributions in `build/distributions/` and writes checksums to `build/artifacts.json`.

The build automatically uses a sibling or ancestor `VolmLib` checkout. Set `-PlocalVolmLibDirectory=/path/to/VolmLib` or `VOLMLIB_DIR` to select another checkout. To resolve published dependencies instead, use `-PuseLocalVolmLib=false` and select an appropriate `volmLibVersion` in `gradle.properties`.

## Multiplexor

Register the source directory containing Animus and VolmLib from the ServerMultiplexor workspace:

```sh
./start.sh projects scan /path/to/VolmitSoftware
./start.sh projects plan animus --all-targets
./start.sh projects build animus --target bukkit --wait
./start.sh projects build animus --all-targets --wait
```

The `animus` project declares 16 targets: `core`, `bukkit`, and 14 mod builds. Every platform target requires `animus:core`, and VolmLib is included as a source dependency. Multiplexor builds captured source copies and can automatically deliver artifacts to saved destinations according to its workspace settings.

## Project layout

| Path | Purpose |
| --- | --- |
| `core/` | Platform-independent runtime and services, compiled for Java 17 |
| `bukkit/` | Spigot, Paper, and Folia plugin entry point |
| `adapters/mod-common/` | Minecraft integration shared by the mod loaders |
| `adapters/fabric/`, `adapters/forge/`, `adapters/neoforge/` | Loader entry points and build configuration |
| `gradle/` | Identity, version matrix, packaging, artifact checks, and Multiplexor build declarations |
| `qa/status.mjs` | Gameplay scenario for the root and status commands |

Project identity is configured in `gradle.properties`: `animus`, `Animus`, `art.arcane.animus`, version `0.1.0`. Source packages and entry points use the Animus namespace. VolmLib and its bundled dependencies are shaded into private packages under `art.arcane.animus.internal`.

Keep shared NPC logic in `core/` and platform entity operations in the adapters. Entity and world operations must use the owning region/entity scheduler on Folia and the logical server thread on mod loaders.

## Current runtime

Install the Bukkit artifact in `plugins/`, or the matching mod artifact in `mods/`. Startup creates `plugins/Animus/config.json` on Bukkit or `config/animus/config.json` on mod loaders:

```json
{
  "statusMessage": "Hello from Animus"
}
```

`/animus` and `/animus status` report the project version, platform, Minecraft release, and configured message. Configuration is loaded at startup; restart after editing it. Invalid configuration prevents startup and preserves the file.
