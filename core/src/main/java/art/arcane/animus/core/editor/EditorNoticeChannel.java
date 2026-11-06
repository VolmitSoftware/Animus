package art.arcane.animus.core.editor;

import java.util.Optional;
import java.util.UUID;

/** How a platform reaches its online players with editor notices. Called from the editor's timer thread; never blocks. */
public interface EditorNoticeChannel {
    /** The permission that receives every editor notice; operators hold it by default. */
    String PERMISSION = "animus.editor.notify";

    /** The player's name when the platform knows it without blocking. */
    Optional<String> name(UUID player);

    /**
     * On each online player's own owner: when the notice {@link EditorNotice#reaches} the player, play the sound at the
     * player, then send the chat line with its hover and click, and the action bar.
     */
    void deliver(EditorNotice notice);
}
