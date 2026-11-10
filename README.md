# Animus

NPCs for Minecraft servers, defined in JSON.

Every NPC is a JSON file you can edit by hand, through `/animus` commands, or in the [web editor](https://animus-editor.web.app). NPCs walk routes, carry riders, fight, build schematics, run behaviors, open shops and chest GUIs, and play rig animations. They are drawn with packets for each player, so players join with an unmodified client and other plugins never see an NPC as an entity.

| | |
|---|---|
| Minecraft | 26.1.2, 26.2 and 26.3 |
| Java | 25 |
| Plugin servers | Paper, Spigot and Folia, one jar for all three. Supported |
| Mod servers | Fabric, NeoForge and Forge, as a server-side mod. Experimental |
| Documentation | [docs.volmitsoftware.com/animus](https://docs.volmitsoftware.com/animus/) |

Supported on Paper, Spigot and Folia; Fabric, Forge and NeoForge are experimental. All of them run the same NPC runtime, commands, behaviors, navigation, mounts, riding, combat and building. The differences are listed under [platform differences](https://docs.volmitsoftware.com/animus/01-installation-compatibility#platform-differences): Folia runs each NPC on the region that owns its chunk, and mod servers have no protection plugin, no Vault economy and no Bukkit events.

## Getting started

1. Put the jar in `plugins/` (or `mods/` on a mod server) and start the server.
2. Follow the [tutorial introduction](https://docs.volmitsoftware.com/animus/20-tutorial-introduction) to make, skin and walk a first NPC.
3. Coming from Citizens? [Migrating from Citizens](https://docs.volmitsoftware.com/animus/13-migrating-from-citizens) imports your NPCs together with Sentinel, Denizen, dtlTraders and Shopkeepers data, and one command undoes it.

Every command is listed under [commands and permissions](https://docs.volmitsoftware.com/animus/02-commands-permissions), and every `config.toml` setting under [configuration](https://docs.volmitsoftware.com/animus/03-configuration).

## Extending Animus

Trusted Java modules add behavior actions, conditions, triggers and import converters. See [Java modules and API](https://docs.volmitsoftware.com/animus/99-java-modules-api) and the [reference module](examples/modules/reference/README.md). Example behavior packs are in `examples/packs/`.

## Building from source

Building needs JDK 25. Set `JAVA_HOME` to it if `java -version` reports something older.

```sh
./gradlew build                                   # Core tests, Bukkit API matrix, artifact checks, converter archives
./gradlew :converter:installDist                  # Runnable local converter
./gradlew build -PincludeModDistributions=true    # Plugin jar plus every mod jar in build/distributions
```

The plugin jar is written to `build/distributions/`. The build uses a VolmLib checkout found beside or above the Animus checkout; point it elsewhere with `-PlocalVolmLibDirectory=/path/to/VolmLib`, or use the published dependency with `-PuseLocalVolmLib=false`.

## Support

- [Discord](https://volmitsoftware.com/discord)
- [Documentation](https://docs.volmitsoftware.com/animus/)
