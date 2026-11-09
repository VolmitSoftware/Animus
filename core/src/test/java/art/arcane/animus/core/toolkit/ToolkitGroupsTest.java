package art.arcane.animus.core.toolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.command.CommandActor;
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
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Trait group, folder and selection screens: every write is one transaction, bulk changes ask twice. */
class ToolkitGroupsTest {
    static final UUID GUARD = UUID.fromString("12345678-1234-1234-1234-123456789abc"), BAKER = UUID.fromString("22345678-1234-1234-1234-123456789abc"),
            COOK = UUID.fromString("32345678-1234-1234-1234-123456789abc");
    static final int CROWD = ToolScreen.BULK + 1;
    @TempDir Path root;
    DocumentStore store;
    NpcRepository npcs;
    final MockScreenHost host = new MockScreenHost();
    final Player alex = new Player("Alex");
    final ScreenManager<Player, Stack> manager = new ScreenManager<>(host, (UUID npc) -> true, (String warning) -> { throw new AssertionError(warning); });
    final Map<UUID, String> active = new HashMap<>();
    Toolkit<Player, Stack> toolkit;
    final Toolkit.Commands commands = new Toolkit.Commands((CommandActor actor, String id) -> {}, (CommandActor actor) -> {}, (CommandActor actor, String id) -> {});

    static String npc(String id, UUID uuid, String folder, String extra) {
        return ToolkitScreensTest.npc(id, uuid, extra).replace("\"group\":\"town\"", "\"group\":\"" + folder + "\"");
    }

    static UUID crowd(int i) { return UUID.fromString(String.format("42345678-1234-1234-1234-%012d", i)); }

    @BeforeEach void setUp() throws IOException {
        store = new DocumentStore(root);
        List<DocumentStore.Change> files = new ArrayList<>(List.of(
                new DocumentStore.Change("npcs/town/guard.json", "missing", npc("guard", GUARD, "town", ",\"groups\":[\"guards\"]")),
                new DocumentStore.Change("npcs/town/baker.json", "missing", npc("baker", BAKER, "town", "")),
                new DocumentStore.Change("npcs/town/kitchen/cook.json", "missing", npc("cook", COOK, "town/kitchen", "")),
                new DocumentStore.Change("groups/guards.json", "missing", "{\"schemaVersion\":1,\"revision\":1,\"id\":\"guards\",\"description\":\"Gate watch\",\"values\":{\"tags\":[\"armed\"]}}")));
        for (int i = 0; i < CROWD; i++) files.add(new DocumentStore.Change("npcs/crowd/c" + i + ".json", "missing", npc("c" + i, crowd(i), "crowd", "")));
        store.commit(files);
        npcs = new NpcRepository(store);
        assertTrue(npcs.reload().isEmpty());
        active.put(GUARD, "guard");
        active.put(BAKER, "baker");
        for (int i = 0; i < CROWD; i++) active.put(crowd(i), "c" + i);
        alex.permissions.add(Toolkit.ADMIN);
        alex.pose = new ScreenHost.Pose(Set.of("world"), 0, 64, 0, 65.62, 0, 0, false);
        alex.wand = true;
        FakeNpcRuntime<Player> living = new FakeNpcRuntime<>((UUID id) -> id.equals(alex.id) ? Optional.of(alex) : Optional.empty()) {
            @Override public Optional<ActiveNpc> active(UUID npc) {
                String id = active.get(npc);
                return id == null ? Optional.empty() : Optional.of(new ActiveNpc(id, id, ToolkitScreensTest.at(npc, 3)));
            }
        };
        toolkit = Toolkit.install(new Toolkit.Parts<>(() -> npcs, () -> store, manager, living, Runnable::run, (Player player) -> Optional.empty(), Optional.empty(), System::nanoTime));
    }

    @AfterEach void tearDown() throws IOException { toolkit.close(); store.close(); }

    void click(String name, Click click) { manager.click(alex, alex.window, MockScreenHost.slot(alex, name), click); }

    /** The slot of the item named exactly this, for names that are prefixes of others. */
    int exact(String name) {
        for (Map.Entry<Integer, ScreenFrame.Item> item : alex.frame.items().entrySet())
            if (item.getValue().name().map(RichText::plain).orElse("").equals(name)) return item.getKey();
        throw new AssertionError("no item named exactly " + name);
    }

