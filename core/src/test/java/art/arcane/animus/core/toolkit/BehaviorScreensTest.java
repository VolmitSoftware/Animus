package art.arcane.animus.core.toolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The Advanced behavior list, its details and the running runs Cancel a run lists. */
class BehaviorScreensTest extends ToolkitHarness {
    static final String PATROL = "{\"schemaVersion\":1,\"revision\":1,\"id\":\"town:patrol\",\"entry\":\"main\",\"routines\":{\"main\":{\"type\":\"wait\",\"ticks\":20}},"
            + "\"triggers\":[{\"id\":\"tick\",\"event\":\"timer\",\"intervalTicks\":200}]}";
    static final UUID RUN = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    @BeforeEach void setUp() throws IOException {
        List<DocumentStore.Change> files = new ArrayList<>(List.of(
                npcFile("guard", GUARD, ",\"groups\":[\"talkers\"]"),
                npcFile("baker", BAKER, ",\"behaviors\":[\"town:patrol\"]"),
                new DocumentStore.Change("groups/talkers.json", "missing", "{\"schemaVersion\":2,\"revision\":1,\"id\":\"talkers\",\"values\":{\"behaviors\":[\"harbor:ferryman\"]}}"),
                new DocumentStore.Change("behaviors/town/talk.json", "missing", fixture("talk")),
                new DocumentStore.Change("behaviors/harbor/ferryman.json", "missing", fixture("ferryman")),
                new DocumentStore.Change("behaviors/town/patrol.json", "missing", PATROL)));
        for (int i = 0; i < ToolScreen.BULK; i++)
            files.add(new DocumentStore.Change("npcs/crowd/c" + i + ".json", "missing", npc("c" + i, UUID.nameUUIDFromBytes(("c" + i).getBytes()), "").replace("\"town\"", "\"crowd\"")));
        start(files, true);
        toolkit.mode(alex.id, ToolkitMode.ADVANCED);
    }

