package art.arcane.animus.bukkit.event;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.entity.Item;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * A player or mob dropped an item within three blocks of a virtual NPC, or threw it into its hitbox, on the thread that
 * owns the NPC (the main thread, or its region on Folia). Fired once per item, while a player is near the NPC. Who threw it
 * is {@link Item#getThrower()}; items nobody threw, such as loot and block drops, are not reported.
 */
public final class VirtualNpcItemDropEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID npcId;
    private final String npcKey;
    private final Item item;
    private final boolean thrown;

    public VirtualNpcItemDropEvent(UUID npcId, String npcKey, Item item, boolean thrown) {
        this.npcId = Objects.requireNonNull(npcId); this.npcKey = Objects.requireNonNull(npcKey);
        this.item = Objects.requireNonNull(item); this.thrown = thrown;
    }
    public UUID getNpcId() { return npcId; }
    /** The NPC id used in commands and file names, such as {@code guard}. */
    public String getNpcKey() { return npcKey; }
    public Item getItem() { return item; }
    /** The item flew into the hitbox rather than landing beside it. */
    public boolean isThrown() { return thrown; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
