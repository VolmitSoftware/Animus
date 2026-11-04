package art.arcane.animus.core.runtime;

import art.arcane.animus.api.Outcome;
import art.arcane.animus.core.extension.CapabilityRefusal;

/** Translates the runtime's route-ending reason into what a waiting behavior is told. */
public final class NavigationRefusals {
    private static final int MAXIMUM_REASON = 512;

    private NavigationRefusals() {}

    public static CapabilityRefusal of(String reason) {
        String bounded = reason.length() > MAXIMUM_REASON ? reason.substring(0, MAXIMUM_REASON) : reason;
        if (reason.startsWith("blocked")) return new CapabilityRefusal(Outcome.Status.FAILED, "navigation-blocked", bounded);
        if (reason.startsWith("unavailable")) return new CapabilityRefusal(Outcome.Status.UNAVAILABLE, "navigation-unavailable", bounded);
        if (reason.startsWith("cancelled") || reason.startsWith("replaced")) return new CapabilityRefusal(Outcome.Status.CANCELLED, "navigation-cancelled", bounded);
        if (reason.startsWith("invalid")) return new CapabilityRefusal(Outcome.Status.INVALID, "navigation-invalid", bounded);
        return new CapabilityRefusal(Outcome.Status.FAILED, "navigation-failed", bounded);
    }
}
