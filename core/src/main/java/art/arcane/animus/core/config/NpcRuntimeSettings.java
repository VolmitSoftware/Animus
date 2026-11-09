package art.arcane.animus.core.config;

import art.arcane.animus.core.presentation.BodyRules;

/**
 * Plugin-wide runtime budgets; definitions and state remain separate JSON documents. {@code packetsPerTick} is the NPC
 * packet work of one tick, split evenly across the {@code updatesPerTick} viewers updated (per region on Folia); the
 * defaults give about five packets per player per tick at 100 players, and each viewer's own {@code queuePerViewer} and
 * {@code pendingSpawnsPerViewer} still bound what one connection receives. {@code pendingSpawnsPerViewer} is the spawns one
 * viewer may have unacknowledged, and {@code lookUpdatesPerTick} the look-close NPCs that may turn in one tick.
 * {@code simulationPerTick} is the NPC upkeep of one tick (idle falls and rig playback, per region on Folia),
 * {@code queuePerViewer} the packet operations one viewer may have unsent, and {@code degradedIntervalTicks} how far apart
 * cosmetic updates and moves of distant bodies are spread while a budget runs short.
 * A hotload applies every key on the owner's next tick (on Folia, each region's next tick). A lowered cap keeps the
 * viewers and bodies already shown and only holds back new ones; a shorter view distance retires far bodies as usual.
 * {@code enabled} turns the main-thread runtime off and on live; on Folia it needs a restart.
 */
public record NpcRuntimeSettings(boolean enabled, int viewDistance, int maximumVisible, int maximumViewers, int updatesPerTick,
                                 int packetsPerTick, int pendingSpawnsPerViewer, int lookUpdatesPerTick,
                                 int simulationPerTick, int queuePerViewer, int degradedIntervalTicks) {
    public static final NpcRuntimeSettings DEFAULT = new NpcRuntimeSettings(true, 48, 64, 256, 64, 512, 8, 32, 512, 24, 4);
    public NpcRuntimeSettings {
        require("viewDistance", viewDistance, 8, 256);
        require("maximumVisible", maximumVisible, 1, 128);
        require("maximumViewers", maximumViewers, 1, 1024);
        require("updatesPerTick", updatesPerTick, 1, 64);
        require("packetsPerTick", packetsPerTick, 1, 1024);
        require("pendingSpawnsPerViewer", pendingSpawnsPerViewer, 1, 64);
        require("lookUpdatesPerTick", lookUpdatesPerTick, 1, 1024);
        require("simulationPerTick", simulationPerTick, 16, 65536);
        require("queuePerViewer", queuePerViewer, 4, BodyRules.MAX_OPERATIONS);
        require("degradedIntervalTicks", degradedIntervalTicks, 1, 40);
    }

    private static void require(String field, int value, int minimum, int maximum) {
        if (value < minimum || value > maximum) throw new IllegalArgumentException(field + " must be within " + minimum + ".." + maximum + ", not " + value);
    }
}
