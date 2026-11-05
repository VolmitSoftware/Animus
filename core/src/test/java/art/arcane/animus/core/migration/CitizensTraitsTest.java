package art.arcane.animus.core.migration;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CitizensTraitsTest {
    private static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
    private static CitizensTraits.Result map(String type, String record, boolean combat) {
        JsonObject source = json(record);
        JsonObject traits = source.has("traits") ? source.getAsJsonObject("traits") : new JsonObject();
        return CitizensTraits.map("minecraft:" + type, source, traits, combat, new JsonArray());
    }

    @Test void navigatorSettingsBecomeTheNpcsMovement() {
        CitizensTraits.Result result = map("player", """
                {"navigator":{"speedmodifier":0.3,"avoidwater":false,"falldistance":1,"pathfindingrange":60.0,"distancemargin":0.5,"destinationteleportmargin":0.0,
                 "stationaryticks":120,"usedefaultstuckaction":true},"metadata":{"disable-default-stuck-action":true,"flyable":true},
                 "traits":{"attributetrait":{"attributes":{"minecraft:step_height":2.0,"minecraft:max_health":40}}}}
                """, false);
        assertEquals(json("""
                {"speed":1.29,"avoidWater":false,"maximumFall":1,"pathRange":60.0,"arrivalMargin":0.5,"stuck":{"afterTicks":120,"teleport":false},"fly":true,"stepHeight":1.5}
                """), result.fields().get("movement"));
        for (String path : List.of("/navigator/speedmodifier", "/navigator/avoidwater", "/navigator/falldistance", "/navigator/pathfindingrange",
                "/navigator/distancemargin", "/navigator/destinationteleportmargin", "/navigator/stationaryticks", "/navigator/usedefaultstuckaction",
                "/metadata/disable-default-stuck-action", "/metadata/flyable")) assertTrue(result.converted().contains(path), path);
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
                "/traits/rotationtrait/globalParameters/immediate", "/traits/rotationtrait/globalParameters/maxYawPerTick", "/traits/spawned", "/traits/controllable/enabled"))
            assertTrue(result.converted().contains(path), path);
        assertEquals(CitizensImporter.Status.UNSUPPORTED_PRESERVED, result.notes().get("/metadata/minecraft-ai").status());
        assertEquals(CitizensImporter.Status.REQUIRES_REVIEW, result.notes().get("/metadata/pathfinder-open-doors").status());
        assertEquals(CitizensImporter.Status.REQUIRES_REVIEW, result.notes().get("/traits/rotationtrait/globalParameters/headOnly").status());
        assertEquals(CitizensImporter.Status.UNSUPPORTED_PRESERVED, result.notes().get("/traits/targetable/targetable").status());
        assertEquals(CitizensImporter.Status.UNSUPPORTED_PRESERVED, result.notes().get("/traitnames").status());
        assertEquals(CitizensImporter.Status.UNSUPPORTED_PRESERVED, map("player", "{\"metadata\":{\"protected\":false}}", false).notes().get("/metadata/protected").status());
    }

    @Test void negativeAgeMakesBabiesOnlyOnAgeableBodies() {
        assertTrue(map("cow", "{\"traits\":{\"age\":{\"age\":-1,\"locked\":true}}}", false).fields().get("baby").getAsBoolean());
        assertFalse(map("cow", "{\"traits\":{\"age\":{\"age\":0}}}", false).fields().has("baby"));
        CitizensTraits.Result player = map("player", "{\"traits\":{\"age\":{\"age\":-1}}}", false);
        assertFalse(player.fields().has("baby"));
        assertEquals(CitizensImporter.Status.UNSUPPORTED_PRESERVED, player.notes().get("/traits/age/age").status());
    }
}
