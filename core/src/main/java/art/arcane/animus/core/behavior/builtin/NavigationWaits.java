package art.arcane.animus.core.behavior.builtin;

import art.arcane.animus.api.Capabilities;
import art.arcane.animus.api.NpcHandle;
import art.arcane.animus.api.Outcome;
import art.arcane.animus.core.extension.CapabilityRefusal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Actions waiting for a route to end. Each holds an invocation slot for as long as the NPC walks, so the
 * number of waits is bounded separately. Ticks and cancellation are observed on the platform owner.
 */
final class NavigationWaits implements AutoCloseable {
    private static final class Wait {
        final NpcHandle npc;
        final Capabilities.Cancellation token;
        final long deadline;
        final CompletableFuture<String> result = new CompletableFuture<>();

        Wait(NpcHandle npc, Capabilities.Cancellation token, long deadline) {
            this.npc = npc;
            this.token = token;
            this.deadline = deadline;
        }
    }

    private int maximum; // this monitor
    private final AtomicInteger pendingMaximum = new AtomicInteger();
    private final BehaviorPlatform platform;
    private final Set<Wait> active = new LinkedHashSet<>();
    private long tick;
    private boolean closed;

    NavigationWaits(int maximum, BehaviorPlatform platform) {
        if (maximum < 1) throw new IllegalArgumentException("Navigation wait capacity must be positive");
        this.maximum = maximum;
        this.platform = Objects.requireNonNull(platform);
    }

    /** New capacity, adopted on the next owner tick. A lower one keeps every active wait and refuses new ones until they end. */
    void maximum(int next) {
        if (next < 1) throw new IllegalArgumentException("Navigation wait capacity must be positive");
        pendingMaximum.set(next);
    }

    /** Owner only, before starting the route, so a refused wait leaves the NPC's current route alone. */
    synchronized void requireCapacity() {
        if (closed) throw stopping();
        if (active.size() >= maximum)
            throw new CapabilityRefusal(Outcome.Status.BUSY, "navigation-wait-capacity", "Too many behaviors are already waiting for a route; raise behaviors.actions.navigationWaits");
    }

    /** Owner only. The result is the given response on arrival, or the refusal describing how the route ended. */
    synchronized CompletionStage<String> watch(NpcHandle npc, Capabilities.Cancellation token, CompletionStage<Void> finished, String response, int timeoutTicks) {
        requireCapacity();
        Wait wait = new Wait(npc, token, tick + timeoutTicks);
        active.add(wait);
        finished.whenComplete((nothing, failure) -> settle(wait, response, failure));
        return wait.result;
    }

    private void settle(Wait wait, String response, Throwable failure) {
        synchronized (this) { active.remove(wait); }
        if (failure == null) { wait.result.complete(response); return; }
        wait.result.completeExceptionally(failure instanceof CompletionException wrapped && wrapped.getCause() != null ? wrapped.getCause() : failure);
    }

    /** Owner tick only. The result settles before the route stops, so the route's own cancellation report cannot replace it. A regionized port answers navigationStatus here from published state and stops the route on the NPC's owner. */
    void tick() {
        List<Wait> snapshot;
        long now;
        synchronized (this) {
            int next = pendingMaximum.getAndSet(0);
            if (next > 0) maximum = next;
            now = ++tick;
            if (closed || active.isEmpty()) return;
            snapshot = new ArrayList<>(active);
        }
        Optional<String> unavailable = platform.unavailable();
        for (Wait wait : snapshot) {
            if (wait.token.cancelled()) end(wait, new CapabilityRefusal(Outcome.Status.CANCELLED, "navigation-cancelled", "The behavior stopped waiting for this route"), true);
            else if (unavailable.isPresent()) end(wait, new CapabilityRefusal(Outcome.Status.UNAVAILABLE, "platform-unavailable", unavailable.get()), false);
            else if (now >= wait.deadline) end(wait, new CapabilityRefusal(Outcome.Status.FAILED, "navigation-timeout", "The route did not finish in time; last status: " + status(wait.npc)), true);
        }
    }

    private void end(Wait wait, CapabilityRefusal refusal, boolean stopRoute) {
        synchronized (this) { if (!active.remove(wait)) return; }
        wait.result.completeExceptionally(refusal);
        if (!stopRoute) return;
        try {
            platform.onNpcOwner(wait.npc, () -> {
                try { platform.stopNavigation(wait.npc); }
                catch (RuntimeException | LinkageError gone) { /* An NPC that left the runtime has no route to stop. */ }
            }, ignored -> { /* An owner that cannot accept the stop has no route left to stop. */ });
        } catch (RuntimeException | LinkageError gone) { /* An NPC that left the runtime has no route to stop. */ }
    }

    private String status(NpcHandle npc) {
        try {
            String status = platform.navigationStatus(npc);
            return status.length() > 512 ? status.substring(0, 512) : status;
        } catch (RuntimeException | LinkageError gone) { return "unavailable"; }
    }

    @Override public void close() {
        List<Wait> dropped;
        synchronized (this) {
            closed = true;
            dropped = new ArrayList<>(active);
            active.clear();
        }
        for (Wait wait : dropped) wait.result.completeExceptionally(stopping());
    }

    private static CapabilityRefusal stopping() {
        return new CapabilityRefusal(Outcome.Status.UNAVAILABLE, "actions-stopping", "Built-in actions are stopping");
    }
}
