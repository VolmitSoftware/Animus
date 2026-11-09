package art.arcane.animus.bukkit.runtime;

import art.arcane.animus.bukkit.api.NpcInfo;
import art.arcane.animus.core.npc.NpcDefinition;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BukkitNpcLookupTest {
    private static NpcDefinition npc(String uuid, String id, String group, String name, boolean spawned) throws IOException {
        return NpcDefinition.parse("""
                {"schemaVersion":1,"revision":1,"uuid":"%s","id":"%s","group":"%s","name":"%s","type":"minecraft:player","spawned":%s,
                 "position":{"world":"world","x":0,"y":64,"z":0,"yaw":0,"pitch":0}}
                """.formatted(uuid, id, group, name, spawned));
    }

    @Test
    void looksUpByKeyUuidAndNameAndListsInKeyOrder() throws IOException {
        NpcDefinition guard = npc("00000000-0000-0000-0000-000000000001", "guard", "town", "&6Town Guard", true);
        NpcDefinition baker = npc("00000000-0000-0000-0000-000000000002", "baker", "", "Baker", false);
        NpcDefinition watch = npc("00000000-0000-0000-0000-000000000003", "watch", "town/gate", "town guard", true);
        Map<String, NpcDefinition> definitions = new HashMap<>(Map.of("guard", guard, "baker", baker, "watch", watch));
        AtomicReference<Map<String, NpcDefinition>> snapshot = new AtomicReference<>(Map.copyOf(definitions));
        BukkitNpcLookup lookup = new BukkitNpcLookup(snapshot::get);

        NpcInfo found = lookup.byKey("guard").orElseThrow();
        assertEquals(new NpcInfo(guard.uuid(), "guard", "town", "&6Town Guard", true), found);
        assertEquals(Optional.of(found), lookup.byUuid(guard.uuid()));
        assertTrue(lookup.byKey("nobody").isEmpty());
        assertTrue(lookup.byUuid(UUID.randomUUID()).isEmpty());
        assertEquals(List.of("guard", "watch"), lookup.byName("TOWN GUARD").stream().map(NpcInfo::key).toList());
        assertEquals(List.of("baker", "guard", "watch"), lookup.all().stream().map(NpcInfo::key).toList());
        assertSame(lookup.all(), lookup.all(), "An unchanged snapshot reuses its index");

        definitions.remove("watch");
        snapshot.set(Map.copyOf(definitions));
        assertEquals(List.of("baker", "guard"), lookup.all().stream().map(NpcInfo::key).toList());
    }
}
