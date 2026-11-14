package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.command.PermissionNodes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.gui.GuiModel.Click;
import art.arcane.animus.core.gui.MockScreenHost;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.inventory.MockInventory.Stack;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.text.Words;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Home, NPCs and the NPC screen per mode, the wand's clicks and how {@code /animus tools} routes. */
class ToolkitScreensTest extends ToolkitHarness {
    static final Set<String> NOT_ON_CENTRE = Set.of("Delete", "Close", "Stand still", "Discard", "Clear selection");

    @BeforeEach void setUp() throws IOException {
        List<DocumentStore.Change> files = new ArrayList<>(List.of(npcFile("guard", GUARD, ""), npcFile("baker", BAKER, "")));
        for (int i = 0; i < 50; i++) files.add(file("groups/g" + (100 + i) + ".json", "{\"schemaVersion\":2,\"revision\":1,\"id\":\"g" + (100 + i) + "\",\"values\":{\"tags\":[\"t\"]}}"));
        start(files, true);
        active.put(GUARD, 3.0);
        active.put(BAKER, 6.0);
    }

    void advanced() { toolkit.mode(alex.id, ToolkitMode.ADVANCED); }

    void centreIsSafe() {
        for (int slot : List.of(13, 22, 31, 34)) assertFalse(NOT_ON_CENTRE.contains(name(slot)), title() + " has " + name(slot) + " on slot " + slot);
    }

    @Test void homeShowsTheCommonTasksInSimpleAndTheLibraryInAdvanced() {
        toolkit.home(alex.id);
        assertEquals("Animus", title());
        assertEquals(Map.of(10, "New NPC", 12, "NPCs", 14, "Wand", 16, "Web editor", 18, "Close", 25, "Mode: Simple", 26, "Help"), buttons());
        centreIsSafe();
        click("Mode: Simple", Click.LEFT);
        assertEquals(Map.ofEntries(Map.entry(10, "New NPC"), Map.entry(12, "NPCs"), Map.entry(14, "Wand"), Map.entry(16, "Web editor"),
                Map.entry(18, "Folders"), Map.entry(19, "Behaviors"), Map.entry(20, "Shops"), Map.entry(21, "Routes"), Map.entry(22, "Recordings"), Map.entry(23, "Holograms"), Map.entry(24, "GUIs"),
                Map.entry(25, "Skins"), Map.entry(26, "Trait groups"), Map.entry(27, "Close"), Map.entry(34, "Mode: Advanced"), Map.entry(35, "Help")), buttons(), "Advanced adds a row; Simple items stay where they were");
        centreIsSafe();
        click("Trait groups", Click.LEFT);
        assertEquals("Trait groups", title());
        click("Back", Click.LEFT);
        click("Holograms", Click.LEFT);
        assertEquals("Saved holograms", title());
        click("Back", Click.LEFT);
        assertEquals("Animus", title());
        click("Close", Click.LEFT);
        assertNull(alex.window);
    }

    @Test void theModeLeverSwitchesRedrawsAndIsSaved() throws IOException {
        toolkit.home(alex.id);
        assertTrue(lore("Mode: Simple").contains("Simple shows the common tasks."));
        assertTrue(lore("Mode: Simple").contains("Click: switch to Advanced"));
        click("Mode: Simple", Click.LEFT);
        assertEquals(ToolkitMode.ADVANCED, toolkit.mode(alex.id));
        assertTrue(shows("Mode: Advanced"));
        drain();
        assertTrue(java.nio.file.Files.readString(store.resolve(ToolkitPrefs.path(alex.id))).contains("advanced"));
        List<Runnable> lane = new ArrayList<>();
        ToolkitPrefs fresh = new ToolkitPrefs(() -> store, lane::add);
        fresh.load(alex.id);
        lane.forEach(Runnable::run);
        assertEquals(ToolkitMode.ADVANCED, fresh.mode(alex.id), "the next join reads Advanced");
    }

