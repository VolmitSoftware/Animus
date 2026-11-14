package art.arcane.animus.core.toolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.gui.GuiModel.Click;
import art.arcane.animus.core.gui.MockScreenHost;
import art.arcane.animus.core.recording.RecordingDocument;
import art.arcane.animus.core.recording.RecordingFrame;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocuments;
import art.arcane.animus.core.virtual.Vec3;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Routes: the list, one route's details, making a new one with the chat chips, and its points. */
class ToolkitRoutesTest extends ToolkitHarness {
    static final String MARKET = "{\"schemaVersion\":1,\"loop\":true,\"points\":[{\"x\":0.5,\"y\":64,\"z\":0.5},{\"x\":8.5,\"y\":64,\"z\":0.5}]}";
    static final List<String> NOT_ON_CENTRE = List.of("Delete", "Discard", "Delete point", "Close", "Stand still", "Dismount");

    static String recording(String id) {
        return new RecordingDocument(1, id, UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7"), "world", new RecordingDocument.Origin(0, 64, 0, 0, 0), 2, 40,
                List.of(new RecordingFrame(0, 0, 0, 0, 0, 0, RecordingFrame.ON_GROUND), new RecordingFrame(40, 4, 0, 0, 0, 0, RecordingFrame.ON_GROUND)), List.of()).serialize();
    }

    @BeforeEach void setUp() throws IOException {
        start(List.of(npcFile("guard", GUARD, ",\"modules\":{\"animus:navigation\":{\"enabled\":true,\"route\":\"market\"}}"), npcFile("baker", BAKER, ""),
                file("routes/market.json", MARKET)), true);
        active.put(GUARD, 3.0);
        active.put(BAKER, 3.0);
    }

    JsonObject navigation(String id) { return npcs.find(id).orElseThrow().json().getAsJsonObject("modules").getAsJsonObject("animus:navigation"); }

    String tx() { return changes().getLast(); }

    /** No destructive button or Close sits where the pointer opens. */
    void centreIsSafe() {
        for (int slot : List.of(13, 22, 31)) assertFalse(NOT_ON_CENTRE.contains(name(slot)), title() + " has " + name(slot) + " on slot " + slot);
    }

    /** One tap of sneak where Alex stands at {@code x}. */
    void sneakAt(double x) {
        stand(x, 0, false);
        toolkit.tick(alex);
        stand(x, 0, true);
        toolkit.tick(alex);
        toolkit.tick(alex);
    }

    void routes() {
        toolkit.open(alex.id, RouteScreen::new);
        drain();
    }

    void detail() {
        routes();
        click("Market", Click.RIGHT);
        drain();
    }

    @Test void theListShowsTheSameItemsInBothModesAndClickUsesTheRouteOnTheSelection() {
        toolkit.selectOnly(alex.id, "baker");
        routes();
        assertEquals("Routes", title());
        Map<Integer, String> simple = Map.of(0, "Market", 36, "New route", 45, "Back", 46, "Search", 53, "Help");
        assertEquals(simple, buttons());
        assertEquals("2 points, used by 1 NPC\nClick: use on baker\nRight-click: details", lore("Market"));
        centreIsSafe();
        toolkit.mode(alex.id, ToolkitMode.ADVANCED);
        routes();
        assertEquals(simple, buttons(), "Advanced adds nothing here; Walk once, Start and Stop now live in Movement");

        click("Market", Click.LEFT);
        drain();
        assertEquals("market", navigation("baker").get("route").getAsString());
        assertTrue(alex.messages.getLast().contains("Route Market on baker"), alex.messages.getLast());
        drain();
        assertTrue(lore("Market").contains("Now: baker walks it"), lore("Market"));
        click("Market", Click.RIGHT);
        drain();
        assertEquals("Route Market", title());
    }

    @Test void withNothingSelectedAClickOpensTheDetailsAndAnEmptyListSaysWhatToDo() throws IOException {
        routes();
        assertEquals("2 points, used by 1 NPC\nClick: details", lore("Market"));
        click("Market", Click.LEFT);
        drain();
        assertEquals("Route Market", title());
        store.commit(List.of(new art.arcane.animus.core.storage.DocumentStore.Change("routes/market.json", store.hash("routes/market.json"), null)));
        routes();
        assertEquals("No routes yet; New route makes one", name(22));
    }

