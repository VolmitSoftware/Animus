package art.arcane.animus.core.behavior.builtin;

import art.arcane.animus.api.Outcome;
import art.arcane.animus.core.extension.CapabilityRefusal;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlatformRefusalsTest {
    @Test void disabledRuntimeReportsItsOwnReasonOnEveryPlatform() {
        assertEquals(Optional.of("NPC runtime disabled in config.toml"), PlatformRefusals.unavailability(() -> false, () -> "NPC runtime disabled in config.toml"));
        assertEquals(Optional.empty(), PlatformRefusals.unavailability(() -> true, () -> { throw new AssertionError("A ready runtime must not format status off its owner"); }));
    }

    @Test void runtimeRejectionsKeepExpectedStatuses() {
        assertEquals("ok", PlatformRefusals.mapped(() -> "ok"));
        CapabilityRefusal invalid = assertThrows(CapabilityRefusal.class, () -> PlatformRefusals.mapped(() -> { throw new IllegalArgumentException("Route leg is too long"); }));
        assertEquals(Outcome.Status.INVALID, invalid.status());
        assertTrue(invalid.diagnostics().getFirst().message().contains("Route leg"));
        CapabilityRefusal unavailable = assertThrows(CapabilityRefusal.class, () -> PlatformRefusals.mapped(() -> { throw new IllegalStateException("NPC must be active"); }));
        assertEquals(Outcome.Status.UNAVAILABLE, unavailable.status());
        CapabilityRefusal passed = new CapabilityRefusal(Outcome.Status.BUSY, "busy", "Busy");
        assertSame(passed, assertThrows(CapabilityRefusal.class, () -> PlatformRefusals.mapped(() -> { throw passed; })));
        assertThrows(NullPointerException.class, () -> PlatformRefusals.mapped(() -> { throw new NullPointerException(); }));
    }

    @Test void rejectionMessagesAreBounded() {
        CapabilityRefusal refused = assertThrows(CapabilityRefusal.class, () -> PlatformRefusals.mapped(() -> { throw new IllegalStateException("x".repeat(10_000)); }));
        assertTrue(refused.diagnostics().getFirst().message().length() <= 512);
        CapabilityRefusal noMessage = assertThrows(CapabilityRefusal.class, () -> PlatformRefusals.mapped(() -> { throw new IllegalStateException(); }));
        assertFalse(noMessage.diagnostics().getFirst().message().isBlank());
    }
}
