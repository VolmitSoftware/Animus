package art.arcane.animus.core.gui;

import art.arcane.animus.core.gui.MockScreenHost.Player;
import art.arcane.animus.core.inventory.MockInventory.Stack;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.text.Placeholders;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Window titles, item names, lore and trades are filled for the viewer; editing tools show placeholders as written. */
class ScreenPlaceholdersTest {
    private final MockScreenHost host = new MockScreenHost();
    private final ScreenManager<Player, Stack> manager = new ScreenManager<>(host, (UUID npc) -> true, (String warning) -> { throw new AssertionError(warning); });
    private final Player alex = new Player("Alex");

    /** A fake PlaceholderAPI: the viewer's name, and a coloured rank so a placeholder's own colour codes are seen to render. */
    private final Placeholders fake = new Placeholders() {
        @Override public String fill(UUID viewer, String text) {
            return viewer.equals(alex.id) ? text.replace("%player_name%", "Alex").replace("%rank%", "&6Gold") : text;
        }
        @Override public boolean active() { return true; }
    };

    private static ScreenFrame.Item item(String name, String lore) {
        return new ScreenFrame.Item(new NpcItem("minecraft:stone", 1, Optional.empty(), Optional.empty()), Optional.of(name), List.of(lore), Optional.empty());
    }

    private record Fixed(ScreenFrame frame, boolean fills) implements Screen {
        @Override public ScreenFrame render() { return frame; }
        @Override public void click(int slot, GuiModel.Click click) {}
        @Override public boolean fillsPlaceholders() { return fills; }
    }

    @Test void shouldFillTitleNamesAndLoreForTheViewer() {
        manager.placeholders(() -> fake);
        ScreenFrame frame = new ScreenFrame("Shop for %player_name%", "9x1", 9, 1, Map.of(0, item("%rank% sword", "Owned by %player_name%")));
        assertTrue(manager.open(alex, Optional.empty(), (ScreenManager<Player, Stack>.Viewer viewer) -> Optional.of(new Fixed(frame, true))));
        assertEquals("Shop for Alex", alex.frame.title());
        assertEquals(Optional.of("§6Gold sword"), alex.frame.items().get(0).name());
        assertEquals(List.of("Owned by Alex"), alex.frame.items().get(0).lore());
    }

    @Test void shouldShowEditingToolsAsWritten() {
        manager.placeholders(() -> fake);
        ScreenFrame frame = new ScreenFrame("Rename %player_name%", "9x1", 9, 1, Map.of());
        assertTrue(manager.open(alex, Optional.empty(), (ScreenManager<Player, Stack>.Viewer viewer) -> Optional.of(new Fixed(frame, false))));
        assertEquals("Rename %player_name%", alex.frame.title());
    }

    @Test void shouldFillTradeNames() {
        ScreenFrame.Offer offer = new ScreenFrame.Offer(item("Cost", "x"), Optional.of(item("%player_name% coin", "y")), item("Prize", "for %player_name%"), 0, 5);
        ScreenFrame filled = ScreenFrame.merchant("%player_name%'s trades", new ScreenFrame.Merchant(List.of(offer))).filled(fake, alex.id);
        assertEquals("Alex's trades", filled.title());
        ScreenFrame.Offer shown = filled.merchant().orElseThrow().offers().getFirst();
        assertEquals(Optional.of("Alex coin"), shown.costB().orElseThrow().name());
        assertEquals(List.of("for Alex"), shown.result().lore());
    }

    @Test void shouldKeepTheSameFrameWithoutPlaceholders() {
        ScreenFrame frame = new ScreenFrame("Plain", "9x1", 9, 1, Map.of(0, item("Stone", "50% off")));
        assertSame(frame, frame.filled(fake, alex.id));
        ScreenFrame marked = new ScreenFrame("Hi %player_name%", "9x1", 9, 1, Map.of());
        assertSame(marked, marked.filled(Placeholders.NONE, alex.id), "nothing is filled without PlaceholderAPI");
    }
}
