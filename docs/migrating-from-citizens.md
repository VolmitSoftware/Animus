# Migrating from Citizens

Animus reads the Citizens `saves.yml` and turns every NPC into its own definition file. Nothing runs until you apply the import, and patrols, combat, riding, shops and builds stay off until you accept them. You can undo the whole migration with one command.

The commands need `animus.admin`. They work the same on Paper, Spigot, Folia and mod servers. On mod servers the data folder is `config/animus/` instead of `plugins/Animus/`.

## Steps

1. Back up the server. Stop it.
2. Remove the Citizens jar (and Sentinel, dtlTraders and Builder) from `plugins/`. Keep their data folders. Animus refuses to apply, accept or run imported NPCs while Citizens is enabled, so the same NPC never runs twice.
3. Leave `plugins/Citizens/saves.yml` where it is, or copy it to `plugins/Animus/imports/saves.yml`. Animus also looks in `imports/inbox/saves.yml`.
4. Optional: copy the dtlTraders data folder to `plugins/Animus/imports/dtltraders/` (a folder left at `plugins/dtlTraders/` also works). Builder and Constructor schematics are found in `plugins/Builder/schematics/`, `plugins/Constructor/schematics/` or `plugins/Animus/imports/schematics/`.
5. Start the server. The console says which save it found and the next command.
6. Preview:

   ```text
   /animus import citizens
   ```

   This converts the save, stages a reviewed plan and prints the counts: NPCs, valid definitions, skins, folders, the patrols, look-close and behaviors it made, what stays off and what is kept but not run. Nothing is installed. The full report is `imports/<import-id>/report.json`, with one report per NPC under `reports/`.

   Options: world mappings such as `world_old=world` when a world has a new name, and a mode for NPCs that already exist in Animus: `reject` (the default, refuse any collision), `replace` (update definitions from an earlier import) or `duplicate <label>` (install a second copy under `npcs/copy-<label>/`). For example `/animus import citizens replace world_old=world`.
7. Apply:

   ```text
   /animus import citizens apply
   ```

   Every NPC is installed at its Citizens position, despawned. dtlTraders shops are installed in the same step, and `.schem` and `.nbt` schematics are copied into `schematics/`. Text and command behaviors made from the save are installed in `behaviors/citizens/`; run `/animus behaviors reload` to load them, or restart.
8. Review, then accept:

   ```text
   /animus import citizens review
   /animus import citizens accept all
   ```

   `review` lists how many NPCs wait in each category. Accept them all, or one at a time:

   | Category | What accepting it does |
   | --- | --- |
   | `spawn` | Spawns every NPC Citizens had spawned. NPCs that were despawned in Citizens stay despawned. Look-close and text start with the NPC. |
   | `patrols` | Turns on the routes made from Citizens waypoints. |
   | `combat` | Turns on the Sentinel settings converted to `animus:combat`. See [combat](combat.md). |
   | `riding` | Turns on riding for NPCs that had the `controllable` trait. |
   | `shops` | Lets dtlTraders NPCs open their shop on right click. |
   | `builder` | Lets Builder sections run with `/animus build start <npc-id>`. Builds never start on their own. |

## Where the files go

Each NPC becomes `npcs/<world>-r<x>_<z>/citizens-<id>.json`. The folder is the NPC's world after world mapping and the 512-block region file it stands in, so NPCs of one town share a folder: an NPC at x 100, z -40 in `world` goes in `npcs/world-r0_-1/`, the area of `region/r.0.-1.mca`. The world name is lowercased with other characters changed to `-` (`World Nether` becomes `world-nether`) and cut to 48 characters. An NPC without coordinates goes in `npcs/<world>/`, and one with no world in `npcs/citizens/`. Citizens has no NPC groups of its own, so the location is the grouping. The ID is always `citizens-<Citizens id>` and the UUID is the Citizens UUID, so the same save always gives the same files. Skins go in `skins/<sha256>.json`, one file for each different skin.

## What converts

Name, type, position and facing, skin, equipment, inventory, hologram lines, poses, sneaking and sitting, armor stand settings, owner, mounts, Sentinel settings, dtlTraders shop links, Builder settings, waypoints, look-close, text and commands. [Compatibility](compatibility.md) lists every trait and field.

