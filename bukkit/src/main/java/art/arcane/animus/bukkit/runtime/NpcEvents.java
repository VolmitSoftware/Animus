package art.arcane.animus.bukkit.runtime;

import art.arcane.animus.bukkit.api.NpcPosition;
import art.arcane.animus.bukkit.event.VirtualNpcDeathEvent;
import art.arcane.animus.bukkit.event.VirtualNpcDespawnEvent;
import art.arcane.animus.bukkit.event.VirtualNpcSpawnEvent;
import art.arcane.animus.bukkit.event.VirtualNpcWaypointEvent;
import art.arcane.animus.core.behavior.trigger.TriggerEvent;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Server;

/** Raises the public NPC lifecycle events. Each is asynchronous when the caller is not a server thread. */
final class NpcEvents {
    private NpcEvents() {}

    static NpcPosition position(WorldPosition at) { return new NpcPosition(at.world(), at.point().x(), at.point().y(), at.point().z()); }

    static void spawned(Server server, UUID npc, String id, WorldPosition at) {
        server.getPluginManager().callEvent(new VirtualNpcSpawnEvent(npc, id, position(at), !server.isPrimaryThread()));
    }
    static void despawned(Server server, UUID npc, String id) {
        server.getPluginManager().callEvent(new VirtualNpcDespawnEvent(npc, id, !server.isPrimaryThread()));
    }
    static void died(Server server, UUID npc, String id, WorldPosition at, Optional<TriggerEvent.Attacker> killer) {
        server.getPluginManager().callEvent(new VirtualNpcDeathEvent(npc, id, position(at), killer.map(TriggerEvent.Attacker::id),
                killer.map(TriggerEvent.Attacker::kind), !server.isPrimaryThread()));
    }
    static void waypoint(Server server, UUID npc, String id, UUID route, int waypoint, WorldPosition at) {
        server.getPluginManager().callEvent(new VirtualNpcWaypointEvent(npc, id, route, waypoint, position(at)));
    }
}
