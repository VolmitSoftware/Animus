package art.arcane.animus.bukkit.runtime;

import art.arcane.animus.core.virtual.ChunkPosition;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** A hotload reaches each Folia region on that region's next tick, never in the middle of one. */
class RegionHotloadTest {
    private static final ChunkPosition A = new ChunkPosition("world", 0, 0), B = new ChunkPosition("world", 64, 64);

    private static Map<RegionBudget.Kind, Integer> limits(int routes) {
        Map<RegionBudget.Kind, Integer> limits = new EnumMap<>(RegionBudget.Kind.class);
        for (RegionBudget.Kind kind : RegionBudget.Kind.values()) limits.put(kind, 64);
        limits.put(RegionBudget.Kind.ROUTES, routes);
        return limits;
    }

    @Test void shouldPickUpPublishedSettingsAtEachRegionsNextTickOnly() {
        FakeRegions regions = new FakeRegions().assign(1, A).assign(2, B);
        RegionPickup<String> settings = new RegionPickup<>(regions, "first");
        List<String> seenA = new ArrayList<>(), seenB = new ArrayList<>();
        regions.repeat(A, () -> {
            seenA.add(settings.current());
            settings.publish("second");
            seenA.add(settings.current());
        });
        regions.repeat(B, () -> seenB.add(settings.current()));
        regions.tick(1);
        assertEquals(List.of("first", "first"), seenA, "a region keeps the settings it started its tick with");
        regions.tick(2);
        assertEquals(List.of("second"), seenB, "another region picks them up on its next tick");
        regions.tick(1);
        assertEquals("second", seenA.get(2));
        assertEquals("second", settings.current(), "away from a region thread the newest settings are read");
    }

    @Test void shouldGrantOnlyALoweredRouteBudgetFromTheNextRegionTick() {
        FakeRegions regions = new FakeRegions().assign(1, A);
        RegionBudget budget = new RegionBudget(regions, limits(16));
        int[] granted = new int[1];
        regions.repeat(A, () -> {
            granted[0] = 0;
            for (int i = 0; i < 64; i++) granted[0] += budget.take(RegionBudget.Kind.ROUTES, 1);
        });
        regions.tick(1);
        assertEquals(16, granted[0]);
        budget.limits(limits(4));
        regions.tick(1);
        assertEquals(4, granted[0]);
        budget.limits(limits(40));
        regions.tick(1);
        assertEquals(40, granted[0]);
    }

    @Test void shouldKeepTheLimitsARegionTickStartedWith() {
        FakeRegions regions = new FakeRegions().assign(1, A);
        RegionBudget budget = new RegionBudget(regions, limits(8));
        int[] granted = new int[1];
        regions.repeat(A, () -> {
            granted[0] = budget.take(RegionBudget.Kind.ROUTES, 2);
            budget.limits(limits(2));
            granted[0] += budget.take(RegionBudget.Kind.ROUTES, 64);
        });
        regions.tick(1);
        assertEquals(8, granted[0], "a hotload in the middle of a region tick waits for the next one");
        regions.tick(1);
        assertEquals(2, granted[0]);
    }

    @Test void shouldRefuseIncompleteLimitsOnHotload() {
        RegionBudget budget = new RegionBudget(new FakeRegions(), limits(8));
        Map<RegionBudget.Kind, Integer> missing = limits(8);
        missing.remove(RegionBudget.Kind.PACKETS);
        assertThrows(IllegalArgumentException.class, () -> budget.limits(missing));
    }
}
