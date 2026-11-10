package art.arcane.animus.bukkit.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.schedule.ScheduleWorld;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

/** A schedule reads the clock of the world the NPC stands in, whichever way the runtime names that world. */
class BukkitScheduleHostTest {
    private static final UUID MAIN = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final World OVERWORLD = (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] {World.class},
            (Object proxy, java.lang.reflect.Method method, Object[] args) -> switch (method.getName()) {
                case "getName" -> "world";
                case "getUID" -> MAIN;
                case "getKey" -> NamespacedKey.minecraft("overworld");
                case "getTime" -> 13500L;
                case "hasStorm" -> true;
                case "hashCode" -> MAIN.hashCode();
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.getName());
            });
    /** Bukkit finds a world by its folder name only; a dimension key or UUID string finds nothing through getWorld(String). */
    private static final Server SERVER = (Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[] {Server.class},
            (Object proxy, java.lang.reflect.Method method, Object[] args) -> switch (method.getName()) {
                case "getWorlds" -> List.of(OVERWORLD);
                case "getWorld" -> args[0] instanceof String name && name.equals("world") || args[0] instanceof UUID id && id.equals(MAIN) ? OVERWORLD : null;
                default -> throw new UnsupportedOperationException(method.getName());
            });

    @Test void theClockIsFoundByDimensionKeyUuidOrName() {
        BukkitScheduleHost host = new BukkitScheduleHost(SERVER, null);
        for (String reference : List.of("minecraft:overworld", MAIN.toString(), "world"))
            assertEquals(Optional.of(new ScheduleWorld.Clock(13500, true)), host.clock(reference), reference);
    }

    @Test void anUnloadedWorldHasNoClock() {
        assertTrue(new BukkitScheduleHost(SERVER, null).clock("minecraft:the_end").isEmpty());
    }
}