    @Test void homeShowsTheFinishButtonWhileACaptureOrRecordingRuns() throws IOException {
        toolkit.home(alex.id);
        assertTrue(name(HomeScreen.RUNNING).isBlank());
        recording = true;
        toolkit.home(alex.id);
        assertEquals("Stop recording", name(HomeScreen.RUNNING));
        click("Stop recording", Click.LEFT);
        assertEquals(List.of("animus record stop"), alex.commands);
        recording = false;
        toolkit.captures().start(alex, "walk", List.of());
        toolkit.route(alex.id, "add", "");
        stand(4, 0, false);
        toolkit.route(alex.id, "add", "");
        toolkit.home(alex.id);
        assertEquals("Finish route (2 points)", name(HomeScreen.RUNNING));
        click("Finish route", Click.LEFT);
        assertEquals("Route points", title());
        click("Save", Click.LEFT);
        drain();
        assertTrue(toolkit.captures().capture(alex.id).isEmpty());
        assertNotNull(store.read("routes/walk.json"));
    }

    @Test void theNpcScreenListsItsJobsPerMode() {
        toolkit.selectOnly(alex.id, "guard");
        toolkit.open(alex.id);
        assertEquals("guard", title());
        assertEquals(Map.ofEntries(Map.entry(4, "guard"), Map.entry(10, "Name"), Map.entry(11, "Body"), Map.entry(12, "Skin"), Map.entry(13, "Equipment"), Map.entry(14, "Hologram"),
                Map.entry(19, "Talk"), Map.entry(20, "Shop"), Map.entry(21, "Movement"), Map.entry(22, "Combat"),
                Map.entry(28, "Bring here"), Map.entry(29, "Go to"), Map.entry(30, "Spawned: on"), Map.entry(31, "Copy"), Map.entry(35, "Delete"),
                Map.entry(45, "Back"), Map.entry(52, "Mode: Simple"), Map.entry(53, "Help")), buttons());
        centreIsSafe();
        click("Mode: Simple", Click.LEFT);
        Map<Integer, String> shown = buttons();
        for (Map.Entry<Integer, String> added : Map.of(16, "All fields", 23, "Behaviors", 24, "GUIs", 32, "Folder", 33, "Trait groups", 37, "Attributes", 38, "Modules",
                39, "Inspect", 40, "Revive", 41, "Open in editor").entrySet()) assertEquals(added.getValue(), shown.get(added.getKey()), shown.toString());
        assertEquals("Mode: Advanced", shown.get(52));
        assertEquals(28, shown.size());
        centreIsSafe();
        assertTrue(lore("guard").contains("Folder town"), "Advanced shows the folder in the header");
    }

    @Test void severalSelectedShareOneScreenAndOneOnlyJobsSaySo() {
        toolkit.selectOnly(alex.id, "guard");
        toolkit.selection().toggle(alex.id, "baker");
        toolkit.open(alex.id);
        assertEquals("2 NPCs", title());
        assertTrue(lore("Name").contains("First only"));
        assertTrue(lore("Go to").contains("First only"));
        assertFalse(lore("Copy").contains("First only"));
        assertTrue(lore("2 NPCs").contains("guard, baker"));
    }

    @Test void toolsOpensTheNpcScreenOverHomeOrHomeAlone() {
        toolkit.open(alex.id);
        assertEquals("Animus", title(), "nothing selected opens Home");
        toolkit.selectOnly(alex.id, "baker");
        toolkit.open(alex.id);
        assertEquals("baker", title());
        click("Back", Click.LEFT);
        assertEquals("Animus", title(), "Back from the NPC screen goes Home");
    }

    @Test void deleteAsksOnceOnTheNpcScreen() {
        toolkit.selectOnly(alex.id, "guard");
        toolkit.open(alex.id);
        click("Delete", Click.LEFT);
        drain();
        assertTrue(npcs.find("guard").isPresent(), "the first click only arms");
        assertEquals("Click again to confirm", name(NpcScreen.DELETE));
        assertTrue(lore(NpcScreen.DELETE).contains("Deletes guard"), lore(NpcScreen.DELETE));
        assertTrue(lore(NpcScreen.DELETE).contains("Any other click cancels"));
        click("Copy", Click.LEFT);
        drain();
        assertEquals("Delete", name(NpcScreen.DELETE), "another click cancels");
        click("Delete", Click.LEFT);
        click("Click again to confirm", Click.LEFT);
        drain();
        assertTrue(npcs.find("guard").isEmpty());
        assertTrue(alex.messages.contains("[Animus]: Deleted guard"), alex.messages.toString());
        assertTrue(toolkit.selected(alex.id).isEmpty(), "a deleted NPC leaves the selection");
        assertEquals("NPCs", title());
    }

