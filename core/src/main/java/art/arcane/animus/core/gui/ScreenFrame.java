package art.arcane.animus.core.gui;

import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.text.Placeholders;
import art.arcane.animus.core.text.RichText;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * What one window shows: a title, a size and the display item of every filled slot. Text is already coloured.
 * A frame holds no platform objects, so screens render and are tested without a server. A frame with a
 * {@code merchant} is the villager trading screen instead of a chest: its slots are the offers, by index.
 */
public record ScreenFrame(String title, String resolution, int width, int rows, Map<Integer, Item> items, Optional<Merchant> merchant) {
    public static final String MERCHANT = "merchant";

    /** A display item. {@code head} names the player whose head a {@code minecraft:player_head} shows. */
    public record Item(NpcItem item, Optional<String> name, List<String> lore, Optional<String> head) {
        public Item { Objects.requireNonNull(item); Objects.requireNonNull(name); lore = List.copyOf(lore); Objects.requireNonNull(head); }

        Item filled(UnaryOperator<String> fill) { return new Item(item, name.map(fill), lore.stream().map(fill).toList(), head); }
    }

    /**
     * One trade in the trading screen: up to two costs and a result, all display items. The offer is crossed out once
     * {@code uses} reaches {@code maxUses}.
     */
    public record Offer(Item costA, Optional<Item> costB, Item result, int uses, int maxUses) {
        public Offer { Objects.requireNonNull(costA); Objects.requireNonNull(costB); Objects.requireNonNull(result); }
    }

    /** The trading screen's offers, in list order. */
    public record Merchant(List<Offer> offers) {
        public Merchant { offers = List.copyOf(offers); }
    }

    public ScreenFrame {
        Objects.requireNonNull(title); Objects.requireNonNull(resolution); items = Map.copyOf(items); Objects.requireNonNull(merchant);
    }

    public ScreenFrame(String title, String resolution, int width, int rows, Map<Integer, Item> items) {
        this(title, resolution, width, rows, items, Optional.empty());
    }

    /** The trading screen with these offers. */
    public static ScreenFrame merchant(String title, Merchant merchant) {
        return new ScreenFrame(title, MERCHANT, 1, 1, Map.of(), Optional.of(merchant));
    }

    /**
     * This frame with a viewer's placeholders filled in its title, item names and lore, then coloured again so a
     * placeholder's own colour codes render. The same frame when no text holds a placeholder.
     */
    public ScreenFrame filled(Placeholders placeholders, UUID viewer) {
        if (!placeholders.active() || !marked()) return this;
        UnaryOperator<String> fill = (String text) -> {
            String result = placeholders.fill(viewer, text);
            return result.equals(text) ? text : RichText.colorize(result);
        };
        Map<Integer, Item> filledItems = new LinkedHashMap<>();
        for (Map.Entry<Integer, Item> slot : items.entrySet()) filledItems.put(slot.getKey(), slot.getValue().filled(fill));
        Optional<Merchant> filledMerchant = merchant.map((Merchant shown) -> new Merchant(shown.offers().stream()
                .map((Offer offer) -> new Offer(offer.costA().filled(fill), offer.costB().map((Item cost) -> cost.filled(fill)), offer.result().filled(fill), offer.uses(), offer.maxUses()))
                .toList()));
        return new ScreenFrame(fill.apply(title), resolution, width, rows, filledItems, filledMerchant);
    }

    private boolean marked() {
        if (Placeholders.marked(title)) return true;
        for (Item item : items.values()) if (marked(item)) return true;
        if (merchant.isPresent()) for (Offer offer : merchant.get().offers())
            if (marked(offer.costA()) || offer.costB().map(ScreenFrame::marked).orElse(false) || marked(offer.result())) return true;
        return false;
    }

    private static boolean marked(Item item) {
        return item.name().map(Placeholders::marked).orElse(false) || item.lore().stream().anyMatch(Placeholders::marked);
    }

    /** Slots a click may name: the chest's slots, or the trading screen's offers. */
    public int size() { return merchant.map((Merchant shown) -> shown.offers().size()).orElse(width * rows); }

    /** Two frames share a window when only their items differ. */
    public boolean sameWindow(ScreenFrame other) { return other != null && resolution.equals(other.resolution) && title.equals(other.title); }
}
