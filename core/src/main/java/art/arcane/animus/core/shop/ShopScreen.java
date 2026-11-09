package art.arcane.animus.core.shop;

import art.arcane.animus.core.config.ShopSettings;
import art.arcane.animus.core.gui.GuiDocument;
import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.GuiScreen;
import art.arcane.animus.core.gui.Screens;
import art.arcane.animus.core.text.RichText;
import art.arcane.animus.core.gui.Screen;
import art.arcane.animus.core.gui.ScreenFrame;
import art.arcane.animus.core.gui.ScreenHost;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.inventory.TradeInventory;
import art.arcane.animus.core.npc.NpcItem;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * A shop open for one viewer, in one of three looks.
 * <ul>
 * <li>Chest: the shop's own pages. The bottom row carries the controls: previous page in slot 2, close in slot 4,
 * next page in slot 6 and the buy, sell and trade switch in slot 8, each shown only when it does something. Entries
 * under a shown control are hidden.</li>
 * <li>Chest with a layout GUI: the GUI's list area shows every offer, in pages the GUI's own buttons turn.</li>
 * <li>Native: the villager trading screen lists every offer; see {@link ShopMerchant}.</li>
 * </ul>
 * Left and right click buy or sell the entry's amount; shift click buys or sells a full stack when the entry allows
 * it. With {@code confirm}, a chest window asks first and the shop comes back after.
 */
public final class ShopScreen<P, S> implements Screen {
    /** What every shop screen of one server shares. */
    public record Context<S>(ShopSettings settings, ShopTransactions<S> transactions, Optional<ShopStateStore> store, Supplier<Instant> clock) {}
    /**
     * How this window shows the shop. {@code villager} is the opening NPC's villager look, {@code layout} a chest GUI
     * with a list area, used with the chest style, and {@code screens} what that GUI's buttons open.
     */
    public record View<P, S>(ShopDocument.Style style, Optional<ShopBinding.Villager> villager, Optional<GuiDocument> layout, Optional<Screens<P, S>> screens) {
        public static <P, S> View<P, S> chest() { return new View<>(ShopDocument.Style.CHEST, Optional.empty(), Optional.empty(), Optional.empty()); }
    }
    /** One tradable entry and the index of the page it is on. */
    record Offer(ShopDocument.Entry entry, int page) {}
    private record Pending(Offer offer, boolean fullStack) {}
    private static final int CONFIRM = 11, ITEM = 13, CANCEL = 15, EMPTY_CLOSE = 22;

    private final ShopDocument shop;
    private final ScreenManager<P, S>.Viewer viewer;
    private final Context<S> context;
    private final View<P, S> view;
    private final List<ShopDocument.Page> pages;
    private final List<ShopDocument.Mode> modes;
    private final List<Offer> offers;
    private final GuiScreen<P, S> layout;
    private final Map<ShopDocument.Entry, Optional<ShopTransactions.Prepared<S>>> prepared = new HashMap<>();
    private final Map<Integer, ShopDocument.Entry> shown = new HashMap<>();
    private final Map<Integer, Runnable> controls = new HashMap<>();
    private int page;
    private ShopDocument.Mode mode;
    private Pending pending;

    private ShopScreen(ShopDocument shop, ScreenManager<P, S>.Viewer viewer, Context<S> context, View<P, S> view, List<ShopDocument.Page> pages,
                       List<ShopDocument.Mode> modes, List<Offer> offers) {
        this.shop = shop;
        this.viewer = viewer;
        this.context = context;
        this.view = view;
        this.pages = List.copyOf(pages);
        this.modes = List.copyOf(modes);
        this.offers = List.copyOf(offers);
        this.mode = modes.contains(shop.defaultMode()) || modes.isEmpty() ? shop.defaultMode() : modes.getFirst();
        Optional<GuiDocument> frame = view.style() == ShopDocument.Style.CHEST ? view.layout().filter((GuiDocument document) -> document.list().isPresent()) : Optional.empty();
        this.layout = frame.isPresent() && view.screens().isPresent()
                ? new GuiScreen<>(frame.get(), viewer, view.screens().get(), Map.of("shop", shop.id(), "title", shop.title()), Optional.of(new Cells())) : null;
    }

    /**
     * The shop in its own chest pages for this viewer, or empty after telling them why it cannot open. A shop with no
     * entries at all opens a window that says so.
     */
    public static <P, S> Optional<Screen> open(ShopDocument shop, ScreenManager<P, S>.Viewer viewer, Context<S> context) {
        return open(shop, viewer, context, View.chest());
    }