    @Test void aRefusalShowsOnTheWindowUntilTheNextClick() {
        alex.permissions.clear();
        alex.permissions.add(PermissionNodes.TOOLS);
        toolkit.selectOnly(alex.id, "guard");
        toolkit.open(alex.id);
        assertEquals("No permission\nanimus.npc.create", lore(NpcScreen.COPY), "copy needs animus.npc.create, so it is greyed out");
        click("Copy", Click.LEFT);
        drain();
        assertTrue(npcs.find("guard-copy").isEmpty());
        toolkit.route(alex.id, "add", "");
        assertEquals("No permission: animus.npc.move; ask an operator to grant it", name(ToolScreen.PAGE));
        assertTrue(alex.messages.getLast().contains("No permission: animus.npc.move"), "and in chat");
        assertTrue(alex.sounds.contains("minecraft:block.beacon.deactivate@0.2"), alex.sounds.toString());
        click(0, Click.LEFT);
        assertEquals("", name(ToolScreen.PAGE).replace(" ", ""), "any next click ends it");
        assertEquals("No permission\nanimus.combat", lore(NpcScreen.COMBAT), "the combat screen needs animus.combat");
        click("Combat", Click.LEFT);
        assertEquals("guard", title());
    }

    @Test void manageRunsSpawnCopyInspectReviveAndTheEditorLink() {
        toolkit.selectOnly(alex.id, "guard");
        advanced();
        toolkit.open(alex.id);
        click("Spawned: on", Click.LEFT);
        drain();
        assertTrue(alex.messages.getLast().contains("Despawned 1 NPC; tx "), alex.messages.getLast());
        assertEquals("Spawned: off", name(NpcScreen.SPAWNED));
        click("Spawned: off", Click.LEFT);
        drain();
        assertTrue(alex.messages.getLast().contains("Spawned 1 NPC; tx "), alex.messages.getLast());
        click("Copy", Click.LEFT);
        drain();
        assertTrue(npcs.find("guard-copy").isPresent());
        click("Revive", Click.LEFT);
        assertEquals(List.of("revive guard"), calls);
        click("Inspect", Click.LEFT);
        assertEquals(List.of("revive guard", "inspect guard"), calls);
        assertNull(alex.window, "inspect closes the window so the chat shows");
        toolkit.open(alex.id);
        click("Open in editor", Click.LEFT);
        assertEquals(List.of("revive guard", "inspect guard", "pair"), calls);
    }

    @Test void nameWritesTheDisplayNameOfTheFirstOnly() {
        toolkit.selectOnly(alex.id, "guard");
        toolkit.selection().toggle(alex.id, "baker");
        toolkit.open(alex.id);
        click("Name", Click.LEFT);
        assertEquals("Name", alex.prompt.label());
        MockScreenHost.answer(alex, Optional.of("Gate Guard"));
        drain();
        assertEquals("Gate Guard", npcs.find("guard").orElseThrow().name());
        assertEquals("baker", npcs.find("baker").orElseThrow().name());
    }

    @Test void movementShowsWhatItDoesAndStandStillRemovesIt() throws IOException {
        store.commit(List.of(file("routes/market.json", ToolkitRoutesTest.MARKET)));
        toolkit.selectOnly(alex.id, "guard");
        toolkit.open(alex.id);
        click("Movement", Click.LEFT);
        assertEquals("Movement", title());
        assertTrue(lore("Route").contains("Walks point to point."));
        assertTrue(lore("Recording").contains("Replays what you did."));
        assertTrue(lore(MovementScreen.HEADER).contains("Now: stands still"));
        toolkit.write(manager.viewer(alex.id).orElseThrow(), (ToolkitFiles files) -> files.route("market", List.of("guard")));
        drain();
        assertTrue(lore(MovementScreen.HEADER).contains("Now: walks route Market"), lore(MovementScreen.HEADER));
        click("Stand still", Click.LEFT);
        click("Click again to confirm", Click.LEFT);
        drain();
        assertTrue(calls.contains("stop guard"));
        assertTrue(alex.messages.getLast().contains("guard stands still"), alex.messages.getLast());
        assertFalse(alex.messages.stream().anyMatch((String line) -> line.contains("Stopped guard")), "one line, the saved change: " + alex.messages);
        assertTrue(lore(MovementScreen.HEADER).contains("Now: stands still"), lore(MovementScreen.HEADER));
        click("Route", Click.LEFT);
        assertEquals("Routes", title());
    }