    void folder(String name, Click click) { manager.click(alex, alex.window, exact(name), click); }

    String folderLore(String name) { return String.join("\n", alex.frame.items().get(exact(name)).lore().stream().map(RichText::plain).toList()); }

    void type(String text) { MockScreenHost.answer(alex, Optional.of(text)); }

    String title() { return RichText.plain(alex.frame.title()); }

    String lore(String name) {
        ScreenFrame.Item item = alex.frame.items().get(MockScreenHost.slot(alex, name));
        return String.join("\n", item.lore().stream().map(RichText::plain).toList());
    }

    String last() { return alex.messages.getLast(); }

    String tx(String line) { return line.substring(line.indexOf("tx ") + 3, line.indexOf("tx ") + 39); }

    List<String> groups(String id) { return npcs.stored().stream().filter((art.arcane.animus.core.npc.NpcDefinition npc) -> npc.id().equals(id)).findFirst().orElseThrow().groups(); }

    void select(String... ids) {
        toolkit.selection().set(alex.id, List.of(ids));
        manager.viewer(alex.id).ifPresent((ScreenManager<Player, Stack>.Viewer viewer) -> viewer.refresh());
    }

    void openGroup() {
        toolkit.open(alex.id, commands, GroupScreen::new);
        click("guards", Click.SHIFT_LEFT);
        assertEquals("Group guards", title());
    }

    @Test void renameMovesTheFileAndRepointsEveryMemberInOneTransaction() throws IOException {
        openGroup();
        assertTrue(lore("guards").contains("Gate watch") && lore("guards").contains("Members 1"), lore("guards"));
        click("Rename", Click.LEFT);
        assertEquals("New group id", alex.prompt.label());
        type("watch");
        String line = last();
        assertTrue(line.contains("Renamed group guards to watch, 1 NPC repointed; tx "), line);
        assertNull(store.read("groups/guards.json"));
        assertEquals("watch", JsonDocuments.object(store.read("groups/watch.json")).get("id").getAsString());
        assertEquals(List.of("watch"), groups("guard"));
        assertEquals(List.of("armed"), npcs.find("guard").orElseThrow().tags(), "the values still apply under the new id");
        assertEquals(List.of("groups/guards.json", "groups/watch.json", "npcs/town/guard.json"),
                store.paths(tx(line)).stream().filter((String path) -> !path.startsWith("state/")).sorted().toList());
        assertEquals("Group watch", title(), "the screen follows the rename");
        store.rollback(tx(line));
        npcs.reloadAfterRollback();
        assertEquals(List.of("guards"), groups("guard"), "one undo brings the old id back everywhere");
    }

    @Test void renameRefusesATakenOrInvalidId() throws IOException {
        new GroupFiles(npcs, store).create("other", List.of());
        npcs.reload();
        assertThrows(IOException.class, () -> new GroupFiles(npcs, store).rename("guards", "other"));
        assertThrows(IOException.class, () -> new GroupFiles(npcs, store).rename("guards", "Bad Id"));
        assertEquals(List.of("guards"), groups("guard"));
    }

    @Test void deleteAsksTwiceAndUnassignsMembersInTheSameTransaction() throws IOException {
        openGroup();
        click("Delete", Click.LEFT);
        assertNotNull(store.read("groups/guards.json"), "the first click only arms it");
        click("Click again", Click.LEFT);
        String line = last();
        assertTrue(line.contains("Deleted group guards, unassigned from 1 NPC; tx "), line);
        assertNull(store.read("groups/guards.json"));
        assertTrue(groups("guard").isEmpty());
        assertTrue(store.paths(tx(line)).containsAll(List.of("groups/guards.json", "npcs/town/guard.json")));
        MockScreenHost.slot(alex, "No group guards");
    }

    @Test void newGroupIsCreatedEmptyAndAssignedToTheSelection() throws IOException {
        select("guard", "baker");
        toolkit.open(alex.id, commands, GroupScreen::new);
        click("New group", Click.LEFT);
        type("Bad Id");
        assertTrue(last().contains("is not 1-64 lowercase"), last());
        assertNull(store.read("groups/bad id.json"));
        click("New group", Click.LEFT);
        type("cooks");
        String line = last();
        assertTrue(line.contains("Created group cooks, assigned to 2 NPCs; tx "), line);
        assertEquals(List.of("guards", "cooks"), groups("guard"));
        assertEquals(List.of("cooks"), groups("baker"));
        assertTrue(store.paths(tx(line)).containsAll(List.of("groups/cooks.json", "npcs/town/guard.json", "npcs/town/baker.json")));
        assertTrue(JsonDocuments.object(store.read("groups/cooks.json")).getAsJsonObject("values").isEmpty());
        assertTrue(lore("cooks").contains("In the selection 2 of 2"), lore("cooks"));
    }

