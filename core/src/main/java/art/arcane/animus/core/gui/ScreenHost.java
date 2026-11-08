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

    /**
     * Where a player stands and looks. {@code worlds} holds every name the player's world answers to (its name, key
     * and the identity NPC positions use), so a hologram anchor written either way matches.
     */
    record Pose(java.util.Set<String> worlds, double x, double y, double z, double eyeY, float yaw, float pitch, boolean sneaking) {
        public Pose { worlds = java.util.Set.copyOf(worlds); }
        public boolean in(String world) { return worlds.contains(world); }
    }
    /** The player's pose, or empty when it cannot be read on this thread. */
    Optional<Pose> pose(P player);
    /** {@code world} is a {@code namespace:key} or world name. False when that world is not loaded or the move was refused. */
    boolean teleport(P player, String world, double x, double y, double z, float yaw, float pitch);
    /**
     * Shows a boss bar on the named lane for {@code ticks}, replacing the lane's previous bar; 0 ticks hides the lane.
     * {@code color} is pink, blue, red, green, yellow, purple or white; {@code style} solid or segmented_6, 10, 12 or 20.
     */
    void bossBar(P player, String lane, String title, float progress, String color, String style, int ticks);
    /** False when the effect is unknown on this server. */
    boolean effect(P player, String effect, int ticks, int amplifier);
    /** Spawns particles only this player sees. False when the particle is unknown or needs data. */
    boolean particle(P player, String particle, int count, double spread, double x, double y, double z);
    /**
     * Sends coloured text to the players in scope: {@code server}, {@code world} (the source's world) or
     * {@code radius} blocks around the source, optionally only those with a permission. Each send runs on its
     * recipient's owner.
     */
    void broadcast(P source, String text, String scope, double radius, Optional<String> permission);

    TradeInventory<S> inventory(P player);
    ItemOps<S> items();
    /** A real stack for an item, or empty after adding a field error. Name and lore are already coloured. */
    Optional<S> stack(NpcItem item, Optional<String> name, List<String> lore, List<String> errors);
    void drop(P player, S stack);
    Economy economy();
    /**
     * Opens an owned shop's containers on this thread for one trade, after checking that each block still holds an
     * inventory and that protection lets the shop's owner open it. A platform without container shops refuses.
     */
    default art.arcane.animus.core.shop.ShopStock<S> stock(art.arcane.animus.core.shop.ShopDocument shop) {
        return art.arcane.animus.core.shop.ShopStock.refused("this platform has no container shops");
    }

    /**
     * What the player holds or wears in a hand or armor slot, in the definition item shape with its components. Empty
     * for an empty slot, for {@code body} and {@code saddle}, and on a platform that cannot read it.
     */
    default Optional<NpcItem> worn(P player, art.arcane.animus.core.npc.EquipmentSlot slot) { return Optional.empty(); }

    /** Whether the player holds the Animus selection wand in the main hand, found by its item marker, not its name. */
    default boolean holdsWand(P player) { return false; }
    /** Puts a selection wand in the player's inventory, or drops it at their feet when full. False when the platform has none. */
    default boolean giveWand(P player) { return false; }
    /** One chat line with its click chips; a platform without click text sends the console text. */
    default void chat(P player, art.arcane.animus.core.text.ChatLine line) { message(player, line.console()); }

    /**
     * A line of text asked of the player: an anvil, a sign or a chat capture. {@code answer} runs once on the player's
     * owner with the typed text, or empty when the player cancelled, closed the input or left; it never runs twice.
     */
    record Prompt(String label, String initial, java.util.function.Consumer<Optional<String>> answer) {
        /** The characters an anvil title shows before it runs past the window's edge. */
        public static final int TITLE = 20;

        public Prompt { java.util.Objects.requireNonNull(label); java.util.Objects.requireNonNull(initial); java.util.Objects.requireNonNull(answer); }

        /** The label cut to fit an anvil title; the chat line keeps the whole label. */
        public String title() { return label.length() <= TITLE ? label : label.substring(0, TITLE - 3).stripTrailing() + "..."; }
    }

    /**
     * Asks the player for text. The caller has already hidden its window. False when this platform has no text input,
     * in which case {@code answer} is never called.
     */
    default boolean prompt(P player, Prompt prompt) { return false; }
}
