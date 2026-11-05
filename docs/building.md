# Building

An NPC can build a schematic block by block. It walks to each block with its normal [navigation](runtime.md#initial-movement-and-routes), swings its arm and places the block, and nearby players hear the block's place sound. Several NPCs can share one build. Builds are saved, so they continue after a restart, and every block a build changes can be undone.

Builds run on Spigot and Paper 26.1.2, 26.2 and 26.3, and on Fabric, NeoForge and Forge servers for the same versions. They are not available on Folia; see [platforms](#platforms).

## Schematic files

Put schematic files in the `schematics` folder of the Animus data folder: `plugins/Animus/schematics/` on Bukkit, `config/animus/schematics/` on mod servers. No other plugin or mod is needed to read them.

| Format | Extension | Notes |
| --- | --- | --- |
| Sponge schematic, versions 1, 2 and 3 | `.schem` | The format WorldEdit and most editors save today. The saved offset is used by `anchor=schematic`. |
| Vanilla structure | `.nbt` | Saved by a structure block. Structure void cells leave the world as it is. If the file holds several palettes, the first is used. |
| Old MCEdit schematic | `.schematic` | Not supported. These files store numeric block IDs from before Minecraft 1.13, which cannot be read safely. Open the file in an editor and save it again as `.schem`. A `.schematic` file that is really a Sponge schematic is read normally. |

Reading is bounded. A file larger than `maximumFileBytes`, a box larger than `maximumVolume` cells or `maximumSide` blocks on any side, a palette larger than `maximumPalette`, more than 64 MiB once decompressed, NBT nested deeper than 64 levels or more than 2,000,000 NBT tags is refused with the reason. Nothing is placed until the whole file is read.

Each block state is matched to the blocks this server has. A block under an older name, such as `minecraft:grass`, is tried under its new name. A state whose properties this server does not have is placed with the block's default properties. A block this server does not know at all is left out and reported, and its cells stay as they are. `/animus build inspect <schematic>` shows all of this before you build.

Chest, furnace, sign and banner contents in a schematic are not copied: the block is placed empty. Entities in a schematic are not placed.

## Commands

All build commands need `animus.admin`, except `donate`. They are the same on every platform.

```text
/animus build                                   status of the build system
/animus build list                              every build and its progress
/animus build schematics                        schematic files in the folder
/animus build inspect <schematic>               size, block count and unknown blocks
/animus build start <npc-id> <schematic> [at=<x>,<y>,<z>] [option=value...]
/animus build start <npc-id>                    start the build saved in the NPC's definition
/animus build <build> status|pause|resume|cancel|undo|survey
/animus build <build> team add|remove <npc-id>
/animus build <build> donate                    give the items in your main hand
```

A build is named after the NPC that leads it, and `<build>` may also be any NPC on its team. The build starts where the leader stands, or at `at=`. An NPC can work on one build at a time. Starting a new build for an NPC whose last build has finished, failed, been cancelled or been undone discards that old build's record and undo data.

```text
/animus build start mason tower.schem at=120,64,-40 rotation=90 excavate=true materials=donate
/animus build mason team add helper
/animus build mason survey
/animus build mason pause
```

### Options

| Option | Default | Meaning |
| --- | --- | --- |
| `anchor` | `center` | `center` puts the middle of the footprint on the origin and the lowest filled layer at the origin's height. `corner` puts the schematic's lowest north-west corner on the origin. `schematic` uses the offset saved in the file. |
| `rotation` | `0` | Turn clockwise, seen from above: `0`, `90`, `180` or `270`. Stairs, doors, logs, rails, fences, signs and other directional blocks turn with the build. |
| `mirror` | `none` | `x` swaps east and west, `z` swaps north and south. Mirroring happens before rotation. |
| `offset` | `0,0,0` | Moves the whole build by `<x>,<y>,<z>` blocks, up to 1024 each way. `yoffset=<y>` changes only the height. |
| `pattern` | `spiral` | The order of blocks within one layer: `spiral` (outside in), `reversespiral` (inside out), `linear` (row by row from the north-west corner) or `reverselinear`. |
| `layers` | `1` | How many layers form one group. |
| `groupall` | `false` | Treat the whole build as one group. |
| `ignoreair` | `false` | Do not place the schematic's air. |
| `ignoreliquids` | `false` | Do not place water or lava. |
| `excavate` | `false` | First clear the whole box from the top down, keeping blocks that already match. |
| `speed` | `2` | Walking speed, 1 to 5 blocks per second. |
| `interval` | `4` | Ticks between two blocks placed by one NPC, 1 to 200. |
| `reach` | `4.5` | How close, 2 to 6 blocks, the NPC must be to place a block. |
| `timeout` | `10` | Seconds, 1 to 300, an NPC may walk toward one block. After that it is moved next to the block, or places it from where it stands when there is no clear spot. |
| `hold` | `true` | Show the block being placed (or a pickaxe while digging) in the NPC's hand. |
| `silent` | `false` | No place sound. |
| `materials` | `creative` | `creative` places blocks for free. `donate` uses items players hand over. `container` takes items from the container at `container=<x>,<y>,<z>`. |

### Build order

The build goes up one group of layers at a time. Inside each group the order is:

1. air, from the top of the group down;
2. solid blocks, bottom up;
3. falling blocks such as sand, gravel and concrete powder;
4. blocks that hang on others: torches, lanterns, doors, trapdoors, buttons, levers, pressure plates, redstone dust, repeaters, comparators, rails, signs, banners, beds, carpets, plants, crops, vines and similar;
5. water and lava last.

Blocks are placed without updating their neighbours, so a torch or a door does not pop off while the wall behind it is not built yet, and water does not flow until a neighbour changes. Excavation, when turned on, runs before the first group.

A block that already matches the schematic is skipped and costs nothing. Each NPC takes the next block of the current step; the next step starts only when every block of the current one is done.

### Materials

With `materials=donate`, a build waits when it needs an item it does not have, and its status says which item and how many. Players hand items over with `/animus build <build> donate` while holding them; the build takes only items it still needs and leaves the rest in the hand. `animus.build.donate` is given to everyone by default. `survey` lists what the rest of the build needs and what it holds. The list is an upper bound: blocks already in place are only counted off when the build gets to them.

With `materials=container`, items come from the container block at `container=`. The container must be in a loaded chunk; when it is missing or unloaded the build waits.

The item for each block comes from the server: a wall torch needs a torch, wheat needs seeds. The upper half of a door or tall plant and the head of a bed are free, a double slab needs two slabs, and blocks with several pieces in one cell (candles, sea pickles, eggs, snow layers, petals) need one item per piece. Water and lava need a water or lava bucket each, and the bucket is used up. A block that no item places, such as fire or a portal, is skipped in these modes and counted under `no-item`. Undo is free and returns no items.

## Pausing, waiting and undo

A build waits on its own, and starts again on its own, when:

- a block lies in a chunk that is not loaded. Animus never loads or generates a chunk for a build;
- a sourced build runs short of an item, or its container is not there;
- no NPC on its team is active in the build's world.

`pause` stops a build until `resume`. `cancel` stops it for good. A paused, waiting, cancelled or finished build keeps its undo record.

`undo` puts back every block the build changed, newest first, including blocks it dug out. A block that someone changed after the build placed it is left alone and counted under `kept`. Undo does not walk: blocks change at `undoPerTick` per tick, and wait for unloaded chunks the same way. The undo record holds at most `maximumUndoEntries` changes. Past that the build still runs but its status shows `undo=... (full)`, and undo can only revert the changes it recorded.

## Protection

Every block change asks the platform's protection first. If the answer is no, the block is skipped and counted under `denied`; if protection fails or gives no answer, the change is refused.

- On Bukkit and Paper with WorldGuard, the NPC is asked about as a non-member, as in [combat](combat.md#protection): the `build` flag and `block-place` (or `block-break` when a block turns to air) must not be denied at the block. If WorldGuard is installed but cannot be used, every change is refused. Other claim plugins are not asked.
- Without WorldGuard, and on Fabric, NeoForge and Forge servers, which have no standard protection API, every change is allowed. `/animus build` shows which protection is in use.

## Behaviors

The built-in [`animus:build`](behaviors.md#actions) action starts, pauses, resumes, cancels or undoes the NPC's build:

```json
{"type": "action", "capability": "animus:build", "arguments": {"operation": "start", "schematic": "hut.nbt", "rotation": 180, "materials": "donate"}}
```

A start without `schematic` uses the NPC's `animus:builder` section. Two triggers fire for every NPC on the team: `build_finished` when the last block is placed, and `build_paused` when the build is paused or starts waiting.

## Saved build in a definition

An NPC definition can hold a build request, which `/animus build start <npc-id>` runs:

```json
"modules": {
  "animus:builder": {
    "schematic": "tower.schem",
    "origin": {"x": 120, "y": 64, "z": -40},
    "options": {"rotation": 90, "excavate": true, "materials": "donate", "pattern": "spiral", "layers": 1}
  }
}
```

`options` uses the JSON names of the options above: `anchor`, `rotation`, `mirror`, `offset` (`{"x", "y", "z"}`), `pattern`, `layers`, `groupAll`, `ignoreAir`, `ignoreLiquids`, `excavate`, `speed`, `placeIntervalTicks`, `reach`, `moveTimeoutSeconds`, `holdBlock`, `silent`, `materials` and `container`. An imported section also needs `"reviewAccepted": true`. Nothing starts on its own.

## Saved state

Each build is saved under `state/builds/<build>.json`, with its undo record in `state/builds/<build>/undo-00000.json` and following files of 4096 changes each. The files are written through the same crash-safe journal as other generated state, a few seconds after a change and at once when a build pauses or ends. Do not edit them.

```json
{
  "schemaVersion": 1,
  "id": "mason",
  "team": ["mason", "helper"],
  "world": "0f6c1a52-6e1e-4f0a-9d7c-2b0b8a1d3e44",
  "origin": {"x": 120, "y": 64, "z": -40},
  "schematic": "tower.schem",
  "schematicSha256": "5d41402abc4b2a76b9719d911017c592...",
  "options": {"anchor": "center", "rotation": 90, "mirror": "none", "offset": {"x": 0, "y": 0, "z": 0}, "pattern": "spiral",
              "layers": 1, "groupAll": false, "ignoreAir": false, "ignoreLiquids": false, "excavate": true, "speed": 2.0,
              "placeIntervalTicks": 4, "reach": 4.5, "moveTimeoutSeconds": 10, "holdBlock": true, "silent": false, "materials": "donate"},
  "state": "waiting",
  "reason": "needs 12 minecraft:oak_planks (have 0, the rest of the build needs 140)",
  "cursor": 1834,
  "steps": 5120,
  "counters": {"placed": 1702, "skipped": 120, "denied": 12, "noItem": 0, "refused": 0, "restored": 0, "kept": 0},
  "materials": {"minecraft:glass": 4},
  "startedBy": "00000000-0000-0000-0000-0000000000a1",
  "startedAt": "2026-11-04T18:20:11Z",
  "undo": {"entries": 1702, "capacity": 250000, "full": false, "remaining": -1}
}
```

After a restart the server reads the schematic again and continues from the saved step; blocks already in place are skipped. If the schematic file changed or went missing, the build stops with that reason and can still be undone. `state` is one of `running`, `paused`, `waiting`, `finished`, `cancelled`, `undoing`, `undone` or `failed`.

## Settings

Plugin-wide limits live in `config.toml` and need a restart:

```toml
[builds]
enabled = true
maximumBuilds = 16         # builds that may run at once
maximumTeam = 8            # NPCs on one build
placementsPerTick = 32     # blocks placed per tick across every build
checksPerTick = 512        # blocks compared per tick across every build
undoPerTick = 32           # blocks restored per tick across every build
maximumVolume = 1000000    # cells in one schematic box
maximumPalette = 4096      # block states in one schematic
maximumSide = 1024         # blocks on each side of the box
maximumFileBytes = 33554432
maximumUndoEntries = 250000
saveSeconds = 5
```

## Platforms

| Platform | Builds | Protection |
| --- | --- | --- |
| Spigot and Paper | Yes, on the main thread | WorldGuard flags when installed, otherwise none |
| Fabric, NeoForge, Forge | Yes, on the server thread | None: mod servers have no standard protection API |
| Folia | No. `/animus build` and `animus:build` say so | |

On Folia a build site, the NPCs of its team and its material container can belong to different regions, and there is no safe way yet to hand a build between regions, so building stays off there.

## Limits

- The NPC walks with the normal navigator, at most 30 blocks per walking leg, and is moved next to a block after `timeout`. Parts of a build that no NPC can reach are still placed when the timeout runs out.
- Block entity contents (chest items, sign text, banner patterns) and schematic entities are not copied.
- Repair mode, the packet-only preview and area marking, multi-part builds, a materials screen and returning dug-out items are not available yet.
- Builder `.schematic` files must be saved again as `.schem`.
