package art.arcane.animus.core.behavior.runtime;

import art.arcane.animus.core.behavior.BehaviorFacts;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConnectedFactsTest {
    @Test void everyFactReachesTheConnectedProvider() {
        UUID npc = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
        ConnectedFacts facts = new ConnectedFacts();
        facts.connect(new BehaviorFacts() {
            @Override public Optional<Map<String, Object>> player(UUID player) { return Optional.of(Map.of("name", "Steve")); }
            @Override public Optional<Map<String, Object>> npc(UUID id) { return Optional.of(Map.of("name", "Smith")); }
            @Override public Map<String, Object> server() { return Map.of("tick", 3L); }
            @Override public List<UUID> npcs() { return List.of(npc); }
            @Override public Optional<String> world(String reference) { return Optional.of("world"); }
        });
        assertEquals(List.of(npc), facts.npcs());
        assertEquals(Optional.of("world"), facts.world("minecraft:overworld"));
        assertEquals("Smith", facts.npc(npc).orElseThrow().get("name"));
        for (Method method : BehaviorFacts.class.getMethods()) {
            if (java.lang.reflect.Modifier.isStatic(method.getModifiers())) continue;
            assertDoesNotThrow(() -> ConnectedFacts.class.getDeclaredMethod(method.getName(), method.getParameterTypes()), "ConnectedFacts passes on " + method.getName());
        }
    }
}
