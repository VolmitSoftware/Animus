package art.arcane.animus.core.hire;

import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcItem;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * {@code modules["animus:hire"]} in an NPC definition: what hiring this NPC as a bodyguard costs and how long one hire
 * lasts. A click opens the hiring window; the hirer pays an economy {@code price}, the {@code items}, or both, and the NPC
 * guards them until the time runs out or they dismiss it. A renewal adds another {@code durationMinutes}.
 */
public record HireTerms(BigDecimal price, List<NpcItem> items, Duration duration, Optional<String> permission) {
    public static final String MODULE = "animus:hire";
    public static final int MAXIMUM_ITEMS = 6;
    public static final long MAXIMUM_MINUTES = 10_080;
    private static final Set<String> FIELDS = Set.of("enabled", "price", "items", "durationMinutes", "permission");

    public HireTerms {
        Objects.requireNonNull(price); items = List.copyOf(items); Objects.requireNonNull(duration); Objects.requireNonNull(permission);
    }

    public boolean free() { return price.signum() == 0 && items.isEmpty(); }

    public static Optional<HireTerms> of(NpcDefinition definition) { return parse(definition.json()); }

    /** Empty when the module is absent or disabled; throws with the exact field when it is malformed. */
    public static Optional<HireTerms> parse(JsonObject definition) {
        if (!definition.has("modules") || !definition.get("modules").isJsonObject()) return Optional.empty();
        JsonElement value = definition.getAsJsonObject("modules").get(MODULE);
        if (value == null) return Optional.empty();
        String path = "$.modules[\"" + MODULE + "\"]";
        if (!value.isJsonObject()) throw new IllegalArgumentException(path + ": expected object");
        JsonObject module = value.getAsJsonObject();
        for (String key : module.keySet()) if (!FIELDS.contains(key)) throw new IllegalArgumentException(path + "." + key + ": unknown field");
        if (module.has("enabled") && !bool(module.get("enabled"), path + ".enabled")) return Optional.empty();
        BigDecimal price = BigDecimal.ZERO;
        if (module.has("price")) {
            JsonElement amount = module.get("price");
            if (!amount.isJsonPrimitive() || !amount.getAsJsonPrimitive().isNumber() || amount.getAsBigDecimal().signum() < 0
                    || amount.getAsBigDecimal().compareTo(BigDecimal.valueOf(1_000_000_000)) > 0)
                throw new IllegalArgumentException(path + ".price: expected a number 0..1000000000");
            price = amount.getAsBigDecimal();
        }
        List<NpcItem> items = new ArrayList<>();
        if (module.has("items")) {
            if (!module.get("items").isJsonArray()) throw new IllegalArgumentException(path + ".items: expected an array of items");
            JsonArray array = module.getAsJsonArray("items");
            if (array.size() > MAXIMUM_ITEMS) throw new IllegalArgumentException(path + ".items: at most " + MAXIMUM_ITEMS + " items");
            for (int index = 0; index < array.size(); index++) {
                List<String> errors = new ArrayList<>();
                Optional<NpcItem> item = NpcItem.parse(array.get(index), path + ".items[" + index + "]", errors);
                if (item.isEmpty()) throw new IllegalArgumentException(errors.getFirst());
                items.add(item.get());
            }
        }
        long minutes = 60;
        if (module.has("durationMinutes")) {
            JsonElement number = module.get("durationMinutes");
            try {
                if (!number.isJsonPrimitive() || !number.getAsJsonPrimitive().isNumber()) throw new ArithmeticException();
                minutes = number.getAsBigDecimal().longValueExact();
                if (minutes < 1 || minutes > MAXIMUM_MINUTES) throw new ArithmeticException();
            } catch (ArithmeticException | NumberFormatException invalid) {
                throw new IllegalArgumentException(path + ".durationMinutes: expected integer 1.." + MAXIMUM_MINUTES);
            }
        }
        Optional<String> permission = Optional.empty();
        if (module.has("permission")) {
            JsonElement node = module.get("permission");
            if (!node.isJsonPrimitive() || !node.getAsJsonPrimitive().isString() || !node.getAsString().matches("[a-z0-9_.-]{1,128}"))
                throw new IllegalArgumentException(path + ".permission: expected a permission node such as animus.hire.guard");
            permission = Optional.of(node.getAsString());
        }
        return Optional.of(new HireTerms(price, items, Duration.ofMinutes(minutes), permission));
    }

    private static boolean bool(JsonElement value, String path) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException(path + ": expected true or false");
        return value.getAsBoolean();
    }
}
