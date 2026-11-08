package art.arcane.animus.core.combat;

import art.arcane.animus.core.config.CombatBudgets;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** What viewers see of an attack: a swing on every melee hit or throw, and a drawn bow while a bow fight lasts. */
class CombatAnimationTest {
    private static final String MELEE = "{\"targets\":{\"categories\":[\"monsters\"]},\"targeting\":{\"scanIntervalTicks\":1,\"scanRange\":16},\"melee\":{\"damage\":4,\"reach\":3,\"cooldownTicks\":20}}";
    private static final String RANGED = "{\"targets\":{\"categories\":[\"monsters\"]},\"targeting\":{\"scanIntervalTicks\":1,\"scanRange\":30},\"pursuit\":{\"giveUpRange\":40},"
            + "\"melee\":{\"enabled\":false},\"ranged\":{\"enabled\":true,\"weapon\":\"%s\",\"damage\":5,\"range\":30,\"cooldownTicks\":20,\"velocity\":1.6}}";
    private final MockCombatWorld world = new MockCombatWorld();
    private CombatEngine engine;

    private CombatEngine engine() {
        if (engine == null) engine = new CombatEngine(CombatBudgets.DEFAULT, world, new ProtectionAdapter() {
            @Override public Decision decide(Request request) { return Decision.ALLOW; }
            @Override public Optional<Boolean> inRegion(String region, art.arcane.animus.core.virtual.WorldPosition position) { return Optional.empty(); }
        }, new Random(7), (String ignored) -> {});
        return engine;
    }
    private UUID combatant(String json) {
        UUID npc = world.npc(0, 0, 20);
        JsonObject module = JsonParser.parseString(json).getAsJsonObject();
        module.addProperty("enabled", true);
        assertTrue(engine().register(npc, "npc-" + npc, CombatSettings.parseModule(module, CombatTesting.KILLABLE), CombatLoadout.NONE));
        return npc;
    }
    private void run(int ticks) { for (int i = 0; i < ticks; i++) engine().tick(); }

    @Test void everyMeleeHitSwingsTheArm() {
        UUID npc = combatant(MELEE);
        UUID zombie = world.monster("minecraft:zombie", 2, 0);
        run(1);
        assertEquals(4, world.realDamage.get(zombie));
        assertEquals(1, world.swings.getOrDefault(npc, 0));
        run(20);
        assertEquals(8, world.realDamage.get(zombie));
        assertEquals(2, world.swings.getOrDefault(npc, 0), "one swing per hit");
        assertFalse(world.drawn.getOrDefault(npc, false), "a melee fighter never draws");
    }

    @Test void aBowFighterDrawsWhileTheFightLastsAndLowersAfter() {
        UUID npc = combatant(RANGED.formatted("arrow"));
        UUID zombie = world.monster("minecraft:zombie", 10, 0);
        run(1);
        assertEquals(1, world.helperLaunches);
        assertTrue(world.drawn.getOrDefault(npc, false), "the bow is drawn while the target is in range");
        assertEquals(0, world.swings.getOrDefault(npc, 0), "an arrow is released, not swung");
        run(25);
        assertEquals(1, world.drawChanges, "the draw is held between shots, not sent again");
        world.real.remove(zombie);
        run(5);
        assertFalse(world.drawn.get(npc), "the bow is lowered once the target is gone");
    }

    @Test void aThrowSwingsAndNeverDraws() {
        UUID npc = combatant(RANGED.formatted("snowball"));
        world.monster("minecraft:zombie", 10, 0);
        run(1);
        assertEquals(1, world.helperLaunches);
        assertEquals(1, world.swings.getOrDefault(npc, 0));
        assertFalse(world.drawn.getOrDefault(npc, false));
    }
}