    static String fixture(String name) throws IOException {
        try (java.io.InputStream in = java.util.Objects.requireNonNull(BehaviorScreensTest.class.getResourceAsStream("/conversation/" + name + ".json"), name)) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    void select(String... ids) { for (String id : ids) toolkit.selection().toggle(alex.id, id); }

    @Override void click(String name, Click click) { super.click(name, click); drain(); }

    @Override void click(int slot, Click click) { super.click(slot, click); drain(); }

    void type(String text) { MockScreenHost.answer(alex, Optional.of(text)); drain(); }

    List<String> behaviorsOf(String path) throws IOException {
        List<String> listed = new ArrayList<>();
        JsonElement behaviors = JsonDocuments.object(store.read(path)).get("behaviors");
        if (behaviors != null) for (JsonElement behavior : behaviors.getAsJsonArray()) listed.add(behavior.getAsString());
        return listed;
    }

    String last() { return alex.messages.getLast(); }

    void openList() {
        toolkit.open(alex.id, BehaviorScreen::new);
        drain();
        assertEquals("Behaviors", title());
    }

    @Test void theListNamesEachBehaviorWithItsKindAndWhoHasItInFourLines() {
        select("guard", "baker");
        openList();
        assertEquals(Map.of(0, "harbor:ferryman", 1, "town:patrol", 2, "town:talk", BehaviorScreen.CREATE, "New conversation", BehaviorScreen.RELOAD, "Reload",
                BehaviorScreen.TRIGGERS, "Trigger bindings", ToolScreen.BACK, "Back", ToolScreen.SEARCH, "Search", ToolScreen.HELP, "Help"), buttons());
        assertEquals("Conversation\nNow: on 0 of 2\nClick: use on 2 NPCs\nRight-click: details", lore("town:talk"));
        assertEquals("Node graph\nNow: on 1 of 2\nClick: use on 2 NPCs\nRight-click: details", lore("town:patrol"));
        assertTrue(lore("harbor:ferryman").contains("Now: on 0 of 2, 1 by trait group"), lore("harbor:ferryman"));
        assertFalse(lore("town:talk").contains("Loose file"), "the file's place shows only in details");
        assertTrue(lore("Trigger bindings").contains("Shows in chat"), lore("Trigger bindings"));
    }

    @Test void aClickUsesItOnTheSelectionAndDetachIsInTheDetails() throws IOException {
        select("guard", "baker");
        openList();
        click("town:talk", Click.LEFT);
        assertTrue(last().contains("Attached town:talk to 2 NPCs"), last());
        assertEquals(List.of("town:talk"), behaviorsOf("npcs/town/guard.json"));
        assertEquals(List.of("town:patrol", "town:talk"), behaviorsOf("npcs/town/baker.json"));
        assertTrue(lore("town:talk").contains("Now: on 2 of 2"), "the list reads the files again after a write");
        click("town:patrol", Click.RIGHT);
        assertEquals("town:patrol", title());
        click("Remove from NPC", Click.LEFT);
        assertTrue(last().contains("Detached town:patrol from baker, 1 unchanged"), last());
        assertEquals(List.of("town:talk"), behaviorsOf("npcs/town/baker.json"));
    }

    @Test void removeFromNpcShowsOnTheListAtOnce() throws IOException {
        select("baker");
        openList();
        assertTrue(lore("town:patrol").contains("Now: on 1 of 1"), lore("town:patrol"));
        click("town:patrol", Click.RIGHT);
        click("Remove from NPC", Click.LEFT);
        assertEquals(List.of(), behaviorsOf("npcs/town/baker.json"));
        click(ToolScreen.BACK, Click.LEFT);
        assertEquals("Behaviors", title());
        assertTrue(lore("town:patrol").contains("Now: on 0 of 1"), lore("town:patrol"));
    }

    @Test void moreThanTwentyNpcsAskOnceWithTheSharedConfirm() throws IOException {
        select("guard");
        for (int i = 0; i < ToolScreen.BULK; i++) select("c" + i);
        openList();
        click("town:talk", Click.LEFT);
        assertEquals(ToolScreen.CONFIRM, name(2));
        assertTrue(lore(2).contains("21 NPCs affected"), lore(2));
        assertTrue(behaviorsOf("npcs/town/guard.json").isEmpty());
        click(2, Click.LEFT);
        assertTrue(last().contains("Attached town:talk to 21 NPCs"), last());
        assertEquals(List.of("town:talk"), behaviorsOf("npcs/crowd/c0.json"));
    }

    @Test void withNothingSelectedAClickOpensTheDetails() {
        openList();
        assertTrue(lore("town:talk").endsWith("Click: details"), lore("town:talk"));
        click("town:talk", Click.LEFT);
        assertEquals("town:talk", title());
    }

    @Test void theDetailsRunTraceValidateAndOpenTheConversation() {
        select("guard", "baker");
        openList();
        click("town:talk", Click.RIGHT);
        assertEquals(Map.of(BehaviorDetailScreen.HEADER, "town:talk", BehaviorDetailScreen.RUN, "Run", BehaviorDetailScreen.TRACE, "Trace", BehaviorDetailScreen.CANCEL, "Cancel a run",
                BehaviorDetailScreen.VALIDATE, "Validate", BehaviorDetailScreen.CONVERSATION, "Open as conversation", BehaviorDetailScreen.REMOVE, "Remove from NPC",
                ToolScreen.BACK, "Back", ToolScreen.HELP, "Help"), buttons());
        assertTrue(lore(BehaviorDetailScreen.HEADER).contains("Loose file"), lore(BehaviorDetailScreen.HEADER));
        click("Run", Click.LEFT);
        assertEquals(List.of("animus behaviors run npc=guard behavior=town:talk", "animus behaviors run npc=baker behavior=town:talk"), alex.commands);
        click("Validate", Click.LEFT);
        assertEquals("animus behaviors validate behavior=town:talk", alex.commands.getLast());
        assertTrue(lore("Validate").contains("Shows in chat"));
        click("Open as conversation", Click.LEFT);
        assertEquals("Talk: town", title());
        assertEquals("Start", name(ConversationScreen.BLOCK));
        click(ToolScreen.BACK, Click.LEFT);
        click("Trace", Click.LEFT);
        assertNull(alex.window, "trace closes the window so the runs in chat can be clicked");
        assertEquals("animus behaviors trace npc=baker", alex.commands.getLast());
    }

    @Test void aBehaviorThatIsNoConversationSaysWhy() {
        openList();
        click("town:patrol", Click.RIGHT);
        assertTrue(lore("Not a conversation").contains("Starts on timer."), lore("Not a conversation"));
    }

    @Test void cancelARunListsTheRunningRunsToClick() {
        runs = (String npc) -> CompletableFuture.completedFuture(npc.equals("baker")
                ? List.of(new Toolkit.Run(RUN, "baker", "town:talk"), new Toolkit.Run(UUID.randomUUID(), "baker", "town:patrol")) : List.of());
        select("guard", "baker");
        openList();
        click("town:talk", Click.RIGHT);
        click("Cancel a run", Click.LEFT);
        assertEquals("Runs of town:talk", title());
        assertEquals(Map.of(0, "On baker", BehaviorRunsScreen.RELOAD, "Reload", ToolScreen.BACK, "Back", ToolScreen.HELP, "Help"), buttons());
        assertFalse(alex.prompt != null && alex.prompt.label().contains("id"), "no run id is typed");
        click("On baker", Click.LEFT);
        assertEquals(ToolScreen.CONFIRM, name(0));
        click(0, Click.LEFT);
        assertEquals("animus behaviors cancel execution=" + RUN, alex.commands.getLast());
    }

    @Test void cancelARunSaysWhenNothingRuns() {
        runs = (String npc) -> CompletableFuture.completedFuture(List.of());
        select("guard");
        openList();
        click("town:talk", Click.RIGHT);
        click("Cancel a run", Click.LEFT);
        assertTrue(shows("Nothing running"), buttons().toString());
    }

    @Test void theActionRowListsTriggersAndReloads() {
        openList();
        click("Reload", Click.LEFT);
        assertEquals(BehaviorScreen.RELOAD_COMMAND, alex.commands.getLast());
        click("Trigger bindings", Click.LEFT);
        assertEquals("animus behaviors triggers", alex.commands.getLast());
        assertNull(alex.window);
    }

    @Test void newConversationAsksForAnIdAndOpensTheEditor() throws IOException {
        openList();
        click("New conversation", Click.LEFT);
        assertEquals("Behavior id", alex.prompt.label());
        type("Town Greeter");
        assertTrue(last().contains("namespace:path"), last());
        click("New conversation", Click.LEFT);
        type("town:greeter");
        assertTrue(alex.messages.stream().anyMatch((String line) -> line.contains("Created town:greeter")), alex.messages.toString());
        assertEquals(BehaviorScreen.RELOAD_COMMAND, host.console.getLast());
        assertNotNull(alex.window);
        assertEquals("Talk: greeter", title());
        assertTrue(lore("Line 1").contains("Hello"));
        JsonObject made = JsonDocuments.object(store.read("behaviors/town/greeter.json"));
        assertEquals("interaction", made.getAsJsonArray("triggers").get(0).getAsJsonObject().get("event").getAsString());
    }
}
