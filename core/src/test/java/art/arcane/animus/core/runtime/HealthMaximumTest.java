package art.arcane.animus.core.runtime;

import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.virtual.VirtualNpcState;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HealthMaximumTest {
    private static final WorldPosition START = new WorldPosition("world", new Vec3(1, 64, 2));

    private static double maximum(String modules) throws Exception {
        NpcDefinition definition = NpcDefinition.parse("""
                {"schemaVersion":1,"revision":1,"uuid":"00000000-0000-0000-0000-000000000040","id":"walker","group":"test","name":"Walker",
                 "type":"minecraft:player","spawned":true,"position":{"world":"world","x":1,"y":64,"z":2,"yaw":0,"pitch":0}%s}
                """.formatted(modules));
        DefinitionSpec<String> spec = DefinitionSpec.parse(definition, Set.of("minecraft:player"), (String world) -> world,
                (art.arcane.animus.core.npc.NpcItem item, String path, java.util.List<String> errors) -> item.id());
        return RouteRules.maximumHealth(spec);
    }

    @Test void maximumHealthAppliesWithoutKillableOrCombat() throws Exception {
        assertEquals(20, maximum(""));
        assertEquals(40, maximum(",\"modules\":{\"animus:health\":{\"maximumHealth\":40}}"));
        assertEquals(40, maximum(",\"modules\":{\"animus:health\":{\"killable\":true,\"maximumHealth\":40}}"));
    }

    private static VirtualNpcState at(double maximum, double damage) {
        VirtualNpcState state = new VirtualNpcState(UUID.randomUUID(), START, 0.6, 1.8, maximum);
        state.spawn();
        if (damage > 0) state.damage(damage);
        return state;
    }

    @Test void raisingTheMaximumFillsAFullNpcAndKeepsTheFractionOtherwise() {
        assertEquals(40, at(20, 0).reconfigured(START, 0.6, 1.8, 40).snapshot().health());
        assertEquals(20, at(20, 10).reconfigured(START, 0.6, 1.8, 40).snapshot().health());
    }

    @Test void loweringTheMaximumClamps() {
        assertEquals(20, at(40, 10).reconfigured(START, 0.6, 1.8, 20).snapshot().health());
        assertEquals(10, at(40, 30).reconfigured(START, 0.6, 1.8, 20).snapshot().health());
    }

    @Test void aDeadNpcStaysDeadWhenItsMaximumChanges() {
        VirtualNpcState dead = at(20, 20).reconfigured(START, 0.6, 1.8, 40);
        assertEquals(0, dead.snapshot().health());
        assertFalse(dead.snapshot().active());
    }
}
