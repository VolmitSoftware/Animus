package art.arcane.animus.core.behavior.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A player made operator for one command is never left one: not after a failure, a quit, a stop or a crash. */
class TemporaryOperatorsTest {
    @TempDir Path root;
    private final UUID alex = UUID.randomUUID(), sam = UUID.randomUUID();
    /** The server's operator list, and every change made to it in order. */
    private final Set<UUID> ops = new HashSet<>();
    private final List<String> changes = new ArrayList<>();
    private final TemporaryOperators.Operators server = new TemporaryOperators.Operators() {
        @Override public boolean operator(UUID player) { return ops.contains(player); }
        @Override public void operator(UUID player, boolean operator) {
            changes.add((operator ? "op " : "deop ") + (player.equals(alex) ? "alex" : "sam"));
            if (operator) ops.add(player); else ops.remove(player);
        }
    };

    private Path file() { return root.resolve("state/temporary-operators.json"); }

    @Test void thePlayerIsAnOperatorOnlyWhileTheCommandRuns() throws Exception {
        TemporaryOperators operators = new TemporaryOperators(file(), server);
        boolean during = operators.perform(alex, () -> {
            try { assertTrue(Files.readString(file()).contains(alex.toString()), "written down before the player is made an operator"); }
            catch (java.io.IOException unread) { throw new AssertionError(unread); }
            return ops.contains(alex);
        });
        assertTrue(during);
        assertFalse(ops.contains(alex));
        assertEquals(List.of("op alex", "deop alex"), changes);
        assertFalse(Files.readString(file()).contains(alex.toString()), "and crossed off once they are not one");
    }

    @Test void aFailingCommandStillTakesItBack() throws Exception {
        TemporaryOperators operators = new TemporaryOperators(file(), server);
        assertThrows(IllegalStateException.class, () -> operators.perform(alex, () -> { throw new IllegalStateException("command broke"); }));
        assertFalse(ops.contains(alex));
    }

    @Test void anOperatorAlreadyIsLeftAlone() throws Exception {
        ops.add(alex);
        new TemporaryOperators(file(), server).perform(alex, () -> true);
        assertTrue(ops.contains(alex));
        assertEquals(List.of(), changes, "nothing is granted or taken from a real operator");
    }

    @Test void quittingOrStoppingDuringTheCommandTakesItBack() throws Exception {
        TemporaryOperators operators = new TemporaryOperators(file(), server);
        operators.perform(alex, () -> { operators.quit(alex); return absent(ops.contains(alex), "the quit took it"); });
        operators.perform(sam, () -> { operators.close(); return absent(ops.contains(sam), "the stop took it"); });
        assertEquals(List.of("op alex", "deop alex", "op sam", "deop sam"), changes, "each is taken back once");
    }

    @Test void aServerThatRefusesTheDeopKeepsThePlayerForTheNextStart() throws Exception {
        TemporaryOperators.Operators stubborn = new TemporaryOperators.Operators() {
            @Override public boolean operator(UUID player) { return ops.contains(player); }
            @Override public void operator(UUID player, boolean operator) {
                if (!operator) throw new IllegalStateException("not this thread");
                ops.add(player);
            }
        };
        TemporaryOperators operators = new TemporaryOperators(file(), stubborn);
        operators.perform(alex, () -> true);
        assertTrue(Files.readString(file()).contains(alex.toString()), "still listed, so the next start takes it back");
        assertEquals(1, new TemporaryOperators(file(), server).recover());
        assertFalse(ops.contains(alex));
    }

    @Test void aCrashIsUndoneAtTheNextStart() throws Exception {
        TemporaryOperators crashed = new TemporaryOperators(file(), server);
        crashed.perform(alex, () -> {
            // The server dies here: no finally, no quit, no disable. Only the file survives, with the operator list.
            TemporaryOperators restarted = new TemporaryOperators(file(), server);
            try { assertEquals(1, restarted.recover()); }
            catch (java.io.IOException failed) { throw new AssertionError(failed); }
            return null;
        });
        assertFalse(ops.contains(alex));
        assertEquals(0, new TemporaryOperators(file(), server).recover(), "recovery empties the list");
    }

    private static Void absent(boolean value, String message) {
        assertFalse(value, message);
        return null;
    }
}
