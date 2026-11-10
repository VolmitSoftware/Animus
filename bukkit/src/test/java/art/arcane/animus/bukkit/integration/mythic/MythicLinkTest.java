package art.arcane.animus.bukkit.integration.mythic;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MythicLinkTest {
    private final List<String> closed = new ArrayList<>();

    @Test void aStepThatCannotLinkClosesWhatTheStepsBeforeItOpened() {
        NoClassDefFoundError missing = new NoClassDefFoundError("io/lumine/mythic/api/MissingType");
        NoClassDefFoundError thrown = assertThrows(NoClassDefFoundError.class, () -> MythicLink.opened((List<AutoCloseable> handles) -> {
            handles.add(() -> closed.add("templates"));
            handles.add(() -> closed.add("listeners"));
            throw missing;
        }));
        assertSame(missing, thrown);
        assertEquals(List.of("listeners", "templates"), closed);
    }

    @Test void startedStepsCloseInReverseWhenTheHandleCloses() throws Exception {
        AutoCloseable started = MythicLink.opened((List<AutoCloseable> handles) -> {
            handles.add(() -> closed.add("templates"));
            handles.add(() -> closed.add("ticks"));
        });
        assertEquals(List.of(), closed);
        started.close();
        assertEquals(List.of("ticks", "templates"), closed);
    }
}
