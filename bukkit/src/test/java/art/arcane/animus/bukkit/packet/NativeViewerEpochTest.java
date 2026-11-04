package art.arcane.animus.bukkit.packet;

import org.junit.jupiter.api.Test;
import java.lang.reflect.InvocationTargetException;
import static org.junit.jupiter.api.Assertions.*;

/** Authority comparisons remain independent of location, client ACKs and account equality. */
class NativeViewerEpochTest {
    @Test void sameConnectionAndCounterRetainsAuthority() {
        Object connection = new Object();
        var first = new NativeViewerEpoch.Stamp(connection, 17);
        var second = new NativeViewerEpoch.Stamp(connection, 17);
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertEquals(17, first.sequence());
    }

    @Test void samePositionTeleportCounterChangeRevokesAuthority() {
        Object connection = new Object();
        assertNotEquals(new NativeViewerEpoch.Stamp(connection, 17), new NativeViewerEpoch.Stamp(connection, 18));
        assertNotEquals(new NativeViewerEpoch.Stamp(connection, Integer.MAX_VALUE - 1), new NativeViewerEpoch.Stamp(connection, 0));
    }

    @Test void reconnectedEquivalentAccountNeverReusesConnectionAuthority() {
        class Connection {
            @Override public boolean equals(Object other) { return other instanceof Connection; }
            @Override public int hashCode() { return 0; }
        }
        assertNotEquals(new NativeViewerEpoch.Stamp(new Connection(), 17), new NativeViewerEpoch.Stamp(new Connection(), 17));
    }

    @Test void unvalidatedVersionRefusesBindingBeforeNativeAccess() throws ReflectiveOperationException {
        // Bukkit is runtime-only for these pure tests; reflection avoids expanding the test compile API.
        var bind = NativeViewerEpoch.class.getMethod("bind", Class.forName("org.bukkit.plugin.java.JavaPlugin"), String.class);
        var failure = assertThrows(InvocationTargetException.class, () -> bind.invoke(null, null, "26.4"));
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
    }
}
