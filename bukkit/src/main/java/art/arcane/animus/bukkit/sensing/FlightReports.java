package art.arcane.animus.bukkit.sensing;

import art.arcane.animus.core.combat.ProjectileFlight;
import art.arcane.animus.core.virtual.Vec3;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The hits one followed projectile reports, each body once. Combat reports every body it stops the projectile in as the
 * strike lands, before the strike can kill; the flight reports the bodies it flies through and leaves the rest to combat,
 * whichever of the two looks first. Used only on the projectile's owner.
 */
final class FlightReports {
    /** A hit on {@code npc} where the projectile met it; {@code stopped} when it stopped in the body. */
    record Report(UUID npc, Vec3 point, boolean stopped) {}

    private final int pierce;
    private final Set<UUID> reported = new HashSet<>(), stoppedIn = new HashSet<>();
    private boolean spent;

    FlightReports(int pierce) { this.pierce = Math.max(0, pierce); }

    /** The projectile stopped for good; nothing past that body is reported. */
    boolean spent() { return spent; }

    /** The bodies the last move crossed, nearest first. {@code combatStops} names the ones combat stops the projectile in. */
    List<Report> crossed(List<ProjectileFlight.Hit> hits, Predicate<UUID> combatStops) {
        List<Report> reports = new ArrayList<>();
        for (ProjectileFlight.Hit hit : hits) {
            if (spent) break;
            if (reported.contains(hit.npc()) || stoppedIn.contains(hit.npc())) continue;
            if (combatStops.test(hit.npc())) { stop(hit.npc(), false); continue; }
            reported.add(hit.npc());
            reports.add(new Report(hit.npc(), hit.point(), false));
        }
        return List.copyOf(reports);
    }

    /** Combat stopped the projectile in this body; {@code last} when it goes no further. */
    Optional<Report> struck(UUID npc, Vec3 point, boolean last) {
        stop(npc, last);
        return reported.add(npc) ? Optional.of(new Report(npc, point, true)) : Optional.empty();
    }

    private void stop(UUID npc, boolean last) {
        if ((stoppedIn.add(npc) && stoppedIn.size() > pierce) || last) spent = true;
    }
}
