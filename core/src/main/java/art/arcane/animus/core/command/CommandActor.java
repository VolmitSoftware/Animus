package art.arcane.animus.core.command;

import art.arcane.animus.core.text.ChatLine;
import art.arcane.animus.core.virtual.WorldPosition;
import art.arcane.volmlib.util.director.help.DirectorMiniMenu;
import java.util.List;
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

    /** Where the player stands, for commands that default to it; empty for the console. Read on the actor's owner. */
    default Optional<WorldPosition> position() { return Optional.empty(); }

    /** UUIDs of entities near the player, nearest first, for completing combat targets; empty for the console. */
    default List<UUID> nearby() { return List.of(); }

    /** A line exactly as given, without the prefix. */
    default void plain(String line) { tell(line); }

    /** A line with click chips. Platforms that show hover and click override this; the default prints the console text. */
    default void rich(ChatLine line) { plain(line.console()); }

    /** One {@code /animus} help page: rich lines for a player, the plain form for the console. */
    default void help(AnimusHelp.Page page) {
        if (player().isEmpty()) AnimusHelp.console(page).forEach(this::plain);
        else AnimusHelp.lines(page).forEach(this::rich);
    }

    /** A paged listing in the help look. Platforms that show hover and click override this; the default prints every row. */
    default void menu(DirectorMiniMenu.ContentMenu menu) {
        DirectorMiniMenu.renderContentConsole(menu).forEach(this::plain);
    }
}
