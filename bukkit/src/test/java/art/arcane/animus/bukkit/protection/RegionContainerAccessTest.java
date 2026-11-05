package art.arcane.animus.bukkit.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.bukkit.protection.RegionFlags.Flag;
import art.arcane.animus.core.virtual.Vec3;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

class RegionContainerAccessTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-0000000000ff");

    private static World world() {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] {World.class}, (Object proxy, Method method, Object[] args) -> switch (method.getName()) {
            case "getUID" -> WORLD;
            case "getName" -> "world";
            case "hashCode" -> 1;
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    @Test void ownerMustPassChestAccessAtTheContainer() {
        FakeRegionFlags flags = new FakeRegionFlags().build(20, 3, OWNER, false);
        RegionContainerAccess access = new RegionContainerAccess(flags);
        assertFalse(access.test(OWNER, new Location(world(), 20, 61, 3)));
        assertTrue(access.test(OWNER, new Location(world(), 0, 61, 3)));
        FakeRegionFlags.Query query = flags.queries.getFirst();
        assertEquals(OWNER, query.player());
        assertEquals(List.of(Flag.CHEST_ACCESS), query.flags());
        assertEquals(WORLD.toString(), query.at().world());
        assertEquals(new Vec3(20, 61, 3), query.at().point());
    }

    @Test void failedQueryPropagatesSoTheShopStockRefuses() {
        FakeRegionFlags flags = new FakeRegionFlags();
        flags.failure = new IllegalStateException("WorldGuard is disabled");
        assertThrows(IllegalStateException.class, () -> new RegionContainerAccess(flags).test(OWNER, new Location(world(), 0, 61, 3)));
    }
}
