package art.arcane.animus.bukkit.runtime;

import art.arcane.animus.core.virtual.ChunkPosition;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Hotloaded combat limits reach each region at the start of its next region tick, never in the middle of one. */
class RegionBudgetLiveLimitsTest {
    private static final ChunkPosition A = new ChunkPosition("world", 0, 0);

    @Test void shouldApplyAChangedLimitFromTheNextRegionTick() {
        FakeRegions regions = new FakeRegions().assign(1, A);
        Map<RegionBudget.Kind, Integer> limits = new EnumMap<>(RegionBudget.Kind.class);
        for (RegionBudget.Kind kind : RegionBudget.Kind.values()) limits.put(kind, 64);
        limits.put(RegionBudget.Kind.COMBAT_SCANS, 4);
        RegionBudget budget = new RegionBudget(regions, limits);
        List<Integer> granted = new ArrayList<>();
        regions.repeat(A, () -> {
            int first = budget.take(RegionBudget.Kind.COMBAT_SCANS, 2);
            budget.limit(RegionBudget.Kind.COMBAT_SCANS, granted.isEmpty() ? 1 : 8);
            granted.add(first + budget.take(RegionBudget.Kind.COMBAT_SCANS, 100));
        });
        regions.tick(1);
        assertEquals(4, granted.get(0), "A change mid-tick does not alter the limit of the running tick");
        regions.tick(1);
        assertEquals(1, granted.get(1), "The lowered limit applies from the next tick");
        regions.tick(1);
        assertEquals(8, granted.get(2), "A raised limit applies from the next tick too");
    }

    @Test void shouldRejectAnInvalidLimit() {
        FakeRegions regions = new FakeRegions().assign(1, A);
        Map<RegionBudget.Kind, Integer> limits = new EnumMap<>(RegionBudget.Kind.class);
        for (RegionBudget.Kind kind : RegionBudget.Kind.values()) limits.put(kind, 4);
        RegionBudget budget = new RegionBudget(regions, limits);
        assertThrows(IllegalArgumentException.class, () -> budget.limit(RegionBudget.Kind.COMBAT_BRAINS, 0));
    }
}