    @Test void npcsListsNearestFirstThenTheRestAndEditsOrVisits() throws IOException {
        toolkit.home(alex.id);
        click("NPCs", Click.LEFT);
        assertEquals("NPCs", title());
        assertTrue(MockScreenHost.slot(alex, "guard") < MockScreenHost.slot(alex, "baker"));
        assertTrue(lore("baker").contains("6 blocks away"), lore("baker"));
        assertEquals("Search", name(ToolScreen.SEARCH), "NPCs always offers Search");
        assertFalse(lore("baker").contains("Sneak-click"), "Simple has no multi-select");
        click("guard", Click.RIGHT);
        assertTrue(alex.effects.contains("teleport:world:1,64,0"), "beside the guard at 3 64 0, on the player's side: " + alex.effects);
        toolkit.home(alex.id);
        click("NPCs", Click.LEFT);
        click("baker", Click.LEFT);
        assertEquals(List.of("baker"), toolkit.selected(alex.id));
        assertEquals("baker", title(), "a click opens its NPC screen");
        click("Back", Click.LEFT);
        assertEquals("NPCs", title());
    }

    @Test void advancedNpcsMultiSelectsAndBuildsTheSelection() {
        advanced();
        toolkit.home(alex.id);
        click("NPCs", Click.LEFT);
        assertTrue(lore("baker").contains("Sneak-click: add or remove"));
        click("guard", Click.SHIFT_LEFT);
        click("baker", Click.SHIFT_RIGHT);
        assertEquals(List.of("guard", "baker"), toolkit.selected(alex.id));
        assertTrue(lore("baker").contains("Selected"));
        assertEquals("Edit selection (2)", name(NpcsScreen.EDIT));
        click("Clear selection", Click.LEFT);
        assertTrue(toolkit.selected(alex.id).isEmpty());
        assertTrue(alex.messages.contains("bar:Selection cleared"), alex.messages.toString());
        click("All in world", Click.LEFT);
        assertEquals(List.of("baker", "guard"), toolkit.selected(alex.id));
        click("By folder", Click.LEFT);
        assertEquals("By folder", title());
        click("town", Click.LEFT);
        assertEquals("NPCs", title(), "picking goes back");
        assertTrue(alex.messages.getLast().contains("Selected 2 NPCs in town"), alex.messages.getLast());
    }

    @Test void theWandSelectsEditsAndTogglesNpcs() {
        assertTrue(toolkit.interacted(alex, GUARD, true, false), "a left click selects only");
        assertEquals(List.of("guard"), toolkit.selected(alex.id));
        assertEquals("guard", primary, "the wand also sets the selection @selected names");
        assertEquals(List.of("guard"), glows, "it glows a moment, as /animus select makes it");
        assertNull(alex.window, "a left click opens nothing");
        now += Toolkit.REPEAT_NANOS / 2;
        assertTrue(toolkit.interacted(alex, GUARD, false, false), "the second hand of the same click is used and ignored");
        assertNull(alex.window);
        now += Toolkit.REPEAT_NANOS;
        assertTrue(toolkit.interacted(alex, BAKER, false, false), "a right-click selects and edits");
        assertEquals(List.of("baker"), toolkit.selected(alex.id));
        assertEquals("baker", title());
        now += Toolkit.REPEAT_NANOS;
        assertTrue(toolkit.interacted(alex, GUARD, false, true));
        assertEquals(List.of("baker", "guard"), toolkit.selected(alex.id));
        stand(0, 0, true);
        now += Toolkit.REPEAT_NANOS;
        assertTrue(toolkit.interacted(alex, BAKER, true, false), "a left click reads sneaking from the pose");
        assertEquals(List.of("guard"), toolkit.selected(alex.id));
        assertTrue(alex.messages.contains("bar:Removed baker, 1 selected"), alex.messages.toString());

        alex.wand = false;
        assertFalse(toolkit.interacted(alex, GUARD, false, false), "without the wand the click goes on to shops and GUIs");
        alex.wand = true;
        alex.permissions.clear();
        assertFalse(toolkit.interacted(alex, GUARD, false, false), "only players with animus.tools use the wand");
    }

