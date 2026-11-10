package art.arcane.animus.core.migration;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CitizensTraitsTest {
    private static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
    private static CitizensTraits.Result map(String type, String record, boolean combat) { return map(type, record, combat, false); }
    private static CitizensTraits.Result map(String type, String record, boolean combat, boolean routed) { return map(type, record, combat, routed, false); }
    /** A Sentinel NPC converts with both combat and health modules. */
    private static CitizensTraits.Result map(String type, String record, boolean combat, boolean routed, boolean patrolled) {
        JsonObject source = json(record);
        JsonObject traits = source.has("traits") ? source.getAsJsonObject("traits") : new JsonObject();
        return CitizensTraits.map("minecraft:" + type, source, traits, combat, combat, routed, patrolled, new JsonArray());
    }

    @Test void navigatorSettingsBecomeTheNpcsMovement() {
        CitizensTraits.Result result = map("player", """
                {"navigator":{"speedmodifier":0.3,"avoidwater":false,"falldistance":1,"pathfindingrange":60.0,"distancemargin":0.5,"destinationteleportmargin":0.0,
                 "stationaryticks":120,"usedefaultstuckaction":true},"metadata":{"disable-default-stuck-action":true,"flyable":true,"pathfinder-open-doors":true},
                 "traits":{"attributetrait":{"attributes":{"minecraft:step_height":2.0,"minecraft:max_health":40}}}}
                """, false);
        assertEquals(json("""
                {"speed":1.29,"avoidWater":false,"maximumFall":1,"pathRange":60.0,"arrivalMargin":0.5,"stuck":{"afterTicks":120,"teleport":false},"style":"fly","openDoors":true}
                """), result.fields().get("movement"));
        for (String path : List.of("/navigator/speedmodifier", "/navigator/avoidwater", "/navigator/falldistance", "/navigator/pathfindingrange",
                "/navigator/distancemargin", "/navigator/destinationteleportmargin", "/navigator/stationaryticks", "/navigator/usedefaultstuckaction",
                "/metadata/disable-default-stuck-action", "/metadata/flyable", "/metadata/pathfinder-open-doors")) assertTrue(result.converted().contains(path), path);
        assertEquals(CitizensImporter.Status.CONVERTED, result.notes().get("/traits/attributetrait/attributes/minecraft:step_height").status());
        assertTrue(result.converted().contains("/traits/attributetrait/attributes/minecraft:max_health"));
        assertEquals(json("{\"step_height\":2.0,\"max_health\":40}"), result.fields().get("attributes"), "the attribute trait becomes the attributes field");
        assertEquals(List.of(), result.warnings());
    }

    @Test void legacyAttributeNamesConvertAndValuesOutsideTheVanillaRangeAreClamped() {
        CitizensTraits.Result result = map("zombie", """
                {"traits":{"attributetrait":{"attributes":{"generic_scale":1.5,"generic.gravity":0.02,"zombie_spawn_reinforcements":0.5,"horse.jump_strength":0.7,
                 "minecraft:armor":45,"mymod:mana":10}}}}
                """, false);
        assertEquals(json("{\"scale\":1.5,\"gravity\":0.02,\"spawn_reinforcements\":0.5,\"jump_strength\":0.7,\"armor\":30}"), result.fields().get("attributes"));
        assertTrue(result.converted().contains("/traits/attributetrait/attributes/generic_scale"));
        assertEquals(List.of("/traits/attributetrait/attributes/minecraft:armor: 45 is outside 0..30 for armor and became 30"), result.warnings());
        assertEquals(CitizensImporter.Status.UNSUPPORTED_PRESERVED, result.notes().get("/traits/attributetrait/attributes/mymod:mana").status());
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
        assertEquals(CitizensImporter.Status.CONVERTED, result.notes().get("/traits/rotationtrait/globalParameters/headOnly").status());
        assertEquals(CitizensImporter.Status.UNSUPPORTED_PRESERVED, result.notes().get("/traitnames").status());
        assertEquals(CitizensImporter.Status.UNSUPPORTED_PRESERVED, map("player", "{\"metadata\":{\"protected\":false}}", false).notes().get("/metadata/protected").status());
    }

    @Test void followBecomesAGuardThatProtectsOnlyWhenAsked() {
        JsonObject traits = json("{\"followtrait\":{\"followingUUID\":\"00000000-0000-0000-0000-00000000000c\",\"margin\":3.5,\"protect\":true}}");
        assertEquals(json("{\"player\":\"00000000-0000-0000-0000-00000000000c\",\"followDistance\":3.5,\"pointRange\":1.0,\"protect\":true}"),
                CitizensTraits.follow(traits, Set.of()).orElseThrow());
        JsonObject npc = json("{\"followtrait\":{\"followingUUID\":\"00000000-0000-0000-0000-00000000000d\"}}");
        assertEquals(json("{\"npc\":\"00000000-0000-0000-0000-00000000000d\",\"followDistance\":2.0,\"pointRange\":1.0,\"protect\":false}"),
                CitizensTraits.follow(npc, Set.of("00000000-0000-0000-0000-00000000000d")).orElseThrow());
        assertTrue(CitizensTraits.follow(json("{\"followtrait\":{\"margin\":2}}"), Set.of()).isEmpty(), "following nobody");
        CitizensTraits.Result result = map("player", "{\"traits\":{\"followtrait\":{\"followingUUID\":\"00000000-0000-0000-0000-00000000000c\",\"protect\":false}}}", false);
        assertEquals(CitizensImporter.Status.CONVERTED, result.notes().get("/traits/followtrait/followingUUID").status());
        assertTrue(result.converted().contains("/traits/followtrait/protect"));
    }

    @Test void homeInTheNpcsWorldReturnsAndOtherwiseIsKept() {
        JsonObject traits = json("""
                {"location":{"world":"world"},"hometrait":{"location":{"world":"world","x":10.5,"y":64.0,"z":-3.25,"yaw":-90.0,"pitch":0.0},"delay":40,"distance":-1.0,"strategy":"PATHFIND"}}
                """);
        assertEquals(json("{\"x\":10.5,\"y\":64.0,\"z\":-3.25,\"yaw\":-90.0,\"pitch\":0.0,\"delayTicks\":40,\"return\":\"walk\"}"), CitizensTraits.home(traits, "world").orElseThrow());
        assertTrue(CitizensTraits.home(traits, "world_nether").isEmpty());
        CitizensTraits.Result moved = map("player", "{\"traits\":{\"location\":{\"world\":\"town\"},\"hometrait\":{\"location\":{\"world\":\"world\",\"x\":0,\"y\":64,\"z\":0}}}}", false);
        assertEquals(CitizensImporter.Status.UNSUPPORTED_PRESERVED, moved.notes().get("/traits/hometrait").status());
    }

    @Test void anchorsBecomeNamedPlacesAndMirrorBecomesAModule() {
        CitizensTraits.Result result = map("player", """
                {"traits":{"location":{"world":"world"},"anchors":{"list":{"0":"Dock;world;3.0;64.0;3.0","1":"pier;isle;1;2;3","2":"bad;world;x"}}}}
                """, false);
        assertEquals(json("{\"dock\":{\"x\":3.0,\"y\":64.0,\"z\":3.0},\"pier\":{\"x\":1.0,\"y\":2.0,\"z\":3.0,\"world\":\"isle\"}}"), result.fields().get("anchors"));
        assertEquals(CitizensImporter.Status.UNSUPPORTED_PRESERVED, result.notes().get("/traits/anchors/list/2").status());
        assertEquals(json("{\"skin\":true,\"name\":true}"), CitizensTraits.mirror(json("{\"mirrortrait\":{\"enabled\":true,\"mirrorName\":true}}")).orElseThrow());
        assertTrue(CitizensTraits.mirror(json("{\"mirrortrait\":{\"enabled\":false}}")).isEmpty());
    }

    @Test void pausePathfindingPausesTheRouteOrIsKeptWithoutOne() {
        JsonObject traits = json("{\"pausepathfinding\":{\"playerrange\":80.0,\"rightclick\":true,\"pauseticks\":0,\"lockoutduration\":60}}");
        assertEquals(json("{\"playerRange\":64.0,\"onClick\":true,\"ticks\":20,\"lockoutTicks\":60}"), CitizensTraits.pause(traits).orElseThrow());
        assertTrue(CitizensTraits.pause(json("{\"pausepathfinding\":{\"playerrange\":-1.0}}")).isEmpty(), "pauses for nothing");
        CitizensTraits.Result routed = map("player", "{\"traits\":{\"pausepathfinding\":{\"rightclick\":true}}}", false, true);
        assertEquals(CitizensImporter.Status.CONVERTED, routed.notes().get("/traits/pausepathfinding").status());
        CitizensTraits.Result standing = map("player", "{\"traits\":{\"pausepathfinding\":{\"rightclick\":true}}}", false, false);
        assertEquals(CitizensImporter.Status.UNSUPPORTED_PRESERVED, standing.notes().get("/traits/pausepathfinding").status());
    }

    @Test void aRouteWithoutAvoidWaterSwimsAsCitizensDoes() {
        assertEquals(json("{\"avoidWater\":false}"), map("player", "{}", false, true).fields().get("movement"), "Citizens' default lets the NPC swim");
        assertFalse(map("player", "{}", false, false).fields().has("movement"), "an NPC without a route needs no movement");
        assertEquals(json("{\"avoidWater\":true}"), map("player", "{\"navigator\":{\"avoidwater\":true}}", false, true).fields().get("movement"));
    }

    @Test void citizensRoutesOpenDoorsAndDropAtLeastThreeBlocks() {
        CitizensTraits.Result low = map("player", "{\"navigator\":{\"falldistance\":1}}", false, true, true);
        assertEquals(json("{\"maximumFall\":3,\"avoidWater\":false,\"openDoors\":true}"), low.fields().get("movement"));
        assertTrue(low.raisedFall());
        CitizensTraits.Result high = map("player", "{\"navigator\":{\"falldistance\":5}}", false, true, true);
        assertEquals(5, high.fields().getAsJsonObject("movement").get("maximumFall").getAsInt(), "A longer drop stays");
        assertFalse(high.raisedFall());
        assertEquals(json("{\"avoidWater\":false,\"openDoors\":true}"), map("player", "{}", false, true, true).fields().get("movement"),
                "Without a fall distance the body's own drop of 3 applies");
        assertEquals(1, map("player", "{\"navigator\":{\"falldistance\":1}}", false, true, false).fields().getAsJsonObject("movement").get("maximumFall").getAsInt(),
                "A wander route is not a Citizens route");
    }

    @Test void vanillaAiBecomesAWanderRouteOnMobBodies() {
        String record = "{\"metadata\":{\"minecraft-ai\":true},\"navigator\":{\"speedmodifier\":1.5}}";
        assertTrue(CitizensTraits.wanders("minecraft:zombie", json(record)));
        assertFalse(CitizensTraits.wanders("minecraft:player", json(record)), "player bodies have no vanilla AI");
        assertEquals(json("""
                {"enabled":true,"mode":"walk","speed":"SPEED","order":"wander","waitTicks":120,"points":[],"wander":{"radius":10,"height":7}}
                """.replace("\"SPEED\"", Double.toString(CitizensBehaviors.speed(1.5)))), CitizensTraits.vanillaWander(json(record)));
        assertEquals(CitizensImporter.Status.CONVERTED, map("zombie", record, false, true).notes().get("/metadata/minecraft-ai").status());
        assertEquals(CitizensImporter.Status.UNSUPPORTED_PRESERVED, map("zombie", record, false, false).notes().get("/metadata/minecraft-ai").status());
    }

    @Test void targetableNpcsGetACombatModuleThatNeverFightsBack() {
        JsonObject traits = json("{\"targetable\":{\"targetable\":true}}");
        assertTrue(CitizensTraits.targetable(traits));
        assertFalse(CitizensTraits.targetable(json("{\"targetable\":{\"targetable\":false}}")));
        assertEquals(json("{\"enabled\":true,\"targeting\":{\"fightBack\":false}}"), CitizensTraits.targetableCombat());
        assertEquals(json("{\"killable\":false}"), CitizensTraits.targetableHealth(json("{}")));
        assertEquals(json("{\"killable\":true}"), CitizensTraits.targetableHealth(json("{\"metadata\":{\"protected\":false}}")));
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
