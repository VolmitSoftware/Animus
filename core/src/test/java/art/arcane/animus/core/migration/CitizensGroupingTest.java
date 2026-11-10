package art.arcane.animus.core.migration;

import art.arcane.animus.core.control.RideSettings;
import art.arcane.animus.core.shop.ShopBinding;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CitizensGroupingTest {
    @TempDir Path root;
    private static final String SAVES = """
            npc:
              '1':
                name: Baker
                traits:
                  type: PLAYER
                  location: {world: World Nether, x: 1, y: 64, z: 2}
                  trader: {guiName: bakery}
              '2':
                name: Horse
                traits:
                  type: HORSE
                  location: {world: old, x: 3, y: 64, z: 4}
                  controllable: {enabled: true}
              '3':
                name: Lost
                traits:
                  type: PLAYER
              '4':
                name: Parked
                traits:
                  type: HORSE
                  location: {world: old, x: 3, y: 64, z: 4}
                  controllable: {enabled: false}
            """;

    @Test void groupIsTheWorldAloneAsAStablePathSegment() {
        assertEquals("world", CitizensImporter.group("world"));
        assertEquals("world_nether", CitizensImporter.group("world_nether"));
        assertEquals("world-nether", CitizensImporter.group("World Nether"));
        assertEquals("spawn", CitizensImporter.group("__spawn__"));
        assertEquals("citizens", CitizensImporter.group(""));
        assertEquals("citizens", CitizensImporter.group("$$$"));
        assertEquals(48, CitizensImporter.group("a".repeat(80)).length());
    }

    @Test void definitionsAreSplitIntoWorldFoldersAfterWorldMapping() throws Exception {
        Path source = Files.writeString(root.resolve("saves.yml"), SAVES);
        CitizensImporter.Preview preview = new CitizensImporter().preview(source, root.resolve("data"), Map.of("old", "survival"));
        assertEquals(Set.of("npcs/world-nether/citizens-1.json", "npcs/survival/citizens-2.json", "npcs/citizens/citizens-3.json", "npcs/survival/citizens-4.json"),
                preview.summary().getAsJsonObject("documents").keySet());
        JsonObject horse = JsonDocuments.object(Files.readString(preview.directory().resolve("npcs/survival/citizens-2.json")));
        assertEquals("survival", horse.get("group").getAsString());
    }

    @Test void controllableBecomesRidingAndImportedShopsOpen() throws Exception {
        Path source = Files.writeString(root.resolve("saves.yml"), SAVES);
        CitizensImporter.Preview preview = new CitizensImporter().preview(source, root.resolve("data"), Map.of());
        JsonObject horse = JsonDocuments.object(Files.readString(preview.directory().resolve("npcs/old/citizens-2.json")));
        JsonObject riding = horse.getAsJsonObject("modules").getAsJsonObject(RideSettings.MODULE);
        assertTrue(riding.get("enabled").getAsBoolean() && !riding.has("reviewAccepted"));
        assertTrue(RideSettings.parse(horse).isPresent());
        JsonObject parked = JsonDocuments.object(Files.readString(preview.directory().resolve("npcs/old/citizens-4.json")));
        assertFalse(parked.getAsJsonObject("modules").has(RideSettings.MODULE));

        JsonObject baker = JsonDocuments.object(Files.readString(preview.directory().resolve("npcs/world-nether/citizens-1.json")));
        JsonObject shop = baker.getAsJsonObject("modules").getAsJsonObject(ShopBinding.MODULE);
        assertFalse(shop.has("reviewAccepted"));
        assertEquals("dtltraders:bakery", ShopBinding.parse(baker).orElseThrow().shop());
    }

    @Test void handWrittenShopsNeedNoReview() throws Exception {
        JsonObject definition = JsonDocuments.object("""
                {"modules": {"animus:shop": {"shop": "town:baker"}}}""");
        assertEquals("town:baker", ShopBinding.parse(definition).orElseThrow().shop());
    }
}
