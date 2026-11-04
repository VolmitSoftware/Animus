package art.arcane.animus.bukkit.packet;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PassengerGraphTest {
    @Test void rejectsCyclesAndMultipleVehiclesWithoutChangingExistingMounts() {
        PassengerGraph graph = new PassengerGraph();
        graph.replace(1, List.of(2)); graph.replace(2, List.of(3));
        assertThrows(IllegalArgumentException.class, () -> graph.replace(3, List.of(1)));
        assertThrows(IllegalArgumentException.class, () -> graph.replace(4, List.of(2)));
        assertThrows(IllegalArgumentException.class, () -> graph.replace(1, List.of(1)));
        assertThrows(IllegalArgumentException.class, () -> graph.replace(1, List.of(2, 2)));
        assertEquals(List.of(2), graph.passengers(1)); assertEquals(List.of(3), graph.passengers(2));
    }
    @Test void despawnAndExplicitDetachAllowReparenting() {
        PassengerGraph graph = new PassengerGraph();
        graph.replace(1, List.of(2, 3)); graph.remove(2);
        assertEquals(List.of(3), graph.passengers(1));
        graph.replace(4, List.of(2)); graph.replace(1, List.of()); graph.replace(4, List.of(2, 3));
        assertEquals(List.of(2, 3), graph.passengers(4));
        graph.remove(4); graph.replace(5, List.of(2, 3));
        graph.clear(); assertTrue(graph.passengers(5).isEmpty());
    }
}
