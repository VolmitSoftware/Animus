package art.arcane.animus.bukkit;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.UUID;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PlaceholderAPI is found by reflection through its own class loader; the fake under me.clip.placeholderapi stands in for it. */
class BukkitPlaceholdersTest {
    private final UUID alex = UUID.randomUUID();
    private final OfflinePlayer online = (OfflinePlayer) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{OfflinePlayer.class},
            (proxy, method, arguments) -> method.getName().equals("getName") ? "Alex" : null);
    private final Map<UUID, OfflinePlayer> players = Map.of(alex, online);

    private static Plugin placeholderApi(boolean enabled) {
        return (Plugin) Proxy.newProxyInstance(BukkitPlaceholdersTest.class.getClassLoader(), new Class<?>[]{Plugin.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "isEnabled" -> enabled;
                    case "getName" -> BukkitPlaceholders.PLUGIN;
                    default -> null;
                });
    }

    @Test
    void shouldFillThroughAnEnabledPlaceholderApi() {
        BukkitPlaceholders source = new BukkitPlaceholders(players::get);
        source.bind(placeholderApi(true));
        assertTrue(source.available());
        assertEquals("Hi Alex, 3 online", source.fill(alex, "Hi %player_name%, %server_online% online"));
    }

    @Test
    void shouldLeaveAnOfflineViewerToTheCaller() {
        BukkitPlaceholders source = new BukkitPlaceholders(players::get);
        source.bind(placeholderApi(true));
        assertNull(source.fill(UUID.randomUUID(), "Hi %player_name%"));
    }

    @Test
    void shouldStayOffWhenPlaceholderApiIsMissingOrDisabled() {
        BukkitPlaceholders source = new BukkitPlaceholders(players::get);
        source.bind(null);
        assertFalse(source.available());
        assertNull(source.fill(alex, "Hi %player_name%"));
        source.bind(placeholderApi(false));
        assertFalse(source.available());
        source.bind(placeholderApi(true));
        assertTrue(source.available());
        source.bind(placeholderApi(false));
        assertFalse(source.available(), "a disabled PlaceholderAPI is dropped");
    }
}
