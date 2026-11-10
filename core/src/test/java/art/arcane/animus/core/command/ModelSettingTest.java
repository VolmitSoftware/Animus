package art.arcane.animus.core.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.integration.ModelEngineModule;
import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.storage.DocumentStore;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModelSettingTest {
    @TempDir Path root;

    private static final String GUARD = "{\"schemaVersion\":1,\"revision\":1,\"uuid\":\"12345678-1234-1234-1234-123456789abc\",\"id\":\"guard\",\"group\":\"\",\"name\":\"N\","
            + "\"type\":\"minecraft:player\",\"spawned\":false,\"position\":{\"world\":\"world\",\"x\":0,\"y\":64,\"z\":0,\"yaw\":0,\"pitch\":0}}";

    private static JsonObject module(NpcRepository repository) {
        JsonObject modules = repository.find("guard").orElseThrow().json().getAsJsonObject("modules");
        return modules == null ? null : modules.getAsJsonObject(ModelEngineModule.MODULE);
    }

    @Test void setsShowsAndRemovesTheModel() throws Exception {
        try (DocumentStore store = new DocumentStore(root)) {
            store.commit(List.of(new DocumentStore.Change("npcs/guard.json", "missing", GUARD)));
            NpcRepository repository = new NpcRepository(store);
            assertTrue(repository.reload().isEmpty());
            assertEquals("guard: no Model Engine model", ModelSetting.show(repository, "guard"));
            assertEquals("scale needs a model first: /animus npc model guard model <id>",
                    assertThrows(IllegalArgumentException.class, () -> ModelSetting.set(repository, "guard", "scale", "2")).getMessage());

            assertTrue(ModelSetting.set(repository, "guard", "model", "drake").startsWith("guard: model = drake; tx "));
            assertTrue(ModelSetting.set(repository, "guard", "scale", "2.5").startsWith("guard: scale = 2.5; tx "));
            assertTrue(ModelSetting.set(repository, "guard", "walk", "run").startsWith("guard: walk = run; tx "));
            assertTrue(ModelSetting.set(repository, "guard", "show-body", "on").startsWith("guard: show-body = true; tx "));
            assertTrue(ModelSetting.set(repository, "guard", "nametag-bone", "tag_name").startsWith("guard: nametag-bone = tag_name; tx "));
            assertEquals(JsonParser.parseString("{\"model\":\"drake\",\"scale\":2.5,\"states\":{\"walk\":\"run\"},\"showBody\":true,\"nametagBone\":\"tag_name\"}"), module(repository));
            assertEquals("guard: " + module(repository), ModelSetting.show(repository, "guard"));

            assertEquals("$.modules.animus:modelengine.scale: expected 0.0625..16",
                    assertThrows(IllegalArgumentException.class, () -> ModelSetting.set(repository, "guard", "scale", "40")).getMessage());
            assertEquals(2.5, module(repository).get("scale").getAsDouble(), "a refused value leaves the file alone");

            assertTrue(ModelSetting.set(repository, "guard", "walk", "reset").startsWith("guard: walk reset; tx "));
            assertFalse(module(repository).has("states"), "an empty states object is removed");
            assertTrue(ModelSetting.set(repository, "guard", "model", "reset").startsWith("guard: model removed; tx "));
            assertEquals(null, module(repository));
        }
    }

    @Test void unknownSettingsListTheKnownOnes() {
        assertEquals("setting must be one of attack, death, hitbox, hurt, idle, interact, model, nametag-bone, scale, show-body, walk",
                assertThrows(IllegalArgumentException.class, () -> ModelSetting.set(null, "guard", "colour", "red")).getMessage());
    }
}
