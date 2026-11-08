package art.arcane.animus.core.combat;

import art.arcane.animus.core.npc.NpcAttributes;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CombatAttributesTest {
    private final MockCombatWorld world = new MockCombatWorld();

    private static CombatSettings settings(String module, String attributes) { return settings(module, "{\"killable\":true}", attributes); }
    private static CombatSettings settings(String module, String health, String attributes) {
        JsonObject definition = JsonParser.parseString("{\"type\":\"minecraft:player\",\"modules\":{\"animus:combat\":" + module + ",\"animus:health\":" + health + "}}").getAsJsonObject();
        definition.getAsJsonObject("modules").getAsJsonObject("animus:combat").addProperty("enabled", true);
        definition.add("attributes", JsonParser.parseString(attributes));
        return CombatSettings.parse(definition).orElseThrow();
    }

    @Test void attributesFillCombatSettingsTheModuleLeavesOut() {
        CombatSettings settings = settings("{}", """
                {"max_health":40,"attack_damage":7,"attack_knockback":2,"attack_speed":2,"follow_range":48,"movement_speed":0.1}
                """);
        assertEquals(40, settings.health().defense().maximumHealth());
        assertEquals(7, settings.melee().damage());
        assertEquals(0.4 + 2 * 0.5, settings.melee().knockback(), 1e-9);
        assertEquals(10, settings.melee().cooldownTicks(), "two hits a second");
        assertEquals(48, settings.targeting().scanRange());
        assertEquals(4.317, settings.pursuit().speed(), 1e-9, "the chase runs at the walking speed");
    }

    @Test void theModulesWinOverAttributes() {
        CombatSettings settings = settings("""
                {"melee":{"damage":3,"knockback":1,"cooldownTicks":30},"targeting":{"scanRange":12},"pursuit":{"speed":2}}
                """, "{\"killable\":true,\"maximumHealth\":30,\"armor\":0.25}", "{\"max_health\":40,\"attack_damage\":7,\"attack_knockback\":2,\"attack_speed\":2,\"follow_range\":48,\"armor\":20,\"movement_speed\":0.2}");
        assertEquals(30, settings.health().defense().maximumHealth());
        assertEquals(3, settings.melee().damage());
        assertEquals(1, settings.melee().knockback());
        assertEquals(30, settings.melee().cooldownTicks());
        assertEquals(12, settings.targeting().scanRange());
        assertEquals(2, settings.pursuit().speed());
        assertEquals(0.25, settings.health().defense().armor());
        assertEquals(0, settings.health().defense().armorPoints(), "an explicit armor share ignores the armor attribute");
    }

    @Test void valuesBeyondTheCombatRangesAreKeptWithinThem() {
        CombatSettings settings = settings("{}", "{\"follow_range\":2048,\"attack_speed\":0,\"attack_damage\":500,\"attack_knockback\":5}");
        assertEquals(256, settings.targeting().scanRange());
        assertEquals(1200, settings.melee().cooldownTicks());
        assertEquals(100, settings.melee().damage());
        assertEquals(2.9, settings.melee().knockback(), 1e-9);
    }

    @Test void armorPointsReduceDamageAsVanillaArmorDoes() {
        assertEquals(4, CombatAttributes.armored(10, 20, 0), 1e-9, "20 armor against a 10 hit blocks 20 - 10/2 = 15 of 25 parts");
        assertEquals(10 * (1 - 0.8 / 25), CombatAttributes.armored(10, 4, 0), 1e-9, "a weak armor still blocks a fifth of its points");
        assertEquals(10 * (1 - 20 / 25.0), CombatAttributes.armored(10, 30, 8), 1e-9, "at most 20 points count");
    }

    @Test void armorAndToughnessAndKnockbackResistanceApplyInAFight() {
        CombatSettings armored = settings("{}", "{\"killable\":true,\"invulnerableTicks\":0}", "{\"armor\":20,\"armor_toughness\":8,\"knockback_resistance\":0.5}");
        assertEquals(20, armored.health().defense().armorPoints());
        assertEquals(8, armored.health().defense().armorToughness());
        assertEquals(0.5, armored.health().defense().knockbackResistance());
        CombatEngine engine = new CombatEngine(art.arcane.animus.core.config.CombatBudgets.DEFAULT, world, ProtectionAdapter.NONE, new java.util.Random(7), (String message) -> {});
        UUID npc = world.npc(0, 0, 20);
        assertTrue(engine.register(npc, "armored", armored, CombatLoadout.NONE));
        engine.playerAttack(npc, new TargetRef(TargetKind.PLAYER, UUID.randomUUID()), MockCombatWorld.at(1, 64, 0), 10);
        assertEquals(20 - CombatAttributes.armored(10, 20, 8), world.npc(npc).orElseThrow().health(), 1e-9);
        assertEquals(0.2, world.knocked.get(npc), 1e-9, "half of the 0.4 a player's hit knocks back");
    }

    @Test void attributeNotesNameTheFieldsThatWin() {
        assertTrue(NpcAttributes.note("max_health").contains("animus:health sets maximumHealth"));
        assertTrue(NpcAttributes.note("follow_range").contains("targeting.scanRange"));
    }
}
