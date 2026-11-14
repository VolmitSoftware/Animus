package art.arcane.animus.core.toolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.gui.GuiModel.Click;
import art.arcane.animus.core.gui.MockScreenHost;
import art.arcane.animus.core.gui.MockScreenHost.Player;
import art.arcane.animus.core.gui.ScreenFrame;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.inventory.MockInventory.Stack;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocuments;
import art.arcane.animus.core.text.RichText;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Trait group, folder and selection screens: every write is one transaction, bulk and destructive changes ask once more. */
class ToolkitGroupsTest extends ToolkitHarness {
    static final UUID COOK = UUID.fromString("32345678-1234-1234-1234-123456789abc");
    static final int CROWD = ToolScreen.BULK + 1;

    static String npc(String id, UUID uuid, String folder, String extra) {
        return npc(id, uuid, extra).replace("\"group\":\"town\"", "\"group\":\"" + folder + "\"");
    }

    static UUID crowd(int i) { return UUID.fromString(String.format("42345678-1234-1234-1234-%012d", i)); }

    @BeforeEach void setUp() throws IOException {
        List<DocumentStore.Change> files = new ArrayList<>(List.of(
                new DocumentStore.Change("npcs/town/guard.json", "missing", npc("guard", GUARD, "town", ",\"groups\":[\"guards\"]")),
                new DocumentStore.Change("npcs/town/baker.json", "missing", npc("baker", BAKER, "town", "")),
                new DocumentStore.Change("npcs/town/kitchen/cook.json", "missing", npc("cook", COOK, "town/kitchen", "")),
                new DocumentStore.Change("groups/guards.json", "missing", "{\"schemaVersion\":2,\"revision\":1,\"id\":\"guards\",\"description\":\"Gate watch\",\"values\":{\"tags\":[\"armed\"]}}")));
        for (int i = 0; i < CROWD; i++) files.add(new DocumentStore.Change("npcs/crowd/c" + i + ".json", "missing", npc("c" + i, crowd(i), "crowd", "")));
        start(files, false);
        active.put(GUARD, 3.0);
        active.put(BAKER, 3.0);
        for (int i = 0; i < CROWD; i++) active.put(crowd(i), 3.0);
    }

    /** The slot of the item named exactly this, for names that are prefixes of others. */
    int exact(String name) {
        for (Map.Entry<Integer, ScreenFrame.Item> item : alex.frame.items().entrySet())
            if (item.getValue().name().map(RichText::plain).orElse("").equals(name)) return item.getKey();
        throw new AssertionError("no item named exactly " + name);
    }

    void folder(String name, Click click) { manager.click(alex, alex.window, exact(name), click); }

    String folderLore(String name) { return String.join("\n", alex.frame.items().get(exact(name)).lore().stream().map(RichText::plain).toList()); }

    void type(String text) { MockScreenHost.answer(alex, Optional.of(text)); }


    String last() { return alex.messages.getLast(); }

    List<String> groups(String id) { return npcs.stored().stream().filter((art.arcane.animus.core.npc.NpcDefinition npc) -> npc.id().equals(id)).findFirst().orElseThrow().groups(); }

    void select(String... ids) {
        toolkit.selection().set(alex.id, List.of(ids));
        manager.viewer(alex.id).ifPresent((ScreenManager<Player, Stack>.Viewer viewer) -> viewer.refresh());
    }

    void openGroup() {
        toolkit.open(alex.id, GroupScreen::new);
        click("guards", Click.RIGHT);
        assertEquals("Trait group guards", title());
    }

    void centreIsSafe() {
        for (int slot : List.of(13, 22, 31)) assertFalse(List.of("Delete", "Remove from NPC", "Take off", "Close").contains(name(slot)), title() + " has " + name(slot) + " on " + slot);
        assertEquals("Help", name(alex.frame.size() - 1), title() + " has no Help");
    }

    @Test void traitGroupsUseOnTheSelectionWithAClickAndOpenDetailsWithARightClick() {
        toolkit.open(alex.id, GroupScreen::new);
        assertEquals(Map.of(0, "guards", 36, "New trait group", 44, "0 NPCs selected", 45, "Back", 46, "Search", 53, "Help"), buttons());
        assertTrue(lore("guards").contains("Gate watch") && lore("guards").contains("Click: details"), "nothing selected: a click opens it: " + lore("guards"));
        centreIsSafe();
        click("guards", Click.LEFT);
        assertEquals("Trait group guards", title());
        click("Back", Click.LEFT);
        select("guard", "baker");
        assertTrue(lore("guards").contains("Now: on 1 of 2 NPCs") && lore("guards").contains("Click: use on 2 NPCs, right-click: details"), lore("guards"));
        click("guards", Click.LEFT);
        assertTrue(last().endsWith("Assigned guards to baker, 1 unchanged"), last());
        assertEquals(List.of("guards"), groups("baker"));
        click("guards", Click.RIGHT);
        assertEquals("Trait group guards", title(), "a right-click never takes it off");
        assertEquals(List.of("guards"), groups("baker"));
        click("Help", Click.LEFT);
        assertTrue(alex.messages.getLast().contains("21-in-game-toolkit#one-trait-group"), alex.messages.getLast());
    }

