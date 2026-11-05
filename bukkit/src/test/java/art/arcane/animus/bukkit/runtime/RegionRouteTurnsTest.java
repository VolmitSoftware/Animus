package art.arcane.animus.bukkit.runtime;

import art.arcane.animus.core.virtual.ChunkPosition;
import org.junit.jupiter.api.Test;
import java.util.EnumMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RegionRouteTurnsTest {
    private static final ChunkPosition A = new ChunkPosition("world", 0, 0);

    /** Folia runs the NPC tasks of a region in the same order every tick; route turns must still reach the last ones. */
    @Test void shouldGiveEveryPatrolOnARegionATurnWhenTheyAskInTheSameOrder() {
        FakeRegions regions = new FakeRegions().assign(1, A);
        Map<RegionBudget.Kind, Integer> limits = new EnumMap<>(RegionBudget.Kind.class);
        for (RegionBudget.Kind kind : RegionBudget.Kind.values()) limits.put(kind, 64);
        limits.put(RegionBudget.Kind.ROUTES, 16);
        RegionBudget budget = new RegionBudget(regions, limits);
        int patrols = 264;
        int[] waited = new int[patrols], served = new int[patrols], longest = new int[patrols];
        for (int i = 0; i < patrols; i++) {
            int patrol = i;
            regions.repeat(A, () -> {
                if (budget.takeRoute(waited[patrol])) { served[patrol]++; waited[patrol] = 0; }
                else { waited[patrol]++; longest[patrol] = Math.max(longest[patrol], waited[patrol]); }
            });
        }
        int refused = 0;
        for (int tick = 0; tick < 200; tick++) { regions.tick(1); refused += budget.drainRefusedRoutes(); }
        for (int i = 0; i < patrols; i++) {
            assertTrue(served[i] >= 8, "patrol " + i + " served " + served[i]);
            assertTrue(longest[i] <= 2 * (patrols / 16 + 1) + 2, "patrol " + i + " waited " + longest[i]);
        }
        assertTrue(refused > 0, "refusals are counted for status and logging");
        assertEquals(0, budget.drainRefusedRoutes());
    }

    @Test void shouldRefuseRouteTurnsAwayFromARegionThread() {
        FakeRegions regions = new FakeRegions().assign(1, A);
        Map<RegionBudget.Kind, Integer> limits = new EnumMap<>(RegionBudget.Kind.class);
        for (RegionBudget.Kind kind : RegionBudget.Kind.values()) limits.put(kind, 4);
        RegionBudget budget = new RegionBudget(regions, limits);
        assertFalse(budget.takeRoute(0));
    }
}
