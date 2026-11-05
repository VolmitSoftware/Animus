package art.arcane.animus.core.gui;

import java.util.Map;
import java.util.Optional;

/**
 * Builds screens by id for navigation between windows. An empty result means the viewer was already told why it
 * could not open.
 */
public interface Screens<P, S> {
    Optional<Screen> gui(ScreenManager<P, S>.Viewer viewer, String id, Map<String, String> args);
    Optional<Screen> shop(ScreenManager<P, S>.Viewer viewer, String id);
    /** {@code npc.*} for the NPC that opened the window, or null. */
    Object npc(ScreenManager<P, S>.Viewer viewer, String property);
}
