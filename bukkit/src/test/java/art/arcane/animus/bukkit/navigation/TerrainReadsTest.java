package art.arcane.animus.bukkit.navigation;

import art.arcane.animus.core.navigation.VoxelSnapshot.Cell;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.Server;
import org.bukkit.block.Block;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Terrain reads answer as before: air is open space, and world keys resolve by UUID or not at all. */
class TerrainReadsTest {
    @Test void airIsAnOpenCellWithoutReadingItsShape() {
        Block air = (Block) Proxy.newProxyInstance(Block.class.getClassLoader(), new Class<?>[] {Block.class}, (Object proxy, java.lang.reflect.Method method, Object[] args) -> {
            if (method.getName().equals("isEmpty")) return true;
            throw new AssertionError("air needs no " + method.getName());
        });
        assertSame(Cell.AIR, BlockCells.of(air));
    }

    @Test void worldKeysResolveByUuidAndOtherKeysNameNoWorld() {
        List<UUID> asked = new ArrayList<>();
        Server server = (Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[] {Server.class}, (Object proxy, java.lang.reflect.Method method, Object[] args) -> {
            if (method.getName().equals("getWorld") && args[0] instanceof UUID id) { asked.add(id); return null; }
            throw new AssertionError("unexpected " + method.getName());
        });
        BukkitTerrain terrain = new BukkitTerrain(server);
        UUID world = UUID.fromString("8a0f6f1c-0000-4000-8000-000000000001");
        assertNull(terrain.world(world.toString()));
        assertNull(terrain.world(world.toString()));
        assertNull(terrain.world("minecraft:overworld"));
        assertFalse(terrain.worldLoaded("minecraft:overworld"));
        assertEquals(List.of(world, world), asked);
    }
}
