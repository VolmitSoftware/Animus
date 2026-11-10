package art.arcane.animus.core.behavior.builtin;

import art.arcane.animus.api.Diagnostic;
import art.arcane.animus.core.behavior.builtin.BehaviorPlatform.Cost;
import art.arcane.animus.core.behavior.builtin.BehaviorPlatform.CostItem;
import art.arcane.animus.core.inventory.MatchRule;
import art.arcane.animus.core.npc.NpcItem;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The arguments {@code animus:charge} and {@code animus:can-afford} share: money, experience levels and items. */
final class ChargeArguments {
    static final int MAXIMUM_ITEMS = 27, MAXIMUM_AMOUNT = 6400, MAXIMUM_LEVELS = 100_000;
    static final String SCHEMA = "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{"
            + "\"money\":{\"type\":\"number\",\"minimum\":0,\"maximum\":1000000000000},"
            + "\"levels\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":" + MAXIMUM_LEVELS + "},"
            + "\"items\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":" + MAXIMUM_ITEMS + ",\"items\":{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{"
            + "\"item\":{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":128},\"count\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":99},"
            + "\"components\":{\"type\":\"string\",\"maxLength\":" + NpcItem.MAX_COMPONENTS + "}},\"required\":[\"id\"]},"
            + "\"amount\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":" + MAXIMUM_AMOUNT + "}},\"required\":[\"item\",\"amount\"]}},"
            + "\"match\":{\"enum\":[\"strict\",\"legacy\",\"material\"]},"
            + "\"player\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":36}},"
            + "\"anyOf\":[{\"required\":[\"money\"]},{\"required\":[\"levels\"]},{\"required\":[\"items\"]}]}";

    private ChargeArguments() {}

    /** Refuses a charge that takes nothing, and items that are not item objects. */
    static void check(JsonObject arguments, List<Diagnostic> problems) {
        Cost cost = parse(arguments, problems);
        if (problems.isEmpty() && cost.money().signum() == 0 && cost.levels() == 0 && cost.items().isEmpty())
            problems.add(new Diagnostic(Diagnostic.Severity.ERROR, "charge-empty", "$", "A charge takes money above 0, at least one level or an item"));
    }

    /** The cost the arguments describe. Invalid items are reported and left out. */
    static Cost parse(JsonObject arguments) { return parse(arguments, new ArrayList<>()); }

    private static Cost parse(JsonObject arguments, List<Diagnostic> problems) {
        BigDecimal money = arguments.has("money") ? arguments.get("money").getAsBigDecimal().stripTrailingZeros() : BigDecimal.ZERO;
        int levels = arguments.has("levels") ? arguments.get("levels").getAsInt() : 0;
        MatchRule match = arguments.has("match") ? MatchRule.valueOf(arguments.get("match").getAsString().toUpperCase(Locale.ROOT)) : MatchRule.STRICT;
        List<CostItem> items = new ArrayList<>();
        if (arguments.has("items")) {
            int index = 0;
            for (JsonElement entry : arguments.getAsJsonArray("items")) {
                JsonObject object = entry.getAsJsonObject();
                List<String> errors = new ArrayList<>();
                String path = "$.items[" + index++ + "].item";
                NpcItem.parse(object.get("item"), path, errors).ifPresent((NpcItem item) ->
                        items.add(new CostItem(item.id(), item.components(), object.get("amount").getAsInt(), match)));
                for (String error : errors) problems.add(new Diagnostic(Diagnostic.Severity.ERROR, "argument-syntax", path, error));
            }
        }
        return new Cost(money.signum() > 0 ? money : BigDecimal.ZERO, levels, items);
    }
}
