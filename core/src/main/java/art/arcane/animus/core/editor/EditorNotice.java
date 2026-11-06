package art.arcane.animus.core.editor;

import art.arcane.animus.core.config.EditorNotifySettings;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One in-game notice of an editor event, ready to send. Texts are section-coded and must not be read for colour codes
 * again. {@code owner} is the player who paired the token; they get the notice even without the notify permission.
 */
public record EditorNotice(Optional<UUID> owner, Optional<EditorNotifySettings.Sound> sound, Optional<Line> chat, Optional<String> actionBar) {
    public EditorNotice {
        Objects.requireNonNull(owner); Objects.requireNonNull(sound); Objects.requireNonNull(chat); Objects.requireNonNull(actionBar);
    }

    /** One chat line. A click puts {@code suggest} into the chat box; {@code copy} follows the line as its own clickable piece. */
    public record Line(String text, Optional<String> hover, Optional<String> suggest, Optional<Copy> copy) {
        public Line { Objects.requireNonNull(text); Objects.requireNonNull(hover); Objects.requireNonNull(suggest); Objects.requireNonNull(copy); }
    }

    /** A piece of the line that copies {@code value} to the clipboard when clicked. */
    public record Copy(String text, String value, String hover) {
        public Copy { Objects.requireNonNull(text); Objects.requireNonNull(value); Objects.requireNonNull(hover); }
    }

    /** True when the player should get this notice: the token's owner, or a holder of {@link EditorNoticeChannel#PERMISSION}. */
    public boolean reaches(UUID player, boolean permitted) { return permitted || owner.isPresent() && owner.get().equals(player); }
}
