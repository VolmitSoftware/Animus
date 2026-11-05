package art.arcane.animus.core.migration;

import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Shopkeepers item stacks as definition items. Current saves write {@code DataVersion}, {@code id}, {@code count} and
 * {@code components}, each component as the SNBT text Minecraft's give command reads, so they become
 * {@code components} exactly; a removed component ({@code !minecraft:food}) stays removed. Old Bukkit-serialized
 * items ({@code type}, {@code amount}, {@code meta}) convert their type and amount; their meta, and anything else
 * that cannot be written exactly, is kept in {@code imported} and the item is not exact.
 */
final class ShopkeepersItems {
    private static final String COMPONENT = "!?[a-z0-9_.-]+(?::[a-z0-9_./-]+)?";
    private static final Set<String> MODERN = Set.of("DataVersion", "id", "count", "components");
    private static final Set<String> LEGACY = Set.of("==", "v", "type", "amount", "meta");

    /**
     * {@code exact} is true when the item is written exactly as the save holds it. {@code amount} is the stack size,
     * which may exceed what the icon shows. {@code dataVersion} is the Minecraft data version the item was saved by.
     */
    record Item(NpcItem item, int amount, boolean exact, boolean components, Optional<Integer> dataVersion, List<String> notes) {
        Item { notes = List.copyOf(notes); }
    }

    private ShopkeepersItems() {}

    /** Empty when the value is no item at all: no id, an unknown id, or a count below 1. */
    static Optional<Item> convert(JsonElement value) {
        if (value == null || value.isJsonNull()) return Optional.empty();
        if (value.isJsonPrimitive()) {
            Optional<String> id = id(value.getAsString());
            return id.map((String valid) -> new Item(new NpcItem(valid, 1, Optional.empty(), Optional.empty()), 1, true, false, Optional.empty(), List.of()));
        }
        if (!value.isJsonObject()) return Optional.empty();
        JsonObject source = value.getAsJsonObject();
        if (source.has("id")) return modern(source);
        if (source.has("type")) return legacy(source);
        return Optional.empty();
    }

    private static Optional<Item> modern(JsonObject source) {
        Optional<String> id = scalar(source.get("id")).flatMap(ShopkeepersItems::id);
        if (id.isEmpty()) return Optional.empty();
        Optional<Integer> count = source.has("count") ? integer(source.get("count")) : Optional.of(1);
        if (count.isEmpty() || count.get() < 1) return Optional.empty();
        List<String> notes = new ArrayList<>();
        JsonObject unconverted = new JsonObject();
        boolean exact = true;
        for (Map.Entry<String, JsonElement> entry : source.entrySet())
            if (!MODERN.contains(entry.getKey())) { unconverted.add(entry.getKey(), entry.getValue().deepCopy()); exact = false; notes.add("item field " + entry.getKey() + " is kept in imported"); }
        Optional<String> components = Optional.empty();
        boolean hasComponents = false;
        JsonElement componentValue = source.get("components");
        if (componentValue != null && !componentValue.isJsonNull()) {
            if (!componentValue.isJsonObject()) { unconverted.add("components", componentValue.deepCopy()); exact = false; notes.add("components are not a mapping; kept in imported"); }
            else if (!componentValue.getAsJsonObject().isEmpty()) {
                hasComponents = true;
                List<String> parts = new ArrayList<>();
                boolean writable = true;
                for (Map.Entry<String, JsonElement> component : componentValue.getAsJsonObject().entrySet()) {
                    String key = component.getKey();
                    JsonElement snbt = component.getValue();
                    if (!key.matches(COMPONENT) || snbt == null || !snbt.isJsonPrimitive() || !snbt.getAsJsonPrimitive().isString()) { writable = false; break; }
                    parts.add(key.startsWith("!") ? key : key + "=" + snbt.getAsString());
                }
                String joined = "[" + String.join(",", parts) + "]";
                if (writable && joined.length() <= NpcItem.MAX_COMPONENTS) components = Optional.of(joined);
                else {
                    unconverted.add("components", componentValue.deepCopy());
                    exact = false;
                    notes.add(writable ? "components exceed " + NpcItem.MAX_COMPONENTS + " characters; kept in imported" : "a component is not SNBT text; components kept in imported");
                }
            }
        }
        int amount = count.get();
        if (amount > NpcItem.MAX_COUNT) notes.add("count " + amount + " shows as " + NpcItem.MAX_COUNT + "; trades still use " + amount);
        Optional<Integer> dataVersion = source.has("DataVersion") ? integer(source.get("DataVersion")) : Optional.empty();
        NpcItem item = new NpcItem(id.get(), Math.min(amount, NpcItem.MAX_COUNT), components, imported(unconverted));
        return Optional.of(new Item(item, amount, exact, hasComponents, dataVersion, notes));
    }

