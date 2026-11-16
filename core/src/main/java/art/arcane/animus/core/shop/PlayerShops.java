package art.arcane.animus.core.shop;

import art.arcane.animus.core.command.PermissionNodes;
import art.arcane.animus.core.config.ShopSettings;
import art.arcane.animus.core.editor.DocumentKind;
import art.arcane.animus.core.editor.EditorDocuments;
import art.arcane.animus.core.editor.EditorError;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Container shops as the last catalog holds them: which blocks keep whose stock, how many shops a player owns and may
 * own, and the writes that follow the world rather than a player: closing a shop whose chest is lost and dropping a
 * chest its owner broke. Lookups read the last catalog without locks, from any thread; writes run on the file lane.
 */
public final class PlayerShops {
    /** One container block, its world as the shop file names it. */
    public record Block(String world, int x, int y, int z) {
        public static Block of(ShopDocument.Container container) { return new Block(container.world(), container.x(), container.y(), container.z()); }
        public String shown() { return world + " " + x + " " + y + " " + z; }
    }

    private final Supplier<DocumentStore> store;
    private final Executor files;
    private final Supplier<ShopSettings> settings;
    private final Runnable reload;
    private final BiConsumer<UUID, String> tell;
    private final Consumer<String> log;
    private volatile Map<Block, String> blocks = Map.of();
    private volatile Map<String, ShopDocument> shops = Map.of();

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

    /** Takes the container shops of a newly loaded catalog. */
    public void index(ShopCatalog catalog) {
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
        if (!problems.isEmpty()) throw new IOException(problems.getFirst());
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

    private static String upper(String text) { return text.isEmpty() ? "Its chest cannot be used" : Character.toUpperCase(text.charAt(0)) + text.substring(1); }

    private static String cut(String text) { return text.length() <= ShopDocument.MAXIMUM_REASON ? text : text.substring(0, ShopDocument.MAXIMUM_REASON); }

    private static String text(JsonObject object, String key) { return object.get(key) instanceof JsonElement value && value.isJsonPrimitive() ? value.getAsString() : ""; }

    private static int number(JsonObject object, String key) {
        try { return object.get(key) instanceof JsonElement value && value.isJsonPrimitive() ? value.getAsInt() : Integer.MIN_VALUE; }
        catch (NumberFormatException notNumber) { return Integer.MIN_VALUE; }
    }
}
