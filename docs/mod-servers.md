# Mod servers

Animus runs on Fabric, NeoForge and Forge servers for Minecraft **26.1.2, 26.2 and 26.3** with Java 25. It is a server-side mod: players join with an unmodified client. The NPC runtime, definitions, imports, commands, behaviors, triggers, modules, navigation, mounts, riding and combat are the same code that runs on Bukkit and Paper, so a definition behaves the same on every platform. An integrated singleplayer or LAN server loads it too; that path has not been checked live (see [live checks](#live-checks)).

## Install

Put the jar for your loader and Minecraft version in the server's `mods` folder.

| Loader | Jar |
|---|---|
| Fabric | `Animus v0.1.0 [Fabric] <minecraft>+<fabric-loader>.jar` |
| NeoForge | `Animus v0.1.0 [NeoForge] <minecraft>+<neoforge>.jar` |
| Forge | `Animus v0.1.0 [Forge] <minecraft>+<forge>.jar` |

The Fabric jar carries the three Fabric API modules it uses, so the full Fabric API is not required. Each jar runs only on its own Minecraft version.

## Files

Everything lives in `config/animus/` in the server folder: `config.toml` holds the plugin-wide settings and the `npcs/`, `skins/`, `behaviors/`, `routes/`, `modules/`, `imports/` and other folders hold the JSON data. The layout is the same as `plugins/Animus/` on Bukkit, so the folder can be copied between platforms. Settings and data formats are described in [storage](storage.md) and [runtime](runtime.md).

## Worlds

A world is named by its dimension: `minecraft:overworld`, `minecraft:the_nether`, `minecraft:the_end`, or a modded dimension such as `example:mining`. Definitions written on Bukkit keep working: the level name from `server.properties` (usually `world`) means the overworld, and `world_nether` and `world_the_end` mean the other two vanilla dimensions. A bare vanilla name such as `the_nether` also works. A Bukkit world UUID cannot be resolved; edit the definition to use one of these names.

## Commands and permissions

The command set is `/animus` with the same subcommands, arguments and replies as on Bukkit; see [runtime](runtime.md), [behaviors](behaviors.md) and [combat](combat.md). Tab completion offers the same choices.

| Node | Who has it |
|---|---|
| `animus.status` | Everyone |
| `animus.admin` | Operators (permission level 2 and above) |
| `animus.ride` and custom riding nodes | Operators |

A permission mod can grant any node to anyone through the vanilla permission named by the node with its first dot as the namespace separator: `animus.admin` is `animus:admin` and `animus.ride.town` is `animus:ride.town`.

## What differs from Bukkit

| Feature | Mod servers |
|---|---|
| Combat protection | No protection adapter is installed, because mod servers have no standard protection API. Every combat hit is allowed; `/animus combat status` shows `protection=none`. |
| Combat pursuit regions (`pursuit.region`) | Not available, because no protection plugin provides regions. The NPC fights within its normal ranges, the log says so once per NPC, and `/animus combat status` counts it as `region-unavailable`. |
| Combat permission and group rules | Read through the vanilla permission mapping above; a group is the permission `group.<name>`, so it needs a permission mod that grants it. |
| Build protection | No protection adapter: every block a [build](building.md) changes is allowed; `/animus build` shows `protection=none`. |
| Mob control (`/animus target <npc-id> <mob-uuid>`) | Not available; the command says so. Use `/animus combat <npc-id> target <uuid>` or `guard` from the combat module instead. |
| Manual hit (`/animus attack <npc-id> <uuid> [damage]`) | Available, with the same reach, line of sight and protection checks. |
| Plugin events (`VirtualNpcInteractionEvent`, `VirtualNpcDamageEvent` and the others) | Not fired. Trusted Java modules and behavior triggers receive the same facts on every platform. |
| Packet previews (`/animus proof`, `/animus foliaproof`) | Not available; they answer that they belong to Bukkit. |
| `packetProofEnabled` in `config.toml` | Ignored. |

## Live checks

Each of the nine jars ran on a dedicated server with a headless protocol client joining as a player. On every server Animus started, its required mixin configuration applied (a failed injection stops the server), and a graceful stop exited cleanly with no Animus error or warning in the log.

| Loader | 26.1.2 | 26.2 | 26.3 |
|---|---|---|---|
| Fabric 0.19.5 | Basic set passes | Basic set passes | Full set passes |
| NeoForge 26.1.2.116 / 26.2.0.89 / 26.3.0.33-beta | Basic set passes | Basic set passes | Full set passes |
| Forge 64.1.4 / 65.1.3 / 66.0.8 | Basic set passes | Basic set passes | Full set passes |

The basic set (scenario `smoke` in `qa/multiplexor/live-features.mjs`) spawns and renders an NPC, fires an interaction trigger, navigates, rides with steering and a sneak dismount, and restarts the server with the NPC kept. The full set is the Paper live suite: rendering with skins, text lines and poses, triggers, behavior actions and choices, durable state across a graceful restart and a kill, NPC passengers, riding (mount, steer, walls, sneak dismount, logout, teleport, death, a second viewer), dragons, terrain and water walking, combat (melee, arrows, fireballs, guard, pursuit, leash, death and respawn), the example packs, a tiny `.nbt` build with undo, and an item-for-item shop trade. It also checks the mod-only results in [what differs](#what-differs-from-bukkit) (`protection=none`, pursuit regions and money unavailable) and that a far teleport, a short teleport, a trip to the nether and a reconnect each rebuild the NPC for that player, and that an NPC placed in `minecraft:the_nether` shows only there.

These checks do not cover an integrated singleplayer or LAN server, or what a graphical client draws: skin layers, text display placement, animation smoothness, the dragon model and the shop window's look. They need a person with a real client. The Forge dedicated server also tries to announce itself on the LAN; on a host without a network route it logs a `LanServerPinger` warning, which comes from Forge and not from Animus.

## Building

The loader jars build with their own Gradle runs, started from the root project. Loader toolchains and Minecraft download into the normal Gradle cache on the first run.

```sh
./gradlew buildFabric buildNeoForge buildForge     # every loader for every Minecraft version
./gradlew fabricJar26_3                             # one loader and version
./gradlew build -PincludeModDistributions=true     # Bukkit plus every mod jar in build/distributions
```

Each jar is written to `adapters/<loader>/build/<minecraft>/libs/`.
