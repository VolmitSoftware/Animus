package art.arcane.animus.core.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HealthSettingsTest {
    static JsonObject definition(String modules, String attributes) {
        JsonObject definition = new JsonObject();
        definition.addProperty("type", "minecraft:villager");
        definition.add("modules", JsonParser.parseString(modules));
        if (attributes != null) definition.add("attributes", JsonParser.parseString(attributes));
        return definition;
    }
    static CombatSettings parse(String modules) { return CombatSettings.parse(definition(modules, null)).orElseThrow(); }

    @Test void aHealthModuleAloneIsPassiveAndRunsOnlyWhenKillable() {
        CombatSettings killable = parse("{\"animus:health\":{\"killable\":true}}");
        assertTrue(killable.passive());
        assertTrue(killable.enabled());
        assertFalse(killable.health().defense().invincible());
        assertEquals(20, killable.health().defense().maximumHealth());
        assertEquals(HealthSettings.DamageSource.ATTACKS, killable.health().defense().sources());
        assertFalse(parse("{\"animus:health\":{}}").enabled(), "Killable is off by default");
    }

    @Test void attributesFillHealthArmorAndKnockbackResistance() {
        CombatSettings settings = CombatSettings.parse(definition("{\"animus:health\":{\"killable\":true}}",
                "{\"max_health\":40,\"armor\":10,\"armor_toughness\":2,\"knockback_resistance\":0.5}")).orElseThrow();
        assertEquals(40, settings.health().defense().maximumHealth());
        assertEquals(10, settings.health().defense().armorPoints());
        assertEquals(2, settings.health().defense().armorToughness());
        assertEquals(.5, settings.health().defense().knockbackResistance());
        CombatSettings explicit = CombatSettings.parse(definition("{\"animus:health\":{\"killable\":true,\"maximumHealth\":8,\"armor\":0.25}}",
                "{\"max_health\":40,\"armor\":10}")).orElseThrow();
        assertEquals(8, explicit.health().defense().maximumHealth(), "An explicit value wins over the attribute");
        assertEquals(0, explicit.health().defense().armorPoints());
        assertEquals(.25, explicit.health().defense().armor());
    }

    @Test void everySettingIsRead() {
        CombatSettings settings = parse("""
                {"animus:health":{"killable":true,"maximumHealth":30,"invulnerableTicks":4,"knockback":false,"regenerate":true,"regenerateTicks":40,
                 "sources":["players","projectiles"],"attackerPermission":"town.hit","showHealth":true,
                 "death":{"drops":[{"item":{"id":"emerald","count":2,"components":"[rarity=epic]"},"maxCount":5,"chance":0.5}],"experience":9,"dropEquipment":true},
                 "respawn":{"delayTicks":40,"points":[{"world":"world","x":1,"y":64,"z":2}],"order":"nearest"}}}""");
        assertEquals(30, settings.health().defense().maximumHealth());
        assertEquals(4, settings.health().defense().invulnerableTicks());
        assertFalse(settings.health().defense().knockback());
        assertEquals(40, settings.health().defense().healRateTicks());
        assertEquals(Set.of(HealthSettings.DamageSource.PLAYERS, HealthSettings.DamageSource.PROJECTILES), settings.health().defense().sources());
        assertEquals(Optional.of("town.hit"), settings.health().defense().attackerPermission());
        assertTrue(settings.health().defense().showHealth());
        assertEquals(List.of(new HealthSettings.DropRule("minecraft:emerald", 2, 5, Optional.of("[rarity=epic]"), .5)), settings.health().death().drops());
        assertEquals(9, settings.health().death().experience());
        assertTrue(settings.health().death().dropEquipment());
        assertEquals(40, settings.health().respawn().delayTicks());
        assertEquals(HealthSettings.Order.NEAREST, settings.health().respawn().order());
        assertFalse(settings.health().respawn().permadeath());
    }

    @Test void regenerationIsOffUnlessTurnedOn() {
        assertEquals(0, parse("{\"animus:health\":{\"killable\":true,\"regenerateTicks\":40}}").health().defense().healRateTicks());
    }

    @Test void permadeathNeverRespawnsAndADeadDefinitionDoesNotRun() {
        CombatSettings settings = parse("{\"animus:health\":{\"killable\":true,\"respawn\":{\"afterDeath\":\"permadeath\"}}}");
        assertTrue(settings.health().respawn().permadeath());
        assertEquals(-1, settings.health().respawn().delayTicks());
        JsonObject dead = definition("{\"animus:health\":{\"killable\":true,\"dead\":true}}", null);
        assertTrue(CombatSettings.parse(dead).orElseThrow().health().respawn().dead());
        assertTrue(HealthSettings.dead(dead));
        assertFalse(HealthSettings.dead(definition("{\"animus:health\":{\"killable\":true}}", null)));
    }

    @Test void onACombatNpcHealthSuppliesDefenseDeathAndRespawn() {
        CombatSettings settings = parse("{\"animus:combat\":{\"enabled\":true,\"targets\":{\"categories\":[\"monsters\"]}},\"animus:health\":{\"killable\":true,\"maximumHealth\":50}}");
        assertFalse(settings.passive(), "Combat still fights");
        assertEquals(50, settings.health().defense().maximumHealth());
        CombatSettings unkillable = parse("{\"animus:combat\":{\"enabled\":true},\"animus:health\":{\"killable\":false}}");
        assertTrue(unkillable.health().defense().invincible(), "A combat NPC that is not killable cannot be hurt");
        IllegalArgumentException combatDefense = assertThrows(IllegalArgumentException.class,
                () -> parse("{\"animus:combat\":{\"enabled\":true,\"defense\":{\"maximumHealth\":5}},\"animus:health\":{\"killable\":true}}"));
        assertEquals("$.modules.animus:combat.defense: unknown setting", combatDefense.getMessage());
    }

    @Test void aCombatNpcWithoutHealthCannotBeHurt() {
        CombatSettings settings = parse("{\"animus:combat\":{\"enabled\":true}}");
        assertTrue(settings.health().defense().invincible());
        assertFalse(settings.health().killable());
        assertFalse(settings.passive());
    }

    @Test void protectionRangeIgnoredAttackersAndDeletionAreHealthSettings() {
        CombatSettings settings = parse("{\"animus:health\":{\"killable\":true,\"protectFromRange\":12,\"protectedFromIgnored\":true,\"respawn\":{\"afterDeath\":\"delete\"}}}");
        assertEquals(12, settings.health().defense().protectFromRange());
        assertTrue(settings.health().defense().protectedFromIgnored());
        assertTrue(settings.health().respawn().deleteOnDeath());
        assertEquals(-1, settings.health().respawn().delayTicks(), "A deleted NPC never respawns");
        assertEquals("$.modules.animus:health.protectFromRange: must be within 0.0..256.0",
                assertThrows(IllegalArgumentException.class, () -> parse("{\"animus:health\":{\"protectFromRange\":300}}")).getMessage());
    }

    @Test void mistakesNameTheField() {
        assertEquals("$.modules.animus:health.sources[0]: expected players, mobs, npcs, projectiles, fire, lava or fall",
                assertThrows(IllegalArgumentException.class, () -> parse("{\"animus:health\":{\"sources\":[\"drowning\"]}}")).getMessage());
        assertEquals("$.modules.animus:health.respawn.afterDeath: expected respawn, permadeath or delete",
                assertThrows(IllegalArgumentException.class, () -> parse("{\"animus:health\":{\"respawn\":{\"afterDeath\":\"never\"}}}")).getMessage());
        assertEquals("$.modules.animus:health.death.drops[0].maxCount: must be within 3..99",
                assertThrows(IllegalArgumentException.class, () -> parse("{\"animus:health\":{\"death\":{\"drops\":[{\"item\":{\"id\":\"stone\",\"count\":3},\"maxCount\":2}]}}}")).getMessage().replace(".0", ""));
        assertEquals("$.modules.animus:health.health: unknown setting",
                assertThrows(IllegalArgumentException.class, () -> parse("{\"animus:health\":{\"health\":3}}")).getMessage());
    }
}
