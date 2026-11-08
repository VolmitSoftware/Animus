package art.arcane.animus.core.combat;

import art.arcane.animus.core.npc.NpcDefinition;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CombatSettingsTest {
    private static JsonObject module(String json) { return JsonParser.parseString(json).getAsJsonObject(); }
    private static CombatSettings parse(String json) { return CombatSettings.parseModule(module(json), HealthSettings.UNKILLABLE); }
    private static String definition(String combat, String extra) {
        return "{\"schemaVersion\":1,\"revision\":1,\"uuid\":\"" + new UUID(0, 1) + "\",\"id\":\"guard\",\"group\":\"town\",\"name\":\"Guard\",\"type\":\"minecraft:player\",\"spawned\":false,"
                + "\"position\":{\"world\":\"world\",\"x\":0,\"y\":64,\"z\":0,\"yaw\":0,\"pitch\":0},\"modules\":{\"animus:combat\":" + combat + "}" + extra + "}";
    }
    private static void assertField(String json, String field) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> parse(json));
        assertTrue(error.getMessage().startsWith(field), error.getMessage());
    }

    @Test void defaultsAreSafeAndTargetNothing() {
        CombatSettings settings = parse("{\"enabled\":true}");
        assertTrue(settings.enabled());
        assertEquals(Set.of(), settings.targets().categories());
        assertTrue(settings.victimDrops());
        assertFalse(settings.ranged().enabled());
        assertTrue(settings.melee().enabled());
        assertEquals(CombatSettings.LineOfSight.REQUIRED, settings.targeting().lineOfSight());
        assertFalse(settings.targetableByMobs());
    }
    @Test void mobsTargetTheNpcOnlyWhenItOptsIn() {
        assertTrue(parse("{\"enabled\":true,\"targetableByMobs\":true}").targetableByMobs());
        assertField("{\"targetableByMobs\":\"yes\"}", "$.modules.animus:combat.targetableByMobs");
    }
    @Test void aFullDocumentParses() {
        CombatSettings settings = parse("""
                {"enabled":true,"targets":{"categories":["monsters"],"entityTypes":["minecraft:pillager"],"players":["Alice"],"npcs":["bandit"],"names":["Bob"],"squads":["raiders"]},
                 "ignores":{"categories":["animals"]},"avoids":{"entityTypes":["minecraft:creeper"]},
                 "targeting":{"scanRange":20,"scanIntervalTicks":10,"reactionTicks":5,"memoryTicks":100,"retainTarget":true,"fightBack":false,"lineOfSight":"field_of_view"},
                 "avoidance":{"range":6,"fleeWhenAttacked":true,"returnPoint":{"world":"world","x":1,"y":64,"z":2}},
                 "squad":{"name":"town","alertRange":24},
                 "guard":{"player":"00000000-0000-0000-0000-000000000001","followDistance":7,"pointRange":4},
                 "pursuit":{"closeChase":true,"rangedChase":true,"speed":4,"giveUpRange":40,"leashRange":64,"returnToPost":true,"teleportWhenStuck":true,"replanTicks":20},
                 "melee":{"damage":4,"reach":3,"cooldownTicks":20,"knockback":0.4},
                 "ranged":{"enabled":true,"weapon":"arrow","damage":3,"range":24,"minimumRange":4,"cooldownTicks":40,"velocity":1.6,"inaccuracy":3,"safeShot":true,"knockback":0.2},
                 "victimDrops":false}""");
        assertEquals(CombatSettings.LineOfSight.FIELD_OF_VIEW, settings.targeting().lineOfSight());
        assertEquals("town", settings.squad().name().orElseThrow());
        assertFalse(settings.victimDrops());
        assertEquals(new UUID(0, 1), settings.guard().player().orElseThrow());
    }
    @Test void bareRegistryIdsAreMinecraftIds() {
        CombatSettings settings = parse("""
                {"enabled":true,"targets":{"entityTypes":["pillager"],"effects":["glowing"]},
                 "melee":{"weaponDamage":{"netherite_axe":9,"minecraft:iron_sword":6}},
                 "ranged":{"enabled":true,"weapon":"potion","effect":{"id":"poison"}}}""");
        assertEquals(Set.of("minecraft:pillager"), settings.targets().entityTypes());
        assertEquals(Set.of("minecraft:glowing"), settings.targets().effects());
        assertEquals(Map.of("minecraft:netherite_axe", 9.0, "minecraft:iron_sword", 6.0), settings.melee().weaponDamage());
        assertEquals("minecraft:poison", settings.ranged().effect().orElseThrow().id());
    }
    @Test void invalidValuesNameTheirField() {
        assertField("{\"melee\":{\"reach\":7}}", "$.modules.animus:combat.melee.reach");
        assertField("{\"melee\":{\"reach\":\"far\"}}", "$.modules.animus:combat.melee.reach");
        assertField("{\"melee\":{\"reech\":2}}", "$.modules.animus:combat.melee.reech");
        assertField("{\"targets\":{\"categories\":[\"dragons\"]}}", "$.modules.animus:combat.targets.categories[0]");
        assertField("{\"targeting\":{\"lineOfSight\":\"xray\"}}", "$.modules.animus:combat.targeting.lineOfSight");
        assertField("{\"guard\":{\"player\":\"nope\"}}", "$.modules.animus:combat.guard.player");
        assertField("{\"guard\":{\"player\":\"00000000-0000-0000-0000-000000000001\",\"npc\":\"00000000-0000-0000-0000-000000000002\"}}", "$.modules.animus:combat.guard");
        assertField("{\"ranged\":{\"weapon\":\"laser\"}}", "$.modules.animus:combat.ranged.weapon");
        assertField("{\"unknown\":1}", "$.modules.animus:combat.unknown");
    }
    @Test void definitionsRejectInvalidCombatSettingsWithTheField() {
        IOException error = assertThrows(IOException.class, () -> NpcDefinition.parse(definition("{\"melee\":{\"reach\":100}}", "")));
        assertTrue(error.getMessage().contains("$.modules.animus:combat.melee.reach"));
    }
    @Test void enabledNeedsALiteralTrue() throws IOException {
        assertFalse(parse("{\"enabled\":\"true\"}").enabled());
        assertTrue(CombatSettings.active(NpcDefinition.parse(definition("{\"enabled\":\"true\"}", ""))).isEmpty());
        assertTrue(CombatSettings.active(NpcDefinition.parse(definition("{\"enabled\":true}", ""))).isPresent());
    }
    @Test void importedDefinitionsNeedAnAcceptedReview() throws IOException {
        String migration = ",\"migration\":{\"importId\":\"a\",\"citizensId\":1}";
        assertEquals(Optional.empty(), CombatSettings.active(NpcDefinition.parse(definition("{\"enabled\":true,\"reviewAccepted\":false}", migration))));
        assertEquals(Optional.empty(), CombatSettings.active(NpcDefinition.parse(definition("{\"enabled\":true,\"reviewAccepted\":\"true\"}", migration))));
        assertTrue(CombatSettings.active(NpcDefinition.parse(definition("{\"enabled\":true,\"reviewAccepted\":true}", migration))).isPresent());
    }
    @Test void importedHealthWaitsForTheCombatReview() throws IOException {
        String migration = ",\"migration\":{\"importId\":\"a\",\"citizensId\":1}";
        String waiting = definition("{\"enabled\":false,\"reviewAccepted\":false},\"animus:health\":{\"killable\":true}", migration);
        assertEquals(Optional.empty(), CombatSettings.active(NpcDefinition.parse(waiting)));
        String accepted = definition("{\"enabled\":false,\"reviewAccepted\":true},\"animus:health\":{\"killable\":true}", migration);
        assertTrue(CombatSettings.active(NpcDefinition.parse(accepted)).orElseThrow().passive());
    }
    @Test void anAbsentModuleIsNotActive() throws IOException {
        JsonObject plain = JsonParser.parseString(definition("{}", "")).getAsJsonObject();
        plain.getAsJsonObject("modules").remove("animus:combat");
        assertTrue(CombatSettings.active(NpcDefinition.parse(plain.toString())).isEmpty());
    }
    @Test void healthDeathAndRespawnAreNotCombatSettings() {
        assertField("{\"defense\":{\"maximumHealth\":40}}", "$.modules.animus:combat.defense: unknown setting");
        assertField("{\"death\":{\"experience\":5}}", "$.modules.animus:combat.death: unknown setting");
        assertField("{\"respawn\":{\"delayTicks\":100}}", "$.modules.animus:combat.respawn: unknown setting");
    }
    @Test void aCombatNpcWithoutHealthCannotBeHurt() throws IOException {
        CombatSettings settings = CombatSettings.active(NpcDefinition.parse(definition("{\"enabled\":true}", ""))).orElseThrow();
        assertTrue(settings.health().defense().invincible());
        assertFalse(settings.passive(), "It still fights");
    }
    @Test void reviewNotesAndPreservedDataAreAccepted() {
        assertDoesNotThrow(() -> parse("{\"enabled\":false,\"reviewAccepted\":false,\"preservedSettings\":{\"needsAmmo\":true},\"historicalStatistics\":{\"stats_punches\":1},\"migrationReport\":{\"notes\":[]}}"));
    }
}
