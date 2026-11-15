package art.arcane.animus.bukkit.event;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.entity.Entity;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * A real entity started to touch a virtual NPC's hitbox, on the thread that owns the NPC (the main thread, or its region
 * on Folia). NPC bodies have no collision, so the entity passes into the body; this fires when the touch begins and
 * again only after the entity has stepped clear. Seen while a player is near the NPC, a few times a second.
 */
public final class VirtualNpcContactEvent extends Event {
    /** {@code TOP}: the entity stands on the hitbox. {@code SIDE}: any other touch. */
    public enum Contact { TOP, SIDE }
    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID npcId;
    private final String npcKey;
    private final Entity entity;
    private final Contact contact;

    public VirtualNpcContactEvent(UUID npcId, String npcKey, Entity entity, Contact contact) {
        this.npcId = Objects.requireNonNull(npcId); this.npcKey = Objects.requireNonNull(npcKey);
        this.entity = Objects.requireNonNull(entity); this.contact = Objects.requireNonNull(contact);
    }
    public UUID getNpcId() { return npcId; }
    /** The NPC id used in commands and file names, such as {@code guard}. */
    public String getNpcKey() { return npcKey; }
    public Entity getEntity() { return entity; }
    public Contact getContact() { return contact; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
