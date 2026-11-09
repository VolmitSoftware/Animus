package art.arcane.animus.bukkit.event;

import art.arcane.animus.bukkit.api.NpcPosition;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * An NPC became active: its definition was spawned or loaded, or it respawned after dying. It fires where the
 * {@code spawn} behavior trigger fires. Asynchronous when Animus is not on a server thread.
 */
public final class VirtualNpcSpawnEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID npcId;
    private final String npcKey;
    private final NpcPosition position;

    public VirtualNpcSpawnEvent(UUID npcId, String npcKey, NpcPosition position, boolean async) {
        super(async);
        this.npcId = Objects.requireNonNull(npcId); this.npcKey = Objects.requireNonNull(npcKey); this.position = Objects.requireNonNull(position);
    }
    public UUID getNpcId() { return npcId; }
    /** The NPC id used in commands and file names, such as {@code guard}. */
    public String getNpcKey() { return npcKey; }
    public NpcPosition getPosition() { return position; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
