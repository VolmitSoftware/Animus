package art.arcane.animus.core.toolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.behavior.conversation.ConversationDocument;
import art.arcane.animus.core.editor.DocumentKind;
import art.arcane.animus.core.editor.EditorDocuments;
import art.arcane.animus.core.gui.GuiModel.Click;
import art.arcane.animus.core.gui.MockScreenHost;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** NPC > Talk and the conversation editor with its step menu, in Simple and Advanced. */
class TalkScreensTest extends ToolkitHarness {
    static final UUID SMITH = UUID.fromString("32345678-1234-1234-1234-123456789abc"), MAYOR = UUID.fromString("42345678-1234-1234-1234-123456789abc");

    @BeforeEach void setUp() throws IOException {
        start(List.of(
                npcFile("guard", GUARD, ",\"groups\":[\"talkers\"]"),
                npcFile("smith", SMITH, ""),
                npcFile("mayor", MAYOR, ",\"behaviors\":[\"town:talk\"],\"groups\":[\"talkers\"]"),
                new DocumentStore.Change("groups/talkers.json", "missing", "{\"schemaVersion\":2,\"revision\":1,\"id\":\"talkers\",\"values\":{\"behaviors\":[\"harbor:ferryman\"]}}"),
                new DocumentStore.Change("behaviors/town/talk.json", "missing", BehaviorScreensTest.fixture("talk")),
                new DocumentStore.Change("behaviors/harbor/ferryman.json", "missing", BehaviorScreensTest.fixture("ferryman"))), true);
    }

    @Override void click(String name, Click click) { super.click(name, click); drain(); }

    @Override void click(int slot, Click click) { super.click(slot, click); drain(); }

    void type(String text) { MockScreenHost.answer(alex, Optional.of(text)); drain(); }

    List<String> behaviorsOf(String path) throws IOException {
        List<String> listed = new ArrayList<>();
        JsonElement behaviors = JsonDocuments.object(store.read(path)).get("behaviors");
        if (behaviors != null) for (JsonElement behavior : behaviors.getAsJsonArray()) listed.add(behavior.getAsString());
        return listed;
    }

    /** NPC > Talk with only {@code id} selected. */
    void talk(String id) {
        toolkit.selectOnly(alex.id, id);
        toolkit.open(alex.id);
        drain();
        click(NpcScreen.TALK, Click.LEFT);
    }

    @Test void talkMakesTheFirstConversationAttachesItInOneChangeAndOpensIt() throws IOException {
        talk("smith");
        assertEquals("Talk: smith", title());
        assertTrue(lore("Line 1").contains("Hello"), lore("Line 1"));
        assertEquals(List.of("town:smith-talk"), behaviorsOf("npcs/town/smith.json"));
        JsonObject made = JsonDocuments.object(store.read("behaviors/town/smith-talk.json"));
        assertEquals("interaction", made.getAsJsonArray("triggers").get(0).getAsJsonObject().get("event").getAsString());
        assertEquals(1, changes().size(), alex.messages.toString());
        assertTrue(alex.lines.stream().filter((art.arcane.animus.core.text.ChatLine line) -> !line.transactions().isEmpty()).findFirst().orElseThrow().toString()
                .contains("Made a conversation for smith"), alex.messages.toString());
        assertEquals(List.of(BehaviorScreen.RELOAD_COMMAND), host.console);
        click("Back", Click.LEFT);
        assertEquals("smith", title(), "the editor took Talk's place, so Back is the NPC screen");
    }

    @Test void talkMakesNothingWhenThePlayerWentBackBeforeItWasReady() throws IOException {
        toolkit.selectOnly(alex.id, "smith");
        toolkit.open(alex.id);
        drain();
        super.click(NpcScreen.TALK, Click.LEFT);
        assertEquals("Talk", title());
        super.click(ToolScreen.BACK, Click.LEFT);
        drain();
        assertEquals("smith", title(), "the screen the player went back to stays");
        assertEquals(null, store.read("behaviors/town/smith-talk.json"), "nothing is made for a Talk the player left");
        assertTrue(behaviorsOf("npcs/town/smith.json").isEmpty());
        assertTrue(host.console.isEmpty(), host.console.toString());
    }

