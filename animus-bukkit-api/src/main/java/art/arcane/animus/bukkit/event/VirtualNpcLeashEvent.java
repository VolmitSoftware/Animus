package art.arcane.animus.bukkit.event;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * A mob on a lead met a virtual NPC. {@code TIE}: the player leading it right-clicked the NPC, on the player's thread;
 * the lead stays with the player, since a virtual NPC cannot hold one. {@code PASS}: it was led within three blocks of
 * the NPC, on the NPC's thread. Fired once per mob, while a player is near the NPC.
 */
public final class VirtualNpcLeashEvent extends Event {
    /** A player tying a led mob to the NPC, or a led mob passing near it. */
    public enum Kind { TIE, PASS }
    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID npcId;
    private final String npcKey;
    private final LivingEntity mob;
    private final Entity holder;
    private final Kind kind;

    public VirtualNpcLeashEvent(UUID npcId, String npcKey, LivingEntity mob, Entity holder, Kind kind) {
        this.npcId = Objects.requireNonNull(npcId); this.npcKey = Objects.requireNonNull(npcKey);
        this.mob = Objects.requireNonNull(mob); this.holder = Objects.requireNonNull(holder); this.kind = Objects.requireNonNull(kind);
    }
    public UUID getNpcId() { return npcId; }
    /** The NPC id used in commands and file names, such as {@code guard}. */
    public String getNpcKey() { return npcKey; }
    public LivingEntity getMob() { return mob; }
    /** Whoever holds the lead, usually a player. */
    public Entity getHolder() { return holder; }
    public Kind getKind() { return kind; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
