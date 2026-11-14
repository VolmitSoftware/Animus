package art.arcane.animus.core.toolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.command.CommandActor;
import art.arcane.animus.core.command.PermissionNodes;
import art.arcane.animus.core.gui.FakeNpcRuntime;
import art.arcane.animus.core.gui.MockScreenHost;
import art.arcane.animus.core.gui.MockScreenHost.Player;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.inventory.MockInventory.Stack;
import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Each player's menu mode: Simple by default, saved per player, read on the file lane before the first window draws. */
class ToolkitPrefsTest {
    static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000a1e7");
    @TempDir Path root;
    DocumentStore store;
    final List<Runnable> queue = new ArrayList<>();

    @BeforeEach void setUp() throws IOException { store = new DocumentStore(root); }
    @AfterEach void tearDown() throws IOException { store.close(); }

    void drain() { while (!queue.isEmpty()) queue.removeFirst().run(); }
    ToolkitPrefs now() { return new ToolkitPrefs(() -> store, Runnable::run); }
    Path file(UUID player) throws IOException { return store.resolve(ToolkitPrefs.path(player)); }
    void save(UUID player, String content) throws IOException { DocumentStore.atomic(file(player), content); }

    @Test void theModeWordsParseLoosely() {
        assertEquals("simple", ToolkitMode.SIMPLE.word());
        assertEquals("advanced", ToolkitMode.ADVANCED.word());
        assertEquals("Simple", ToolkitMode.SIMPLE.label());
        assertEquals("Advanced", ToolkitMode.ADVANCED.label());
        assertEquals(Optional.of(ToolkitMode.ADVANCED), ToolkitMode.parse(" Advanced "));
        assertEquals(Optional.of(ToolkitMode.SIMPLE), ToolkitMode.parse("SIMPLE"));
        assertEquals(Optional.empty(), ToolkitMode.parse("expert"));
        assertEquals(Optional.empty(), ToolkitMode.parse(""));
        assertEquals(Optional.empty(), ToolkitMode.parse(null));
    }

    @Test void aPlayerWithNoFileIsSimpleAndNothingIsWritten() throws IOException {
        ToolkitPrefs prefs = now();
        assertEquals(ToolkitMode.SIMPLE, prefs.mode(ALEX), "unknown before any read");
        assertFalse(prefs.known(ALEX));
        prefs.load(ALEX);
        assertTrue(prefs.known(ALEX));
        assertEquals(ToolkitMode.SIMPLE, prefs.mode(ALEX));
        assertFalse(Files.exists(file(ALEX)), "reading never writes");
    }

    @Test void aChosenModeIsSavedAndAFreshInstanceReadsItBack() throws IOException {
        ToolkitPrefs prefs = now();
        prefs.mode(ALEX, ToolkitMode.ADVANCED);
        assertEquals(ToolkitMode.ADVANCED, prefs.mode(ALEX));
        JsonObject saved = JsonDocuments.object(JsonDocuments.read(file(ALEX)));
        assertEquals(1, saved.get("schemaVersion").getAsInt());
        assertEquals("advanced", saved.get("mode").getAsString());
        assertEquals(2, saved.size(), "only the version and the mode: " + saved);

        ToolkitPrefs restarted = now();
        assertEquals(ToolkitMode.SIMPLE, restarted.mode(ALEX), "nothing is known before the read");
        restarted.load(ALEX);
        assertEquals(ToolkitMode.ADVANCED, restarted.mode(ALEX));
        restarted.mode(ALEX, ToolkitMode.SIMPLE);
        assertEquals("simple", JsonDocuments.object(JsonDocuments.read(file(ALEX))).get("mode").getAsString());
    }