    @Test void aConversationThatCannotBeReadStopsTheWaitAndSaysSo() throws IOException {
        store.commit(List.of(new DocumentStore.Change("behaviors/town/smith-talk.json", "missing", "{\"schemaVersion\":1,")));
        talk("smith");
        assertEquals("Talk", title());
        assertFalse(shows("Getting the conversation ready"), buttons().toString());
        assertTrue(shows("Conversation not ready"), buttons().toString());
        assertEquals("Back", name(ToolScreen.BACK));
    }

    @Test void anNpcInNoFolderNamesItsConversationAfterItself() throws IOException {
        UUID hermit = UUID.fromString("52345678-1234-1234-1234-123456789abc");
        store.commit(List.of(new DocumentStore.Change("npcs/hermit.json", "missing", npc("hermit", hermit, "").replace("\"group\":\"town\"", "\"group\":\"\""))));
        npcs.reload();
        talk("hermit");
        assertEquals("Talk: hermit", title(), "not npcs:hermit-talk");
        assertEquals(List.of("hermit:talk"), behaviorsOf("npcs/hermit.json"));
        assertTrue(store.read("behaviors/hermit/talk.json") != null);
    }

    @Test void talkOpensTheOneConversationAnNpcHas() {
        talk("guard");
        assertEquals("Talk: ferryman", title(), "through its trait group");
        assertTrue(host.console.isEmpty(), "nothing was made");
    }

    @Test void talkListsSeveralToPick() {
        talk("mayor");
        assertEquals("Talk", title());
        assertEquals(Map.of(0, "harbor:ferryman", 1, "town:talk", 45, "Back", 53, "Help"), buttons());
        assertTrue(lore("town:talk").contains("Click: edit it"), lore("town:talk"));
        click("town:talk", Click.LEFT);
        assertEquals("Talk: town", title());
        click("Back", Click.LEFT);
        assertEquals("Talk", title());
    }

    void editTalk() {
        talk("mayor");
        click("town:talk", Click.LEFT);
    }

    @Test void theEditorShowsTheSameItemsInSimpleWithSaveAndHelpBelow() {
        editTalk();
        assertEquals(Map.ofEntries(Map.entry(ConversationScreen.START, "Starts on"), Map.entry(ConversationScreen.BLOCK, "Start"), Map.entry(ConversationScreen.ADD_LINE, "Add line"),
                Map.entry(9, "Line 1"), Map.entry(10, "Question"), Map.entry(36, "Choice: Just looking"), Map.entry(37, "Choice: Goodbye"),
                Map.entry(ConversationScreen.ADD_CHOICE, "Add choice"), Map.entry(ToolScreen.BACK, "Back"), Map.entry(ToolScreen.SAVE, "Saved"), Map.entry(ToolScreen.HELP, "Help")), buttons());
        assertTrue(lore("Starts on").contains("Now: Right-click"), lore("Starts on"));
        assertTrue(lore("Line 1").contains("Click: edit the text") && lore("Line 1").contains("Right-click: details"), lore("Line 1"));
        assertFalse(lore("Line 1").contains("Sneak"), "nothing hides on sneak");
        assertFalse(shows("Attach"), "Talk attaches; the editor no longer does");
    }

