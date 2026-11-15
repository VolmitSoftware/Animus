package art.arcane.animus.bukkit.event;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * A player picked a dialogue choice and the click passed token, player, expiry and NPC validation. Fired on the thread
 * that owns the player, before the behavior hears the answer: cancelling it drops the answer, so the
 * {@code dialogue_choice} trigger does not fire. The prompt is used up either way.
 */
public final class VirtualNpcDialogueChoiceEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final UUID npcId;
    private final Optional<String> behavior;
    private final String prompt;
    private final String option;
    private boolean cancelled;

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
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
