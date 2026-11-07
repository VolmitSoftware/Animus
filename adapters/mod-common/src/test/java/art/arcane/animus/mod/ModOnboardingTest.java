package art.arcane.animus.mod;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModOnboardingTest {
    @Test void aDelayedLineWaitsItsTicksAndRunsOnce() {
        ModOnboarding.Delays delays = new ModOnboarding.Delays();
        List<String> ran = new ArrayList<>();
        delays.add(() -> ran.add("hint"), 3);
        delays.add(() -> ran.add("soon"), 1);
        delays.tick();
        assertEquals(List.of("soon"), ran);
        delays.tick();
        assertEquals(List.of("soon"), ran);
        delays.tick();
        assertEquals(List.of("soon", "hint"), ran);
        delays.tick();
        assertEquals(List.of("soon", "hint"), ran, "each line runs once");
    }
}
