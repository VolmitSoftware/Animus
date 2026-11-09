package art.arcane.animus.core.runtime;

import art.arcane.animus.core.config.NavigationSettings;

/**
 * Work left in one owner tick, shared by routes, recordings and idle falls: collision cells, planning starts and
 * simulation turns. The owner refills it at the start of each tick, before any of them spend it.
 */
final class TickBudget {
    /** Collision cells left for route steps, recording frames and falls before a leg plans. */
    int motionCells;
    int planStarts;
    /** Path jobs the planner refused this tick for lack of capacity. */
    int pathRefusals;
    /** Simulation turns left: idle falls and rig playback. */
    int simulationLeft;

    void navigation(NavigationSettings settings) {
        planStarts = settings.planningStartsPerTick(); motionCells = settings.motionCellsPerTick(); pathRefusals = 0;
    }
    /** True when {@code wanted} cells are left, or when nothing has been spent yet, so one large step never stalls. */
    boolean cells(int wanted, NavigationSettings settings) {
        return motionCells >= wanted || motionCells >= settings.motionCellsPerTick();
    }
}
