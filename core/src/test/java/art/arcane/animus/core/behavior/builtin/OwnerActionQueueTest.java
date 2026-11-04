package art.arcane.animus.core.behavior.builtin;

import art.arcane.animus.api.NpcHandle;
import art.arcane.animus.api.Outcome;
import art.arcane.animus.core.extension.CapabilityRefusal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OwnerActionQueueTest {
    private static final NpcHandle FIRST = new NpcHandle(UUID.randomUUID()), SECOND = new NpcHandle(UUID.randomUUID());

    private static CapabilityRefusal refusal(CompletionStage<?> stage) {
        CompletableFuture<?> future = stage.toCompletableFuture();
        assertTrue(future.isCompletedExceptionally());
        Throwable failure = future.handle((value, thrown) -> thrown).join();
        return assertInstanceOf(CapabilityRefusal.class, failure);
    }

    @Test void workRunsOnlyWhenTheOwnerTicks() {
        OwnerActionQueue queue = new OwnerActionQueue(new OwnerActionQueue.Limits(4, 4, 8), OwnerActionQueue.Router.INLINE);
        List<String> ran = new ArrayList<>();
        CompletionStage<String> result = queue.submit(FIRST, () -> false, () -> { ran.add("work"); return "done"; });
        assertTrue(ran.isEmpty());
        assertFalse(result.toCompletableFuture().isDone());
        queue.tick();
        assertEquals(List.of("work"), ran);
        assertEquals("done", result.toCompletableFuture().join());
    }

    @Test void globalBudgetBoundsWorkPerTickAndPreservesOrder() {
        OwnerActionQueue queue = new OwnerActionQueue(new OwnerActionQueue.Limits(2, 2, 8), OwnerActionQueue.Router.INLINE);
        List<Integer> ran = new ArrayList<>();
        for (int i = 0; i < 5; i++) { int index = i; queue.submit(new NpcHandle(UUID.randomUUID()), () -> false, () -> { ran.add(index); return index; }); }
        queue.tick();
        assertEquals(List.of(0, 1), ran);
        queue.tick();
        assertEquals(List.of(0, 1, 2, 3), ran);
        queue.tick();
        assertEquals(List.of(0, 1, 2, 3, 4), ran);
    }

    @Test void perNpcBudgetDefersOnlyThatNpc() {
        OwnerActionQueue queue = new OwnerActionQueue(new OwnerActionQueue.Limits(8, 1, 8), OwnerActionQueue.Router.INLINE);
        List<String> ran = new ArrayList<>();
        for (String name : List.of("a1", "a2", "a3")) queue.submit(FIRST, () -> false, () -> { ran.add(name); return name; });
        queue.submit(SECOND, () -> false, () -> { ran.add("b1"); return "b1"; });
        queue.tick();
        assertEquals(List.of("a1", "b1"), ran);
        queue.tick();
        assertEquals(List.of("a1", "b1", "a2"), ran);
        queue.tick();
        assertEquals(List.of("a1", "b1", "a2", "a3"), ran);
    }

    @Test void fullQueueRejectsAsBusyWithoutRunning() {
        OwnerActionQueue queue = new OwnerActionQueue(new OwnerActionQueue.Limits(1, 1, 1), OwnerActionQueue.Router.INLINE);
        queue.submit(FIRST, () -> false, () -> "kept");
        AtomicBoolean ran = new AtomicBoolean();
        CapabilityRefusal refused = assertThrows(CapabilityRefusal.class, () -> queue.submit(FIRST, () -> false, () -> { ran.set(true); return "dropped"; }));
        assertEquals(Outcome.Status.BUSY, refused.status());
        queue.tick();
        assertFalse(ran.get());
    }

    @Test void cancelledWorkIsDroppedBeforeItTouchesTheOwnerAndCostsNoBudget() {
        OwnerActionQueue queue = new OwnerActionQueue(new OwnerActionQueue.Limits(1, 1, 4), OwnerActionQueue.Router.INLINE);
        AtomicBoolean revoked = new AtomicBoolean(), ran = new AtomicBoolean(), other = new AtomicBoolean();
        CompletionStage<String> cancelled = queue.submit(FIRST, revoked::get, () -> { ran.set(true); return "no"; });
        queue.submit(FIRST, () -> false, () -> { other.set(true); return "yes"; });
        revoked.set(true);
        queue.tick();
        assertFalse(ran.get());
        assertTrue(other.get());
        assertEquals(Outcome.Status.CANCELLED, refusal(cancelled).status());
    }

    @Test void workFailureFailsOnlyItsOwnStage() {
        OwnerActionQueue queue = new OwnerActionQueue(new OwnerActionQueue.Limits(4, 4, 4), OwnerActionQueue.Router.INLINE);
        CompletionStage<String> failed = queue.submit(FIRST, () -> false, () -> { throw new CapabilityRefusal(Outcome.Status.UNAVAILABLE, "npc-inactive", "NPC is not active"); });
        CompletionStage<String> fine = queue.submit(SECOND, () -> false, () -> "fine");
        queue.tick();
        assertEquals("npc-inactive", refusal(failed).diagnostics().getFirst().code());
        assertEquals("fine", fine.toCompletableFuture().join());
    }

    @Test void closeRefusesPendingAndNewWork() {
        OwnerActionQueue queue = new OwnerActionQueue(new OwnerActionQueue.Limits(4, 4, 4), OwnerActionQueue.Router.INLINE);
        AtomicBoolean ran = new AtomicBoolean();
        CompletionStage<String> pending = queue.submit(FIRST, () -> false, () -> { ran.set(true); return "no"; });
        queue.close();
        queue.tick();
        assertFalse(ran.get());
        assertEquals(Outcome.Status.UNAVAILABLE, refusal(pending).status());
        assertThrows(CapabilityRefusal.class, () -> queue.submit(FIRST, () -> false, () -> "late"));
    }

    @Test void workRunsWhereTheRouterPlacesItForThatNpc() {
        List<Runnable> owned = new ArrayList<>();
        List<NpcHandle> routed = new ArrayList<>();
        OwnerActionQueue queue = new OwnerActionQueue(new OwnerActionQueue.Limits(4, 4, 4), (npc, work, refused) -> { routed.add(npc); owned.add(work); return true; });
        AtomicBoolean ran = new AtomicBoolean();
        CompletionStage<String> result = queue.submit(FIRST, () -> false, () -> { ran.set(true); return "owned"; });
        queue.tick();
        assertEquals(List.of(FIRST), routed);
        assertFalse(ran.get(), "routing alone must not run the work on the dispatching thread");
        assertFalse(result.toCompletableFuture().isDone());
        owned.getFirst().run();
        assertEquals("owned", result.toCompletableFuture().join());
    }

    @Test void routerRefusalFailsTheWorkAsUnavailableWithoutRunningIt() {
        OwnerActionQueue queue = new OwnerActionQueue(new OwnerActionQueue.Limits(4, 4, 4), (npc, work, refused) -> false);
        AtomicBoolean ran = new AtomicBoolean();
        CompletionStage<String> result = queue.submit(FIRST, () -> false, () -> { ran.set(true); return "no"; });
        queue.tick();
        assertFalse(ran.get());
        CapabilityRefusal refused = refusal(result);
        assertEquals(Outcome.Status.UNAVAILABLE, refused.status());
        assertEquals("npc-owner-unavailable", refused.diagnostics().getFirst().code());
    }

    @Test void ownerThatDropsAcceptedWorkReportsItsReason() {
        List<java.util.function.Consumer<String>> refusals = new ArrayList<>();
        OwnerActionQueue queue = new OwnerActionQueue(new OwnerActionQueue.Limits(4, 4, 4), (npc, work, refused) -> { refusals.add(refused); return true; });
        CompletionStage<String> result = queue.submit(FIRST, () -> false, () -> "never");
        queue.tick();
        refusals.getFirst().accept("NPC went dormant: its chunk unloaded");
        CapabilityRefusal refused = refusal(result);
        assertEquals(Outcome.Status.UNAVAILABLE, refused.status());
        assertTrue(refused.getMessage().contains("chunk unloaded"), refused.getMessage());
    }

    @Test void limitsAreBounded() {
        assertThrows(IllegalArgumentException.class, () -> new OwnerActionQueue.Limits(0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new OwnerActionQueue.Limits(1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new OwnerActionQueue.Limits(1, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new OwnerActionQueue.Limits(1, 2, 1));
    }
}