    private static Optional<Item> legacy(JsonObject source) {
        Optional<String> id = scalar(source.get("type")).flatMap(ShopkeepersItems::id);
        if (id.isEmpty()) return Optional.empty();
        Optional<Integer> count = source.has("amount") ? integer(source.get("amount")) : Optional.of(1);
        if (count.isEmpty() || count.get() < 1) return Optional.empty();
        List<String> notes = new ArrayList<>();
        JsonObject unconverted = new JsonObject();
        boolean exact = true;
        for (Map.Entry<String, JsonElement> entry : source.entrySet()) {
            if (entry.getKey().equals("meta") || !LEGACY.contains(entry.getKey())) {
                unconverted.add(entry.getKey(), entry.getValue().deepCopy());
                exact = false;
            }
        }
        if (!exact) notes.add("old Bukkit item data is kept in imported; load and save the shop once with a current Shopkeepers to convert it exactly");
        int amount = count.get();
        Optional<Integer> dataVersion = source.has("v") ? integer(source.get("v")) : Optional.empty();
        NpcItem item = new NpcItem(id.get(), Math.min(amount, NpcItem.MAX_COUNT), Optional.empty(), imported(unconverted));
        return Optional.of(new Item(item, amount, exact, source.has("meta"), dataVersion, notes));
    }

    /** Kept data larger than an item can carry stays in the staged source.yml only. */
    private static Optional<JsonObject> imported(JsonObject unconverted) {
        if (unconverted.isEmpty()) return Optional.empty();
        JsonObject imported = new JsonObject();
        imported.addProperty("source", "Shopkeepers");
        imported.add("unconverted", unconverted);
        if (JsonDocuments.write(imported).getBytes(StandardCharsets.UTF_8).length > NpcItem.MAX_IMPORTED_BYTES) {
            JsonObject pointer = new JsonObject();
            pointer.addProperty("source", "Shopkeepers");
            pointer.addProperty("unconverted", "too large for an item; kept in source.yml");
            return Optional.of(pointer);
        }
        return Optional.of(imported);
    }

    /** A Minecraft item id from {@code minecraft:stone}, {@code stone} or {@code STONE}; air is no item. */
    static Optional<String> id(String text) {
        String lowered = text.trim().toLowerCase(Locale.ROOT);
        String id = lowered.contains(":") ? lowered : "minecraft:" + lowered;
        if (!NpcItem.validId(id) || id.equals("minecraft:air") || id.equals("minecraft:cave_air") || id.equals("minecraft:void_air")) return Optional.empty();
        return Optional.of(id);
    }

    static Optional<String> scalar(JsonElement value) {
        return value != null && value.isJsonPrimitive() ? Optional.of(value.getAsString()) : Optional.empty();
    }

    static Optional<Integer> integer(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return Optional.empty();
        try { return Optional.of(value.getAsBigDecimal().intValueExact()); }
        catch (ArithmeticException | NumberFormatException invalid) { return Optional.empty(); }
    }
}
