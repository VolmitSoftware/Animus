package art.arcane.animus.core.combat;

import art.arcane.animus.core.config.CombatBudgets;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * What viewers see of an attack: a swing on every melee hit or throw; a bow drawn, let go with each shot and drawn again;
 * a crossbow charged, held loaded and fired; a trident raised and thrown.
 */
class CombatAnimationTest {
    private static final String MELEE = "{\"targets\":{\"categories\":[\"monsters\"]},\"targeting\":{\"scanIntervalTicks\":1,\"scanRange\":16},\"melee\":{\"damage\":4,\"reach\":3,\"cooldownTicks\":20}}";
    private static final String RANGED = "{\"targets\":{\"categories\":[\"monsters\"]},\"targeting\":{\"scanIntervalTicks\":1,\"scanRange\":30},\"pursuit\":{\"giveUpRange\":40},"
            + "\"melee\":{\"enabled\":false},\"ranged\":{\"enabled\":true,\"weapon\":\"%s\",\"damage\":5,\"range\":30,\"cooldownTicks\":%d,\"velocity\":1.6}}";
    private final MockCombatWorld world = new MockCombatWorld();
    private CombatEngine engine;

    private CombatEngine engine() {
        if (engine == null) engine = new CombatEngine(CombatBudgets.DEFAULT, world, new ProtectionAdapter() {
            @Override public Decision decide(Request request) { return Decision.ALLOW; }
            @Override public Optional<Boolean> inRegion(String region, art.arcane.animus.core.virtual.WorldPosition position) { return Optional.empty(); }
        }, new Random(7), (String ignored) -> {});
        return engine;
    }
    private UUID combatant(String json, CombatLoadout loadout) {
        UUID npc = world.npc(0, 0, 20);
        JsonObject module = JsonParser.parseString(json).getAsJsonObject();
        module.addProperty("enabled", true);
        assertTrue(engine().register(npc, "npc-" + npc, CombatSettings.parseModule(module, CombatTesting.KILLABLE), loadout));
        return npc;
    }
    private UUID combatant(String json) { return combatant(json, CombatLoadout.NONE); }
    private static CombatLoadout holding(String item) { return new CombatLoadout(Optional.empty(), Map.of("main_hand", item), Map.of()); }
    private void run(int ticks) { for (int i = 0; i < ticks; i++) engine().tick(); }

    /** Runs the engine and returns what viewers were shown, each entry with the tick it happened on. */
    private List<String> timeline(int ticks) {
        List<String> events = new ArrayList<>();
        for (int tick = 0; tick < ticks; tick++) {
            int before = world.shown.size();
            engine().tick();
            for (String event : world.shown.subList(before, world.shown.size())) events.add(tick + " " + event);
        }
        return events;
    }
    private static int at(List<String> events, String event, int occurrence) {
        int seen = 0;
        for (String entry : events) {
            String[] parts = entry.split(" ", 2);
            if (parts[1].equals(event) && seen++ == occurrence) return Integer.parseInt(parts[0]);
        }
        return fail("no " + event + " #" + occurrence + " in " + events);
    }

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

    @Test void aBowIsDrawnInFullLetGoWithEachShotAndDrawnAgain() {
        UUID npc = combatant(RANGED.formatted("arrow", 40), holding("minecraft:bow"));
        UUID zombie = world.monster("minecraft:zombie", 10, 0);
        List<String> events = timeline(130);
        int drawn = at(events, "draw", 0), shot = at(events, "shot", 0);
        assertEquals(20, shot - drawn, "the first arrow flies after a full draw: " + events);
        assertEquals(shot, at(events, "lower", 0), "the bow is let go with the arrow");
        assertTrue(events.indexOf(shot + " shot") < events.indexOf(shot + " lower"), "the arrow leaves before the bow is lowered");
        assertEquals(shot + 20, at(events, "draw", 1), "it rests, then draws again so the next arrow comes on cooldown");
        assertEquals(shot + 40, at(events, "shot", 1));
        assertEquals(shot + 40, at(events, "lower", 1));
        assertEquals(shot + 80, at(events, "shot", 2));
        assertTrue(events.contains(shot + " sound minecraft:entity.arrow.shoot"), "each arrow sounds as it is let go");
        assertEquals(0, world.swings.getOrDefault(npc, 0), "an arrow is let go, not swung");
        world.real.remove(zombie);
        run(5);
        assertFalse(world.drawn.get(npc), "the bow is lowered once the target is gone");
    }