    @Test void detailsHoldMembersValuesRenameDescriptionRemoveAndDelete() {
        select("guard");
        openGroup();
        assertEquals(Map.of(4, "guards", 19, "Members", 20, "Values", 21, "Rename", 23, "Description", 33, "Remove from NPC", 35, "Delete", 45, "Back", 53, "Help"), buttons());
        assertTrue(lore(GroupDetailScreen.SUMMARY).contains("Gate watch") && lore(GroupDetailScreen.SUMMARY).contains("Now: 1 member"), lore(GroupDetailScreen.SUMMARY));
        assertTrue(lore("Values").contains("Now: Tags"), lore("Values"));
        centreIsSafe();
        click("Remove from NPC", Click.LEFT);
        assertEquals("Click again to confirm", name(GroupDetailScreen.UNASSIGN));
        assertEquals(List.of("guards"), groups("guard"), "the first click only asks");
        click(GroupDetailScreen.UNASSIGN, Click.LEFT);
        assertTrue(last().endsWith("Unassigned guards from guard"), last());
        assertTrue(groups("guard").isEmpty());
        assertTrue(lore(GroupDetailScreen.UNASSIGN).contains("No selected NPC has it"), lore(GroupDetailScreen.UNASSIGN));
    }

    @Test void renameMovesTheFileAndRepointsEveryMemberInOneTransaction() throws IOException {
        openGroup();
        click("Rename", Click.LEFT);
        assertEquals("New id", alex.prompt.label());
        type("watch");
        String line = last();
        assertTrue(line.endsWith("Renamed trait group guards to watch, 1 NPC repointed"), line);
        assertNull(store.read("groups/guards.json"));
        assertEquals("watch", JsonDocuments.object(store.read("groups/watch.json")).get("id").getAsString());
        assertEquals(List.of("watch"), groups("guard"));
        assertEquals(List.of("armed"), npcs.find("guard").orElseThrow().tags(), "the values still apply under the new id");
        assertEquals(List.of("groups/guards.json", "groups/watch.json", "npcs/town/guard.json"),
                store.paths(changes().getLast()).stream().filter((String path) -> !path.startsWith("state/")).sorted().toList());
        assertEquals("Trait group watch", title(), "the screen follows the rename");
        store.rollback(changes().getLast());
        npcs.reloadAfterRollback();
        assertEquals(List.of("guards"), groups("guard"), "one undo brings the old id back everywhere");
    }

    @Test void renameRefusesATakenOrInvalidId() throws IOException {
        new GroupFiles(npcs, store).create("other", List.of());
        npcs.reload();
        IOException taken = assertThrows(IOException.class, () -> new GroupFiles(npcs, store).rename("guards", "other"));
        assertEquals("A trait group named other exists", taken.getMessage());
        assertThrows(IOException.class, () -> new GroupFiles(npcs, store).rename("guards", "Bad Id"));
        assertEquals(List.of("guards"), groups("guard"));
    }

    @Test void deleteAsksOnceMoreAndTakesItOffMembersInTheSameTransaction() throws IOException {
        openGroup();
        click("Delete", Click.LEFT);
        assertNotNull(store.read("groups/guards.json"), "the first click only asks");
        assertEquals("Click again to confirm", name(GroupDetailScreen.DELETE));
        click(GroupDetailScreen.DELETE, Click.LEFT);
        String line = last();
        assertTrue(line.endsWith("Deleted trait group guards, taken off 1 NPC"), line);
        assertNull(store.read("groups/guards.json"));
        assertTrue(groups("guard").isEmpty());
        assertTrue(store.paths(changes().getLast()).containsAll(List.of("groups/guards.json", "npcs/town/guard.json")));
        MockScreenHost.slot(alex, "No trait group guards");
    }

