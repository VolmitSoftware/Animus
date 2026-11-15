package art.arcane.animus.core.control;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SeatTogglesTest {
    @TempDir Path root;

    @Test void aToggleFlipsAndSurvivesARestart() throws Exception {
        Path file = root.resolve("state/seats/toggles.json");
        UUID alex = UUID.fromString("00000000-0000-0000-0000-00000000a1e7");
        List<String> log = new ArrayList<>();
        SeatToggles toggles = new SeatToggles(file, Runnable::run, log::add);
        assertTrue(toggles.allows(alex), "sitting starts on");
        assertFalse(toggles.set(alex, null));
        assertFalse(toggles.allows(alex));
        assertTrue(Files.readString(file).contains(alex.toString()));
        assertFalse(new SeatToggles(file, Runnable::run, log::add).allows(alex), "kept across a restart");
        assertTrue(toggles.set(alex, Boolean.TRUE));
        assertTrue(new SeatToggles(file, Runnable::run, log::add).allows(alex));
        assertEquals(List.of(), log);
    }
}
