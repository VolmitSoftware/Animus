package art.arcane.animus.core.integration;

import art.arcane.animus.core.npc.NpcDefinition;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MythicBindingTest {
    private static JsonObject definition(String module) {
        return JsonParser.parseString("""
                {"schemaVersion":1,"revision":1,"uuid":"00000000-0000-0000-0000-000000000001","id":"king","group":"",
                 "name":"King","type":"minecraft:player","spawned":true,
                 "position":{"world":"world","x":0,"y":1,"z":0,"yaw":0,"pitch":0}%s}
                """.formatted(module == null ? "" : ",\"modules\":{\"mythic:mob\":" + module + "}")).getAsJsonObject();
    }
    private static String failure(String module) {
        return assertThrows(IllegalArgumentException.class, () -> MythicBinding.parse(definition(module)), module).getMessage();
    }

    @Test void absentModuleBindsNothing() {
        assertTrue(MythicBinding.parse(definition(null)).isEmpty());
    }

    @Test void mobIsReadAndSkillsRunByDefault() {
        assertEquals(new MythicBinding("SkeletonKing", true), MythicBinding.parse(definition("{\"mob\":\"SkeletonKing\"}")).orElseThrow());
        assertEquals(new MythicBinding("Skeleton_King-2", false), MythicBinding.parse(definition("{\"mob\":\"Skeleton_King-2\",\"enabled\":false}")).orElseThrow());
    }

    @Test void malformedFieldsNameTheirExactPath() {
        assertTrue(failure("[]").startsWith("$.modules.mythic:mob: expected object"));
        assertTrue(failure("{\"enabled\":true}").startsWith("$.modules.mythic:mob.mob"));
        assertTrue(failure("{\"mob\":\"\"}").startsWith("$.modules.mythic:mob.mob"));
        assertTrue(failure("{\"mob\":\"bad name\"}").startsWith("$.modules.mythic:mob.mob"));
        assertTrue(failure("{\"mob\":\"" + "a".repeat(65) + "\"}").startsWith("$.modules.mythic:mob.mob"));
        assertTrue(failure("{\"mob\":\"King\",\"enabled\":1}").startsWith("$.modules.mythic:mob.enabled"));
        assertTrue(failure("{\"mob\":\"King\",\"skills\":true}").startsWith("$.modules.mythic:mob.skills: unknown field"));
    }

    @Test void definitionValidationReportsTheModule() {
        List<String> errors = NpcDefinition.validate(definition("{\"mob\":7}"));
        assertTrue(errors.stream().anyMatch((String error) -> error.startsWith("$.modules.mythic:mob.mob")), errors.toString());
        assertTrue(NpcDefinition.validate(definition("{\"mob\":\"King\"}")).isEmpty());
    }
}
