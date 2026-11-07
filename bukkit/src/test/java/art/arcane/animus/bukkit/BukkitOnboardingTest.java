package art.arcane.animus.bukkit;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BukkitOnboardingTest {
    @Test void onlyOnlineAdminsHearGuidance() {
        Player admin = player(true, true), visitor = player(false, true), gone = player(true, false);
        assertEquals(List.of(admin), List.of(admin, visitor, gone).stream().filter(BukkitOnboarding::hears).toList());
        assertTrue(BukkitOnboarding.admin(gone), "an admin who left is still an admin");
        assertFalse(BukkitOnboarding.admin(visitor));
    }

    private static Player player(boolean admin, boolean online) {
        UUID id = UUID.randomUUID();
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (Object proxy, java.lang.reflect.Method method, Object[] arguments) -> switch (method.getName()) {
            case "hasPermission" -> arguments[0].equals("animus.admin") && admin;
            case "isOnline" -> online;
            case "getUniqueId" -> id;
            case "equals" -> proxy == arguments[0];
            case "hashCode" -> id.hashCode();
            case "toString" -> "player " + id;
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }
}
