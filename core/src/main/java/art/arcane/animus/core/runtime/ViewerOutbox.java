package art.arcane.animus.core.runtime;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The packet operations one viewer has handed to the transport and not yet seen finish, bounded by
 * {@code [npcs] queuePerViewer}. Despawns are always admitted; authoritative work fills the queue; cosmetic work stops
 * a quarter short of it, so when the queue fills cosmetic updates are the first refused. Refused work is not lost: the
 * runtime sends the latest state on a later turn. The owner admits and sends; transport threads finish.
 */
public final class ViewerOutbox {
    private final AtomicInteger pending = new AtomicInteger();
    private long refused;

    /** Owner only: reserves a place for one operation of this tier, or refuses it. */
    public boolean admit(PresentationPressure.Tier tier, int capacity) {
        int limit = tier == PresentationPressure.Tier.COSMETIC ? capacity - capacity / 4 : capacity;
        if (tier != PresentationPressure.Tier.DESPAWN && pending.get() >= limit) { refused++; return false; }
        pending.incrementAndGet();
        return true;
    }

    /** The admitted operation was handed over; its place frees when it finishes, sent or failed. */
    public <T> CompletionStage<T> track(CompletionStage<T> sent) {
        sent.whenComplete((T result, Throwable failure) -> pending.decrementAndGet());
        return sent;
    }

    /** The admitted operation failed before the transport took it. */
    public void abandon() {
        if (pending.getAndDecrement() <= 0) { pending.incrementAndGet(); throw new IllegalStateException("No admitted operation to abandon"); }
    }

    public int pending() { return pending.get(); }

    /** Operations refused for a full queue since this viewer was admitted. */
    public long refused() { return refused; }
}