    @Test void newRouteIsNamedThenWalkedWithChipsAndSavePutsItOnTheNpcAndReopensMovement() throws IOException {
        sneakAt(1);
        assertTrue(toolkit.captures().capture(alex.id).isEmpty(), "sneaking with the wand drops nothing while no route is being made");
        toolkit.selectOnly(alex.id, "baker");
        routes();
        alex.wand = false;
        click("New route", Click.LEFT);
        assertEquals("Route name", alex.prompt.label());
        assertEquals("baker route", alex.prompt.initial(), "a free name is suggested after the NPC's name");
        MockScreenHost.answer(alex, Optional.of("Bakery Loop"));
        assertNull(alex.window, "the window closes so the player can walk");
        assertTrue(alex.wand, "the wand goes in hand so sneaking drops points");
        assertTrue(alex.messages.contains("bar:Walk, tap sneak to drop a point"), alex.messages.toString());
        String chips = alex.messages.getLast();
        assertTrue(chips.startsWith("[Animus]: 0 points "), chips);
        assertEquals("[Animus]: 0 points [Add] [Undo] [Save] [Cancel]", alex.lines.getLast().visible());
        assertTrue(art.arcane.animus.core.text.ChatWidth.of(alex.lines.getLast().visible().replace("0 points", "88 points")) <= art.arcane.animus.core.text.ChatWidth.DEFAULT,
                "the chip line stays on one line at the default chat width");
        for (String step : List.of("add", "undo", "save", "cancel")) assertTrue(chips.contains("/animus tools capture " + step), chips);

        toolkit.route(alex.id, "save", "");
        assertTrue(alex.messages.getLast().contains("A route needs at least 2 points; it has 0"), alex.messages.getLast());
        sneakAt(2);
        assertTrue(alex.messages.getLast().contains("1 point "), "the chips come back with the count: " + alex.messages.getLast());
        stand(8, 0, false);
        toolkit.route(alex.id, "add", "");
        assertTrue(alex.messages.getLast().contains("2 points"), alex.messages.getLast());
        toolkit.route(alex.id, "undo", "");
        assertTrue(alex.messages.contains("bar:Removed the last point, 1 left"), alex.messages.toString());
        toolkit.route(alex.id, "add", "");
        assertNull(store.read("routes/bakery-loop.json"), "nothing is written before Save");

        toolkit.route(alex.id, "save", "");
        drain();
        assertTrue(alex.messages.stream().anyMatch((String line) -> line.contains("Saved route Bakery loop, 2 points, on baker")), "the name as typed: " + alex.messages);
        assertEquals("bakery-loop", navigation("baker").get("route").getAsString());
        assertTrue(toolkit.captures().capture(alex.id).isEmpty());
        assertEquals("Movement", title(), "Movement opens again on the NPC");
        assertTrue(lore(MovementScreen.HEADER).contains("Now: walks route Bakery loop"), lore(MovementScreen.HEADER));
        click("Back", Click.LEFT);
        assertEquals("baker", title());
        click("Back", Click.LEFT);
        assertEquals("Animus", title());

        store.rollback(tx());
        assertTrue(npcs.reloadAfterRollback().isEmpty());
        assertNull(store.read("routes/bakery-loop.json"), "one Undo takes back the route and its use together");
        assertFalse(npcs.find("baker").orElseThrow().json().has("modules"));
    }

    @Test void aSaveLeavesOffDeletedNpcsAndNumbersANameTakenMeanwhile() throws IOException {
        toolkit.captures().start(alex, "market", List.of("guard", "baker"));
        toolkit.route(alex.id, "add", "");
        stand(6, 0, false);
        toolkit.route(alex.id, "add", "");
        store.commit(List.of(new DocumentStore.Change("npcs/town/baker.json", store.hash("npcs/town/baker.json"), null)));
        npcs.reload();
        toolkit.route(alex.id, "save", "");
        drain();
        String saved = alex.messages.stream().filter((String line) -> line.contains("Saved route")).findFirst().orElseThrow();
        assertTrue(saved.contains("Saved route Market 2 (Market was taken), 2 points, on guard; baker no longer exists"), saved);
        assertNotNull(store.read("routes/market-2.json"));
        assertEquals("market-2", navigation("guard").get("route").getAsString());
        assertTrue(toolkit.captures().capture(alex.id).isEmpty(), "the walked points are not lost to a refusal");
    }

    @Test void newRouteWaitsUntilTheRoutesAreRead() {
        toolkit.open(alex.id, RouteScreen::new);
        assertEquals("New route", name(RouteScreen.CREATE));
        assertEquals("Reading routes", lore(RouteScreen.CREATE));
        click(RouteScreen.CREATE, Click.LEFT);
        assertNull(alex.prompt, "no name is asked before the taken names are known");
        drain();
        click("New route", Click.LEFT);
        assertEquals("Route name", alex.prompt.label());
    }

