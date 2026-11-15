package art.arcane.animus.bukkit.event;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * A real projectile flew into a virtual NPC's hitbox, on the thread that owns the projectile (the main thread, or its
 * region on Folia). No world entity represents the NPC, so the server fires no hit event of its own. {@link #isStopped}
 * is true when the NPC's combat takes projectiles and the projectile struck the body; otherwise it flew on through.
 * Fired once per projectile and NPC, only while a player is near the NPC.
 */
public final class VirtualNpcProjectileHitEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID npcId;
    private final String npcKey;
    private final Projectile projectile;
    private final Location hit;
    private final boolean stopped;

    public VirtualNpcProjectileHitEvent(UUID npcId, String npcKey, Projectile projectile, Location hit, boolean stopped) {
        this.npcId = Objects.requireNonNull(npcId); this.npcKey = Objects.requireNonNull(npcKey);
        this.projectile = Objects.requireNonNull(projectile); this.hit = Objects.requireNonNull(hit).clone(); this.stopped = stopped;
    }
    public UUID getNpcId() { return npcId; }
    /** The NPC id used in commands and file names, such as {@code guard}. */
    public String getNpcKey() { return npcKey; }
    /** The projectile; its shooter is {@link Projectile#getShooter()}. */
    public Projectile getProjectile() { return projectile; }
    /** Where the projectile's path met the hitbox. */
    public Location getHitLocation() { return hit.clone(); }
    public boolean isStopped() { return stopped; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
