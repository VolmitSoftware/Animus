package art.arcane.animus.core.metrics;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Point-in-time counts the NPC runtime publishes once per second, read on the thread that rolls the metrics (the
 * runtime's owner on single-owner servers, the global region thread on Folia).
 *
 * @param runtime          how NPCs are owned here, for display
 * @param definitions      NPC definitions on disk
 * @param active           NPCs with a live body
 * @param dormant          spawned NPCs waiting for their chunk or a player nearby
 * @param sleepingRoutes   patrols with no player near them
 * @param viewers          players watching NPCs
 * @param visible          NPC bodies shown, summed over viewers
 * @param routes           routes with a turn in the scheduler
 * @param routesWaiting    routes left for a later tick by the navigation budget
 * @param pathJobs         path plans still being computed
 * @param navigationPressured routes have waited for budget long enough to log pressure
 * @param presentationStrained cosmetic packets are paused because authoritative ones waited for budget
 * @param cosmeticDeferred cosmetic updates skipped under strain since start
 * @param navigationLoad   0-100: share of the navigation budget used over the last second; 100 while routes wait
 * @param presentationLoad 0-100: share of the packet budget used over the last second; 100 while packets wait
 * @param places           where each active NPC stands
 */
public record RuntimeGauges(String runtime, int definitions, int active, int dormant, int sleepingRoutes, int viewers, int visible,
                            int routes, int routesWaiting, int pathJobs, boolean navigationPressured, boolean presentationStrained,
                            long cosmeticDeferred, int navigationLoad, int presentationLoad, List<NpcPlace> places) {
    public static final RuntimeGauges NONE = new RuntimeGauges("unavailable", 0, 0, 0, 0, 0, 0, 0, 0, 0, false, false, 0, 0, 0, List.of());

    /** One active NPC's chunk. */
    public record NpcPlace(UUID npc, String world, int chunkX, int chunkZ) {
        public NpcPlace { Objects.requireNonNull(npc); Objects.requireNonNull(world); }
    }

    public RuntimeGauges {
        Objects.requireNonNull(runtime);
        places = List.copyOf(places);
        navigationLoad = Math.clamp(navigationLoad, 0, 100);
        presentationLoad = Math.clamp(presentationLoad, 0, 100);
    }
}
