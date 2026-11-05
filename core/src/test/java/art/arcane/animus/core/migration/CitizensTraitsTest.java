package art.arcane.animus.core.migration;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CitizensTraitsTest {
    private static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
    private static CitizensTraits.Result map(String type, String record, boolean combat) { return map(type, record, combat, false); }
    private static CitizensTraits.Result map(String type, String record, boolean combat, boolean routed) {
        JsonObject source = json(record);
        JsonObject traits = source.has("traits") ? source.getAsJsonObject("traits") : new JsonObject();
        return CitizensTraits.map("minecraft:" + type, source, traits, combat, routed, new JsonArray());
    }

    @Test void navigatorSettingsBecomeTheNpcsMovement() {
        CitizensTraits.Result result = map("player", """
                {"navigator":{"speedmodifier":0.3,"avoidwater":false,"falldistance":1,"pathfindingrange":60.0,"distancemargin":0.5,"destinationteleportmargin":0.0,
                 "stationaryticks":120,"usedefaultstuckaction":true},"metadata":{"disable-default-stuck-action":true,"flyable":true,"pathfinder-open-doors":true},
                 "traits":{"attributetrait":{"attributes":{"minecraft:step_height":2.0,"minecraft:max_health":40}}}}
                """, false);
        assertEquals(json("""
                {"speed":1.29,"avoidWater":false,"maximumFall":1,"pathRange":60.0,"arrivalMargin":0.5,"stuck":{"afterTicks":120,"teleport":false},"fly":true,"openDoors":true,"stepHeight":1.5}
                """), result.fields().get("movement"));
        for (String path : List.of("/navigator/speedmodifier", "/navigator/avoidwater", "/navigator/falldistance", "/navigator/pathfindingrange",
                "/navigator/distancemargin", "/navigator/destinationteleportmargin", "/navigator/stationaryticks", "/navigator/usedefaultstuckaction",
                "/metadata/disable-default-stuck-action", "/metadata/flyable", "/metadata/pathfinder-open-doors")) assertTrue(result.converted().contains(path), path);
        assertEquals(CitizensImporter.Status.CONVERTED, result.notes().get("/traits/attributetrait/attributes/minecraft:step_height").status());
        assertEquals(CitizensImporter.Status.UNSUPPORTED_PRESERVED, result.notes().get("/traits/attributetrait/attributes/minecraft:max_health").status());
        assertEquals(List.of(), result.warnings());
    }

    @Test void bodyStateMetadataMaps() {
        CitizensTraits.Result result = map("player", """
                {"metadata":{"glowing":true,"collidable":false,"protected":false,"minecraft-ai":true,"using-held-item":true,"removefromplayerlist":false,
                 "cached-skin-uuid":"00000000-0000-0000-0000-00000000000b","cached-skin-uuid-name":"skinowner","pathfinder-open-doors":true},
                 "traits":{"scoreboardtrait":{"color":"DARK_RED","tags":["CITIZENS_NPC","Town Guard","town-guard"]},"gravity":{"enabled":true},
                 "rotationtrait":{"globalParameters":{"immediate":true,"maxYawPerTick":40.0,"headOnly":true}},"spawned":false,"targetable":{"targetable":true},
                 "controllable":{"enabled":false}}}
                """, true);
        JsonObject fields = result.fields();
        assertTrue(fields.get("glowing").getAsBoolean());
        assertTrue(fields.get("usingItem").getAsBoolean());
        assertTrue(fields.get("listed").getAsBoolean());
        assertFalse(fields.get("gravity").getAsBoolean());
        assertEquals("dark_red", fields.get("teamColor").getAsString());
        assertEquals("[\"town-guard\"]", fields.get("tags").toString());
        assertEquals(json("{\"skinUuid\":\"00000000-0000-0000-0000-00000000000b\",\"skinName\":\"skinowner\"}"), result.migration());
        for (String path : List.of("/metadata/glowing", "/metadata/collidable", "/metadata/protected", "/metadata/using-held-item", "/metadata/removefromplayerlist",
                "/metadata/cached-skin-uuid", "/traits/scoreboardtrait/tags/0", "/traits/scoreboardtrait/tags/1", "/traits/scoreboardtrait/color", "/traits/gravity/enabled",
                "/traits/rotationtrait/globalParameters/immediate", "/traits/rotationtrait/globalParameters/maxYawPerTick", "/traits/spawned", "/traits/controllable/enabled",
                "/metadata/minecraft-ai", "/metadata/pathfinder-open-doors", "/traits/targetable/targetable"))
            assertTrue(result.converted().contains(path), path);
        assertEquals(CitizensImporter.Status.REQUIRES_REVIEW, result.notes().get("/traits/rotationtrait/globalParameters/headOnly").status());
        assertEquals(CitizensImporter.Status.UNSUPPORTED_PRESERVED, result.notes().get("/traitnames").status());
        assertEquals(CitizensImporter.Status.UNSUPPORTED_PRESERVED, map("player", "{\"metadata\":{\"protected\":false}}", false).notes().get("/metadata/protected").status());
    }

    @Test void aRouteWithoutAvoidWaterSwimsAsCitizensDoes() {
        assertEquals(json("{\"avoidWater\":false}"), map("player", "{}", false, true).fields().get("movement"), "Citizens' default lets the NPC swim");
        assertFalse(map("player", "{}", false, false).fields().has("movement"), "an NPC without a route needs no movement");
        assertEquals(json("{\"avoidWater\":true}"), map("player", "{\"navigator\":{\"avoidwater\":true}}", false, true).fields().get("movement"));
    }

    @Test void vanillaAiBecomesAWanderRouteOnMobBodies() {
        String record = "{\"metadata\":{\"minecraft-ai\":true},\"navigator\":{\"speedmodifier\":1.5}}";
        assertTrue(CitizensTraits.wanders("minecraft:zombie", json(record)));
        assertFalse(CitizensTraits.wanders("minecraft:player", json(record)), "player bodies have no vanilla AI");
        assertEquals(json("""
                {"enabled":false,"reviewAccepted":false,"mode":"walk","speed":"SPEED","order":"wander","waitTicks":120,"points":[],"wander":{"radius":10,"height":7}}
                """.replace("\"SPEED\"", Double.toString(CitizensBehaviors.speed(1.5)))), CitizensTraits.vanillaWander(json(record)));
        assertEquals(CitizensImporter.Status.CONVERTED, map("zombie", record, false, true).notes().get("/metadata/minecraft-ai").status());
        assertEquals(CitizensImporter.Status.UNSUPPORTED_PRESERVED, map("zombie", record, false, false).notes().get("/metadata/minecraft-ai").status());
    }

    @Test void targetableNpcsGetACombatModuleThatNeverFightsBack() {
        JsonObject traits = json("{\"targetable\":{\"targetable\":true}}");
        assertTrue(CitizensTraits.targetable(traits));
        assertFalse(CitizensTraits.targetable(json("{\"targetable\":{\"targetable\":false}}")));
        assertEquals(json("{\"enabled\":false,\"reviewAccepted\":false,\"targeting\":{\"fightBack\":false},\"defense\":{\"invincible\":true}}"),
                CitizensTraits.targetableCombat(json("{}")));
        assertFalse(CitizensTraits.targetableCombat(json("{\"metadata\":{\"protected\":false}}")).getAsJsonObject("defense").get("invincible").getAsBoolean());
        assertEquals(CitizensImporter.Status.UNSUPPORTED_PRESERVED, map("player", "{\"traits\":{\"targetable\":{\"targetable\":true}}}", false)
                .notes().get("/traits/targetable/targetable").status());
    }

    @Test void namedPosesBecomeTheDefinitionsPoses() {
        CitizensTraits.Result result = map("player", """
                {"traits":{"poses":{"list":{"0":"Wave Hello;45.5;-120.0","1":"nod;370;10","2":"broken","3":"wave-hello;1;1"}}}}
                """, false);
        assertEquals(json("{\"wave-hello\":{\"yaw\":45.5,\"pitch\":-90.0},\"nod\":{\"yaw\":10.0,\"pitch\":10.0}}"), result.fields().get("poses"));
        assertTrue(result.converted().contains("/traits/poses/list/0"));
        assertTrue(result.converted().contains("/traits/poses/list/1"));
        assertEquals(CitizensImporter.Status.UNSUPPORTED_PRESERVED, result.notes().get("/traits/poses/list/2").status());
        assertEquals(CitizensImporter.Status.UNSUPPORTED_PRESERVED, result.notes().get("/traits/poses/list/3").status());
    }

    @Test void negativeAgeMakesBabiesOnlyOnAgeableBodies() {
        assertTrue(map("cow", "{\"traits\":{\"age\":{\"age\":-1,\"locked\":true}}}", false).fields().get("baby").getAsBoolean());
        assertFalse(map("cow", "{\"traits\":{\"age\":{\"age\":0}}}", false).fields().has("baby"));
        CitizensTraits.Result player = map("player", "{\"traits\":{\"age\":{\"age\":-1}}}", false);
        assertFalse(player.fields().has("baby"));
        assertEquals(CitizensImporter.Status.UNSUPPORTED_PRESERVED, player.notes().get("/traits/age/age").status());
    }
}
