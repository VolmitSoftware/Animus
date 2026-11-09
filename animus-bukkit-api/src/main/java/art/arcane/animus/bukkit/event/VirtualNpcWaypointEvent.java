package art.arcane.animus.bukkit.event;

import art.arcane.animus.bukkit.api.NpcPosition;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * An NPC reached a waypoint of its route, on the thread that owns the NPC (the main thread, or its region on Folia).
 * {@code waypoint} is the route index, or -1 for a destination given by command or behavior. No world entity
 * represents the NPC.
 */
public final class VirtualNpcWaypointEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID npcId, routeId;
    private final String npcKey;
    private final int waypoint;
    private final NpcPosition position;

    public VirtualNpcWaypointEvent(UUID npcId, String npcKey, UUID routeId, int waypoint, NpcPosition position) {
        this.npcId = Objects.requireNonNull(npcId); this.npcKey = Objects.requireNonNull(npcKey);
        this.routeId = Objects.requireNonNull(routeId); this.waypoint = waypoint; this.position = Objects.requireNonNull(position);
    }
    public UUID getNpcId() { return npcId; }
    /** The NPC id used in commands and file names, such as {@code guard}. */
    public String getNpcKey() { return npcKey; }
    public UUID getRouteId() { return routeId; }
    public int getWaypoint() { return waypoint; }
    public NpcPosition getPosition() { return position; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
