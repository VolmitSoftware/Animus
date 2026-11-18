package art.arcane.animus.core.behavior;

import art.arcane.animus.core.npc.NpcDefinition;
import java.util.List;
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

    @Test void theRuntimeListsItsNpcsAndTheirBehaviors() throws Exception {
        UUID other = UUID.fromString("00000000-0000-0000-0000-00000000000b");
        Map<String, NpcDefinition> definitions = Map.of("guard", definition("guard", NPC, "[\"denizen:assignment/guard\"]"), "smith", definition("smith", other, "[]"));
        SnapshotFacts facts = new SnapshotFacts(SnapshotFacts.npcs(() -> definitions, (UUID id) -> Optional.empty()));
        assertEquals(List.of(NPC, other), facts.npcs().stream().sorted().toList());
        assertEquals(List.of("denizen:assignment/guard"), facts.npc(NPC).orElseThrow().get("behaviors"));
        assertEquals(List.of(), facts.npc(other).orElseThrow().get("behaviors"));
        assertEquals(List.of(), new SnapshotFacts((UUID id) -> Optional.empty()).npcs(), "a lookup alone lists no NPCs");
    }

    @Test void theFactTypesTheEditorShowsAreWhatTheFactsGive() throws Exception {
        art.arcane.animus.core.virtual.VirtualNpcState.Snapshot body = new art.arcane.animus.core.virtual.VirtualNpcState.Snapshot(NPC,
                new art.arcane.animus.core.virtual.WorldPosition("world", new art.arcane.animus.core.virtual.Vec3(1, 64, 2)),
                art.arcane.animus.core.virtual.Hitbox.atFeet(new art.arcane.animus.core.virtual.Vec3(1, 64, 2), 0.6, 1.8), 20, true, 1);
        Map<String, NpcDefinition> definitions = Map.of("guard", definition("guard", NPC, "[\"town:guard\"]"));
        Map<String, Map<String, Object>> given = Map.of(
                "player", SnapshotFacts.player("Alex", NPC, 20, 20, 18, 3, "world", 1, 64, 2, "SURVIVAL", false, true, false, "minecraft:wheat", 4),
                "npc", new SnapshotFacts(SnapshotFacts.npcs(() -> definitions, (UUID id) -> Optional.of(body))).npc(NPC).orElseThrow(),
                "server", SnapshotFacts.server(1, 20, 5, 1000, List.of(NPC)));
        assertEquals(given.keySet(), art.arcane.animus.core.behavior.schema.ExpressionDocs.FACT_TYPES.keySet());
        for (Map.Entry<String, Map<String, Object>> root : given.entrySet()) {
            Map<String, String> types = art.arcane.animus.core.behavior.schema.ExpressionDocs.FACT_TYPES.get(root.getKey());
            assertEquals(types.keySet(), root.getValue().keySet(), root.getKey());
            for (Map.Entry<String, Object> fact : root.getValue().entrySet())
                assertEquals(types.get(fact.getKey()), switch (fact.getValue()) {
                    case Boolean ignored -> "boolean";
                    case Number ignored -> "number";
                    case String ignored -> "text";
                    case List<?> ignored -> "list";
                    default -> "map";
                }, root.getKey() + "." + fact.getKey());
        }
    }

    private static NpcDefinition definition(String id, UUID uuid, String behaviors) throws Exception {
        return NpcDefinition.parse("{\"schemaVersion\":2,\"revision\":1,\"uuid\":\"" + uuid + "\",\"id\":\"" + id + "\",\"group\":\"test\",\"name\":\"Test\",\"type\":\"minecraft:player\",\"spawned\":false,\"behaviors\":"
                + behaviors + ",\"position\":{\"world\":\"world\",\"x\":0,\"y\":61,\"z\":0,\"yaw\":0,\"pitch\":0}}");
    }
}
