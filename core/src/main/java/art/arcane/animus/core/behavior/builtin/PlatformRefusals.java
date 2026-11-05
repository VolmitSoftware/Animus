package art.arcane.animus.core.behavior.builtin;

import art.arcane.animus.api.Outcome;
import art.arcane.animus.core.extension.CapabilityRefusal;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** How every behavior platform turns runtime rejections into capability outcomes. */
public final class PlatformRefusals {
    private static final int MAXIMUM_REASON = 512;

    private PlatformRefusals() {}

    public static Optional<String> unavailability(BooleanSupplier runtimeAvailable, Supplier<String> runtimeStatus) {
        return runtimeAvailable.getAsBoolean() ? Optional.empty() : Optional.of(runtimeStatus.get());
    }

    /** Runtime services report a rejected request through these two exception types; anything else is a real fault. */
    public static <T> T mapped(Supplier<T> work) {
        try { return work.get(); }
        catch (IllegalArgumentException rejected) { throw refusal(Outcome.Status.INVALID, "platform-rejected", rejected); }
        catch (IllegalStateException rejected) { throw refusal(Outcome.Status.UNAVAILABLE, "platform-unavailable", rejected); }
    }

    public static CapabilityRefusal refusal(Outcome.Status status, String code, RuntimeException cause) {
        String message = cause.getMessage() == null || cause.getMessage().isBlank() ? cause.getClass().getSimpleName() : cause.getMessage();
        return new CapabilityRefusal(status, code, message.substring(0, Math.min(message.length(), MAXIMUM_REASON)));
    }

    public static CapabilityRefusal offline() {
        return new CapabilityRefusal(Outcome.Status.UNAVAILABLE, "player-offline", "The triggering player is no longer online");
    }
    public static CapabilityRefusal rejectedCommand() {
        return new CapabilityRefusal(Outcome.Status.INVALID, "command-rejected", "The server did not accept this command");
    }
    public static CapabilityRefusal inactive() {
        return new CapabilityRefusal(Outcome.Status.UNAVAILABLE, "npc-inactive", "The NPC is not active");
    }
    public static CapabilityRefusal unknownItem(String key) {
        return new CapabilityRefusal(Outcome.Status.INVALID, "unknown-item", "Unknown item: " + key);
    }
    public static CapabilityRefusal unknownParticle(String key) {
        return new CapabilityRefusal(Outcome.Status.INVALID, "unknown-particle", "Unknown particle: " + key);
    }
    public static CapabilityRefusal particleNeedsData(String key) {
        return new CapabilityRefusal(Outcome.Status.INVALID, "particle-needs-data", "This particle needs extra data, which behaviors cannot supply yet: " + key);
    }
    public static CapabilityRefusal worldUnavailable() {
        return new CapabilityRefusal(Outcome.Status.UNAVAILABLE, "world-unavailable", "The NPC's world is not loaded");
    }
}
