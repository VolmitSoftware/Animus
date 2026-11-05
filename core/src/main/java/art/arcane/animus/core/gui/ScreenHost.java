package art.arcane.animus.core.gui;

import art.arcane.animus.core.inventory.Economy;
import art.arcane.animus.core.inventory.ItemOps;
import art.arcane.animus.core.inventory.TradeInventory;
import art.arcane.animus.core.npc.NpcItem;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What screens need from a server platform. Every method that takes a player runs on that player's owner thread.
 * The platform cancels every click and drag in an Animus window, keeps player items out of it, and reports the four
 * bound clicks, closes and player events to {@link ScreenManager}.
 *
 * @param <P> the platform's online player
 * @param <S> the platform's item stack
 */
public interface ScreenHost<P, S> {
    UUID id(P player);
    String name(P player);
    /** A {@code viewer.*} property such as {@code level} or {@code world}, or null when the platform has none. */
    Object viewer(P player, String property);
    boolean permitted(P player, String node);

    /**
     * Shows the frame. {@code window} is the token of the window already open for this screen, or null. Returns the
     * token of the window now showing, which is the same token when it was updated in place, or empty when the
     * platform cannot draw this frame (for example an unsupported resolution).
     */
    Optional<Object> show(P player, ScreenFrame frame, Object window);
    /** Closes the window if it is still the one given. */
    void close(P player, Object window);

    void message(P player, String text);
    void actionBar(P player, String text);
    void title(P player, String title, String subtitle, int fadeIn, int stay, int fadeOut);
    void sound(P player, String sound, String source, float volume, float pitch);
    /** Runs a command as the player, or as the console when the player is empty. */
    void command(Optional<P> player, String command);
    void broadcast(String text);

    TradeInventory<S> inventory(P player);
    ItemOps<S> items();
    /** A real stack for an item, or empty after adding a field error. Name and lore are already coloured. */
    Optional<S> stack(NpcItem item, Optional<String> name, List<String> lore, List<String> errors);
    void drop(P player, S stack);
    Economy economy();
}
