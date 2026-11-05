package art.arcane.animus.core.behavior.builtin;

import art.arcane.animus.api.Capabilities;
import art.arcane.animus.api.NpcHandle;
import art.arcane.animus.api.Outcome;
import art.arcane.animus.core.extension.CapabilityRefusal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Bounded hand-off to the platform owner. Budgets defer work to a later tick; only a full queue refuses it.
 * The router places each item where its NPC is owned, which on a single-owner server is the tick itself.
 */
final class OwnerActionQueue implements AutoCloseable {
    /** Accepted work runs exactly once, or {@code refused} runs if the owner later drops it. */
    @FunctionalInterface interface Router {
        Router INLINE = (npc, work, refused) -> { work.run(); return true; };
        boolean dispatch(NpcHandle npc, Runnable work, Consumer<String> refused);
    }
    record Limits(int perTick, int perNpc, int queued) {
        Limits {
            if (perTick < 1 || perNpc < 1 || perNpc > perTick || queued < 1)
                throw new IllegalArgumentException("Invalid owner action budgets");
        }
    }

    private volatile Limits limits; // written by the dispatching owner only
    private final AtomicReference<Limits> pendingLimits = new AtomicReference<>();
    private final Router router;
    private final Set<Item<?>> pending = new LinkedHashSet<>();
    private boolean closed;

    OwnerActionQueue(Limits limits, Router router) { this.limits = Objects.requireNonNull(limits); this.router = Objects.requireNonNull(router); }

    /** New budgets, adopted on the next tick. A lowered queue keeps every accepted item and refuses new ones until it drains. */
    void limits(Limits next) { pendingLimits.set(Objects.requireNonNull(next)); }

    <T> CompletionStage<T> submit(NpcHandle npc, Capabilities.Cancellation token, Supplier<T> work) {
        Item<T> item = new Item<>(Objects.requireNonNull(npc), Objects.requireNonNull(token), Objects.requireNonNull(work));
        synchronized (this) {
            if (closed) throw stopped();
            if (pending.size() >= limits.queued())
                throw new CapabilityRefusal(Outcome.Status.BUSY, "action-queue-full", "Built-in action queue is full");
            pending.add(item);
        }
        return item.result;
    }

    /** Dispatching owner only. Work and cancellation observations run outside the queue lock. */
    void tick() {
        Limits next = pendingLimits.getAndSet(null);
        if (next != null) limits = next;
        Limits budget = limits;
        List<Item<?>> snapshot;
        synchronized (this) {
            if (closed || pending.isEmpty()) return;
            snapshot = new ArrayList<>(pending);
        }
        int remaining = budget.perTick();
        Map<NpcHandle, Integer> used = new HashMap<>();
        for (Item<?> item : snapshot) {
            if (item.token.cancelled()) {
                if (remove(item)) item.result.completeExceptionally(new CapabilityRefusal(Outcome.Status.CANCELLED, "action-cancelled", "Action was cancelled before it reached the owner"));
                continue;
            }
            if (remaining == 0) break;
            int spent = used.getOrDefault(item.npc, 0);
            if (spent >= budget.perNpc()) continue;
            if (!remove(item)) continue;
            remaining--;
            used.put(item.npc, spent + 1);
            boolean accepted;
            try { accepted = router.dispatch(item.npc, item::run, item::refuse); }
            catch (RuntimeException | LinkageError failure) { item.result.completeExceptionally(failure); continue; }
            if (!accepted) item.refuse("The NPC's owner cannot accept work now");
        }
    }

    @Override public void close() {
        List<Item<?>> dropped;
        synchronized (this) {
            closed = true;
            dropped = new ArrayList<>(pending);
            pending.clear();
        }
        for (Item<?> item : dropped) item.result.completeExceptionally(stopped());
    }

    private synchronized boolean remove(Item<?> item) { return pending.remove(item); }
    private static CapabilityRefusal stopped() {
        return new CapabilityRefusal(Outcome.Status.UNAVAILABLE, "actions-stopping", "Built-in actions are stopping");
    }

    private static final class Item<T> {
        final NpcHandle npc;
        final Capabilities.Cancellation token;
        final Supplier<T> work;
        final CompletableFuture<T> result = new CompletableFuture<>();

        Item(NpcHandle npc, Capabilities.Cancellation token, Supplier<T> work) {
            this.npc = npc;
            this.token = token;
            this.work = work;
        }

        void run() {
            try { result.complete(work.get()); }
            catch (RuntimeException | LinkageError | AssertionError failure) { result.completeExceptionally(failure); }
        }

        void refuse(String reason) {
            result.completeExceptionally(new CapabilityRefusal(Outcome.Status.UNAVAILABLE, "npc-owner-unavailable", reason));
        }
    }
}