    @Test void stepsEditOnAClickAndOpenTheirMenuOnARightClick() {
        editTalk();
        click("Line 1", Click.LEFT);
        assertEquals("Line", alex.prompt.label());
        assertEquals("Hello, {player_name}.", alex.prompt.initial());
        type("Welcome to {npc.name}'s shop.");
        assertTrue(lore("Line 1").contains("Welcome to {npc.name}'s shop."), lore("Line 1"));
        assertEquals("Save", name(ToolScreen.SAVE));
        assertEquals("Discard", name(ToolScreen.DISCARD));
        click("Line 1", Click.SHIFT_RIGHT);
        assertEquals("Line", alex.prompt.label(), "a sneaking click edits, as a click does");
        MockScreenHost.answer(alex, Optional.empty());
        drain();

        click("Line 1", Click.RIGHT);
        assertEquals("Line 1", title());
        assertEquals(Map.of(ConversationStepScreen.HEADER, "Line 1", ConversationStepScreen.EDIT, "Edit text", ConversationStepScreen.INSERT, "Insert after",
                ConversationStepScreen.UP, "Move up", ConversationStepScreen.DOWN, "Move down", ConversationStepScreen.DELETE, "Delete", 18, "Back", 26, "Help"), buttons());
        assertTrue(lore(ConversationStepScreen.UP).contains("Already first"), lore(ConversationStepScreen.UP));
        click("Insert after", Click.LEFT);
        type("Look around.");
        assertEquals("Talk: town", title(), "inserting goes back to the editor");
        assertTrue(lore("Line 2").contains("Look around."), lore("Line 2"));
        click("Line 2", Click.RIGHT);
        click("Move up", Click.LEFT);
        assertTrue(lore(ConversationStepScreen.HEADER).contains("Now: step 1 of 3"), lore(ConversationStepScreen.HEADER));
        click("Back", Click.LEFT);
        assertTrue(lore("Line 1").contains("Look around."), lore("Line 1"));
        click("Line 1", Click.RIGHT);
        click(ConversationStepScreen.DELETE, Click.LEFT);
        assertEquals(ToolScreen.CONFIRM, name(ConversationStepScreen.DELETE));
        assertTrue(lore(ConversationStepScreen.DELETE).contains("Any other click cancels"));
        click(ConversationStepScreen.DELETE, Click.LEFT);
        assertEquals("Talk: town", title());
        assertTrue(lore("Line 1").contains("Welcome"), lore("Line 1"));
    }

    @Test void choicesOpenTheirBlockAndKeepLabelAndDeleteInTheirMenu() {
        editTalk();
        assertTrue(lore("Choice: Just looking").contains("Click: follow it"), "Simple says no block: " + lore("Choice: Just looking"));
        click("Choice: Just looking", Click.LEFT);
        assertEquals("Block just-looking", name(ConversationScreen.BLOCK));
        assertEquals("town > just-looking", title());
        assertTrue(lore(ToolScreen.BACK).contains("To block main"), lore(ToolScreen.BACK));
        click("Add choice", Click.LEFT);
        type("Show me");
        type("Here you go.");
        assertTrue(lore("Question").contains("Choose one."), "a block without a question gets one");
        click(ToolScreen.BACK, Click.LEFT);
        assertEquals("Start", name(ConversationScreen.BLOCK));

        click("Choice: Just looking", Click.RIGHT);
        assertEquals(Map.of(ConversationStepScreen.HEADER, "Choice: Just looking", ConversationStepScreen.EDIT, "Edit label", ConversationStepScreen.DELETE, "Delete", 18, "Back", 26, "Help"), buttons());
        click("Edit label", Click.LEFT);
        type("Only browsing");
        assertTrue(shows("Choice: Only browsing"));
        click("Choice: Goodbye", Click.RIGHT);
        click(ConversationStepScreen.DELETE, Click.LEFT);
        click(ConversationStepScreen.DELETE, Click.LEFT);
        assertTrue(alex.messages.getLast().contains("Removed blocks goodbye"), alex.messages.getLast());
        assertFalse(shows("Goodbye"));
    }

    @Test void aNewChoiceAsksWhatTheNpcAnswersAndSaysItInSimple() throws IOException {
        editTalk();
        click("Add choice", Click.LEFT);
        type("Where is the bakery?");
        assertEquals("NPC's answer", alex.prompt.label(), "the answer is asked right after the choice");
        type("Down by the well.");
        String choice = lore("Choice: Where is the bakery?");
        assertTrue(choice.contains("Then: Down by the well."), choice);
        assertFalse(choice.contains("Leads to") || choice.contains("where-is-the-bakery"), "Simple shows no block id: " + choice);
        assertTrue(choice.lines().count() <= 3, "title plus at most three lines: " + choice);
        click("Choice: Where is the bakery?", Click.LEFT);
        assertEquals("Block where-is-the-bakery", name(ConversationScreen.BLOCK));
        assertTrue(lore("Line 1").contains("Down by the well."), "the answer is its first line: " + lore("Line 1"));
        click(ToolScreen.BACK, Click.LEFT);
        toolkit.mode(alex.id, ToolkitMode.ADVANCED);
        manager.viewer(alex.id).orElseThrow().refresh();
        assertTrue(lore("Choice: Where is the bakery?").contains("Leads to: where-is-the-bakery"), "Advanced names the block");
        click(ToolScreen.BACK, Click.LEFT);
        click(ToolScreen.SAVE, Click.LEFT);
        ConversationDocument saved = ((ConversationDocument.Fits) ConversationDocument.read(JsonDocuments.object(store.read("behaviors/town/talk.json")))).conversation();
        assertEquals(1, saved.blocks.get("where-is-the-bakery").steps.size());
    }

