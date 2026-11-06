package art.arcane.animus.core.shop;

import art.arcane.animus.core.config.ShopSettings;
import art.arcane.animus.core.gui.ScreenFrame;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.text.RichText;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Predicate;

/**
 * A shop drawn as the villager trading screen. Each tradable entry is one offer:
 * <ul>
 * <li>trade: the first two needs are the costs and the first give is the result;</li>
 * <li>buy and command: a gold nugget token named with the price is the cost and the entry's item is the result;</li>
 * <li>sell: the entry's item is the cost and a gold nugget token named with the payment is the result.</li>
 * </ul>
 * The result's tooltip is the chest entry's tooltip, so it names every need and give, the price, the limit and the
 * stock. Uses count down the tightest of the per-player limit, the global limit and the stock; a locked entry shows
 * crossed out. The screen is only a picture: no item moves into it, and choosing the result runs the same
 * transaction as a chest click.
 */
final class ShopMerchant {
    private final ShopDocument shop;
    private final ShopSettings settings;
    private final ShopLedger ledger;
    private final UUID viewer;
    private final Predicate<String> permitted;
    private final Instant now;
    private final BiFunction<ShopDocument.Entry, Instant, ScreenFrame.Item> tooltip;

    ShopMerchant(ShopDocument shop, ShopSettings settings, ShopLedger ledger, UUID viewer, Predicate<String> permitted, Instant now,
                 BiFunction<ShopDocument.Entry, Instant, ScreenFrame.Item> tooltip) {
        this.shop = shop;
        this.settings = settings;
        this.ledger = ledger;
        this.viewer = viewer;
        this.permitted = permitted;
        this.now = now;
        this.tooltip = tooltip;
    }

    ScreenFrame frame(List<ShopScreen.Offer> offers, Optional<ShopBinding.Villager> villager) {
        List<ScreenFrame.Offer> shown = new ArrayList<>();
        for (ShopScreen.Offer offer : offers) shown.add(offer(offer.entry()));
        return ScreenFrame.merchant(RichText.colorize(title(villager)), new ScreenFrame.Merchant(shown));
    }

    /** The shop's title, or for an untitled shop the profession's name; a villager adds its level, as vanilla does. */
    String title(Optional<ShopBinding.Villager> villager) {
        if (villager.isEmpty()) return shop.title();
        String title = shop.title();
        if (title.equals(shop.id()) && !villager.get().profession().endsWith(":none")) title = ShopScreen.readable(villager.get().profession());
        return settings.message("merchantTitle").replace("{title}", title).replace("{level}", settings.message("level" + villager.get().level()));
    }

    private ScreenFrame.Offer offer(ShopDocument.Entry entry) {
        ScreenFrame.Item tip = tooltip.apply(entry, now);
        BigDecimal price = entry.price(now);
        ScreenFrame.Item costA, result;
        Optional<ScreenFrame.Item> costB = Optional.empty();
        switch (entry.type()) {
            case TRADE -> {
                costA = goods(entry.needs().getFirst());
                if (entry.needs().size() > 1) costB = Optional.of(goods(entry.needs().get(1)));
                ShopDocument.Goods give = entry.gives().getFirst();
                result = new ScreenFrame.Item(counted(give.item(), give.amount()), tip.name(), tip.lore(), Optional.empty());
            }
            case SELL -> {
                costA = new ScreenFrame.Item(entry.item(), tip.name(), List.of(), Optional.empty());
                result = token(settings.message("tokenPays").replace("{price}", ShopScreen.price(settings, price)), tip.lore());
            }
            default -> {
                costA = token(price.signum() > 0 ? settings.message("tokenPrice").replace("{price}", ShopScreen.price(settings, price)) : settings.message("tokenFree"), List.of());
                result = tip;
            }
        }
        long cap = -1, left = Long.MAX_VALUE;
        String id = shop.id();
        if (entry.limit().perPlayer() >= 0) { long remaining = entry.limit().perPlayer() - ledger.usedBy(id, entry.id(), viewer, now); if (remaining < left) { left = remaining; cap = entry.limit().perPlayer(); } }
        if (entry.limit().global() >= 0) { long remaining = entry.limit().global() - ledger.usedGlobally(id, entry.id(), now); if (remaining < left) { left = remaining; cap = entry.limit().global(); } }
        if (entry.stock().isPresent()) { long remaining = entry.stock().get().maximum() - ledger.stockUsed(id, entry.id(), now); if (remaining < left) { left = remaining; cap = entry.stock().get().maximum(); } }
        int maxUses = cap < 0 ? Integer.MAX_VALUE : (int) Math.min(Integer.MAX_VALUE, cap);
        int uses = cap < 0 ? 0 : (int) Math.min(maxUses, Math.max(0, cap - Math.max(0, left)));
        if (entry.permission().isPresent() && !permitted.test(entry.permission().get())) {
            if (cap < 0) maxUses = 1;
            uses = maxUses;
        }
        return new ScreenFrame.Offer(costA, costB, result, uses, maxUses);
    }

    private static ScreenFrame.Item goods(ShopDocument.Goods goods) {
        return new ScreenFrame.Item(counted(goods.item(), goods.amount()), Optional.empty(), List.of(), Optional.empty());
    }

    private static NpcItem counted(NpcItem item, int amount) {
        return new NpcItem(item.id(), Math.clamp(amount, 1, NpcItem.MAX_COUNT), item.components(), Optional.empty());
    }

    private static ScreenFrame.Item token(String name, List<String> lore) {
        return new ScreenFrame.Item(new NpcItem("minecraft:gold_nugget", 1, Optional.empty(), Optional.empty()), Optional.of(RichText.colorize(name)), lore, Optional.empty());
    }
}
