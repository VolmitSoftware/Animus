package art.arcane.animus.core.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.editor.DocumentKind;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GuiDraftsTest {
    @TempDir Path root;

    @Test void anEmptyChestGuiLoadsAndATakenIdIsRefused() throws IOException {
        try (DocumentStore store = new DocumentStore(root)) {
            String line = GuiDrafts.create(store, DocumentKind.INVENTORY, "town:board", GuiDrafts.inventory("&8Notice board", 4));
            assertTrue(line.startsWith("Created gui/inventories/town/board.json; tx "), line);
            GuiDocument board = GuiDocument.parse("town:board", store.read("gui/inventories/town/board.json")).document().orElseThrow();
            assertEquals(4, board.rows());
            assertEquals("gui/inventories/town/board.json already exists; pick another id",
                    assertThrows(IOException.class, () -> GuiDrafts.create(store, DocumentKind.INVENTORY, "town:board", GuiDrafts.inventory("Again", 1))).getMessage());
            assertEquals("rows= is 1 to 6", assertThrows(IOException.class, () -> GuiDrafts.inventory("Too tall", 7)).getMessage());
            assertEquals("An id is namespace:path in lowercase, such as town:welcome",
                    assertThrows(IOException.class, () -> GuiDrafts.create(store, DocumentKind.INVENTORY, "Board", GuiDrafts.inventory("x", 1))).getMessage());
        }
    }

    @Test void aHologramStandsAtHeadHeightWhereThePlayerIs() throws IOException {
        try (DocumentStore store = new DocumentStore(root)) {
            GuiDrafts.create(store, DocumentKind.HOLOGRAM, "town:welcome", GuiDrafts.hologram(new WorldPosition("world", new Vec3(10.25, 64, -3)), 90f, "&6Welcome"));
            HologramDocument hologram = HologramDocument.parse("town:welcome", store.read("gui/holograms/town/welcome.json")).document().orElseThrow();
            assertEquals("world", hologram.world());
            assertEquals(65.5, hologram.y());
            assertEquals(10.25, hologram.x());
            assertEquals(90f, hologram.yaw());
            assertEquals(1, hologram.lines().size());
        }
    }
}
