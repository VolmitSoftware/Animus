package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.command.PermissionNodes;
import art.arcane.animus.core.gui.ScreenHost;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.owner.NpcOwnership;
import art.arcane.animus.core.shop.PlayerShops;
import art.arcane.animus.core.shop.ShopDocument;
import art.arcane.animus.core.shop.ShopSpot;
import art.arcane.animus.core.text.ChatLine;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Shops players make and run themselves, as in Shopkeepers: a new shop on a chest the player may open, its menu for
 * its owner, and Add chest's wait for the next container the player right-clicks. Every step runs on the player's owner;
 * file work goes through the toolkit's file lane as that player.
 */
final class PlayerShopFlow<P, S> {
    private final Toolkit<P, S> toolkit;
    private final Map<UUID, Consumer<ShopSpot>> chests = new ConcurrentHashMap<>();

    PlayerShopFlow(Toolkit<P, S> toolkit) { this.toolkit = toolkit; }

    void clear() { chests.clear(); }

    void forget(UUID player) { chests.remove(player); }

    /** The container shops, or empty after telling the player why there are none. */
    Optional<PlayerShops> shops(P player) {
        Optional<Toolkit.Commands> hooks = toolkit.hooks();
        if (hooks.isEmpty()) { toolkit.refuse(player, "The Animus menu is still starting; try again in a moment"); return Optional.empty(); }
        PlayerShops shops = hooks.get().shops().get();
        if (!shops.settings().enabled()) { toolkit.refuse(player, "Shops are turned off on this server"); return Optional.empty(); }
        return Optional.of(shops);
    }

    /** Whether the player may run shops they do not own. */
    boolean manager(P player) {
        ScreenHost<P, S> host = toolkit.host();
        return PlayerShops.manager(Optional.of(host.id(player)), (String node) -> host.permitted(player, node));
    }

    /** The node a shop's own screens need: {@code animus.shops.create}, or {@code animus.npc.others} for staff who run any shop. */
    static String node(boolean manager) { return manager ? PermissionNodes.NPC_OTHERS : PermissionNodes.SHOPS_CREATE; }

    private OptionalInt limit(P player, PlayerShops shops) {
        ScreenHost<P, S> host = toolkit.host();
        return PlayerShops.limit(Optional.of(host.id(player)), (String node) -> host.permitted(player, node), shops.settings());
    }

    /**
     * Opens New shop for the container: refused unless the player may make shops, may open that block, is under both
     * their shop and NPC limits, and no shop keeps its stock there yet. The player's own shop on it opens its menu.
     */
    void start(P player, ShopSpot spot) {
        if (!toolkit.allows(player, PermissionNodes.SHOPS_CREATE)) return;
        Optional<PlayerShops> shops = shops(player);
        if (shops.isEmpty()) return;
        ScreenHost<P, S> host = toolkit.host();
        UUID id = host.id(player);
        Optional<ShopDocument> holder = shops.get().at(spot.worlds(), spot.x(), spot.y(), spot.z());
        if (holder.isPresent()) {
            if (holder.get().owns(id) || manager(player)) manage(player, holder.get().id());
            else toolkit.refuse(player, "That chest belongs to " + owner(holder.get()) + "'s shop");
            return;
        }
        String world = toolkit.worldName(spot.worlds());
        Optional<String> refused = host.stockRefusal(player, spot.container(world));
        if (refused.isPresent()) { toolkit.refuse(player, "You cannot use that chest: " + refused.get()); return; }
        Optional<String> full = PlayerShops.full(shops.get().owned(id).size(), limit(player, shops.get()))
                .or(() -> PlayerShops.npcFull(NpcOwnership.owned(toolkit.npcs().all(), id), toolkit.limit(player)));
        if (full.isPresent()) { toolkit.refuse(player, full.get()); return; }
        toolkit.show(player, (Toolkit<P, S> menu, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands) -> new ShopMakeScreen<>(menu, viewer, commands, spot, world));
    }

