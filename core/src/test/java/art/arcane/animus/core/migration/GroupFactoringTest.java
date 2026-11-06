package art.arcane.animus.core.migration;

import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.storage.DocumentStore;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroupFactoringTest {
    @TempDir Path root;
    private static final String SAVES = """
            npc:
              '1':
                uuid: 00000000-0000-0000-0000-000000000001
                name: Guard One
                traits:
                  type: PLAYER
                  spawned: true
                  location: {world: world, x: 10.5, y: 64, z: -3.25, yaw: 90, pitch: 0}
                  sentinel: {health: 40, targets: [MONSTERS]}
                  lookclose: {enabled: true, range: 10}
              '2':
                uuid: 00000000-0000-0000-0000-000000000002
                name: Guard Two
                traits:
                  type: PLAYER
                  spawned: true
                  location: {world: world, x: 12.5, y: 64, z: -3.25, yaw: 90, pitch: 0}
                  sentinel: {health: 40, targets: [MONSTERS]}
                  lookclose: {enabled: true, range: 10}
              '3':
                uuid: 00000000-0000-0000-0000-000000000003
                name: Baker
                traits:
                  type: PLAYER
                  location: {world: world, x: 1, y: 64, z: 2}
            """;

    @AfterEach void clearGuard() { CitizensGuard.clear(); }

    @Test
    void npcsWithIdenticalGroupableFieldsShareOneGroupAndKeepTheirEffectiveDefinition() {
        JsonObject a = JsonParser.parseString("{\"id\":\"a\",\"glowing\":true,\"modules\":{\"x:y\":{\"b\":1,\"a\":2},\"citizens:review\":{\"k\":1}}}").getAsJsonObject();
        JsonObject b = JsonParser.parseString("{\"id\":\"b\",\"modules\":{\"x:y\":{\"a\":2,\"b\":1},\"citizens:review\":{\"k\":2}},\"glowing\":true}").getAsJsonObject();
        JsonObject c = JsonParser.parseString("{\"id\":\"c\",\"glowing\":false}").getAsJsonObject();
        Map<String, JsonObject> definitions = new LinkedHashMap<>();
        for (JsonObject definition : List.of(a, b, c)) definitions.put(definition.get("id").getAsString(), definition);
        GroupFactoring.Result result = GroupFactoring.factor(definitions);
        assertEquals(0, result.groups().size(), "members that are not valid NPCs are left as they were");
        assertEquals(a, result.definitions().get("a"));
    }

    @Test
    void factorGroupsStagesGroupFilesAndAcceptAndRollbackCoverThem() throws Exception {
        Files.createDirectories(root.resolve("plugins/Citizens"));
        Files.writeString(root.resolve("plugins/Citizens/saves.yml"), SAVES);
        try (DocumentStore store = new DocumentStore(root.resolve("plugins/Animus"))) {
            NpcRepository npcs = new NpcRepository(store); npcs.loadForStartup();
            CitizensDropIn flow = new CitizensDropIn(store, npcs, new CitizensDropIn.Host(Optional.of(root.resolve("plugins")), () -> false, () -> false, () -> false));
            String staged = String.join("\n", flow.execute(new String[]{"factor-groups"}));
            assertTrue(staged.contains("Factored groups: groups/citizens-"), staged);
            assertTrue(staged.contains(".json (2 NPCs)"), staged);
            assertTrue(staged.contains("combat 2"), "review counts read the effective definitions: " + staged);
            flow.execute(new String[]{"apply"});
            NpcDefinition one = npcs.find("citizens-1").orElseThrow(), two = npcs.find("citizens-2").orElseThrow();
            assertEquals(1, one.groups().size());
            assertEquals(one.groups(), two.groups());
            String group = one.groups().getFirst();
            assertTrue(group.matches("citizens-[a-f0-9]{8}"), group);
            assertNotNull(store.read("groups/" + group + ".json"));
            assertTrue(one.json().getAsJsonObject("modules").has("animus:combat"), "the effective definition keeps the converted combat");
            assertFalse(npcs.draft("citizens-1").document().getAsJsonObject("modules").has("animus:combat"), "the stored file lists the group instead");
            assertTrue(npcs.find("citizens-3").orElseThrow().groups().isEmpty());

            String accepted = String.join("\n", flow.execute(new String[]{"accept", "combat"}));
            assertTrue(accepted.contains("combat 2"), accepted);
            JsonObject combat = npcs.find("citizens-1").orElseThrow().json().getAsJsonObject("modules").getAsJsonObject("animus:combat");
            assertTrue(combat.get("reviewAccepted").getAsBoolean() && combat.get("enabled").getAsBoolean(), combat.toString());
            assertTrue(store.read("groups/" + group + ".json").replaceAll("\\s", "").contains("\"reviewAccepted\":true"));

            flow.execute(new String[]{"rollback"});
            assertNull(store.read("groups/" + group + ".json"));
            assertTrue(npcs.all().isEmpty());
        }
    }
}
