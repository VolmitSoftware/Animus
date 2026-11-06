package art.arcane.animus.core.shop;

import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.command.CommandActor;
import art.arcane.animus.core.config.ShopSettings;
import art.arcane.animus.core.gui.GuiDocument;
import art.arcane.animus.core.gui.GuiScreen;
import art.arcane.animus.core.text.RichText;
import art.arcane.animus.core.gui.Screen;
import art.arcane.animus.core.gui.ScreenHost;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.gui.Screens;
import art.arcane.animus.core.inventory.Economy;
import art.arcane.animus.core.migration.ShopImporter;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.runtime.NpcRuntime;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Chest GUIs and shops on one server: the catalog, the open windows, the durable counters and the {@code shop} and
 * {@code gui} subcommands. Player work runs on each player's owner; catalog reads run on the file lane.
 *
 * @param <P> the platform's online player
 * @param <S> the platform's item stack
 */
public final class ShopService<P, S> implements Screens<P, S>, AutoCloseable {
    private final AnimusRuntime runtime;
    private final ScreenHost<P, S> host;
    private final NpcRuntime<P, ?> living;
    private final Executor files;
    private final Function<String, Optional<UUID>> playerNamed;
    private final Consumer<String> log;
    private volatile ShopSettings settings;
    private final Path data;
    private final ScreenManager<P, S> screens;
    private final ShopStateStore store;
    private volatile ShopScreen.Context<S> context;
    private final AutoCloseable settingsListener;
    private volatile ShopCatalog catalog = ShopCatalog.EMPTY;
    private volatile Map<String, NpcDefinition> bindingsSource;
    private volatile Map<UUID, ShopBinding> bindings = Map.of();
    private volatile boolean closed;

    /**
     * @param economy the platform's money provider; {@code economy = "none"} in {@code config.toml} replaces it
     * @param playerNamed finds an online player's UUID by exact name, on any thread
     */
    public ShopService(AnimusRuntime runtime, ScreenHost<P, S> host, NpcRuntime<P, ?> living, Executor files,
                       Function<String, Optional<UUID>> playerNamed, Consumer<String> log) throws IOException {
        this.runtime = Objects.requireNonNull(runtime);
        this.host = Objects.requireNonNull(host);
        this.living = Objects.requireNonNull(living);
        this.files = Objects.requireNonNull(files);
        this.playerNamed = Objects.requireNonNull(playerNamed);
        this.log = Objects.requireNonNull(log);
        settings = runtime.shopSettings();
        data = runtime.documents().resolve(ShopCatalog.SHOP_FOLDER).getParent();
        store = new ShopStateStore(data.resolve("state/shops"), data.resolve("logs/shops"), settings.flushMillis(), log);
        context = context(settings);
        screens = new ScreenManager<>(host, (UUID npc) -> living.active(npc).isPresent(), log);
        settingsListener = runtime.onSettings((art.arcane.animus.core.config.PluginSettings next) -> applySettings(next.shops()));
    }

    /**
     * A hotload of {@code [shops]}: messages, price format, economy and the on/off switch apply to the next window and
     * trade, and {@code flushMillis} to the next counter write.
     */
    private void applySettings(ShopSettings next) {
        if (closed) return;
        store.flushMillis(next.flushMillis());
        settings = next;
        context = context(next);
    }

    private ShopScreen.Context<S> context(ShopSettings active) {
        ShopTransactions<S> transactions = new ShopTransactions<>(host.items(), economy(active, host.economy()), store.ledger(), Instant::now);
        return new ShopScreen.Context<>(active, transactions, Optional.of(store), Instant::now);
    }

    /** Re-renders every open shop and chest GUI window on its viewer's owner, after a hotload changed what they show. */
    public void refreshOpen() {
        for (UUID viewer : screens.viewing())
            living.players().deliver(viewer, (P player) -> screens.viewer(viewer).ifPresent((ScreenManager<P, S>.Viewer open) -> open.refresh()));
    }

    /** The money provider shops and GUI economy actions use, after {@code [shops] economy}. */
    public static Economy economy(ShopSettings settings, Economy provider) {
        if (!settings.enabled()) return Economy.none("shops are disabled in config.toml");
        return settings.economy().equals("none") ? Economy.none("economy = \"none\" in config.toml") : provider;
    }

    public ScreenManager<P, S> screens() { return screens; }
    /** An online player's UUID by exact name, on any thread. */
    public Optional<UUID> playerNamed(String name) { return playerNamed.apply(name); }
    public ShopCatalog catalog() { return catalog; }
    public boolean enabled() { return settings.enabled() && !closed; }
    public String status() {
        ShopCatalog current = catalog;
        Optional<String> money = context.transactions().economy().unavailable();
        return "Shops " + (settings.enabled() ? "enabled" : "disabled") + " | shops=" + current.shops().size() + " | guis=" + current.guis().size()
                + " | problems=" + current.problems().size() + " | open=" + screens.viewing().size()
                + " | economy=" + money.map((String reason) -> "unavailable (" + reason + ")").orElse("ready");
    }

