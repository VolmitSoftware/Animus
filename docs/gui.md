# Chest GUIs

Animus opens chest windows from JSON files. The files use the Gloss inventory format: the same field names, the same `schemaVersion` 1 and the same actions. Animus reads and runs them with its own code, so Gloss does not need to be installed. A file that uses only features both plugins share works in both.

Files live in `plugins/Animus/gui/inventories/` (`config/animus/gui/inventories/` on mod servers). A file at `town/menu.json` has the id `town:menu`. Files must be in a namespace folder.

```json
{
  "schemaVersion": 1,
  "revision": 1,
  "title": "&8Bakery counter",
  "resolution": "9x3",
  "mask": ["#########", "#  B H  #", "####X####"],
  "keys": {
    "#": {"type": "decoration", "icon": {"type": "item", "item": "minecraft:brown_stained_glass_pane", "name": " "}},
    "B": {"type": "button", "icon": {"type": "item", "item": "minecraft:bread", "name": "&eBrowse the bakery"},
          "actions": [{"type": "animus:open-shop", "shop": "shop:bakery"}]},
    "H": {"type": "button", "icon": {"type": "item", "item": "minecraft:clock", "name": "&fIt is {{ time.hour }}:00"},
          "actions": [{"type": "message", "message": "&7Hello, {{ viewer.name }}."}]},
    "X": {"type": "button", "icon": {"type": "item", "item": "minecraft:barrier", "name": "&cClose"}, "actions": [{"type": "close"}]}
  }
}
```

Open a GUI with `/animus gui open <id> [player]`, with the behavior action `animus:open-gui`, or from another GUI with an `inventory` or `navigate` action. `/animus gui reload` reads the files again, `/animus gui list` lists them and `/animus gui problems` shows why a file was refused.

## What Animus reads

| Field | Meaning |
| --- | --- |
| `schemaVersion` | Must be 1. Any other version is refused by name. |
| `revision` | 1 or more. |
| `title` | Window title. `&` colour codes, `&#RRGGBB` and common MiniMessage colour and style tags such as `<red>` and `<bold>` work. `{{ expr }}` holes are filled for each viewer. |
| `resolution` | `9x1` to `9x6`, `5x1` (hopper) or `3x3` (dropper). Mod servers draw only the `9xN` sizes; a `5x1` or `3x3` window does not open there. |
| `mask`, `keys` | One row of characters per window row; each character draws the key with that name. A space is empty. |
| `slots` | Slot number to component. Explicit slots draw over the mask. |
| `list` | Fills the slots of one mask character with pages of a template. `var` names each entry, `source` is an expression that gives a list, `pageSize` defaults to the area, `refreshTicks` re-reads the source. List cells draw over everything else, as in Gloss. |
| `refresh` | `titleTicks` and `slotsTicks` redraw the window on a timer. `0` turns a timer off. |
| `show` | A condition. When false, the GUI does not open for that viewer. |
| `variants` | Replacement title, mask, keys or slots, chosen by the highest `priority` whose `when` is true. Nothing merges. |
| `actions` | Named action lists for `call`. Calls must exist and must not loop. |
| `closeOnTeleport` | Default true. |
| `select` | Read and checked, but not used: Animus opens a GUI by id. |
| `preset` | Refused. Gloss presets are not available; copy the fields in. |

Components are `button` (an icon and actions), `decoration` (an icon) and `toggle` (shows `trueIcon` and runs `trueActions` when `condition` gives `expectedValue`, otherwise the false side). Every component can have `show`. Icons are `item` (`item`, `count`, `customModelValue`, `name`, `lore`) and `playerHead` (`player`, `name`, `lore`); a head shows the player's skin only on Bukkit and only for an online player. Holographic menu components (`list`, `slider`, `field`, `tabs`) and icons (`text`, `textImage`, `block`, `entity`) have no meaning in a chest window and are refused. `customItem` icons are refused.

## Actions

Every action may have `trigger` (`any`, `left_click`, `right_click`, `shift_left_click`, `shift_right_click`), `when` (a condition) and `cooldownTicks`.

