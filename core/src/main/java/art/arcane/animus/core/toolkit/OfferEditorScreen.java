package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.inventory.TradeInventory;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.shop.ShopDocument;
import art.arcane.animus.core.shop.ShopStock;
import art.arcane.animus.core.text.ChatLine;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A player shop's offers laid out as Shopkeepers lays them: one column per offer, the item on top and one or two price
 * items under it. A click on an empty slot picks it, and a click on an item in the player's own inventory puts a copy of
 * that stack there, nothing moving; with no slot picked the item starts a new offer, priced in the server's currency. A
 * click on an item adds one, a right-click takes one away, sneaking makes it ten; at none the item goes, and the offer
 * with its top item. A buying shop's second row is what it pays; a book shop's columns are the books in its chests,
 * by title. Offers this layout cannot show stay as they are. Nothing is saved before Save.
 */
final class OfferEditorScreen<P, S> extends ToolScreen<P, S> {
    static final int COLUMNS = 9, PAGES = 5, MAXIMUM = COLUMNS * PAGES, MOST = 64;
    static final int TOP = 0, FIRST = 1, SECOND = 2;
    static final String BOOK = "minecraft:written_book";

    /** One offer column: the item on top, or a book's title, and the price items under it, each carrying its amount as its count. */
    static final class Offer {
        final Optional<JsonObject> source;
        Optional<NpcItem> top = Optional.empty(), first = Optional.empty(), second = Optional.empty();
        Optional<String> book = Optional.empty();
        Offer(Optional<JsonObject> source) { this.source = source; }
        Optional<NpcItem> get(int row) { return row == TOP ? top : row == FIRST ? first : second; }
        void set(int row, Optional<NpcItem> item) { if (row == TOP) top = item; else if (row == FIRST) first = item; else second = item; }
        boolean priced() { return first.isPresent() || second.isPresent(); }
    }

    /** A picked slot: its offer and row. */
    private record Picked(int offer, int row) {}

    private final String id;
    private final Runnable changed;
    private Optional<ShopDocument> shop = Optional.empty();
    private ShopDocument.Kind kind = ShopDocument.Kind.TRADING;
    private final List<Offer> offers = new ArrayList<>();
    private final List<JsonObject> kept = new ArrayList<>();
    private int edits;
    private Optional<Picked> picked = Optional.empty();

    OfferEditorScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands, String id, Runnable changed) {
        super(toolkit, viewer, commands);
        this.id = id; this.changed = changed;
    }

    @Override String title() { return "Offers"; }
    @Override int rows() { return 4; }
    @Override String node() { return PlayerShopFlow.node(toolkit.shopFlow().manager(viewer.player())); }
    @Override void begin() { load(); }

    private PlayerShopFiles files() { return new PlayerShopFiles(toolkit.npcs(), toolkit.documents()); }

    private void load() {
        toolkit.later(viewer, (ToolkitFiles ignored) -> files().read(id), (PlayerShopFiles.Shop found) -> {
            shop = Optional.of(found.shop());
            kind = found.shop().kind().orElse(ShopDocument.Kind.TRADING);
            offers.clear(); kept.clear(); edits = 0; picked = Optional.empty();
            read(found.document());
            if (kind == ShopDocument.Kind.BOOK) books(found.shop());
            if (!kept.isEmpty()) toolkit.say(viewer.player(), ChatLine.reply(kept.size() + (kept.size() == 1 ? " offer stays" : " offers stay") + " as it is here; the web editor changes it"));
            viewer.refresh();
        });
    }

    /** Every entry, in page then slot order: one that fits this layout becomes a column, any other is kept as it is. */
    private void read(JsonObject document) {
        if (!(document.get("pages") instanceof JsonArray pages)) return;
        for (com.google.gson.JsonElement page : pages) {
            if (!(page instanceof JsonObject grids)) continue;
            for (String mode : List.of("buy", "sell", "trade")) {
                if (!(grids.get(mode) instanceof JsonObject grid)) continue;
                grid.keySet().stream().sorted(java.util.Comparator.comparingInt(Integer::parseInt)).forEach((String slot) -> {
                    if (!(grid.get(slot) instanceof JsonObject entry)) return;
                    Optional<Offer> column = mode.equals("trade") ? column(entry) : Optional.empty();
                    if (column.isPresent() && offers.size() < MAXIMUM) offers.add(column.get());
                    else kept.add(entry);
                });
            }
        }
    }

    /** The entry as a column, when it is a trade of one item for one or two (a buying shop: one for one; a book shop: a book for one or two). */
    private Optional<Offer> column(JsonObject entry) {
        Optional<ShopDocument.Entry> parsed = shop.flatMap((ShopDocument found) -> found.entry(text(entry, "id")));
        if (parsed.isEmpty() || parsed.get().type() != ShopDocument.Type.TRADE) return Optional.empty();
        ShopDocument.Entry trade = parsed.get();
        int needs = trade.needs().size(), gives = trade.gives().size();
        Offer offer = new Offer(Optional.of(entry));
        switch (kind) {
            case BOOK -> {
                if (trade.book().isEmpty() || needs > 2) return Optional.empty();
                offer.book = trade.book();
                prices(offer, trade.needs());
            }
            case BUYING -> {
                if (needs != 1 || gives != 1) return Optional.empty();
                offer.top = Optional.of(counted(trade.needs().getFirst()));
                offer.first = Optional.of(counted(trade.gives().getFirst()));
            }
            default -> {
                if (gives != 1 || needs > 2) return Optional.empty();
                offer.top = Optional.of(counted(trade.gives().getFirst()));
                prices(offer, trade.needs());
            }
        }
        return Optional.of(offer);
    }

    private static void prices(Offer offer, List<ShopDocument.Goods> needs) {
        if (!needs.isEmpty()) offer.first = Optional.of(counted(needs.getFirst()));
        if (needs.size() > 1) offer.second = Optional.of(counted(needs.get(1)));
    }

    private static NpcItem counted(ShopDocument.Goods goods) { return new NpcItem(goods.item().id(), Math.clamp(goods.amount(), 1, MOST), goods.item().components(), Optional.empty()); }

    /** A book shop also offers every copyable book title in its chests that has no offer yet, unpriced until the owner prices it. */
    private void books(ShopDocument found) {
        ShopStock<S> stock = toolkit.host().stock(found);
        if (stock.inventory().isEmpty()) { refuse("Cannot read the chest: " + stock.refusal().orElse("")); return; }
        TradeInventory<S> chest = stock.inventory().get();
        Set<String> titles = new LinkedHashSet<>();
        for (int slot = 0; slot < chest.size(); slot++) {
            S held = chest.get(slot);
            if (held == null || toolkit.host().items().empty(held) || toolkit.host().items().copyBook(held).isEmpty()) continue;
            toolkit.host().items().bookTitle(held).ifPresent(titles::add);
        }
        for (Offer offer : offers) offer.book.ifPresent(titles::remove);
        for (String title : titles) {
            if (offers.size() >= MAXIMUM) break;
            Offer offer = new Offer(Optional.empty());
            offer.book = Optional.of(title);
            offers.add(offer);
        }
    }

    private static String text(JsonObject object, String key) { return object.get(key) instanceof com.google.gson.JsonPrimitive value ? value.getAsString() : ""; }

    private int pages() { return Math.clamp((Math.min(offers.size() + (kind == ShopDocument.Kind.BOOK ? 0 : 1), MAXIMUM) + COLUMNS - 1) / COLUMNS, 1, PAGES); }

    @Override void draw() {
        help("Click an empty slot, then an item in your inventory, to put that item there. Click an item to add one, right-click to take one away; "
                + "sneak for ten. The top row is what players get, the rows below what they pay.", "10-shops", "player-shops");
        draft(edits, 1, true, this::save, this::load);
        if (shop.isEmpty()) { icon(13, "minecraft:paper", "&7Loading", List.of(), Optional.empty()); return; }
        int pages = pages();
        page = Math.clamp(page, 0, pages - 1);
        for (int column = 0; column < COLUMNS; column++) {
            int index = page * COLUMNS + column;
            for (int row = TOP; row <= SECOND; row++) slot(index, row, row * COLUMNS + column);
        }
        if (page > 0) button(bottom(PREVIOUS), "minecraft:arrow", title("Previous page"), any("page " + page), (GuiModel.Click click) -> page--);
        if (page < pages - 1) button(bottom(NEXT), "minecraft:arrow", title("Next page"), any("page " + (page + 2)), (GuiModel.Click click) -> page++);
        if (pages > 1) icon(bottom(PAGE), "minecraft:paper", "&7Page &f" + (page + 1) + "&7 of &f" + pages, List.of(), Optional.empty());
    }

    /** What a row holds for this shop's kind. */
    private String role(int row) {
        return switch (row) {
            case TOP -> switch (kind) { case BUYING -> "It buys"; case BOOK -> "Book"; default -> "It sells"; };
            case FIRST -> kind == ShopDocument.Kind.BUYING ? "It pays" : "Price";
            default -> "Second price";
        };
    }

    private void slot(int index, int row, int slot) {
        if (kind == ShopDocument.Kind.BUYING && row == SECOND) return;
        Optional<Offer> offer = index < offers.size() ? Optional.of(offers.get(index)) : Optional.empty();
        if (kind == ShopDocument.Kind.BOOK && row == TOP) {
            if (offer.isPresent()) icon(slot, BOOK, title(offer.get().book.orElse("")), List.of("&7" + role(row)), Optional.empty());
            else if (index == offers.size()) icon(slot, "minecraft:light_gray_stained_glass_pane", "&7No more books", List.of("&7Put a signed book in your chest"), Optional.empty());
            return;
        }
        if (offer.isEmpty() && index > offers.size()) return;
        if (offer.isEmpty() && row != TOP) return;
        Optional<NpcItem> item = offer.flatMap((Offer found) -> found.get(row));
        if (item.isPresent()) {
            counted(slot, item.get(), title(item.get().count() + "x " + itemName(new NpcItem(item.get().id(), 1, Optional.empty(), Optional.empty()))),
                    lore(List.of("&7" + role(row)), hints("add one", "take one away", "ten at a time")), (GuiModel.Click click) -> amount(index, row, click));
            return;
        }
        boolean chosen = picked.filter((Picked at) -> at.offer() == index && at.row() == row).isPresent();
        String hint = row == TOP && offer.isEmpty() ? "&7Or click an item in your inventory" : "&7Then click an item in your inventory";
        button(slot, chosen ? "minecraft:lime_stained_glass_pane" : "minecraft:light_gray_stained_glass_pane", chosen ? title("Picked") : "&7" + role(row),
                lore(List.of(chosen ? "&fClick an item in your inventory" : hint), any(chosen ? "unpick it" : "pick this slot")),
                (GuiModel.Click click) -> picked = chosen ? Optional.empty() : Optional.of(new Picked(index, row)));
    }

    private void amount(int index, int row, GuiModel.Click click) {
        Offer offer = offers.get(index);
        NpcItem item = offer.get(row).orElseThrow();
        int next = (int) step(click, item.count(), 1, 0, MOST);
        if (next == item.count()) return;
        edits++;
        if (next > 0) { offer.set(row, Optional.of(new NpcItem(item.id(), next, item.components(), Optional.empty()))); return; }
        if (row == TOP) { offers.remove(index); picked = Optional.empty(); return; }
        offer.set(row, Optional.empty());
    }

    /** A copy of a stack the player clicked in their own inventory goes to the picked slot, or starts a new offer. */
    @Override void take(NpcItem item) {
        if (shop.isEmpty()) return;
        NpcItem copy = new NpcItem(item.id(), Math.clamp(item.count(), 1, MOST), item.components(), Optional.empty());
        Picked target;
        if (picked.isPresent()) target = picked.get();
        else if (kind == ShopDocument.Kind.BOOK) { refuse("Click a price slot under a book first"); return; }
        else target = new Picked(offers.size(), TOP);
        if (target.row() == TOP && kind == ShopDocument.Kind.BOOK) { refuse("Books come from your chest; put a signed book in it"); return; }
        if (target.offer() >= offers.size()) {
            if (offers.size() >= MAXIMUM) { refuse("A shop shows at most " + MAXIMUM + " offers here"); return; }
            if (target.row() != TOP) { refuse("Put the item on top first"); return; }
            Offer added = new Offer(Optional.empty());
            if (kind != ShopDocument.Kind.TRADING) added.first = Optional.of(new NpcItem(currency(), 1, Optional.empty(), Optional.empty()));
            offers.add(added);
            page = (offers.size() - 1) / COLUMNS;
        }
        offers.get(target.offer()).set(target.row(), Optional.of(copy));
        picked = Optional.empty();
        edits++;
    }

    private String currency() { return toolkit.hooks().map((Toolkit.Commands hooks) -> hooks.shops().get().settings().currency()).orElse("minecraft:emerald"); }

    private void save() {
        List<JsonObject> entries = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Offer offer : offers) offer.source.map((JsonObject source) -> text(source, "id")).ifPresent(ids::add);
        for (JsonObject entry : kept) ids.add(text(entry, "id"));
        int next = 1;
        for (int index = 0; index < offers.size(); index++) {
            Offer offer = offers.get(index);
            if (kind == ShopDocument.Kind.BOOK && !offer.priced()) continue;
            if (offer.top.isEmpty() && offer.book.isEmpty()) { refuse("Offer " + (index + 1) + " has no item on top"); return; }
            if (!offer.priced()) { refuse("Offer " + (index + 1) + " needs a price"); page = index / COLUMNS; return; }
            String entryId;
            if (offer.source.isPresent()) entryId = text(offer.source.get(), "id");
            else {
                while (ids.contains("offer-" + next)) next++;
                entryId = "offer-" + next;
                ids.add(entryId);
            }
            entries.add(entry(offer, entryId));
        }
        entries.addAll(kept);
        toolkit.write(viewer, (ToolkitFiles ignored) -> files().offers(id, entries), () -> {
            toolkit.hooks().ifPresent((Toolkit.Commands hooks) -> hooks.shops().get().reload());
            changed.run();
            load();
        });
    }

    /** The trade entry a column writes; fields the column does not show, such as limits, stay as they were. */
    private JsonObject entry(Offer offer, String entryId) {
        JsonObject entry = offer.source.map(JsonObject::deepCopy).orElseGet(JsonObject::new);
        entry.addProperty("id", entryId);
        entry.addProperty("type", ShopDocument.Type.TRADE.key());
        List<NpcItem> prices = new ArrayList<>();
        offer.first.ifPresent(prices::add);
        offer.second.ifPresent(prices::add);
        switch (kind) {
            case BOOK -> {
                entry.add("item", new NpcItem(BOOK, 1, Optional.empty(), Optional.empty()).toJson());
                entry.addProperty("book", offer.book.orElseThrow());
                entry.add("needs", goods(prices));
                entry.remove("gives");
            }
            case BUYING -> {
                entry.add("item", offer.top.orElseThrow().toJson());
                entry.add("needs", goods(List.of(offer.top.orElseThrow())));
                entry.add("gives", goods(prices));
                entry.remove("book");
            }
            default -> {
                entry.add("item", offer.top.orElseThrow().toJson());
                entry.add("needs", goods(prices));
                entry.add("gives", goods(List.of(offer.top.orElseThrow())));
                entry.remove("book");
            }
        }
        return entry;
    }

    private static JsonArray goods(List<NpcItem> items) {
        JsonArray goods = new JsonArray();
        for (NpcItem item : items) {
            JsonObject one = new JsonObject();
            one.add("item", new NpcItem(item.id(), 1, item.components(), Optional.empty()).toJson());
            one.addProperty("amount", item.count());
            goods.add(one);
        }
        return goods;
    }
}
