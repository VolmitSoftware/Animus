package art.arcane.animus.core.behavior.schema;

import art.arcane.animus.core.quest.QuestDocument;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The editor's quest schema and the Java parser accept and refuse the same documents. */
class QuestSchemaAgreementTest {
    private static JsonSchemaCheck schema() throws Exception {
        JsonObject schema = JsonParser.parseString(Files.readString(RepositoryFiles.root().resolve("schema/quest-v1.schema.json"))).getAsJsonObject();
        schema.remove("$id");
        return new JsonSchemaCheck(schema);
    }

    private static String example() throws Exception { return Files.readString(RepositoryFiles.root().resolve("examples/quests/town/herbs.json")); }

    @Test void theExampleQuestPassesBoth() throws Exception {
        String text = example();
        assertEquals(List.of(), schema().validate(JsonParser.parseString(text)));
        assertEquals(List.of(), QuestDocument.parse("town:herbs", text).problems());
    }

    @Test void bothRefuseTheSameMistakes() throws Exception {
        JsonSchemaCheck schema = schema();
        String text = example();
        for (String broken : List.of(
                text.replace("\"type\": \"talk\", \"npc\": \"farmer\"", "\"type\": \"talk\""),
                text.replace("\"entity\": \"minecraft:zombie\"", "\"entity\": \"minecraft:zombie\", \"npc\": \"bandit\""),
                text.replace("{\"mode\": \"daily\"}", "{\"mode\": \"cooldown\"}"),
                text.replace("{\"mode\": \"daily\"}", "{\"mode\": \"daily\", \"seconds\": 5}"),
                text.replace("\"town:herbs\"", "\"town:a/b\""),
                text.replace("\"experience\": 50", "\"experience\": 50, \"commands\": [{\"command\": \"/say hi\"}]"),
                text.replace("\"type\": \"talk\", \"npc\": \"farmer\"", "\"type\": \"custom\""),
                text.replace("\"type\": \"talk\", \"npc\": \"farmer\"", "\"type\": \"talk\", \"npc\": \"farmer\", \"amount\": 2"))) {
            JsonObject document = JsonParser.parseString(broken).getAsJsonObject();
            assertFalse(schema.validate(document).isEmpty(), "schema accepted " + broken);
            assertFalse(QuestDocument.parse("town:herbs", broken).problems().isEmpty(), "parser accepted " + broken);
        }
    }
}
