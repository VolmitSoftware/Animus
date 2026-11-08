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
        assertFalse(killable.defense().invincible());
        assertEquals(20, killable.defense().maximumHealth());
        assertEquals(CombatSettings.DamageSource.ALL, killable.defense().sources());
        assertFalse(parse("{\"animus:health\":{}}").enabled(), "Killable is off by default");
    }

    @Test void attributesFillHealthArmorAndKnockbackResistance() {
        CombatSettings settings = CombatSettings.parse(definition("{\"animus:health\":{\"killable\":true}}",
                "{\"max_health\":40,\"armor\":10,\"armor_toughness\":2,\"knockback_resistance\":0.5}")).orElseThrow();
        assertEquals(40, settings.defense().maximumHealth());
        assertEquals(10, settings.defense().armorPoints());
        assertEquals(2, settings.defense().armorToughness());
        assertEquals(.5, settings.defense().knockbackResistance());
        CombatSettings explicit = CombatSettings.parse(definition("{\"animus:health\":{\"killable\":true,\"maximumHealth\":8,\"armor\":0.25}}",
                "{\"max_health\":40,\"armor\":10}")).orElseThrow();
        assertEquals(8, explicit.defense().maximumHealth(), "An explicit value wins over the attribute");
        assertEquals(0, explicit.defense().armorPoints());
        assertEquals(.25, explicit.defense().armor());
    }

    @Test void everySettingIsRead() {
        CombatSettings settings = parse("""
                {"animus:health":{"killable":true,"maximumHealth":30,"invulnerableTicks":4,"knockback":false,"regenerate":true,"regenerateTicks":40,
                 "sources":["players","projectiles"],"attackerPermission":"town.hit","showHealth":true,
                 "death":{"drops":[{"item":{"id":"emerald","count":2,"components":"[rarity=epic]"},"maxCount":5,"chance":0.5}],"experience":9,"dropEquipment":true},
                 "respawn":{"delayTicks":40,"points":[{"world":"world","x":1,"y":64,"z":2}],"order":"nearest"}}}""");
        assertEquals(30, settings.defense().maximumHealth());
        assertEquals(4, settings.defense().invulnerableTicks());
        assertFalse(settings.defense().knockback());
        assertEquals(40, settings.defense().healRateTicks());
        assertEquals(Set.of(CombatSettings.DamageSource.PLAYERS, CombatSettings.DamageSource.PROJECTILES), settings.defense().sources());
        assertEquals(Optional.of("town.hit"), settings.defense().attackerPermission());
        assertTrue(settings.defense().showHealth());
        assertEquals(List.of(new CombatSettings.DropRule("minecraft:emerald", 2, 5, Optional.of("[rarity=epic]"), .5)), settings.death().drops());
        assertEquals(9, settings.death().experience());
        assertTrue(settings.death().dropEquipment());
        assertEquals(40, settings.respawn().delayTicks());
        assertEquals(CombatSettings.Order.NEAREST, settings.respawn().order());
        assertTrue(settings.respawn().home());
        assertFalse(settings.respawn().permadeath());
    }

    @Test void regenerationIsOffUnlessTurnedOn() {
        assertEquals(0, parse("{\"animus:health\":{\"killable\":true,\"regenerateTicks\":40}}").defense().healRateTicks());
    }

    @Test void permadeathNeverRespawnsAndADeadDefinitionDoesNotRun() {
        CombatSettings settings = parse("{\"animus:health\":{\"killable\":true,\"respawn\":{\"afterDeath\":\"permadeath\"}}}");
        assertTrue(settings.respawn().permadeath());
        assertEquals(-1, settings.respawn().delayTicks());
        JsonObject dead = definition("{\"animus:health\":{\"killable\":true,\"dead\":true}}", null);
        assertTrue(CombatSettings.parse(dead).orElseThrow().respawn().dead());
        assertTrue(HealthSettings.dead(dead));
        assertFalse(HealthSettings.dead(definition("{\"animus:health\":{\"killable\":true}}", null)));
    }

    @Test void onACombatNpcHealthReplacesDefenseDeathAndRespawn() {
        CombatSettings settings = parse("{\"animus:combat\":{\"enabled\":true,\"targets\":{\"categories\":[\"monsters\"]}},\"animus:health\":{\"killable\":true,\"maximumHealth\":50}}");
        assertFalse(settings.passive(), "Combat still fights");
        assertEquals(50, settings.defense().maximumHealth());
        CombatSettings unkillable = parse("{\"animus:combat\":{\"enabled\":true},\"animus:health\":{\"killable\":false}}");
        assertTrue(unkillable.defense().invincible(), "A combat NPC that is not killable cannot be hurt");
        IllegalArgumentException both = assertThrows(IllegalArgumentException.class,
                () -> parse("{\"animus:combat\":{\"enabled\":true,\"defense\":{\"maximumHealth\":5}},\"animus:health\":{\"killable\":true}}"));
        assertEquals("$.modules.animus:combat.defense: set by animus:health on this NPC; remove one of them", both.getMessage());
    }

    @Test void aCombatNpcWithoutHealthAcceptsEverySource() {
        CombatSettings settings = parse("{\"animus:combat\":{\"enabled\":true}}");
        assertEquals(CombatSettings.DamageSource.ALL, settings.defense().sources());
        assertFalse(settings.passive());
        assertFalse(settings.respawn().home(), "Combat respawns where the NPC fell");
    }

    @Test void mistakesNameTheField() {
        assertEquals("$.modules.animus:health.sources[0]: expected players, mobs, npcs or projectiles",
                assertThrows(IllegalArgumentException.class, () -> parse("{\"animus:health\":{\"sources\":[\"fall\"]}}")).getMessage());
        assertEquals("$.modules.animus:health.respawn.afterDeath: expected respawn or permadeath",
                assertThrows(IllegalArgumentException.class, () -> parse("{\"animus:health\":{\"respawn\":{\"afterDeath\":\"never\"}}}")).getMessage());
        assertEquals("$.modules.animus:health.death.drops[0].maxCount: must be within 3..99",
                assertThrows(IllegalArgumentException.class, () -> parse("{\"animus:health\":{\"death\":{\"drops\":[{\"item\":{\"id\":\"stone\",\"count\":3},\"maxCount\":2}]}}}")).getMessage().replace(".0", ""));
        assertEquals("$.modules.animus:health.health: unknown setting",
                assertThrows(IllegalArgumentException.class, () -> parse("{\"animus:health\":{\"health\":3}}")).getMessage());
    }
}
