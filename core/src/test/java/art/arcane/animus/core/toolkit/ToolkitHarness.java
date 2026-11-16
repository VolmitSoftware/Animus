package art.arcane.animus.core.toolkit;

import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.command.CommandActor;
import art.arcane.animus.core.command.PermissionNodes;
import art.arcane.animus.core.gui.FakeNpcRuntime;
import art.arcane.animus.core.gui.GuiModel.Click;
import art.arcane.animus.core.gui.MockScreenHost;
import art.arcane.animus.core.gui.MockScreenHost.Player;
import art.arcane.animus.core.gui.ScreenFrame;
import art.arcane.animus.core.gui.ScreenHost;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.inventory.MockInventory.Stack;
import art.arcane.animus.core.npc.NpcDefinition;
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
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;

/**
 * The setup every menu test shares: the test's files, Alex (an admin with the wand at 0 64 0) and the menu over a fake
 * runtime spawning the NPCs in {@link #active}, hooked to {@link #commands} as the platform's command tree hooks it.
 * Call {@link #start} from the test's own {@code @BeforeEach}.
 */
abstract class ToolkitHarness {
    static final UUID GUARD = UUID.fromString("12345678-1234-1234-1234-123456789abc"), BAKER = UUID.fromString("22345678-1234-1234-1234-123456789abc");
    @TempDir Path root;
    DocumentStore store;
    NpcRepository npcs;
    final MockScreenHost host = new MockScreenHost();
    final Player alex = new Player("Alex");
    final ScreenManager<Player, Stack> manager = new ScreenManager<>(host, (UUID npc) -> true, (String warning) -> { throw new AssertionError(warning); });
    final List<Runnable> queue = new ArrayList<>();
    /** Spawned NPCs: uuid to x, standing on the z = 0 line of "world" at y 64. */
    final Map<UUID, Double> active = new HashMap<>();
    /** What the runtime and the lent commands were asked to do, such as {@code follow guard market} or {@code inspect guard}. */
    final List<String> calls = new ArrayList<>();
    /** NPCs the runtime made glow for Alex. */
    final List<String> glows = new ArrayList<>();
    long now = 1_000_000_000L;
    /** What the toolkit signs skin addresses with; tests that sign set their own. */
    art.arcane.animus.core.skin.SkinSigner signer = art.arcane.animus.core.skin.SkinSigner.off("Skin signing is off in this test");
    /** The [owners] table the menu reads its limits from. */
    art.arcane.animus.core.config.OwnerSettings owners = art.arcane.animus.core.config.OwnerSettings.DEFAULT;
    String primary;
    boolean recording;
    Toolkit<Player, Stack> toolkit;
    FakeNpcRuntime<Player> living;
    /** The running behavior runs the command layer reports; none unless a test sets them. */
    Toolkit.Runs runs = (String npc) -> CompletableFuture.completedFuture(List.of());
    /** How the shop screens write prices: an economy that calls its money Coins, unless a test takes it away. */
    art.arcane.animus.core.inventory.Economy economy = art.arcane.animus.core.shop.PricesTest.coins("Coins");
    final Toolkit.Commands commands = new Toolkit.Commands((CommandActor actor, String id) -> calls.add("inspect " + id), (CommandActor actor) -> calls.add("pair"),
            (CommandActor actor, String id) -> calls.add("revive " + id), (UUID player) -> recording, (String npc) -> runs.running(npc),
            () -> new art.arcane.animus.core.shop.Prices(economy, art.arcane.animus.core.config.ShopSettings.DEFAULT), () -> this.shops);
    /** The container shops the menu sees; reloading reads them from the store's files. */
    art.arcane.animus.core.shop.PlayerShops shops;
    /** Lines the shops sent to players, as {@code uuid: text}. */
    final List<String> told = new ArrayList<>();

    /** Commits {@code files}, reads the NPCs and installs the menu; {@code queued} holds file work until {@link #drain}. */
    void start(List<DocumentStore.Change> files, boolean queued) throws IOException {
        store = new DocumentStore(root);
        if (!files.isEmpty()) store.commit(files);
        npcs = new NpcRepository(store);
        assertTrue(npcs.reload().isEmpty(), npcs.diagnostics().toString());
        alex.permissions.add(PermissionNodes.ADMIN);
        stand(0, 0, false);
        alex.wand = true;
        living = new FakeNpcRuntime<>((UUID id) -> id.equals(alex.id) ? Optional.of(alex) : Optional.empty()) {
            @Override public Optional<ActiveNpc> active(UUID npc) {
                Double x = ToolkitHarness.this.active.get(npc);
                if (x == null) return Optional.empty();
                String id = npcs.stored().stream().filter((NpcDefinition definition) -> definition.uuid().equals(npc)).findFirst().map(NpcDefinition::id).orElse(npc.toString());
                return Optional.of(new ActiveNpc(id, id, at(npc, x)));
            }
            @Override public Optional<String> selection(UUID viewer) { return Optional.ofNullable(primary); }
            @Override public void select(UUID viewer, String id) { primary = id; }
            @Override public boolean highlight(UUID viewer, String id, int ticks) { glows.add(id); return true; }
            @Override public void followRoute(String id, String route) { calls.add("follow " + id + " " + route); }
            @Override public boolean stopNavigation(String id) { calls.add("stop " + id); return true; }
        };
        toolkit = Toolkit.install(new Toolkit.Parts<>(() -> npcs, () -> store, manager, living, queued ? queue::add : Runnable::run,
                (Player player) -> Optional.empty(), Optional.of((String id, art.arcane.animus.core.virtual.WorldPosition destination) -> {
                    calls.add("relocate " + id + " " + destination.world() + " " + destination.point().x() + " " + destination.point().y() + " " + destination.point().z());
                    return true;
                }), () -> now, () -> signer, () -> owners));
        shops = new art.arcane.animus.core.shop.PlayerShops(() -> store, queued ? queue::add : Runnable::run, () -> art.arcane.animus.core.config.ShopSettings.DEFAULT,
                this::indexShops, (UUID player, String text) -> told.add(player + ": " + text), (String line) -> { throw new AssertionError(line); });
        indexShops();
        toolkit.hook(commands);
        toolkit.joined(alex.id);
        drain();
    }

