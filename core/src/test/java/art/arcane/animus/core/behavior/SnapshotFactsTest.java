package art.arcane.animus.core.behavior;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SnapshotFactsTest {
    private static final UUID NPC = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final String WORLD_ID = "1f3c1b0e-5d7a-4a2f-9a0c-111111111111";

    private static Optional<String> bukkit(String reference) {
        return switch (reference) {
            case "world", "minecraft:overworld", WORLD_ID -> Optional.of("world");
            default -> Optional.empty();
        };
    }

    @Test void npcWorldFactsUseTheNameThePlayerFactsUse() {
        SnapshotFacts facts = new SnapshotFacts((UUID id) -> Optional.of(SnapshotFacts.npc("Guard", "guard", "guard", NPC, true, 20, WORLD_ID, 1, 64, 2)));
        facts.worlds(SnapshotFactsTest::bukkit);
        Map<String, Object> npc = facts.npc(NPC).orElseThrow();
        assertEquals("world", npc.get("world"));
        assertEquals("1.00,64.00,2.00,world", npc.get("location"));
        assertEquals(Optional.of("world"), facts.world("minecraft:overworld"));
        assertEquals(Optional.empty(), facts.world("skylands"));
    }

    @Test void worldsNoResolverKnowsStayAsWritten() {
        SnapshotFacts facts = new SnapshotFacts((UUID id) -> Optional.of(SnapshotFacts.npc("Guard", "guard", "guard", NPC, true, 20, "skylands", 1, 64, 2)));
        assertEquals("skylands", facts.npc(NPC).orElseThrow().get("world"));
        assertEquals(Optional.empty(), facts.world("world"));
        facts.worlds(SnapshotFactsTest::bukkit);
        assertEquals("skylands", facts.npc(NPC).orElseThrow().get("world"));
        facts.worlds((String reference) -> { throw new IllegalStateException("unloading"); });
        assertEquals("skylands", facts.npc(NPC).orElseThrow().get("world"));
        assertEquals(Optional.empty(), facts.world("world"));
    }
}
