package art.arcane.animus.core.virtual;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VirtualNpcStateReconfigurationTest {
    private static final WorldPosition START = new WorldPosition("world", new Vec3(1, 64, 2));
    @Test void changingDefinitionGeometryPreservesHealthAndMaximumHealthWithoutMutatingOldState() {
        VirtualNpcState old = new VirtualNpcState(UUID.randomUUID(), START, 0.6, 1.8, 42);
        old.spawn(); var before = old.damage(15);
        WorldPosition next = new WorldPosition("other", new Vec3(20, 70, 10));
        VirtualNpcState copy = old.reconfigured(next, 2, 3);
        assertEquals(before, old.snapshot());
        assertEquals(before.npcId(), copy.snapshot().npcId());
        assertEquals(27, copy.snapshot().health()); assertTrue(copy.snapshot().active());
        assertEquals(next, copy.snapshot().position()); assertEquals(Hitbox.atFeet(next.point(), 2, 3), copy.snapshot().bounds());
        assertEquals(before.revision() + 1, copy.snapshot().revision());
        old.despawn(); assertTrue(copy.snapshot().active());
        assertEquals(42, copy.respawn(next).health());
    }
    @Test void editingDeadOrDormantNpcCannotReviveIt() {
        VirtualNpcState old = new VirtualNpcState(UUID.randomUUID(), START, 0.6, 1.8, 20);
        old.spawn(); old.damage(20);
        VirtualNpcState dead = old.reconfigured(START, 1, 2);
        assertEquals(0, dead.snapshot().health()); assertFalse(dead.snapshot().active());
        assertThrows(IllegalStateException.class, dead::spawn);
        old.respawn(START); old.damage(5); old.despawn();
        VirtualNpcState dormant = old.reconfigured(START, 1, 2);
        assertEquals(15, dormant.snapshot().health()); assertFalse(dormant.snapshot().active());
    }
    @Test void invalidReplacementCannotMutatePreviouslyActiveState() {
        VirtualNpcState old = new VirtualNpcState(UUID.randomUUID(), START, 0.6, 1.8, 20);
        var before = old.spawn();
        assertThrows(IllegalArgumentException.class, () -> old.reconfigured(START, Double.NaN, 2));
        assertThrows(IllegalArgumentException.class, () -> old.reconfigured(START, 1, 0));
        assertThrows(NullPointerException.class, () -> old.reconfigured(null, 1, 2));
        assertEquals(before, old.snapshot());
    }
}
