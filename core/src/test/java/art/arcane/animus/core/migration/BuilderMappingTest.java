package art.arcane.animus.core.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.building.BuildOptions;
import art.arcane.animus.core.building.BuilderModule;
import art.arcane.animus.core.navigation.BlockPos;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuilderMappingTest {
    @TempDir Path root;
    private static final String SAVES = """
            npc:
              '7':
                uuid: 00000000-0000-0000-0000-000000000007
                name: Mason
                traits:
                  type: PLAYER
                  location: {world: world, x: 1, y: 64, z: 2, yaw: 0, pitch: 0}
                  builder:
                    Schematic: tower
                    State: building
                    PatternXY: REVERSESPIRAL
                    IgnoreAir: 'true'
                    IgnoreLiquid: false
                    Excavate: true
                    HoldItems: false
                    RequireMaterials: true
                    NeededMaterials: {'1': 12}
                    MoveTimeoutSeconds: 2.4
                    YOffset: -3
                    YLayers: 2
                    Origin: {world: world, x: 10.7, y: 64, z: -5.2, yaw: 0, pitch: 0}
                    oncomplete: celebrate
                    Silent: true
                    Shiny: yes
                  constructor:
                    team: [8, 9]
                    preview: 30
            """;

    @Test void builderTraitBecomesAReviewedBuilderSection() throws Exception {
        Path source = Files.writeString(root.resolve("saves.yml"), SAVES);
        CitizensImporter importer = new CitizensImporter();
        CitizensImporter.Preview preview = importer.preview(source, root.resolve("out"), Map.of());
        assertEquals(1, preview.valid());
        JsonArray warnings = preview.summary().getAsJsonArray("migrationWarnings");
        String all = warnings.toString();
        assertTrue(all.contains("save it again as tower.schem"), all);
        assertTrue(all.contains("Denizen task"), all);
        assertTrue(all.contains("kept for review without conversion: Shiny"), all);
        assertTrue(all.contains("constructor trait kept for review only"), all);
        assertTrue(all.contains("team, preview"), all);
        JsonObject report = JsonDocuments.object(Files.readString(preview.directory().resolve("reports/7.json"))).getAsJsonObject("fields");
        assertEquals("REQUIRES_REVIEW", report.get("/traits/builder/Schematic").getAsString());
        assertEquals("REQUIRES_REVIEW", report.get("/traits/constructor/preview").getAsString());
        try (DocumentStore store = new DocumentStore(root.resolve("out"))) {
            importer.apply(store, preview.id());
            NpcDefinition definition = NpcDefinition.parse(store.read("npcs/world/citizens-7.json"));
            BuilderModule module = BuilderModule.of(definition).orElseThrow();
            assertFalse(module.reviewAccepted());
            assertEquals("tower.schem", module.schematic());
            assertEquals(Optional.of(new BlockPos(10, 64, -6)), module.origin());
            BuildOptions options = module.options();
            assertEquals(BuildOptions.Pattern.REVERSE_SPIRAL, options.pattern());
            assertTrue(options.ignoreAir());
            assertFalse(options.ignoreLiquids());
            assertTrue(options.excavate());
            assertFalse(options.holdBlock());
            assertTrue(options.silent());
            assertEquals(BuildOptions.Materials.DONATE, options.materials());
            assertEquals(2, options.moveTimeoutSeconds());
            assertEquals(-3, options.offsetY());
            assertEquals(2, options.layers());
            JsonObject review = definition.json().getAsJsonObject("modules").getAsJsonObject("citizens:review");
            assertEquals(30, review.getAsJsonObject("constructor").get("preview").getAsInt());
            assertEquals("celebrate", definition.json().getAsJsonObject("modules").getAsJsonObject(BuilderModule.MODULE)
                    .getAsJsonObject("imported").getAsJsonObject("builder").get("oncomplete").getAsString());
        }
    }

    @Test void acceptedReviewOrHandWrittenSectionsAreReady() throws Exception {
        NpcDefinition written = NpcDefinition.parse("""
                {"schemaVersion": 1, "revision": 1, "uuid": "00000000-0000-0000-0000-000000000011", "id": "mason", "group": "town",
                 "name": "Mason", "type": "minecraft:player", "spawned": false, "position": {"world": "world", "x": 0, "y": 64, "z": 0, "yaw": 0, "pitch": 0},
                 "modules": {"animus:builder": {"schematic": "hut.nbt", "options": {"rotation": 90}}}}
                """);
        BuilderModule module = BuilderModule.of(written).orElseThrow();
        assertTrue(module.reviewAccepted());
        assertEquals(90, module.options().transform().rotation().degrees());
        assertEquals(Optional.empty(), module.origin());
    }

    @Test void badTraitsStillConvertWithWarnings() {
        BuilderMapping.Result result = BuilderMapping.convert(new JsonObject());
        assertEquals("missing.schem", result.module().get("schematic").getAsString());
        assertTrue(result.warnings().getFirst().contains("names no schematic"));
        JsonObject odd = new JsonObject();
        odd.addProperty("Schematic", "../escape");
        odd.addProperty("PatternXY", "zigzag");
        BuilderMapping.Result escaped = BuilderMapping.convert(odd);
        assertEquals(".._escape.schem", escaped.module().get("schematic").getAsString());
        assertTrue(escaped.warnings().stream().anyMatch((String warning) -> warning.contains("zigzag")));
    }
}