    @Test void aChipLeftFromAnotherRouteIsRefused() {
        toolkit.captures().start(alex, "walk", List.of());
        assertTrue(alex.messages.getLast().contains("/animus tools capture add name=walk"), "each chip names its route: " + alex.messages.getLast());
        toolkit.route(alex.id, "cancel", "market");
        assertTrue(alex.messages.getLast().contains("That chip is for route Market; the route you are making is Walk"), alex.messages.getLast());
        assertTrue(toolkit.captures().capture(alex.id).isPresent(), "the route being made is untouched");
        toolkit.route(alex.id, "cancel", "walk");
        assertTrue(toolkit.captures().capture(alex.id).isEmpty());
    }

    @Test void theSavedLineShowsTheFilePathOnlyInAdvanced() {
        for (ToolkitMode mode : ToolkitMode.values()) {
            toolkit.mode(alex.id, mode);
            String route = "walk-" + mode.label().toLowerCase(java.util.Locale.ROOT);
            toolkit.captures().start(alex, route, List.of());
            toolkit.route(alex.id, "add", "");
            stand(6, 0, false);
            toolkit.route(alex.id, "add", "");
            toolkit.route(alex.id, "save", "");
            drain();
            String saved = alex.messages.stream().filter((String line) -> line.contains("Saved route " + art.arcane.animus.core.text.Words.readable(route))).findFirst().orElseThrow();
            assertEquals(mode == ToolkitMode.ADVANCED, saved.contains("routes/" + route + ".json"), mode + ": " + saved);
            stand(0, 0, false);
        }
    }

    @Test void cancelDropsTheRouteAndATakenNameOrABadNameIsRefused() {
        routes();
        click("New route", Click.LEFT);
        assertEquals("route", alex.prompt.initial(), "with nothing selected the suggestion is plain");
        MockScreenHost.answer(alex, Optional.of("market"));
        assertTrue(alex.messages.getLast().contains("Route Market already exists; pick another name"), alex.messages.getLast());
        assertNotNull(alex.window, "a refused name keeps the window");
        click("New route", Click.LEFT);
        MockScreenHost.answer(alex, Optional.of("no/slashes"));
        assertTrue(alex.messages.getLast().contains("A route name is 1 to 64 lowercase letters, digits, _ or -"), alex.messages.getLast());
        click("New route", Click.LEFT);
        MockScreenHost.answer(alex, Optional.of("walk"));
        toolkit.route(alex.id, "add", "");
        toolkit.route(alex.id, "cancel", "");
        assertTrue(toolkit.captures().capture(alex.id).isEmpty());
        assertTrue(alex.messages.getLast().contains("Dropped route Walk; nothing saved"), alex.messages.getLast());
        toolkit.route(alex.id, "add", "");
        assertTrue(alex.messages.getLast().contains("No route being made; New route in Routes starts one"), alex.messages.getLast());
    }

    @Test void withoutAnNpcSaveOnlySavesAndTheListShowsFinishWhileARouteIsBeingMade() throws IOException {
        routes();
        click("New route", Click.LEFT);
        MockScreenHost.answer(alex, Optional.of("walk"));
        toolkit.route(alex.id, "add", "");
        stand(6, 0, false);
        toolkit.route(alex.id, "add", "");
        routes();
        assertEquals("Finish route (2 points)", name(RouteScreen.CREATE));
        click("Finish route", Click.LEFT);
        assertEquals("Route points", title());
        click("Save", Click.LEFT);
        drain();
        assertTrue(alex.messages.stream().anyMatch((String line) -> line.contains("Saved route Walk, 2 points")), alex.messages.toString());
        assertNotNull(store.read("routes/walk.json"));
        drain();
        assertEquals("Routes", title(), "Save goes back to the list, which shows the new route");
        assertEquals("New route", name(RouteScreen.CREATE));
        assertTrue(shows("Walk"));
    }