    @Test void theWandOnAirOpensHomeClearsOrSaysItMissed() {
        assertTrue(toolkit.used(alex, false));
        assertNull(alex.window, "it waits a moment for an NPC click of the same press");
        toolkit.tick(alex);
        toolkit.tick(alex);
        assertEquals("Animus", title());
        manager.close(alex);

        toolkit.used(alex, false);
        now += Toolkit.REPEAT_NANOS;
        toolkit.interacted(alex, GUARD, false, false);
        assertEquals("guard", title(), "the NPC click of the same press wins");
        manager.close(alex);
        toolkit.tick(alex);
        toolkit.tick(alex);
        assertNull(alex.window, "and Home never opens after it");

        now += Toolkit.REPEAT_NANOS;
        toolkit.used(alex, true);
        toolkit.tick(alex);
        toolkit.tick(alex);
        assertTrue(toolkit.selected(alex.id).isEmpty(), "sneak + right-click clears the selection");
        assertTrue(alex.messages.contains("bar:Selection cleared"));

        toolkit.swung(alex);
        toolkit.tick(alex);
        toolkit.tick(alex);
        toolkit.swung(alex);
        toolkit.tick(alex);
        toolkit.tick(alex);
        assertEquals(1, alex.messages.stream().filter("bar:No NPC there"::equals).count(), "at most once every 2 seconds");
        now += Toolkit.MISS_NANOS;
        toolkit.swung(alex);
        toolkit.tick(alex);
        toolkit.tick(alex);
        assertEquals(2, alex.messages.stream().filter("bar:No NPC there"::equals).count());

        alex.wand = false;
        assertFalse(toolkit.used(alex, false), "without the wand the click is the item's own");
    }

    @Test void aSwingFarFromEveryNpcStaysQuietUnlessSneaking() {
        stand(40, 0, false);
        toolkit.swung(alex);
        toolkit.tick(alex);
        toolkit.tick(alex);
        assertFalse(alex.messages.contains("bar:No NPC there"), "only holding the wand: " + alex.messages);
        stand(40, 0, true);
        toolkit.swung(alex);
        toolkit.tick(alex);
        toolkit.tick(alex);
        assertTrue(alex.messages.contains("bar:No NPC there"), "a sneak-click was meant for an NPC: " + alex.messages);
    }

    @Test void aSwingWhileRecordingIsPartOfTheTakeAndSaysNothing() {
        recording = true;
        stand(0, 0, true);
        toolkit.swung(alex);
        toolkit.tick(alex);
        toolkit.tick(alex);
        assertFalse(alex.messages.contains("bar:No NPC there"), "the swing is recorded, not a missed click: " + alex.messages);
    }

    @Test void theWandAndTheLoreNameTheNpcAsPlayersSeeItAndAdvancedAddsTheId() {
        toolkit.selectOnly(alex.id, "guard");
        toolkit.open(alex.id);
        drain();
        click("Name", Click.LEFT);
        MockScreenHost.answer(alex, Optional.of("Gate Guard"));
        drain();
        toolkit.interacted(alex, GUARD, true, false);
        assertEquals("bar:Selected Gate Guard", alex.messages.stream().filter((String line) -> line.startsWith("bar:Selected")).reduce((String a, String b) -> b).orElseThrow());
        toolkit.open(alex.id);
        drain();
        click(NpcScreen.DELETE, Click.LEFT);
        assertTrue(lore(NpcScreen.DELETE).startsWith("Deletes Gate Guard;"), lore(NpcScreen.DELETE));
        advanced();
        toolkit.open(alex.id);
        drain();
        click(NpcScreen.DELETE, Click.LEFT);
        assertTrue(lore(NpcScreen.DELETE).startsWith("Deletes Gate Guard (guard);"), lore(NpcScreen.DELETE));
    }

