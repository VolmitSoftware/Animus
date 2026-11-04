package art.arcane.animus.bukkit.event;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired after authoritative virtual hit validation; no Bukkit NPC entity is fabricated. */
public final class VirtualNpcInteractionEvent extends Event implements Cancellable {
    public enum Action { INTERACT, ATTACK }
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player viewer;
    private final UUID npcId;
    private final Action action;
    private final double distance;
    private final boolean preview;
    private boolean cancelled;

    public VirtualNpcInteractionEvent(Player viewer, UUID npcId, Action action, double distance, boolean preview) {
        this.viewer = Objects.requireNonNull(viewer);
        this.npcId = Objects.requireNonNull(npcId);
        this.action = Objects.requireNonNull(action);
        if (!Double.isFinite(distance) || distance < 0) throw new IllegalArgumentException("Invalid hit distance");
        this.distance = distance;
        this.preview = preview;
    }
    public Player getViewer() { return viewer; }
    public UUID getNpcId() { return npcId; }
    public Action getAction() { return action; }
    public double getDistance() { return distance; }
    /** Preview interactions must not trigger real commands, damage or other world side effects. */
    public boolean isPreview() { return preview; }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
