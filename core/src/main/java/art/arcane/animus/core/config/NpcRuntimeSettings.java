package art.arcane.animus.core.config;

/**
 * Plugin-wide runtime budgets; definitions and state remain separate JSON documents. {@code packetsPerTick} is the NPC
 * packet work of one tick shared by every viewer (per region on Folia), {@code pendingSpawnsPerViewer} the spawns one
 * viewer may have unacknowledged, and {@code lookUpdatesPerTick} the look-close NPCs that may turn in one tick.
 * A hotload applies every key on the owner's next tick (on Folia, each region's next tick). A lowered cap keeps the
 * viewers and bodies already shown and only holds back new ones; a shorter view distance retires far bodies as usual.
 * {@code enabled} turns the main-thread runtime off and on live; on Folia it needs a restart.
 */
public record NpcRuntimeSettings(boolean enabled, int viewDistance, int maximumVisible, int maximumViewers, int updatesPerTick,
                                 int packetsPerTick, int pendingSpawnsPerViewer, int lookUpdatesPerTick) {
    public static final NpcRuntimeSettings DEFAULT = new NpcRuntimeSettings(true, 48, 64, 256, 16, 32, 8, 32);
    public NpcRuntimeSettings {
        if (viewDistance < 8 || viewDistance > 256 || maximumVisible < 1 || maximumVisible > 128
                || maximumViewers < 1 || maximumViewers > 1024 || updatesPerTick < 1 || updatesPerTick > 64
                || packetsPerTick < 1 || packetsPerTick > 1024 || pendingSpawnsPerViewer < 1 || pendingSpawnsPerViewer > 64
                || lookUpdatesPerTick < 1 || lookUpdatesPerTick > 1024)
            throw new IllegalArgumentException("NPC budgets require viewDistance=8..256, maximumVisible=1..128, maximumViewers=1..1024, updatesPerTick=1..64, "
                    + "packetsPerTick=1..1024, pendingSpawnsPerViewer=1..64, lookUpdatesPerTick=1..1024");
    }
}