    @Test void theWandOpensNothingBeforeTheCommandTreeHooksInAndThenUsesItsCommands() {
        toolkit.close();
        toolkit = Toolkit.install(new Toolkit.Parts<>(() -> npcs, () -> store, manager, living, queue::add, (MockScreenHost.Player player) -> Optional.empty(), Optional.empty(), () -> now));
        assertTrue(toolkit.interacted(alex, GUARD, false, false));
        drain();
        assertNull(alex.window, "no menu runs on commands that are not there");
        assertTrue(alex.messages.getLast().contains("The Animus menu is still starting"), alex.messages.getLast());

        recording = true;
        UUID run = UUID.randomUUID();
        runs = (String npc) -> CompletableFuture.completedFuture(List.of(new Toolkit.Run(run, npc, "town:talk")));
        toolkit.hook(commands);
        now += Toolkit.REPEAT_NANOS;
        assertTrue(toolkit.used(alex, false));
        toolkit.tick(alex);
        toolkit.tick(alex);
        drain();
        assertEquals("Stop recording", name(HomeScreen.RUNNING), "a menu the wand opens right after a restart sees the recording");
        click("Stop recording", Click.LEFT);
        assertEquals(List.of("animus record stop"), alex.commands);
        toolkit.open(alex.id, (Toolkit<MockScreenHost.Player, Stack> kit, ScreenManager<MockScreenHost.Player, Stack>.Viewer viewer, Toolkit.Commands lent) ->
                new BehaviorRunsScreen<>(kit, viewer, lent, "town:talk", List.of("guard")));
        drain();
        assertEquals("On guard", name(0), "and Cancel a run lists the running runs");
    }

    @Test void bringHereMovesEverySelectedNpcInOneChange() {
        toolkit.selectOnly(alex.id, "guard");
        toolkit.selection().toggle(alex.id, "baker");
        stand(10, 5, false);
        toolkit.open(alex.id);
        drain();
        click("Bring here", Click.LEFT);
        drain();
        assertEquals(1, changes().size(), "one Undo puts both back: " + alex.messages);
        for (String id : List.of("guard", "baker")) {
            com.google.gson.JsonObject position = npcs.find(id).orElseThrow().json().getAsJsonObject("position");
            assertEquals(10.0, position.get("x").getAsDouble(), id);
            assertEquals(7.0, position.get("z").getAsDouble(), "two blocks in front of Alex, who looks south: " + id);
            assertEquals(-180.0, position.get("yaw").getAsDouble(), "turned to face Alex: " + id);
        }
        assertTrue(alex.messages.stream().anyMatch((String line) -> line.contains("Moved 2 NPCs here")), alex.messages.toString());
    }

    @Test void deleteLeavesForTheNpcListAndForHomeWhenNoneAreLeft() {
        toolkit.selectOnly(alex.id, "guard");
        toolkit.open(alex.id);
        drain();
        click(NpcScreen.DELETE, Click.LEFT);
        click(NpcScreen.DELETE, Click.LEFT);
        drain();
        assertTrue(npcs.find("guard").isEmpty());
        assertEquals("NPCs", title(), "never an empty NPC window");
        click(ToolScreen.BACK, Click.LEFT);
        assertEquals("Animus", title(), "Back from the list goes Home");
        toolkit.selectOnly(alex.id, "baker");
        toolkit.open(alex.id);
        drain();
        click(NpcScreen.DELETE, Click.LEFT);
        click(NpcScreen.DELETE, Click.LEFT);
        drain();
        assertEquals("Animus", title(), "with no NPC left the window goes Home");
    }

    @Test void undoingADeleteSelectsTheNpcsItBringsBack() throws IOException {
        toolkit.selectOnly(alex.id, "baker");
        String deleted = new ToolkitFiles(npcs, store).delete(List.of("guard"));
        String tx = deleted.substring(deleted.indexOf("; tx ") + 5);
        String renamed = new ToolkitFiles(npcs, store).copies(List.of("baker"));
        store.rollback(tx);
        npcs.reload();
        toolkit.undone(alex.id, tx.substring(0, 8));
        assertEquals(List.of("guard"), toolkit.selected(alex.id), "the restored NPC is selected for the player who undid it");
        toolkit.selectOnly(alex.id, "baker");
        store.rollback(renamed.substring(renamed.indexOf("; tx ") + 5));
        npcs.reload();
        toolkit.undone(alex.id, renamed.substring(renamed.indexOf("; tx ") + 5));
        assertEquals(List.of("baker"), toolkit.selected(alex.id), "undoing anything but a delete leaves the selection alone");
    }

    @Test void aScreenWhoseNpcsVanishLeavesOnItsNextTick() throws IOException {
        toolkit.selectOnly(alex.id, "guard");
        toolkit.open(alex.id);
        drain();
        click("Equipment", Click.LEFT);
        assertEquals("Equipment", title());
        new ToolkitFiles(npcs, store).delete(List.of("guard"));
        manager.tick(alex);
        assertEquals("NPCs", title());
    }