### Patrols

Waypoints become a route in the NPC's `modules["animus:navigation"]`, with world mappings applied. It stays off until you accept `patrols`.

| Citizens | Animus route |
| --- | --- |
| `linear` | `order: sequence` that loops, or `pingpong` when `cycle` is on |
| `guided` | `order: random`: destinations become `points` and guide waypoints `guides`; the NPC walks to a random destination through the guides. `distance` (or the NPC's pathfinding range) becomes `linkDistance` |
| `wander` | `order: wander` around the region centres, or around the NPC's position. `xrange` and `yrange` become `wander.radius` and `height` (at most 32 and 8); a `delay` of -1 becomes 60 ticks |
| `delay` trigger | The point's `waitTicks`; several delays on one point add up |
| Speed modifier | `speed` of 4.3 blocks per second times the modifier, within 1 to 5 |

Points move to the centre of their block. Points in another world are left out. Other providers and the `animation`, `chat`, `command`, `speed` and `teleport` triggers are kept in `citizens:review` and reported for each NPC, as are legs longer than the 32-block leg limit and destinations no guide reaches. The route rules are in [the runtime guide](runtime.md#initial-movement-and-routes).

### Look-close

An enabled look-close trait becomes `modules["animus:look-close"]` with its `range`, `lineOfSight` (Citizens' realistic looking) and `whileNavigating` (the opposite of disable-while-navigating). It needs no review: the NPC turns to the nearest player once it is spawned. Citizens writes a look-close setting only when it differs from the server's default, so when a save only ever writes `enabled: false`, an unset value means on; the report says when the import assumed this. Random looking, head-only, per-player, NPC targets and filters are reported and not run.

### Text

Citizens text becomes the behavior `citizens:text-<id>` in `behaviors/citizens/`, listed in the NPC's `behaviors`. Talk-close NPCs speak when a player comes within the talk range (at most 32 blocks); others speak when right-clicked. The delay is the trigger cooldown (100 ticks when Citizens chose a random delay). A random talker picks a random line; otherwise the lines take turns. `<br>` starts a new message, `<p>` and `<player>` become the player's name, and colour codes are kept. The cooldown is one per NPC, not one per player.

### Commands

Citizens commands become the behavior `citizens:commands-<id>`. Right-click and left-click commands get their own interaction triggers, permissions become permission checks, delays become waits, and the longest cooldown becomes the trigger cooldown, one per NPC. Player commands run as the player and others from the console; an operator command runs from the console. `random`, `cycle` and `sequential` execution modes take turns per NPC, or per player for `sequential`. Sneak clicks, commands run as the NPC, use limits and commands that cost money, experience or items are not converted and are reported.

Commands stay denied until you list the behavior in `config.toml`, for example `commandBehaviors = ["citizens:commands-12"]` under `[behaviors.actions]`, or `commandNamespaces = ["citizens"]` for all of them, and restart. `review` lists how many wait. Read each command in `behaviors/citizens/` first: anyone who can edit an allowed behavior can run console commands.

## Kept but not run

These are kept in each definition under `modules["citizens:review"]` and in the original archive, and are listed by `review`:

- Waypoints, look-close, text and commands that did not convert, with the reason in the NPC's report.
- Denizen assignments, triggers, flags and constants. Denizen scripts are not translated; they need to be rewritten as behaviors.
- Every trait Animus does not know.

## Rolling back

```text
/animus import citizens rollback
```

This undoes every acceptance, the shops, the generated behaviors and the NPC definitions, newest first, and deletes the copied schematics that have not changed. If you edited an imported NPC after accepting, rollback refuses rather than overwrite the edit; undo the edit first with `/animus rollback <transaction-id>`. The staged preview stays, so `/animus import citizens apply` installs it again. Reinstall Citizens only after a rollback.

## Converting in the browser

The [editor](../animus-web/README.md) can convert a `saves.yml` in the browser with the same rules: open it, choose Convert Citizens save, and pick the file. The file is read on your computer and never uploaded. It shows the same report and downloads a zip to unpack into the data folder. The zip holds `imports/saves.yml` and the staged `imports/<import-id>/` folder, so `/animus import citizens` on the server finds the same preview, byte for byte, and continues from step 6.
