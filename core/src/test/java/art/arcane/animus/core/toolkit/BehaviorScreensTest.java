package art.arcane.animus.core.toolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.behavior.conversation.ConversationDocument;
import art.arcane.animus.core.command.CommandActor;
import art.arcane.animus.core.editor.DocumentKind;
import art.arcane.animus.core.editor.EditorDocuments;
import art.arcane.animus.core.gui.FakeNpcRuntime;
import art.arcane.animus.core.gui.GuiModel.Click;
import art.arcane.animus.core.gui.MockScreenHost;
import art.arcane.animus.core.gui.MockScreenHost.Player;
import art.arcane.animus.core.gui.ScreenFrame;
import art.arcane.animus.core.gui.ScreenHost;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.inventory.MockInventory.Stack;
import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocuments;
import art.arcane.animus.core.text.RichText;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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

/** The behavior list, its run controls and the conversation editor, through the mock screen host. */
class BehaviorScreensTest {
    static final String PATROL = "{\"schemaVersion\":1,\"revision\":1,\"id\":\"town:patrol\",\"entry\":\"main\",\"routines\":{\"main\":{\"type\":\"wait\",\"ticks\":20}},"
            + "\"triggers\":[{\"id\":\"tick\",\"event\":\"timer\",\"intervalTicks\":200}]}";
    static final String RUN = "00000000-0000-0000-0000-00000000000a";
    @TempDir Path root;
    DocumentStore store;
    NpcRepository npcs;
    final MockScreenHost host = new MockScreenHost();
    final Player alex = new Player("Alex");
    final ScreenManager<Player, Stack> manager = new ScreenManager<>(host, (UUID npc) -> true, (String warning) -> { throw new AssertionError(warning); });
    final List<Runnable> queue = new ArrayList<>();
    Toolkit<Player, Stack> toolkit;
    final Toolkit.Commands commands = new Toolkit.Commands((CommandActor actor, String id) -> {}, (CommandActor actor) -> {}, (CommandActor actor, String id) -> {});

    @BeforeEach void setUp() throws IOException {
        store = new DocumentStore(root);
        List<DocumentStore.Change> files = new ArrayList<>(List.of(
                new DocumentStore.Change("npcs/town/guard.json", "missing", ToolkitScreensTest.npc("guard", ToolkitScreensTest.GUARD, ",\"groups\":[\"talkers\"]")),
                new DocumentStore.Change("npcs/town/baker.json", "missing", ToolkitScreensTest.npc("baker", ToolkitScreensTest.BAKER, ",\"behaviors\":[\"town:patrol\"]")),
                new DocumentStore.Change("groups/talkers.json", "missing", "{\"schemaVersion\":1,\"revision\":1,\"id\":\"talkers\",\"values\":{\"behaviors\":[\"harbor:ferryman\"]}}"),
                new DocumentStore.Change("behaviors/town/talk.json", "missing", fixture("talk")),
                new DocumentStore.Change("behaviors/harbor/ferryman.json", "missing", fixture("ferryman")),
                new DocumentStore.Change("behaviors/town/patrol.json", "missing", PATROL)));
        for (int i = 0; i < ToolScreen.BULK; i++)
            files.add(new DocumentStore.Change("npcs/crowd/c" + i + ".json", "missing", ToolkitScreensTest.npc("c" + i, UUID.nameUUIDFromBytes(("c" + i).getBytes()), "").replace("\"town\"", "\"crowd\"")));
        store.commit(files);
        npcs = new NpcRepository(store);
        assertTrue(npcs.reload().isEmpty(), npcs.diagnostics().toString());
        alex.permissions.add(Toolkit.ADMIN);
        alex.pose = new ScreenHost.Pose(Set.of("world"), 0, 64, 0, 65.62, 0, 0, false);
        alex.wand = true;
        FakeNpcRuntime<Player> living = new FakeNpcRuntime<>((UUID id) -> id.equals(alex.id) ? Optional.of(alex) : Optional.empty());
        toolkit = Toolkit.install(new Toolkit.Parts<>(() -> npcs, () -> store, manager, living, queue::add, (Player player) -> Optional.empty(), Optional.empty(), System::nanoTime));
    }