    @Test void theNpcScreenShowsTheRouteBeingMadeAndTheRecordingRunning() {
        toolkit.selectOnly(alex.id, "guard");
        toolkit.open(alex.id);
        drain();
        assertFalse(buttons().containsKey(NpcScreen.RUNNING));
        toolkit.captures().start(alex, "walk", List.of("guard"));
        toolkit.open(alex.id);
        drain();
        assertEquals("guard", title(), "/animus with a selection still opens the NPC screen");
        assertEquals("Finish route (0 points)", name(NpcScreen.RUNNING));
        click(NpcScreen.RUNNING, Click.LEFT);
        assertEquals("Route points", title());
        toolkit.captures().quit(alex.id);
        recording = true;
        toolkit.open(alex.id);
        drain();
        assertEquals("Stop recording", name(NpcScreen.RUNNING));
        click(NpcScreen.RUNNING, Click.LEFT);
        assertEquals(List.of("animus record stop"), alex.commands);
    }

    @Test void theLibraryOpensFoldersWithNothingSelectedSoAFolderCanBeRenamed() {
        advanced();
        toolkit.home(alex.id);
        drain();
        click("Folders", Click.LEFT);
        drain();
        assertEquals("Folders", title());
        assertTrue(toolkit.selected(alex.id).isEmpty());
        click("town", Click.RIGHT);
        assertTrue(shows("Rename"), "rename works without a selected NPC: " + buttons());
    }

    @Test void theWandIsGivenOnceAndMovedAfterThat() {
        alex.wand = false;
        toolkit.home(alex.id);
        click("Wand", Click.LEFT);
        assertTrue(alex.messages.getLast().contains("Wand given; right-click an NPC to edit it"), alex.messages.getLast());
        click("Wand", Click.LEFT);
        assertTrue(alex.messages.getLast().contains("The wand is in your hand"), alex.messages.getLast());
        assertEquals(1, alex.wands, "a second request never makes a second wand");
        assertEquals("minecraft:stick", Toolkit.WAND_ITEM.id());
        assertEquals(3, Toolkit.WAND_LORE.size());
    }

    @Test void theOutlineDrawsWhileSomethingIsSelectedAndStopsWhenCleared() {
        toolkit.interacted(alex, GUARD, true, false);
        assertEquals(Set.of(alex.id), toolkit.watching());
        for (int tick = 0; tick < ToolkitOutline.PERIOD; tick++) toolkit.tick(alex);
        assertEquals(ToolkitOutline.POINTS, alex.effects.stream().filter((String effect) -> effect.startsWith("particle:minecraft:end_rod")).count(), "one outline per period");
        toolkit.clear(alex);
        assertTrue(toolkit.watching().isEmpty());
        toolkit.interacted(alex, BAKER, true, false);
        toolkit.quit(alex.id);
        assertTrue(toolkit.selected(alex.id).isEmpty(), "quitting clears the selection");
        assertTrue(toolkit.watching().isEmpty());
    }

    @Test void aHandledClickTicksQuietly() {
        toolkit.home(alex.id);
        click(0, Click.LEFT);
        assertTrue(alex.sounds.isEmpty(), "filler is not a button");
        click("NPCs", Click.LEFT);
        assertEquals(List.of(Toolkit.CLICK_SOUND + "@0.3"), alex.sounds);
    }

    @Test void helpPrintsALineWithTheWikiAndKeepsTheWindow() {
        toolkit.home(alex.id);
        Object window = alex.window;
        click("Help", Click.LEFT);
        assertTrue(alex.messages.getLast().contains("https://docs.volmitsoftware.com/animus/21-in-game-toolkit"), alex.messages.getLast());
        assertTrue(alex.messages.getLast().contains("/animus gui opens this menu"), "Home's help names the command that opens it: " + alex.messages.getLast());
        assertEquals(window, alex.window);
    }