    /** The shop in the given view for this viewer, or empty after telling them why it cannot open. */
    public static <P, S> Optional<Screen> open(ShopDocument shop, ScreenManager<P, S>.Viewer viewer, Context<S> context, View<P, S> view) {
        ScreenHost<P, S> host = viewer.host();
        if (shop.permission().isPresent() && !host.permitted(viewer.player(), shop.permission().get())) {
            host.message(viewer.player(), RichText.colorize(context.settings().message("shopPermission")));
            return Optional.empty();
        }
        List<ShopDocument.Page> pages = shop.pages().stream().filter((ShopDocument.Page candidate) -> candidate.permission().map((String node) -> host.permitted(viewer.player(), node)).orElse(true)).toList();
        List<ShopDocument.Mode> modes = shop.modes().stream().filter((ShopDocument.Mode candidate) -> pages.stream().anyMatch((ShopDocument.Page visible) -> !visible.grid(candidate).isEmpty())).toList();
        List<Offer> offers = offers(pages, modes);
        boolean empty = shop.pages().stream().allMatch((ShopDocument.Page page) -> shop.modes().stream().allMatch((ShopDocument.Mode listed) -> page.grid(listed).isEmpty()));
        if (empty) return Optional.of(new ShopScreen<>(shop, viewer, context, view, List.of(), List.of(), List.of()));
        if (pages.isEmpty() || modes.isEmpty() || (view.style() == ShopDocument.Style.NATIVE && offers.isEmpty())) {
            host.message(viewer.player(), RichText.colorize(context.settings().message("noPages")));
            return Optional.empty();
        }
        return Optional.of(new ShopScreen<>(shop, viewer, context, view, pages, modes, offers));
    }

    /** Every tradable entry, by mode in switch order, then page, then slot. */
    static List<Offer> offers(List<ShopDocument.Page> pages, List<ShopDocument.Mode> modes) {
        List<Offer> offers = new ArrayList<>();
        for (ShopDocument.Mode listed : modes) for (int index = 0; index < pages.size(); index++) {
            int at = index;
            pages.get(index).grid(listed).entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                    .filter((Map.Entry<Integer, ShopDocument.Entry> slot) -> slot.getValue().type() != ShopDocument.Type.STATIC)
                    .forEach((Map.Entry<Integer, ShopDocument.Entry> slot) -> offers.add(new Offer(slot.getValue(), at)));
        }
        return offers;
    }

    public ShopDocument shop() { return shop; }
    public ShopDocument.Mode mode() { return mode; }
    public int page() { return page; }

    @Override public ScreenFrame render() {
        shown.clear();
        controls.clear();
        if (pending != null) return confirmFrame();
        if (pages.isEmpty()) return emptyFrame();
        if (view.style() == ShopDocument.Style.NATIVE) return new ShopMerchant(shop, context.settings(), context.transactions().ledger(), viewer.id(),
                (String node) -> viewer.host().permitted(viewer.player(), node), context.clock().get(), this::display).frame(offers, view.villager());
        if (layout != null) return layout.render();
        ShopDocument.Page current = pages.get(page);
        int rows = current.rows();
        Map<Integer, ScreenFrame.Item> items = new LinkedHashMap<>();
        Instant now = context.clock().get();
        for (Map.Entry<Integer, ShopDocument.Entry> slot : current.grid(mode).entrySet()) {
            items.put(slot.getKey(), display(slot.getValue(), now));
            shown.put(slot.getKey(), slot.getValue());
        }
        int base = (rows - 1) * 9;
        if (page > 0) control(base + 2, "minecraft:arrow", "previous", List.of(), items, () -> { page--; });
        control(base + 4, "minecraft:barrier", "close", List.of(), items, viewer::close);
        if (page < pages.size() - 1) control(base + 6, "minecraft:arrow", "next", List.of(), items, () -> { page++; });
        if (modes.size() > 1) {
            String icon = switch (mode) { case BUY -> "minecraft:emerald"; case SELL -> "minecraft:gold_ingot"; case TRADE -> "minecraft:chest"; };
            control(base + 8, icon, "mode", List.of(text("modeHint")), items, () -> mode = modes.get((modes.indexOf(mode) + 1) % modes.size()));
        }
        String title = shop.title() + (pages.size() > 1 ? " &8(" + (page + 1) + "/" + pages.size() + ")" : "");
        return new ScreenFrame(RichText.colorize(title), "9x" + rows, 9, rows, items);
    }

