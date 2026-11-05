package art.arcane.animus.core.text;

import java.util.Objects;
import java.util.Optional;

/** A piece of a chat line: colorized text, an optional command run on click and optional colorized hover text. */
public record TextPart(String text, Optional<String> command, Optional<String> hover) {
    public TextPart {
        Objects.requireNonNull(text); Objects.requireNonNull(command); Objects.requireNonNull(hover);
    }

    public static TextPart of(String text) { return new TextPart(text, Optional.empty(), Optional.empty()); }
}
