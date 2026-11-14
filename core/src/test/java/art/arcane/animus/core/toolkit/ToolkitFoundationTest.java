package art.arcane.animus.core.toolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.gui.GuiModel.Click;
import art.arcane.animus.core.gui.MockScreenHost;
import art.arcane.animus.core.gui.MockScreenHost.Player;
import art.arcane.animus.core.gui.ScreenFrame;
import art.arcane.animus.core.gui.ScreenHost;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.inventory.MockInventory.Stack;
import art.arcane.animus.core.npc.NpcItem;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The pieces every tool screen shares: text prompts, wand picks, guarded buttons and value stepping. */
class ToolkitFoundationTest extends ToolkitHarness {
    final List<String> answers = new ArrayList<>();
    int changes;

    /** A screen with one of each shared button. */
    final class ProbeScreen extends ToolScreen<Player, Stack> {
        double number = 1;
        String choice = "walk";

        ProbeScreen(Toolkit<Player, Stack> toolkit, ScreenManager<Player, Stack>.Viewer viewer, Toolkit.Commands commands) { super(toolkit, viewer, commands); }
        @Override String title() { return "Probe"; }
        @Override int rows() { return 6; }
        @Override void draw() {
            button(0, "minecraft:paper", title("Rename"), any("type a name"), (Click click) -> prompt("New name", "old", "Letters and digits", answers::add));
            button(1, "minecraft:saddle", title("Vehicle"), any("click an NPC"), (Click click) -> pick("Vehicle", (String npc) -> answers.add("vehicle " + npc)));
            confirm(2, "minecraft:red_concrete", "Delete", "Removes the files", 1, true, () -> answers.add("deleted"));
            confirm(3, "minecraft:book", "Assign", "Adds the group", BULK + 1, false, () -> answers.add("assigned many"));
            confirm(4, "minecraft:book", "Tag", "Adds the tag", 2, false, () -> answers.add("tagged"));
            button(5, "minecraft:clock", title("Speed " + number), any("step"), (Click click) -> number = step(click, number, 0.5, 0, 10));
            button(6, "minecraft:compass", title("Mode " + choice), any("cycle"), (Click click) -> choice = cycle(click, List.of("walk", "fly", "swim"), choice));
            button(7, "minecraft:stone", title("Refuse"), any("refuse"), (Click click) -> refuse("Not today"));
            button(8, "minecraft:stone", title("Change"), any("change"), (Click click) -> changes++);
            draft(changes, 1, true, () -> { answers.add("saved"); changes = 0; }, () -> changes = 0);
        }
    }

    @BeforeEach void setUp() throws IOException {
        start(List.of(file("npcs/guard.json", npc("guard", GUARD, "").replace("\"group\":\"town\"", "\"group\":\"\""))), false);
        active.put(GUARD, 3.0);
        alex.wand = false;
        toolkit.open(alex.id, ProbeScreen::new);
        assertEquals("Probe", title());
    }

    @Test void aPromptHidesTheWindowAndBringsItBackWithTheAnswer() {
        click(0, Click.LEFT);
        assertNull(alex.window, "the chest closes while the player types");
        assertNotNull(alex.prompt);
        assertEquals("New name", alex.prompt.label());
        assertEquals("old", alex.prompt.initial());
        assertEquals("Letters and digits", alex.prompt.hint(), "the hint reaches the platform's input");
        assertTrue(manager.viewer(alex.id).orElseThrow().suspended());
        MockScreenHost.answer(alex, Optional.of("baker"));
        assertNotNull(alex.window, "the screen is back");
        assertEquals("Probe", title());
        assertEquals(List.of("baker"), answers);
        assertEquals(1, alex.closed);
        assertEquals(2, alex.opened);
    }

    @Test void aCancelledPromptBringsTheWindowBackAndChangesNothing() {
        click(0, Click.LEFT);
        MockScreenHost.answer(alex, Optional.empty());
        assertNotNull(alex.window);
        assertTrue(answers.isEmpty());
        assertTrue(alex.messages.stream().anyMatch((String line) -> line.endsWith("Cancelled; nothing changed")), alex.messages.toString());
    }

    @Test void aPlatformWithoutTextInputKeepsTheWindowAndSaysSo() {
        alex.noPrompt = true;
        click(0, Click.LEFT);
        assertNotNull(alex.window);
        assertNull(alex.prompt);
        assertTrue(alex.messages.stream().anyMatch((String line) -> line.contains("no text input for New name")), alex.messages.toString());
    }

    @Test void aPickTakesTheNextWandClickInsteadOfSelecting() {
        click(1, Click.LEFT);
        assertTrue(alex.wand, "the player is handed a wand when they hold none");
        assertNull(alex.window);
        assertEquals(Optional.of("Vehicle"), toolkit.picking(alex.id));
        assertTrue(toolkit.interacted(alex, GUARD, false, false));
        assertEquals(List.of("vehicle guard"), answers);
        assertTrue(toolkit.selected(alex.id).isEmpty(), "a picked NPC is not selected");
        assertNotNull(alex.window, "the screen is back");
        assertEquals(Optional.empty(), toolkit.picking(alex.id));
    }

