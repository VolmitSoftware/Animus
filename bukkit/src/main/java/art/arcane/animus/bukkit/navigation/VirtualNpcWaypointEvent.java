package art.arcane.animus.bukkit.navigation;

import art.arcane.animus.core.virtual.WorldPosition;
import java.util.*;
import org.bukkit.event.*;

/** Owner-thread arrival receipt. waypoint=-1 denotes a manual destination; no world entity represents this NPC. */
public final class VirtualNpcWaypointEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID npcId, routeId;
    private final String npcName;
    private final int waypoint;
    private final WorldPosition position;
    public VirtualNpcWaypointEvent(UUID npcId, String npcName, UUID routeId, int waypoint, WorldPosition position) {
        this.npcId = Objects.requireNonNull(npcId); this.npcName = Objects.requireNonNull(npcName);
        this.routeId = Objects.requireNonNull(routeId); this.waypoint = waypoint; this.position = Objects.requireNonNull(position);
    }
    public UUID getNpcId() { return npcId; }
    public String getNpcName() { return npcName; }
    public UUID getRouteId() { return routeId; }
    public int getWaypoint() { return waypoint; }
    public WorldPosition getPosition() { return position; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
