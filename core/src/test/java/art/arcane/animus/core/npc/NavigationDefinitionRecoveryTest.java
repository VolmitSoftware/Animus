package art.arcane.animus.core.npc;

import art.arcane.animus.core.navigation.WaypointSettings;
import art.arcane.animus.core.storage.*;
import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class NavigationDefinitionRecoveryTest {
    @TempDir Path root;
    private static final String PATH="npcs/town/guard.json", POINTER="state/npc-definitions/last-valid/"+PATH;
    private static JsonObject definition() throws IOException {
        JsonObject json=JsonDocuments.object(NpcRepositoryTest.JSON);json.addProperty("spawned",true);
        JsonObject modules=new JsonObject();modules.add("animus:navigation",JsonParser.parseString("""
                {"enabled":true,"mode":"walk","speed":2,"loop":false,"points":[{"x":1,"y":64,"z":2}]}
                """));json.add("modules",modules);return json;
    }
    @Test void invalidInlineEditRetainsValidWaypointDefinitionAcrossReloadAndRestartWithoutOverwritingBytes() throws Exception {
        String valid,bad,pointerHash;
        try(var store=new DocumentStore(root)) {
            var repository=new NpcRepository(store);repository.loadForStartup();
            repository.apply(new NpcRepository.Draft(PATH,"missing",0,definition()));valid=store.read(PATH);pointerHash=store.hash(POINTER);
            JsonObject edited=JsonDocuments.object(valid);edited.getAsJsonObject("modules").getAsJsonObject("animus:navigation").addProperty("speed","bad");
            bad=JsonDocuments.write(edited);Files.writeString(root.resolve(PATH),bad);
            var errors=repository.reload();assertEquals(1,errors.size());assertTrue(errors.getFirst().contains("$.modules.animus:navigation"));assertTrue(errors.getFirst().contains("speed"));
            assertEquals(2,WaypointSettings.parse(repository.find("guard").orElseThrow()).orElseThrow().speed());
            assertEquals(pointerHash,store.hash(POINTER));assertEquals(bad,store.read(PATH));
        }
        try(var store=DocumentStore.readOnly(root)) {
            var validation=new NpcRepository(store).validateStored();assertEquals(1,validation.errors().size());
            assertEquals(pointerHash,store.hash(POINTER));assertEquals(bad,store.read(PATH));
        }
        try(var store=new DocumentStore(root)) {
            var repository=new NpcRepository(store);var errors=repository.loadForStartup();
            assertEquals(1,errors.size());assertTrue(errors.getFirst().contains("$.modules.animus:navigation"));assertTrue(errors.getFirst().contains("durable recovery"));
            var retained=repository.find("guard").orElseThrow();assertTrue(retained.json().get("spawned").getAsBoolean());
            assertEquals(2,WaypointSettings.parse(retained).orElseThrow().speed());assertEquals(bad,store.read(PATH));assertEquals(pointerHash,store.hash(POINTER));
            Files.writeString(root.resolve(PATH),valid);assertTrue(repository.reload().isEmpty());
        }
    }
    @Test void invalidNewWaypointDefinitionIsNeverActivatedOrCheckpointed() throws Exception {
        JsonObject invalid=definition();invalid.getAsJsonObject("modules").getAsJsonObject("animus:navigation")
                .getAsJsonArray("points").get(0).getAsJsonObject().addProperty("x","broken");
        String raw=JsonDocuments.write(invalid);Files.createDirectories(root.resolve("npcs/town"));Files.writeString(root.resolve(PATH),raw);
        IOException parse=assertThrows(IOException.class,()->NpcDefinition.parse(raw));
        assertTrue(parse.getMessage().contains("$.modules.animus:navigation"));assertTrue(parse.getMessage().contains("points[0].x"));
        try(var store=new DocumentStore(root)) {
            var repository=new NpcRepository(store);assertThrows(IOException.class,repository::loadForStartup);
            assertTrue(repository.snapshot().isEmpty());assertNull(store.read(POINTER));assertEquals(raw,store.read(PATH));
        }
    }
    @Test void disabledReviewDataAndUnknownModulesRemainPreserved() throws Exception {
        JsonObject json=definition();JsonObject modules=json.getAsJsonObject("modules");
        modules.getAsJsonObject("animus:navigation").addProperty("enabled",false);
        modules.getAsJsonObject("animus:navigation").addProperty("speed","pending translation");
        modules.add("thirdparty:future",JsonParser.parseString("{\"opaque\":[1,\"retained\"]}"));
        json.add("migration",new JsonObject());var parsed=NpcDefinition.parse(JsonDocuments.write(json));
        assertEquals(modules,parsed.json().getAsJsonObject("modules"));assertTrue(WaypointSettings.parse(parsed).isEmpty());
    }
}
