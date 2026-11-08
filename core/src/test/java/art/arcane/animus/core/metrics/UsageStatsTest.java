package art.arcane.animus.core.metrics;

import art.arcane.animus.core.npc.NpcDefinition;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UsageStatsTest {
    private static NpcDefinition npc(String id, String extra) throws IOException {
        return NpcDefinition.parse("{\"schemaVersion\":1,\"revision\":1,\"uuid\":\"" + UUID.randomUUID() + "\",\"id\":\"" + id
                + "\",\"group\":\"test\",\"name\":\"Test\",\"type\":\"minecraft:player\",\"spawned\":false,\"position\":{\"world\":\"world\",\"x\":0,\"y\":61,\"z\":0,\"yaw\":0,\"pitch\":0}" + extra + "}");
    }

    @Test
    void countsFallIntoCoarseBuckets() {
        assertEquals("0", UsageStats.bucket(0));
        assertEquals("1-10", UsageStats.bucket(1));
        assertEquals("1-10", UsageStats.bucket(10));
        assertEquals("11-50", UsageStats.bucket(11));
        assertEquals("51-200", UsageStats.bucket(200));
        assertEquals("201-1000", UsageStats.bucket(1000));
        assertEquals("1000+", UsageStats.bucket(1001));
    }

    @Test
    void noDefinitionsReportsNothingInUse() {
        UsageStats stats = UsageStats.of(List.of());
        assertEquals(0, stats.npcs());
        assertEquals(Set.of(), stats.importSources());
        assertEquals(0, stats.traitGroupsInUse());
        assertFalse(stats.killable());
    }

    @Test
    void importSourcesComeFromEachDefinitionsMigrationRecord() throws IOException {
        UsageStats stats = UsageStats.of(List.of(
                npc("a", ",\"migration\":{\"importId\":\"x\",\"citizensId\":\"4\"}"),
                npc("b", ",\"migration\":{\"importId\":\"y\",\"source\":\"Shopkeepers\",\"shopkeeper\":2}"),
                npc("c", ",\"migration\":{\"importId\":\"z\",\"converter\":\"zno:znpcs\",\"sourceId\":3}"),
                npc("d", ",\"behaviors\":[\"denizen:greeter\"]"),
                npc("e", "")));
        assertEquals(5, stats.npcs());
        assertEquals(Set.of("Citizens", "Shopkeepers", "Module", "Denizen"), stats.importSources());
    }

    @Test
    void traitGroupsInUseAreDistinctAcrossNpcs() throws IOException {
        UsageStats stats = UsageStats.of(List.of(npc("a", ",\"groups\":[\"guards\",\"town\"]"), npc("b", ",\"groups\":[\"town\"]"), npc("c", "")));
        assertEquals(2, stats.traitGroupsInUse());
    }

    @Test
    void anyKillableNpcMarksTheServer() throws IOException {
        assertFalse(UsageStats.of(List.of(npc("a", ",\"modules\":{\"animus:health\":{\"killable\":false}}"))).killable());
        assertTrue(UsageStats.of(List.of(npc("a", ""), npc("b", ",\"modules\":{\"animus:health\":{\"killable\":true}}"))).killable());
    }
}
