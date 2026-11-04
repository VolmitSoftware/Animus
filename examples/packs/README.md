# Example behavior packs

Seven packs that use only built-in `animus:` capabilities, so they need no Java module. Each one is a complete [pack](../../docs/behaviors.md#packs): a `pack.json`, its behaviors, and an NPC definition that lists them.

| Pack | NPCs | Shows |
| --- | --- | --- |
| `greeter` | Greeter | A click greeting that remembers each player, and a wave when someone comes close. |
| `patrol` | Patroller | A square route walked twice on a timer, with `animus:navigate` and `"wait": true`. |
| `dialogue` | Innkeeper | Prompts whose answers lead to more prompts, and a flag that changes the next conversation. |
| `quest` | Quest Giver, Harbour Master | A delivery quest shared by two NPCs through the `quest` namespace's quest stage. |
| `shop` | Shopkeeper | Bread for emeralds through vanilla commands, charged to the player who clicked. Needs the command allow-list. |
| `town-guard` | Town Guard | Fights hostile mobs and anyone who attacks it, then walks back to its post. Greets members, challenges strangers and calls for help when hit. |
| `personal-guard` | Bodyguard | One player at a time can hire it; it follows that player and fights whatever hurts them until dismissed. |

## Installing

Copy a pack into the plugin folder, then copy its NPC definition into `npcs/`:

```sh
cp -r examples/packs/greeter plugins/Animus/packs/
cp -r examples/packs/greeter/npcs/examples plugins/Animus/npcs/
```

Then run `/animus behaviors reload` for the behaviors and `/animus reload` for the definitions. Behavior packs reload without a restart. The NPC files are not loaded from the pack itself; only `pack.json` and `behaviors/` are.

Every NPC stands in the world named `world` near 0, 64, 0, each at its own spot. Edit `position` in its definition to place it elsewhere. Check a pack before installing it with the converter:

```sh
converter/build/install/converter/bin/converter validate-behaviors examples/packs/greeter
```

## Limits of each pack

- **patrol** walks absolute coordinates from (0, 64, 0) to (8, 64, 8). Change the four `navigate` steps to points on solid ground where you place it. A leg fails the run with `navigation-blocked`, `navigation-unavailable` or `navigation-timeout` when the way is blocked, the terrain is not loaded, or 1,200 ticks pass.
- **dialogue** and **quest** answers arrive as `dialogue_choice` triggers, so their cooldowns and queue limits apply. A prompt stays open for the default 60 seconds.
- **shop** acts on the player who clicked: each command names them with `{player}`, which becomes their UUID. Payment comes first. `clear {player} minecraft:emerald 1` takes one emerald or fails when there is none, and `execute store success` records which in the `animus_shop` scoreboard objective. `give` runs only when that score is 1, and the score is then reset. A player cannot get bread without paying. The steps are still separate commands, so a payment can be lost without bread: when the player goes offline between them, when the action queue is full or times out after the emerald is taken, or when two shopkeepers using this objective serve the same player at once. The spawn trigger creates the objective; after the first start its `scoreboard objectives add` reports that the objective exists, which is harmless. The shopkeeper says the same line whether or not the sale went through. Commands are denied until you allow the pack's namespace in `config.toml` and restart:

  ```toml
  [behaviors.actions]
  commandNamespaces = ["shop"]
  ```

  Until then the shopkeeper's behavior is accepted but reported unavailable with `privileged-capability-not-granted`. Anyone who can edit files in an allowed namespace can run console commands.
- **town-guard** and **personal-guard** set their fighting in the NPC definition's `modules["animus:combat"]` (see [combat](../../docs/combat.md)). Both need combat enabled in the plugin settings, and protection such as WorldGuard `pvp` still decides every hit. Neither starts a fight with a player.
- **town-guard** targets the `monsters` category within 16 blocks and fights back against any player, mob or NPC that hurts it. It ignores creepers and runs from any within 4 blocks instead of hitting them. It drops a target past 24 blocks from where the fight began, then walks back there, which is its post when it was idle. Guards in the `town-guard` squad never fight each other and alert idle squad members within 24 blocks. It has 40 health, heals 1 every 5 seconds and respawns 30 seconds after dying. Its behavior only arms it, greets or challenges visitors, sounds the alarm when hit and announces its death.
- **personal-guard** has no targets of its own and fights back when hurt. Hiring runs `animus:combat-control` with `"operation": "guard"` and `"target": "player"`, so it guards whoever answered the prompt: it follows them to within 4 blocks and attacks whatever hurts them. Dismissing stops the guard and drops its target. The guard lasts until dismissal, despawn or restart. A client who logs out stays hired until then, and the bodyguard does not follow anyone across worlds.
- **personal-guard** keeps the hire in two flags: `hired` per player and NPC, and `taken` per NPC so a second player cannot hire it. The spawn trigger clears `taken`, so after a restart the bodyguard can be hired again, and a stale `hired` flag alone does not count. Purge them with `/animus behaviors state purge behavior animus:flags/personal-guard`.
