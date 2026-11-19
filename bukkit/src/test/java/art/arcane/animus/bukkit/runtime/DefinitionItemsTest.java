package art.arcane.animus.bukkit.runtime;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** How a definition item's components reach the server's item parser, which Paper and Spigot read differently. */
class DefinitionItemsTest {
    private static final String LOADED = "[minecraft:charged_projectiles=[{id:\"minecraft:arrow\",count:1}]]";

    @Test void paperReadsTheComponentsAloneAndSpigotAfterTheItemId() {
        assertEquals(LOADED, DefinitionItems.arguments("minecraft:crossbow", LOADED, true), "Paper puts the item id in front itself");
        assertEquals("minecraft:crossbow" + LOADED, DefinitionItems.arguments("minecraft:crossbow", LOADED, false), "Spigot parses a whole give argument");
    }
}
