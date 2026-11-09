package art.arcane.animus.bukkit.event;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * An active NPC stopped being active: it was despawned, deleted or replaced by a definition with another UUID. It
 * fires where the {@code despawn} behavior trigger fires, and also after a death. Asynchronous when Animus is not on a
 * server thread.
 */
public final class VirtualNpcDespawnEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID npcId;
    private final String npcKey;

    public VirtualNpcDespawnEvent(UUID npcId, String npcKey, boolean async) {
        super(async);
        this.npcId = Objects.requireNonNull(npcId); this.npcKey = Objects.requireNonNull(npcKey);
    }
    public UUID getNpcId() { return npcId; }
    /** The NPC id used in commands and file names, such as {@code guard}. */
    public String getNpcKey() { return npcKey; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
