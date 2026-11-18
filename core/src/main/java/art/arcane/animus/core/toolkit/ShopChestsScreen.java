package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenHost;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.shop.PlayerShops;
import art.arcane.animus.core.shop.ShopDocument;
import art.arcane.animus.core.shop.ShopSpot;
import java.util.List;
import java.util.Optional;

/**
 * The chests a player's shop keeps its stock in, up to eight: a click opens one, a right-click stops using it (the chest
 * and its items stay), and Add chest waits for the next container the player right-clicks.
 */
final class ShopChestsScreen<P, S> extends ToolScreen<P, S> {
    static final int ADD = ACTIONS;
    private final String id;
    private final Runnable changed;
    private Optional<ShopDocument> shop = Optional.empty();

    ShopChestsScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands, String id, Runnable changed) {
        super(toolkit, viewer, commands);
        this.id = id; this.changed = changed;
    }

    @Override String title() { return "Chests"; }
    @Override int rows() { return 6; }
    @Override String node() { return PlayerShopFlow.node(toolkit.shopFlow().manager(viewer.player())); }
    @Override void begin() { reload(); }

    private PlayerShopFiles files() { return new PlayerShopFiles(toolkit.npcs(), toolkit.documents()); }

    private void reload() {
        toolkit.later(viewer, (ToolkitFiles ignored) -> files().read(id), (PlayerShopFiles.Shop found) -> { shop = Optional.of(found.shop()); viewer.refresh(); });
    }

    private void saved() {
        toolkit.hooks().ifPresent((Toolkit.Commands hooks) -> hooks.shops().get().reload());
        reload();
        changed.run();
    }

    @Override void draw() {
        back();
        help("Your shop sells from and pays into every chest listed here. Removing one keeps the chest and its items.", "10-shops", "player-shops");
        if (shop.isEmpty()) { icon(22, "minecraft:paper", "&7Loading", List.of(), Optional.empty()); return; }
        List<ShopDocument.Container> chests = shop.get().containers();
        list(chests, (ShopDocument.Container chest) -> PlayerShops.Block.of(chest).shown(), "No chests", (Integer slot, ShopDocument.Container chest) ->
                button(slot, "minecraft:chest", title("Chest " + (chests.indexOf(chest) + 1)), lore(List.of("&7" + chest.x() + " " + chest.y() + " " + chest.z()),
                        hints("open it", "stop using it", "")), (GuiModel.Click click) -> {
                    if (right(click)) toolkit.write(viewer, (ToolkitFiles ignored) -> files().removeChest(id, chest), this::saved);
                    else toolkit.host().openContainer(viewer.player(), chest).ifPresent((String reason) -> refuse("Cannot open the chest: " + reason));
                }));
        if (chests.size() >= ShopDocument.MAXIMUM_CONTAINERS) icon(ADD, "minecraft:gray_dye", "&7Add chest", List.of("&7A shop keeps at most " + ShopDocument.MAXIMUM_CONTAINERS), Optional.empty());
        else button(ADD, "minecraft:hopper", title("Add chest"), lore(List.of("&7Then right-click the chest"), any("add a chest")),
                (GuiModel.Click click) -> toolkit.shopFlow().pick(viewer, this::add));
    }

    /** Adds the container the player right-clicked, after the checks New shop's chest passes; a listed chest that is gone makes way for it. */
    private void add(ShopSpot spot) {
        P player = viewer.player();
        Optional<PlayerShops> shops = toolkit.shopFlow().shops(player);
        if (shops.isEmpty()) return;
        Optional<ShopDocument> holder = shops.get().at(spot.worlds(), spot.x(), spot.y(), spot.z());
        if (holder.isPresent()) { refuse(holder.get().id().equals(id) ? "The shop already uses that chest" : "That chest belongs to another shop"); return; }
        ScreenHost<P, S> host = toolkit.host();
        ShopDocument.Container chest = spot.container(toolkit.worldName(spot.worlds()));
        Optional<String> refused = host.stockRefusal(player, chest);
        if (refused.isPresent()) { refuse("You cannot use that chest: " + refused.get()); return; }
        List<ShopDocument.Container> gone = this.shop.map((ShopDocument found) -> found.containers().stream().filter(host::containerGone).toList()).orElse(List.of());
        toolkit.write(viewer, (ToolkitFiles ignored) -> files().addChest(id, chest, gone), this::saved);
    }
}