    /** A shop with no entries yet: a hint where the offers would be, and Close. */
    private ScreenFrame emptyFrame() {
        Map<Integer, ScreenFrame.Item> items = new LinkedHashMap<>();
        items.put(ITEM, new ScreenFrame.Item(item("minecraft:paper"), Optional.of(RichText.colorize(text("empty"))),
                List.of(RichText.colorize(text("emptyHint"))), Optional.empty()));
        control(EMPTY_CLOSE, "minecraft:barrier", "close", List.of(), items, viewer::close);
        return new ScreenFrame(RichText.colorize(shop.title()), "9x3", 9, 3, items);
    }

    @Override public void click(int slot, GuiModel.Click click) {
        if (pages.isEmpty()) { Runnable control = controls.get(slot); if (control != null) control.run(); return; }
        if (pending != null) {
            Pending confirmed = pending;
            if (slot == CONFIRM) { pending = null; purchase(confirmed.offer(), confirmed.fullStack()); }
            else if (slot == CANCEL) pending = null;
            return;
        }
        if (view.style() == ShopDocument.Style.NATIVE) { if (slot >= 0 && slot < offers.size()) choose(offers.get(slot), click); return; }
        if (layout != null) { layout.click(slot, click); return; }
        Runnable control = controls.get(slot);
        if (control != null) { control.run(); return; }
        ShopDocument.Entry entry = shown.get(slot);
        if (entry != null) choose(new Offer(entry, page), click);
    }

    @Override public boolean tick(long tick) { return (layout != null && pending == null && layout.tick(tick)) || tick % 20 == 0; }

    private void choose(Offer offer, GuiModel.Click click) {
        if (offer.entry().type() == ShopDocument.Type.STATIC) return;
        if (shop.confirm()) { pending = new Pending(offer, click.shift()); return; }
        purchase(offer, click.shift());
    }

    /** The layout GUI's list area: one cell per offer. */
    private final class Cells implements GuiScreen.Cells {
        @Override public int size() { return offers.size(); }
        @Override public ScreenFrame.Item item(int index) { return display(offers.get(index).entry(), context.clock().get()); }
        @Override public void click(int index, GuiModel.Click click) { choose(offers.get(index), click); }
    }

    private void purchase(Offer offer, boolean fullStack) {
        ShopDocument.Entry entry = offer.entry();
        ScreenHost<P, S> host = viewer.host();
        P player = viewer.player();
        Optional<ShopTransactions.Prepared<S>> ready = prepare(entry);
        if (entry.commands().stream().anyMatch((ShopDocument.ShopCommand command) -> command.command().contains("{player}"))
                && !art.arcane.animus.core.behavior.ArgumentTemplate.commandSafeName(host.name(player))) {
            host.message(player, RichText.colorize(text("failed").replace("{reason}", "your name cannot be written into this shop's commands")));
            return;
        }
        if (ready.isEmpty()) { host.message(player, RichText.colorize(text("failed").replace("{reason}", "an item in this entry does not exist on this server"))); return; }
        ShopTransactions.Buyer<S> buyer = new ShopTransactions.Buyer<>(viewer.id(), (String node) -> host.permitted(player, node), host.inventory(player),
                (S stack) -> host.drop(player, stack));
        Optional<TradeInventory<S>> stock = Optional.empty();
        if (shop.stocked()) {
            ShopStock<S> opened = host.stock(shop);
            if (opened.inventory().isEmpty()) { host.message(player, RichText.colorize(text("containerUnavailable").replace("{reason}", opened.refusal().orElse("")).replace("{shop}", shop.id()))); return; }
            stock = opened.inventory();
        }
        ShopTransactions.Outcome outcome = context.transactions().perform(shop.id(), ready.get(), buyer, fullStack, stock);
        String item = describe(ready.get(), entry, outcome.units());
        String price = price(outcome.price());
        String key = switch (outcome.status()) {
            case DONE -> switch (entry.type()) {
                case BUY -> outcome.price().signum() > 0 ? "bought" : "boughtFree";
                case SELL -> "sold";
                case TRADE -> "traded";
                case COMMAND -> "purchased";
                case STATIC -> "notTradable";
            };
            case NOT_TRADABLE -> "notTradable";
            case NO_PERMISSION -> "noPermission";
            case LIMIT_REACHED -> "limitReached";
            case OUT_OF_STOCK -> "outOfStock";
            case ECONOMY_UNAVAILABLE -> "economyUnavailable";
            case INSUFFICIENT_FUNDS -> "insufficientFunds";
            case INVENTORY_FULL -> "inventoryFull";
            case NOT_ENOUGH_ITEMS -> "notEnoughItems";
            case NOTHING_TO_SELL -> "nothingToSell";
            case PAYMENT_FAILED -> "paymentFailed";
            case FAILED -> "failed";
            case CONTAINER_FULL -> "containerFull";
            case CONTAINER_UNAVAILABLE -> "containerUnavailable";
        };
        host.message(player, RichText.colorize(fill(text(key), item, price, outcome.detail().orElse(""))));
        if (!outcome.done()) return;
        String name = host.name(player);
        for (ShopDocument.ShopCommand command : entry.commands()) {
            String line = command.command().replace("{player}", name).replace("{uuid}", viewer.id().toString())
                    .replace("{amount}", Integer.toString(outcome.units())).replace("{price}", outcome.price().toPlainString());
            host.command(command.source() == ShopDocument.CommandSource.PLAYER ? Optional.of(player) : Optional.empty(), line);
        }
        entry.broadcast().filter((String text) -> !text.isBlank()).ifPresent((String text) -> host.broadcast(RichText.colorize(fill(text.replace("{player}", name), item, price, ""))));
        if (shop.log()) context.store().ifPresent((ShopStateStore store) -> store.log(shop.id(), name + " (" + viewer.id() + ") "
                + entry.type().name().toLowerCase(Locale.ROOT) + " " + entry.id() + ": " + RichText.plain(item) + " for " + outcome.price().toPlainString()
                + " on page \"" + pages.get(offer.page()).name() + "\""));
        if (entry.closeAfterPurchase() || shop.closeAfterPurchase()) viewer.close();
    }