    /** Reads the catalog on the file lane and reports problems to the log and the callback. The result completes once the new catalog is live, or at once when the queue refuses the work. */
    public CompletableFuture<Void> reload(Consumer<ShopCatalog> done) {
        CompletableFuture<Void> published = new CompletableFuture<>();
        try {
            files.execute(() -> {
                try {
                    ShopCatalog next;
                    try {
                        next = ShopCatalog.load(data, runtime.behaviorSettings().actions()::commandAllowed);
                    } catch (IOException | RuntimeException failure) {
                        log.accept("Shop and GUI catalog could not be read: " + failure.getMessage());
                        next = new ShopCatalog(catalog.guis(), catalog.shops(), List.of("catalog: " + failure.getMessage()));
                    }
                    next = next.retaining(catalog, data, runtime.behaviorSettings().actions()::commandAllowed);
                    catalog = next;
                    next.problems().stream().limit(40).forEach((String problem) -> log.accept("Shop or GUI refused: " + problem));
                    done.accept(next);
                } finally { published.complete(null); }
            });
        } catch (RejectedExecutionException full) { log.accept("Shop catalog reload refused: the file queue is full"); published.complete(null); }
        return published;
    }

    /**
     * A validated NPC click on the player's owner. Opens the NPC's shop when its definition binds one. Returns true
     * when the click belonged to a shop.
     */
    public boolean interacted(P player, UUID npc, boolean attack, boolean sneaking) {
        if (!enabled()) return false;
        ShopBinding binding = binding(npc);
        if (binding == null || !binding.opensOn(attack)) return false;
        if (sneaking && host.permitted(player, "animus.admin")) return false;
        openShop(player, binding.shop(), Optional.of(npc));
        return true;
    }

    /** On the player's owner. */
    public boolean openShop(P player, String id, Optional<UUID> npc) {
        if (!enabled()) return false;
        return screens.open(player, npc, (ScreenManager<P, S>.Viewer viewer) -> shop(viewer, id));
    }

    /** On the player's owner. */
    public boolean openGui(P player, String id, Map<String, String> args, Optional<UUID> npc) {
        if (!enabled()) return false;
        return screens.open(player, npc, (ScreenManager<P, S>.Viewer viewer) -> gui(viewer, id, args));
    }

    @Override public Optional<Screen> gui(ScreenManager<P, S>.Viewer viewer, String id, Map<String, String> args) {
        Optional<GuiDocument> document = catalog.gui(id);
        if (document.isEmpty()) { host.message(viewer.player(), RichText.colorize(settings.message("shopMissing").replace("{shop}", id))); return Optional.empty(); }
        GuiScreen<P, S> screen = new GuiScreen<>(document.get(), viewer, this, args);
        if (!screen.shown()) { host.message(viewer.player(), RichText.colorize(settings.message("shopPermission"))); return Optional.empty(); }
        return Optional.of(screen);
    }

    @Override public Optional<Screen> shop(ScreenManager<P, S>.Viewer viewer, String id) {
        Optional<ShopDocument> shop = catalog.shop(id);
        if (shop.isEmpty()) { host.message(viewer.player(), RichText.colorize(settings.message("shopMissing").replace("{shop}", id))); return Optional.empty(); }
        return ShopScreen.open(shop.get(), viewer, context);
    }

    @Override public Object npc(ScreenManager<P, S>.Viewer viewer, String property) {
        if (viewer.npc().isEmpty()) return null;
        Optional<NpcRuntime.ActiveNpc> active = living.active(viewer.npc().get());
        return switch (property) {
            case "uuid" -> viewer.npc().get().toString();
            case "id" -> active.map(NpcRuntime.ActiveNpc::id).orElse("");
            case "name" -> active.map(NpcRuntime.ActiveNpc::name).orElse("");
            case "spawned" -> active.isPresent();
            case "health" -> active.map((NpcRuntime.ActiveNpc npc) -> npc.state().health()).orElse(0.0);
            default -> null;
        };
    }

    private ShopBinding binding(UUID npc) {
        Map<String, NpcDefinition> snapshot = runtime.npcs().snapshot();
        if (snapshot != bindingsSource) {
            Map<UUID, ShopBinding> next = new HashMap<>();
            for (NpcDefinition definition : snapshot.values()) {
                try { ShopBinding.of(definition).ifPresent((ShopBinding binding) -> next.put(definition.uuid(), binding)); }
                catch (IllegalArgumentException invalid) { /* Definitions are validated on load; an invalid module binds nothing. */ }
            }
            bindings = Map.copyOf(next);
            bindingsSource = snapshot;
        }
        return bindings.get(npc);
    }