    @Test void theDetailShowsNamedSettingsInWordsAndSecondsAndWritesThem() throws IOException {
        detail();
        assertEquals(Map.ofEntries(Map.entry(4, "Market"), Map.entry(19, "Rename"), Map.entry(20, "Duplicate"), Map.entry(21, "Edit points"), Map.entry(28, "Loop"),
                Map.entry(29, "Order"), Map.entry(30, "Mode"), Map.entry(31, "Speed"), Map.entry(35, "Delete"), Map.entry(45, "Back"), Map.entry(53, "Help")), buttons());
        centreIsSafe();
        assertFalse(lore("Market").contains("routes/"), "Simple shows no file paths");
        assertTrue(lore("Speed").contains("Now: 2 blocks per second"), lore("Speed"));
        assertTrue(lore("Order").contains("Now: In order"), lore("Order"));
        assertTrue(lore("Mode").contains("Now: Walk"), lore("Mode"));
        assertTrue(lore("Loop").contains("Now: on"), lore("Loop"));

        click("Duplicate", Click.LEFT);
        assertEquals("market-copy", alex.prompt.initial());
        MockScreenHost.answer(alex, Optional.of("market-two"));
        drain();
        assertEquals(JsonDocuments.object(MARKET), JsonDocuments.object(store.read("routes/market-two.json")));
        click("Speed", Click.LEFT);
        drain();
        assertEquals(2.5, JsonDocuments.object(store.read("routes/market.json")).get("speed").getAsDouble());
        assertTrue(alex.messages.getLast().contains("Route Market speed: 2.5 blocks per second"), alex.messages.getLast());
        assertTrue(lore("Speed").contains("Now: 2.5 blocks per second"), lore("Speed"));
        click("Order", Click.LEFT);
        drain();
        JsonObject route = JsonDocuments.object(store.read("routes/market.json"));
        assertEquals("pingpong", route.get("order").getAsString());
        assertFalse(route.has("loop"), "loop only goes with In order");
        assertTrue(lore("Order").contains("Now: Back and forth"), lore("Order"));
        click("Loop", Click.LEFT);
        assertTrue(alex.messages.getLast().contains("Loop needs Order: In order"), alex.messages.getLast());
        click("Mode", Click.RIGHT);
        drain();
        assertEquals("fly", JsonDocuments.object(store.read("routes/market.json")).get("mode").getAsString(), "right-click steps back");

        toolkit.mode(alex.id, ToolkitMode.ADVANCED);
        click("Back", Click.LEFT);
        drain();
        click(buttons().entrySet().stream().filter((Map.Entry<Integer, String> entry) -> entry.getValue().equals("Market")).findFirst().orElseThrow().getKey(), Click.RIGHT);
        drain();
        assertEquals("Route Market", title());
        assertTrue(lore(RouteDetailScreen.HEADER).contains("routes/market.json"), lore(RouteDetailScreen.HEADER));
    }

    @Test void renameRepointsEveryUserInOneTransactionAndRollsBackTogether() throws IOException {
        detail();
        click("Rename", Click.LEFT);
        assertEquals("New route name", alex.prompt.label());
        MockScreenHost.answer(alex, Optional.of("bazaar"));
        drain();
        assertTrue(alex.messages.getLast().contains("Renamed route Market to Bazaar, 1 NPC repointed"), alex.messages.getLast());
        assertNull(store.read("routes/market.json"));
        assertEquals("bazaar", navigation("guard").get("route").getAsString());
        assertEquals("Route Bazaar", title());
        store.rollback(tx());
        assertTrue(npcs.reloadAfterRollback().isEmpty());
        assertNotNull(store.read("routes/market.json"));
        assertNull(store.read("routes/bazaar.json"));
        assertEquals("market", navigation("guard").get("route").getAsString());
    }

    @Test void deleteAsksOnceNamesItsNpcsAndTakesItOffThem() throws IOException {
        detail();
        assertTrue(lore("Delete").contains("Deletes route Market and takes it off guard"), lore("Delete"));
        click("Delete", Click.LEFT);
        assertEquals("Click again to confirm", name(RouteDetailScreen.DELETE));
        click("Back", Click.LEFT);
        drain();
        assertNotNull(store.read("routes/market.json"), "any other click cancels");
        click("Market", Click.RIGHT);
        drain();
        click("Delete", Click.LEFT);
        click(RouteDetailScreen.DELETE, Click.LEFT);
        drain();
        assertTrue(alex.messages.getLast().contains("Deleted route Market, unassigned from 1 NPC"), alex.messages.getLast());
        assertNull(store.read("routes/market.json"));
        assertFalse(npcs.find("guard").orElseThrow().json().has("modules"));
        assertTrue(shows("Route Market is gone"));
    }

    @Test void editPointsLoadsTheRouteAndSaveWritesItBackAndReturnsToTheDetail() throws IOException {
        detail();
        stand(4, 4, false);
        click("Edit points", Click.LEFT);
        drain();
        assertEquals("Route points", title());
        assertEquals(Optional.of("market"), toolkit.captures().capture(alex.id).orElseThrow().source());
        click("Point 2", Click.LEFT);
        click("Move here", Click.LEFT);
        click("Save", Click.LEFT);
        drain();
        assertTrue(alex.messages.getLast().contains("Saved route Market, 2 points"), alex.messages.getLast());
        assertEquals(4.5, JsonDocuments.object(store.read("routes/market.json")).getAsJsonArray("points").get(1).getAsJsonObject().get("x").getAsDouble());
        drain();
        assertEquals("Route Market", title(), "Save goes back to the route");
        store.rollback(tx());
        assertEquals(JsonDocuments.object(MARKET), JsonDocuments.object(store.read("routes/market.json")));
    }

