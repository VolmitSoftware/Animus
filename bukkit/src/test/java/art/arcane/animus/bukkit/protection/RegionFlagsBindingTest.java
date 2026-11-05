package art.arcane.animus.bukkit.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.UUID;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;

class RegionFlagsBindingTest {
    private static final WorldPosition AT = new WorldPosition("world", new Vec3(0, 61, 0));

    private static <T> T proxy(Class<T> type, java.util.function.Function<String, Object> answers) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (Object self, Method method, Object[] args) -> switch (method.getName()) {
            case "hashCode" -> System.identityHashCode(self);
            case "equals" -> self == args[0];
            case "toString" -> type.getSimpleName();
            default -> answers.apply(method.getName());
        }));
    }
    private static Server server(Plugin worldGuard) {
        PluginManager plugins = proxy(PluginManager.class, (String name) -> switch (name) {
            case "getPlugin" -> worldGuard;
            case "isPluginEnabled" -> worldGuard != null && worldGuard.isEnabled();
            default -> throw new UnsupportedOperationException(name);
        });
        return proxy(Server.class, (String name) -> {
            if (name.equals("getPluginManager")) return plugins;
            throw new UnsupportedOperationException(name);
        });
    }
    private static Plugin worldGuard(boolean enabled) {
        return proxy(Plugin.class, (String name) -> switch (name) {
            case "isEnabled" -> enabled;
            case "getName" -> "WorldGuard";
            default -> throw new UnsupportedOperationException(name);
        });
    }

    @Test void withoutWorldGuardThereIsNoBinding() {
        assertNull(RegionFlags.bind(server(null)));
    }

    @Test void installedButDisabledWorldGuardFailsClosed() {
        RegionFlags.Binding binding = RegionFlags.bind(server(worldGuard(false)));
        assertNotNull(binding, "a disabled WorldGuard must not be treated as absent");
        assertTrue(binding.failure().orElseThrow().contains("disabled"), binding.failure().toString());
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> binding.flags().state(AT, null, RegionFlags.Flag.PVP));
        assertTrue(refused.getMessage().contains("disabled"));
        assertThrows(IllegalStateException.class, () -> binding.flags().canBuild(AT, UUID.randomUUID(), RegionFlags.Flag.CHEST_ACCESS));
        assertThrows(IllegalStateException.class, () -> binding.flags().inRegion("castle", AT));
    }

    @Test void enabledWorldGuardWhoseApiCannotBeUsedFailsClosed() {
        // The test classpath has no WorldGuard classes, as on a server with an incompatible WorldGuard.
        RegionFlags.Binding binding = RegionFlags.bind(server(worldGuard(true)));
        assertNotNull(binding);
        assertTrue(binding.failure().orElseThrow().contains("could not be used"), binding.failure().toString());
        assertThrows(IllegalStateException.class, () -> binding.flags().state(AT, null, RegionFlags.Flag.PVP));
    }

    @Test void failureNamesTheCombatConsequence() {
        RegionFlags.Binding binding = RegionFlags.bind(server(worldGuard(false)));
        assertEquals(binding.failure().orElseThrow() + "; all combat damage is denied", binding.describe("all combat damage is denied"));
    }
}
