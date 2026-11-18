package art.arcane.animus.core.shop;

import art.arcane.animus.core.command.PermissionNodes;
import art.arcane.animus.core.config.ShopSettings;
import art.arcane.animus.core.editor.DocumentKind;
import art.arcane.animus.core.editor.EditorDocuments;
import art.arcane.animus.core.editor.EditorError;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocuments;
import art.arcane.animus.core.text.RichText;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Chest shops as the last catalog holds them: which blocks keep whose stock, how many shops a player owns and may own,
 * and the writes that follow the world rather than a player: closing a shop whose chest is lost and dropping a chest its
 * owner broke. Each shop also counts its sales since its owner last opened its menu, kept in {@value #SALES}, and tells
 * its online owner of each one unless they turned that off. Lookups read the last catalog without locks, from any
 * thread; writes run on the file lane.
 */
public final class PlayerShops {
    /** One container block, its world as the shop file names it. */
    public record Block(String world, int x, int y, int z) {
        public static Block of(ShopDocument.Container container) { return new Block(container.world(), container.x(), container.y(), container.z()); }
        public String shown() { return world + " " + x + " " + y + " " + z; }
    }

    /** One trade a chest shop made: who traded, what the shop handed over and what it took, as the buyer's reply names them. */
    public record Sale(String buyer, String gave, String took) {
        public Sale { Objects.requireNonNull(buyer); Objects.requireNonNull(gave); Objects.requireNonNull(took); }
    }

    /** The shop's sales since its owner last opened its menu, and whether the owner turned the sale line off. */
    private record Tally(int unseen, boolean quiet) {
        static final Tally NONE = new Tally(0, false);
    }

    /** The namespace of the shops players make in game. */
    public static final String NAMESPACE = "players";

    /** Where the sale counts and the sale line switches are kept. */
    public static final String SALES = "state/shop-sales.json";
    static final int SALES_SCHEMA = 1;

    private final Supplier<DocumentStore> store;
    private final Executor files;
    private final Supplier<ShopSettings> settings;
    private final Runnable reload;
    private final BiConsumer<UUID, String> tell;
    private final Consumer<String> log;
    private volatile Map<Block, String> blocks = Map.of();
    private volatile Map<String, ShopDocument> shops = Map.of();
    private final Map<String, Tally> sales = new ConcurrentHashMap<>();
    private volatile boolean salesRead;

    /**
     * @param reload reads the catalog again after a write
     * @param tell sends a line to a player who is online, on their owner; it does nothing for one who is not
     */
    public PlayerShops(Supplier<DocumentStore> store, Executor files, Supplier<ShopSettings> settings, Runnable reload, BiConsumer<UUID, String> tell, Consumer<String> log) {
        this.store = Objects.requireNonNull(store); this.files = Objects.requireNonNull(files); this.settings = Objects.requireNonNull(settings);
        this.reload = Objects.requireNonNull(reload); this.tell = Objects.requireNonNull(tell); this.log = Objects.requireNonNull(log);
    }

    /** Reads the catalog again, after a player's shop file changed. */
    public void reload() { reload.run(); }

    /** The {@code [shops]} settings a hotload last set. */
    public ShopSettings settings() { return settings.get(); }

    /** Takes the chest shops of a newly loaded catalog; the first one also reads the sale counts. Runs on the file lane. */
    public void index(ShopCatalog catalog) {
        if (!salesRead) readSales();
        Map<Block, String> nextBlocks = new HashMap<>();
        Map<String, ShopDocument> nextShops = new HashMap<>();
        for (ShopDocument shop : catalog.shops().values()) {
            if (!shop.stocked()) continue;
            nextShops.put(shop.id(), shop);
            for (ShopDocument.Container container : shop.containers()) nextBlocks.putIfAbsent(Block.of(container), shop.id());
        }
        blocks = Map.copyOf(nextBlocks);
        shops = Map.copyOf(nextShops);
    }

    /** Whether any loaded shop keeps its stock in a block, so a block event elsewhere needs no lookup. */
    public boolean any() { return !blocks.isEmpty(); }

    /** The shop whose stock is in this block; {@code worlds} holds every name the block's world answers to. */
    public Optional<ShopDocument> at(Collection<String> worlds, int x, int y, int z) {
        Map<Block, String> current = blocks;
        for (String world : worlds) {
            String id = current.get(new Block(world, x, y, z));
            if (id != null) return shop(id);
        }
        return Optional.empty();
    }

    public Optional<ShopDocument> shop(String id) { return Optional.ofNullable(shops.get(id)); }

    /** Every container shop, by id. */
    public List<ShopDocument> all() { return shops.values().stream().sorted(java.util.Comparator.comparing(ShopDocument::id)).toList(); }

    /** The container shops {@code player} owns, by id. */
    public List<ShopDocument> owned(UUID player) { return all().stream().filter((ShopDocument shop) -> shop.owns(player)).toList(); }

    /**
     * The chest shop a player means by {@code named}: its id, its id without {@code players:}, or its title in any case,
     * the player's own shops first.
     */
    public Optional<ShopDocument> find(String named, UUID player) {
        String wanted = named.strip();
        Optional<ShopDocument> exact = shop(wanted).or(() -> shop(NAMESPACE + ":" + wanted));
        if (exact.isPresent()) return exact;
        Predicate<ShopDocument> called = (ShopDocument shop) -> RichText.plain(RichText.colorize(shop.title())).equalsIgnoreCase(wanted)
                || shop.id().substring(shop.id().indexOf(':') + 1).equalsIgnoreCase(wanted);
        return owned(player).stream().filter(called).findFirst().or(() -> all().stream().filter(called).findFirst());
    }

    /** The player's shops as {@code /animus shop manage} completes them: their ids, without {@code players:}. */
    public List<String> names(UUID player) {
        return owned(player).stream().map((ShopDocument shop) -> shop.id().startsWith(NAMESPACE + ":") ? shop.id().substring(NAMESPACE.length() + 1) : shop.id()).toList();
    }

    /** Whoever may run a shop they do not own: the console, {@code animus.admin} and {@code animus.npc.others}. */
    public static boolean manager(Optional<UUID> player, Predicate<String> has) {
        return player.isEmpty() || has.test(PermissionNodes.ADMIN) || has.test(PermissionNodes.NPC_OTHERS);
    }

    /** How many shops the player may own: the highest {@code animus.shops.limit.<n>}, else {@code [shops] playerLimit}; empty for an admin. */
    public static OptionalInt limit(Optional<UUID> player, Predicate<String> has, ShopSettings settings) {
        if (player.isEmpty() || has.test(PermissionNodes.ADMIN)) return OptionalInt.empty();
        for (int limit = ShopSettings.MAXIMUM_PLAYER_LIMIT; limit >= 0; limit--) if (has.test(PermissionNodes.SHOP_LIMIT_PREFIX + limit)) return OptionalInt.of(limit);
        return OptionalInt.of(settings.playerLimit());
    }

    /**
     * Why a player at their NPC limit may not open a shop, since its NPC counts toward that limit; empty while one more
     * NPC fits. {@code owned} is how many NPCs they own.
     */
    public static Optional<String> npcFull(long owned, OptionalInt limit) {
        if (limit.isEmpty() || owned < limit.getAsInt()) return Optional.empty();
        return Optional.of(limit.getAsInt() == 0 ? "A shop needs an NPC and you cannot make NPCs; ask an admin for an " + PermissionNodes.LIMIT_PREFIX + "<n> node"
                : "A shop needs an NPC and you own " + owned + ", your limit; remove one to open a shop");
    }

    /**
     * The chest shops that would be left without an NPC if {@code leaving} were deleted: each one every NPC that opens it
     * is among them, and at least one is. {@code npcs} are the stored definitions.
     */
    public List<ShopDocument> runBy(Collection<NpcDefinition> npcs, Set<String> leaving) {
        Map<String, Boolean> kept = new HashMap<>();
        for (NpcDefinition definition : npcs) {
            Optional<ShopBinding> binding;
            try { binding = ShopBinding.parse(definition.json()); }
            catch (IllegalArgumentException invalid) { continue; }
            if (binding.isEmpty() || !shops.containsKey(binding.get().shop())) continue;
            boolean stays = !leaving.contains(definition.id());
            kept.merge(binding.get().shop(), stays, Boolean::logicalOr);
        }
        List<ShopDocument> orphaned = new ArrayList<>();
        for (Map.Entry<String, Boolean> shop : kept.entrySet()) if (!shop.getValue()) orphaned.add(shops.get(shop.getKey()));
        orphaned.sort(Comparator.comparing(ShopDocument::id));
        return orphaned;
    }

    /**
     * A chest shop traded: one more sale for its owner's menu, and a line to the owner when they are online and have not
     * turned it off. On the buyer's owner thread; the count is written on the file lane.
     */
    public void sold(ShopDocument shop, Sale sale) {
        Tally now = sales.merge(shop.id(), new Tally(1, false), (Tally before, Tally one) -> new Tally(Math.min(Integer.MAX_VALUE - 1, before.unseen()) + 1, before.quiet()));
        saveSales();
        if (!now.quiet()) shop.owner().ifPresent((ShopDocument.Owner owner) -> tell.accept(owner.uuid(), line(shop, sale)));
    }

    /** What the owner reads of a sale, from the shop's side: it sold to a player, or a buying shop bought from one. */
    static String line(ShopDocument shop, Sale sale) {
        String title = "&a" + shop.title() + "&a";
        return shop.kind().orElse(ShopDocument.Kind.TRADING) == ShopDocument.Kind.BUYING
                ? title + " bought " + sale.took() + " from " + sale.buyer() + " for " + sale.gave()
                : title + " sold " + sale.gave() + " to " + sale.buyer() + " for " + sale.took();
    }

    /** The shop's sales since this was last asked, which starts the count again: what its owner's menu shows when it opens. */
    public int seen(String id) {
        int[] unseen = {0};
        sales.computeIfPresent(id, (String key, Tally tally) -> { unseen[0] = tally.unseen(); return new Tally(0, tally.quiet()); });
        if (unseen[0] > 0) saveSales();
        return unseen[0];
    }

    /** Whether the owner turned the sale line of this shop off. */
    public boolean quiet(String id) { return sales.getOrDefault(id, Tally.NONE).quiet(); }

    /** Turns the shop's sale line off, or back on; the count goes on either way. */
    public void quiet(String id, boolean quiet) {
        sales.merge(id, new Tally(0, quiet), (Tally before, Tally ignored) -> new Tally(before.unseen(), quiet));
        saveSales();
    }

    /** Why a player who owns {@code owned} shops may not make another, or empty while they are under their limit. */
    public static Optional<String> full(long owned, OptionalInt limit) {
        if (limit.isEmpty() || owned < limit.getAsInt()) return Optional.empty();
        return Optional.of(limit.getAsInt() == 0 ? "You cannot make shops; ask an admin for an " + PermissionNodes.SHOP_LIMIT_PREFIX + "<n> node"
                : "You own " + owned + (owned == 1 ? " shop" : " shops") + ", your limit; remove one to make another");
    }

    /** The file of a shop id. */
    public static String path(String id) throws IOException {
        try { return EditorDocuments.pathFor(DocumentKind.SHOP, id); }
        catch (EditorError invalid) { throw new IOException(invalid.getMessage()); }
    }

    /**
     * Closes the shop with {@code reason} once its chest is lost, and tells its owner if they are online. A shop already
     * closed stays as it is. Never blocks the caller.
     */
    public void lost(ShopDocument shop, String reason) {
        later("Closing shop " + shop.id(), () -> {
            String text = upper(reason);
            Optional<String> changed = change(shop.id(), (JsonObject document) -> {
                if (document.has("closed")) return false;
                document.addProperty("closed", cut(text));
                return true;
            });
            if (changed.isEmpty()) return;
            shop.owner().ifPresent((ShopDocument.Owner owner) -> tell.accept(owner.uuid(), "&cYour shop " + shop.title() + "&c closed: " + text
                    + ". Fix it, then open the shop again from its menu."));
        });
    }

    /**
     * Its owner broke one of the shop's chests: the shop stops using it. Its last chest closes the shop instead, so the
     * owner can add another one first.
     */
    public void broken(ShopDocument shop, Block block) {
        later("Updating shop " + shop.id(), () -> {
            boolean last = shop.containers().size() == 1;
            Optional<String> changed = change(shop.id(), (JsonObject document) -> {
                if (last) {
                    if (document.has("closed")) return false;
                    document.addProperty("closed", "Its chest at " + block.shown() + " was broken");
                    return true;
                }
                if (!(document.get("containers") instanceof JsonArray containers)) return false;
                JsonArray kept = new JsonArray();
                for (JsonElement element : containers)
                    if (!(element instanceof JsonObject container && block.equals(new Block(text(container, "world"), number(container, "x"), number(container, "y"), number(container, "z")))))
                        kept.add(element);
                if (kept.size() == containers.size() || kept.isEmpty()) return false;
                document.add("containers", kept);
                return true;
            });
            if (changed.isEmpty()) return;
            shop.owner().ifPresent((ShopDocument.Owner owner) -> tell.accept(owner.uuid(), last
                    ? "&eYour shop " + shop.title() + "&e closed: its last chest was broken. Add a chest, then open the shop again."
                    : "&eYour shop " + shop.title() + "&e no longer uses the chest at " + block.shown() + "."));
        });
    }

    @FunctionalInterface private interface Edit { boolean apply(JsonObject document); }
    @FunctionalInterface private interface Work { void run() throws IOException; }

    /** Applies {@code edit} to the stored shop and commits it when it changed something; the transaction, or empty. File lane. */
    private Optional<String> change(String id, Edit edit) throws IOException {
        DocumentStore documents = store.get();
        String path = path(id);
        String current = documents.read(path);
        if (current == null) return Optional.empty();
        JsonObject document = JsonDocuments.object(current);
        if (!edit.apply(document)) return Optional.empty();
        document.addProperty("revision", EditorDocuments.revision(document) + 1);
        List<String> problems = ShopDocument.parse(id, document).problems();
        if (!problems.isEmpty()) throw new IOException(ShopDocument.readable(problems.getFirst()));
        String tx = documents.commit(List.of(new DocumentStore.Change(path, JsonDocuments.hash(current), JsonDocuments.write(document))));
        reload.run();
        return Optional.of(tx);
    }

    private void later(String label, Work work) {
        try {
            files.execute(() -> {
                try { work.run(); }
                catch (IOException | RuntimeException failure) { log.accept(label + ": " + failure.getMessage()); }
            });
        } catch (RejectedExecutionException full) { log.accept(label + ": the file queue is full"); }
    }

    /**
     * Reads {@value #SALES} once, adding its counts to any made before it was read. A missing file reads as no sales; an
     * unreadable one is logged and left until the next count replaces it. File lane.
     */
    private void readSales() {
        salesRead = true;
        try {
            Path file = store.get().resolve(SALES);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return;
            JsonObject saved = JsonDocuments.object(JsonDocuments.read(file));
            if (!(saved.get("schemaVersion") instanceof JsonElement version) || !version.isJsonPrimitive() || version.getAsInt() != SALES_SCHEMA)
                throw new IOException("schemaVersion is not " + SALES_SCHEMA);
            if (!(saved.get("shops") instanceof JsonObject listed)) return;
            for (Map.Entry<String, JsonElement> shop : listed.entrySet()) {
                if (!(shop.getValue() instanceof JsonObject tally)) continue;
                int unseen = tally.get("sales") instanceof JsonElement count && count.isJsonPrimitive() ? Math.max(0, count.getAsInt()) : 0;
                boolean quiet = tally.get("quiet") instanceof JsonElement off && off.isJsonPrimitive() && off.getAsBoolean();
                sales.merge(shop.getKey(), new Tally(unseen, quiet), (Tally made, Tally read) -> new Tally(made.unseen() + read.unseen(), read.quiet() || made.quiet()));
            }
        } catch (IOException | RuntimeException unreadable) { log.accept("Shop sales in " + SALES + " are unreadable: " + unreadable.getMessage()); }
    }

    /** Writes every count and switch that is not the default, on the file lane, after the saved ones were read in. */
    private void saveSales() {
        later("Saving shop sales", () -> {
            if (!salesRead) readSales();
            JsonObject listed = new JsonObject();
            for (Map.Entry<String, Tally> shop : new TreeMap<>(sales).entrySet()) {
                if (shop.getValue().equals(Tally.NONE)) continue;
                JsonObject tally = new JsonObject();
                tally.addProperty("sales", shop.getValue().unseen());
                if (shop.getValue().quiet()) tally.addProperty("quiet", true);
                listed.add(shop.getKey(), tally);
            }
            JsonObject saved = new JsonObject();
            saved.addProperty("schemaVersion", SALES_SCHEMA);
            saved.add("shops", listed);
            DocumentStore.atomic(store.get().resolve(SALES), JsonDocuments.write(saved));
        });
    }

    private static String upper(String text) { return text.isEmpty() ? "Its chest cannot be used" : Character.toUpperCase(text.charAt(0)) + text.substring(1); }

    private static String cut(String text) { return text.length() <= ShopDocument.MAXIMUM_REASON ? text : text.substring(0, ShopDocument.MAXIMUM_REASON); }

    private static String text(JsonObject object, String key) { return object.get(key) instanceof JsonElement value && value.isJsonPrimitive() ? value.getAsString() : ""; }

    private static int number(JsonObject object, String key) {
        try { return object.get(key) instanceof JsonElement value && value.isJsonPrimitive() ? value.getAsInt() : Integer.MIN_VALUE; }
        catch (NumberFormatException notNumber) { return Integer.MIN_VALUE; }
    }
}
