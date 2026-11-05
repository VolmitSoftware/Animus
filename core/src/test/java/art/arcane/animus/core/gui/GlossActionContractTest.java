package art.arcane.animus.core.gui;

import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** schema/gloss/source.json lists the Gloss actions Animus runs and refuses; the parser's tables must say the same. */
class GlossActionContractTest {
    @Test
    void sharedActionListsMatchTheParser() throws IOException {
        Path source = Path.of(System.getProperty("animusSchemaDirectory"), "gloss", "source.json");
        JsonObject actions = JsonDocuments.object(Files.readString(source)).getAsJsonObject("actions");
        assertEquals(GuiParser.SUPPORTED, strings(actions, "supported"));
        assertEquals(GuiParser.ANIMUS, strings(actions, "animus"));
        assertEquals(GuiParser.GLOSS_ONLY, strings(actions, "refused"));
    }

    private static Set<String> strings(JsonObject object, String key) {
        Set<String> values = new HashSet<>();
        for (JsonElement value : object.getAsJsonArray(key)) values.add(value.getAsString());
        return values;
    }
}
