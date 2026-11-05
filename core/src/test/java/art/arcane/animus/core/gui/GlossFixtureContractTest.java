package art.arcane.animus.core.gui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Gloss's own default documents, copied unchanged into schema/gloss/fixtures with the pinned schemas, run through the
 * Animus parsers. Each loads, or is refused only for a feature Animus names as unavailable.
 */
class GlossFixtureContractTest {
    private static String fixture(String name) throws IOException {
        return Files.readString(Path.of(System.getProperty("animusSchemaDirectory"), "gloss", "fixtures", name));
    }

    @Test void theHologramBaselineLoads() throws IOException {
        assertEquals(List.of(), HologramDocument.parse("gloss:baseline", fixture("hologram-baseline.json")).problems());
    }

    @Test void theInventoryExampleLoads() throws IOException {
        assertEquals(List.of(), GuiDocument.parse("gloss:example", fixture("inventory-example.json")).problems());
    }

    @Test void theDefaultMenuLoads() throws IOException {
        assertEquals(List.of(), MenuDocument.parse("gloss:default", fixture("menu-default.json")).problems());
    }

    @Test void theDefaultAnimationsLoad() throws IOException {
        for (String name : List.of("flash", "marquee", "rainbow", "typewriter"))
            assertEquals(List.of(), TextAnimation.parse("gloss:" + name, fixture("animation-" + name + ".json")).problems(), name);
    }
}