    private ScreenFrame confirmFrame() {
        Map<Integer, ScreenFrame.Item> items = new LinkedHashMap<>();
        items.put(CONFIRM, new ScreenFrame.Item(item("minecraft:lime_wool"), Optional.of(RichText.colorize(text("confirm"))), List.of(), Optional.empty()));
        items.put(ITEM, display(pending.offer().entry(), context.clock().get()));
        items.put(CANCEL, new ScreenFrame.Item(item("minecraft:red_wool"), Optional.of(RichText.colorize(text("cancel"))), List.of(), Optional.empty()));
        return new ScreenFrame(RichText.colorize(text("confirmTitle")), "9x3", 9, 3, items);
    }

    private void control(int slot, String icon, String key, List<String> lore, Map<Integer, ScreenFrame.Item> items, Runnable action) {
        String name = key.equals("mode") ? text("mode").replace("{mode}", modeName(mode)) : text(key);
        List<String> coloured = new ArrayList<>();
        for (String line : lore) coloured.add(RichText.colorize(line));
        items.put(slot, new ScreenFrame.Item(item(icon), Optional.of(RichText.colorize(name)), coloured, Optional.empty()));
        shown.remove(slot);
        controls.put(slot, action);
    }

    private String modeName(ShopDocument.Mode value) {
        return text(switch (value) { case BUY -> "modeBuy"; case SELL -> "modeSell"; case TRADE -> "modeTrade"; });
    }