    @Test void newTraitGroupIsCreatedEmptyAndUsedOnTheSelection() throws IOException {
        select("guard", "baker");
        toolkit.open(alex.id, GroupScreen::new);
        assertFalse(shows("Folders"), "trait groups have no Folders button");
        click("New trait group", Click.LEFT);
        assertEquals("Trait group id", alex.prompt.label());
        type("Bad Id");
        assertTrue(last().contains("Trait group id Bad Id is not 1-64 lowercase"), last());
        assertNull(store.read("groups/bad id.json"));
        click("New trait group", Click.LEFT);
        type("cooks");
        String line = last();
        assertTrue(line.endsWith("Created trait group cooks, assigned to 2 NPCs"), line);
        assertEquals(List.of("guards", "cooks"), groups("guard"));
        assertEquals(List.of("cooks"), groups("baker"));
        assertTrue(store.paths(changes().getLast()).containsAll(List.of("groups/cooks.json", "npcs/town/guard.json", "npcs/town/baker.json")));
        assertTrue(JsonDocuments.object(store.read("groups/cooks.json")).getAsJsonObject("values").isEmpty());
        assertTrue(lore("cooks").contains("Now: on 2 NPCs"), lore("cooks"));
    }

    @Test void valuesAreSavedAndRefusedWhenAMemberWouldBreak() throws IOException {
        JsonObject values = new JsonObject();
        values.addProperty("glowing", true);
        String line = new GroupFiles(npcs, store).values("guards", values);
        assertTrue(line.startsWith("Saved the values of guards for 1 NPC; tx "), line);
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
        assertEquals("Description", alex.prompt.label());
        assertEquals("Gate watch", alex.prompt.initial());
        type("Night shift");
        assertTrue(last().endsWith("Described guards"), last());
        assertEquals("Night shift", npcs.groups().find("guards").orElseThrow().description());
        click("Description", Click.LEFT);
        type("");
        assertTrue(last().endsWith("Removed the description of guards"), last());
        assertFalse(JsonDocuments.object(store.read("groups/guards.json")).has("description"));
        click("Values", Click.LEFT);
        assertEquals("Values of guards", title());
        assertFalse(buttons().containsValue("Name") || buttons().containsValue("Id") || buttons().containsValue("Body"), "fields a trait group may not set are left out: " + buttons());
        folder("Glowing", Click.LEFT);
        assertFalse(alex.lines.getLast().transactions().isEmpty(), last());
        assertTrue(JsonDocuments.object(store.read("groups/guards.json")).getAsJsonObject("values").get("glowing").getAsBoolean());
        click("Search", Click.LEFT);
        type("tags");
        assertTrue(lore("Tags").contains("Now: 1 entry"), lore("Tags"));
        click("Search", Click.LEFT);
        click("Back", Click.LEFT);
        assertEquals("Trait group guards", title());
    }

