# Migrating from Citizens

Animus reads the Citizens `saves.yml` and turns every NPC into its own definition file. Nothing runs until you apply the import, and patrols, combat, riding, shops and builds stay off until you accept them. You can undo the whole migration with one command.

The commands need `animus.admin`. They work the same on Paper, Spigot, Folia and mod servers. On mod servers the data folder is `config/animus/` instead of `plugins/Animus/`.

## Steps

1. Back up the server. Stop it.
2. Remove the Citizens jar (and Sentinel, dtlTraders, Builder and Denizen) from `plugins/`. Keep their data folders. Animus refuses to apply, accept or run imported NPCs while Citizens is enabled, so the same NPC never runs twice.
3. Leave `plugins/Citizens/saves.yml` where it is, or copy it to `plugins/Animus/imports/saves.yml`. Animus also looks in `imports/inbox/saves.yml`.
4. Optional: copy the dtlTraders data folder to `plugins/Animus/imports/dtltraders/` (a folder left at `plugins/dtlTraders/` also works). Builder and Constructor schematics are found in `plugins/Builder/schematics/`, `plugins/Constructor/schematics/` or `plugins/Animus/imports/schematics/`.
5. Start the server. The console lists every save and folder it found (Citizens, Shopkeepers, dtlTraders and Builder schematics) and the next command.
6. Preview:

   ```text
   /animus import citizens
   ```

   This converts the save, stages a reviewed plan and prints the counts: NPCs, valid definitions, skins, folders, the patrols, look-close and behaviors it made, what stays off and what is kept but not run. Nothing is installed. A definition counts as valid only when it also passes the spawn checks that do not depend on the server, such as a skin needing a player body. A spawned NPC that still cannot start is logged once as a warning that names the field. The full report is `imports/<import-id>/report.json`, with one report per NPC under `reports/`.

   Each NPC report gives every Citizens field a status under `fields`: `CONVERTED`, `REQUIRES_REVIEW` (Animus has a near equivalent you should check), `UNSUPPORTED_PRESERVED` (kept in `citizens:review` and `records/`, not run) or `REQUIRES_INTEGRATION` (needs data that is not in the save, such as Denizen scripts). `reasons` gives the one-line reason for each field that is not converted, and for converted fields that change nothing in Citizens either. The migration warnings in `report.json` list only things you should act on, each saying what to do.

   Options: world mappings such as `world_old=world` when a world has a new name, and a mode for NPCs that already exist in Animus: `reject` (the default, refuse any collision), `replace` (update definitions from an earlier import) or `duplicate <label>` (install a second copy under `npcs/copy-<label>/`). For example `/animus import citizens replace world_old=world`.
