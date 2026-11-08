package art.arcane.animus.core.toolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import art.arcane.animus.core.recording.RecordingDocument;
import art.arcane.animus.core.recording.RecordingFrame;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocuments;
import art.arcane.animus.core.text.RichText;
import art.arcane.animus.core.virtual.Hitbox;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.VirtualNpcState;
import art.arcane.animus.core.virtual.WorldPosition;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The route, points and recording screens against a real document store. */
class ToolkitRoutesTest {
    static final UUID GUARD = UUID.fromString("12345678-1234-1234-1234-123456789abc"), BAKER = UUID.fromString("22345678-1234-1234-1234-123456789abc");
    static final String MARKET = "{\"schemaVersion\":1,\"loop\":true,\"points\":[{\"x\":0.5,\"y\":64,\"z\":0.5},{\"x\":8.5,\"y\":64,\"z\":0.5}]}";
    @TempDir Path root;
    DocumentStore store;
    NpcRepository npcs;
    final MockScreenHost host = new MockScreenHost();
    final Player alex = new Player("Alex");
    final ScreenManager<Player, Stack> manager = new ScreenManager<>(host, (UUID npc) -> true, (String warning) -> { throw new AssertionError(warning); });
    final List<Runnable> queue = new ArrayList<>();
    final List<String> calls = new ArrayList<>();
    final Toolkit.Commands commands = new Toolkit.Commands((CommandActor actor, String id) -> {}, (CommandActor actor) -> {}, (CommandActor actor, String id) -> {});
    Toolkit<Player, Stack> toolkit;

    static String npc(String id, UUID uuid, String modules) {
        return "{\"schemaVersion\":1,\"revision\":1,\"uuid\":\"" + uuid + "\",\"id\":\"" + id + "\",\"group\":\"town\",\"name\":\"N\",\"type\":\"minecraft:player\",\"spawned\":true,"
                + "\"position\":{\"world\":\"world\",\"x\":0,\"y\":64,\"z\":0,\"yaw\":0,\"pitch\":0}" + modules + "}";
    }

    static String recording(String id) {
        return new RecordingDocument(1, id, UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7"), "world", new RecordingDocument.Origin(0, 64, 0, 0, 0), 2, 40,
                List.of(new RecordingFrame(0, 0, 0, 0, 0, 0, RecordingFrame.ON_GROUND), new RecordingFrame(40, 4, 0, 0, 0, 0, RecordingFrame.ON_GROUND)), List.of()).serialize();
    }

    @BeforeEach void setUp() throws IOException {
        store = new DocumentStore(root);
        store.commit(List.of(
                new DocumentStore.Change("npcs/town/guard.json", "missing", npc("guard", GUARD, ",\"modules\":{\"animus:navigation\":{\"enabled\":true,\"route\":\"market\"}}")),
                new DocumentStore.Change("npcs/town/baker.json", "missing", npc("baker", BAKER, "")),
                new DocumentStore.Change("routes/market.json", "missing", MARKET),
                new DocumentStore.Change("recordings/wave.json", "missing", recording("wave"))));
        npcs = new NpcRepository(store);
        assertTrue(npcs.reload().isEmpty());
        alex.permissions.add(Toolkit.ADMIN);
        stand(0, 0);
        alex.wand = true;
        FakeNpcRuntime<Player> living = new FakeNpcRuntime<>((UUID id) -> id.equals(alex.id) ? Optional.of(alex) : Optional.empty()) {
            @Override public Optional<ActiveNpc> active(UUID npc) {
                String id = npc.equals(GUARD) ? "guard" : npc.equals(BAKER) ? "baker" : null;
                if (id == null) return Optional.empty();
                Vec3 at = new Vec3(3, 64, 0);
                return Optional.of(new ActiveNpc(id, id, new VirtualNpcState.Snapshot(npc, new WorldPosition("world", at), new Hitbox(at, at.add(new Vec3(0.6, 1.8, 0.6))), 20, true, 1)));
            }
            @Override public void followRoute(String id, String route) { calls.add("follow " + id + " " + route); }
        };
        toolkit = Toolkit.install(new Toolkit.Parts<>(() -> npcs, () -> store, manager, living, queue::add, (Player player) -> Optional.empty(), Optional.empty(), () -> 1L));
    }

