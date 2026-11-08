package art.arcane.animus.core.text;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Another plugin's placeholders, such as PlaceholderAPI's {@code %player_name%}, filled for one viewing player. Animus
 * fills its own {@code {player_name}} and {@code {{ }}} holes first; these are filled last, just before the text is
 * coloured and sent, so a placeholder's own colour codes still render. Call on the viewer's owner thread.
 */
public interface Placeholders {
    Placeholders NONE = new Placeholders() {
        @Override public String fill(UUID viewer, String text) { return text; }
        @Override public boolean active() { return false; }
    };

    /** The text with its placeholders filled for the viewer; text with no {@code %...%} pair comes back as it is. */
    String fill(UUID viewer, String text);

    /** Whether a placeholder plugin is installed now. */
    boolean active();

    /** {@code [placeholders] refreshTicks}: how often shown nameplates are filled again; 0 only when shown or changed. */
    default int refreshTicks() { return 0; }

    /** Whether the text holds a {@code %...%} pair, the only text worth handing to the placeholder plugin. */
    static boolean marked(String text) {
        if (text == null) return false;
        int first = text.indexOf('%');
        return first >= 0 && text.indexOf('%', first + 1) > first + 1;
    }

    /** Each line filled for the viewer; the same list when no line is marked. */
    default List<String> fill(UUID viewer, List<String> lines) {
        if (!active() || lines.stream().noneMatch(Placeholders::marked)) return lines;
        List<String> filled = new ArrayList<>(lines.size());
        for (String line : lines) filled.add(fill(viewer, line));
        return filled;
    }
}
