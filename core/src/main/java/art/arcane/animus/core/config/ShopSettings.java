package art.arcane.animus.core.config;

import java.text.DecimalFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * {@code [shops]} in {@code config.toml}. {@code economy} is {@code auto} (Vault on Bukkit when installed) or
 * {@code none}. {@code messages} overrides any of the default texts; placeholders are written {@code {name}}.
 */
public record ShopSettings(boolean enabled, String economy, String priceFormat, int flushMillis, Map<String, String> messages) {
    public static final Map<String, String> DEFAULT_MESSAGES = defaults();
    public static final ShopSettings DEFAULT = new ShopSettings(true, "auto", "#,##0.##", 2000, DEFAULT_MESSAGES);

    public ShopSettings {
        if (!Set.of("auto", "none").contains(economy)) throw new IllegalArgumentException("economy must be auto or none");
        try { new DecimalFormat(priceFormat); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("priceFormat is not a decimal pattern: " + priceFormat, invalid); }
        if (flushMillis < 100 || flushMillis > 60_000) throw new IllegalArgumentException("flushMillis must be within 100..60000");
        Map<String, String> merged = new LinkedHashMap<>(DEFAULT_MESSAGES);
        for (Map.Entry<String, String> message : messages.entrySet()) {
            if (!DEFAULT_MESSAGES.containsKey(message.getKey())) throw new IllegalArgumentException("messages." + message.getKey() + " is not a shop message");
            if (message.getValue() == null || message.getValue().length() > 512) throw new IllegalArgumentException("messages." + message.getKey() + " must be at most 512 characters");
            merged.put(message.getKey(), message.getValue());
        }
        messages = Map.copyOf(merged);
    }

    public String message(String key) { return messages.getOrDefault(key, key); }

    private static Map<String, String> defaults() {
        Map<String, String> texts = new LinkedHashMap<>();
        texts.put("bought", "&aBought {item} for {price}.");
        texts.put("boughtFree", "&aReceived {item}.");
        texts.put("sold", "&aSold {item} for {price}.");
        texts.put("traded", "&aTraded for {item}.");
        texts.put("purchased", "&aPurchased {item} for {price}.");
        texts.put("notTradable", "&7That item is not for sale.");
        texts.put("noPermission", "&cYou do not have permission for that.");
        texts.put("limitReached", "&cYou have reached the limit for {item}.");
        texts.put("outOfStock", "&c{item} is out of stock.");
        texts.put("economyUnavailable", "&cMoney trades are unavailable: {reason}");
        texts.put("insufficientFunds", "&cYou need {price} for that.");
        texts.put("inventoryFull", "&cYour inventory has no room for {item}.");
        texts.put("notEnoughItems", "&cYou do not have the items for that.");
        texts.put("nothingToSell", "&cYou have no {item} to sell.");
        texts.put("paymentFailed", "&cThe payment failed; your items were returned.");
        texts.put("failed", "&cThat trade failed and was undone: {reason}");
        texts.put("shopMissing", "&cThe shop {shop} is not available.");
        texts.put("shopPermission", "&cYou cannot use this shop.");
        texts.put("noPages", "&cThis shop has nothing you can use.");
        texts.put("priceLine", "&7Price: &f{price}");
        texts.put("payLine", "&7Pays: &f{price}");
        texts.put("freeLine", "&7Free");
        texts.put("needsLine", "&7Needs:");
        texts.put("givesLine", "&7Gives:");
        texts.put("goodsLine", "&8- &f{item}");
        texts.put("limitLine", "&7Limit: &f{used}/{limit}");
        texts.put("resetLine", "&7Resets every {period}");
        texts.put("stockLine", "&7Stock: &f{left}");
        texts.put("lockedLine", "&cNeeds permission");
        texts.put("stackLine", "&8Shift-click for a stack");
        texts.put("previous", "&aPrevious page");
        texts.put("next", "&aNext page");
        texts.put("close", "&cClose");
        texts.put("mode", "&eShowing: {mode}");
        texts.put("modeHint", "&7Click to switch");
        texts.put("modeBuy", "Buy");
        texts.put("modeSell", "Sell");
        texts.put("modeTrade", "Trade");
        texts.put("confirmTitle", "&8Confirm purchase");
        texts.put("confirm", "&aConfirm");
        texts.put("cancel", "&cCancel");
        return Map.copyOf(texts);
    }
}
