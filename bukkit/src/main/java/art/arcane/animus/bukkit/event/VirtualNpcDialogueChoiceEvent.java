package art.arcane.animus.bukkit.event;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired on the owner thread after a dialogue click passes token, player, expiry and NPC validation. */
public final class VirtualNpcDialogueChoiceEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final UUID npcId;
    private final Optional<String> behavior;
    private final String prompt;
    private final String option;

    public VirtualNpcDialogueChoiceEvent(Player player, UUID npcId, Optional<String> behavior, String prompt, String option) {
        this.player = Objects.requireNonNull(player);
        this.npcId = Objects.requireNonNull(npcId);
        this.behavior = Objects.requireNonNull(behavior);
        this.prompt = Objects.requireNonNull(prompt);
        this.option = Objects.requireNonNull(option);
    }

    public Player getPlayer() { return player; }
    public UUID getNpcId() { return npcId; }
    public Optional<String> getBehavior() { return behavior; }
    public String getPrompt() { return prompt; }
    public String getOption() { return option; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