    /** New shop's last step: saves the shop and its NPC, then shows the shop's menu. The checks of {@link #start} run again on the file lane. */
    void make(ScreenManager<P, S>.Viewer viewer, ShopSpot spot, String world, ShopDocument.Kind kind, String type) {
        P player = viewer.player();
        if (!toolkit.allows(player, PermissionNodes.SHOPS_CREATE)) return;
        Optional<PlayerShops> shops = shops(player);
        if (shops.isEmpty()) return;
        ScreenHost<P, S> host = toolkit.host();
        ShopDocument.Container chest = spot.container(world);
        Optional<String> refused = host.stockRefusal(player, chest);
        if (refused.isPresent()) { toolkit.refuse(player, "You cannot use that chest: " + refused.get()); return; }
        UUID id = viewer.id();
        OptionalInt shopLimit = limit(player, shops.get()), npcLimit = toolkit.limit(player);
        PlayerShopFiles.Plan plan = new PlayerShopFiles.Plan(id, host.name(player), kind, type, chest, new Landing.Spot(spot.stand(), spot.yaw()));
        toolkit.background(player, (ToolkitFiles ignored) -> {
            PlayerShopFiles files = new PlayerShopFiles(toolkit.npcs(), toolkit.documents());
            PlayerShopFiles.Census census = files.census(id, chest);
            if (census.holder().isPresent()) throw new IOException("That chest already belongs to a shop");
            Optional<String> full = PlayerShops.full(census.owned(), shopLimit).or(() -> PlayerShops.npcFull(NpcOwnership.owned(toolkit.npcs().all(), id), npcLimit));
            if (full.isPresent()) throw new IOException(full.get());
            return files.make(plan, world);
        }, (P online, PlayerShopFiles.Made made) -> {
            toolkit.living().refresh();
            shops.get().reload();
            toolkit.say(online, Toolkit.reply("Made " + made.title() + "; stock your chest, then set its offers; tx " + made.tx()));
            if (viewer.open()) viewer.replace(new PlayerShopScreen<>(toolkit, viewer, toolkit.hooks().orElseThrow(), made.shop(), Optional.of(made.stored())));
        });
    }

    /**
     * The shop's menu, for its owner or a player who may run any shop. {@code named} is the shop's id, its id without
     * {@code players:}, or its title in any case; the player's own shops are matched first.
     */
    void manage(P player, String named) {
        Optional<PlayerShops> shops = shops(player);
        if (shops.isEmpty()) return;
        UUID self = toolkit.host().id(player);
        boolean manager = manager(player);
        Optional<ShopDocument> shop = shops.get().find(named, self);
        if (!manager && shop.map((ShopDocument found) -> !found.owns(self)).orElse(true)) {
            toolkit.refuse(player, shop.isPresent() ? "That shop is not yours" : "You have no shop called " + named);
            return;
        }
        String id = shop.map(ShopDocument::id).orElse(named);
        toolkit.show(player, (Toolkit<P, S> menu, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands) -> new PlayerShopScreen<>(menu, viewer, commands, id));
    }

    /** {@code /animus shop item}: the shop maker item, for players who may make shops. */
    void item(P player) {
        if (!toolkit.allows(player, PermissionNodes.SHOPS_CREATE)) return;
        switch (toolkit.host().giveShopItem(player)) {
            case GIVEN -> toolkit.say(player, ChatLine.success("Shop item given; sneak and right-click your chest with it"));
            case MOVED -> toolkit.say(player, ChatLine.success("The shop item is in your hand; sneak and right-click your chest with it"));
            case DROPPED -> toolkit.say(player, ChatLine.success("Inventory full; the shop item is at your feet"));
            case NONE -> toolkit.refuse(player, ScreenHost.NO_PLAYER_SHOPS);
        }
    }

    /** Hides the window until the player right-clicks a container, which {@code chosen} then gets with the window back. */
    void pick(ScreenManager<P, S>.Viewer viewer, Consumer<ShopSpot> chosen) {
        UUID id = viewer.id();
        P player = viewer.player();
        viewer.suspend();
        chests.put(id, (ShopSpot spot) -> {
            if (!viewer.open()) { toolkit.refuse(player, "Add chest cancelled; the menu closed"); return; }
            viewer.resume();
            chosen.accept(spot);
            viewer.refresh();
        });
        toolkit.bar(player, "Add chest: right-click a chest, barrel or other container");
    }

    boolean picking(UUID player) { return chests.containsKey(player); }

    boolean clicked(UUID player, ShopSpot spot) {
        Consumer<ShopSpot> chosen = chests.remove(player);
        if (chosen == null) return false;
        chosen.accept(spot);
        return true;
    }

    /** The name a shop's owner goes by, or "a player". */
    static String owner(ShopDocument shop) { return shop.owner().flatMap(ShopDocument.Owner::name).orElse("a player"); }
}
