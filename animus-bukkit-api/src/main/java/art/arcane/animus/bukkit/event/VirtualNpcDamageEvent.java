package art.arcane.animus.bukkit.event;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Before a virtual NPC damages a real entity. The following Bukkit DamageSource has no fabricated attacker. */
public final class VirtualNpcDamageEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID npcId;
    private final LivingEntity target;
    private double damage;
    private boolean cancelled;
    public VirtualNpcDamageEvent(UUID npcId, LivingEntity target, double damage) {
        this.npcId = Objects.requireNonNull(npcId); this.target = Objects.requireNonNull(target); setDamage(damage);
    }
    public UUID getNpcId() { return npcId; }
    public LivingEntity getTarget() { return target; }
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
