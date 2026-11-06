package art.arcane.animus.bukkit.runtime;

import art.arcane.animus.core.virtual.ChunkPosition;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Each region enforces its simulation turns, and a region that runs short slows its own cosmetic work only. */
class RegionBudgetPressureTest {
    private static final ChunkPosition A = new ChunkPosition("world", 0, 0), B = new ChunkPosition("world", 64, 64);

    private static RegionBudget budget(FakeRegions regions, int simulation) {
        Map<RegionBudget.Kind, Integer> limits = new EnumMap<>(RegionBudget.Kind.class);
        for (RegionBudget.Kind kind : RegionBudget.Kind.values()) limits.put(kind, 64);
        limits.put(RegionBudget.Kind.SIMULATION, simulation);
        RegionBudget budget = new RegionBudget(regions, limits);
        budget.interval(4);
        return budget;
    }

    @Test void shouldGrantSimulationTurnsUpToTheLimitAndSlowOnlyThatRegion() {
        FakeRegions regions = new FakeRegions().assign(1, A).assign(2, B);
        RegionBudget budget = budget(regions, 3);
        List<Integer> granted = new ArrayList<>();
        regions.repeat(A, () -> {
            int turns = 0;
            for (int i = 0; i < 5; i++) if (budget.takeSimulation()) turns++;
            granted.add(turns);
        });
        List<Boolean> quiet = new ArrayList<>(), busy = new ArrayList<>();
        regions.repeat(B, () -> quiet.add(budget.cosmeticAllowed(0)));
        regions.tick(1);
        assertEquals(List.of(3), granted, "five NPCs asked, three turns granted");
        regions.repeat(A, () -> { for (long turn = 0; turn < 8; turn++) busy.add(budget.cosmeticAllowed(turn)); });
        regions.tick(1);
        assertEquals(2, busy.stream().filter(Boolean::booleanValue).count(), "one cosmetic turn in four on the short region");
        regions.tick(2);
        assertEquals(List.of(true), quiet, "the other region keeps its full cosmetic rate");
    }

    @Test void shouldSlowEveryRegionWhileBehaviorRunsWait() {
        FakeRegions regions = new FakeRegions().assign(1, A);
        RegionBudget budget = budget(regions, 64);
        AtomicBoolean backlog = new AtomicBoolean();
        budget.backlog(backlog::get);
        List<Boolean> distant = new ArrayList<>();
        regions.repeat(A, () -> { for (long turn = 0; turn < 4; turn++) distant.add(budget.distantMove(turn, 0)); });
        regions.tick(1);
        assertTrue(distant.stream().allMatch(Boolean::booleanValue), "distant bodies move every turn without pressure");
        distant.clear();
        backlog.set(true);
        regions.tick(1);
        assertEquals(1, distant.stream().filter(Boolean::booleanValue).count(), "one move in four for a distant body under pressure");
    }
}