| Action | What it does |
| --- | --- |
| `message`, `actionbar`, `title`, `sound` | Sends text, an action bar, a title or a sound to the viewer. |
| `command` | Runs a command as the server or, with `"source": "player"`, as the viewer. |
| `close` | Closes the window. |
| `navigate` | `mode` `page` moves the list (`next`, `prev` or a number); `push` and `replace` open another GUI by id; `back` and `home` go through the history; `close` closes. |
| `inventory` | Opens another GUI by `id` with `args`, readable as `args.*`. |
| `setSession` | Stores a value for the viewer, readable as `session.*` until the window closes. |
| `give`, `take` | Gives or takes items. `take` without a name or lore matches the item type only; when it cannot take the amount it sends `denyMessage` and stops the list. |
| `economy` | `withdraw`, `deposit` or `has` money. Without an economy it fails, sends `denyMessage` and stops the list. |
| `delay`, `sequence` | Waits, or runs steps at `atTicks`. Waiting work stops when the window closes. |
| `if`, `switch`, `chance`, `cooldown`, `stop`, `call` | Flow control, as in Gloss. |
| `animus:open-shop` | Opens a [shop](shops.md) by `shop` id. `back` returns to this GUI. |

Gloss actions that have no meaning in Animus are refused when the file loads, with the exact field, for example `$.keys.X.actions[1].type: action 'camera' is not available in Animus`. These are `teleport`, `connect`, `bossbar`, `surface`, `prompt`, `dialog`, `book`, `parallel`, `repeat`, `emit`, `broadcast`, `effect`, `particle`, `setState`, `addState`, `clearState`, `sky`, `camera` and `glow`, and the cutscene fields `once`, `skippable` and `onSkip` of `sequence`. Animus-only actions start with `animus:`. Nothing is skipped silently: a file with any problem is refused as a whole and the previous files keep working until a reload.

A GUI that runs `command` anywhere needs its id or namespace on the same allow-list as the `animus:command` behavior action (`commandBehaviors` or `commandNamespaces` under `[behaviors.actions]`). Without it the file is refused with that reason.

## Expressions

`{{ expr }}` in text and the `show`, `when`, `condition`, `on`, `source` and `amount` fields use the Gloss expression grammar: numbers, quoted strings, colours (`#RGB`, `#RRGGBB`, `#AARRGGBB`), `true` and `false`, arrays, `!`, `-`, `* / %`, `+ -`, comparisons, `== !=`, `&&`, `||` and `a ? b : c`. The standard functions are `clamp`, `lerp`, `min`, `max`, `floor`, `ceil`, `round`, `abs`, `mod`, `pow`, `smoothstep`, `sin`, `cos`, `rgb`, `argb`, `alpha`, `mix`, `palette`, `select`, `number`, `bar`, `hex`, `str`, `fixed`, `plain`, `readable` and `align`, plus `len`, `contains`, `lower` and `upper`. An expression is at most 512 characters and 256 levels deep. An unknown name or function refuses the file at load; a failure while running shows `?` in text and counts as false in a condition.

| Name | Value |
| --- | --- |
| `viewer.name`, `viewer.uuid` | The viewer. `player.*` is the same. |
| `viewer.world`, `viewer.health`, `viewer.level`, `viewer.food`, `viewer.sneaking`, `viewer.x`, `viewer.y`, `viewer.z` | The viewer's state. Bukkit also has `viewer.op`, `viewer.flying` and `viewer.gamemode`. |
| `server.online`, `server.max` | Player counts. |
| `time.hour`, `time.minute`, `time.second`, `time.day`, `time.month`, `time.year`, `time.weekday`, `time.millis` | The server clock. |
| `args.*`, `session.*` | Values passed by `inventory` and stored by `setSession`. |
| `npc.name`, `npc.id`, `npc.uuid`, `npc.spawned`, `npc.health` | The NPC that opened the window, when one did. |
| `page`, `pages` | The list page, counted from 1, and the page count. |

`papi(...)` is not available yet.

## Safety

The server decides everything. Every click and drag in an Animus window is cancelled, so no item ever moves into or out of it. Only left, right, shift-left and shift-right clicks on the window's own slots reach the GUI, and they run on the next tick, outside the click itself. Number keys, the offhand key, drops, double clicks, middle clicks and shift clicks from the player's inventory do nothing. A window closes when the player logs out, dies, changes world or respawns, teleports (unless `closeOnTeleport` is false), when the NPC that opened it despawns, and when Animus stops.

## With and without Gloss

Animus never loads Gloss classes. Gloss documents can be copied into `gui/inventories/<namespace>/` as they are; one that uses a Gloss-only feature is refused with the field. Animus documents with no `animus:` actions can be copied back into Gloss's `inventories/` folder.
