package art.arcane.animus.core.toolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.gui.GuiBinding;
import art.arcane.animus.core.gui.GuiModel.Click;
import art.arcane.animus.core.gui.MockScreenHost;
import art.arcane.animus.core.storage.DocumentStore;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Hologram: the NPC's own lines added, edited, moved and removed, and a saved hologram shown above it at a height. */
class HologramScreenTest extends ToolkitHarness {
    static final String SIGN = "gui/holograms/town/sign.json";

    @BeforeEach void setUp() throws IOException {
        start(List.of(npcFile("guard", GUARD, ""), npcFile("baker", BAKER, ""),
                new DocumentStore.Change(SIGN, "missing", "{\"schemaVersion\":1,\"lines\":[\"Town board\"]}")), false);
        active.put(GUARD, 3.0);
        active.put(BAKER, 6.0);
    }

    void select(String... chosen) {
        toolkit.selection().clear(alex.id);
        for (String id : chosen) toolkit.selection().toggle(alex.id, id);
    }

    void open() {
        toolkit.open(alex.id);
        click("Hologram", Click.LEFT);
        assertEquals("Hologram", title());
    }

    List<String> lines(String id) {
        JsonObject hologram = npcs.find(id).orElseThrow().json().getAsJsonObject("hologram");
        return hologram == null ? List.of() : hologram.getAsJsonArray("lines").asList().stream().map((com.google.gson.JsonElement line) -> line.getAsString()).toList();
    }

    void add(String text) {
        click("Add line", Click.LEFT);
        assertEquals("New line", alex.prompt.label());
        MockScreenHost.answer(alex, Optional.of(text));
    }

    Optional<GuiBinding.Hologram> saved(String id) { return GuiBinding.parse(npcs.find(id).orElseThrow().json()).flatMap(GuiBinding::hologram); }

    @Test void simpleShowsTheLinesTheSavedHologramAndAnEmptyState() {
        select("guard");
        open();
        assertEquals(Map.of(4, "guard", 13, "No text yet", 28, "Saved hologram", 36, "Add line", 45, "Back", 53, "Help"), buttons());
        assertTrue(lore("Saved hologram").contains("Now: none"), lore("Saved hologram"));
        add("&6Welcome");
        add("Second");
        assertEquals(List.of("&6Welcome", "Second"), lines("guard"));
        assertEquals(Map.of(4, "guard", 9, "Line 1", 10, "Line 2", 28, "Saved hologram", 36, "Add line", 45, "Back", 53, "Help"), buttons());
        assertTrue(lore("Line 1").contains("Welcome") && lore("Line 1").contains("Right-click: details"), lore("Line 1"));
        assertTrue(lore("guard").contains("Now: 2 lines"), lore("guard"));
    }

    @Test void linesAreEditedMovedAndRemovedOnTheWholeSelection() {
        select("guard", "baker");
        open();
        add("Hello");
        add("World");
        assertEquals(List.of("Hello", "World"), lines("baker"), "every selected NPC gets the line");
        click("Line 1", Click.LEFT);
        assertEquals("Line 1", alex.prompt.label());
        assertEquals("Hello", alex.prompt.initial());
        MockScreenHost.answer(alex, Optional.of("Hi"));
        assertEquals(List.of("Hi", "World"), lines("guard"));
        click("Line 1", Click.LEFT);
        MockScreenHost.answer(alex, Optional.of(" "));
        assertEquals(List.of("Hi", "World"), lines("guard"), "blank keeps the line");

        click("Line 2", Click.RIGHT);
        assertEquals("Line 2", title());
        assertEquals(Map.of(4, "Line 2", 20, "Edit text", 21, "Move up", 35, "Remove", 45, "Back", 53, "Help"), buttons());
        click("Move up", Click.LEFT);
        assertEquals(List.of("World", "Hi"), lines("baker"));
        assertEquals("Line 1", title(), "the screen follows the line");
        assertEquals("Move down", name(HologramScreen.LineScreen.DOWN));
        click("Remove", Click.LEFT);
        assertEquals("Click again to confirm", name(HologramScreen.LineScreen.REMOVE));
        click(HologramScreen.LineScreen.REMOVE, Click.LEFT);
        assertEquals(List.of("Hi"), lines("guard"));
        assertEquals("Hologram", title(), "removing goes back");
    }

