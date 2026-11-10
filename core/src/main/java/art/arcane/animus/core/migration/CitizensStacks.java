package art.arcane.animus.core.migration;

import art.arcane.animus.core.npc.NpcItem;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Items Citizens saves with its item storage (shop costs and results, command item costs, drops) as Animus item
 * objects. An item is exact when every piece of its data has a component here, so it can be taken or matched.
 */
final class CitizensStacks {
    private static final Set<String> BASICS = Set.of("type", "type_key", "type_namespace", "id", "amount", "durability", "data");

    /**
     * @param item {@code id} and, when it has any, {@code components}; never a count
     * @param amount the stack size Citizens saved, at least 1
     * @param inexact why the item cannot be matched exactly, or empty when it can
     */
    record Stack(JsonObject item, int amount, Optional<String> inexact) {
        Stack { item = item.deepCopy(); }
        @Override public JsonObject item() { return item.deepCopy(); }
        /** The item with its count, for an entry that shows or gives a stack. Counts above 99 show as 99. */
        JsonObject counted() {
            JsonObject counted = item.deepCopy();
            counted.addProperty("count", Math.min(amount, NpcItem.MAX_COUNT));
            return counted;
        }
    }

    private CitizensStacks() {}

    /** Empty for air, a missing type or a value that is not an item mapping. */
    static Optional<Stack> read(JsonElement value) {
        if (value == null || !value.isJsonObject()) return Optional.empty();
        JsonObject source = value.getAsJsonObject();
        String raw = text(source.get("type_key")) != null ? Optional.ofNullable(text(source.get("type_namespace"))).orElse("minecraft") + ":" + text(source.get("type_key"))
                : text(source.get("type")) != null ? text(source.get("type")) : text(source.get("id"));
        if (raw == null) return Optional.empty();
        String id = raw.toLowerCase(Locale.ROOT);
        if (!id.contains(":")) id = "minecraft:" + id;
        if (id.equals("minecraft:air") || !NpcItem.validId(id)) return Optional.empty();
        long amount = whole(source.get("amount"), 1);
        if (amount < 1) return Optional.empty();
        JsonObject item = new JsonObject();
        item.addProperty("id", id);
        CitizensItems.Result meta = CitizensItems.read(source, whole(source.has("durability") ? source.get("durability") : source.get("data"), 0));
        Set<String> other = new TreeSet<>();
        for (String key : source.keySet()) if (!BASICS.contains(key) && !key.equals("meta") && !key.equals("editable_components")) other.add(key);
        Optional<String> inexact = Optional.empty();
        if (!meta.readable()) inexact = Optional.of("its item meta could not be read");
        else if (!meta.unmapped().isEmpty()) inexact = Optional.of("item meta " + String.join(", ", new TreeSet<>(meta.unmapped())) + " has no item component here");
        else if (!other.isEmpty()) inexact = Optional.of("item field " + String.join(", ", other) + " has no item component here");
        if (meta.components().isPresent()) {
            if (meta.components().get().length() > NpcItem.MAX_COMPONENTS) inexact = Optional.of("its components are longer than " + NpcItem.MAX_COMPONENTS + " characters");
            else item.addProperty("components", meta.components().get());
        }
        return Optional.of(new Stack(item, (int) Math.min(Integer.MAX_VALUE, amount), inexact));
    }

    /**
     * A money amount rounded to 4 decimals: a whole number as a long, anything else as a double, so it prints as
     * {@code 10} or {@code 2.5}.
     */
    static Number money(double value) {
        java.math.BigDecimal rounded = java.math.BigDecimal.valueOf(value).setScale(4, java.math.RoundingMode.HALF_UP).stripTrailingZeros();
        return rounded.scale() <= 0 ? (Number) rounded.longValueExact() : (Number) rounded.doubleValue();
    }

    private static String text(JsonElement value) { return value != null && value.isJsonPrimitive() ? value.getAsString() : null; }

    private static long whole(JsonElement value, long fallback) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber() && Double.isFinite(value.getAsDouble()) ? (long) Math.floor(value.getAsDouble()) : fallback;
    }
}
