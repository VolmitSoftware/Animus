package art.arcane.animus.bukkit.runtime;

import art.arcane.animus.api.Outcome;
import art.arcane.animus.core.extension.CapabilityRefusal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NavigationRefusalsTest {
    private static void expect(String reason, Outcome.Status status, String code) {
        CapabilityRefusal refusal = NavigationRefusals.of(reason);
        assertEquals(status, refusal.status(), reason);
        assertEquals(code, refusal.diagnostics().getFirst().code(), reason);
        assertEquals(reason, refusal.diagnostics().getFirst().message());
    }

    @Test void routeEndingsKeepTheirMeaning() {
        expect("blocked: COLLISION; wall", Outcome.Status.FAILED, "navigation-blocked");
        expect("failed: NO_PATH", Outcome.Status.FAILED, "navigation-failed");
        expect("unavailable: terrain", Outcome.Status.UNAVAILABLE, "navigation-unavailable");
        expect("cancelled: world unloaded", Outcome.Status.CANCELLED, "navigation-cancelled");
        expect("replaced by reusable route reload", Outcome.Status.CANCELLED, "navigation-cancelled");
        expect("invalid waypoint 2: too far", Outcome.Status.INVALID, "navigation-invalid");
        expect("anything else", Outcome.Status.FAILED, "navigation-failed");
    }

    @Test void longReasonsAreBounded() {
        assertTrue(NavigationRefusals.of("blocked: " + "x".repeat(10_000)).diagnostics().getFirst().message().length() <= 512);
    }
}
