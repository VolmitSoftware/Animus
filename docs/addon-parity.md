# Citizens add-on parity

Servers that move from Citizens to Animus often also run add-on plugins that store their settings in Citizens `saves.yml` or next to it. This page lists what three popular add-ons do, what Animus covers today, how their saved data should import, and the order in which to build the missing parts. It describes these plugins only as import and migration targets.

Research date: 2026-11-04. SpigotMC pages refused automated access, so listing text came from the Spiget mirror of each resource. Each claim links its source. "Not confirmed" means no public source answered it.

Status words:

- **covered**: Animus does this today.
- **partial**: Animus has part of it, or something close with different rules.
- **missing**: nothing in Animus does this yet.

Design words:

- **module**: a section under `modules["animus:..."]` in the NPC definition JSON, checked field by field like `animus:combat`.
- **action** / **condition**: a built-in behavior capability, as in [behaviors.md](behaviors.md#built-in-capabilities).
- **GUI**: a server-side inventory screen that Animus opens for a player. Animus has a chest screen framework that reads Gloss's inventory format ([chest GUIs](gui.md)); shops use it and Constructor can too.
- **pack file**: a JSON file in a behavior pack, outside the NPC definition, for data shared by many NPCs.

## 1. dtlTraders (shops)

### Versions and sources

dtlTraders has three formats that an importer can meet:

| Version | Where it stores data | Source |
| --- | --- | --- |
| Free 6.x (6.4.1 is the latest free build) | Trait `trader` in saves.yml, plus one YAML file per shop | [BukkitDev files](https://dev.bukkit.org/projects/dtltraders/files/3309667); behavior read from that build |
| Premium dtlTradersPlus 6.x (latest 6.4.39, closed source) | Same shop file layout, with more features enabled | [dtlTradersPlus README and default files](https://github.com/Minevid/dtlTraders-plus), [real 6.4.4 data folder (issue 6 attachment)](https://github.com/Minevid/dtlTraders-plus/issues/6), [version reference (issue 32)](https://github.com/Minevid/dtlTraders-plus/issues/32), shop files in [Aegis](https://github.com/TheRealTarma/Aegis) and [Nexoria](https://github.com/Felipe-1205/Nexoria) |
| Old open-source 3.x | Traits `trader` and `wallet` in saves.yml, with item strings | [Dandielo/dtlTraders](https://github.com/Dandielo/dtlTraders), [dtlCore](https://github.com/Dandielo/dtlCore), [a live server's saves.yml](https://github.com/minecraftportugal/mcpt-server) |

The free build already contains the premium code paths, but they are switched off. Premium changes after 6.4.4 are not confirmed.

### How a shop works

A shop has a name and one or more pages. Each page has three separate item grids: buy (the player pays money), sell (the player is paid), and trade (barter). Each mode can be switched on or off per shop, and `default-shop` picks the mode a player sees first. A page size must be a multiple of 9; other values fall back to 27.

A Citizens NPC with the `trader` trait opens its linked shop on right click. Left click is off by default. A sneaking player with `dtltraders.admin` gets the shop editor instead. Every setting is edited in one inventory editor (`/dtl trait`) by dragging items and typing text into an anvil prompt.

### Feature table

| Feature | What it does | Animus status | Proposed Animus design |
| --- | --- | --- | --- |
| NPC opens a shop on click | Right click opens the linked shop; left click can be enabled | covered | `modules["animus:shop"]` with `shop` and `openOn`; see [shops](shops.md) |
| Shop definitions | Named shop shared by many NPCs, with title, permission and enabled modes | covered | `shops/<namespace>/<path>.json`, checked field by field |
| Pages | Several pages per shop, each with a name, size and optional permission; pages a player cannot use are skipped | covered | `pages` with `permission`; hidden pages are skipped |
| Buy, sell and trade modes | Three grids per page, with a mode switch button | covered | `buy`, `sell` and `trade` grids; mode switch in the bottom row |
| Static items | Decoration only | covered | `type: static` |
| Money buy | Left click buys the stack size, shift click buys a full stack, right click asks for a custom amount. Price is for the stack and is divided per item | partial | `type: buy` with `price` and `allowStack`; a typed custom amount is not available |
| Money sell | Takes matching items from anywhere in the inventory. Can sell fewer than the amount when `sell-all-when-not-enough-items` is on | covered | `type: sell` with `sellPartial` |
| Barter trade | Needed items plus an optional money price give the obtainable items | covered | `type: trade` with `needs`, `gives` and an optional `price` |
| Command items (premium) | A purchase runs commands as player, console or op. `BUY_AND_KEEP` gives a token item that runs the commands when used | partial | `type: command`; commands run on purchase. `BUY_AND_KEEP` tokens are not made |
| Run as op | Runs a command with op rights through a temporary op player | covered | Not planned. Imports run OP commands from the console and report each one |
| On-purchase commands | Commands that run after a buy, sell or trade | covered | Entry `commands`, on the command allow-list |
| Item matching | Sell and trade compare material, damage, name, lore, enchantments, potion type and book content. Trade also compares the head texture. Hidden data and custom model data are not compared | covered | `match`: `strict` (default) or `legacy` (imports) |
| Per-player limits | Each item ID has a per-player count; `-1` is no limit | covered | `limit.perPlayer`, saved in `state/shops/` |
| Limit reset | One server-uptime counter clears every player's count for an item every N seconds. The time shown in lore is wrong after a restart | covered | `limit.resetSeconds` in real time per player; the import notes the change |
| Global limits and stock | Not present in dtlTraders (a global limit is an open request, issue 7) | covered | `limit.global` and `stock` with `restockSeconds` |
| Item permissions | Items need a permission; players without it see a notice | covered | Entry `permission` with a "needs permission" line |
| Lore and display | Price, item lore, description and limit lines in lore; or custom display keys from `messages.yml` | covered | Entry `display`; message templates under `[shops.messages]` |
| PlaceholderAPI in lore | Placeholders resolve in lore and descriptions | missing | Optional PlaceholderAPI adapter for shop text only |
| Broadcast | Chat broadcast on purchase with item, price and player | covered | Entry `broadcast` |
| Close after purchase | Per shop and per item | covered | Shop and entry `closeAfterPurchase` |
| Full inventory | Refuses when no empty slot, or drops extra items with `drop-item-on-full-inventory` | covered | Fit check counts partial stacks; `dropWhenFull` |
| Confirmation window (premium) | Asks before a purchase | covered | Shop `confirm` |
| Discounts (premium) | A discount price with start, duration and end dates | partial | Entry `discount` with `price`, `start` and `end`; percentages are not converted |
| Shop logs (premium) | Daily log file per shop with each transaction | covered | Shop `log`, daily files under `logs/shops/` |
| Open shop by command (premium) | `/dtl open <player> <shop>` and a per-shop command | covered | `/animus shop open <shop> [player]` and `animus:open-shop`; no per-shop command |
| Mirror and clone (premium) | Copy a page into another mode or shop | missing | Editor commands; pages can reference another page |
| In-game editor | Drag-and-drop editor with anvil text input | partial | JSON files and `/animus shop` commands; no editor yet |
| Selector items | `/dtl selector` gives page and mode buttons | partial | Fixed controls in the bottom row; materials are not configurable |
| Economy providers | One server economy: Vault, or OptEco, GemsEconomy or BeastTokens | partial | Vault on Bukkit; OptEco, GemsEconomy and BeastTokens are not supported; mod servers have no money |
| Old 3.x item strings and wallets | Prices per item or bundle, health, food, XP and level costs, patterns, tiers, wallets, limits by player name | covered | Money buy and sell entries convert; the rest is reported and kept |

### Commands and permissions to map

dtlTraders commands are `/dtltraders`, aliased `/dtl` and `/traders`: `trait`, `trait <npcId> [shop]`, `create`, `delete`, `list`, `edit [shop] <page>`, `editshop`, `toggle [buy|sell|trade] <shop>`, `reload`, `info`, `loadconfig`, `save`, `selector`, and `open <player> <shop>`. Permissions are `dtltraders.admin`, wildcards `dtltraders.shop.*`, `dtltraders.page.*` and `dtltraders.items.*`, one node per command, `dtltraders.inv.*` editor nodes, and the shop, page and item permissions that the data itself names. Sources: [README and permissions.yml](https://github.com/Minevid/dtlTraders-plus), [messages.yml](https://github.com/Minevid/dtlTraders-plus/blob/master/English/messages.yml).

Animus equivalents: `/animus shop status|list|open|reload|reset|problems|import` under `animus.admin`. Linking an NPC is an edit of its definition (`modules["animus:shop"]`); there is no separate link command. Shop, page and item permissions import unchanged, so existing permission setups keep working.

### Import mapping

| Source | Key | Animus target | Notes |
| --- | --- | --- | --- |
| saves.yml trait `trader` | `guiName` | `modules["animus:shop"].shop` | Lowercased. `null` means no shop |
| saves.yml trait `trader` | `converted-to-v5` | none | Marks NPCs already migrated from 3.x; old 3.x keys can still be next to it, so ignore them when this is `true` |
| saves.yml trait `trader` without `converted-to-v5` | `type`, `sell`, `buy`, `patterns`, `stock`, `owner-uuid` | New shop `converted-<npc-name>` | 3.x: the old `sell` list is what the NPC sells, so it becomes the Animus `buy` grid, and the old `buy` list becomes `sell`. Use the bundle price when `.sp` is set, otherwise price times amount |
| saves.yml trait `wallet` (3.x) | `type`, `money`, `player-uuid` | Kept for review | No Animus wallets |
| `shops/<name>/<name>.yml` (6.x) or `guis.yml` (5.x) | Root key = shop name | `shops/<name>.json` | `title`, `permission`, `default-shop`, `buy-shop-enabled`, `sell-shop-enabled`, `trade-shop-enabled`, `close-after-purchase`, `confirmation-window-enabled`, `logging-enabled`, `command` |
| Shop file | `pages.page-N` | `pages[N]` | `size`, `page-name`, `page-permission`. An empty name gets a generated name in dtlTraders; keep the generated value |
| Shop file | `buy-items`, `sell-items`, `trade-items` → `item-<slot>` | Slot maps by slot number | `id` stays as the limit key |
| Item entry | `type`, `item`, `trade-price`, `trade-limit`, `limit-reset-seconds`, `permission`, `description`, `display-name`, `display-lore`, `show-*` flags, `custom-input-enabled`, `stack-sale-enabled`, `drop-item-on-full-inventory`, `sell-all-when-not-enough-items`, `broadcast-message`, `close-after-purchase` | Entry fields above | `item` is a Bukkit-serialized item (`==`, `v`, `type`, `amount`, `meta`) or a short form `{type, amount, damage, name, lore}` |
| Trade entry | `needed-items.trade-item-N`, `obtainable-items.obtainable-item-N`, `on-trade-commands` | `needs`, `gives`, `onPurchase` | |
| Command entry (premium) | `run-mode`, `commands.N.{executor, command}`, `close-inventory` | `type: command` | `%player%` becomes `{player}`. `OP` executor becomes console and is reported |
| Discount (premium) | `Discount.*` | `discount` | Read both `Discount.duration` and `Discount.discount-duration`; dates are `dd/MM/yyyy HH:mm:ss` |
| `data.dat` | Next item ID, per-player counts, uptime counter | Limit state | Binary Java data stream; import counts as player-scoped state |
| `BUY_AND_KEEP` tokens in player inventories | First lore line holds the item ID | Token rule | Accept the old lore marker so tokens bought before migration still work |
| `config.yml`, `messages.yml` | Click toggles, economy choice, button layout, price format | Plugin TOML | Price format is a Java decimal pattern such as `#0.00` |

Format quirks to accept: the discount duration and confirmation flags are read and written under different key names, and a deleted shop leaves NPCs linked to a missing name. Report a missing shop and leave the NPC without a shop.

### Edge cases to keep

- A trade checks needed items in the 36 main slots, may take them from several stacks, then checks space, then charges money only when the price is above 0.
- A sale that would remove no items is refused.
- dtlTraders refuses a purchase when no slot is empty even if the items would stack. Animus should count partial stacks; this is a deliberate improvement and should be stated in the release notes.
- Items that differ only in hidden data match each other in dtlTraders. The `dtl` match rule keeps this so imported shops behave the same; new shops should default to `strict`.
- Every money change and item change must happen together or not at all, on the player's owning thread.

### Implementation order

Steps 1 to 7 are built; see [shops](shops.md) and [chest GUIs](gui.md). Step 8 remains.

1. Inventory screen framework (pages, buttons, click types, safe close, no item theft through shift, number keys or the offhand key).
2. Economy adapter with Vault, transactional withdraw and deposit, and failure reporting.
3. Shop files and the `animus:shop` module with static, buy, sell and trade entries, item matching and the fit check.
4. Importer for 6.x shop files and the `trader` trait, then 5.x `guis.yml`.
5. Per-player limits with reset, item and page permissions, and `data.dat` import.
6. Command entries and tokens, on-purchase behaviors, broadcast and close-after-purchase.
7. Discounts, confirmation, logs, open-by-command and the `animus:open-shop` action.
8. PlaceholderAPI text, the in-game editor, then 3.x item string import.

## 2. Sentinel (combat)

Animus already has a Sentinel-style combat module ([combat.md](combat.md)) and a field-by-field importer ([compatibility.md](compatibility.md#sentinel-settings)). This section lists only what is still missing or partial. Sentinel is open source; facts below come from the pinned commit [96672f1](https://github.com/mcmonkeyprojects/Sentinel/tree/96672f1b03f2182d11f8e3188cae24b9cdaf14a8) used in [upstream-inventory.md](upstream-inventory.md), its [README](https://github.com/mcmonkeyprojects/Sentinel/blob/96672f1b03f2182d11f8e3188cae24b9cdaf14a8/README.md) and [plugin.yml](https://github.com/mcmonkeyprojects/Sentinel/blob/96672f1b03f2182d11f8e3188cae24b9cdaf14a8/src/main/resources/plugin.yml).

### Import defects found

These change how imported NPCs behave and should be fixed before any new feature.

| Problem | Sentinel behavior | Animus behavior | Fix |
| --- | --- | --- | --- |
| `respawnTime: 0` | The NPC stays dead and is marked not spawned ([onDeath](https://github.com/mcmonkeyprojects/Sentinel/blob/96672f1b03f2182d11f8e3188cae24b9cdaf14a8/src/main/java/org/mcmonkey/sentinel/SentinelTrait.java)) | Converts to `delayTicks: 0`, which respawns at once | Convert 0 to `-1` |
| `respawnTime` below 0 | The NPC is deleted on death | Never respawns, but is not deleted | Add `respawn.deleteOnDeath` or report the difference in `migrationReport` |
| `OWNER` ignore | Added to every new Sentinel's ignores, so it never attacks its owner ([onAttach](https://github.com/mcmonkeyprojects/Sentinel/blob/96672f1b03f2182d11f8e3188cae24b9cdaf14a8/src/main/java/org/mcmonkey/sentinel/SentinelTrait.java)) | Kept unsupported, so an imported guard that targets players can attack its owner | Add an `owner` rule that uses the definition's `owner` |

### Feature table

| Feature | What it does | Animus status | Proposed Animus design |
| --- | --- | --- | --- |
| Basic categories and entity types | `PLAYERS`, `NPCS`, `MONSTERS`, `MOBS`, `PASSIVE_MOBS`, and about 80 entity names with plural aliases | covered | Already converted |
| `OWNER` target and ignore | The NPC's Citizens owner | missing | Rule entry `owner: true` |
| Name patterns | `player:`, `npc:` and `entityname:` are regular expressions | partial | Animus matches exact names. Add `patterns` lists with a bounded regex engine |
| Held and worn items | `helditem:`, `offhand:`, `equipped:`, `in_inventory:` item matchers | missing | Rule `items` with slot and material or pattern |
| Permission groups | `group:` through Vault | missing | Rule `groups` through a permission adapter |
| Permission nodes | `permission:` | missing | Rule `permissions` |
| Health | `healthabove:` and `healthbelow:` percentage | missing | Rule `health` with `above` and `below` |
| Potion effects | `potion:` | missing | Rule `effects` |
| Scoreboard | `sbteam:`, `sbscoreabove:`, `sbscorebelow:` | missing | Rule `teams` and `scores` |
| Mob status | `status:angry`, `status:passive` | missing | Rule `status` |
| NPCs owned by | `npc_owned_by:<target>` | missing | Rule `ownedBy` with a nested rule |
| Squad and UUID | `squad:`, `uuid:` | covered | Already converted |
| Combined rules | `multi:` (several separate targets together) and `allinone:` (one entity matches all parts) | missing | Rule `all` (one entity, all parts) and `together` (several entities) |
| Fight events | `event:pvp`, `pve`, `eve`, `pvnpc`, `pvsentinel`, `npcvnpc`, `pv:<target>`, `ev:<target>` target whoever starts that kind of fight nearby | missing | Rule `events` evaluated from Bukkit damage events within scan range |
| Guarded fight | `event:guarded_fight` attacks what the guarded player attacks | partial | Animus guards defend their charge; add `guard.assist` |
| Chat trigger | `event:message:<text>` targets a visible player whose chat contains the text | missing | Rule `chat` with a substring list |
| Other plugins | Towny, Factions, SimpleClans, War, SimplePets, BeautyQuests, Denizen procedures | missing | Optional target adapters later; keep entries for review |
| Melee and arrows | Melee with reach and rate; bows and crossbows | covered | |
| Other weapons | Trident, fireball from blaze rod, splash and lingering potions, lightning from nether star, spectral arrows, snowballs, eggs, ender pearls, wither skulls, llama spit, shulker bullets, evoker fangs | missing | `ranged.weapon` values added one at a time, each with one damage authority |
| Arrow type from inventory | Tipped, spectral or fire arrows set by inventory contents | missing | `ranged.arrow` from definition inventory |
| Healer NPC | Potions on friendly targets, as in the README healer example | missing | `ranged.weapon: potion` with `effect`, used with health rules |
| Damage and armor from equipment | `damage: -1` and `armor: -1` use held item and worn armor | partial | Converts to fixed values. Add `fromEquipment: true` using item attributes |
| Weapon damage map | `weapondamage MATERIAL DAMAGE` | missing | `melee.weaponDamage` map |
| Weapon redirect | Treat one material as another | missing | `weapons.redirect` map |
| Ammunition | `needammo` uses arrows and potions from the NPC inventory | missing | `ranged.useAmmo` that reduces definition inventory at runtime only |
| Auto switch | Swap between bow and sword by distance using inventory items | partial | Animus switches between its own melee and ranged sections. Add showing the matching held item |
| CrackShot and QualityArmory guns | Fire gun items | missing | Out of scope; keep for review |
| Greeting and warning | Messages to friendly or hostile players in `greet_range`, every `greet_rate` ticks | missing | `greetings` section with `friendly`, `hostile`, `range`, `cooldownTicks`, or a behavior trigger `combat-greet`. 60 imported records use `warning_text` |
| WorldGuard region leash | `wgregion` keeps chasing inside one region | missing | `pursuit.region` checked through the existing WorldGuard adapter |
| Path guarding | Guards a Citizens path or point and returns to it | partial | Return to post and patrol resume exist; patrol restarts from the first waypoint |
| Scan range | Any value; default 20, many servers use 50 to 100 | partial | Limited to 64 |
| Speed | Speed modifier | partial | Limited to 1 to 5 blocks per second |
| Mobs fight NPCs | Sentinel NPCs are real entities that mobs target | partial | Only the zombie adapter targets NPCs |
| Player damage rules | Vanilla damage including enchantments, potions and critical hits | partial | Attack attribute and charge only |
| Kill, respawn, forgive | `/sentinel kill`, `respawn`, `forgive` | partial | `release` acts like forgive. Add `kill` and `respawn` operations |
| Info and statistics | `/sentinel info`, `stats`, `targets`, `ignores`, `avoids` | partial | `/animus combat <npc> status` exists. Counters are imported but not updated |
| Death messages | Custom death messages for kills | missing | `death.message` text |
| Invisible targets | `random.ignore invisible targets` | missing | `targeting.ignoreInvisible` |
| Armor stand targets | Off by default (`random.allow armor stand targets`) | partial | Armor stands count as mobs; confirm they are excluded by default |
| Guard damage | Guards cannot hurt the player they guard (`random.no guard damage`) | covered | Guards never target their charge |
| Explosion blocks | Fireballs and skulls do not break blocks | missing | Needed with explosive weapons |
| Defaults from config | `sentinel defaults.*` in Sentinel's config set new NPC values | missing | Optional `[combat.defaults]` in TOML; imports already write Sentinel defaults |
| API events | Attack, chase start and end, combat state, no more targets, wants to path | partial | `damage` and `death` triggers exist. Add `combat-start`, `combat-end` and `target-lost` triggers |
| Editing by command | About 55 `/sentinel` subcommands with `sentinel.<name>` permissions and `sentinel.admin` for other owners' NPCs | partial | Animus edits JSON with `animus.admin`. Owner-scoped editing is not planned |

### Import mapping

The saves.yml path is `npc.<id>.traits.sentinel`; the current mapping is in [compatibility.md](compatibility.md#sentinel-settings). Changes this section adds:

| Sentinel key | Change |
| --- | --- |
| `respawnTime` | 0 becomes `-1`; below 0 sets `deleteOnDeath` or is reported |
| `allIgnores` containing `OWNER` | Becomes `ignores.owner: true` |
| `allTargets` and others: `byPlayerName`, `byNpcName`, `byEntityName` | Become `patterns` once regex rules exist |
| `byHeldItem`, `byOffhandItem`, `byEquippedItem`, `byInventoryItem` | Become `items` rules |
| `byGroup`, `byStatus`, `byOther` (`healthabove`, `potion`, `permission`, `sbteam`, `npc_owned_by`) | Become the matching new rules |
| `byEvent` | Becomes `events`, `chat` and `guard.assist` |
| `byAllInOne`, `byMultiple` | Become `all` and `together` |
| `warning_text`, `greeting_text`, `greet_range`, `greet_rate` | Become `greetings` |
| `worldguard_region` | Becomes `pursuit.region` |
| `needsAmmo`, `autoswitch`, `weapon_damage_map`, `weapon_redirect_map` | Become `ranged.useAmmo`, held-item switching, `melee.weaponDamage`, `weapons.redirect` |
| `damage: -1`, `armor: -1` | Become `fromEquipment: true` |

### Implementation order

1. Fix the three import defects above.
2. Greetings and warnings (60 imported records use them).
3. Regex name patterns, item rules, health and permission rules, and `all` combined rules.
4. Fight event rules and guard assist.
5. Damage and armor from equipment, weapon damage map and the held item display for switching.
6. WorldGuard region leash, `kill` and `respawn` operations, and combat start and end triggers.
7. Extra weapons in this order: potions (healer), trident, snowball and egg, fireball, lightning, the rest.
8. Optional third-party target adapters.

## 3. Constructor, formerly Builder (NPCs that build)

### Versions and sources

| Version | What is known | Source |
| --- | --- | --- |
| Builder 0.7.1 by jrbudda, open source, old MCEdit `.schematic` only | Exact saves.yml keys, commands, config and build order | [jrbudda/Builder](https://github.com/jrbudda/Builder) (`BuilderTrait.java`, `BuilderSchematic.java`, `config.yml`, `plugin.yml`, `supply.txt`) |
| Builder fork for 1.12 | Adds tile entity contents and a `silent` flag | [Morphan1/Builder](https://github.com/Morphan1/Builder) |
| Constructor 4.1 by Weefle (premium, closed source, 2026-07-27) | Features, commands and permissions from the listing and change log only | [Spiget resource 79683](https://api.spiget.org/v2/resources/79683), [updates](https://api.spiget.org/v2/resources/79683/updates), [older 2.1.4 page](https://voxel.shop/resource/constructor-1-13-1-15.54) |

The linked source repository, github.com/Weefle/Constructor, is not available. Constructor's permission and command names repeat Builder's, so it is likely derived from Builder, but its saves.yml keys are **not confirmed**. A sample saves.yml from a Constructor server is needed before writing that importer.

### Feature table

| Feature | What it does | Animus status | Proposed Animus design |
| --- | --- | --- | --- |
| Schematic files | Builder: `.schematic`. Constructor: Sponge `.schem` and structure `.nbt`, own reader as a fallback | missing | Animus reader for Sponge `.schem` versions 2 and 3 and vanilla `.nbt`, no WorldEdit needed; legacy `.schematic` through a one-time conversion |
| Multi-part builds (Constructor) | A YAML file lists parts built in order with offsets | missing | `build` pack file with `parts` and offsets; YAML layout not confirmed |
| Build module | NPC has a loaded schematic, an origin and options | missing | `animus:builder` module with `schematic`, `origin`, `options` |
| Origin | NPC position, player position, a set point, or the schematic's own origin. Builder centres X and Z on the origin and trims empty bottom layers | missing | `origin` with `mode` (`npc`, `point`, `schematic`) and `yOffset`; centre rule kept for Builder imports |
| Rotation (Constructor) | Rotates the build, with block state fixes | missing | `rotation` 0, 90, 180, 270 with block state rotation |
| Mirroring | Not mentioned | missing (not needed for parity) | Later |
| Build order | Bottom up in groups of `layers:N`. Within a group: air, base blocks, furniture, redstone, liquids and falling blocks, then decorations. `groupall` groups the whole build | missing | Built-in planner with the same phase order; `layers` and `groupAll` options |
| Layer pattern | `spiral` (default), `reversespiral`, `linear`, `reverselinear` | missing | `pattern` option |
| Skip matching blocks | Blocks that already match are skipped. Repair mode replaces changed blocks and removes added ones | missing | Planner compares each block before queuing; `mode: build` or `repair` |
| Ignore air and liquids | `ignoreair`, `ignoreliquid`; Constructor places air only with both ignore air and excavate | missing | `ignoreAir`, `ignoreLiquids` |
| Excavate | Clears the site top down before building | missing | `excavate` option; removed blocks are kept as described below |
| Walking to blocks | NPC walks next to each block, then places it. Teleports after a timeout (default 2 seconds) or when stuck. Walks back at the end | partial | Navigator exists (walking, 30 blocks per request, `teleportWhenStuck`). Add a reach search around each block and `moveTimeoutSeconds` |
| Build speed | Walking speed plus the timeout; no ticks-per-block setting | partial | Navigator speed 1 to 5; add `blocksPerTick` limits in TOML |
| Place effects | Swing arm, hold the block (or a pickaxe for air), block sound and particles | partial | `animus:set-equipment`, sound and particle exist. Add arm swing and per-block effect |
| Containers and signs | Constructor keeps chest, furnace, shulker, sign and banner contents. Builder 0.7.1 does not | missing | Copy block entity data from the schematic |
| Entities in schematics | Not placed | missing (not needed) | |
| Physics | Builder places without physics updates | missing | Place without updates, then update each finished group; falling and attached blocks go last |
| Materials from players | Supply mode: the build waits until players donate the needed items by right click; Builder maps blocks to items with `supply.txt` | missing | `materials.mode: donate` with a needed list as runtime state; click to see the list and donate |
| Materials list | Constructor has a needed materials screen; Builder shows a chat list | missing | GUI list, shared with the shop screen framework |
| Excavated items | Constructor stores removed blocks and returns them through `/constructor excavated` | missing | Store removed items as NPC state; GUI to collect |
| Chest or economy sourcing | Not present in either | missing (not needed) | Optional later |
| Mark area | Builder places markers at the four corners and restores them later | missing | Packet-only outline, no world change |
| Preview (Constructor) | Glowing ghost blocks for N seconds, limit 3000 entities | missing | Packet-only block displays per viewer; `previewSeconds`, `maxPreviewBlocks` |
| Undo (Constructor) | Reverts every placed block, including contents, across sessions | missing | Undo journal stored per build, with the original block and its contents |
| Teams (Constructor 4) | A leader with workers sharing one build by distance; members persist | missing | `team` list on the leader; the planner hands out blocks by nearest worker |
| Survey and info | Survey lists needed materials; info shows total, remaining and percent | missing | `/animus build <npc> survey|info` |
| Pause, cancel, resume after restart | Cancel stops. After restart the build continues, skipping blocks already placed | missing | Build state saved with the NPC; resume rebuilds the queue and skips matching blocks |
| Start, complete and cancel hooks | Messages, Denizen tasks (Builder `onstart`, `oncomplete`, `oncancel`), Denizen triggers in Constructor | missing | Behavior triggers `build-start`, `build-complete`, `build-cancel` |
| Protection | Builder never checks. Constructor checks WorldGuard build and break flags and sends test place and break events for other claim plugins, with a bypass permission | missing | Protection adapter that asks before each block; deny skips and reports. Bypass only through settings |
| Chunk loading | Builder loads chunks as it places | missing | Place only in loaded chunks; wait when a chunk is not loaded, matching the no-terrain-loading rule |
| GUIs (Constructor) | NPC list, schematic list, parameters, team and preview buttons | missing | Phase 2 GUI; Phase 1 commands |
| Dynmap refresh | Re-render the area when done | missing (not needed) | |

### Commands and permissions to map

Builder: `/builder (id) <help|reload|list|load <name>|origin [clear|schematic|me|current]|mark [material]|build [flags]|cancel|survey [excavate]|timeout <seconds>|supply <true|false>|hold <true|false>|info>`. Build flags: `ignoreair`, `ignoreliquid`, `excavate`, `layers:N`, `groupall`, `spiral`, `reversespiral`, `linear`, `reverselinear`, `yoffset:N`, `onstart:`, `oncomplete:`, `oncancel:`. Each build run resets the options to their defaults except `yoffset`. Permissions are `builder.*` with one node per command plus `builder.donate`. Source: [jrbudda/Builder](https://github.com/jrbudda/Builder).

Constructor: `/constructor load|build [-ignoreAir] [-ignoreLiquid] [-ignoreProtection] [-excavate]|cancel|undo|preview [seconds]|team add|remove|clear|list|npc|list|excavated|info|survey|reload|timeout|supply`. Permissions `constructor.<command>`; `info`, `survey` and `donate` are given to all players by default. Source: [listing](https://api.spiget.org/v2/resources/79683).

Animus equivalents: `/animus build <npc> load|start|cancel|undo|preview|survey|info|team ...` under `animus.admin`, with donating open to any player when the build allows it.

### Import mapping

Builder, trait path `npc.<id>.traits.builder` ([BuilderTrait.java](https://github.com/jrbudda/Builder)):

| Builder key | Animus target | Notes |
| --- | --- | --- |
| `Schematic` | `schematic` | File name without extension. Missing files are reported |
| `State` | Build state | `building` and `collecting` resume; `marking` becomes idle |
| `PatternXY` | `options.pattern` | |
| `IgnoreAir`, `IgnoreLiquid`, `Excavate` | `options.ignoreAir`, `ignoreLiquids`, `excavate` | |
| `HoldItems` | `options.holdBlock` | |
| `RequireMaterials` | `materials.mode: donate` | |
| `NeededMaterials.<id>` | Outstanding materials | Old numeric item IDs need a lookup table |
| `MoveTimeoutSeconds` | `options.moveTimeoutSeconds` | |
| `YOffset`, `YLayers` | `origin.yOffset`, `options.layers` | |
| `Origin.{world,x,y,z,yaw,pitch}` | `origin` point | |
| `ContinueLoc.{...}` | Resume anchor | |
| `onstart`, `oncomplete`, `oncancel` | Kept for review | Denizen task names; translate to behaviors by hand |
| `Silent` (fork) | `options.silent` | |
| `toggled` | none | |

`groupall` and queue position are not saved by Builder, so a resumed build always groups by layer.

Constructor, trait path `npc.<id>.traits.constructor`: key names are not confirmed. The importer should keep the whole trait for review and map Builder-style keys only when it finds them. Team membership is stored in this trait, according to the change log.

Files: Builder schematics are in `plugins/Builder/schematics/` (setting `SchematicsFolder`); Constructor's are in `plugins/Constructor/schematics/`. Import copies them into an Animus schematics folder. Builder `config.yml` texts (`DefaultTexts.*` with `<NPC>`, `<SCHEMATIC>`, `<AMOUNT>`, `<PLAYER>`, `<ITEM>`) map to Animus message templates, and `supply.txt` maps to a materials table.

### Implementation order

1. Schematic reader for `.schem` and `.nbt`, and the build planner with phase order, layers, patterns, skip-matching and ignore options.
2. Block placer with per-tick limits, loaded chunks only, a protection check per block, and block entity contents.
3. `animus:builder` module, `/animus build` commands, and walking to each block with timeout teleport.
4. Saved build state, resume after restart, and the Builder trait importer with schematic copy.
5. Excavation, the undo journal and repair mode.
6. Donated materials, survey, the materials list and excavated items, using the shared screen framework.
7. Packet-only preview and mark, rotation, and start, complete and cancel triggers.
8. Teams, multi-part builds and the Constructor importer once a sample save is available.

## Shared work

All three add-ons need the same pieces. Building them once avoids three versions:

- An inventory screen framework (shops, materials lists, excavated items, editors). Built: [chest GUIs](gui.md).
- An economy adapter (shops; not needed by the other two). Built: Vault on Bukkit.
- A protection adapter for block changes, next to the existing combat protection adapter.
- A per-player and per-NPC state store for limits, build progress and undo data, reusing behavior state.

## What could not be confirmed

- dtlTradersPlus changes after 6.4.4, including any new keys and how the discount percentage is used.
- Constructor saves.yml keys, its full config.yml, the multi-part YAML layout, pattern names, hold-item behavior, what it does when protection denies a block, and how it handles unloaded chunks.
- The SpigotMC pages and reviews for all three plugins and the Citizens wiki Builder page refused access.
