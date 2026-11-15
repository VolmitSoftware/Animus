package art.arcane.animus.mod;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The worlds the editor is told about are every name the server resolves, so a definition written for Bukkit finds its terrain. */
class ModWorldKeysTest {
    @Test void theEditorHearsEveryNameAWorldAnswersTo() {
        List<String> names = ModWorldKeys.names(Set.of("minecraft:the_end", "minecraft:overworld", "example:mining", "minecraft:the_nether"), "world");
        assertEquals(List.of("example:mining", "minecraft:overworld", "overworld", "world", "minecraft:the_end", "the_end", "world_the_end",
                "minecraft:the_nether", "the_nether", "world_nether"), names);
    }
}
