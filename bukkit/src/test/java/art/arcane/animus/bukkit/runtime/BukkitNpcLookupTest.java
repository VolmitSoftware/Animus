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
                {"schemaVersion":2,"revision":1,"uuid":"%s","id":"%s","group":"%s","name":"%s","type":"minecraft:player","spawned":%s,
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

    /** A plugin whose name and enabled flag the test sets. */
    private static org.bukkit.plugin.Plugin plugin(String name, java.util.concurrent.atomic.AtomicBoolean enabled) {
        return (org.bukkit.plugin.Plugin) java.lang.reflect.Proxy.newProxyInstance(org.bukkit.plugin.Plugin.class.getClassLoader(), new Class<?>[]{org.bukkit.plugin.Plugin.class},
                (Object proxy, java.lang.reflect.Method method, Object[] arguments) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "isEnabled" -> enabled.get();
                    case "hashCode" -> name.hashCode();
                    case "equals" -> proxy == arguments[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    void aPluginGetsAServiceWhileEnabledAndLosesWhatItMadeWhenItDisables() {
        BukkitNpcLookup lookup = new BukkitNpcLookup(Map::of);
        java.util.concurrent.atomic.AtomicBoolean enabled = new java.util.concurrent.atomic.AtomicBoolean(true);
        org.bukkit.plugin.Plugin shops = plugin("Shops", enabled);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> lookup.service(shops), "no service before Animus finished starting");
        List<String> opened = new java.util.ArrayList<>(), released = new java.util.ArrayList<>(), left = new java.util.ArrayList<>();
        AtomicReference<java.util.function.BooleanSupplier> gate = new AtomicReference<>();
        lookup.connect(new art.arcane.animus.core.api.NpcScopes() {
            @Override public art.arcane.animus.api.NpcService open(String owner, java.util.function.BooleanSupplier available) { opened.add(owner); gate.set(available); return null; }
            @Override public void release(String owner) { released.add(owner); }
        }, (UUID player) -> left.add(player.toString()));
        lookup.service(shops);
        assertEquals(List.of("plugin:Shops"), opened);
        assertTrue(gate.get().getAsBoolean());
        enabled.set(false);
        org.junit.jupiter.api.Assertions.assertFalse(gate.get().getAsBoolean(), "the service follows the plugin's enabled state");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> lookup.service(shops));
        lookup.disabled(new org.bukkit.event.server.PluginDisableEvent(shops));
        assertEquals(List.of("plugin:Shops"), released);
    }
}
