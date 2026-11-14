package art.arcane.animus.core.toolkit;

import java.util.Locale;
import java.util.Optional;

/** How much the Animus menu shows a player. Simple shows the common tasks; Advanced shows everything. Saved per player by {@link ToolkitPrefs}. */
public enum ToolkitMode {
    SIMPLE, ADVANCED;

    /** The lowercase word commands and the saved file use. */
    public String word() { return name().toLowerCase(Locale.ROOT); }

    /** The name players read: Simple or Advanced. */
    public String label() { return name().charAt(0) + word().substring(1); }

    /** The mode a word names, ignoring case and surrounding spaces; empty for anything else. */
    public static Optional<ToolkitMode> parse(String word) {
        if (word == null) return Optional.empty();
        String wanted = word.strip();
        for (ToolkitMode mode : values()) if (mode.word().equalsIgnoreCase(wanted)) return Optional.of(mode);
        return Optional.empty();
    }
}
