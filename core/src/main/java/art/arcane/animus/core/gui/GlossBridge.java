package art.arcane.animus.core.gui;

import java.nio.file.Path;
import java.util.Set;

/**
 * The optional link to an installed Gloss plugin. Animus never needs it: without Gloss every GUI kind still works,
 * and only opening Gloss's own menus and reading or writing Gloss's live folders report why they are unavailable.
 *
 * @param <P> the platform's online player
 */
public interface GlossBridge<P> {
    /** Null when Gloss is present; otherwise why it is not. */
    String unavailable();
    /** Gloss's data folder, {@code plugins/Gloss}. */
    Path folder();
    Set<String> menuIds();
    /** Opens a Gloss menu by its Gloss id. Call on the player's owner. False when Gloss refused or lacks the menu. */
    boolean open(P player, String menuId);

    static <P> GlossBridge<P> absent(String reason) {
        return new GlossBridge<>() {
            @Override public String unavailable() { return reason; }
            @Override public Path folder() { throw new IllegalStateException(reason); }
            @Override public Set<String> menuIds() { return Set.of(); }
            @Override public boolean open(P player, String menuId) { return false; }
        };
    }
}
