package art.arcane.animus.core.integration;

import com.google.gson.JsonObject;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NpcTemplatesTest {
    private static NpcTemplates.Source mobs() {
        return new NpcTemplates.Source() {
            @Override public NpcTemplates.Template template(String name) {
                if (!name.equals("King")) throw new IllegalArgumentException("MythicMobs has no mob named " + name);
                JsonObject fields = new JsonObject();
                fields.addProperty("type", "minecraft:player");
                return new NpcTemplates.Template(fields, List.of());
            }
            @Override public List<String> names() { return List.of("King"); }
        };
    }

    @Test void aMissingSourceSaysWhichPluginIsAbsent() {
        NpcTemplates templates = new NpcTemplates();
        assertEquals("MythicMobs is not running on this server", assertThrows(IllegalArgumentException.class, () -> templates.template(NpcTemplates.MYTHIC, "King")).getMessage());
        assertEquals(List.of(), templates.names(NpcTemplates.MYTHIC));
    }

    @Test void aRegisteredSourceAnswersUntilItsHandleCloses() throws Exception {
        NpcTemplates templates = new NpcTemplates();
        AutoCloseable handle = templates.register(NpcTemplates.MYTHIC, mobs());
        assertThrows(IllegalStateException.class, () -> templates.register(NpcTemplates.MYTHIC, mobs()));
        assertEquals("minecraft:player", templates.template(NpcTemplates.MYTHIC, "King").fields().get("type").getAsString());
        assertEquals("MythicMobs has no mob named Queen", assertThrows(IllegalArgumentException.class, () -> templates.template(NpcTemplates.MYTHIC, "Queen")).getMessage());
        assertEquals(List.of("King"), templates.names(NpcTemplates.MYTHIC));
        handle.close();
        assertEquals(List.of(), templates.names(NpcTemplates.MYTHIC));
    }

    @Test void aTemplateCannotBeChangedThroughWhatItHandsOut() {
        JsonObject fields = new JsonObject();
        fields.addProperty("type", "minecraft:player");
        NpcTemplates.Template template = new NpcTemplates.Template(fields, List.of());
        fields.addProperty("type", "minecraft:horse");
        template.fields().addProperty("type", "minecraft:frog");
        assertEquals("minecraft:player", template.fields().get("type").getAsString());
    }
}
