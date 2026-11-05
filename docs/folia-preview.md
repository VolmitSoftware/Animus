# Folia stationary preview

The experimental Folia proof renders one stationary player-shaped NPC privately to an administrator. It has its own command and does not activate saved NPC definitions. Saved NPCs run separately through the [Folia runtime](folia-runtime.md), where riding and combat run on region owners; the remote-control preview stays unavailable on Folia.

Enable `packetProofEnabled = true` in `config.toml`; it applies when the file is saved, and turning it off clears existing proof bodies. An administrator with `animus.admin` can use:

```text
/animus foliaproof spawn [skin-sha256]
/animus foliaproof status
/animus foliaproof clear
```

Console can inspect global status. Spawn/clear belong to a player viewer. Skin references use existing content-addressed JSON files under `skins/`; signed texture contents are not logged. The NPC appears approximately three blocks ahead, aligned to a block center, with verified full-block support and clearance. Nearby partial or unknown block shapes can cause conservative rejection.

Every player or terrain read follows an ownership check. The entire body/support or interaction footprint must be owned, already loaded and sent to the viewer. A pending spawn is cancelled when its viewer teleports. Visible previews also retire after a teleport, including a same-position teleport, or after logout, death/respawn, world change, permission loss, unavailable terrain or expiry. A connection identity and exact native teleport sequence supplement Bukkit events because Folia's same-region asynchronous teleport path bypasses `PlayerTeleportEvent`.

Interactions use the server's eye position, direction, virtual hitbox, reach and line of sight. Client hit points do not establish authority. `VirtualNpcInteractionEvent` is synchronous on the viewer's owning region and carries `preview=true`; it supports cancellation and revalidates after listeners. The proof changes no real or virtual health and runs no commands. Integrations must respect preview isolation and separately own any other entities or terrain they access.

| Limit | Value |
| --- | --- |
| Concurrent viewer leases | 8 |
| Bodies per viewer | 1 |
| Admitted attempts per service lifetime | 64; restart after exhaustion |
| Pending command callbacks | 8 |
| Admitted skin reads | 2 |
| Lease lifetime / viewer distance | 120 seconds / 16 blocks |
| Interaction rate / reach | 10 per second / 4.5 blocks |
| Body/support / line-of-sight samples | 128 / 512 |

The lifetime ceiling also bounds retired network cleanup when a connection is stalled. Clearing a proof releases its concurrent slot but does not reset that ceiling. Invalid skin or transport completion can consume an admitted attempt. The [Folia runtime](folia-runtime.md) uses its own transport and NPC ownership lifecycle and does not share these preview limits.

This proof uses no world-registered NPC body, vehicle or helper entity. It does not establish multi-viewer production ownership, cross-region NPC transfer, mounted riding, complex entity presentation, graphical fidelity or performance improvement. Exact source/build and live verification status are recorded in [implementation evidence](implementation.md); protocol evidence remains distinct from graphical-client testing.