    @Test void linesGoBeforeTheQuestionSoTheConversationWaitsForAnAnswer() throws IOException {
        editTalk();
        assertEquals("Start", name(ConversationScreen.BLOCK));
        assertFalse(lore("Add line").contains("{npc.name}"), "Simple shows no placeholders: " + lore("Add line"));
        click("Add line", Click.LEFT);
        type("Nice to meet you!");
        assertEquals("Line 2", name(10));
        assertEquals("Question", name(11), "the question stays last");
        click("Line 2", Click.RIGHT);
        assertTrue(lore(ConversationStepScreen.DOWN).contains("The question stays last"), lore(ConversationStepScreen.DOWN));
        click(ConversationStepScreen.DOWN, Click.LEFT);
        assertTrue(lore(ConversationStepScreen.HEADER).contains("Now: step 2 of 3"), lore(ConversationStepScreen.HEADER));
        click("Back", Click.LEFT);
        click("Question", Click.RIGHT);
        assertTrue(lore(ConversationStepScreen.UP).contains("The question stays last"), lore(ConversationStepScreen.UP));
        click("Back", Click.LEFT);
        click(ToolScreen.SAVE, Click.LEFT);
        ConversationDocument saved = ((ConversationDocument.Fits) ConversationDocument.read(JsonDocuments.object(store.read("behaviors/town/talk.json")))).conversation();
        assertTrue(saved.blocks.get("main").steps.getLast() instanceof ConversationDocument.Choices, "nothing plays after the question");
    }

    @Test void aShopOnTheSameNpcIsNamedOnTheStartItem() throws IOException {
        store.commit(List.of(file("shops/town/bakery.json", ToolkitShopScreensTest.BAKERY)));
        new ShopFiles(npcs, store).bindShop("town:bakery", List.of("mayor"));
        editTalk();
        assertTrue(lore(ConversationScreen.BLOCK).contains("Also has a shop: players get both"), lore(ConversationScreen.BLOCK));
    }

    @Test void discardDropsTheChangesAndBackAsksOnceWhenUnsaved() {
        editTalk();
        click("Add line", Click.LEFT);
        type("One more thing.");
        assertTrue(lore("Line 2").contains("One more thing."), lore("Line 2"));
        click(ToolScreen.DISCARD, Click.LEFT);
        assertEquals("Saved", name(ToolScreen.SAVE));
        assertFalse(shows("Line 2"));
        click("Add line", Click.LEFT);
        type("One more thing.");
        click(ToolScreen.BACK, Click.LEFT);
        assertEquals("Click again to leave unsaved", name(ToolScreen.BACK));
        click(ToolScreen.BACK, Click.LEFT);
        assertEquals("Talk", title());
    }

    @Test void saveWritesOneValidRevision() throws IOException {
        editTalk();
        click("Starts on", Click.LEFT);
        assertTrue(lore("Starts on").contains("Now: Sneak + right-click"), lore("Starts on"));
        for (int i = 0; i < 7; i++) click("Starts on", Click.LEFT);
        assertTrue(lore("Starts on").contains("Now: Walk near"), lore("Starts on"));
        click("Distance", Click.LEFT);
        assertTrue(lore("Distance").contains("Now: 7 blocks"), lore("Distance"));
        click("Add choice", Click.LEFT);
        type("Tell me more");
        type("Ask the baker.");
        click(ToolScreen.SAVE, Click.LEFT);
        assertTrue(alex.messages.stream().anyMatch((String line) -> line.contains("Saved town:talk")), alex.messages.toString());
        assertEquals(BehaviorScreen.RELOAD_COMMAND, host.console.getLast());
        JsonObject saved = JsonDocuments.object(store.read("behaviors/town/talk.json"));
        assertEquals(2, saved.get("revision").getAsInt());
        assertEquals(List.of(), new EditorDocuments(store, () -> 0).validate(DocumentKind.BEHAVIOR, "town:talk", saved).asList().stream()
                .filter((JsonElement problem) -> "error".equals(problem.getAsJsonObject().get("severity").getAsString())).toList());
        ConversationDocument back = ((ConversationDocument.Fits) ConversationDocument.read(saved)).conversation();
        assertEquals(ConversationDocument.StartKind.PROXIMITY, back.start.kind);
        assertEquals(7, back.start.radius);
        assertTrue(back.blocks.containsKey("tell-me-more"));
        assertEquals("Saved", name(ToolScreen.SAVE), "the screen now edits what is on disk");
    }

