package art.arcane.animus.core.owner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.api.NpcHandle;
import art.arcane.animus.core.command.PermissionNodes;
import art.arcane.animus.core.npc.NpcDefinition;
import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** While a behavior runs, each call on an owned NPC needs its owner's node now, not when the behavior was bound. */
class OwnerGateTest {
    private final Set<String> held = new HashSet<>();
    private final OwnerPermissions permissions = new OwnerPermissions() {
        @Override public Answer has(UUID player, String node) { throw new AssertionError("a running call never waits on a lookup"); }
        @Override public Answer cached(UUID player, String node) { return held.contains(node) ? Answer.YES : Answer.NO; }
    };

    @Test void aCommandOnAnOwnedNpcNeedsTheOwnersNodeAsItRuns() throws IOException {
        NpcDefinition stall = NpcOwnershipTest.npc("stall", Optional.of(OwnedNpcAttackTest.ALEX)), mayor = NpcOwnershipTest.npc("mayor", Optional.empty());
        OwnerGate gate = new OwnerGate(() -> Map.of("stall", stall, "mayor", mayor), () -> permissions);
        String command = "{\"command\":\"give {player_name} diamond\"}";
        held.add(PermissionNodes.OWNED_COMMANDS);
        assertEquals(Optional.empty(), gate.refused(new NpcHandle(stall.uuid()), "animus:command", command, true));
        held.clear();
        Optional<String> refused = gate.refused(new NpcHandle(stall.uuid()), "animus:command", command, true);
        assertTrue(refused.orElseThrow().contains("cannot run commands (action animus:command) unless its owner holds animus.owned.commands"), refused.get());
        assertEquals(Optional.empty(), gate.refused(new NpcHandle(mayor.uuid()), "animus:command", command, true), "a server NPC is not checked");
        assertEquals(Optional.empty(), gate.refused(new NpcHandle(stall.uuid()), "animus:say", "{\"text\":\"hi\"}", false), "talking needs nothing");
        assertTrue(gate.refused(new NpcHandle(stall.uuid()), "other:smite", "{}", true).isPresent(), "another plugin's privileged action needs the commands node");
        assertTrue(gate.refused(new NpcHandle(stall.uuid()), "animus:title", "{\"title\":\"hi\",\"player\":\"Steve\"}", false).orElseThrow().contains(PermissionNodes.OWNED_PLAYERS));
    }
}
