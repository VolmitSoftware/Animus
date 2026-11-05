package art.arcane.animus.core.migration;

import art.arcane.animus.core.control.RideSettings;
import art.arcane.animus.core.shop.ShopBinding;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
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

    private static String group(String world, double x, double z) { return CitizensImporter.group(world, new JsonPrimitive(x), new JsonPrimitive(z)); }

    @Test void groupIsTheWorldAndRegionFileAsAStablePathSegment() {
        assertEquals("world-r0_0", group("world", 0, 511.9));
        assertEquals("world-r-1_1", group("world", -0.5, 512));
        assertEquals("world_nether-r-2_-3", group("world_nether", -1024, -1025));
        assertEquals("world-nether-r0_0", group("World Nether", 1, 1));
        assertEquals("spawn-r0_0", group("__spawn__", 1, 1));
        assertEquals("citizens", group("", 1, 1));
        assertEquals("citizens", group("$$$", 1, 1));
        assertEquals("world", CitizensImporter.group("world", JsonNull.INSTANCE, new JsonPrimitive(1)));
        assertEquals("world", CitizensImporter.group("world", new JsonPrimitive("1"), new JsonPrimitive(1)));
        assertEquals(48 + "-r0_0".length(), group("a".repeat(80), 1, 1).length());
    }

    @Test void definitionsAreSplitIntoWorldFoldersAfterWorldMapping() throws Exception {
        Path source = Files.writeString(root.resolve("saves.yml"), SAVES);
        CitizensImporter.Preview preview = new CitizensImporter().preview(source, root.resolve("data"), Map.of("old", "survival"));
        assertEquals(Set.of("npcs/world-nether-r0_0/citizens-1.json", "npcs/survival-r0_0/citizens-2.json", "npcs/citizens/citizens-3.json", "npcs/survival-r0_0/citizens-4.json"),
                preview.summary().getAsJsonObject("documents").keySet());
        JsonObject horse = JsonDocuments.object(Files.readString(preview.directory().resolve("npcs/survival-r0_0/citizens-2.json")));
        assertEquals("survival-r0_0", horse.get("group").getAsString());
    }

    @Test void controllableBecomesDisabledRidingAndImportedShopsWaitForReview() throws Exception {
        Path source = Files.writeString(root.resolve("saves.yml"), SAVES);
        CitizensImporter.Preview preview = new CitizensImporter().preview(source, root.resolve("data"), Map.of());
        JsonObject horse = JsonDocuments.object(Files.readString(preview.directory().resolve("npcs/old-r0_0/citizens-2.json")));
        JsonObject riding = horse.getAsJsonObject("modules").getAsJsonObject(RideSettings.MODULE);
        assertFalse(riding.get("enabled").getAsBoolean());
        assertFalse(riding.get("reviewAccepted").getAsBoolean());
        assertTrue(RideSettings.parse(horse).isEmpty());
        JsonObject parked = JsonDocuments.object(Files.readString(preview.directory().resolve("npcs/old-r0_0/citizens-4.json")));
        assertFalse(parked.getAsJsonObject("modules").has(RideSettings.MODULE));

        JsonObject baker = JsonDocuments.object(Files.readString(preview.directory().resolve("npcs/world-nether-r0_0/citizens-1.json")));
        JsonObject shop = baker.getAsJsonObject("modules").getAsJsonObject(ShopBinding.MODULE);
        assertFalse(shop.get("reviewAccepted").getAsBoolean());
        assertTrue(ShopBinding.parse(baker).isEmpty());
        shop.addProperty("reviewAccepted", true);
        assertEquals("dtltraders:bakery", ShopBinding.parse(baker).orElseThrow().shop());
    }

    @Test void handWrittenShopsNeedNoReview() throws Exception {
        JsonObject definition = JsonDocuments.object("""
                {"modules": {"animus:shop": {"shop": "town:baker"}}}""");
        assertEquals("town:baker", ShopBinding.parse(definition).orElseThrow().shop());
    }
}