    @Test void listsPageThirtySixEntriesAndSearchFiltersThem() {
        advanced();
        toolkit.home(alex.id);
        click("Trait groups", Click.LEFT);
        assertEquals("Next page", name(ToolScreen.NEXT));
        assertEquals("Page 1 of 2", name(ToolScreen.PAGE));
        assertEquals(36, alex.frame.items().values().stream().filter((art.arcane.animus.core.gui.ScreenFrame.Item item) -> item.item().id().equals("minecraft:book")).count());
        click("Next page", Click.LEFT);
        assertEquals(14, alex.frame.items().values().stream().filter((art.arcane.animus.core.gui.ScreenFrame.Item item) -> item.item().id().equals("minecraft:book")).count());
        assertEquals("Previous page", name(ToolScreen.PREVIOUS));
        click("Search", Click.LEFT);
        assertEquals("Search", alex.prompt.label());
        MockScreenHost.answer(alex, Optional.of("G14"));
        assertEquals(10, alex.frame.items().values().stream().filter((art.arcane.animus.core.gui.ScreenFrame.Item item) -> item.item().id().equals("minecraft:book")).count(),
                "g140 to g149, ignoring case, back on page 1");
        assertTrue(lore(ToolScreen.SEARCH).contains("Now: G14"));
        assertEquals("", name(ToolScreen.NEXT).replace(" ", ""));
        click("Search", Click.LEFT);
        assertNull(alex.prompt, "a click while filtered clears the search");
        assertEquals("Page 1 of 2", name(ToolScreen.PAGE));
    }

    @Test void groupsAssignOrUnassignTheSelectionInOneTransaction() throws IOException {
        toolkit.interacted(alex, GUARD, true, false);
        now += Toolkit.REPEAT_NANOS;
        toolkit.interacted(alex, BAKER, false, true);
        advanced();
        toolkit.home(alex.id);
        click("Trait groups", Click.LEFT);
        click("Next page", Click.LEFT);
        click("g149", Click.LEFT);
        drain();
        String line = alex.messages.getLast();
        assertTrue(line.contains("Assigned g149 to 2 NPCs; tx "), line);
        String tx = line.substring(line.indexOf("tx ") + 3, line.indexOf("tx ") + 39);
        assertEquals(List.of("npcs/town/baker.json", "npcs/town/guard.json"), store.paths(tx).stream().filter((String path) -> path.startsWith("npcs/")).sorted().toList());
        assertTrue(lore("g149").contains("Now: on 2 NPCs"), lore("g149"));
        click("g149", Click.RIGHT);
        click("Remove from NPC", Click.LEFT);
        click(GroupDetailScreen.UNASSIGN, Click.LEFT);
        drain();
        assertTrue(alex.messages.getLast().contains("Unassigned g149 from 2 NPCs"), alex.messages.getLast());
        assertTrue(npcs.find("guard").orElseThrow().groups().isEmpty());
    }

    @Test void hintsSayClickRightClickAndSneakClick() {
        assertEquals(List.of("&fClick: &7open it"), ToolScreen.hints("open it", "open it", "open it"));
        assertEquals(List.of("&fClick: &7next", "&fRight-click: &7previous"), ToolScreen.hints("next", "previous", "next"));
        assertEquals(List.of("&fClick: &7page 2", "&fSneak-click: &7last page"), ToolScreen.hints("page 2", "", "last page"));
        assertEquals(List.of("&fClick: &7save", "&fRight-click: &7clear", "&fSneak-click: &7clear"), ToolScreen.hints("save", "clear", "clear"));
    }

    @Test void rawKeysReadAsWords() {
        assertEquals("Team color", Words.readable("teamColor"));
        assertEquals("Combat", Words.readable("animus:combat"));
        assertEquals("Movement speed", Words.readable("movement_speed"));
        assertEquals("Click redirect", Words.readable("clickRedirect"));
        assertEquals("Player", Words.readable("minecraft:player"));
        assertEquals("Max health", Words.readable("minecraft:max_health"));
    }

    @Test void everyButtonSaysWhatItsClicksDoInTheNewWords() {
        advanced();
        toolkit.selectOnly(alex.id, "guard");
        toolkit.open(alex.id);
        for (int slot : buttons().keySet()) {
            String text = lore(slot);
            if (slot == NpcScreen.SKIN && name(slot).equals("Skin") && text.contains("Player bodies only") && !text.contains("Click")) continue;
            assertTrue(text.contains("Click: "), name(slot) + " lacks click hints: " + text);
            assertFalse(text.matches("(?s).*\\b(Left|Right|Sneak) .*"), name(slot) + " uses the old wording: " + text);
        }
        UUID unused = UUID.randomUUID();
        assertFalse(toolkit.interacted(alex, unused, false, false), "an NPC that is not spawned is not the wand's");
    }
}