    @Test void thePointsScreenChoosesAPointAndItsButtonsActOnIt() throws IOException {
        toolkit.open(alex.id, PointsScreen::new);
        assertEquals(Map.of(22, "No route being made", 45, "Back", 53, "Help"), buttons());
        assertTrue(lore(22).contains("New route in Routes starts one"));

        assertTrue(toolkit.captures().start(alex, "walk", List.of()));
        toolkit.route(alex.id, "add", "");
        stand(10, 0, false);
        toolkit.route(alex.id, "add", "");
        stand(20, 0, false);
        toolkit.route(alex.id, "add", "");
        stand(9, 0, false);
        toolkit.open(alex.id, PointsScreen::new);
        assertEquals(Map.ofEntries(Map.entry(0, "Point 1"), Map.entry(1, "Point 2 (here)"), Map.entry(2, "Point 3"), Map.entry(36, "Add point"), Map.entry(37, "Wait"),
                Map.entry(38, "Go there"), Map.entry(39, "Move here"), Map.entry(40, "3 points"), Map.entry(44, "Delete point"), Map.entry(45, "Back"),
                Map.entry(51, "Discard"), Map.entry(52, "Save"), Map.entry(53, "Help")), buttons());
        centreIsSafe();
        assertTrue(lore("Point 3").contains("Now: chosen"), lore("Point 3"));
        assertEquals("0 64 0\nClick: choose it", lore("Point 1"));

        click("Point 1", Click.LEFT);
        assertTrue(lore("Point 1").contains("Now: chosen"), lore("Point 1"));
        click("Go there", Click.LEFT);
        assertTrue(alex.effects.contains("teleport:world:0,64,0"), alex.effects.toString());
        toolkit.open(alex.id, PointsScreen::new);
        stand(5, 5, false);
        click("Add point", Click.LEFT);
        WaypointCapture capture = toolkit.captures().capture(alex.id).orElseThrow();
        assertEquals(new Vec3(5.5, 64, 5.5), capture.points().get(1), "added after the chosen point");
        assertEquals(1, capture.current());
        click("Wait", Click.LEFT);
        assertEquals("Wait in seconds", alex.prompt.label());
        MockScreenHost.answer(alex, Optional.of("2"));
        assertTrue(lore("Point 2").contains("waits 2 seconds"), lore("Point 2"));
        assertTrue(lore("Wait").contains("Now: 2 seconds"), lore("Wait"));
        click("Wait", Click.LEFT);
        MockScreenHost.answer(alex, Optional.of("soon"));
        assertTrue(alex.messages.getLast().contains("soon is not a number of seconds"), alex.messages.getLast());
        stand(-3, 7, false);
        click("Point 4", Click.LEFT);
        click("Move here", Click.LEFT);
        assertEquals(new Vec3(-2.5, 64, 7.5), capture.points().get(3));
        click("Point 3", Click.LEFT);
        click("Delete point", Click.LEFT);
        assertEquals(4, capture.points().size(), "a point delete asks once");
        assertEquals("Click again to confirm", name(PointsScreen.DELETE));
        click(PointsScreen.DELETE, Click.LEFT);
        assertEquals(3, capture.points().size());

        click("Save", Click.LEFT);
        drain();
        JsonObject saved = JsonDocuments.object(store.read("routes/walk.json"));
        assertEquals(40, saved.getAsJsonArray("points").get(1).getAsJsonObject().get("waitTicks").getAsInt(), "seconds are saved as ticks");
        assertTrue(toolkit.captures().capture(alex.id).isEmpty());
    }

    @Test void discardAsksOnceAndKeepsSavedRoutes() {
        assertTrue(toolkit.captures().start(alex, "walk", List.of()));
        toolkit.route(alex.id, "add", "");
        toolkit.open(alex.id, PointsScreen::new);
        click("Discard", Click.LEFT);
        assertEquals("Click again to confirm", name(ToolScreen.DISCARD));
        click(ToolScreen.DISCARD, Click.LEFT);
        assertTrue(toolkit.captures().capture(alex.id).isEmpty());
        assertEquals("No route being made", name(22));
    }
}
