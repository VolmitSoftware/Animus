package art.arcane.animus.bukkit.event;

import art.arcane.animus.bukkit.api.NpcPosition;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * An NPC's health reached zero, where the {@code death} behavior trigger fires. {@code killerType} is
 * {@code player}, {@code mob} or {@code npc} when the killer is known. Asynchronous when Animus is not on a server thread.
 */
public final class VirtualNpcDeathEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID npcId;
    private final String npcKey;
    private final NpcPosition position;
    private final Optional<UUID> killer;
    private final Optional<String> killerType;

    public VirtualNpcDeathEvent(UUID npcId, String npcKey, NpcPosition position, Optional<UUID> killer, Optional<String> killerType, boolean async) {
        super(async);
        this.npcId = Objects.requireNonNull(npcId); this.npcKey = Objects.requireNonNull(npcKey); this.position = Objects.requireNonNull(position);
        this.killer = Objects.requireNonNull(killer); this.killerType = Objects.requireNonNull(killerType);
    }
    public UUID getNpcId() { return npcId; }
    /** The NPC id used in commands and file names, such as {@code guard}. */
    public String getNpcKey() { return npcKey; }
    public NpcPosition getPosition() { return position; }
    /** The UUID of the player, mob or NPC that dealt the killing blow, when known. */
    public Optional<UUID> getKiller() { return killer; }
    /** {@code player}, {@code mob} or {@code npc}, when the killer is known. */
    public Optional<String> getKillerType() { return killerType; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