    @Test void linesThatDifferReadMixedAndEveryChangeGoesLineByLineOnEachNpc() throws IOException {
        store.commit(List.of(new DocumentStore.Change("npcs/town/guard.json", store.hash("npcs/town/guard.json"), npc("guard", GUARD, ",\"hologram\":{\"lines\":[\"Gate\",\"Closed\"]}")),
                new DocumentStore.Change("npcs/town/baker.json", store.hash("npcs/town/baker.json"), npc("baker", BAKER, ",\"hologram\":{\"lines\":[\"Bread\"]}"))));
        npcs.reload();
        select("guard", "baker");
        open();
        assertTrue(lore(HologramScreen.HEADER).contains("Now: Mixed"), lore(HologramScreen.HEADER));
        assertTrue(lore("Line 1").contains("Now: Mixed") && lore("Line 1").contains("Click: type it for all"), lore("Line 1"));
        assertTrue(lore("Line 2").contains("Now: Mixed"), "baker has no second line: " + lore("Line 2"));
        click("Line 2", Click.LEFT);
        assertEquals("", alex.prompt.initial(), "Mixed copies no NPC's text into the prompt");
        MockScreenHost.answer(alex, Optional.of("Open"));
        assertEquals(ToolScreen.CONFIRM, name(HologramScreen.FIRST_LINE + 1), "typing one text for lines that differ asks first");
        click(HologramScreen.FIRST_LINE + 1, Click.LEFT);
        assertEquals(List.of("Gate", "Open"), lines("guard"));
        assertEquals(List.of("Bread"), lines("baker"), "an NPC without that line is left alone");
        assertTrue(alex.messages.getLast().contains("1 skipped"), alex.messages.getLast());
        add("Welcome");
        assertEquals(List.of("Gate", "Open", "Welcome"), lines("guard"), "a new line goes at the end of each NPC's own lines");
        assertEquals(List.of("Bread", "Welcome"), lines("baker"));
        assertEquals(2, changes().size(), "each edit is one change on all of them");
    }

    @Test void aHeightThatDiffersReadsMixedAndSettingItKeepsEachNpcsOtherAxes() throws IOException {
        new ShopFiles(npcs, store).bindHologram("town:sign", List.of("guard", "baker"));
        new ShopFiles(npcs, store).offset("town:sign", List.of("guard"), 0, 1.0);
        select("guard", "baker");
        open();
        assertTrue(lore("Height").contains("Now: Mixed"), lore("Height"));
        click("Height", Click.LEFT);
        MockScreenHost.answer(alex, Optional.of("3"));
        assertEquals(ToolScreen.CONFIRM, name(HologramScreen.HEIGHT));
        click(HologramScreen.HEIGHT, Click.LEFT);
        double[] guard = saved("guard").orElseThrow().offset().orElseThrow(), baker = saved("baker").orElseThrow().offset().orElseThrow();
        assertEquals(List.of(1.0, 3.0, 0.0), List.of(guard[0], guard[1], guard[2]), "guard keeps its own sideways offset");
        assertEquals(List.of(0.0, 3.0, 0.0), List.of(baker[0], baker[1], baker[2]));
    }

    @Test void theLastLineRemovedLeavesNoHologram() {
        select("guard");
        open();
        add("Only");
        click("Line 1", Click.RIGHT);
        click("Remove", Click.LEFT);
        click(HologramScreen.LineScreen.REMOVE, Click.LEFT);
        assertFalse(npcs.find("guard").orElseThrow().json().has("hologram"));
        assertTrue(shows("No text yet"));
    }

