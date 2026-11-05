package art.arcane.animus.core.command;

import art.arcane.animus.core.behavior.builtin.PendingChoices;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChoiceCommandTest {
    private static final String TOKEN = "b".repeat(32);
    private final List<String> messages = new ArrayList<>();
    private final UUID playerId = UUID.randomUUID();

    private CommandActor sender(boolean player) {
        return new CommandActor() {
            @Override public boolean permitted(String node) { throw new AssertionError("A choice click needs no permission"); }
            @Override public void tell(String message) { messages.add(message); }
            @Override public Optional<UUID> player() { return player ? Optional.of(playerId) : Optional.empty(); }
            @Override public boolean reply(Runnable action) { action.run(); return true; }
        };
    }

    @Test void choiceCommandPassesTheSendersOwnIdentity() {
        List<String> seen = new ArrayList<>();
        AnimusCommands.choice(sender(true), new String[]{"choice", TOKEN, "yes"}, (UUID player, String token, String option) -> { seen.add(player + ":" + token + ":" + option); return PendingChoices.Selection.ACCEPTED; });
        assertEquals(List.of(playerId + ":" + TOKEN + ":yes"), seen);
        assertTrue(messages.isEmpty());
    }

    @Test void rejectedChoiceTellsThePlayerNothingAboutWhy() {
        for (PendingChoices.Selection rejected : List.of(PendingChoices.Selection.UNKNOWN, PendingChoices.Selection.EXPIRED, PendingChoices.Selection.WRONG_PLAYER, PendingChoices.Selection.UNKNOWN_OPTION, PendingChoices.Selection.NPC_UNAVAILABLE)) {
            messages.clear();
            AnimusCommands.choice(sender(true), new String[]{"choice", TOKEN, "yes"}, (UUID player, String token, String option) -> rejected);
            assertEquals(List.of(AnimusCommands.NOT_AVAILABLE), messages);
        }
    }

    @Test void malformedChoiceNeverReachesSelection() {
        for (String[] arguments : List.of(new String[]{"choice"}, new String[]{"choice", TOKEN}, new String[]{"choice", TOKEN, "yes", "extra"},
                new String[]{"choice", "short", "yes"}, new String[]{"choice", TOKEN.toUpperCase(), "yes"}, new String[]{"choice", TOKEN, "Bad Option"}, new String[]{"choice", TOKEN, "x".repeat(33)})) {
            messages.clear();
            AnimusCommands.choice(sender(true), arguments, (UUID player, String token, String option) -> { throw new AssertionError("Malformed click reached selection"); });
            assertEquals(List.of(AnimusCommands.NOT_AVAILABLE), messages);
        }
    }

    @Test void consoleCannotAnswerAChoice() {
        AnimusCommands.choice(sender(false), new String[]{"choice", TOKEN, "yes"}, (UUID player, String token, String option) -> { throw new AssertionError("Console reached selection"); });
        assertEquals(List.of(AnimusCommands.NOT_AVAILABLE), messages);
    }
}
