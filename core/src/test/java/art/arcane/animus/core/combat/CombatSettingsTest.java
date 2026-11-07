package art.arcane.animus.core.combat;

import art.arcane.animus.core.npc.NpcDefinition;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CombatSettingsTest {
    private static JsonObject module(String json) { return JsonParser.parseString(json).getAsJsonObject(); }
    private static String definition(String combat, String extra) {
        return "{\"schemaVersion\":1,\"revision\":1,\"uuid\":\"" + new UUID(0, 1) + "\",\"id\":\"guard\",\"group\":\"town\",\"name\":\"Guard\",\"type\":\"minecraft:player\",\"spawned\":false,"
                + "\"position\":{\"world\":\"world\",\"x\":0,\"y\":64,\"z\":0,\"yaw\":0,\"pitch\":0},\"modules\":{\"animus:combat\":" + combat + "}" + extra + "}";
    }
    private static void assertField(String json, String field) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> CombatSettings.parseModule(module(json)));
        assertTrue(error.getMessage().startsWith(field), error.getMessage());
    }

    @Test void defaultsAreSafeAndTargetNothing() {
        CombatSettings settings = CombatSettings.parseModule(module("{\"enabled\":true}"));
        assertTrue(settings.enabled());
        assertEquals(Set.of(), settings.targets().categories());
        assertEquals(20, settings.defense().maximumHealth());
        assertFalse(settings.ranged().enabled());
        assertTrue(settings.melee().enabled());
        assertEquals(CombatSettings.LineOfSight.REQUIRED, settings.targeting().lineOfSight());
        assertFalse(settings.targetableByMobs());
    }
    @Test void mobsTargetTheNpcOnlyWhenItOptsIn() {
        assertTrue(CombatSettings.parseModule(module("{\"enabled\":true,\"targetableByMobs\":true}")).targetableByMobs());
        assertField("{\"targetableByMobs\":\"yes\"}", "$.modules.animus:combat.targetableByMobs");
    }
    @Test void aFullDocumentParses() {
        CombatSettings settings = CombatSettings.parseModule(module("""
                {"enabled":true,"targets":{"categories":["monsters"],"entityTypes":["minecraft:pillager"],"players":["Alice"],"npcs":["bandit"],"names":["Bob"],"squads":["raiders"]},
                 "ignores":{"categories":["animals"]},"avoids":{"entityTypes":["minecraft:creeper"]},
                 "targeting":{"scanRange":20,"scanIntervalTicks":10,"reactionTicks":5,"memoryTicks":100,"retainTarget":true,"fightBack":false,"lineOfSight":"field_of_view"},
                 "avoidance":{"range":6,"fleeWhenAttacked":true,"returnPoint":{"world":"world","x":1,"y":64,"z":2}},
                 "squad":{"name":"town","alertRange":24},
                 "guard":{"player":"00000000-0000-0000-0000-000000000001","followDistance":7,"pointRange":4},
                 "pursuit":{"closeChase":true,"rangedChase":true,"speed":4,"giveUpRange":40,"leashRange":64,"returnToPost":true,"teleportWhenStuck":true,"replanTicks":20},
                 "melee":{"damage":4,"reach":3,"cooldownTicks":20,"knockback":0.4},
                 "ranged":{"enabled":true,"weapon":"arrow","damage":3,"range":24,"minimumRange":4,"cooldownTicks":40,"velocity":1.6,"inaccuracy":3,"safeShot":true,"knockback":0.2},
                 "defense":{"maximumHealth":40,"armor":0.2,"invincible":false,"knockback":true,"protectedFromIgnored":true,"protectFromRange":30,"healRateTicks":100,"invulnerableTicks":10},
                 "death":{"drops":[{"item":"minecraft:iron_ingot","amount":2,"chance":0.5}],"experience":5,"victimDrops":false},
                 "respawn":{"delayTicks":100,"locations":[{"world":"world","x":0,"y":64,"z":0}]}}"""));
        assertEquals(CombatSettings.LineOfSight.FIELD_OF_VIEW, settings.targeting().lineOfSight());
        assertEquals("town", settings.squad().name().orElseThrow());
        assertEquals(40, settings.defense().maximumHealth());
        assertEquals(List.of(new CombatSettings.DropRule("minecraft:iron_ingot", 2, .5)), settings.death().drops());
        assertEquals(new UUID(0, 1), settings.guard().player().orElseThrow());
    }
    @Test void bareRegistryIdsAreMinecraftIds() {
        CombatSettings settings = CombatSettings.parseModule(module("""
                {"enabled":true,"targets":{"entityTypes":["pillager"],"effects":["glowing"]},
                 "melee":{"weaponDamage":{"netherite_axe":9,"minecraft:iron_sword":6}},
                 "ranged":{"enabled":true,"weapon":"potion","effect":{"id":"poison"}},
                 "death":{"drops":[{"item":"iron_ingot"}]}}"""));
        assertEquals(Set.of("minecraft:pillager"), settings.targets().entityTypes());
        assertEquals(Set.of("minecraft:glowing"), settings.targets().effects());
        assertEquals(Map.of("minecraft:netherite_axe", 9.0, "minecraft:iron_sword", 6.0), settings.melee().weaponDamage());
        assertEquals("minecraft:poison", settings.ranged().effect().orElseThrow().id());
        assertEquals(List.of(new CombatSettings.DropRule("minecraft:iron_ingot", 1, 1)), settings.death().drops());
        assertField("{\"death\":{\"drops\":[{\"item\":\":iron\"}]}}", "$.modules.animus:combat.death.drops[0].item");
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
        assertField("{\"death\":{\"drops\":[{\"item\":\"iron\",\"chance\":2}]}}", "$.modules.animus:combat.death.drops[0]");
        assertField("{\"respawn\":{\"delayTicks\":-2}}", "$.modules.animus:combat.respawn.delayTicks");
        assertField("{\"unknown\":1}", "$.modules.animus:combat.unknown");
    }
    @Test void definitionsRejectInvalidCombatSettingsWithTheField() {
        IOException error = assertThrows(IOException.class, () -> NpcDefinition.parse(definition("{\"melee\":{\"reach\":100}}", "")));
        assertTrue(error.getMessage().contains("$.modules.animus:combat.melee.reach"));
    }
    @Test void enabledNeedsALiteralTrue() throws IOException {
        assertFalse(CombatSettings.parseModule(module("{\"enabled\":\"true\"}")).enabled());
        assertTrue(CombatSettings.active(NpcDefinition.parse(definition("{\"enabled\":\"true\"}", ""))).isEmpty());
        assertTrue(CombatSettings.active(NpcDefinition.parse(definition("{\"enabled\":true}", ""))).isPresent());
    }
    @Test void importedDefinitionsNeedAnAcceptedReview() throws IOException {
        String migration = ",\"migration\":{\"importId\":\"a\",\"citizensId\":1}";
        assertEquals(Optional.empty(), CombatSettings.active(NpcDefinition.parse(definition("{\"enabled\":true,\"reviewAccepted\":false}", migration))));
        assertEquals(Optional.empty(), CombatSettings.active(NpcDefinition.parse(definition("{\"enabled\":true,\"reviewAccepted\":\"true\"}", migration))));
        assertTrue(CombatSettings.active(NpcDefinition.parse(definition("{\"enabled\":true,\"reviewAccepted\":true}", migration))).isPresent());
    }
    @Test void anAbsentModuleIsNotActive() throws IOException {
        JsonObject plain = JsonParser.parseString(definition("{}", "")).getAsJsonObject();
        plain.getAsJsonObject("modules").remove("animus:combat");
        assertTrue(CombatSettings.active(NpcDefinition.parse(plain.toString())).isEmpty());
    }
    @Test void reviewNotesAndPreservedDataAreAccepted() {
        assertDoesNotThrow(() -> CombatSettings.parseModule(module("{\"enabled\":false,\"reviewAccepted\":false,\"preservedSettings\":{\"needsAmmo\":true},\"historicalStatistics\":{\"stats_punches\":1},\"migrationReport\":{\"notes\":[]}}")));
    }
}
