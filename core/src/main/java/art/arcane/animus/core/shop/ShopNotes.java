package art.arcane.animus.core.shop;

import art.arcane.animus.core.gui.GuiDocument;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Warnings about a shop that loads but cannot show everything exactly in its style. They never refuse the shop. In
 * the trading screen a trade shows two costs and one result, money shows as a token item and static entries are left
 * out; a layout GUI must be loaded and have a list area.
 */
public final class ShopNotes {
    private ShopNotes() {}

    /** The notes for this shop in this style, each as {@code $.path: message}. */
    public static List<String> of(ShopDocument shop, ShopDocument.Style style, Function<String, Optional<GuiDocument>> guis) {
        List<String> notes = new ArrayList<>();
        if (shop.layout().isPresent()) {
            String layout = shop.layout().get();
            if (style == ShopDocument.Style.NATIVE) notes.add("$.layout: applies to the chest style only");
            else {
                Optional<GuiDocument> gui = guis.apply(layout);
                if (gui.isEmpty()) notes.add("$.layout: chest GUI " + layout + " is not loaded; the shop shows its own pages");
                else if (gui.get().list().isEmpty()) notes.add("$.layout: chest GUI " + layout + " has no list area; the shop shows its own pages");
            }
        }
        if (style != ShopDocument.Style.NATIVE) return notes;
        for (int index = 0; index < shop.pages().size(); index++) {
            ShopDocument.Page page = shop.pages().get(index);
            for (ShopDocument.Mode mode : ShopDocument.Mode.values()) {
                List<Map.Entry<Integer, ShopDocument.Entry>> slots = page.grid(mode).entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.naturalOrder())).toList();
                for (Map.Entry<Integer, ShopDocument.Entry> slot : slots) {
                    String path = "$.pages[" + index + "]." + mode.key() + "." + slot.getKey();
                    ShopDocument.Entry entry = slot.getValue();
                    switch (entry.type()) {
                        case STATIC -> notes.add(path + ": static entries do not show in the trading screen");
                        case TRADE -> {
                            if (entry.needs().size() > 2 || entry.gives().size() > 1) notes.add(path + ": the trading screen shows two needs and one result; the rest are listed in the result's tooltip");
                            if (entry.price().signum() > 0) notes.add(path + ": the trading screen lists the money price in the result's tooltip; the money is taken from the player's balance");
                        }
                        case SELL -> notes.add(path + ": the trading screen shows the payment as a token item; the money is paid to the player's balance");
                        case BUY, COMMAND -> {
                            if (entry.price().signum() > 0) notes.add(path + ": the trading screen shows the price as a token item; the money is taken from the player's balance");
                        }
                    }
                }
            }
        }
        return notes;
    }
}
