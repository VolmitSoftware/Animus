package art.arcane.animus.bukkit;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BukkitServerBehaviorsTest {
    @Test void eventTextLosesColourCodesAndBlankTextIsAbsent() {
        assertEquals(Optional.of("Alex fell from a high place"), BukkitServerBehaviors.plain("§eAlex §rfell from a high place"));
        assertEquals(Optional.of("hello"), BukkitServerBehaviors.plain("hello"));
        assertEquals(Optional.empty(), BukkitServerBehaviors.plain(null));
        assertEquals(Optional.empty(), BukkitServerBehaviors.plain("§c "));
    }
}
