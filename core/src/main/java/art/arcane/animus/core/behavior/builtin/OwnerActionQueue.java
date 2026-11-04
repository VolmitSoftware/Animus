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
import java.util.function.Supplier;

/** Bounded hand-off to the platform owner. Budgets defer work to a later tick; only a full queue refuses it. */
final class OwnerActionQueue implements AutoCloseable {
    record Limits(int perTick, int perNpc, int queued) {
        Limits {
            if (perTick < 1 || perNpc < 1 || perNpc > perTick || queued < 1)
                throw new IllegalArgumentException("Invalid owner action budgets");
        }
    }

    private final Limits limits;
    private final Set<Item<?>> pending = new LinkedHashSet<>();
    private boolean closed;

    OwnerActionQueue(Limits limits) { this.limits = Objects.requireNonNull(limits); }

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

    /** Owner only. Work and cancellation observations run outside the queue lock. */
    void tick() {
        List<Item<?>> snapshot;
        synchronized (this) {
            if (closed || pending.isEmpty()) return;
            snapshot = new ArrayList<>(pending);
        }
        int remaining = limits.perTick();
        Map<NpcHandle, Integer> used = new HashMap<>();
        for (Item<?> item : snapshot) {
            if (item.token.cancelled()) {
                if (remove(item)) item.result.completeExceptionally(new CapabilityRefusal(Outcome.Status.CANCELLED, "action-cancelled", "Action was cancelled before it reached the owner"));
                continue;
            }
            if (remaining == 0) break;
            int spent = used.getOrDefault(item.npc, 0);
            if (spent >= limits.perNpc()) continue;
            if (!remove(item)) continue;
            remaining--;
            used.put(item.npc, spent + 1);
            item.run();
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
    }
}
