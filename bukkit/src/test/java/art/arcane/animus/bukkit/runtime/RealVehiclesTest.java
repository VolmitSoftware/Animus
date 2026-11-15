package art.arcane.animus.bukkit.runtime;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Set;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Mannequin;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which real entities an NPC may ride: never one of combat's decoys, which stand on NPC bodies. */
class RealVehiclesTest {
    private static final Plugin ANIMUS = proxy(Plugin.class, (String name, Object[] args) -> "getName".equals(name) ? "Animus" : null);

    @Test void aDecoyIsNeverAVehicle() {
        assertTrue(BukkitRuntimeHost.vehicle(ANIMUS, mannequin(false)), "a mannequin anyone placed can carry an NPC");
        assertFalse(BukkitRuntimeHost.vehicle(ANIMUS, mannequin(true)), "an NPC's decoy cannot");
    }

    private static Mannequin mannequin(boolean decoy) {
        PersistentDataContainer data = proxy(PersistentDataContainer.class, (String name, Object[] args) ->
                "has".equals(name) ? decoy && ((NamespacedKey) args[0]).toString().equals("animus:npc_decoy") : null);
        return proxy(Mannequin.class, (String name, Object[] args) -> switch (name) {
            case "isValid" -> true;
            case "isDead" -> false;
            case "getScoreboardTags" -> Set.of();
            case "getPersistentDataContainer" -> data;
            default -> null;
        });
    }
    private interface Answer { Object of(String method, Object[] args); }
    private static <T> T proxy(Class<T> type, Answer answer) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (Object self, Method method, Object[] args) -> {
            if (method.getName().equals("equals")) return self == args[0];
            if (method.getName().equals("hashCode")) return System.identityHashCode(self);
            return answer.of(method.getName(), args);
        }));
    }
}
