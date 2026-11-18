package art.arcane.animus.core.command;

import art.arcane.animus.core.behavior.builtin.PendingChoices;
import art.arcane.animus.core.text.RichText;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChoiceCommandTest {
    private static final String TOKEN = "b".repeat(32), OTHER = "c".repeat(32);
    private final List<String> messages = new ArrayList<>();
    private final UUID playerId = UUID.randomUUID();
    private final Map<String, List<String>> waiting = new LinkedHashMap<>();
    private final List<String> selected = new ArrayList<>();
    private PendingChoices.Selection outcome = PendingChoices.Selection.ACCEPTED;

    private final ChoiceCommands.Questions questions = new ChoiceCommands.Questions() {
        @Override public Map<String, List<String>> waiting(UUID player) { return player.equals(playerId) ? new LinkedHashMap<>(waiting) : Map.of(); }
        @Override public PendingChoices.Selection select(UUID player, String token, String option) {
            selected.add(player + ":" + token + ":" + option);
            return outcome;
        }
    };

    private CommandActor sender(boolean player) {
        return new CommandActor() {
            @Override public boolean permitted(String node) { throw new AssertionError("A choice needs no permission"); }
            @Override public void tell(String message) { messages.add(message); }
            @Override public Optional<UUID> player() { return player ? Optional.of(playerId) : Optional.empty(); }
            @Override public boolean reply(Runnable action) { action.run(); return true; }
        };
    }

    private List<String> replies() { return messages.stream().map(RichText::plain).map((String line) -> line.substring("[Animus]: ".length())).toList(); }

    @Test void anAnswerReachesSelectionWithTheSendersOwnIdentity() {
        waiting.put(TOKEN, List.of("yes", "no"));
        ChoiceCommands.choose(sender(true), TOKEN, "yes", questions);
        assertEquals(List.of(playerId + ":" + TOKEN + ":yes"), selected);
        assertTrue(messages.isEmpty(), "the NPC answers, not Animus");
    }

    @Test void aTypedAnswerMatchesWhateverItsCase() {
        waiting.put(TOKEN, List.of("loaf", "leave"));
        ChoiceCommands.choose(sender(true), TOKEN, "Loaf", questions);
        assertEquals(List.of(playerId + ":" + TOKEN + ":loaf"), selected);
        assertTrue(messages.isEmpty());
    }

    @Test void withNothingWaitingItSaysSoInOneSentence() {
        ChoiceCommands.choose(sender(true), TOKEN, "yes", questions);
        assertEquals(List.of("No question is waiting for you"), replies());
        assertTrue(selected.isEmpty());
    }

    @Test void aConsoleHasNoQuestionWaiting() {
        waiting.put(TOKEN, List.of("yes"));
        ChoiceCommands.choose(sender(false), TOKEN, "yes", questions);
        assertEquals(List.of("No question is waiting for you"), replies());
        assertTrue(selected.isEmpty());
    }

    @Test void aWrongTokenReadsAsAStaleClickWhateverTheReason() {
        waiting.put(TOKEN, List.of("yes", "no"));
        for (String token : List.of(OTHER, "short", TOKEN.toUpperCase(), "")) {
            messages.clear();
            ChoiceCommands.choose(sender(true), token, "yes", questions);
            assertEquals(List.of(ChoiceCommands.NOT_AVAILABLE), replies(), token);
        }
        assertTrue(selected.isEmpty(), "a token that is not one of the player's never reaches selection");
    }

    @Test void anOptionThatDoesNotExistNamesTheAnswers() {
        waiting.put(TOKEN, List.of("loaf", "rye", "leave"));
        ChoiceCommands.choose(sender(true), TOKEN, "bagel", questions);
        assertEquals(List.of("\"bagel\" is not an answer here; pick loaf, rye or leave"), replies());
        assertTrue(selected.isEmpty(), "the question stays open for a real answer");
    }

    @Test void aQuestionThatExpiresBeforeTheAnswerArrivesIsNoLongerAvailable() {
        waiting.put(TOKEN, List.of("yes"));
        for (PendingChoices.Selection refused : List.of(PendingChoices.Selection.EXPIRED, PendingChoices.Selection.UNKNOWN, PendingChoices.Selection.NPC_UNAVAILABLE)) {
            messages.clear();
            outcome = refused;
            ChoiceCommands.choose(sender(true), TOKEN, "yes", questions);
            assertEquals(List.of(ChoiceCommands.NOT_AVAILABLE), replies(), refused.name());
        }
    }

    @Test void completionOffersTheWaitingTokensThenThatQuestionsAnswers() {
        assertEquals(List.of(), ChoiceCommands.tokens(sender(true), questions), "nothing waits");
        waiting.put(TOKEN, List.of("loaf", "leave"));
        waiting.put(OTHER, List.of("yes", "no"));
        assertEquals(List.of(TOKEN, OTHER), ChoiceCommands.tokens(sender(true), questions));
        assertEquals(List.of("loaf", "leave"), ChoiceCommands.answers(sender(true), questions, TOKEN));
        assertEquals(List.of("yes", "no"), ChoiceCommands.answers(sender(true), questions, OTHER));
        assertEquals(List.of(), ChoiceCommands.answers(sender(true), questions, "0".repeat(32)), "a wrong token has no answers");
        assertEquals(List.of(), ChoiceCommands.tokens(sender(false), questions), "the console has none");
    }
}