    @AfterEach void tearDown() throws IOException { toolkit.close(); store.close(); }

    static String fixture(String name) throws IOException {
        try (java.io.InputStream in = java.util.Objects.requireNonNull(BehaviorScreensTest.class.getResourceAsStream("/conversation/" + name + ".json"), name)) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    void drain() { while (!queue.isEmpty()) queue.removeFirst().run(); }

    void select(String... ids) { for (String id : ids) toolkit.selection().toggle(alex.id, id); }

    void click(String name, Click click) { manager.click(alex, alex.window, MockScreenHost.slot(alex, name), click); drain(); }

    String name(String name) { return RichText.plain(alex.frame.items().get(MockScreenHost.slot(alex, name)).name().orElseThrow()); }

    String lore(String name) {
        ScreenFrame.Item item = alex.frame.items().get(MockScreenHost.slot(alex, name));
        return String.join("\n", item.lore().stream().map(RichText::plain).toList());
    }

    void type(String text) { MockScreenHost.answer(alex, Optional.of(text)); drain(); }

    List<String> behaviorsOf(String path) throws IOException {
        List<String> listed = new ArrayList<>();
        JsonElement behaviors = JsonDocuments.object(store.read(path)).get("behaviors");
        if (behaviors != null) for (JsonElement behavior : behaviors.getAsJsonArray()) listed.add(behavior.getAsString());
        return listed;
    }

    String last() { return alex.messages.getLast(); }

    void openList() {
        toolkit.open(alex.id, commands, BehaviorScreen::new);
        drain();
        assertEquals("Behaviors", RichText.plain(alex.frame.title()));
    }

    @Test void theListShowsEachBehaviorWithItsTriggersAndWhoHasIt() {
        select("guard", "baker");
        openList();
        assertTrue(lore("town:talk").contains("on interaction, dialogue_choice x2"), lore("town:talk"));
        assertTrue(lore("town:talk").contains("Conversation"));
        assertTrue(lore("town:patrol").contains("1 of 2 selected list it"), lore("town:patrol"));
        assertTrue(lore("town:patrol").contains("Node graph"), "a timer behavior is no conversation");
        assertTrue(lore("harbor:ferryman").replace("\n", " ").contains("0 of 2 selected list it, 1 more through a trait group"), lore("harbor:ferryman"));
        assertTrue(lore("town:talk").contains("Left attach to the selection"));
    }

    @Test void leftAttachesAndRightDetachesInOneTransaction() throws IOException {
        select("guard", "baker");
        openList();
        click("town:talk", Click.LEFT);
        assertTrue(last().contains("Attached town:talk to 2 NPCs; tx "), last());
        assertEquals(List.of("town:talk"), behaviorsOf("npcs/town/guard.json"));
        assertEquals(List.of("town:patrol", "town:talk"), behaviorsOf("npcs/town/baker.json"));
        assertTrue(lore("town:talk").contains("2 of 2 selected list it"), "the list reads the files again after a write");
        click("town:patrol", Click.RIGHT);
        assertTrue(last().contains("Detached town:patrol from baker, 1 unchanged; tx "), last());
        assertEquals(List.of("town:talk"), behaviorsOf("npcs/town/baker.json"));
    }

    @Test void attachingToMoreThanTwentyNpcsAsksTwice() throws IOException {
        select("guard");
        for (int i = 0; i < ToolScreen.BULK; i++) select("c" + i);
        openList();
        click("town:talk", Click.LEFT);
        assertTrue(name("town:talk").startsWith("Click again"), "the first click arms it");
        assertTrue(behaviorsOf("npcs/town/guard.json").isEmpty());
        click("town:talk", Click.LEFT);
        assertTrue(last().contains("Attached town:talk to 21 NPCs"), last());
        assertEquals(List.of("town:talk"), behaviorsOf("npcs/crowd/c0.json"));
    }

    @Test void theDetailScreenRunsTracesValidatesAndCancelsThroughTheCommands() {
        select("guard", "baker");
        openList();
        click("town:talk", Click.SHIFT_LEFT);
        assertEquals("town:talk", RichText.plain(alex.frame.title()));
        click("Run", Click.LEFT);
        assertEquals(List.of("animus behaviors run npc=guard behavior=town:talk", "animus behaviors run npc=baker behavior=town:talk"), alex.commands);
        click("Validate", Click.LEFT);
        assertEquals("animus behaviors validate behavior=town:talk", alex.commands.getLast());
        click("Cancel a run", Click.LEFT);
        assertEquals(List.of("animus behaviors trace npc=guard", "animus behaviors trace npc=baker"), alex.commands.subList(3, 5), "the run ids reach chat first");
        assertEquals("Run id to cancel", alex.prompt.label());
        type("not-a-run");
        assertTrue(last().contains("not-a-run is not a run id"), last());
        click("Cancel a run", Click.LEFT);
        type(RUN);
        assertEquals("animus behaviors cancel execution=" + RUN, alex.commands.getLast());
        click("Trace", Click.LEFT);
        assertNull(alex.window, "trace closes the window so the chat can be read and clicked");
        assertEquals("animus behaviors trace npc=baker", alex.commands.getLast());
    }

    @Test void aBehaviorThatIsNoConversationSaysSo() {
        openList();
        click("town:patrol", Click.SHIFT_LEFT);
        assertTrue(lore("Not a conversation").contains("Starts on timer."), lore("Not a conversation"));
    }

    @Test void theBottomRowListsTriggersAndReloads() {
        openList();
        click("Reload behaviors", Click.LEFT);
        assertEquals("animus behaviors reload", alex.commands.getLast());
        click("Trigger bindings", Click.LEFT);
        assertEquals("animus behaviors triggers", alex.commands.getLast());
        assertNull(alex.window);
    }

    void openTalk() {
        openList();
        click("town:talk", Click.SHIFT_LEFT);
        click("Open as conversation", Click.LEFT);
        assertEquals("town:talk", RichText.plain(alex.frame.title()));
        assertEquals("Block main", name("Block main"));
    }

    @Test void linesEditInsertMoveAndDeleteInOrder() {
        openTalk();
        click("Line 1", Click.LEFT);
        assertEquals("Hello, {player_name}.", alex.prompt.initial());
        type("Welcome to {npc.name}'s shop.");
        assertTrue(lore("Line 1").contains("Welcome to {npc.name}'s shop."), lore("Line 1"));
        click("Line 1", Click.RIGHT);
        type("Look around.");
        assertTrue(lore("Line 2").contains("Look around."), "right inserts after");
        click("Line 2", Click.SHIFT_LEFT);
        assertTrue(lore("Line 1").contains("Look around."), "sneak-left moves it up");
        click("Line 1", Click.SHIFT_RIGHT);
        assertTrue(name("Delete Line 1").startsWith("Delete"), "the first sneak-right arms the delete");
        click("Delete Line 1", Click.SHIFT_RIGHT);
        assertTrue(lore("Line 1").contains("Welcome"), "the second deletes it");
        assertTrue(lore("Save").contains("Unsaved changes"));
    }

    @Test void choicesEditOpenTheirBlockAndGoBack() {
        openTalk();
        click("Choice: Just looking", Click.LEFT);
        type("Only browsing");
        assertTrue(name("Choice: Only browsing").contains("Only browsing"));
        click("Choice: Only browsing", Click.RIGHT);
        assertEquals("Block just-looking", name("Block just-looking"), "right opens the block the choice leads to");
        assertEquals("town:talk > just-looking", RichText.plain(alex.frame.title()), "the window names the block it shows");
        assertTrue(lore("Line 1").contains("Take your time."));
        click("Add choice", Click.LEFT);
        type("Show me");
        assertTrue(lore("Question").contains("Choose one."), "a block without a question gets one");
        click("Choice: Show me", Click.RIGHT);
        assertEquals("Block show-me", name("Block show-me"));
        click("Add line", Click.LEFT);
        type("Here you go.");
        click("Back to just-looking", Click.LEFT);
        click("Back to main", Click.LEFT);
        assertEquals("Block main", name("Block main"));
        assertEquals("town:talk", RichText.plain(alex.frame.title()));
    }

    @Test void deletingAChoiceRemovesTheBlocksOnlyItReachedAfterAsking() {
        openTalk();
        click("Choice: Goodbye", Click.SHIFT_LEFT);
        assertTrue(name("Delete goodbye").contains("Delete"));
        click("Delete goodbye", Click.SHIFT_LEFT);
        assertTrue(last().contains("Removed blocks goodbye"), last());
        assertFalse(alex.frame.items().values().stream().anyMatch((ScreenFrame.Item item) -> item.name().map(RichText::plain).orElse("").contains("Goodbye")));
    }

    @Test void saveWritesOneValidRevisionAndReloadsBehaviors() throws IOException {
        openTalk();
        click("Starts on Right-click", Click.LEFT);
        assertEquals("Starts on Sneak + right-click", name("Starts on"));
        for (int i = 0; i < 7; i++) click("Starts on", Click.LEFT);
        assertEquals("Starts on Walk near", name("Starts on"));
        click("Within 6 blocks", Click.LEFT);
        assertEquals("Within 7 blocks", name("Within"));
        click("Add choice", Click.LEFT);
        type("Tell me more");
        click("Save", Click.LEFT);
        assertTrue(last().contains("Saved town:talk; tx "), last());
        assertEquals("animus behaviors reload", alex.commands.getLast());
        JsonObject saved = JsonDocuments.object(store.read("behaviors/town/talk.json"));
        assertEquals(2, saved.get("revision").getAsInt());
        assertEquals(List.of(), new EditorDocuments(store, () -> 0).validate(DocumentKind.BEHAVIOR, "town:talk", saved).asList().stream()
                .filter((JsonElement problem) -> "error".equals(problem.getAsJsonObject().get("severity").getAsString())).toList());
        ConversationDocument back = ((ConversationDocument.Fits) ConversationDocument.read(saved)).conversation();
        assertEquals(ConversationDocument.StartKind.PROXIMITY, back.start.kind);
        assertEquals(7, back.start.radius);
        assertTrue(back.blocks.containsKey("tell-me-more"));
        assertEquals("Saved", name("Saved"), "the screen now edits what is on disk");
        click("Saved", Click.LEFT);
        assertTrue(last().endsWith("No changes to town:talk"), last());
    }

    @Test void leavingWithUnsavedEditsAsksFirst() {
        openTalk();
        click("Add line", Click.LEFT);
        type("One more thing.");
        click("Back", Click.LEFT);
        assertTrue(name("Click again").contains("leave unsaved"));
        click("Click again", Click.LEFT);
        assertEquals("Run", name("Run"), "back on the detail screen");
    }

    @Test void attachFromTheConversationAddsItToTheSelection() throws IOException {
        select("guard");
        openTalk();
        click("Attach to selection", Click.LEFT);
        assertEquals(List.of("town:talk"), behaviorsOf("npcs/town/guard.json"));
    }

    @Test void newConversationAsksForAnIdAndOpensTheEditor() throws IOException {
        openList();
        click("New conversation", Click.LEFT);
        assertEquals("Behavior id", alex.prompt.label());
        type("Town Greeter");
        assertTrue(last().contains("namespace:path"), last());
        click("New conversation", Click.LEFT);
        type("town:greeter");
        assertTrue(alex.messages.stream().anyMatch((String line) -> line.contains("Created town:greeter; tx ")), alex.messages.toString());
        assertEquals("animus behaviors reload", alex.commands.getLast());
        assertNotNull(alex.window);
        assertEquals("town:greeter", RichText.plain(alex.frame.title()));
        assertTrue(lore("Line 1").contains("Hello"));
        JsonObject made = JsonDocuments.object(store.read("behaviors/town/greeter.json"));
        assertEquals("interaction", made.getAsJsonArray("triggers").get(0).getAsJsonObject().get("event").getAsString());
    }
}