    private ScreenFrame.Item display(ShopDocument.Entry entry, Instant now) {
        List<String> lore = new ArrayList<>(entry.display().lore());
        BigDecimal price = entry.price(now);
        if (entry.display().showPrice()) {
            switch (entry.type()) {
                case BUY, COMMAND -> { lore.add(price.signum() > 0 ? text("priceLine").replace("{price}", price(price)) : text("freeLine")); if (entry.allowStack()) lore.add(text("stackLine")); }
                case SELL -> { lore.add(text("payLine").replace("{price}", price(price))); if (entry.allowStack()) lore.add(text("stackLine")); }
                case TRADE -> {
                    lore.add(text("needsLine"));
                    for (ShopDocument.Goods need : entry.needs()) lore.add(text("goodsLine").replace("{item}", goods(need)));
                    lore.add(text("givesLine"));
                    for (ShopDocument.Goods give : entry.gives()) lore.add(text("goodsLine").replace("{item}", goods(give)));
                    if (price.signum() > 0) lore.add(text("priceLine").replace("{price}", price(price)));
                }
                case STATIC -> {}
            }
        }
        ShopLedger ledger = context.transactions().ledger();
        if (entry.display().showLimit() && entry.limit().perPlayer() >= 0) {
            lore.add(text("limitLine").replace("{used}", Long.toString(ledger.usedBy(shop.id(), entry.id(), viewer.id(), now))).replace("{limit}", Long.toString(entry.limit().perPlayer())));
            if (entry.limit().resetSeconds() > 0) lore.add(text("resetLine").replace("{period}", period(entry.limit().resetSeconds())));
        }
        if (entry.display().showLimit() && entry.stock().isPresent())
            lore.add(text("stockLine").replace("{left}", Long.toString(Math.max(0, entry.stock().get().maximum() - ledger.stockUsed(shop.id(), entry.id(), now)))));
        if (entry.permission().isPresent() && !viewer.host().permitted(viewer.player(), entry.permission().get())) lore.add(text("lockedLine"));
        List<String> coloured = new ArrayList<>();
        for (String line : lore) coloured.add(RichText.colorize(line));
        return new ScreenFrame.Item(entry.item(), entry.display().name().map(RichText::colorize), coloured, Optional.empty());
    }

    private Optional<ShopTransactions.Prepared<S>> prepare(ShopDocument.Entry entry) {
        return prepared.computeIfAbsent(entry, (ShopDocument.Entry key) -> {
            List<String> errors = new ArrayList<>();
            ScreenHost<P, S> host = viewer.host();
            Optional<S> goods = host.stack(single(key.item()), Optional.empty(), List.of(), errors);
            List<ShopTransactions.Need<S>> needs = new ArrayList<>(), gives = new ArrayList<>();
            for (ShopDocument.Goods need : key.needs()) host.stack(single(need.item()), Optional.empty(), List.of(), errors).ifPresent((S stack) -> needs.add(new ShopTransactions.Need<>(stack, need.amount())));
            for (ShopDocument.Goods give : key.gives()) host.stack(single(give.item()), Optional.empty(), List.of(), errors).ifPresent((S stack) -> gives.add(new ShopTransactions.Need<>(stack, give.amount())));
            if (goods.isEmpty() || !errors.isEmpty()) return Optional.empty();
            return Optional.of(new ShopTransactions.Prepared<>(key, goods.get(), needs, gives));
        });
    }

    private String describe(ShopTransactions.Prepared<S> ready, ShopDocument.Entry entry, int units) {
        if (entry.type() == ShopDocument.Type.TRADE) {
            List<String> parts = new ArrayList<>();
            for (ShopTransactions.Need<S> give : ready.gives()) parts.add(viewer.host().items().describe(give.stack(), give.amount()));
            return String.join(", ", parts);
        }
        String name = entry.display().name().map(RichText::plain).orElse(null);
        int count = units > 0 ? units : entry.item().count();
        return name != null && entry.type() == ShopDocument.Type.COMMAND ? name : viewer.host().items().describe(ready.goods(), count);
    }

    static String goods(ShopDocument.Goods goods) { return goods.amount() + "x " + readable(goods.item().id()); }

    static String readable(String id) {
        String path = id.substring(id.indexOf(':') + 1);
        StringBuilder out = new StringBuilder();
        for (String word : path.split("[_/]")) if (!word.isEmpty()) out.append(out.isEmpty() ? "" : " ").append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        return out.toString();
    }

    private static NpcItem single(NpcItem item) { return new NpcItem(item.id(), 1, item.components(), Optional.empty()); }
    private static NpcItem item(String id) { return new NpcItem(id, 1, Optional.empty(), Optional.empty()); }

    private String text(String key) { return context.settings().message(key); }

    private String price(BigDecimal amount) { return price(context.settings(), amount); }

    static String price(ShopSettings settings, BigDecimal amount) {
        DecimalFormat format = new DecimalFormat(settings.priceFormat(), DecimalFormatSymbols.getInstance(Locale.ROOT));
        return format.format(amount);
    }

    private String fill(String template, String item, String price, String reason) {
        return template.replace("{item}", item).replace("{price}", price).replace("{reason}", reason).replace("{shop}", shop.id());
    }

    private static String period(long seconds) {
        if (seconds % 86400 == 0) return seconds / 86400 + "d";
        if (seconds % 3600 == 0) return seconds / 3600 + "h";
        if (seconds % 60 == 0) return seconds / 60 + "m";
        return seconds + "s";
    }
}