    @AfterEach void tearDown() throws IOException { toolkit.close(); store.close(); }

    void stand(double x, double z) { alex.pose = new ScreenHost.Pose(Set.of("world"), x, 64, z, 65.62, 0, 0, false); }

    void drain() { while (!queue.isEmpty()) queue.removeFirst().run(); }

    void click(String name, Click click) { manager.click(alex, alex.window, MockScreenHost.slot(alex, name), click); }

    String lore(String name) {
        ScreenFrame.Item item = alex.frame.items().get(MockScreenHost.slot(alex, name));
        return String.join("\n", item.lore().stream().map(RichText::plain).toList());
    }

    boolean shows(String name) { return alex.frame.items().values().stream().anyMatch((ScreenFrame.Item item) -> item.name().map(RichText::plain).orElse("").equals(name)); }

    JsonObject navigation(String id) { return npcs.find(id).orElseThrow().json().getAsJsonObject("modules").getAsJsonObject("animus:navigation"); }

    String tx() {
        String line = alex.messages.getLast();
        return line.substring(line.indexOf("; tx ") + 5).split("[ ;]")[0];
    }

    @Test void thePointsScreenTeleportsSelectsInsertsMovesWaitsAndDeletes() throws IOException {
        toolkit.point(alex);
        stand(10, 0);
        toolkit.point(alex);
        stand(20, 0);
        toolkit.point(alex);
        stand(9, 0);
        toolkit.open(alex.id, commands, PointsScreen::new);
        assertTrue(shows("Point 2 (here)"), "the nearest point is marked");
        assertTrue(lore("Point 3").contains("Current"), lore("Point 3"));
        assertTrue(lore("Point 1").contains("Wait route default"), lore("Point 1"));
        click("Point 1", Click.LEFT);
        assertTrue(alex.effects.contains("teleport:world:0,64,0"), alex.effects.toString());
        click("Point 1", Click.RIGHT);
        stand(5, 5);
        click("Insert point", Click.LEFT);
        WaypointCapture capture = toolkit.capture(alex.id).orElseThrow();
        assertEquals(new Vec3(5.5, 64, 5.5), capture.points().get(1), "inserted after the current point");
        assertEquals(1, capture.current());
        stand(-3, 7);
        click("Point 4", Click.SHIFT_LEFT);
        assertEquals(new Vec3(-2.5, 64, 7.5), capture.points().get(3));
        click("Wait at point 2", Click.LEFT);
        MockScreenHost.answer(alex, Optional.of("40"));
        assertTrue(lore("Point 2").contains("Wait 40 ticks"), lore("Point 2"));
        click("Point 3", Click.SHIFT_RIGHT);
        assertEquals(3, capture.points().size(), "a short route deletes at once");
        click("Save route", Click.LEFT);
        drain();
        JsonObject saved = JsonDocuments.object(store.read("routes/wp-alex.json"));
        assertEquals(40, saved.getAsJsonArray("points").get(1).getAsJsonObject().get("waitTicks").getAsInt());
        assertTrue(toolkit.capture(alex.id).isEmpty());
    }

    @Test void deletingFromALongRouteAsksTwice() {
        for (int i = 0; i < ToolScreen.BULK + 1; i++) { stand(i, 0); toolkit.point(alex); }
        toolkit.open(alex.id, commands, PointsScreen::new);
        click("Point 21", Click.SHIFT_RIGHT);
        assertEquals(ToolScreen.BULK + 1, toolkit.capture(alex.id).orElseThrow().points().size());
        assertTrue(lore("Point 21").contains("Sneak-right again to delete"), lore("Point 21"));
        click("Point 21", Click.SHIFT_RIGHT);
        assertEquals(ToolScreen.BULK, toolkit.capture(alex.id).orElseThrow().points().size());
    }

