package art.arcane.animus.core.command;

import art.arcane.animus.core.text.ChatLine;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import art.arcane.volmlib.util.director.help.DirectorMiniMenu;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.MiniMessage;

/** Whoever issued an Animus command: a player or the console. Read and answer only on the actor's own owner. */
public interface CommandActor {
    /** The reply when the shared file lane refuses work. */
    /** Shown when more edits wait than [files] maximumOperations allows; scripts should pause briefly and send the command again. */
    String BUSY = "File queue full ([files] maximumOperations edits are waiting); wait a moment and send it again";
    boolean permitted(String node);
    /** One reply line in the Animus look; plain text gets the {@code [Animus]} prefix. */
    void tell(String message);
    /** The player's UUID, or empty for the console. */
    Optional<UUID> player();
    /** Runs the action on the actor's owner later. False when it cannot be accepted. */
    boolean reply(Runnable action);

    /** Where the player stands, for commands that default to it; empty for the console. Read on the actor's owner. */
    default Optional<WorldPosition> position() { return Optional.empty(); }

    /** Which way the player faces, for {@code ^} coordinates; empty for the console. Read on the actor's owner. */
    default Optional<Coordinates.Facing> facing() { return Optional.empty(); }

    /** The block the player looks at within reach, for completing coordinates; empty for the console or open air. */
    default Optional<Vec3> target() { return Optional.empty(); }

    /** Plays {@code sound} to the player on the player's own owner; the console hears nothing. */
    default void play(CommandSound sound) {}

    /** UUIDs of entities near the player, nearest first, for completing combat targets; empty for the console. */
    default List<UUID> nearby() { return List.of(); }

    /** A line exactly as given, without the prefix. */
    default void plain(String line) { tell(line); }

    /** A line with click chips. Platforms that show hover and click override this; the default prints the console text. */
    default void rich(ChatLine line) { plain(line.console()); }

    /** One {@code /animus} help page: the shared menu look for a player, the plain form for the console. */
    default void help(AnimusHelp.Page page) {
        if (player().isEmpty()) AnimusHelp.console(page).forEach(this::plain);
        else page(AnimusHelp.lines(page));
    }

    /** A paged listing in the help look: the page for a player, every row for the console. */
    default void menu(DirectorMiniMenu.ContentMenu menu) {
        if (player().isEmpty()) DirectorMiniMenu.renderContentConsole(menu).forEach(this::plain);
        else page(DirectorMiniMenu.renderContent(menu, AnimusDirector.THEME, AnimusDirector.TEXT));
    }

    /**
     * A whole page of MiniMessage lines, as VolmLib's menus render them. Platforms that show hover and click clear the chat
     * window first and send each line styled, as every Volmit plugin does; the default prints each line as plain text.
     */
    default void page(List<String> lines) {
        for (String line : lines) if (!line.isBlank()) plain(MiniMessage.miniMessage().stripTags(line));
    }
}