    @Test void aCorruptFileReadsAsSimpleAndStaysUntilTheModeChanges() throws IOException {
        save(ALEX, "{\"schemaVersion\":1,\"mode\":");
        ToolkitPrefs prefs = now();
        prefs.load(ALEX);
        assertEquals(ToolkitMode.SIMPLE, prefs.mode(ALEX));
        prefs.mode(ALEX, ToolkitMode.SIMPLE);
        assertEquals("{\"schemaVersion\":1,\"mode\":", Files.readString(file(ALEX)), "picking the mode it already has is no change");
        prefs.mode(ALEX, ToolkitMode.ADVANCED);
        assertEquals("advanced", JsonDocuments.object(JsonDocuments.read(file(ALEX))).get("mode").getAsString(), "a real change replaces it");
    }

    @Test void anUnknownVersionOrWordReadsAsSimpleAndIsLeftAlone() throws IOException {
        String newer = "{\"schemaVersion\":2,\"mode\":\"advanced\"}";
        save(ALEX, newer);
        ToolkitPrefs prefs = now();
        prefs.load(ALEX);
        assertEquals(ToolkitMode.SIMPLE, prefs.mode(ALEX));
        assertEquals(newer, Files.readString(file(ALEX)));

        UUID sam = UUID.randomUUID();
        save(sam, "{\"schemaVersion\":1,\"mode\":\"expert\"}");
        prefs.load(sam);
        assertEquals(ToolkitMode.SIMPLE, prefs.mode(sam));
        UUID kim = UUID.randomUUID();
        save(kim, "[]");
        prefs.load(kim);
        assertEquals(ToolkitMode.SIMPLE, prefs.mode(kim));
    }

    @Test void forgetDropsTheCachedModeUntilTheNextRead() {
        ToolkitPrefs prefs = now();
        prefs.mode(ALEX, ToolkitMode.ADVANCED);
        prefs.forget(ALEX);
        assertFalse(prefs.known(ALEX));
        assertEquals(ToolkitMode.SIMPLE, prefs.mode(ALEX));
        prefs.load(ALEX);
        assertEquals(ToolkitMode.ADVANCED, prefs.mode(ALEX));
    }

    @Test void theReadRunsOnTheFileLaneAndThenRunsAfterIt() throws IOException {
        save(ALEX, "{\"schemaVersion\":1,\"mode\":\"advanced\"}");
        ToolkitPrefs prefs = new ToolkitPrefs(() -> store, queue::add);
        List<ToolkitMode> seen = new ArrayList<>();
        prefs.load(ALEX, () -> seen.add(prefs.mode(ALEX)));
        assertTrue(seen.isEmpty(), "nothing runs before the read");
        assertEquals(ToolkitMode.SIMPLE, prefs.mode(ALEX));
        drain();
        assertEquals(List.of(ToolkitMode.ADVANCED), seen);
        prefs.load(ALEX, () -> seen.add(prefs.mode(ALEX)));
        assertEquals(List.of(ToolkitMode.ADVANCED, ToolkitMode.ADVANCED), seen, "a known mode runs at once");
        assertTrue(queue.isEmpty(), "and reads nothing again");
    }

    @Test void aModePickedWhileTheReadWaitsWins() throws IOException {
        save(ALEX, "{\"schemaVersion\":1,\"mode\":\"simple\"}");
        ToolkitPrefs prefs = new ToolkitPrefs(() -> store, queue::add);
        prefs.load(ALEX);
        prefs.mode(ALEX, ToolkitMode.ADVANCED);
        drain();
        assertEquals(ToolkitMode.ADVANCED, prefs.mode(ALEX));
        assertEquals("advanced", JsonDocuments.object(JsonDocuments.read(file(ALEX))).get("mode").getAsString());
    }

    @Test void aPlayerWhoLeftBeforeTheReadIsNotCached() throws IOException {
        save(ALEX, "{\"schemaVersion\":1,\"mode\":\"advanced\"}");
        ToolkitPrefs prefs = new ToolkitPrefs(() -> store, queue::add);
        prefs.load(ALEX);
        prefs.forget(ALEX);
        drain();
        assertFalse(prefs.known(ALEX));
    }

