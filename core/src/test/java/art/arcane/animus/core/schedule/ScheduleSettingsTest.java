package art.arcane.animus.core.schedule;

import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcPose;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScheduleSettingsTest {
    static JsonObject definition(String type, String module, String extra) {
        return JsonParser.parseString("""
                {"schemaVersion":1,"revision":1,"uuid":"00000000-0000-0000-0000-000000000001","id":"baker","group":"test",
                 "name":"Baker","type":"%s","spawned":true,
                 "position":{"world":"world","x":0,"y":64,"z":0,"yaw":0,"pitch":0},
                 "modules":{"animus:schedule":%s}%s}
                """.formatted(type, module, extra)).getAsJsonObject();
    }
    private static String failure(String module) {
        return assertThrows(IllegalArgumentException.class, () -> ScheduleSettings.parse(definition("minecraft:player", module, "")), module).getMessage();
    }

    @Test void readsEveryLocationField() {
        ScheduleSettings schedule = ScheduleSettings.parse(definition("minecraft:player", """
                {"speed":3,"locations":[
                  {"name":"bed","time":13000,"x":10,"y":64,"z":-4,"yaw":90,"pitch":10,"chance":40,"weather":"storm","staySeconds":[30,60],
                   "wander":{"radius":5,"waitSeconds":[2,4]},"pose":"sleeping","equipment":{"head":{"id":"minecraft:leather_helmet"}},
                   "clearEquipment":true,"skin":"%s","skinOnDeparture":true,"commands":["say goodnight"]},
                  {"time":0,"x":1,"y":64,"z":1,"wander":{"area":[5,60,5,0,70,0]}}]}
                """.formatted("a".repeat(64)), "")).orElseThrow();
        assertEquals(3, schedule.speed().getAsDouble());
        ScheduleSettings.Location bed = schedule.locations().getFirst();
        assertEquals("bed", bed.name().orElseThrow());
        assertEquals(13000, bed.time());
        assertEquals(40, bed.chance());
        assertEquals(ScheduleSettings.Weather.STORM, bed.weather());
        assertEquals(new ScheduleSettings.Seconds(30, 60), bed.stay().orElseThrow());
        assertEquals(5, bed.wander().orElseThrow().radius());
        assertEquals(new ScheduleSettings.Seconds(2, 4), bed.wander().orElseThrow().pause());
        assertEquals(NpcPose.SLEEPING, bed.pose().orElseThrow());
        assertEquals("minecraft:leather_helmet", bed.equipment().get(EquipmentSlot.HEAD).id());
        assertTrue(bed.clearEquipment() && bed.skinOnDeparture());
        assertEquals(List.of("say goodnight"), bed.commands());
        ScheduleSettings.Location yard = schedule.locations().get(1);
        assertEquals(100, yard.chance());
        assertEquals(ScheduleSettings.Weather.ANY, yard.weather());
        assertEquals(new ScheduleSettings.Area(0, 60, 0, 5, 70, 5), yard.wander().orElseThrow().area().orElseThrow());
        assertEquals(new ScheduleSettings.Seconds(1, 1), yard.wander().orElseThrow().pause());
    }

    @Test void absentOrDisabledSchedulesNothing() {
        assertTrue(ScheduleSettings.parse(JsonParser.parseString("{\"modules\":{}}").getAsJsonObject()).isEmpty());
        assertTrue(ScheduleSettings.parse(definition("minecraft:player", "{\"enabled\":false,\"locations\":[{\"time\":0,\"x\":0,\"y\":64,\"z\":0}]}", "")).isEmpty());
    }

    @Test void importedSchedulesWaitForReview() {
        String module = "{\"enabled\":true,\"locations\":[{\"time\":0,\"x\":0,\"y\":64,\"z\":0}]}";
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ScheduleSettings.parse(definition("minecraft:player", module, ",\"migration\":{}")))
                .getMessage().contains("reviewAccepted"));
        assertTrue(ScheduleSettings.parse(definition("minecraft:player", "{\"enabled\":false,\"reviewAccepted\":false,\"locations\":[{\"time\":0,\"x\":0,\"y\":64,\"z\":0}]}",
                ",\"migration\":{}")).isEmpty());
        assertTrue(ScheduleSettings.parse(definition("minecraft:player", "{\"reviewAccepted\":true,\"locations\":[{\"time\":0,\"x\":0,\"y\":64,\"z\":0}]}",
                ",\"migration\":{}")).isPresent());
    }

    @Test void aRouteAndAScheduleCannotBothRun() {
        JsonObject both = definition("minecraft:player", "{\"locations\":[{\"time\":0,\"x\":0,\"y\":64,\"z\":0}]}", "");
        JsonObject navigation = new JsonObject();
        navigation.addProperty("enabled", true);
        both.getAsJsonObject("modules").add("animus:navigation", navigation);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ScheduleSettings.parse(both)).getMessage().contains("not both"));
    }

    @Test void malformedFieldsNameTheirPath() {
        assertTrue(failure("{\"locations\":[]}").startsWith("$.modules[\"animus:schedule\"].locations"));
        assertTrue(failure("{\"locations\":[{\"time\":24000,\"x\":0,\"y\":64,\"z\":0}]}").startsWith("$.modules[\"animus:schedule\"].locations[0].time"));
        assertTrue(failure("{\"locations\":[{\"time\":0,\"x\":0.5,\"y\":64,\"z\":0}]}").contains("locations[0].x"));
        assertTrue(failure("{\"locations\":[{\"time\":0,\"x\":0,\"y\":64,\"z\":0,\"chance\":0}]}").contains("locations[0].chance"));
        assertTrue(failure("{\"locations\":[{\"time\":0,\"x\":0,\"y\":64,\"z\":0,\"weather\":\"rain\"}]}").contains("locations[0].weather"));
        assertTrue(failure("{\"locations\":[{\"time\":0,\"x\":0,\"y\":64,\"z\":0,\"staySeconds\":[9,3]}]}").contains("staySeconds: min above max"));
        assertTrue(failure("{\"locations\":[{\"time\":0,\"x\":0,\"y\":64,\"z\":0,\"wander\":{}}]}").contains("wander: expected radius or area"));
        assertTrue(failure("{\"locations\":[{\"time\":0,\"x\":0,\"y\":64,\"z\":0,\"wander\":{\"radius\":33}}]}").contains("wander.radius"));
        assertTrue(failure("{\"locations\":[{\"time\":0,\"x\":0,\"y\":64,\"z\":0,\"commands\":[\"/say hi\"]}]}").contains("commands[0]"));
        assertTrue(failure("{\"locations\":[{\"time\":0,\"x\":0,\"y\":64,\"z\":0,\"equipment\":{\"hat\":{\"id\":\"minecraft:stone\"}}}]}").contains("equipment.hat"));
        assertTrue(failure("{\"locations\":[{\"time\":0,\"x\":0,\"y\":64,\"z\":0,\"door\":true}]}").contains("locations[0].door: unknown field"));
        assertTrue(failure("{\"speed\":11,\"locations\":[{\"time\":0,\"x\":0,\"y\":64,\"z\":0}]}").contains(".speed"));
    }

    @Test void posesAndSkinsNeedAPlayerBody() {
        assertThrows(IllegalArgumentException.class, () -> ScheduleSettings.parse(definition("minecraft:villager",
                "{\"locations\":[{\"time\":0,\"x\":0,\"y\":64,\"z\":0,\"pose\":\"sleeping\"}]}", "")));
        assertThrows(IllegalArgumentException.class, () -> ScheduleSettings.parse(definition("minecraft:villager",
                "{\"locations\":[{\"time\":0,\"x\":0,\"y\":64,\"z\":0,\"skin\":\"" + "b".repeat(64) + "\"}]}", "")));
    }

    @Test void definitionValidationReportsScheduleProblems() {
        List<String> errors = NpcDefinition.validate(definition("minecraft:player", "{\"locations\":[{\"time\":-1,\"x\":0,\"y\":64,\"z\":0}]}", ""));
        assertTrue(errors.stream().anyMatch((String error) -> error.contains("locations[0].time")), errors.toString());
    }
}
