package art.arcane.animus.bukkit.integration.betonquest;

import art.arcane.animus.bukkit.api.AnimusNpcs;
import art.arcane.animus.bukkit.api.NpcInfo;
import art.arcane.animus.bukkit.integration.quests.QuestNpcHost;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import org.betonquest.betonquest.api.identifier.NpcIdentifier;
import org.bukkit.Location;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** BetonQuest finds the ids written for an Animus NPC, by id or by name, whatever their case. */
class AnimusBqIdentifierTest {
    private static final NpcInfo INNKEEPER = new NpcInfo(UUID.fromString("00000000-0000-4000-8000-000000000003"), "citizens-3", "town", "&6Innkeeper", true);

    private static NpcIdentifier id(String name) {
        return (NpcIdentifier) Proxy.newProxyInstance(NpcIdentifier.class.getClassLoader(), new Class<?>[] {NpcIdentifier.class}, (Object proxy, java.lang.reflect.Method method, Object[] args) -> switch (method.getName()) {
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> name;
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    @Test void idsWrittenByKeyOrNameMatchTheNpc() {
        AnimusBqIdentifier identifier = new AnimusBqIdentifier();
        NpcIdentifier byKey = id("town>innkeeper"), byName = id("town>inn_by_name"), other = id("town>bandit"), fancy = id("town>fancy");
        identifier.addID(byKey, "animus citizens-3");
        identifier.addID(byName, "Animus innkeeper byName");
        identifier.addID(other, "animus citizens-8");
        identifier.addID(fancy, "FancyNpcs citizens-3");
        assertEquals(Set.of(byKey, byName), identifier.getIdsFromNpc(new AnimusBqNpc(INNKEEPER, new FakeHost()), null));
        identifier.reset();
        assertEquals(Set.of(), identifier.getIdsFromNpc(new AnimusBqNpc(INNKEEPER, new FakeHost()), null));
    }

    @Test void adaptersOfOneNpcAreEqualAndNamesDropColours() {
        AnimusBqNpc first = new AnimusBqNpc(INNKEEPER, new FakeHost()), second = new AnimusBqNpc(INNKEEPER, new FakeHost());
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertEquals("Innkeeper", first.getName());
        assertEquals("§6Innkeeper", first.getFormattedName());
    }

    @Test void spawnAndDespawnSaveThroughTheHost() {
        FakeHost host = new FakeHost();
        NpcInfo despawned = new NpcInfo(INNKEEPER.uuid(), INNKEEPER.key(), INNKEEPER.group(), INNKEEPER.name(), false);
        host.spawned = false;
        AnimusBqNpc npc = new AnimusBqNpc(despawned, host);
        npc.spawn(new Location(null, 1, 2, 3));
        assertEquals(List.of("teleport citizens-3", "spawned citizens-3 true"), host.calls);
        npc.despawn();
        assertEquals("spawned citizens-3 false", host.calls.getLast());
    }

    static final class FakeHost implements QuestNpcHost {
        final java.util.List<String> calls = new java.util.ArrayList<>();
        boolean spawned = true;
        @Override public AnimusNpcs npcs() {
            return new AnimusNpcs() {
                @Override public Optional<NpcInfo> byKey(String key) { return Optional.empty(); }
                @Override public Optional<NpcInfo> byUuid(UUID uuid) { return Optional.of(new NpcInfo(uuid, INNKEEPER.key(), "", INNKEEPER.name(), spawned)); }
                @Override public List<NpcInfo> byName(String name) { return List.of(); }
                @Override public List<NpcInfo> all() { return List.of(); }
            };
        }
        @Override public Optional<Location> location(UUID npc) { return Optional.empty(); }
        @Override public Optional<Double> height(UUID npc) { return Optional.empty(); }
        @Override public OptionalInt citizensId(UUID npc) { return OptionalInt.empty(); }
        @Override public void teleport(String key, Location to) { calls.add("teleport " + key); }
        @Override public void spawned(String key, boolean value) { calls.add("spawned " + key + " " + value); }
        @Override public void hide(UUID viewer, UUID npc, boolean hidden) { calls.add("hide " + hidden); }
        @Override public boolean hidden(UUID viewer, UUID npc) { return false; }
    }
}
