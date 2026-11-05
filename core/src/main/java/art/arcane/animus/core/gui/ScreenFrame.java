package art.arcane.animus.core.gui;

import art.arcane.animus.core.npc.NpcItem;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What one window shows: a title, a size and the display item of every filled slot. Text is already coloured.
 * A frame holds no platform objects, so screens render and are tested without a server.
 */
public record ScreenFrame(String title, String resolution, int width, int rows, Map<Integer, Item> items) {
    /** A display item. {@code head} names the player whose head a {@code minecraft:player_head} shows. */
    public record Item(NpcItem item, Optional<String> name, List<String> lore, Optional<String> head) {
        public Item { Objects.requireNonNull(item); Objects.requireNonNull(name); lore = List.copyOf(lore); Objects.requireNonNull(head); }
    }

    public ScreenFrame { Objects.requireNonNull(title); Objects.requireNonNull(resolution); items = Map.copyOf(items); }

    public int size() { return width * rows; }

    /** Two frames share a window when only their items differ. */
    public boolean sameWindow(ScreenFrame other) { return other != null && resolution.equals(other.resolution) && title.equals(other.title); }
}
