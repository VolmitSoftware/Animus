package art.arcane.animus.core.toolkit;

import java.util.Objects;

/**
 * The second click a list entry needs before it changes more than {@link ToolScreen#BULK} NPCs, for entries whose
 * clicks do different things and so cannot be a {@code guarded} button. The first click arms the entry; the same click
 * on the same entry runs it; any other entry click disarms it.
 */
final class BulkGuard {
    private String armed;

    /** True when the change should run now: it is small, or this is the second click on the armed entry. */
    boolean ready(String key, int affected) {
        if (affected <= ToolScreen.BULK) { armed = null; return true; }
        if (Objects.equals(armed, key)) { armed = null; return true; }
        armed = key;
        return false;
    }

    boolean armed(String key) { return Objects.equals(armed, key); }

    void clear() { armed = null; }
}
