package art.arcane.animus.core.migration;

import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CitizensModelEngineImportTest {
    @TempDir Path root;

    private static final String SAVE = """
            npc:
              '7':
                uuid: 00000000-0000-0000-0000-000000000007
                name: Drake
                traitnames: type,location,spawned,meg_model
                traits:
                  type: PLAYER
                  spawned: true
                  location: {world: world, x: 1.5, y: 64, z: 2.5, yaw: 90, pitch: 0}
                  meg_model:
                    model_data: '{"version":"R4.0.3","base_visible":false,"models":[{"blueprint":"drake","render_scale":2.0,"hitbox_scale":2.0}]}'
              '8':
                uuid: 00000000-0000-0000-0000-000000000008
                name: Broken
                traits:
                  type: PLAYER
                  spawned: true
                  location: {world: world, x: 4, y: 64, z: 4, yaw: 0, pitch: 0}
                  meg_model: {unknown: true}
            """;

    @Test void aModelEngineNpcImportsWithItsModel() throws Exception {
        CitizensImporter.Preview preview = new CitizensImporter().preview(Files.writeString(root.resolve("saves.yml"), SAVE), root.resolve("out"), Map.of());
        assertEquals(2, preview.valid());
        JsonObject modelled = JsonDocuments.object(Files.readString(preview.directory().resolve("npcs/world/citizens-7.json")));
        assertEquals(JsonDocuments.object("{\"model\":\"drake\",\"scale\":2.0}"), modelled.getAsJsonObject("modules").getAsJsonObject("animus:modelengine"));
        JsonObject report = JsonDocuments.object(Files.readString(preview.directory().resolve("reports/7.json")));
        assertEquals("CONVERTED", report.getAsJsonObject("fields").get("/traits/meg_model/model_data").getAsString());

        JsonObject broken = JsonDocuments.object(Files.readString(preview.directory().resolve("npcs/world/citizens-8.json")));
        assertFalse(broken.getAsJsonObject("modules").has("animus:modelengine"));
        assertTrue(broken.getAsJsonObject("modules").getAsJsonObject("citizens:review").has("meg_model"));
        JsonObject brokenReport = JsonDocuments.object(Files.readString(preview.directory().resolve("reports/8.json")));
        assertEquals("REQUIRES_REVIEW", brokenReport.getAsJsonObject("fields").get("/traits/meg_model/unknown").getAsString());
        assertTrue(preview.summary().getAsJsonArray("migrationWarnings").toString()
                .contains("citizens-8: Model Engine data names no model this converter can read; kept in citizens:review"), preview.summary().toString());
    }
}
