package art.arcane.animus.core.toolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.command.CommandActor;
import art.arcane.animus.core.command.PermissionNodes;
import art.arcane.animus.core.gui.FakeNpcRuntime;
import art.arcane.animus.core.gui.GuiModel.Click;
import art.arcane.animus.core.gui.MockScreenHost;
import art.arcane.animus.core.gui.MockScreenHost.Player;
import art.arcane.animus.core.gui.ScreenHost;
import art.arcane.animus.core.gui.ScreenFrame;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.inventory.MockInventory.Stack;
import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.text.RichText;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The pieces every tool screen shares: text prompts, wand picks, guarded buttons and value stepping. */
class ToolkitFoundationTest {
    static final UUID GUARD = UUID.fromString("12345678-1234-1234-1234-123456789abc");
    @TempDir Path root;
    DocumentStore store;
    NpcRepository npcs;
    final MockScreenHost host = new MockScreenHost();
    final Player alex = new Player("Alex");
    final ScreenManager<Player, Stack> manager = new ScreenManager<>(host, (UUID npc) -> true, (String warning) -> { throw new AssertionError(warning); });
    final List<String> answers = new ArrayList<>();
    Toolkit<Player, Stack> toolkit;
    final Toolkit.Commands commands = new Toolkit.Commands((CommandActor actor, String id) -> {}, (CommandActor actor) -> {}, (CommandActor actor, String id) -> {});

    /** A screen with one of each shared button. */
    final class ProbeScreen extends ToolScreen<Player, Stack> {
        double number = 1;
        String choice = "walk";

        ProbeScreen(Toolkit<Player, Stack> toolkit, ScreenManager<Player, Stack>.Viewer viewer, Toolkit.Commands commands) { super(toolkit, viewer, commands); }
        @Override String title() { return "Probe"; }
        @Override int rows() { return 1; }
        @Override void draw() {
            button(0, "minecraft:paper", title("Rename"), any("type a name"), (Click click) -> prompt("New name", "old", answers::add));
            button(1, "minecraft:saddle", title("Vehicle"), any("click an NPC"), (Click click) -> pick("Vehicle", (String npc) -> answers.add("vehicle " + npc)));
            guarded(2, "minecraft:red_concrete", "Delete", "Removes the files", 1, true, () -> answers.add("deleted"));
            guarded(3, "minecraft:book", "Assign", "Adds the group", BULK + 1, false, () -> answers.add("assigned many"));
            guarded(4, "minecraft:book", "Tag", "Adds the tag", 2, false, () -> answers.add("tagged"));
            button(5, "minecraft:clock", title("Speed " + number), any("step"), (Click click) -> number = step(click, number, 0.5, 0, 10));
            button(6, "minecraft:compass", title("Mode " + choice), any("cycle"), (Click click) -> choice = cycle(click, List.of("walk", "fly", "swim"), choice));
        }
    }

    @BeforeEach void setUp() throws IOException {
        store = new DocumentStore(root);
        store.commit(List.of(new DocumentStore.Change("npcs/guard.json", "missing", ToolkitScreensTest.npc("guard", GUARD, "").replace("\"group\":\"town\"", "\"group\":\"\""))));
        npcs = new NpcRepository(store);
        assertTrue(npcs.reload().isEmpty());
        alex.permissions.add(PermissionNodes.ADMIN);
        alex.pose = new ScreenHost.Pose(Set.of("world"), 0, 64, 0, 65.62, 0, 0, false);
        FakeNpcRuntime<Player> living = new FakeNpcRuntime<>((UUID id) -> id.equals(alex.id) ? Optional.of(alex) : Optional.empty()) {
            @Override public Optional<ActiveNpc> active(UUID npc) {
                return npc.equals(GUARD) ? Optional.of(new ActiveNpc("guard", "guard", ToolkitScreensTest.at(GUARD, 3))) : Optional.empty();
            }
        };
        toolkit = Toolkit.install(new Toolkit.Parts<>(() -> npcs, () -> store, manager, living, Runnable::run, (Player player) -> Optional.empty(), Optional.empty(), System::nanoTime));
        toolkit.open(alex.id, commands, ProbeScreen::new);
        assertEquals("Probe", RichText.plain(alex.frame.title()));
    }

