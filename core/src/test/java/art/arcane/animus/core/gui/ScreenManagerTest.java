package art.arcane.animus.core.gui;

import art.arcane.animus.core.gui.MockScreenHost.Player;
import art.arcane.animus.core.inventory.MockInventory.Stack;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScreenManagerTest {
    private final MockScreenHost host = new MockScreenHost();
    private final Set<UUID> active = new HashSet<>();
    private final List<String> warnings = new ArrayList<>();
    private final ScreenManager<Player, Stack> manager = new ScreenManager<>(host, active::contains, warnings::add);
    private final Player alex = new Player("Alex");
    private final List<Integer> clicks = new ArrayList<>();

    private class Counter implements Screen {
        final boolean teleportCloses;
        Counter(boolean teleportCloses) { this.teleportCloses = teleportCloses; }
        @Override public ScreenFrame render() {
            return new ScreenFrame("Counter " + clicks.size(), "9x1", 9, 1, Map.of(0, new ScreenFrame.Item(new art.arcane.animus.core.npc.NpcItem("minecraft:stone", 1, Optional.empty(), Optional.empty()), Optional.empty(), List.of(), Optional.empty())));
        }
        @Override public void click(int slot, GuiModel.Click click) { clicks.add(slot); }
        @Override public boolean closeOnTeleport() { return teleportCloses; }
    }

    private void open(Optional<UUID> npc, boolean teleportCloses) { assertTrue(manager.open(alex, npc, (ScreenManager<Player, Stack>.Viewer viewer) -> Optional.of(new Counter(teleportCloses)))); }

    @Test void clicksOnAStaleWindowAreIgnored() {
        open(Optional.empty(), true);
        Object stale = alex.window;
        manager.click(alex, stale, 0, GuiModel.Click.LEFT);
        assertEquals(List.of(0), clicks);
        manager.click(alex, new Object(), 0, GuiModel.Click.LEFT);
        manager.click(alex, alex.window, 99, GuiModel.Click.LEFT);
        assertEquals(List.of(0), clicks, "a forged window and an out-of-range slot do nothing");
    }

    @Test void aClientCloseForgetsTheScreen() {
        open(Optional.empty(), true);
        manager.closed(alex, alex.window);
        assertTrue(manager.viewer(alex.id).isEmpty());
        manager.click(alex, alex.window, 0, GuiModel.Click.LEFT);
        assertTrue(clicks.isEmpty());
    }

    @Test void aCloseOfAnOlderWindowKeepsTheNewOne() {
        open(Optional.empty(), true);
        Object first = alex.window;
        manager.click(alex, first, 0, GuiModel.Click.LEFT);
        assertNotSame(first, alex.window, "a new title needs a new window");
        manager.closed(alex, first);
        assertTrue(manager.viewer(alex.id).isPresent());
    }

    @Test void logoutForgetsWithoutTouchingTheClient() {
        open(Optional.empty(), true);
        manager.quit(alex);
        assertTrue(manager.viewer(alex.id).isEmpty());
        assertEquals(0, alex.closed);
    }

    @Test void deathAndWorldChangeClose() {
        open(Optional.empty(), false);
        manager.died(alex);
        assertEquals(1, alex.closed);
        open(Optional.empty(), false);
        manager.worldChanged(alex);
        assertEquals(2, alex.closed);
    }

    @Test void teleportClosesOnlyScreensThatAskForIt() {
        open(Optional.empty(), false);
        manager.teleported(alex);
        assertTrue(manager.viewer(alex.id).isPresent());
        open(Optional.empty(), true);
        manager.teleported(alex);
        assertTrue(manager.viewer(alex.id).isEmpty());
    }

    @Test void aDespawnedNpcClosesTheWindowsItOpened() {
        UUID baker = UUID.randomUUID();
        active.add(baker);
        open(Optional.of(baker), true);
        manager.tick(alex);
        assertTrue(manager.viewer(alex.id).isPresent());
        active.remove(baker);
        manager.tick(alex);
        assertTrue(manager.viewer(alex.id).isEmpty());
        assertEquals(1, alex.closed);
    }

    @Test void shutdownForgetsEveryWindow() {
        open(Optional.empty(), true);
        assertEquals(List.of(alex.id), manager.clear());
        assertTrue(manager.viewing().isEmpty());
    }

    @Test void delayedActionsAreBounded() {
        open(Optional.empty(), true);
        ScreenManager<Player, Stack>.Viewer viewer = manager.viewer(alex.id).orElseThrow();
        for (int index = 0; index < ScreenManager.MAXIMUM_PENDING; index++) assertTrue(viewer.schedule(5, () -> {}));
        art.arcane.animus.core.AnimusLog.shared().debug(true);
        try {
            assertFalse(viewer.schedule(5, () -> {}));
            assertEquals(1, warnings.size(), "debug mode logs the dropped action");
        } finally { art.arcane.animus.core.AnimusLog.shared().debug(false); }
        assertFalse(viewer.schedule(5, () -> {}));
        assertEquals(1, warnings.size(), "outside debug mode it is only counted");
    }

    @Test void aFrameThePlatformCannotDrawClosesTheScreen() {
        host.unsupported = Set.of("9x1");
        assertFalse(manager.open(alex, Optional.empty(), (ScreenManager<Player, Stack>.Viewer viewer) -> Optional.of(new Counter(true))));
        assertTrue(manager.viewer(alex.id).isEmpty());
    }

    @Test void aFailingScreenClosesInsteadOfLeavingAStuckWindow() {
        assertTrue(manager.open(alex, Optional.empty(), (ScreenManager<Player, Stack>.Viewer viewer) -> Optional.of(new Counter(true) {
            @Override public void click(int slot, GuiModel.Click click) { throw new IllegalStateException("boom"); }
        })));
        manager.click(alex, alex.window, 0, GuiModel.Click.LEFT);
        assertTrue(manager.viewer(alex.id).isEmpty());
        assertEquals(1, alex.closed);
        assertTrue(warnings.getFirst().contains("boom"));
    }
}
