package art.arcane.animus.core.combat;

import art.arcane.animus.core.config.CombatBudgets;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Hotloaded {@code [combat]} budgets: they apply on the engine's next tick, and lowered caps drain without cutting fights short. */
class CombatLiveBudgetsTest {
    private static final String HUNT_MONSTERS = "{\"targets\":{\"categories\":[\"monsters\"]},\"targeting\":{\"scanIntervalTicks\":1,\"scanRange\":16},\"melee\":{\"damage\":1,\"reach\":3,\"cooldownTicks\":20}}";
    private static final String ARCHER = "{\"targets\":{\"categories\":[\"monsters\"]},\"targeting\":{\"scanIntervalTicks\":1,\"scanRange\":30},\"pursuit\":{\"giveUpRange\":40},\"melee\":{\"enabled\":false},\"ranged\":{\"enabled\":true,\"damage\":1,\"range\":30,\"cooldownTicks\":1,\"velocity\":1.6}}";
    private final MockCombatWorld world = new MockCombatWorld();
    private final List<String> diagnostics = new ArrayList<>();
    private CombatEngine engine;

    private static CombatBudgets budgets(int maximumNpcs, int maximumHelpers, int helpersPerNpc, int helperLifetimeTicks, int maximumScanRange) {
        return new CombatBudgets(true, maximumNpcs, 16, 8, 32, 32, 8, 4, maximumHelpers, helpersPerNpc, 32, helperLifetimeTicks, 40, 8, maximumScanRange, 8);
    }
    private void start(CombatBudgets budgets) { engine = new CombatEngine(budgets, world, ProtectionAdapter.NONE, new Random(7), diagnostics::add); }
    private static CombatSettings settings(String json) {
        JsonObject module = JsonParser.parseString(json).getAsJsonObject();
        module.addProperty("enabled", true);
        return CombatSettings.parseModule(module, CombatTesting.KILLABLE);
    }
    private UUID combatant(double x, double z, String json) {
        UUID npc = world.npc(x, z, 20);
        assertTrue(engine.register(npc, "npc-" + npc, settings(json), CombatLoadout.NONE));
        return npc;
    }
    private void run(int ticks) { for (int i = 0; i < ticks; i++) engine.tick(); }
    private boolean fighting(UUID npc) { return engine.status(npc).orElseThrow().target().isPresent(); }

    @Test void raisingMaximumNpcsGrantsWaitingNpcsTheirSlots() {
        start(budgets(1, 64, 4, 100, 100));
        UUID first = combatant(0, 0, HUNT_MONSTERS);
        UUID second = combatant(100, 0, HUNT_MONSTERS);
        UUID third = combatant(200, 0, HUNT_MONSTERS);
        world.monster("minecraft:zombie", 2, 0);
        world.monster("minecraft:zombie", 102, 0);
        world.monster("minecraft:zombie", 202, 0);
        run(3);
        assertEquals(1, engine.activeCount());
        assertEquals(2, engine.waitingCount());

        engine.reconfigure(budgets(3, 64, 4, 100, 100));
        assertEquals(3, engine.budgets().maximumNpcs());
        run(3);
        assertEquals(0, engine.waitingCount());
        assertTrue(fighting(first) && fighting(second) && fighting(third));
        assertTrue(engine.capacity().startsWith("fighting-or-scanning=3/3"), engine.capacity());
    }

    @Test void loweringMaximumNpcsBelowActiveFightsDrainsWithoutCuttingThemShort() {
        start(budgets(3, 64, 4, 100, 100));
        UUID first = combatant(0, 0, HUNT_MONSTERS);
        UUID second = combatant(100, 0, HUNT_MONSTERS);
        UUID third = combatant(200, 0, HUNT_MONSTERS);
        UUID firstZombie = world.monster("minecraft:zombie", 2, 0);
        UUID secondZombie = world.monster("minecraft:zombie", 102, 0);
        UUID thirdZombie = world.monster("minecraft:zombie", 202, 0);
        run(3);
        assertEquals(3, engine.activeCount());

        engine.reconfigure(budgets(1, 64, 4, 100, 100));
        UUID late = combatant(300, 0, HUNT_MONSTERS);
        world.monster("minecraft:zombie", 302, 0);
        run(5);
        assertTrue(fighting(first) && fighting(second) && fighting(third), "Fights in progress keep their slots");
        assertEquals(3, engine.activeCount());
        assertFalse(fighting(late), "A newcomer waits while usage is above the lowered cap");
        assertEquals(1, engine.waitingCount());

        world.real.remove(firstZombie);
        world.real.remove(secondZombie);
        run(60);
        assertEquals(1, engine.activeCount(), "Released slots are not handed on while usage is at the cap");
        assertFalse(fighting(late));

        world.real.remove(thirdZombie);
        run(60);
        assertTrue(fighting(late), "The newcomer gets the slot once usage drops below the cap");
    }

