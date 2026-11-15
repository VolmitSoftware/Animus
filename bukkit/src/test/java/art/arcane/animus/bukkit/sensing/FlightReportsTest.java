package art.arcane.animus.bukkit.sensing;

import art.arcane.animus.core.combat.ProjectileFlight;
import art.arcane.animus.core.virtual.Vec3;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** A followed projectile reports each body once, with {@code stopped} true exactly where combat stopped it. */
class FlightReportsTest {
    private static final UUID GUARD = UUID.randomUUID(), GHOST = UUID.randomUUID(), BEHIND = UUID.randomUUID();
    private static final Vec3 AT = new Vec3(1, 65, 0);
    private static final Predicate<UUID> GUARD_TAKES_IT = Set.of(GUARD)::contains;
    private static ProjectileFlight.Hit hit(UUID npc) { return new ProjectileFlight.Hit(npc, AT); }

    @Test void aStrikeCombatReportsFirstIsNotReportedAgainByTheFlight() {
        FlightReports reports = new FlightReports(0);
        // Combat looks first and its arrow kills the guard: the body is gone, or no longer takes projectiles, when the flight looks.
        assertEquals(Optional.of(new FlightReports.Report(GUARD, AT, true)), reports.struck(GUARD, AT, true));
        assertEquals(List.of(), reports.crossed(List.of(hit(GUARD)), (UUID npc) -> false));
        assertTrue(reports.spent());
    }

    @Test void aBodyCombatWillStopTheProjectileInIsLeftToCombatsReport() {
        FlightReports reports = new FlightReports(0);
        assertEquals(List.of(), reports.crossed(List.of(hit(GUARD), hit(BEHIND)), GUARD_TAKES_IT), "the arrow stops in the guard and never reaches the body behind it");
        assertTrue(reports.spent());
        assertEquals(Optional.of(new FlightReports.Report(GUARD, AT, true)), reports.struck(GUARD, AT, true));
        assertEquals(Optional.empty(), reports.struck(GUARD, AT, true));
    }

    @Test void aStrikeOnADecoyIsReportedThoughTheProjectileNeverReachesTheBody() {
        FlightReports reports = new FlightReports(0);
        assertEquals(Optional.of(new FlightReports.Report(GUARD, AT, true)), reports.struck(GUARD, AT, true));
        assertTrue(reports.spent());
    }

    @Test void bodiesCombatDoesNotStopAreFlownThroughOnce() {
        FlightReports reports = new FlightReports(0);
        assertEquals(List.of(new FlightReports.Report(GHOST, AT, false)), reports.crossed(List.of(hit(GHOST), hit(GUARD), hit(BEHIND)), GUARD_TAKES_IT));
        assertEquals(List.of(), reports.crossed(List.of(hit(GHOST)), GUARD_TAKES_IT));
        assertTrue(reports.spent());
    }

    @Test void aPiercingArrowGoesOnPastAsManyStopsAsItsLevel() {
        FlightReports reports = new FlightReports(1);
        assertEquals(Optional.of(new FlightReports.Report(GUARD, AT, true)), reports.struck(GUARD, AT, false));
        assertFalse(reports.spent());
        assertEquals(List.of(new FlightReports.Report(GHOST, AT, false)), reports.crossed(List.of(hit(GUARD), hit(GHOST)), GUARD_TAKES_IT));
        assertEquals(List.of(), reports.crossed(List.of(hit(BEHIND)), Set.of(BEHIND)::contains));
        assertTrue(reports.spent());
    }
}