    @Test void aSavedHologramShowsAboveTheSelectionAtAHeight() {
        select("guard", "baker");
        open();
        click("Saved hologram", Click.LEFT);
        assertEquals("Saved holograms", title());
        assertEquals("Search", name(ToolScreen.SEARCH));
        assertTrue(lore("town:sign").contains("Click: show it above 2 NPCs"), lore("town:sign"));
        click("town:sign", Click.LEFT);
        assertEquals("Hologram", title(), "picking goes back");
        assertEquals("town:sign", saved("baker").orElseThrow().id());
        assertTrue(lore("Saved hologram").contains("Now: town:sign"));
        assertEquals("Height", name(HologramScreen.HEIGHT));
        assertTrue(lore("Height").contains("Now: above the head"), lore("Height"));
        assertEquals("Remove", name(HologramScreen.REMOVE));
        assertFalse(shows("Offset X"), "Simple sets the height only");

        click("Height", Click.LEFT);
        double[] offset = saved("guard").orElseThrow().offset().orElseThrow();
        assertEquals(List.of(0.0, 2.5, 0.0), List.of(offset[0], offset[1], offset[2]));
        click("Height", Click.RIGHT);
        click("Height", Click.RIGHT);
        assertEquals(2.3, saved("baker").orElseThrow().offset().orElseThrow()[1]);
        click("Height", Click.SHIFT_LEFT);
        assertEquals("Height", alex.prompt.label());
        assertEquals("2.3", alex.prompt.initial());
        MockScreenHost.answer(alex, Optional.of("3"));
        assertEquals(3.0, saved("guard").orElseThrow().offset().orElseThrow()[1]);
        assertTrue(lore("Height").contains("Now: 3 blocks"), lore("Height"));
        click("Height", Click.SHIFT_LEFT);
        MockScreenHost.answer(alex, Optional.of("99"));
        assertTrue(alex.messages.getLast().contains("Keep it within 16 blocks"), alex.messages.getLast());
        click("Height", Click.SHIFT_LEFT);
        MockScreenHost.answer(alex, Optional.of(""));
        assertTrue(saved("guard").orElseThrow().offset().isEmpty(), "blank puts it back above the head");

        click("Remove", Click.LEFT);
        assertTrue(saved("guard").isEmpty() && saved("baker").isEmpty());
        assertFalse(shows("Height"));
    }

    @Test void advancedAddsTheOffsetsAndEveryHologramSetting() {
        toolkit.mode(alex.id, ToolkitMode.ADVANCED);
        select("guard");
        open();
        assertEquals("More settings", name(HologramScreen.SETTINGS));
        click("Saved hologram", Click.LEFT);
        click("town:sign", Click.LEFT);
        assertEquals("Offset X", name(HologramScreen.X));
        assertEquals("Offset Z", name(HologramScreen.Z));
        click("Offset X", Click.RIGHT);
        double[] offset = saved("guard").orElseThrow().offset().orElseThrow();
        assertEquals(List.of(-0.1, 2.4, 0.0), List.of(offset[0], offset[1], offset[2]));
        click("Above the head", Click.LEFT);
        assertTrue(saved("guard").orElseThrow().offset().isEmpty());
        click("More settings", Click.LEFT);
        assertEquals("Hologram settings", title());
    }

    @Test void aSavedHologramsDetailsUseItAndRefuseDeletingWhileShown() throws IOException {
        select("guard");
        open();
        click("Saved hologram", Click.LEFT);
        click("town:sign", Click.RIGHT);
        assertEquals("town:sign", title());
        assertEquals(Map.of(4, "town:sign", 20, "Use it", 35, "Delete", 45, "Back", 53, "Help"), buttons());
        assertTrue(lore("town:sign").contains("Now: shown by no NPC"), lore("town:sign"));
        click("Use it", Click.LEFT);
        assertEquals("town:sign", saved("guard").orElseThrow().id());
        assertTrue(lore(4).contains("Now: shown by guard"), lore(4));
        click("Delete", Click.LEFT);
        click(SavedHologramScreen.Detail.DELETE, Click.LEFT);
        assertTrue(alex.messages.getLast().contains("is used by guard"), alex.messages.getLast());
        assertNotNull(store.read(SIGN));
    }

    @Test void anUnusedSavedHologramIsDeletedAfterAsking() throws IOException {
        toolkit.open(alex.id, SavedHologramScreen::new);
        assertTrue(lore("town:sign").contains("Click: details"), "nothing selected opens it");
        click("town:sign", Click.LEFT);
        click("Delete", Click.LEFT);
        assertNotNull(store.read(SIGN), "the first click only asks");
        click(SavedHologramScreen.Detail.DELETE, Click.LEFT);
        assertNull(store.read(SIGN));
        assertEquals("Saved holograms", title());
        assertTrue(shows("No saved holograms yet"));
        assertEquals("animus gui reload", host.console.getLast(), "the catalog reloads");
    }
}