    /** Reads the shop files into {@link #shops}, as a catalog reload does. */
    void indexShops() {
        try { shops.index(art.arcane.animus.core.shop.ShopCatalog.load(root, (String id) -> true)); }
        catch (IOException unreadable) { throw new java.io.UncheckedIOException(unreadable); }
    }

    @AfterEach void stop() throws IOException {
        if (toolkit != null) toolkit.close();
        if (store != null) store.close();
    }

    /** An NPC file's text: a spawned player body in folder {@code town}, named as its id, at 0 64 0. */
    static String npc(String id, UUID uuid, String extra) {
        return "{\"schemaVersion\":2,\"revision\":1,\"uuid\":\"" + uuid + "\",\"id\":\"" + id + "\",\"group\":\"town\",\"name\":\"" + id + "\",\"type\":\"minecraft:player\",\"spawned\":true,"
                + "\"position\":{\"world\":\"world\",\"x\":0,\"y\":64,\"z\":0,\"yaw\":0,\"pitch\":0}" + extra + "}";
    }

    /** {@code npcs/town/<id>.json} holding {@link #npc}. */
    /** Makes Alex own the NPC, as a player who made it would; without animus.admin Alex acts only on NPCs Alex owns. */
    void own(String... ids) throws IOException {
        for (String id : ids) {
            NpcRepository.Draft draft = npcs.draft(id);
            com.google.gson.JsonObject document = draft.document();
            document.addProperty("owner", alex.id.toString());
            npcs.apply(new NpcRepository.Draft(draft.path(), draft.expectedHash(), draft.expectedRevision(), document));
        }
    }

    static DocumentStore.Change npcFile(String id, UUID uuid, String extra) { return new DocumentStore.Change("npcs/town/" + id + ".json", "missing", npc(id, uuid, extra)); }

    static DocumentStore.Change file(String path, String text) { return new DocumentStore.Change(path, "missing", text); }

    static VirtualNpcState.Snapshot at(UUID uuid, double x) {
        Vec3 point = new Vec3(x, 64, 0);
        return new VirtualNpcState.Snapshot(uuid, new WorldPosition("world", point), new Hitbox(point.add(new Vec3(-0.3, 0, -0.3)), point.add(new Vec3(0.3, 1.8, 0.3))), 20, true, 1);
    }

    /** Puts Alex at {@code x 64 z} in "world", looking south. */
    void stand(double x, double z, boolean sneaking) { alex.pose = new ScreenHost.Pose(Set.of("world"), x, 64, z, 65.62, 0, 0, sneaking); }

    void drain() { while (!queue.isEmpty()) queue.removeFirst().run(); }

    /** The ids of the changes Alex's chat reported, oldest first; Simple mode shows each one only as [Undo]. */
    List<String> changes() { return alex.lines.stream().flatMap((art.arcane.animus.core.text.ChatLine line) -> line.transactions().stream()).toList(); }

    void click(String name, Click click) { click(MockScreenHost.slot(alex, name), click); }

    void click(int slot, Click click) { manager.click(alex, alex.window, slot, click); }

    String title() { return RichText.plain(alex.frame.title()); }

    String name(int slot) { return Optional.ofNullable(alex.frame.items().get(slot)).flatMap(ScreenFrame.Item::name).map(RichText::plain).orElse(""); }

    String name(String part) { return name(MockScreenHost.slot(alex, part)); }

    String lore(int slot) { return String.join("\n", alex.frame.items().get(slot).lore().stream().map(RichText::plain).toList()); }

    String lore(String name) { return lore(MockScreenHost.slot(alex, name)); }

    /** Whether an item's name contains {@code name}. */
    boolean shows(String name) { return alex.frame.items().values().stream().anyMatch((ScreenFrame.Item item) -> item.name().map(RichText::plain).orElse("").contains(name)); }

    /** Every item that is not filler, by slot: its plain name. */
    Map<Integer, String> buttons() {
        Map<Integer, String> shown = new TreeMap<>();
        for (Map.Entry<Integer, ScreenFrame.Item> item : alex.frame.items().entrySet())
            if (!item.getValue().item().id().equals(ToolScreen.FILLER)) shown.put(item.getKey(), item.getValue().name().map(RichText::plain).orElse(""));
        return shown;
    }
}
