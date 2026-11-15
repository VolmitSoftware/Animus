package art.arcane.animus.bukkit.event;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * A moving minecart or boat ran into a virtual NPC's hitbox, on the thread that owns the NPC (the main thread, or its
 * region on Folia). The vehicle is not slowed: NPC bodies have no collision. Its riders are
 * {@link Vehicle#getPassengers()}.
 */
public final class VirtualNpcVehicleBumpEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID npcId;
    private final String npcKey;
    private final Vehicle vehicle;
    private final double speed;

    public VirtualNpcVehicleBumpEvent(UUID npcId, String npcKey, Vehicle vehicle, double speed) {
        this.npcId = Objects.requireNonNull(npcId); this.npcKey = Objects.requireNonNull(npcKey);
        this.vehicle = Objects.requireNonNull(vehicle); this.speed = speed;
    }
    public UUID getNpcId() { return npcId; }
    /** The NPC id used in commands and file names, such as {@code guard}. */
    public String getNpcKey() { return npcKey; }
    public Vehicle getVehicle() { return vehicle; }
    /** Blocks a tick across the ground when it hit. */
    public double getSpeed() { return speed; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
