package art.arcane.animus.core.behavior.builtin;

import art.arcane.animus.api.NpcHandle;
import art.arcane.animus.api.Outcome;
import art.arcane.animus.core.behavior.builtin.BehaviorPlatform.ChoiceOption;
import art.arcane.animus.core.extension.CapabilityRefusal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PendingChoicesTest {
    private static final NpcHandle NPC = new NpcHandle(UUID.randomUUID());
    private static final UUID PLAYER = UUID.randomUUID(), STRANGER = UUID.randomUUID();
    private static final List<ChoiceOption> OPTIONS = List.of(new ChoiceOption("yes", "Yes"), new ChoiceOption("no", "No"));
    private final AtomicLong clock = new AtomicLong();
    private final List<DialogueChoice> published = new ArrayList<>();
    private final List<Throwable> sinkFailures = new ArrayList<>();
    private final PendingChoices choices = new PendingChoices(4, published::add, clock::get, sinkFailures::add);

    private String open(UUID player) {
        return choices.open(NPC, player, Optional.of("town:greeter"), "greeting", OPTIONS, Duration.ofSeconds(10));
    }

    @Test void acceptedSelectionPublishesExactlyOnce() {
        String token = open(PLAYER);
        assertEquals(PendingChoices.Selection.ACCEPTED, choices.select(PLAYER, token, "yes", npc -> true));
        assertEquals(List.of(new DialogueChoice(NPC, PLAYER, Optional.of("town:greeter"), "greeting", "yes")), published);
        assertEquals(PendingChoices.Selection.UNKNOWN, choices.select(PLAYER, token, "yes", npc -> true));
        assertEquals(1, published.size());
    }

    @Test void forgedTokenIsRejected() {
        open(PLAYER);
        assertEquals(PendingChoices.Selection.UNKNOWN, choices.select(PLAYER, "0".repeat(32), "yes", npc -> true));
        assertTrue(published.isEmpty());
    }

    @Test void anotherPlayersClickDoesNotConsumeThePrompt() {
        String token = open(PLAYER);
        assertEquals(PendingChoices.Selection.WRONG_PLAYER, choices.select(STRANGER, token, "yes", npc -> true));
        assertTrue(published.isEmpty());
        assertEquals(PendingChoices.Selection.ACCEPTED, choices.select(PLAYER, token, "no", npc -> true));
    }

    @Test void unknownOptionIsRejectedAndThePromptSurvives() {
        String token = open(PLAYER);
        assertEquals(PendingChoices.Selection.UNKNOWN_OPTION, choices.select(PLAYER, token, "maybe", npc -> true));
        assertEquals(PendingChoices.Selection.ACCEPTED, choices.select(PLAYER, token, "yes", npc -> true));
    }

    @Test void promptExpiresAtItsBoundedLifetime() {
        String token = open(PLAYER);
        clock.addAndGet(Duration.ofSeconds(10).toNanos());
        assertEquals(PendingChoices.Selection.EXPIRED, choices.select(PLAYER, token, "yes", npc -> true));
        assertEquals(PendingChoices.Selection.UNKNOWN, choices.select(PLAYER, token, "yes", npc -> true));
        assertTrue(published.isEmpty());
    }

    @Test void newPromptFromTheSameNpcMakesTheOlderClickStale() {
        String older = open(PLAYER);
        String newer = open(PLAYER);
        assertNotEquals(older, newer);
        assertEquals(PendingChoices.Selection.UNKNOWN, choices.select(PLAYER, older, "yes", npc -> true));
        assertEquals(PendingChoices.Selection.ACCEPTED, choices.select(PLAYER, newer, "yes", npc -> true));
    }

    @Test void npcValidationRejectsWithoutPublishing() {
        String token = open(PLAYER);
        assertEquals(PendingChoices.Selection.NPC_UNAVAILABLE, choices.select(PLAYER, token, "yes", npc -> false));
        assertTrue(published.isEmpty());
        assertEquals(PendingChoices.Selection.ACCEPTED, choices.select(PLAYER, token, "yes", npc -> true));
    }

    @Test void capacityIsBoundedAndExpiredEntriesReleaseIt() {
        for (int i = 0; i < 4; i++) open(UUID.randomUUID());
        CapabilityRefusal refused = assertThrows(CapabilityRefusal.class, () -> open(UUID.randomUUID()));
        assertEquals(Outcome.Status.BUSY, refused.status());
        clock.addAndGet(Duration.ofSeconds(10).toNanos());
        for (int i = 0; i < 4; i++) open(UUID.randomUUID());
        assertThrows(CapabilityRefusal.class, () -> open(UUID.randomUUID()));
    }

    @Test void tokensAreUnguessableAndClickSafe() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 64; i++) {
            String token = open(PLAYER);
            assertTrue(token.matches("[a-f0-9]{32}"), token);
            assertTrue(seen.add(token));
        }
    }

    @Test void brokenSinkDoesNotEscapeTheCommandOwner() {
        PendingChoices broken = new PendingChoices(4, choice -> { throw new IllegalStateException("listener"); }, clock::get, sinkFailures::add);
        String token = broken.open(NPC, PLAYER, Optional.empty(), "greeting", OPTIONS, Duration.ofSeconds(1));
        assertEquals(PendingChoices.Selection.ACCEPTED, broken.select(PLAYER, token, "yes", npc -> true));
        assertInstanceOf(IllegalStateException.class, sinkFailures.getFirst());
    }
}
