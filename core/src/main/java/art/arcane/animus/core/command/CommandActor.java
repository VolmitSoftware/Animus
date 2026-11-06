package art.arcane.animus.core.command;

import art.arcane.animus.core.text.ChatLine;
import art.arcane.volmlib.util.director.help.DirectorMiniMenu;
import java.util.Optional;
import java.util.UUID;

/** Whoever issued an Animus command: a player or the console. Read and answer only on the actor's own owner. */
public interface CommandActor {
    /** The reply when the shared file lane refuses work. */
    String BUSY = "File queue full; try again in a moment";
    boolean permitted(String node);
    /** One reply line in the Animus look; plain text gets the {@code [Animus]} prefix. */
    void tell(String message);
    /** The player's UUID, or empty for the console. */
    Optional<UUID> player();
    /** Runs the action on the actor's owner later. False when it cannot be accepted. */
    boolean reply(Runnable action);

    /** A line exactly as given, without the prefix. */
    default void plain(String line) { tell(line); }

    /** A line with click chips. Platforms that show hover and click override this; the default prints the console text. */
    default void rich(ChatLine line) { plain(line.console()); }

    /**
     * A help page or listing in the DirectorMiniMenu look. Platforms that can show hover and click text override this;
     * the default prints the console rendering.
     */
    default void menu(DirectorMiniMenu.DirectorHelpPage page) {
        DirectorMiniMenu.renderConsole(page, AnimusDirector.TEXT).forEach(this::plain);
    }

    default void menu(DirectorMiniMenu.ContentMenu menu) {
        DirectorMiniMenu.renderContentConsole(menu).forEach(this::plain);
    }
}
