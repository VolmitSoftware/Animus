package art.arcane.animus.core.npc;

import art.arcane.animus.core.presentation.BodySkin;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NpcDisguiseTest {
    @TempDir Path root;
    static final String GUARD = """
            {"schemaVersion":1,"revision":1,"uuid":"12345678-1234-1234-1234-123456789abc","id":"guard","group":"","name":"Guard","type":"minecraft:player","spawned":false,
             "position":{"world":"world","x":0,"y":64,"z":0,"yaw":0,"pitch":0},"pose":"sneaking",
             "equipment":{"head":{"id":"minecraft:iron_helmet","count":1},"main_hand":{"id":"minecraft:stick","count":1}},"disguise":"zombie setBaby true"}
            """;
    private final NpcDisguise.Resolver zombie = (String text) -> {
        if (!text.startsWith("zombie")) throw new IllegalArgumentException("Unknown disguise " + text);
        return new NpcDisguise.Body(object("{\"type\":\"minecraft:zombie\",\"baby\":true,\"equipment\":{\"main_hand\":{\"id\":\"minecraft:iron_sword\",\"count\":1}}}"),
                Optional.empty(), List.of("setBurning true"));
    };

    @AfterEach void uninstall() { NpcDisguise.uninstall(zombie); }

    static JsonObject object(String json) { return JsonParser.parseString(json).getAsJsonObject(); }

    @Test
    void theDisguiseFieldMustBeText() {
        JsonObject definition = object(GUARD);
        assertTrue(NpcDefinition.validate(definition).isEmpty());
        definition.addProperty("disguise", " ");
        assertEquals(List.of("$.disguise: expected disguise text of 1-8192 characters without control characters"), NpcDefinition.validate(definition));
        definition.addProperty("disguise", 3);
        assertFalse(NpcDefinition.validate(definition).isEmpty());
    }

    @Test
    void withoutAResolverTheNpcKeepsItsOwnBody() {
        NpcDisguise.Applied applied = NpcDisguise.apply(object(GUARD));
        assertEquals("minecraft:player", applied.definition().get("type").getAsString());
        assertTrue(applied.notes().isEmpty());
        assertTrue(NpcDisguise.resolve("zombie").isEmpty());
    }

    @Test
    void theDisguiseReplacesBodyFieldsAndDropsWhatTheNewBodyCannotCarry() {
        NpcDisguise.install(zombie);
        NpcDisguise.Applied applied = NpcDisguise.apply(object(GUARD));
        JsonObject disguised = applied.definition();
        assertEquals("minecraft:zombie", disguised.get("type").getAsString());
        assertTrue(disguised.get("baby").getAsBoolean());
        assertEquals("minecraft:iron_sword", disguised.getAsJsonObject("equipment").getAsJsonObject("main_hand").get("id").getAsString());
        assertEquals("minecraft:iron_helmet", disguised.getAsJsonObject("equipment").getAsJsonObject("head").get("id").getAsString());
        assertFalse(disguised.has("pose"));
        assertEquals(List.of("not shown on a minecraft:zombie body: pose", "disguise options not shown: setBurning true"), applied.notes());
    }

    @Test
    void unreadableTextLeavesTheBodyAndSaysWhy() {
        NpcDisguise.install(zombie);
        JsonObject definition = object(GUARD);
        definition.addProperty("disguise", "dragon_head");
        NpcDisguise.Applied applied = NpcDisguise.apply(definition);
        assertEquals("minecraft:player", applied.definition().get("type").getAsString());
        assertEquals(List.of("disguise not shown: Unknown disguise dragon_head"), applied.notes());
    }

    @Test
    void equipmentTheNewBodyCannotShowIsDropped() {
        JsonObject horse = object("{\"type\":\"minecraft:horse\",\"horse\":{\"color\":\"black\"}}");
        NpcDisguise.Applied applied = NpcDisguise.apply(object(GUARD), new NpcDisguise.Body(horse, Optional.empty(), List.of()));
        JsonObject disguised = applied.definition();
        assertFalse(disguised.has("equipment"));
        assertEquals("black", disguised.getAsJsonObject("horse").get("color").getAsString());
        assertEquals(List.of("not shown on a minecraft:horse body: equipment.head, equipment.main_hand, pose"), applied.notes());
    }

    @Test
    void aPlayerDisguiseSavesItsSkinAndTheRepositoryPublishesTheDisguisedBody() throws Exception {
        BodySkin skin = new BodySkin("dGV4dHVyZXM=", "c2lnbmVk");
        NpcDisguise.Resolver notch = (String text) -> new NpcDisguise.Body(object("{\"type\":\"minecraft:player\",\"name\":\"Notch\"}"), Optional.of(skin), List.of());
        NpcDisguise.install(notch);
        try (DocumentStore store = new DocumentStore(root)) {
            store.commit(List.of(new DocumentStore.Change("npcs/guard.json", "missing", GUARD.replace("zombie setBaby true", "player Notch"))));
            NpcRepository repository = new NpcRepository(store);
            assertTrue(repository.reload().isEmpty());
            NpcDefinition effective = repository.find("guard").orElseThrow();
            assertEquals("Notch", effective.name());
            String hash = effective.json().get("skin").getAsString();
            String document = store.read("skins/" + hash + ".json");
            assertNotNull(document);
            assertEquals(hash, JsonDocuments.hash(document));
            assertFalse(repository.draft("guard").document().has("skin"));
            assertEquals("Guard", repository.draft("guard").document().get("name").getAsString());
        } finally { NpcDisguise.uninstall(notch); }
    }

    @Test
    void theRepositoryKeepsWhatTheDisguiseLeftOut() throws Exception {
        NpcDisguise.install(zombie);
        try (DocumentStore store = new DocumentStore(root)) {
            store.commit(List.of(new DocumentStore.Change("npcs/guard.json", "missing", GUARD)));
            NpcRepository repository = new NpcRepository(store);
            assertTrue(repository.reload().isEmpty());
            assertEquals("minecraft:zombie", repository.find("guard").orElseThrow().json().get("type").getAsString());
            assertEquals(List.of("not shown on a minecraft:zombie body: pose", "disguise options not shown: setBurning true"), repository.disguiseNotes("guard"));
        }
    }
}
