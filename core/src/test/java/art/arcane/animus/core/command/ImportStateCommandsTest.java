package art.arcane.animus.core.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.migration.DenizenImporter;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The commands an import prints for the operator to type must be real commands of the tree. */
class ImportStateCommandsTest {
    @Test void theDenizenStateConflictNamesCommandsThatParse() {
        List<String> commands = DenizenImporter.stateDeleteCommands();
        assertEquals(List.of("/animus behaviors state delete behavior animus:flags/denizen", "/animus behaviors state delete behavior animus:quests/denizen"), commands);
        String message = DenizenImporter.stateConflict("state/behavior-state/global/animus/flags/denizen.json");
        AnimusDirector real = new AnimusDirector(new AnimusCommandTree(null), (String parameter, CommandActor actor) -> List.of());
        for (String command : commands) {
            assertTrue(message.contains(command), message);
            AnimusDirectorTest.Actor admin = new AnimusDirectorTest.Actor(false, AnimusDirector.ADMIN);
            String[] words = command.substring("/animus ".length()).split(" ");
            real.execute(admin, words);
            String text = admin.text();
            assertFalse(text.contains("Unknown command") || text.contains("Unexpected \"") || text.contains("Missing argument") || text.contains("Usage:") && !text.contains("Failed to execute"),
                    Arrays.toString(words) + " -> " + text);
        }
    }
}
