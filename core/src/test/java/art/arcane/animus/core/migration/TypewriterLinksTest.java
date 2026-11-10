package art.arcane.animus.core.migration;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Typewriter pages swap the Citizens extension's NPC entries for the Animus extension's, keeping entry ids and every link. */
class TypewriterLinksTest {
    private static final NpcLinks LINKS = NpcLinks.of(List.of(new NpcLinks.Link("citizens-3", java.util.UUID.randomUUID(), 3, "Innkeeper")));

    private static final String PAGE = """
            {"id": "page1", "name": "town", "type": "static", "entries": [
              {"id": "inn", "name": "innkeeper", "type": "reference_npc", "displayName": "Innkeeper", "sound": {}, "npcId": 3},
              {"id": "lost", "name": "lost", "blueprintId": "reference_npc", "displayName": "", "sound": {}, "npcId": 42},
              {"id": "click", "name": "click_inn", "blueprintId": "on_npc_interact", "triggers": ["hello"], "identifier": "inn"},
              {"id": "click2", "name": "click_lost", "type": "on_npc_interact", "triggers": [], "identifier": "lost"},
              {"id": "hello", "name": "hello", "type": "spoken", "speaker": "inn", "text": "Hi"}
            ]}
            """;

    @Test void citizensEntriesBecomeAnimusEntries() {
        TypewriterLinks.Result result = TypewriterLinks.rewrite("pages/town.json", PAGE, LINKS);
        JsonArray entries = JsonParser.parseString(result.text()).getAsJsonObject().getAsJsonArray("entries");
        JsonObject inn = entries.get(0).getAsJsonObject();
        assertEquals("animus_npc", inn.get("type").getAsString(), "the key the page used is kept");
        assertEquals("citizens-3", inn.get("npc").getAsString());
        assertFalse(inn.has("npcId"));
        assertEquals("Innkeeper", inn.get("displayName").getAsString());
        assertEquals("reference_npc", entries.get(1).getAsJsonObject().get("blueprintId").getAsString(), "an NPC that was not imported stays");
        assertEquals("on_animus_npc_interact", entries.get(2).getAsJsonObject().get("blueprintId").getAsString());
        assertEquals("inn", entries.get(2).getAsJsonObject().get("identifier").getAsString());
        assertEquals("on_npc_interact", entries.get(3).getAsJsonObject().get("type").getAsString());
        assertEquals("inn", entries.get(4).getAsJsonObject().get("speaker").getAsString(), "speakers keep pointing at the same entry id");
        assertEquals(2, result.changes().size());
        assertTrue(result.notes().stream().anyMatch((String note) -> note.contains("lost") && note.contains("Citizens NPC 42")), result.notes().toString());
    }

    @Test void pageWithoutCitizensEntriesIsUnchanged() {
        String page = "{\"id\": \"p\", \"name\": \"p\", \"type\": \"static\", \"entries\": []}";
        TypewriterLinks.Result result = TypewriterLinks.rewrite("pages/p.json", page, LINKS);
        assertSame(page, result.text());
    }

    @Test void unreadablePageIsReported() {
        TypewriterLinks.Result result = TypewriterLinks.rewrite("pages/bad.json", "{not json", LINKS);
        assertEquals("{not json", result.text());
        assertTrue(result.notes().getFirst().contains("pages/bad.json"));
    }
}
