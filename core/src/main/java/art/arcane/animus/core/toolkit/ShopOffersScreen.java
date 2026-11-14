package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.command.PermissionNodes;
import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.shop.Prices;
import art.arcane.animus.core.shop.ShopDocument;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** A shop's offers, page by page; a click takes one out after asking once, in one transaction. */
final class ShopOffersScreen<P, S> extends ToolScreen<P, S> {
    private final ShopFiles.Shop shop;
    private final Runnable changed;
    private List<ShopFiles.Offer> offers;

    ShopOffersScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands, ShopFiles.Shop shop, Runnable changed) {
        super(toolkit, viewer, commands);
        this.shop = shop; this.changed = changed;
    }

    @Override void begin() { reload(); }

    private ShopFiles files() { return new ShopFiles(toolkit.npcs(), toolkit.documents()); }

    private void reload() { toolkit.later(viewer, (ToolkitFiles ignored) -> files().offers(shop.id()), (List<ShopFiles.Offer> found) -> { offers = found; viewer.refresh(); }); }

    @Override String title() { return "Offers"; }
    @Override String node() { return PermissionNodes.SHOPS; }
    @Override int rows() { return 6; }

    @Override void draw() {
        if (offers == null) icon(22, "minecraft:clock", "&7Reading offers", List.of(), Optional.empty());
        else list(offers, (ShopFiles.Offer offer) -> itemName(offer.entry().item()) + " " + offer.entry().id(), "No offers yet. Use Add held item", (Integer slot, ShopFiles.Offer offer) -> {
            String name = itemName(offer.entry().item());
            List<String> lines = new ArrayList<>(List.of("&7" + deal(offer, commands.prices().get())));
            if (advanced()) lines.add("&8" + offer.entry().id() + ", page " + (offer.page() + 1) + " slot " + offer.slot());
            button(slot, offer.entry().item(), title(name), lore(lines, any("remove it")), (GuiModel.Click click) ->
                    ask(slot, "Removes " + name + " from " + shop.name() + "; Undo in chat brings it back", 0, true,
                            () -> ShopFiles.save(toolkit, viewer, (ToolkitFiles ignored) -> files().removeOffer(shop.id(), offer), ShopBindScreen.RELOAD_COMMAND, () -> { reload(); changed.run(); })));
        });
        back();
        help("These are what the shop trades. Click one to take it out; Add held item on the shop puts more in.", "10-shops", "stocking-a-shop-in-game");
    }

    /** {@code Players buy it for $12.00}: what the offer does, in its grid's words, its price as the server's economy writes it. */
    static String deal(ShopFiles.Offer offer, Prices prices) {
        ShopDocument.Entry entry = offer.entry();
        if (entry.type() == ShopDocument.Type.STATIC) return "Display only";
        if (entry.type() == ShopDocument.Type.COMMAND) return "Runs a command";
        String price = prices.format(entry.price());
        return switch (offer.mode()) {
            case BUY -> "Players buy it for " + price;
            case SELL -> "Players sell it for " + price;
            case TRADE -> "Players trade for it";
        };
    }
}