    @Test void valuesAreSavedAndRefusedWhenAMemberWouldBreak() throws IOException {
        JsonObject values = new JsonObject();
        values.addProperty("glowing", true);
        String line = new GroupFiles(npcs, store).values("guards", values);
        assertTrue(line.contains("Saved the values of guards for 1 NPC; tx "), line);
        assertTrue(npcs.find("guard").orElseThrow().json().get("glowing").getAsBoolean());
        assertTrue(npcs.find("guard").orElseThrow().tags().isEmpty(), "the values are replaced, not merged");
        JsonObject refused = new JsonObject();
        refused.addProperty("name", "Bob");
        IOException failure = assertThrows(IOException.class, () -> new GroupFiles(npcs, store).values("guards", refused));
        assertTrue(failure.getMessage().contains("not allowed in a group"), failure.getMessage());
        assertEquals("Values of guards unchanged", new GroupFiles(npcs, store).values("guards", values));
    }

    @Test void descriptionAndValues() throws IOException {
        openGroup();
        click("Description", Click.LEFT);
        assertEquals("Gate watch", alex.prompt.initial());
        type("Night shift");
        assertTrue(last().contains("Described guards; tx "), last());
        assertEquals("Night shift", npcs.groups().find("guards").orElseThrow().description());
        click("Description", Click.LEFT);
        type("");
        assertTrue(last().contains("Removed the description of guards; tx "), last());
        assertFalse(JsonDocuments.object(store.read("groups/guards.json")).has("description"));
        click("Values", Click.LEFT);
        assertEquals("Values of guards", title());
        click("glowing", Click.LEFT);
        assertTrue(last().contains("; tx "), last());
        assertTrue(JsonDocuments.object(store.read("groups/guards.json")).getAsJsonObject("values").get("glowing").getAsBoolean());
        click("Back", Click.LEFT);
        assertEquals("Group guards", title());
    }

    @Test void membersSelectToggleAndLeaveTheGroup() {
        openGroup();
        click("Members", Click.LEFT);
        assertEquals("Members of guards", title());
        assertTrue(lore("guard").contains("Folder npcs/town/"), "the folder reads as in Find and Selection: " + lore("guard"));
        click("guard", Click.LEFT);
        assertEquals(List.of("guard"), toolkit.selected(alex.id));
        click("guard", Click.SHIFT_LEFT);
        assertTrue(toolkit.selected(alex.id).isEmpty());
        click("Select all members", Click.LEFT);
        assertEquals(List.of("guard"), toolkit.selected(alex.id));
        click("guard", Click.RIGHT);
        assertTrue(last().contains("Unassigned guards from guard"), last());
        assertTrue(groups("guard").isEmpty());
        MockScreenHost.slot(alex, "No NPCs list guards");
    }

    @Test void membersWearTheirOwnSignedSkinOnTheirHeads() throws IOException {
        String skin = "{\"textureRaw\":\"e30=\",\"signature\":\"c2ln\"}\n", hash = JsonDocuments.hash(skin), guard = store.read("npcs/town/guard.json");
        store.commit(List.of(new DocumentStore.Change("skins/" + hash + ".json", "missing", skin),
                new DocumentStore.Change("npcs/town/guard.json", JsonDocuments.hash(guard), guard.replace(",\"groups\":", ",\"skin\":\"" + hash + "\",\"groups\":"))));
        assertTrue(npcs.reload().isEmpty());
        openGroup();
        click("Members", Click.LEFT);
        String head = alex.frame.items().get(MockScreenHost.slot(alex, "guard")).item().components().orElse("");
        assertTrue(head.contains("profile={properties:[{name:\"textures\",value:\"e30=\",signature:\"c2ln\"}]}"), "signed, so the client draws it: " + head);
    }

