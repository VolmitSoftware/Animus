package art.arcane.animus.bukkit.toolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.Test;

class BukkitWandTest {
    static PersistentDataContainer data(Map<NamespacedKey, Object> values) {
        return (PersistentDataContainer) Proxy.newProxyInstance(BukkitWandTest.class.getClassLoader(), new Class<?>[]{PersistentDataContainer.class},
                (Object proxy, java.lang.reflect.Method method, Object[] arguments) -> method.getName().equals("get") ? values.get((NamespacedKey) arguments[0]) : null);
    }

    @Test void theMarkerKeyIsAnimusToolAndOnlyItsValueMakesAWand() {
        assertEquals("animus:tool", BukkitWand.KEY.toString());
        Map<NamespacedKey, Object> values = new HashMap<>();
        assertFalse(BukkitWand.marked(data(values)), "a plain item is not a wand");
        values.put(BukkitWand.KEY, "route");
        assertFalse(BukkitWand.marked(data(values)));
        values.put(BukkitWand.KEY, "wand");
        assertTrue(BukkitWand.marked(data(values)));
    }
}