7. Apply:

   ```text
   /animus import citizens apply
   ```

   Every NPC is installed at its Citizens position, despawned. dtlTraders shops are installed in the same step, and `.schem` and `.nbt` schematics are copied into `schematics/`. Text and command behaviors made from the save are installed in `behaviors/citizens/`. Apply, accept and rollback reload the shops, GUIs and behaviors they change, so new shops trade and removed ones close at once, with no reload or restart.
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
   | `combat` | Turns on the Sentinel settings converted to `animus:combat`. Every guard becomes a combatant; at most `[combat] maximumNpcs` fight or scan at once and guards with no player near sleep, and `review` and `accept` say when more guards are accepted than that. See [combat](combat.md#work-limits). |
   | `riding` | Turns on riding for NPCs that had the `controllable` trait. |
   | `shops` | Lets dtlTraders NPCs open their shop on right click. |
   | `builder` | Lets Builder sections run with `/animus build start <npc-id>`. Builds never start on their own. |
   | `denizen` | Lists the behaviors converted from Denizen scripts on the NPCs that had the assignment. See [Denizen](#denizen). |

## Shopkeepers

`/animus import citizens` also imports a Shopkeepers save. There is no separate command: the Shopkeepers shops join the same preview, apply, review, accept and rollback, so one rollback undoes both imports in order.

1. Remove the Shopkeepers jar from `plugins/` and keep its data folder. Animus refuses to apply Shopkeepers shops while Shopkeepers is enabled, so no shop trades twice.
2. Leave `plugins/Shopkeepers/data/save.yml` where it is, or copy it to `plugins/Animus/imports/shopkeepers/save.yml` (`imports/inbox/shopkeepers/save.yml` also works). The `config.yml` beside it is read too (`plugins/Shopkeepers/config.yml`, or `config.yml` next to the copied save): its currency items, high currency value and minimum cost, and trade log setting change how player shops convert.
3. Run `/animus import citizens`. The preview lists shops, trading offers, offers that only show, offers kept in the source, new mob NPCs and how many Citizens links it found. The report is `imports/shopkeepers/<import-id>/report.json`, with one report per shopkeeper under `reports/`.
4. `/animus import citizens apply`, then `review` and `accept shops` (and `accept spawn` for the new mob NPCs).

The order does not matter. With both saves present, both stage together. If the Citizens import is already applied, a Shopkeepers save found later stages on its own and binds to the installed NPCs. If the Shopkeepers save is applied first, its Citizens shopkeepers wait, and they bind when the Citizens import is applied.

| Shopkeeper | Becomes |
| --- | --- |
| Any shopkeeper | Shop `shopkeepers:shop-<id>` in `shops/shopkeepers/shop-<id>.json`, one trade entry per offer, 45 offers a page. The name becomes the title. |
| Admin shop | Unlimited trades: each recipe's first and second item are the needs, the result item is the gift. `tradePerm` becomes the shop permission. A closed shop (`open: false`) shows its offers and trades nothing. |
| Selling player shop | An owned shop whose stock is its container. The price becomes the currency items Shopkeepers asks for: high currency first when the price is above the minimum cost, the rest in base currency. |
| Buying player shop | The player gives the item and gets the price in base currency from the container. |
| Trading player shop | The player gives the first and second item and gets the result item from the container. |
| Book player shop | Kept in the source and reported. A book offer copies a book by title, which is not an item-for-item trade. |
| Citizens object | `modules["animus:shop"]` on the NPC converted from that Citizens NPC, matched by its Citizens UUID (or old numeric id). It opens nothing until `accept shops`. |
| Mob object | A new NPC `npcs/shopkeepers/shopkeeper-<id>.json` of that mob at the shopkeeper's place, despawned until `accept spawn`, with its shop bound. Mob settings (profession, baby, equipment and so on) are kept in `modules["shopkeepers:review"]`. |
| Sign, hanging sign or virtual object | The shop only, with the sign data kept in the shop's `imported` section. Open it with `/animus shop open <shop>` or bind it to an NPC. |

Items are written exactly: Shopkeepers saves each item component as the text the give command reads, and that text becomes the item's `components`. An offer with item data that cannot be written exactly (old Bukkit item meta, components over 4096 characters) shows in the shop, trades nothing, and keeps the data in `imported`; the report lists it. A player shop without an owner or a container also only shows its offers. Owners, members, hire settings and expiry are kept in the shop's `imported` section and not used.

The offline converter does the same: `converter shopkeepers-preview <save.yml> <data-dir> [config=<config.yml>] [citizens=<saves.yml>]` prints only counts and the output hash, and `converter shopkeepers-apply <data-dir> <import-id>` installs it. Owned shops are described in [shops](shops.md#container-shops).

## Denizen

`/animus import citizens` also converts Denizen scripts and flags, so Denizen can be removed after the migration. There is no separate command: the Denizen part joins the same preview, apply, review, accept and rollback, and it needs the Citizens save beside it.

1. Remove the Denizen jar from `plugins/` and keep `plugins/Denizen/`. Animus refuses to apply or accept the converted scripts while Denizen is enabled, so no script runs twice.
2. Leave `plugins/Denizen/` where it is, or copy its `scripts/` folder, `server_flags.dat` and `player_flags/` to `plugins/Animus/imports/denizen/` (`imports/inbox/denizen/` also works). Animus reads every `.dsc` and `.yml` file under `scripts/`.
3. Run `/animus import citizens`. The preview counts the scripts, the behaviors made and the NPCs they belong to, the flag values imported and kept, and the items kept with a reason. The report is `imports/denizen/<import-id>/report.json`. It lists every script, command, tag and event as converted or kept, and every kept item with its file, line and reason.
4. `/animus import citizens apply` installs the behaviors in `behaviors/denizen/` and the flag values as behavior state. Restart once so the imported flag values load. The behaviors run on no NPC yet.
5. `review`, then `/animus import citizens accept denizen`, lists the behaviors on the NPCs that had the assignment.

Rollback removes the behaviors, the imported state and the NPC bindings with the rest of the import. The converter does the same offline: `converter denizen-preview <Denizen-folder> <data-dir> [citizens=<saves.yml>]` prints counts and the output hash.

### What converts

| Denizen | Becomes |
| --- | --- |
| `assignment` script | `denizen:assignment/<name>`: `on click` becomes an `interaction` trigger, `on damage` an `interaction` trigger with `action: attack`, `on enter proximity` and `on exit proximity` `proximity` triggers, and `on spawn`, `on despawn` and `on death` the triggers of the same names. `on assignment` runs once in Denizen; its trigger settings come from the NPC's saved triggers |
| `interact` script | `denizen:interact/<name>`. The step is a relationship quest stage, `interact-<name>`, 0 for the default step (marked `*`, or the first) and 1 onwards for the others in file order. Click and proximity triggers call the routine of the current step |
| Chat trigger | An `animus:choices` button after the step's click, with a `dialogue_choice` trigger. A phrase with a regex, `*` or a tag is kept |
| `task` script | A routine in each behavior that runs it, and the behavior `denizen:task/<name>` to run by hand. Other lists in the task become routines for `run <task> path:<key>` |
| Trigger on or off, cooldown, radius | Read from the NPC's saved `triggers` (`CLICK`, `PROXIMITY` and so on). A trigger that is off on every NPC with the script is kept |
| `narrate`, `chat` | `animus:say`; `chat` speaks to nearby players. Colour tags become `&` codes, `<&color[#hex]>` becomes `&#hex`, escapes such as `<&co>` and `<&chr[2764]>` become their characters, `<player.name>` becomes `{player_name}`, `<npc.name>` the NPC's name when every NPC with the script shares one, and `<script.data_key[...]>` its literal value |
| `if`, `else if`, `else`, `choose` | `condition` nodes. `has_flag`, `flag` comparisons with whole numbers, `has_permission` and `npc.is_spawned` convert, joined by `&&` or by `\|\|`. A branch ending in `stop` takes the rest of the script into the other branches |
| `flag player`, `flag npc`, `flag server` | `animus:set-flag` for true/false flags; `animus:set-quest-state` and `animus:advance-quest-state` for whole numbers (`:5`, `:++`, `:+:2`, `:!`). Scopes are `player`, `npc` and `global`; dots in names become dashes |
| `zap` | `animus:set-quest-state` on the step stage |
| `wait`, `repeat <n>` | `wait` and `repeat` nodes |
| `run`, `inject` | `call` of the task's routine |
| `execute`, `give`, `take`, `title`, `actionbar` | `animus:command` (`give {player} ...`, `clear {player} ...`, `title {player} ...`), denied until allow-listed. `as_player` runs as the player |
| `look`, `walk`, `equip` | `animus:look` at the player or a literal location, `animus:navigate` to a literal location, `animus:set-equipment` with plain materials |
| `playsound`, `playeffect` | `animus:play-sound` and `animus:spawn-particle` at the NPC. Bukkit sound names become keys (`ENTITY_VILLAGER_YES` is `minecraft:entity.villager.yes`); the report's `sounds` lists every name read. Old particle names are renamed; particle speed and visibility are not carried |
| Flag values | `true`/`false` values become `denizen` flags and whole numbers from 0 to 10^12 become `denizen` quest stages, for each player (`player_flags/`), NPC (`denizen_flags` in the Citizens save) and the server (`server_flags.dat`). A player's interact steps become the relationship stage with every NPC that has the script |

Generated behaviors that use `animus:command` stay denied until listed, for example `commandNamespaces = ["denizen"]` under `[behaviors.actions]`; `review` names them. Read each command first.

### Kept with a reason

Each line below stays out of the behaviors and is listed in the report; a behavior missing some of its lines is marked `partial` in `behaviorStatus`.

- `define`, definitions (`<[name]>`), `foreach`, `while`, `repeat` with a computed count, and any tag Animus has no placeholder for, such as `<player.location>` in text or `<util.random...>`. Behaviors have no expressions.
- `world` scripts. Their events are server-wide (`on player joins`, `on delta time`, `on server start`), and Animus triggers belong to NPCs.
- `procedure`, `command`, `data`, `book`, `inventory` and `item` scripts. Literal data values that converted text reads are copied into it.
- Damage triggers in interact scripts (Animus damage triggers carry no player), proximity `move`, expiring flags, flags holding text, lists, maps or numbers that do not fit a quest stage, `teleport`, `adjust`, `animate`, `bossbar` and other commands with no Animus action.

## Where the files go

Each NPC becomes `npcs/<world>-r<x>_<z>/citizens-<id>.json`. The folder is the NPC's world after world mapping and the 512-block region file it stands in, so NPCs of one town share a folder: an NPC at x 100, z -40 in `world` goes in `npcs/world-r0_-1/`, the area of `region/r.0.-1.mca`. The world name is lowercased with other characters changed to `-` (`World Nether` becomes `world-nether`) and cut to 48 characters. An NPC without coordinates goes in `npcs/<world>/`, and one with no world in `npcs/citizens/`. Citizens has no NPC groups of its own, so the location is the grouping. The ID is always `citizens-<Citizens id>` and the UUID is the Citizens UUID, so the same save always gives the same files. Skins go in `skins/<sha256>.json`, one file for each different skin.

## What converts

Name, type, position and facing, skin, equipment, inventory, hologram lines, poses, sneaking and sitting, armor stand settings, owner, mounts, Sentinel settings, dtlTraders shop links, Builder settings, waypoints, look-close, text and commands. [Compatibility](compatibility.md) lists every trait and field.

### Skins

A skin on a player NPC becomes `skins/<sha256>.json`. Only player bodies show a skin, so a skin on any other body (a llama, frog or ender dragon) is not set: it stays in `modules["citizens:review"]`, the NPC's report lists it, and the preview counts it as a skin kept for review.

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
- Denizen assignments, triggers, flags and constants as Citizens saved them. The scripts themselves convert with the [Denizen](#denizen) part of the import.
- Every trait Animus does not know.

## Rolling back

```text
/animus import citizens rollback
```

This undoes every acceptance, the shops, the generated behaviors and the NPC definitions, newest first, and deletes the copied schematics that have not changed. The removed shops and behaviors leave their catalogs at once, so `/animus behaviors reload` afterwards reports nothing missing. If you edited an imported NPC after accepting, rollback refuses rather than overwrite the edit; undo the edit first with `/animus rollback <transaction-id>`. The staged preview stays, so `/animus import citizens apply` installs it again. Reinstall Citizens only after a rollback.

## Converting in the browser

The [editor](../animus-web/README.md) can convert a `saves.yml` in the browser with the same rules: open it, choose Convert Citizens save, and pick the file. Converting reads the file on your computer and uploads nothing. It shows the same report and downloads a zip to unpack into the data folder. The zip holds `imports/saves.yml` and the staged `imports/<import-id>/` folder, so `/animus import citizens` on the server finds the same preview, byte for byte, and continues from step 6. A Shopkeepers `save.yml` (with its `config.yml`, if you have it) converts the same way; its zip holds `imports/shopkeepers/save.yml` and the staged `imports/shopkeepers/<import-id>/` folder. Load the Citizens save first so the browser checks the links.

The same screen can also upload the files to a paired server and run these steps there: stage, review, apply, accept and roll back, with the same results as the commands. See "Importing on a server" in the [editor README](../animus-web/README.md).