    @Test void aShortCooldownStillShowsEveryRelease() {
        combatant(RANGED.formatted("arrow", 1), holding("minecraft:bow"));
        world.monster("minecraft:zombie", 10, 0);
        List<String> events = timeline(80);
        int shot = at(events, "shot", 0);
        assertEquals(shot + DrawCycle.RELEASE_TICKS, at(events, "draw", 1), "lowered for a moment before the next draw");
        assertEquals(shot + DrawCycle.RELEASE_TICKS + 20, at(events, "shot", 1), "and drawn in full again");
    }

    @Test void aCrossbowIsChargedHeldLoadedAndAimedThenFiredAndChargedAgain() {
        UUID npc = combatant(RANGED.formatted("arrow", 40), holding("minecraft:crossbow"));
        world.monster("minecraft:zombie", 10, 0);
        List<String> events = timeline(110);
        int charging = at(events, "draw", 0);
        assertEquals(charging + 5, at(events, "sound minecraft:item.crossbow.loading_start", 0));
        assertEquals(charging + 13, at(events, "sound minecraft:item.crossbow.loading_middle", 0));
        int loaded = at(events, "sound minecraft:item.crossbow.loading_end", 0);
        assertEquals(charging + 25, loaded, "a crossbow charges for 25 ticks: " + events);
        assertEquals(loaded, at(events, "lower", 0), "charging stops once it is loaded");
        assertEquals(loaded, at(events, "hold minecraft:crossbow[minecraft:charged_projectiles=[{id:\"minecraft:arrow\",count:1}]]", 0),
                "viewers see the crossbow loaded, so the body holds it aimed");
        int fired = at(events, "shot", 0);
        assertEquals(loaded + DrawCycle.AIM_TICKS, fired, "held loaded and aimed before it fires");
        assertTrue(events.contains(fired + " sound minecraft:item.crossbow.shoot"));
        assertEquals(fired, at(events, "hold own", 0), "the shot empties the crossbow");
        assertEquals(fired + DrawCycle.RELEASE_TICKS, at(events, "draw", 1), "charged again after the shot");
        assertEquals(fired + 40, at(events, "shot", 1), "the loaded crossbow waits for the cooldown");
        assertEquals(0, world.swings.getOrDefault(npc, 0));
    }

    @Test void aLoadedCrossbowIsEmptiedWhenTheFightEnds() {
        UUID npc = combatant(RANGED.formatted("arrow", 40), holding("minecraft:crossbow"));
        UUID zombie = world.monster("minecraft:zombie", 10, 0);
        run(28);
        assertTrue(world.held.get(npc).orElseThrow().components().orElseThrow().contains("charged_projectiles"), "loaded");
        world.real.remove(zombie);
        run(5);
        assertEquals(Optional.empty(), world.held.get(npc), "its own empty crossbow again");
        assertFalse(world.drawn.get(npc));
    }

    @Test void aTridentIsRaisedThenThrown() {
        UUID npc = combatant(RANGED.formatted("trident", 40), holding("minecraft:trident"));
        world.monster("minecraft:zombie", 10, 0);
        List<String> events = timeline(60);
        int raised = at(events, "draw", 0), thrown = at(events, "shot", 0);
        assertEquals(10, thrown - raised, "raised for half a second: " + events);
        assertEquals(thrown, at(events, "lower", 0));
        assertTrue(events.contains(thrown + " sound minecraft:item.trident.throw"));
        assertEquals(RangedWeapon.TRIDENT, world.launched.get(world.launchOrder.getFirst()));
        assertEquals(0, world.swings.getOrDefault(npc, 0));
    }

    @Test void aThrowSwingsAndNeverDraws() {
        UUID npc = combatant(RANGED.formatted("snowball", 20));
        world.monster("minecraft:zombie", 10, 0);
        run(1);
        assertEquals(1, world.helperLaunches);
        assertEquals(1, world.swings.getOrDefault(npc, 0));
        assertFalse(world.drawn.getOrDefault(npc, false));
    }
}