    @Test void aFullLaneNeverThrowsIntoTheCaller() {
        Executor full = (Runnable task) -> { throw new RejectedExecutionException("full"); };
        ToolkitPrefs prefs = new ToolkitPrefs(() -> store, full);
        List<String> ran = new ArrayList<>();
        prefs.load(ALEX, () -> ran.add("then"));
        assertEquals(List.of("then"), ran, "the window still opens, in Simple");
        prefs.mode(ALEX, ToolkitMode.ADVANCED);
        assertEquals(ToolkitMode.ADVANCED, prefs.mode(ALEX), "the pick holds for this session");
        ToolkitPrefs closed = new ToolkitPrefs(() -> { throw new IllegalStateException("Animus is not running"); }, Runnable::run);
        closed.load(ALEX);
        assertEquals(ToolkitMode.SIMPLE, closed.mode(ALEX));
        closed.mode(ALEX, ToolkitMode.ADVANCED);
        assertEquals(ToolkitMode.ADVANCED, closed.mode(ALEX));
    }

    /** The toolkit keeps one set of prefs: read at join or first open, dropped on quit. */
    @Test void theToolkitReadsTheModeBeforeTheFirstWindowDraws() throws IOException {
        MockScreenHost host = new MockScreenHost();
        Player alex = new Player("Alex");
        alex.permissions.add(PermissionNodes.ADMIN);
        ScreenManager<Player, Stack> manager = new ScreenManager<>(host, (UUID npc) -> true, (String warning) -> { throw new AssertionError(warning); });
        NpcRepository npcs = new NpcRepository(store);
        FakeNpcRuntime<Player> living = new FakeNpcRuntime<>((UUID id) -> id.equals(alex.id) ? Optional.of(alex) : Optional.empty());
        Toolkit<Player, Stack> toolkit = Toolkit.install(new Toolkit.Parts<>(() -> npcs, () -> store, manager, living, queue::add, (Player player) -> Optional.empty(), Optional.empty(), () -> 0L));
        try {
            save(alex.id, "{\"schemaVersion\":1,\"mode\":\"advanced\"}");
            List<ToolkitMode> drawn = new ArrayList<>();
            toolkit.hook(new Toolkit.Commands((CommandActor actor, String id) -> {}, (CommandActor actor) -> {}, (CommandActor actor, String id) -> {}, (UUID player) -> false,
                    (String npc) -> CompletableFuture.completedFuture(List.of()),
                    () -> new art.arcane.animus.core.shop.Prices(art.arcane.animus.core.inventory.Economy.none("none"), art.arcane.animus.core.config.ShopSettings.DEFAULT)));
            toolkit.open(alex.id, (Toolkit<Player, Stack> kit, ScreenManager<Player, Stack>.Viewer viewer, Toolkit.Commands lent) -> new ToolScreen<>(kit, viewer, lent) {
                @Override String title() { return "Probe"; }
                @Override int rows() { return 1; }
                @Override void draw() { drawn.add(kit.mode(viewer.id())); }
            });
            assertNull(alex.window, "the window waits for the read");
            drain();
            assertEquals(ToolkitMode.ADVANCED, drawn.getFirst(), "the first draw already knows the saved mode");
            assertTrue(toolkit.advanced(alex.id));

            toolkit.mode(alex.id, ToolkitMode.SIMPLE);
            assertEquals(ToolkitMode.SIMPLE, toolkit.mode(alex.id), "a switch shows at once");
            assertFalse(toolkit.advanced(alex.id));
            drain();
            assertEquals("simple", JsonDocuments.object(JsonDocuments.read(file(alex.id))).get("mode").getAsString());

            toolkit.mode(alex.id, ToolkitMode.ADVANCED);
            drain();
            toolkit.quit(alex.id);
            assertFalse(toolkit.advanced(alex.id), "quit drops the cached mode");
            toolkit.joined(alex.id);
            assertFalse(toolkit.advanced(alex.id), "join reads on the file lane");
            drain();
            assertTrue(toolkit.advanced(alex.id));
        } finally { toolkit.close(); }
    }
}
