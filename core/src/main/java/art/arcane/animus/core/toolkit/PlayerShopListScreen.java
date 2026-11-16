package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.shop.PlayerShops;
import art.arcane.animus.core.shop.ShopDocument;
import art.arcane.animus.core.text.RichText;
import java.util.ArrayList;
import java.util.List;

/** {@code /animus shop manage}: the shops the player runs, or every player's shop for staff who may run any. */
final class PlayerShopListScreen<P, S> extends ToolScreen<P, S> {
    PlayerShopListScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands) { super(toolkit, viewer, commands); }

    @Override String title() { return "Your shops"; }
    @Override int rows() { return 6; }
    @Override String node() { return PlayerShopFlow.node(toolkit.shopFlow().manager(viewer.player())); }

    @Override void draw() {
        back();
        help("Shops you made on your chests. Sneak and right-click a chest with the shop item from /animus shop item to make another.", "10-shops", "player-shops");
        boolean manager = toolkit.shopFlow().manager(viewer.player());
        List<ShopDocument> shops = toolkit.hooks().map((Toolkit.Commands hooks) -> {
            PlayerShops all = hooks.shops().get();
            return manager ? all.all() : all.owned(viewer.id());
        }).orElse(List.of());
        list(shops, (ShopDocument shop) -> RichText.plain(RichText.colorize(shop.title())), "No shops yet", (Integer slot, ShopDocument shop) -> {
            ShopDocument.Kind kind = shop.kind().orElse(ShopDocument.Kind.TRADING);
            List<String> lines = new ArrayList<>(List.of("&f" + ShopMakeScreen.label(kind) + " shop"));
            if (!shop.owns(viewer.id())) lines.add("&fOwner: " + PlayerShopFlow.owner(shop));
            lines.add(shop.closed().map((String reason) -> "&cClosed").orElse("&aOpen"));
            button(slot, ShopMakeScreen.ICONS.get(kind), title(RichText.plain(RichText.colorize(shop.title()))), lore(lines, any("run it")),
                    (GuiModel.Click click) -> push(new PlayerShopScreen<>(toolkit, viewer, commands, shop.id())));
        });
    }
}