    @Test void theRouteListShowsPointsAndUsersAndSneakOpensItsDetails() {
        toolkit.open(alex.id, commands, RouteScreen::new);
        drain();
        assertTrue(lore("market").contains("2 points"), lore("market"));
        assertTrue(lore("market").contains("Used by 1 NPC"), lore("market"));
        click("market", Click.SHIFT_LEFT);
        drain();
        assertEquals("Route market", RichText.plain(alex.frame.title()));
        assertTrue(lore("market").contains("guard"), lore("market"));
    }

    @Test void renameRepointsEveryUserInOneTransactionAndRollsBackTogether() throws IOException {
        detail();
        click("Rename", Click.LEFT);
        MockScreenHost.answer(alex, Optional.of("bazaar"));
        drain();
        assertTrue(alex.messages.getLast().contains("Renamed route market to bazaar, 1 NPC repointed; tx "), alex.messages.getLast());
        assertNull(store.read("routes/market.json"));
        assertNotNull(store.read("routes/bazaar.json"));
        assertEquals("bazaar", navigation("guard").get("route").getAsString());
        assertEquals("Route bazaar", RichText.plain(alex.frame.title()));
        store.rollback(tx());
        assertTrue(npcs.reloadAfterRollback().isEmpty());
        assertNotNull(store.read("routes/market.json"));
        assertNull(store.read("routes/bazaar.json"));
        assertEquals("market", navigation("guard").get("route").getAsString());
    }

    @Test void deleteIsRefusedWhileUsedUnlessItAlsoUnassigns() throws IOException {
        detail();
        click("Delete", Click.LEFT);
        click("Click again to delete", Click.LEFT);
        drain();
        assertTrue(alex.messages.getLast().contains("Route market is used by 1 NPC (guard)"), alex.messages.getLast());
        assertNotNull(store.read("routes/market.json"));
        click("Unassign and delete", Click.LEFT);
        click("Click again to unassign and delete", Click.LEFT);
        drain();
        assertTrue(alex.messages.getLast().contains("Deleted route market, unassigned from 1 NPC; tx "), alex.messages.getLast());
        assertNull(store.read("routes/market.json"));
        assertFalse(npcs.find("guard").orElseThrow().json().has("modules"));
        assertTrue(shows("routes/market.json is gone"));
    }

    @Test void duplicateAndRouteSettingsWriteTheFile() throws IOException {
        detail();
        click("Duplicate", Click.LEFT);
        MockScreenHost.answer(alex, Optional.of("market-two"));
        drain();
        assertEquals(JsonDocuments.object(MARKET), JsonDocuments.object(store.read("routes/market-two.json")));
        click("Speed 2.0 blocks/s", Click.LEFT);
        drain();
        assertEquals(2.5, JsonDocuments.object(store.read("routes/market.json")).get("speed").getAsDouble());
        assertTrue(alex.messages.getLast().contains("Route market speed saved; tx "), alex.messages.getLast());
        click("Order sequence", Click.LEFT);
        drain();
        JsonObject route = JsonDocuments.object(store.read("routes/market.json"));
        assertEquals("pingpong", route.get("order").getAsString());
        assertFalse(route.has("loop"), "loop only goes with sequence");
        click("Loop off", Click.LEFT);
        assertTrue(alex.messages.getLast().contains("Loop needs order sequence"), alex.messages.getLast());
        click("Mode walk", Click.LEFT);
        drain();
        assertEquals("swim", JsonDocuments.object(store.read("routes/market.json")).get("mode").getAsString());
    }