    @Test void bulkAssignAsksTwice() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < CROWD; i++) many.add("c" + i);
        toolkit.selection().set(alex.id, many);
        toolkit.open(alex.id, commands, GroupScreen::new);
        click("guards", Click.LEFT);
        assertTrue(lore("guards").contains(CROWD + " NPCs affected"), lore("guards"));
        assertTrue(groups("c0").isEmpty(), "the first click only arms it");
        click("guards", Click.LEFT);
        assertTrue(last().contains("Assigned guards to " + CROWD + " NPCs"), last());
        assertEquals(List.of("guards"), groups("c0"));
    }

    @Test void theSelectionScreenListsActsOnAndRebuildsTheSelection() {
        select("guard", "baker");
        toolkit.open(alex.id, commands, SelectionScreen::new);
        assertEquals("Selection", title());
        String guard = lore("guard");
        assertTrue(guard.contains("Folder npcs/town/") && guard.contains("Type minecraft:player") && guard.contains("Groups guards") && guard.contains("Active"), guard);
        click("baker", Click.RIGHT);
        assertEquals(List.of("guard"), toolkit.selected(alex.id));
        click("guard", Click.LEFT);
        assertTrue(alex.effects.contains("teleport:world:1,64,0"), "beside the guard at 3 64 0, on the player's side: " + alex.effects);
        select("guard", "baker");
        click("baker", Click.SHIFT_LEFT);
        assertEquals(List.of("baker"), toolkit.selected(alex.id));

        click("Select by tag", Click.LEFT);
        type("armed");
        assertEquals(List.of("guard"), toolkit.selected(alex.id), "tags from groups count");
        assertTrue(last().contains("Selected 1 NPC tagged armed"), last());

        click("Select by folder", Click.LEFT);
        folder("npcs/town/kitchen/", Click.LEFT);
        assertEquals("Selection", title());
        assertEquals(List.of("cook"), toolkit.selected(alex.id));

        select("guard", "cook");
        click("Invert in folder", Click.LEFT);
        folder("npcs/town/", Click.LEFT);
        assertEquals(List.of("cook", "baker"), toolkit.selected(alex.id), "guard leaves, baker joins, cook outside the folder stays");

        click("Select all in my world", Click.LEFT);
        assertEquals(List.of("cook", "baker"), toolkit.selected(alex.id), CROWD + 2 + " NPCs asks first");
        click("Click again", Click.LEFT);
        assertEquals(CROWD + 2, toolkit.selected(alex.id).size());
        assertFalse(toolkit.selected(alex.id).contains("cook"), "cook is not active");

        click("Clear", Click.LEFT);
        assertTrue(toolkit.selected(alex.id).isEmpty());
        MockScreenHost.slot(alex, "Nothing selected");
    }

    @Test void foldersRenameWithSubfoldersAndNewFolderTakesTheSelection() throws IOException {
        toolkit.open(alex.id, commands, FolderScreen::new);
        assertTrue(folderLore("npcs/town/").contains("2 NPCs, 3 with subfolders"), folderLore("npcs/town/"));
        folder("npcs/town/", Click.SHIFT_LEFT);
        type("village");
        String line = last();
        assertTrue(line.contains("Renamed npcs/town/ to npcs/village/, moved 3 NPCs; tx "), line);
        assertNotNull(store.read("npcs/village/guard.json"));
        assertNotNull(store.read("npcs/village/kitchen/cook.json"));
        assertNull(store.read("npcs/town/baker.json"));
        assertEquals("village/kitchen", npcs.find("cook").orElseThrow().group());

        folder("npcs/crowd/", Click.SHIFT_LEFT);
        assertNull(alex.prompt, CROWD + " NPCs asks first");
        assertTrue(folderLore("npcs/crowd/").contains("Sneak again to rename"), folderLore("npcs/crowd/"));
        folder("npcs/crowd/", Click.SHIFT_LEFT);
        assertNotNull(alex.prompt);
        MockScreenHost.answer(alex, Optional.empty());

        folder("npcs/", Click.SHIFT_LEFT);
        assertTrue(last().contains("npcs/ itself cannot be renamed"), last());

        select("baker");
        click("New folder", Click.LEFT);
        type("market");
        assertTrue(last().contains("Moved 1 NPC to npcs/market/; tx "), last());
        assertNotNull(store.read("npcs/market/baker.json"));
    }
}
