package art.arcane.animus.core.combat;

import art.arcane.animus.core.config.CombatBudgets;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Sentinel's fight counts go on counting in Animus and survive a restart. */
class CombatStatisticsTest {
    @TempDir Path directory;

    @Test void countsAddToTheImportedTotalsAndAreKeptAcrossARestart() {
        Path file = directory.resolve("state/combat/statistics.json");
        CombatStatistics statistics = new CombatStatistics(file, (String warning) -> fail(warning));
        UUID npc = UUID.randomUUID();
        statistics.add(npc, CombatStatistics.Counter.ARROWS_FIRED, 3);
        statistics.add(npc, CombatStatistics.Counter.DAMAGE_GIVEN, 7.5);
        statistics.add(npc, CombatStatistics.Counter.PUNCHES, -1);
        statistics.flushNow();
        CombatStatistics reopened = new CombatStatistics(file, (String warning) -> fail(warning));
        JsonObject imported = JsonParser.parseString("{\"stats_arrowsFired\":10,\"stats_punches\":4,\"stats_ticksSpawned\":72000}").getAsJsonObject();
        Map<CombatStatistics.Counter, Double> totals = reopened.totals(npc, imported);
        assertEquals(13, totals.get(CombatStatistics.Counter.ARROWS_FIRED));
        assertEquals(4, totals.get(CombatStatistics.Counter.PUNCHES), "A negative count is ignored");
        assertEquals(7.5, totals.get(CombatStatistics.Counter.DAMAGE_GIVEN));
        assertEquals("time spawned 60m, arrows 13, punches 4, damage given 7.5", CombatStatistics.describe(totals));
        assertEquals("no fights yet", CombatStatistics.describe(reopened.totals(UUID.randomUUID(), new JsonObject())));
    }

    @Test void anUnreadableFileStartsFromZeroWithAWarning() throws Exception {
        Path file = directory.resolve("statistics.json");
        java.nio.file.Files.writeString(file, "{broken");
        List<String> warnings = new ArrayList<>();
        CombatStatistics statistics = new CombatStatistics(file, warnings::add);
        assertEquals(Map.of(), statistics.counted(UUID.randomUUID()));
        assertEquals(1, warnings.size());
    }

    @Test void theEngineCountsShotsHitsDamageAndDeaths() {
        MockCombatWorld world = new MockCombatWorld();
        CombatEngine engine = new CombatEngine(CombatBudgets.DEFAULT, world, ProtectionAdapter.NONE, new Random(1), (String line) -> {});
        CombatStatistics statistics = new CombatStatistics(directory.resolve("s.json"), (String warning) -> {});
        engine.statistics(statistics.sink());
        JsonObject module = JsonParser.parseString("{\"enabled\":true,\"targets\":{\"categories\":[\"monsters\"]},\"targeting\":{\"scanIntervalTicks\":1,\"scanRange\":30},"
                + "\"melee\":{\"damage\":4,\"reach\":3},\"ranged\":{\"enabled\":true,\"damage\":2,\"range\":30,\"minimumRange\":4}}").getAsJsonObject();
        UUID npc = world.npc(0, 0, 20);
        assertTrue(engine.register(npc, "guard", CombatSettings.parseModule(module, CombatTesting.KILLABLE), CombatLoadout.NONE));
        UUID far = world.monster("minecraft:zombie", 10, 0);
        for (int i = 0; i < CombatTesting.FIRST_ARROW; i++) engine.tick();
        UUID near = world.monster("minecraft:husk", 2, 0);
        world.moveReal(far, 40, 40);
        for (int i = 0; i < 3; i++) engine.tick();
        CombatTesting.hit(engine, npc, new TargetRef(TargetKind.PLAYER, UUID.randomUUID()), MockCombatWorld.at(1, 64, 0), 20);
        Map<CombatStatistics.Counter, Double> counted = statistics.counted(npc);
        assertEquals(1, counted.get(CombatStatistics.Counter.ARROWS_FIRED));
        assertEquals(1, counted.get(CombatStatistics.Counter.PUNCHES));
        assertEquals(2, counted.get(CombatStatistics.Counter.ATTACK_ATTEMPTS));
        assertEquals(4, counted.get(CombatStatistics.Counter.DAMAGE_GIVEN));
        assertEquals(20, counted.get(CombatStatistics.Counter.DAMAGE_TAKEN));
        assertEquals(1, counted.get(CombatStatistics.Counter.DEATHS));
        assertEquals(1, counted.get(CombatStatistics.Counter.TIMES_SPAWNED));
        assertNotNull(world.realDamage.get(near));
    }
}
