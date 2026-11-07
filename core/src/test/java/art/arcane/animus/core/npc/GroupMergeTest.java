package art.arcane.animus.core.npc;

import art.arcane.animus.core.editor.SharedFixtures;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroupMergeTest {
    @Test
    void everySharedCaseMergesToItsEffectiveDefinitionAndProvenance() throws IOException {
        List<Path> cases;
        try (Stream<Path> listing = Files.list(SharedFixtures.fixture("groups/cases"))) { cases = listing.filter((Path path) -> path.toString().endsWith(".json")).sorted().toList(); }
        assertTrue(cases.size() >= 4, "expected the shared merge cases");
        for (Path file : cases) {
            JsonObject fixture = SharedFixtures.object(file);
            List<GroupDocument> groups = new ArrayList<>();
            for (JsonElement element : fixture.getAsJsonArray("groups")) {
                JsonObject group = element.getAsJsonObject();
                GroupDocument.Result parsed = GroupDocument.parse(group.get("id").getAsString(), group.toString());
                assertTrue(parsed.problems().isEmpty(), file + ": " + parsed.problems());
                groups.add(parsed.group().orElseThrow());
            }
            GroupMerge.Result result = GroupMerge.merge(fixture.getAsJsonObject("npc"), groups);
            assertEquals(fixture.getAsJsonObject("effective"), result.effective(), file.getFileName().toString());
            Map<String, String> provenance = new TreeMap<>();
            fixture.getAsJsonObject("provenance").entrySet().forEach((Map.Entry<String, JsonElement> entry) -> provenance.put(entry.getKey(), entry.getValue().getAsString()));
            assertEquals(provenance, result.provenance(), file.getFileName().toString());
            assertTrue(NpcDefinition.validate(result.effective()).isEmpty(), file + ": " + NpcDefinition.validate(result.effective()));
            assertFalse(result.effective().has("skins"), file.getFileName().toString());
        }
    }

    @Test
    void identityFieldsAndNestedGroupsAreRefusedInsideValues() throws IOException {
        List<String> identity = GroupDocument.parse("refused-identity", SharedFixtures.text(SharedFixtures.fixture("groups/refused-identity.json"))).problems();
        assertTrue(identity.contains("$.values.name: not allowed in a group"), identity.toString());
        assertTrue(identity.contains("$.values.spawned: not allowed in a group"), identity.toString());
        List<String> nested = GroupDocument.parse("refused-nested", SharedFixtures.text(SharedFixtures.fixture("groups/refused-nested.json"))).problems();
        assertEquals(List.of("$.values.groups: not allowed in a group"), nested);
    }

    @Test
    void aBadValueFailsTheGroupWithItsFieldPath() throws IOException {
        List<String> problems = GroupDocument.parse("refused-values", SharedFixtures.text(SharedFixtures.fixture("groups/refused-values.json"))).problems();
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.getFirst().startsWith("$.values.equipment.head.count"), problems.toString());
        List<String> mismatch = GroupDocument.parse("refused-id-mismatch", SharedFixtures.text(SharedFixtures.fixture("groups/refused-id-mismatch.json"))).problems();
        assertTrue(mismatch.getFirst().startsWith("$.id:"), mismatch.toString());
    }

    @Test
    void aGroupMayHoldOneBodyTypesOwnSettings() {
        assertEquals(List.of(), GroupDocument.validateValues(com.google.gson.JsonParser.parseString("{\"horse\":{\"color\":\"black\"}}").getAsJsonObject()));
        assertEquals(List.of("$.values.horse.color: expected white, creamy, chestnut, brown, black, gray or dark_brown"),
                GroupDocument.validateValues(com.google.gson.JsonParser.parseString("{\"horse\":{\"color\":\"blue\"}}").getAsJsonObject()));
        assertEquals(List.of("$.values.llama: available only for minecraft:llama bodies"),
                GroupDocument.validateValues(com.google.gson.JsonParser.parseString("{\"horse\":{},\"llama\":{}}").getAsJsonObject()));
    }

    @Test
    void npcGroupsListIsValidated() {
        JsonObject npc = SharedFixturesNpc.guard();
        npc.add("groups", com.google.gson.JsonParser.parseString("[\"a\",\"a\"]"));
        assertEquals(List.of("$.groups[1]: duplicate group"), NpcDefinition.validate(npc));
        npc.add("groups", com.google.gson.JsonParser.parseString("[]"));
        assertEquals(List.of("$.groups: expected an array of 1-16 group ids"), NpcDefinition.validate(npc));
        npc.add("groups", com.google.gson.JsonParser.parseString("[\"Bad Id\"]"));
        assertTrue(NpcDefinition.validate(npc).getFirst().startsWith("$.groups[0]:"));
    }

    /** A minimal valid NPC. */
    static final class SharedFixturesNpc {
        static JsonObject guard() {
            return com.google.gson.JsonParser.parseString("""
                    {"schemaVersion":1,"revision":1,"uuid":"12345678-1234-1234-1234-123456789abc","id":"guard","group":"town","name":"Guard","type":"minecraft:player","spawned":false,"position":{"world":"world","x":0,"y":64,"z":0,"yaw":0,"pitch":0}}
                    """).getAsJsonObject();
        }
    }
}