    @AfterEach void tearDown() throws IOException { toolkit.close(); store.close(); }

    void click(int slot, Click click) { manager.click(alex, alex.window, slot, click); }

    @Test void aPromptHidesTheWindowAndBringsItBackWithTheAnswer() {
        click(0, Click.LEFT);
        assertNull(alex.window, "the chest closes while the player types");
        assertNotNull(alex.prompt);
        assertEquals("New name", alex.prompt.label());
        assertEquals("old", alex.prompt.initial());
        assertTrue(manager.viewer(alex.id).orElseThrow().suspended());
        MockScreenHost.answer(alex, Optional.of("baker"));
        assertNotNull(alex.window, "the screen is back");
        assertEquals("Probe", RichText.plain(alex.frame.title()));
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
        alex.wand = false;
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
        manager.teleported(alex);
        assertTrue(toolkit.interacted(alex, GUARD, false, false));
        assertTrue(answers.isEmpty());
        assertTrue(alex.messages.stream().anyMatch((String line) -> line.contains("Vehicle cancelled")), alex.messages.toString());
    }

    @Test void clickHintsSayEachActionOnce() {
        assertEquals(List.of("&fClick: &7open it"), ToolScreen.hints("open it", "open it", "open it"));
        assertEquals(List.of("&fLeft or sneak &7next", "&fRight &7previous"), ToolScreen.hints("next", "previous", "next"));
        assertEquals(List.of("&fLeft or right &7page 2", "&fSneak &7last page"), ToolScreen.hints("page 2", "page 2", "last page"));
        assertEquals(List.of("&fLeft &7save", "&fRight or sneak &7clear"), ToolScreen.hints("save", "clear", "clear"));
        assertEquals(3, ToolScreen.hints("a", "b", "c").size());
    }

    @Test void buttonsHideVanillaAttributesAndDiscLinesAndFillerHasNoTooltip() {
        ScreenFrame.Item button = alex.frame.items().get(0);
        assertEquals(Optional.of("[tooltip_display={hidden_components:[\"minecraft:attribute_modifiers\",\"minecraft:bundle_contents\",\"minecraft:jukebox_playable\"]}]"), button.item().components());
        ScreenFrame.Item filler = alex.frame.items().get(8);
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

    @Test void guardedButtonsAskTwiceForDeletesAndBulkChangesOnly() {
        click(4, Click.LEFT);
        assertEquals(List.of("tagged"), answers, "a small change runs at once");
        click(2, Click.LEFT);
        assertEquals(List.of("tagged"), answers, "the first click arms a delete");
        assertTrue(RichText.plain(alex.frame.items().get(2).name().orElseThrow()).contains("Click again"));
        click(3, Click.LEFT);
        assertEquals(List.of("tagged"), answers, "clicking another button disarms the delete and arms the bulk change");
        assertFalse(RichText.plain(alex.frame.items().get(2).name().orElseThrow()).contains("Click again"));
        click(3, Click.LEFT);
        assertEquals(List.of("tagged", "assigned many"), answers);
        click(2, Click.LEFT);
        click(2, Click.RIGHT);
        assertEquals(List.of("tagged", "assigned many", "deleted"), answers);
    }

    @Test void numbersStepAndChoicesCycleWithTheClick() {
        click(5, Click.LEFT);
        click(5, Click.SHIFT_LEFT);
        assertEquals("Speed 6.5", RichText.plain(alex.frame.items().get(5).name().orElseThrow()));
        click(5, Click.SHIFT_RIGHT);
        click(5, Click.SHIFT_RIGHT);
        assertEquals("Speed 0.0", RichText.plain(alex.frame.items().get(5).name().orElseThrow()), "steps stop at the bounds");
        click(6, Click.LEFT);
        assertEquals("Mode fly", RichText.plain(alex.frame.items().get(6).name().orElseThrow()));
        click(6, Click.RIGHT);
        click(6, Click.RIGHT);
        assertEquals("Mode swim", RichText.plain(alex.frame.items().get(6).name().orElseThrow()), "a right click goes backwards and wraps");
    }
}