    @Test void aChangeMadeWhileTheSaveIsOnItsWayIsKeptAndStillUnsaved() throws IOException {
        editTalk();
        click("Starts on", Click.LEFT);
        super.click(ToolScreen.SAVE, Click.LEFT);
        super.click(ConversationScreen.START, Click.LEFT);
        assertTrue(lore("Starts on").contains("Now: Left-click"), lore("Starts on"));
        drain();
        assertTrue(alex.messages.stream().anyMatch((String line) -> line.contains("Saved town:talk")), alex.messages.toString());
        assertTrue(lore("Starts on").contains("Now: Left-click"), "the later change is not thrown away: " + lore("Starts on"));
        assertEquals("Save", name(ToolScreen.SAVE), "and it still waits to be saved");
        ConversationDocument saved = ((ConversationDocument.Fits) ConversationDocument.read(JsonDocuments.object(store.read("behaviors/town/talk.json")))).conversation();
        assertEquals(ConversationDocument.StartKind.SNEAK_CLICK, saved.start.kind, "the file has what was there at the click");
        click(ToolScreen.SAVE, Click.LEFT);
        assertEquals("Saved", name(ToolScreen.SAVE));
        ConversationDocument again = ((ConversationDocument.Fits) ConversationDocument.read(JsonDocuments.object(store.read("behaviors/town/talk.json")))).conversation();
        assertEquals(ConversationDocument.StartKind.LEFT_CLICK, again.start.kind);
    }

    @Test void aLongBlockPagesInsteadOfSendingYouToTheWebEditor() {
        editTalk();
        for (int i = 0; i < 28; i++) { click("Add line", Click.LEFT); type("Line number " + i); }
        assertEquals("Page 2 of 2", name(ToolScreen.PAGE), "the added line shows its page");
        assertEquals("Previous page", name(ToolScreen.PREVIOUS));
        assertEquals("Line 28", name(ConversationScreen.STEPS));
        click(ToolScreen.PREVIOUS, Click.LEFT);
        assertEquals("Page 1 of 2", name(ToolScreen.PAGE));
        assertEquals("Line 1", name(ConversationScreen.STEPS));
        assertEquals("Next page", name(ToolScreen.NEXT));
        assertFalse(alex.frame.items().values().stream().anyMatch((art.arcane.animus.core.gui.ScreenFrame.Item item) -> item.lore().stream().anyMatch((String line) -> line.contains("web editor"))));
    }

    @Test void advancedKeepsEveryEditorItemWhereSimpleHasIt() {
        toolkit.mode(alex.id, ToolkitMode.ADVANCED);
        editTalk();
        assertEquals(Map.ofEntries(Map.entry(ConversationScreen.START, "Starts on"), Map.entry(ConversationScreen.BLOCK, "Start"), Map.entry(ConversationScreen.ADD_LINE, "Add line"),
                Map.entry(9, "Line 1"), Map.entry(10, "Question"), Map.entry(36, "Choice: Just looking"), Map.entry(37, "Choice: Goodbye"),
                Map.entry(ConversationScreen.ADD_CHOICE, "Add choice"), Map.entry(ToolScreen.BACK, "Back"), Map.entry(ToolScreen.SAVE, "Saved"), Map.entry(ToolScreen.HELP, "Help")), buttons());
    }

    @Test void helpPrintsOneLineWithTheWikiAndKeepsTheWindow() {
        editTalk();
        click(ToolScreen.HELP, Click.LEFT);
        assertTrue(alex.messages.getLast().contains("Changes stay here until you click Save"), alex.messages.getLast());
        assertEquals("Talk: town", title());
    }
}