    @Test void loweringHelperCapsLetsFlyingHelpersLiveOutTheirLifetimeButLaunchesNoMore() {
        start(budgets(64, 8, 8, 40, 100));
        combatant(0, 0, ARCHER);
        world.monster("minecraft:zombie", 10, 0);
        run(4);
        int flying = engine.helperCount();
        assertTrue(flying >= 2, "Several helpers in flight: " + flying);
        int launches = world.helperLaunches;

        engine.reconfigure(budgets(64, 1, 1, 40, 100));
        run(10);
        assertEquals(launches, world.helperLaunches, "No helper launches while the count is above the lowered cap");
        assertEquals(flying, engine.helperCount(), "Helpers in flight are not removed early");
        assertTrue(world.removedHelpers.isEmpty());

        run(40);
        assertTrue(world.helperLaunches > launches, "Launching resumes once the old helpers expired");
        assertTrue(engine.helperCount() <= 1);
    }

    @Test void helperLifetimeAppliesToHelpersLaunchedAfterTheChange() {
        start(budgets(64, 1, 1, 200, 100));
        combatant(0, 0, ARCHER);
        world.monster("minecraft:zombie", 10, 0);
        engine.reconfigure(budgets(64, 1, 1, 5, 100));
        run(2);
        assertEquals(1, world.helperLaunches);
        run(10);
        assertFalse(world.removedHelpers.isEmpty(), "The shorter lifetime retired the helper");
    }

    @Test void maximumScanRangeClampsAtTheNextScan() {
        start(budgets(64, 64, 4, 100, 100));
        UUID npc = combatant(0, 0, ARCHER);
        engine.reconfigure(budgets(64, 64, 4, 100, 16));
        world.monster("minecraft:zombie", 20, 0);
        run(5);
        assertFalse(fighting(npc), "A zombie at 20 blocks is beyond the lowered maximum scan range");
        engine.reconfigure(budgets(64, 64, 4, 100, 100));
        run(5);
        assertTrue(fighting(npc));
    }

    @Test void stoppingReleasesTargetsAndLetsHelpersExpireThroughTheNormalPath() {
        start(budgets(64, 8, 8, 20, 100));
        UUID archer = combatant(0, 0, ARCHER);
        world.monster("minecraft:zombie", 10, 0);
        run(3);
        assertTrue(fighting(archer));
        int flying = engine.helperCount();
        assertTrue(flying > 0);
        int launches = world.helperLaunches;

        engine.stop();
        assertTrue(engine.stopping());
        assertFalse(engine.drained(), "Helpers are still in flight");
        assertFalse(fighting(archer), "Stopping releases the target");
        assertEquals(0, engine.activeCount());
        assertEquals(CombatOperations.Status.UNAVAILABLE, engine.engage(archer).status());
        run(5);
        assertEquals(launches, world.helperLaunches, "A stopping engine launches nothing");
        assertEquals(flying, engine.helperCount(), "Helpers are not cut short");
        run(20);
        assertEquals(0, engine.helperCount());
        assertTrue(engine.drained());
        assertEquals(flying, world.removedHelpers.size(), "Each helper despawned when its lifetime ended");
    }

    @Test void resumingAStoppingEngineFightsAgain() {
        start(budgets(64, 8, 8, 20, 100));
        UUID npc = combatant(0, 0, HUNT_MONSTERS);
        world.monster("minecraft:zombie", 2, 0);
        run(2);
        engine.stop();
        assertFalse(fighting(npc));
        engine.resume();
        assertFalse(engine.stopping());
        run(3);
        assertTrue(fighting(npc));
    }
}
