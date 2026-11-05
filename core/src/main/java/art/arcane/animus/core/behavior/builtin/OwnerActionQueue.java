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
import java.util.Optional;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Bounded hand-off to the platform owner. Budgets defer work to a later tick; only a full queue refuses it.
 * The router places each item where its NPC, its player or the server is owned, which on a single-owner server is the tick itself.
 */
final class OwnerActionQueue implements AutoCloseable {
    /** Accepted work runs exactly once, or {@code refused} runs if the owner later drops it. */
    @FunctionalInterface interface Router {
        Router INLINE = (npc, work, refused) -> { work.run(); return true; };
        boolean dispatch(NpcHandle npc, Runnable work, Consumer<String> refused);
        /** Places work where the player is owned. A single owner runs it inline. */
        default boolean player(UUID player, Runnable work, Consumer<String> refused) { work.run(); return true; }
        /** Places work on the global owner. A single owner runs it inline. */
        default boolean global(Runnable work, Consumer<String> refused) { work.run(); return true; }
    }
    /** Where one item runs: the NPC's owner, a player's owner, or the global owner. Per-owner budgets count by the same key. */
    record Route(NpcHandle npc, Optional<UUID> player, boolean global) {
        private static final Object GLOBAL = new Object();
        Route { Objects.requireNonNull(npc); Objects.requireNonNull(player); }
        static Route npc(NpcHandle npc) { return new Route(npc, Optional.empty(), false); }
        static Route player(NpcHandle npc, UUID player) { return new Route(npc, Optional.of(player), false); }
        static Route global(NpcHandle npc) { return new Route(npc, Optional.empty(), true); }
        Object budget() { return global ? GLOBAL : player.isPresent() ? player.get() : npc; }
    }
    record Limits(int perTick, int perNpc, int queued) {
        Limits {
            if (perTick < 1 || perNpc < 1 || perNpc > perTick || queued < 1)
                throw new IllegalArgumentException("Invalid owner action budgets");
        }
    }

    private final Limits limits;
    private final Router router;
    private final Set<Item<?>> pending = new LinkedHashSet<>();
    private boolean closed;

    OwnerActionQueue(Limits limits, Router router) { this.limits = Objects.requireNonNull(limits); this.router = Objects.requireNonNull(router); }

    <T> CompletionStage<T> submit(NpcHandle npc, Capabilities.Cancellation token, Supplier<T> work) { return submit(Route.npc(npc), token, work); }

    <T> CompletionStage<T> submit(Route route, Capabilities.Cancellation token, Supplier<T> work) {
        Item<T> item = new Item<>(Objects.requireNonNull(route), Objects.requireNonNull(token), Objects.requireNonNull(work));
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
        List<Item<?>> snapshot;
        synchronized (this) {
            if (closed || pending.isEmpty()) return;
            snapshot = new ArrayList<>(pending);
        }
        int remaining = limits.perTick();
        Map<Object, Integer> used = new HashMap<>();
        for (Item<?> item : snapshot) {
            if (item.token.cancelled()) {
                if (remove(item)) item.result.completeExceptionally(new CapabilityRefusal(Outcome.Status.CANCELLED, "action-cancelled", "Action was cancelled before it reached the owner"));
                continue;
            }
            if (remaining == 0) break;
            Object budget = item.route.budget();
            int spent = used.getOrDefault(budget, 0);
            if (spent >= limits.perNpc()) continue;
            if (!remove(item)) continue;
            remaining--;
            used.put(budget, spent + 1);
            boolean accepted;
            try { accepted = dispatch(item); }
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

    private boolean dispatch(Item<?> item) {
        Route route = item.route;
        if (route.global()) return router.global(item::run, item::refuse);
        if (route.player().isPresent()) return router.player(route.player().get(), item::run, item::refuse);
        return router.dispatch(route.npc(), item::run, item::refuse);
    }

    private synchronized boolean remove(Item<?> item) { return pending.remove(item); }
    private static CapabilityRefusal stopped() {
        return new CapabilityRefusal(Outcome.Status.UNAVAILABLE, "actions-stopping", "Built-in actions are stopping");
    }

    private static final class Item<T> {
        final Route route;
        final Capabilities.Cancellation token;
        final Supplier<T> work;
        final CompletableFuture<T> result = new CompletableFuture<>();

        Item(Route route, Capabilities.Cancellation token, Supplier<T> work) {
            this.route = route;
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
