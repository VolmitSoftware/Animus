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
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.text.RichText;
import art.arcane.animus.core.virtual.Hitbox;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.VirtualNpcState;
import art.arcane.animus.core.virtual.WorldPosition;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ToolkitScreensTest {
    static final UUID GUARD = UUID.fromString("12345678-1234-1234-1234-123456789abc"), BAKER = UUID.fromString("22345678-1234-1234-1234-123456789abc");
    @TempDir Path root;
    DocumentStore store;
    NpcRepository npcs;
    final MockScreenHost host = new MockScreenHost();
    final Player alex = new Player("Alex");
    final ScreenManager<Player, Stack> manager = new ScreenManager<>(host, (UUID npc) -> true, (String warning) -> { throw new AssertionError(warning); });
    final List<Runnable> queue = new ArrayList<>();
    final Map<UUID, NpcRuntimeView> active = new HashMap<>();
    final List<String> calls = new ArrayList<>();
    long now = 1_000_000_000L;
    String primary;
    Toolkit<Player, Stack> toolkit;
    final Toolkit.Commands commands = new Toolkit.Commands((CommandActor actor, String id) -> calls.add("inspect " + id), (CommandActor actor) -> calls.add("pair"), (CommandActor actor, String id) -> calls.add("revive " + id));

    record NpcRuntimeView(String id, VirtualNpcState.Snapshot state) {}

    static String npc(String id, UUID uuid, String extra) {
        return "{\"schemaVersion\":1,\"revision\":1,\"uuid\":\"" + uuid + "\",\"id\":\"" + id + "\",\"group\":\"town\",\"name\":\"N\",\"type\":\"minecraft:player\",\"spawned\":true,"
                + "\"position\":{\"world\":\"world\",\"x\":0,\"y\":64,\"z\":0,\"yaw\":0,\"pitch\":0}" + extra + "}";
    }

    static VirtualNpcState.Snapshot at(UUID uuid, double x) {
        Vec3 point = new Vec3(x, 64, 0);
        return new VirtualNpcState.Snapshot(uuid, new WorldPosition("world", point), new Hitbox(point.add(new Vec3(-0.3, 0, -0.3)), point.add(new Vec3(0.3, 1.8, 0.3))), 20, true, 1);
    }

    @BeforeEach void setUp() throws IOException {
        store = new DocumentStore(root);
        List<DocumentStore.Change> files = new ArrayList<>(List.of(
                new DocumentStore.Change("npcs/town/guard.json", "missing", npc("guard", GUARD, "")),
                new DocumentStore.Change("npcs/town/baker.json", "missing", npc("baker", BAKER, ""))));
        for (int i = 0; i < 50; i++) files.add(new DocumentStore.Change("groups/g" + (100 + i) + ".json", "missing", "{\"schemaVersion\":1,\"revision\":1,\"id\":\"g" + (100 + i) + "\",\"values\":{\"tags\":[\"t\"]}}"));
        store.commit(files);
        npcs = new NpcRepository(store);
        assertTrue(npcs.reload().isEmpty());
        active.put(GUARD, new NpcRuntimeView("guard", at(GUARD, 3)));
        active.put(BAKER, new NpcRuntimeView("baker", at(BAKER, 6)));
        alex.permissions.add(Toolkit.ADMIN);
        alex.pose = new ScreenHost.Pose(Set.of("world"), 0, 64, 0, 65.62, 0, 0, false);
        alex.wand = true;
        FakeNpcRuntime<Player> living = new FakeNpcRuntime<>((UUID id) -> id.equals(alex.id) ? Optional.of(alex) : Optional.empty()) {
            @Override public Optional<ActiveNpc> active(UUID npc) {
                NpcRuntimeView view = active.get(npc);
                return view == null ? Optional.empty() : Optional.of(new ActiveNpc(view.id(), view.id(), view.state()));
            }
            @Override public Optional<String> selection(UUID viewer) { return Optional.ofNullable(primary); }
            @Override public void select(UUID viewer, String id) { primary = id; }
            @Override public void followRoute(String id, String route) { calls.add("follow " + id + " " + route); }
            @Override public boolean stopNavigation(String id) { calls.add("stop " + id); return true; }
        };
        toolkit = Toolkit.install(new Toolkit.Parts<>(() -> npcs, () -> store, manager, living, queue::add, (Player player) -> Optional.empty(), Optional.empty(), () -> now));
    }

    @AfterEach void tearDown() throws IOException { toolkit.close(); store.close(); }

    void drain() { while (!queue.isEmpty()) queue.removeFirst().run(); }

    void click(String name, Click click) { manager.click(alex, alex.window, MockScreenHost.slot(alex, name), click); }

    String lore(String name) {
        ScreenFrame.Item item = alex.frame.items().get(MockScreenHost.slot(alex, name));
        return String.join("\n", item.lore().stream().map(RichText::plain).toList());
    }

    @Test void theWandSelectsOneNpcAndASneakClickTogglesIt() {
        assertTrue(toolkit.interacted(alex, GUARD, false, false));
        assertEquals(List.of("guard"), toolkit.selected(alex.id));
        assertEquals("guard", primary, "the wand also sets the selection @selected names");
        now += Toolkit.REPEAT_NANOS / 2;
        assertTrue(toolkit.interacted(alex, GUARD, false, false), "the second hand of the same click is used and ignored");
        now += Toolkit.REPEAT_NANOS;
        assertTrue(toolkit.interacted(alex, BAKER, false, true));
        assertEquals(List.of("guard", "baker"), toolkit.selected(alex.id));
        alex.pose = new ScreenHost.Pose(Set.of("world"), 0, 64, 0, 65.62, 0, 0, true);
        now += Toolkit.REPEAT_NANOS;
        assertTrue(toolkit.interacted(alex, GUARD, true, false), "a left click reads sneaking from the pose");
        assertEquals(List.of("baker"), toolkit.selected(alex.id));
        assertTrue(alex.messages.contains("bar:Removed guard, 1 selected"), alex.messages.toString());

        alex.wand = false;
        assertFalse(toolkit.interacted(alex, GUARD, false, false), "without the wand the click goes on to shops and GUIs");
        alex.wand = true;
        alex.permissions.clear();
        assertFalse(toolkit.interacted(alex, GUARD, false, false), "only admins use the wand");
    }

    @Test void thePaletteHintsEveryClickAndOpensEachTool() {
        toolkit.open(alex.id, Toolkit.Tool.PALETTE, commands);
        assertEquals("Animus tools", RichText.plain(alex.frame.title()));
        int buttons = 0;
        for (ScreenFrame.Item item : alex.frame.items().values()) {
            if (item.item().id().equals(ToolScreen.FILLER)) continue;
            String text = String.join("\n", item.lore().stream().map(RichText::plain).toList());
            assertTrue(text.contains("Click: ") || text.contains("Left ") && text.contains("Right") && text.contains("neak"), item.name() + " lacks click hints: " + text);
            List<String> actions = item.lore().stream().map(RichText::plain).filter((String line) -> line.matches("^(Click:|Left|Right|Sneak).*"))
                    .map((String line) -> line.replaceFirst("^(Click:|Left or right|Left or sneak|Right or sneak|Left|Right|Sneak) ", "")).toList();
            assertEquals(actions.size(), Set.copyOf(actions).size(), item.name() + " repeats a click hint: " + text);
            buttons++;
        }
        assertEquals(18, buttons);
        click("Trait groups", Click.LEFT);
        assertEquals("Trait groups", RichText.plain(alex.frame.title()));
        click("Back", Click.LEFT);
        assertEquals("Animus tools", RichText.plain(alex.frame.title()));
        click("Animus wand", Click.LEFT);
        assertTrue(alex.messages.getLast().contains("Wand given"), alex.messages.toString());
    }

    @Test void groupsPageAndAssignOrUnassignTheSelectionInOneTransaction() throws IOException {
        toolkit.interacted(alex, GUARD, false, false);
        now += Toolkit.REPEAT_NANOS;
        toolkit.interacted(alex, BAKER, false, true);
        toolkit.open(alex.id, Toolkit.Tool.PALETTE, commands);
        click("Trait groups", Click.LEFT);
        assertTrue(RichText.plain(alex.frame.items().get(ToolScreen.NEXT).name().orElseThrow()).equals("Next page"));
        assertFalse(RichText.plain(alex.frame.items().get(ToolScreen.PREVIOUS).name().orElseThrow()).contains("Previous"), "the first page has no previous arrow");
        click("Next page", Click.LEFT);
        assertEquals("Page 2 of 2", RichText.plain(alex.frame.items().get(ToolScreen.INFO).name().orElseThrow()));
        assertEquals(5, alex.frame.items().values().stream().filter((ScreenFrame.Item item) -> item.item().id().equals("minecraft:book")).count());
        click("g149", Click.LEFT);
        drain();
        String line = alex.messages.getLast();
        assertTrue(line.contains("Assigned g149 to 2 NPCs; tx "), line);
        String tx = line.substring(line.indexOf("tx ") + 3, line.indexOf("tx ") + 39);
        assertEquals(List.of("npcs/town/baker.json", "npcs/town/guard.json"), store.paths(tx).stream().filter((String path) -> path.startsWith("npcs/")).sorted().toList());
        assertTrue(lore("g149").contains("In the selection 2 of 2"), lore("g149"));
        click("g149", Click.RIGHT);
        drain();
        assertTrue(alex.messages.getLast().contains("Unassigned g149 from 2 NPCs"), alex.messages.getLast());
        assertTrue(npcs.find("guard").orElseThrow().groups().isEmpty());
    }

    @Test void deleteAsksTwiceOnTheSameScreen() {
        toolkit.interacted(alex, GUARD, false, false);
        toolkit.open(alex.id, Toolkit.Tool.MANAGE, commands);
        assertEquals("Manage selection", RichText.plain(alex.frame.title()));
        click("Delete", Click.LEFT);
        drain();
        assertTrue(npcs.find("guard").isPresent(), "the first click only arms");
        assertTrue(lore("Click again to delete").contains("1 NPC affected"), lore("Click again to delete"));
        click("Despawn", Click.LEFT);
        drain();
        assertTrue(lore("Delete").contains("Asks once more"), "another button disarms");
        click("Delete", Click.LEFT);
        click("Click again to delete", Click.LEFT);
        drain();
        assertTrue(npcs.find("guard").isEmpty());
        assertTrue(alex.messages.getLast().contains("Deleted guard; tx "), alex.messages.getLast());
        assertTrue(toolkit.selected(alex.id).isEmpty(), "a deleted NPC leaves the selection");
    }

    @Test void manageRunsSpawnCloneInspectAndTheEditorLink() {
        toolkit.interacted(alex, GUARD, false, false);
        toolkit.open(alex.id, Toolkit.Tool.MANAGE, commands);
        click("Despawn", Click.LEFT);
        drain();
        assertTrue(alex.messages.getLast().contains("Despawned 1 NPC; tx "), alex.messages.getLast());
        click("Spawn", Click.LEFT);
        drain();
        assertTrue(alex.messages.getLast().contains("Spawning 1 NPC; tx "), alex.messages.getLast());
        click("Clone", Click.LEFT);
        drain();
        assertTrue(npcs.find("guard-copy").isPresent());
        click("Inspect", Click.LEFT);
        assertEquals(List.of("inspect guard"), calls);
        assertNull(alex.window, "inspect closes the window so the chat shows");
        toolkit.open(alex.id, Toolkit.Tool.MANAGE, commands);
        click("Open in editor", Click.LEFT);
        assertEquals(List.of("inspect guard", "pair"), calls);
        toolkit.open(alex.id, Toolkit.Tool.MANAGE, commands);
        click("Revive", Click.LEFT);
        assertEquals(List.of("inspect guard", "pair", "revive guard"), calls);
    }

    @Test void newNpcAsksForAnIdThenSavesSelectsAndSpawnsItInFrontOfThePlayer() {
        alex.pose = new ScreenHost.Pose(Set.of("world", "minecraft:overworld"), 12.3, 64, -7.8, 65.62, 90, 0, false);
        toolkit.open(alex.id, Toolkit.Tool.PALETTE, commands);
        click("New NPC", Click.LEFT);
        assertEquals("New NPC id", alex.prompt.label());
        MockScreenHost.answer(alex, Optional.of("miller"));
        drain();
        assertTrue(npcs.find("miller").isPresent(), "the NPC is saved");
        assertEquals(List.of("miller"), toolkit.selected(alex.id));
        com.google.gson.JsonObject position = npcs.find("miller").orElseThrow().json().getAsJsonObject("position");
        assertEquals("world", position.get("world").getAsString(), "the world NPC positions already use");
        assertEquals(10.3, position.get("x").getAsDouble(), "two blocks ahead, where the player looks");
        assertEquals(-7.8, position.get("z").getAsDouble());
        assertEquals(-90f, position.get("yaw").getAsFloat(), "facing the player");
        assertTrue(npcs.find("miller").orElseThrow().spawned());
        drain();
        List<String> created = alex.messages.stream().filter((String line) -> line.contains("; tx ") || line.contains("[Undo]")).toList();
        assertEquals(1, created.size(), "one transaction: " + alex.messages);
        assertTrue(created.getFirst().contains("Created npcs/miller.json and spawned it; tx "), created.getFirst());
        assertFalse(alex.messages.toString().contains("dormant"), alex.messages.toString());
        click("New NPC", Click.LEFT);
        MockScreenHost.answer(alex, Optional.of("miller"));
        drain();
        assertTrue(alex.messages.getLast().contains("already exists"), alex.messages.getLast());
        click("New NPC", Click.RIGHT);
        assertEquals("Body type", alex.prompt.label());
        MockScreenHost.answer(alex, Optional.of("minecraft:villager"));
        MockScreenHost.answer(alex, Optional.of("trader"));
        drain();
        assertEquals("minecraft:villager", npcs.find("trader").orElseThrow().json().get("type").getAsString());
    }

    @Test void theOutlineDrawsWhileSomethingIsSelectedAndStopsWhenCleared() {
        toolkit.interacted(alex, GUARD, false, false);
        assertEquals(Set.of(alex.id), toolkit.watching());
        for (int tick = 0; tick < ToolkitOutline.PERIOD; tick++) toolkit.tick(alex);
        assertEquals(ToolkitOutline.POINTS, alex.effects.stream().filter((String effect) -> effect.startsWith("particle:minecraft:end_rod")).count(), "one outline per period");
        toolkit.open(alex.id, Toolkit.Tool.PALETTE, commands);
        click("1 selected", Click.SHIFT_LEFT);
        assertTrue(toolkit.watching().isEmpty());
        toolkit.interacted(alex, BAKER, false, false);
        toolkit.quit(alex.id);
        assertTrue(toolkit.selected(alex.id).isEmpty(), "quitting clears the selection");
        assertTrue(toolkit.watching().isEmpty());
    }

    @Test void routePointsComeFromTheWandSneakAndSaveAsAPlayerRoute() throws IOException {
        toolkit.open(alex.id, Toolkit.Tool.PALETTE, commands);
        click("Routes", Click.RIGHT);
        assertTrue(toolkit.capture(alex.id).isPresent());
        Consumer<Double> step = (Double x) -> {
            alex.pose = new ScreenHost.Pose(Set.of("world"), x, 64, 0, 65.62, 0, 0, false);
            toolkit.tick(alex);
            alex.pose = new ScreenHost.Pose(Set.of("world"), x, 64, 0, 65.62, 0, 0, true);
            toolkit.tick(alex);
            toolkit.tick(alex);
        };
        step.accept(5.0);
        step.accept(9.0);
        assertEquals(3, toolkit.capture(alex.id).orElseThrow().points().size(), "one point per sneak press");
        click("Routes", Click.SHIFT_LEFT);
        assertEquals(2, toolkit.capture(alex.id).orElseThrow().points().size());
        click("Routes", Click.LEFT);
        click("Save route", Click.LEFT);
        drain();
        assertTrue(alex.messages.getLast().contains("Saved wp-alex, 2 points; sneak-click it in Routes to rename routes/wp-alex.json"), alex.messages.getLast());
        assertNotNull(store.read("routes/wp-alex.json"));
        assertTrue(toolkit.capture(alex.id).isEmpty());
        drain();
        assertTrue(alex.frame.items().values().stream().anyMatch((ScreenFrame.Item item) -> item.name().map(RichText::plain).orElse("").equals("wp-alex")));
        toolkit.interacted(alex, GUARD, false, false);
        click("wp-alex", Click.LEFT);
        drain();
        assertTrue(alex.messages.getLast().contains("Route wp-alex on guard; tx "), alex.messages.getLast());
        click("wp-alex", Click.RIGHT);
        assertTrue(calls.contains("follow guard wp-alex"));
        click("Stop moving", Click.LEFT);
        assertTrue(calls.contains("stop guard"));
        assertTrue(alex.messages.getLast().endsWith("Stopped guard"), alex.messages.getLast());
    }

    @Test void captureClosesTheWindowSoThePlayerWalksAndCountsOnePoint() {
        toolkit.open(alex.id, Toolkit.Tool.PALETTE, commands);
        click("Routes", Click.LEFT);
        click("Capture a route", Click.LEFT);
        assertNull(alex.window, "the window closes so the player can walk the route");
        assertEquals(1, toolkit.capture(alex.id).orElseThrow().points().size());
        toolkit.open(alex.id, Toolkit.Tool.PALETTE, commands);
        click("Routes", Click.LEFT);
        assertEquals("Capturing, 1 point", RichText.plain(alex.frame.items().get(MockScreenHost.slot(alex, "Capturing")).name().orElseThrow()));
    }

    @Test void findListsNearestFirstAndTeleportsOrSelects() {
        toolkit.open(alex.id, Toolkit.Tool.PALETTE, commands);
        click("Find", Click.LEFT);
        assertTrue(MockScreenHost.slot(alex, "guard") < MockScreenHost.slot(alex, "baker"));
        assertTrue(lore("baker").contains("6.0 blocks away"), lore("baker"));
        click("baker", Click.RIGHT);
        assertEquals(List.of("baker"), toolkit.selected(alex.id));
        click("guard", Click.LEFT);
        assertTrue(alex.effects.contains("teleport:world:1,64,0"), "beside the guard at 3 64 0, on the player's side: " + alex.effects);
    }
}
