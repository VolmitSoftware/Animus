package art.arcane.animus.bukkit;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BukkitWorldLookupTest {
    private static final UUID MAIN = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    private static final World OVERWORLD = world("world", MAIN, NamespacedKey.minecraft("overworld"));
    private static final World NETHER = world("world_nether", UUID.fromString("00000000-0000-0000-0000-0000000000f2"), NamespacedKey.minecraft("the_nether"));
    private static final World MINING = world("Mining", UUID.fromString("00000000-0000-0000-0000-0000000000f3"), NamespacedKey.fromString("example:mining"));
    private static final Server SERVER = server(List.of(OVERWORLD, NETHER, MINING));

    private static World world(String name, UUID id, NamespacedKey key) {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] {World.class}, (Object proxy, java.lang.reflect.Method method, Object[] args) -> switch (method.getName()) {
            case "getName" -> name;
            case "getUID" -> id;
            case "getKey" -> key;
            case "hashCode" -> id.hashCode();
            case "equals" -> proxy == args[0];
            case "toString" -> "world " + name;
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    private static Server server(List<World> worlds) {
        return (Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[] {Server.class}, (Object proxy, java.lang.reflect.Method method, Object[] args) -> switch (method.getName()) {
            case "getWorlds" -> worlds;
            case "getWorld" -> {
                for (World world : worlds) {
                    if (args[0] instanceof UUID id && world.getUID().equals(id)) yield world;
                    if (args[0] instanceof String name && world.getName().equalsIgnoreCase(name)) yield world;
                    if (args[0] instanceof NamespacedKey key && world.getKey().equals(key)) yield world;
                }
                yield null;
            }
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    @Test void namesUuidsAndDimensionKeysFindTheSameWorld() {
        assertSame(OVERWORLD, BukkitWorldLookup.find(SERVER, "world"));
        assertSame(OVERWORLD, BukkitWorldLookup.find(SERVER, MAIN.toString()));
        assertSame(OVERWORLD, BukkitWorldLookup.find(SERVER, "minecraft:overworld"));
        assertSame(NETHER, BukkitWorldLookup.find(SERVER, "minecraft:the_nether"));
        assertSame(MINING, BukkitWorldLookup.find(SERVER, "example:mining"));
        assertSame(MINING, BukkitWorldLookup.find(SERVER, "mining"));
    }

    @Test void unknownAndUnusableReferencesFindNothing() {
        assertNull(BukkitWorldLookup.find(SERVER, "minecraft:the_end"));
        assertNull(BukkitWorldLookup.find(SERVER, "skylands"));
        assertNull(BukkitWorldLookup.find(SERVER, UUID.randomUUID().toString()));
        assertNull(BukkitWorldLookup.find(SERVER, ""));
        assertNull(BukkitWorldLookup.find(SERVER, null));
    }

    @Test void theWorldTableNamesEveryLoadedWorld() {
        assertEquals(List.of("world", "world_nether", "Mining"), BukkitWorldLookup.loaded(SERVER).stream().map((art.arcane.animus.core.platform.BukkitWorlds.Loaded world) -> world.name()).toList());
        assertEquals("example:mining", BukkitWorldLookup.loaded(SERVER).getLast().key());
    }
}