    @Test void aPickCancelledByATeleportSaysSo() {
        click(1, Click.LEFT);
        now += Toolkit.REPEAT_NANOS;
        manager.teleported(alex);
        assertTrue(toolkit.interacted(alex, GUARD, false, false));
        assertTrue(answers.isEmpty());
        assertTrue(alex.messages.stream().anyMatch((String line) -> line.contains("Vehicle cancelled")), alex.messages.toString());
    }

    @Test void buttonsHideVanillaAttributesAndDiscLinesAndFillerHasNoTooltip() {
        ScreenFrame.Item button = alex.frame.items().get(0);
        assertEquals(Optional.of("[tooltip_display={hidden_components:[\"minecraft:attribute_modifiers\",\"minecraft:bundle_contents\",\"minecraft:jukebox_playable\"]}]"), button.item().components());
        ScreenFrame.Item filler = alex.frame.items().get(9);
        assertEquals(ToolScreen.FILLER, filler.item().id());
        assertEquals(Optional.of("[tooltip_display={hide_tooltip:true}]"), filler.item().components());
        assertEquals("[custom_name=\"x\",tooltip_display={hidden_components:[\"minecraft:attribute_modifiers\",\"minecraft:bundle_contents\",\"minecraft:jukebox_playable\"]}]",
                ToolScreen.shown(new NpcItem("minecraft:leather_boots", 1, Optional.of("[custom_name=\"x\"]"), Optional.empty())).components().orElseThrow());
        assertEquals("[tooltip_display={hide_tooltip:true}]",
                ToolScreen.shown(new NpcItem("minecraft:stone", 1, Optional.of("[tooltip_display={hide_tooltip:true}]"), Optional.empty())).components().orElseThrow());
    }

    @Test void promptTitlesFitTheAnvil() {
        assertEquals("New name", new ScreenHost.Prompt("New name", "", (Optional<String> typed) -> {}).title());
        String title = new ScreenHost.Prompt("minecraft:waypoint_transmit_range", "", (Optional<String> typed) -> {}).title();
        assertTrue(title.length() <= ScreenHost.Prompt.TITLE, title);
    }

    @Test void confirmAsksOnceForDeletesAndBulkChangesOnly() {
        click(4, Click.LEFT);
        assertEquals(List.of("tagged"), answers, "a small change runs at once");
        assertEquals("Adds the tag\nClick: tag", lore(4));
        click(2, Click.LEFT);
        assertEquals(List.of("tagged"), answers, "the first click arms a delete");
        assertEquals("Click again to confirm", name(2));
        assertEquals("Removes the files\nAny other click cancels", lore(2));
        click(3, Click.LEFT);
        assertEquals(List.of("tagged"), answers, "clicking another button disarms the delete and arms the bulk change");
        assertEquals("Delete", name(2));
        assertTrue(lore(3).contains("21 NPCs affected"), lore(3));
        click(3, Click.RIGHT);
        assertEquals(List.of("tagged", "assigned many"), answers, "any click on the armed slot runs it");
        click(2, Click.LEFT);
        click(2, Click.SHIFT_RIGHT);
        assertEquals(List.of("tagged", "assigned many", "deleted"), answers);
    }

    @Test void aRefusalShowsAtTheBottomUntilTheNextClick() {
        click(7, Click.LEFT);
        assertEquals("Not today", name(ToolScreen.PAGE));
        assertTrue(alex.messages.getLast().endsWith("Not today"));
        click(5, Click.LEFT);
        assertFalse(shows("Not today"));
    }

    @Test void aDraftSavesDiscardsAndAsksBeforeLeavingUnsaved() {
        assertEquals("Saved", name(ToolScreen.SAVE));
        assertEquals("", name(ToolScreen.DISCARD).strip(), "Discard shows only with changes");
        click(8, Click.LEFT);
        click(8, Click.LEFT);
        assertEquals("Save", name(ToolScreen.SAVE));
        assertTrue(lore(ToolScreen.SAVE).contains("2 changes"));
        assertEquals("Discard", name(ToolScreen.DISCARD));
        click(ToolScreen.BACK, Click.LEFT);
        assertEquals("Click again to leave unsaved", name(ToolScreen.BACK));
        click(5, Click.LEFT);
        assertEquals("Back", name(ToolScreen.BACK), "another click keeps the screen");
        click(ToolScreen.SAVE, Click.LEFT);
        assertEquals(List.of("saved"), answers);
        assertEquals("Saved", name(ToolScreen.SAVE));
        click(8, Click.LEFT);
        click(ToolScreen.DISCARD, Click.LEFT);
        assertEquals("Saved", name(ToolScreen.SAVE));
        click(8, Click.LEFT);
        click(ToolScreen.BACK, Click.LEFT);
        click(ToolScreen.BACK, Click.LEFT);
        assertNull(alex.window, "the second click leaves, dropping the change");
    }

    @Test void numbersStepAndChoicesCycleWithTheClick() {
        click(5, Click.LEFT);
        click(5, Click.SHIFT_LEFT);
        assertEquals("Speed 6.5", name(5));
        click(5, Click.SHIFT_RIGHT);
        click(5, Click.SHIFT_RIGHT);
        assertEquals("Speed 0.0", name(5), "steps stop at the bounds");
        click(6, Click.LEFT);
        assertEquals("Mode fly", name(6));
        click(6, Click.RIGHT);
        click(6, Click.RIGHT);
        assertEquals("Mode swim", name(6), "a right-click goes backwards and wraps");
    }
}
