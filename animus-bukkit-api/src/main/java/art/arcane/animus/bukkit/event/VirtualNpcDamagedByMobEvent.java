package art.arcane.animus.bukkit.event;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.entity.Mob;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Before an explicitly controlled real mob damages authoritative virtual NPC health. */
public final class VirtualNpcDamagedByMobEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID npcId;
    private final Mob mob;
    private double damage;
    private boolean cancelled;
    public VirtualNpcDamagedByMobEvent(UUID npcId, Mob mob, double damage) {
        this.npcId = Objects.requireNonNull(npcId); this.mob = Objects.requireNonNull(mob); setDamage(damage);
    }
    public UUID getNpcId() { return npcId; }
    public Mob getMob() { return mob; }
    public double getDamage() { return damage; }
    public void setDamage(double damage) {
        if (!Double.isFinite(damage) || damage <= 0 || damage > 100) throw new IllegalArgumentException("Damage must be (0,100]");
        this.damage = damage;
    }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
