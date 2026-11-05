package art.arcane.animus.core.behavior.builtin;

import art.arcane.animus.api.NpcHandle;
import art.arcane.animus.api.Outcome;
import art.arcane.animus.core.behavior.builtin.BehaviorPlatform.ChoiceOption;
import art.arcane.animus.core.extension.CapabilityRefusal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BuiltinBudgetHotloadTest {
    private static final NpcHandle NPC = new NpcHandle(UUID.randomUUID());
    private static final List<ChoiceOption> OPTIONS = List.of(new ChoiceOption("yes", "Yes"));

    @Test void loweredQueueKeepsEveryAcceptedItemAndRefusesNewOnesUntilItDrains() {
        OwnerActionQueue queue = new OwnerActionQueue(new OwnerActionQueue.Limits(1, 1, 4), OwnerActionQueue.Router.INLINE);
        List<Integer> ran = new ArrayList<>();
        List<CompletionStage<Integer>> accepted = new ArrayList<>();
        for (int i = 0; i < 4; i++) { int index = i; accepted.add(queue.submit(new NpcHandle(UUID.randomUUID()), () -> false, () -> { ran.add(index); return index; })); }
        queue.limits(new OwnerActionQueue.Limits(2, 2, 2));
        queue.tick();
        assertEquals(List.of(0, 1), ran, "the new per-tick budget applies on the tick that adopts it");
        queue.limits(new OwnerActionQueue.Limits(1, 1, 1));
        queue.tick();
        assertEquals(List.of(0, 1, 2), ran);
        CapabilityRefusal busy = assertThrows(CapabilityRefusal.class, () -> queue.submit(NPC, () -> false, () -> 9));
        assertEquals(Outcome.Status.BUSY, busy.status());
        queue.tick();
        assertEquals(List.of(0, 1, 2, 3), ran, "nothing accepted before the cut was dropped");
        for (int i = 0; i < 4; i++) assertEquals(i, accepted.get(i).toCompletableFuture().join());
        assertNotNull(queue.submit(NPC, () -> false, () -> 5));
    }

    @Test void raisedQueueAcceptsMoreAfterTheNextTick() {
        OwnerActionQueue queue = new OwnerActionQueue(new OwnerActionQueue.Limits(1, 1, 1), OwnerActionQueue.Router.INLINE);
        queue.submit(NPC, () -> false, () -> 1);
        assertThrows(CapabilityRefusal.class, () -> queue.submit(NPC, () -> false, () -> 2));
        queue.limits(new OwnerActionQueue.Limits(1, 1, 3));
        queue.tick();
        for (int i = 2; i <= 4; i++) { int value = i; queue.submit(NPC, () -> false, () -> value); }
        assertThrows(CapabilityRefusal.class, () -> queue.submit(NPC, () -> false, () -> 5));
    }

    @Test void loweredNavigationWaitsKeepActiveWaitsAndRefuseNewOnes() {
        NavigationWaits waits = new NavigationWaits(3, new RecordingPlatform());
        CompletableFuture<Void> first = new CompletableFuture<>(), second = new CompletableFuture<>();
        CompletionStage<String> one = waits.watch(NPC, () -> false, first, "one", 1000);
        CompletionStage<String> two = waits.watch(NPC, () -> false, second, "two", 1000);
        waits.maximum(1);
        waits.tick();
        assertFalse(one.toCompletableFuture().isDone());
        assertFalse(two.toCompletableFuture().isDone());
        assertEquals(Outcome.Status.BUSY, assertThrows(CapabilityRefusal.class, waits::requireCapacity).status());
        first.complete(null);
        assertEquals("one", one.toCompletableFuture().join());
        assertThrows(CapabilityRefusal.class, waits::requireCapacity);
        second.complete(null);
        assertEquals("two", two.toCompletableFuture().join());
        waits.requireCapacity();
    }

    @Test void loweredChoiceCapacityKeepsOpenPromptsAndRefusesNewOnes() {
        List<DialogueChoice> published = new ArrayList<>();
        PendingChoices choices = new PendingChoices(4, published::add, System::nanoTime, failure -> { });
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        String token = choices.open(NPC, first, Optional.empty(), "a", OPTIONS, Duration.ofSeconds(60));
        choices.open(NPC, second, Optional.empty(), "b", OPTIONS, Duration.ofSeconds(60));
        choices.maximum(1);
        CapabilityRefusal busy = assertThrows(CapabilityRefusal.class, () -> choices.open(NPC, UUID.randomUUID(), Optional.empty(), "c", OPTIONS, Duration.ofSeconds(60)));
        assertEquals(Outcome.Status.BUSY, busy.status());
        assertEquals(PendingChoices.Selection.ACCEPTED, choices.select(first, token, "yes", npc -> true), "an open prompt still answers");
        assertThrows(CapabilityRefusal.class, () -> choices.open(NPC, UUID.randomUUID(), Optional.empty(), "c", OPTIONS, Duration.ofSeconds(60)));
        choices.maximum(3);
        assertNotNull(choices.open(NPC, UUID.randomUUID(), Optional.empty(), "c", OPTIONS, Duration.ofSeconds(60)));
    }
}