    /** Shop ids, for completion. */
    public List<String> shopIds() { return catalog.shops().keySet().stream().sorted().limit(100).toList(); }

    public void list(CommandActor actor) {
        actor.tell("Shops: " + catalog.shops().size());
        catalog.shops().values().stream().limit(50).forEach((ShopDocument shop) -> actor.tell(shop.id() + " | " + RichText.plain(shop.title()) + " | pages=" + shop.pages().size()));
    }

    public void reloadCommand(CommandActor actor) {
        reload((ShopCatalog next) -> answer(actor, "Reloaded " + next.shops().size() + " shops and " + next.guis().size() + " GUIs; " + next.problems().size() + " problems"));
    }

    /** Opens a shop for the actor, or for the named player. */
    public void open(CommandActor actor, String shop, Optional<String> player) {
        deliver(actor, player, (P viewer) -> openShop(viewer, shop, Optional.empty()));
    }

    /** Clears the limits and stock counters of a shop, for everyone or one player by name or UUID. */
    public void reset(CommandActor actor, String shop, Optional<String> named) {
        Optional<UUID> player = Optional.empty();
        if (named.isPresent()) {
            player = playerNamed.apply(named.get()).or(() -> { try { return Optional.of(UUID.fromString(named.get())); } catch (IllegalArgumentException invalid) { return Optional.empty(); } });
            if (player.isEmpty()) { actor.tell("Unknown player: " + named.get()); return; }
        }
        store.ledger().reset(shop, player);
        actor.tell("Cleared limits and stock of " + shop + player.map((UUID id) -> " for " + id).orElse(""));
    }

    /** Reads a dtlTraders or Shopkeepers folder from {@code imports/inbox/}, with an optional saves file there too. */
    public void importPreview(CommandActor actor, String folder, Optional<String> saves) {
        if (!folder.matches("[A-Za-z0-9_-]{1,64}") || saves.isPresent() && !saves.get().matches("[A-Za-z0-9_-]{1,64}\\.yml")) {
            actor.tell("Shop import: name a folder and an optional .yml file directly under imports/inbox/");
            return;
        }
        fileWork(actor, () -> {
            Optional<Path> savesFile = saves.isPresent() ? Optional.of(runtime.documents().resolve("imports/inbox/" + saves.get())) : Optional.empty();
            ShopImporter.Preview preview = new ShopImporter().preview(runtime.documents().resolve("imports/inbox/" + folder), savesFile, runtime.documents());
            answer(actor, "Shop import " + preview.id() + ": " + preview.shops() + " shops, " + preview.entries() + " entries, "
                    + preview.issues() + " issues, limit data " + preview.limitStatus());
            answer(actor, "Review " + preview.directory().resolve("report.json") + ", then /animus shop import apply " + preview.id());
        });
    }

    public void importApply(CommandActor actor, String importId) {
        fileWork(actor, () -> {
            String transaction = new ShopImporter().apply(runtime.documents(), importId);
            answer(actor, "Shop import applied; transaction " + transaction + ". Reloading shops.");
            reload((ShopCatalog next) -> answer(actor, "Loaded " + next.shops().size() + " shops; " + next.problems().size() + " problems. Restart to load imported limit counts."));
        });
    }

    @FunctionalInterface private interface FileWork { void run() throws IOException; }

    private void fileWork(CommandActor actor, FileWork work) {
        try {
            files.execute(() -> {
                try { work.run(); }
                catch (IOException | RuntimeException failure) { answer(actor, "Shop import: " + failure.getMessage()); }
            });
        } catch (RejectedExecutionException full) { actor.tell("Animus file queue is full; retry shortly."); }
    }

    public void problems(CommandActor actor) {
        List<String> problems = catalog.problems();
        actor.tell("Shop and GUI problems: " + problems.size());
        problems.stream().limit(30).forEach(actor::tell);
    }

    private void deliver(CommandActor actor, Optional<String> named, Consumer<P> work) {
        Optional<UUID> target = named.isPresent() ? playerNamed.apply(named.get()) : actor.player();
        if (target.isEmpty()) { actor.tell(named.map((String name) -> "Unknown player: " + name).orElse("Name a player when running this from the console.")); return; }
        if (!living.players().deliver(target.get(), work)) actor.tell("That player is not online.");
    }

    private void answer(CommandActor actor, String message) { if (!closed && !actor.reply(() -> actor.tell(message))) log.accept(message); }

    /** Forgets every window; the caller closes the platform windows. Then saves the counters. */
    @Override public void close() {
        if (closed) return;
        closed = true;
        try { settingsListener.close(); } catch (Exception ignored) { /* Removing a listener cannot fail. */ }
        screens.clear();
        store.close();
    }
}
