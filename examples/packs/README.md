# Example behavior packs

Seven packs that use only built-in `animus:` capabilities, so they need no Java module. Each one is a complete [pack](../../docs/behaviors.md#packs): a `pack.json`, its behaviors, and an NPC definition that lists them.

| Pack | NPCs | Shows |
| --- | --- | --- |
| `greeter` | Greeter | A click greeting that remembers each player, and a wave when someone comes close. |
| `patrol` | Patroller | A square route walked twice on a timer, with `animus:navigate` and `"wait": true`. |
| `dialogue` | Innkeeper | Prompts whose answers lead to more prompts, and a flag that changes the next conversation. |
| `quest` | Quest Giver, Harbour Master | A delivery quest shared by two NPCs through the `quest` namespace's quest stage. |
| `shop` | Shopkeeper | Bread for emeralds through vanilla commands. Needs the command allow-list. |
| `town-guard` | Town Guard | Arms itself on spawn, greets members, challenges strangers and calls for help when hit. |
| `personal-guard` | Bodyguard | One player at a time can hire and dismiss it. |

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
- **shop** sells to the nearest player within 6 blocks of 16, 64, 0, where the shopkeeper stands, and that is not always the player who clicked. Two commands run in turn, `give` only if the player holds an emerald and then `clear`, so something changing between them can give bread without taking an emerald. Commands are denied until you allow the pack's namespace in `config.toml` and restart:

  ```toml
  [behaviors.actions]
  commandNamespaces = ["shop"]
  ```

  Until then the shopkeeper's behavior is accepted but reported unavailable with `privileged-capability-not-granted`. Anyone who can edit files in an allowed namespace can run console commands.
- **town-guard** and **personal-guard** use `animus:combat-control` as it is today: it enables, disables, targets or releases the same manual combat bridge as `/animus combat`, and combat policy and protection checks still apply. There is no automatic targeting yet, so a guard does not pick its attacker or a hostile mob on its own, and the bodyguard cannot follow its client or defend them. Hiring only arms it and enables combat where it stands. The town guard's alarm reaches nearby players; it does not fight back. When the combat module ships, these packs can target from triggers instead.
- **personal-guard** keeps the hire in two flags: `hired` per player and NPC, and `taken` per NPC so a second player cannot hire it. Purge them with `/animus behaviors state purge behavior animus:flags/personal-guard`.