    @Test void membersSelectOnAClickAndLeaveOnlyThroughTheirDetails() {
        openGroup();
        click("Members", Click.LEFT);
        assertEquals("Members of guards", title());
        assertEquals(Map.of(0, "guard", 36, "Select all members", 45, "Back", 53, "Help"), buttons());
        assertTrue(lore("guard").contains("Folder town") && !lore("guard").contains("npcs/"), lore("guard"));
        centreIsSafe();
        click("guard", Click.LEFT);
        assertEquals(List.of("guard"), toolkit.selected(alex.id));
        click("guard", Click.RIGHT);
        assertEquals("guard in guards", title());
        assertEquals(List.of("guards"), groups("guard"), "a right-click never takes it out");
        assertEquals(Map.of(4, "guard", 10, "Selected: yes", 11, "Edit it", 17, "Take off", 18, "Back", 26, "Help"), buttons());
        click("Selected: yes", Click.LEFT);
        assertTrue(toolkit.selected(alex.id).isEmpty());
        assertEquals("Selected: no", name(GroupMembersScreen.SELECTED));
        click("Take off", Click.LEFT);
        assertEquals("Click again to confirm", name(GroupMembersScreen.LEAVE));
        assertEquals(List.of("guards"), groups("guard"), "the first click only asks");
        click(GroupMembersScreen.LEAVE, Click.LEFT);
        assertTrue(last().contains("Unassigned guards from guard"), last());
        assertTrue(groups("guard").isEmpty());
        assertEquals("Members of guards", title());
        MockScreenHost.slot(alex, "No members yet");
        click("Select all members", Click.LEFT);
        assertTrue(toolkit.selected(alex.id).isEmpty());
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

    @Test void bulkAssignAsksOnce() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < CROWD; i++) many.add("c" + i);
        toolkit.selection().set(alex.id, many);
        toolkit.open(alex.id, GroupScreen::new);
        click("guards", Click.LEFT);
        assertTrue(lore("Click again to confirm").contains(CROWD + " NPCs affected"), lore("Click again to confirm"));
        assertTrue(groups("c0").isEmpty(), "the first click only asks");
        click("Click again to confirm", Click.LEFT);
        assertTrue(last().contains("Assigned guards to " + CROWD + " NPCs"), last());
        assertEquals(List.of("guards"), groups("c0"));
    }

    @Test void advancedNpcsListsActsOnAndRebuildsTheSelection() {
        toolkit.mode(alex.id, ToolkitMode.ADVANCED);
        select("guard", "baker");
        toolkit.open(alex.id, NpcsScreen::new);
        assertEquals("NPCs", title());
        String guard = lore("guard");
        assertTrue(guard.contains("3 blocks away") && guard.contains("Selected"), guard);
        assertTrue(lore("cook").contains("Spawned"), "spawned but not near anyone: " + lore("cook"));
        click("baker", Click.SHIFT_LEFT);
        assertEquals(List.of("guard"), toolkit.selected(alex.id));
        click("guard", Click.RIGHT);
        assertTrue(alex.effects.contains("teleport:world:1,64,0"), "beside the guard at 3 64 0, on the player's side: " + alex.effects);

        click("By tag", Click.LEFT);
        type("armed");
        assertEquals(List.of("guard"), toolkit.selected(alex.id), "tags from groups count");
        assertTrue(last().contains("Selected 1 NPC tagged armed"), last());

        click("By folder", Click.LEFT);
        folder("town / kitchen", Click.LEFT);
        assertEquals("NPCs", title());
        assertEquals(List.of("cook"), toolkit.selected(alex.id));

        select("guard", "cook");
        click("Invert in folder", Click.LEFT);
        folder("town", Click.LEFT);
        assertEquals(List.of("cook", "baker"), toolkit.selected(alex.id), "guard leaves, baker joins, cook outside the folder stays");

        click("All in world", Click.LEFT);
        assertEquals(List.of("cook", "baker"), toolkit.selected(alex.id), CROWD + 2 + " NPCs asks first");
        click("Click again to confirm", Click.LEFT);
        assertEquals(CROWD + 2, toolkit.selected(alex.id).size());
        assertFalse(toolkit.selected(alex.id).contains("cook"), "cook is not spawned near anyone");

        click("Clear selection", Click.LEFT);
        assertTrue(toolkit.selected(alex.id).isEmpty());
        assertFalse(shows("Edit selection"));
    }

    @Test void foldersMoveOnAClickAndRenameInTheirDetails() throws IOException {
        toolkit.open(alex.id, FolderScreen::new);
        assertEquals(Map.of(0, "Top level", 1, "crowd", 2, "town", 3, "town / kitchen", 36, "New folder", 44, "0 NPCs selected", 45, "Back", 46, "Search", 53, "Help"), buttons());
        assertTrue(folderLore("town").contains("2 NPCs, 3 with subfolders"), folderLore("town"));
        centreIsSafe();
        select("baker");
        assertTrue(folderLore("town").contains("Now: baker is here") && folderLore("town").contains("Click: move baker here, right-click: details"), folderLore("town"));
        folder("town / kitchen", Click.LEFT);
        assertTrue(last().contains("Moved 1 NPC to "), last());
        assertNotNull(store.read("npcs/town/kitchen/baker.json"));

        folder("town", Click.RIGHT);
        assertEquals("Folder town", title());
        assertEquals(Map.of(4, "town", 11, "Rename", 18, "Back", 26, "Help"), buttons());
        assertTrue(lore(FolderScreen.HEADER).contains("Now: 1 NPC, 3 with subfolders"), lore(FolderScreen.HEADER));
        click("Rename", Click.LEFT);
        assertEquals("New folder name", alex.prompt.label());
        assertEquals("town", alex.prompt.initial());
        type("village");
        String line = last();
        assertTrue(line.endsWith("Renamed folder town to village, moved 3 NPCs"), line);
        assertNotNull(store.read("npcs/village/guard.json"));
        assertNotNull(store.read("npcs/village/kitchen/cook.json"));
        assertEquals("village/kitchen", npcs.find("cook").orElseThrow().group());
        assertEquals("Folder village", title(), "the details follow the rename");
        click("Back", Click.LEFT);

        folder("crowd", Click.RIGHT);
        click("Rename", Click.LEFT);
        assertNull(alex.prompt, CROWD + " NPCs asks first");
        assertEquals("Click again to confirm", name(FolderScreen.RENAME));
        assertTrue(lore(FolderScreen.RENAME).contains("Renames crowd"), lore(FolderScreen.RENAME));
        click(FolderScreen.RENAME, Click.LEFT);
        assertNotNull(alex.prompt);
        MockScreenHost.answer(alex, Optional.empty());
        click("Back", Click.LEFT);

        folder("Top level", Click.RIGHT);
        assertFalse(shows("Rename"), "the top level keeps its name");
        click("Back", Click.LEFT);

        click("New folder", Click.LEFT);
        assertEquals("Folder name", alex.prompt.label());
        type("market / stalls");
        assertTrue(last().contains("Moved 1 NPC to "), last());
        assertNotNull(store.read("npcs/market/stalls/baker.json"));
        assertTrue(shows("market / stalls"));
    }
}