    @Test void editPointsLoadsTheFileAndSavesBackToTheSameIdWithARollback() throws IOException {
        detail();
        stand(4, 4);
        click("Edit points", Click.LEFT);
        drain();
        assertEquals("Route points", RichText.plain(alex.frame.title()));
        WaypointCapture capture = toolkit.capture(alex.id).orElseThrow();
        assertEquals(Optional.of("market"), capture.source());
        click("Point 2", Click.SHIFT_LEFT);
        click("Save route", Click.LEFT);
        drain();
        assertTrue(alex.messages.getLast().contains("Saved routes/market.json with 2 points; tx "), alex.messages.getLast());
        assertEquals(4.5, JsonDocuments.object(store.read("routes/market.json")).getAsJsonArray("points").get(1).getAsJsonObject().get("x").getAsDouble());
        store.rollback(tx());
        assertEquals(JsonDocuments.object(MARKET), JsonDocuments.object(store.read("routes/market.json")));
    }

    @Test void theRecordingScreenIssuesRecordCommandsAndPlaysOnTheSelection() throws IOException {
        toolkit.open(alex.id, commands, RecordingScreen::new);
        drain();
        assertTrue(lore("wave").contains("2 frames, 2.0 s"), lore("wave"));
        click("Start recording", Click.LEFT);
        MockScreenHost.answer(alex, Optional.of("Market-Walk"));
        assertEquals(List.of("animus record start market-walk"), alex.commands);
        toolkit.open(alex.id, commands, RecordingScreen::new);
        drain();
        click("Stop recording", Click.LEFT);
        click("Stop recording", Click.RIGHT);
        click("Ghost", Click.LEFT);
        click("End preview", Click.LEFT);
        assertEquals(List.of("animus record start market-walk", "animus record stop", "animus record cancel", "animus record ghost", "animus record clear"), alex.commands);

        toolkit.interacted(alex, GUARD, false, false);
        click("wave", Click.LEFT);
        drain();
        assertTrue(alex.messages.getLast().contains("Recording wave on 1 NPC; tx "), alex.messages.getLast());
        assertEquals(Map.of("enabled", true, "recording", "wave", "playback", "loop"), Map.of("enabled", navigation("guard").get("enabled").getAsBoolean(),
                "recording", navigation("guard").get("recording").getAsString(), "playback", navigation("guard").get("playback").getAsString()));
        assertFalse(navigation("guard").has("route"), "a recording replaces the route");
        click("wave", Click.RIGHT);
        drain();
        assertEquals("pingpong", navigation("guard").get("playback").getAsString());
        assertTrue(lore("wave").contains("Selection plays it pingpong on 1 NPC"), lore("wave"));
    }

    @Test void recordingDetailPreviewsRenamesWithItsPlayersAndRefusesDeleteWhileUsed() throws IOException {
        store.commit(List.of(new DocumentStore.Change("npcs/town/baker.json", store.hash("npcs/town/baker.json"),
                npc("baker", BAKER, ",\"modules\":{\"animus:navigation\":{\"enabled\":true,\"recording\":\"wave\",\"playback\":\"once\"}}"))));
        npcs.reload();
        toolkit.open(alex.id, commands, RecordingScreen::new);
        drain();
        click("wave", Click.SHIFT_LEFT);
        drain();
        assertEquals("Recording wave", RichText.plain(alex.frame.title()));
        click("Delete", Click.LEFT);
        click("Click again to delete", Click.LEFT);
        drain();
        assertTrue(alex.messages.getLast().contains("Recording wave plays on 1 NPC (baker)"), alex.messages.getLast());
        click("Rename", Click.LEFT);
        MockScreenHost.answer(alex, Optional.of("greet"));
        drain();
        assertTrue(alex.messages.getLast().contains("Renamed recording wave to greet, 1 NPC repointed; tx "), alex.messages.getLast());
        assertEquals("greet", RecordingDocument.parse(store.read("recordings/greet.json"), JsonDocuments.MAX_BYTES).id());
        assertEquals("greet", navigation("baker").get("recording").getAsString());
        assertEquals("once", navigation("baker").get("playback").getAsString());
        click("Preview", Click.LEFT);
        assertEquals(List.of("animus record preview greet"), alex.commands);
    }

    void detail() {
        toolkit.open(alex.id, commands, RouteScreen::new);
        drain();
        click("market", Click.SHIFT_LEFT);
        drain();
    }
}
