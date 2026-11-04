package art.arcane.animus.core.npc;

import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * One item in a definition. {@code components} is the text that follows the item id in a give command, for example
 * {@code [enchantment_glint_override=true]}. {@code imported} keeps source item data that has no exact equivalent; it is never interpreted.
 */
public record NpcItem(String id, int count, Optional<String> components, Optional<JsonObject> imported) {
    public static final int MAX_COUNT = 99, MAX_COMPONENTS = 4096, MAX_IMPORTED_BYTES = 16384;
    private static final Set<String> FIELDS = Set.of("id", "count", "components", "imported");
    private static final String ID = "[a-z0-9_.-]+:[a-z0-9_./-]+";

    public NpcItem {
        Objects.requireNonNull(id); Objects.requireNonNull(components); Objects.requireNonNull(imported);
        imported = imported.map(JsonObject::deepCopy);
    }
    @Override public Optional<JsonObject> imported() { return imported.map(JsonObject::deepCopy); }

    public static boolean validId(String id) { return id != null && id.length() <= 128 && id.matches(ID); }

    /** Appends exact field errors and returns empty when the value is invalid. */
    static Optional<NpcItem> parse(JsonElement value, String path, List<String> errors) {
        if (value == null || !value.isJsonObject()) { errors.add(path + ": expected item object"); return Optional.empty(); }
        JsonObject item = value.getAsJsonObject();
        int before = errors.size();
        for (String key : item.keySet()) if (!FIELDS.contains(key)) errors.add(path + "." + key + ": unknown field");
        JsonElement id = item.get("id");
        String itemId = id != null && id.isJsonPrimitive() && id.getAsJsonPrimitive().isString() ? id.getAsString() : null;
        if (!validId(itemId)) errors.add(path + ".id: expected namespace:item");
        int count = 1;
        if (item.has("count")) {
            JsonElement countValue = item.get("count");
            try {
                if (!countValue.isJsonPrimitive() || !countValue.getAsJsonPrimitive().isNumber()) throw new ArithmeticException();
                count = countValue.getAsBigDecimal().intValueExact();
                if (count < 1 || count > MAX_COUNT) throw new ArithmeticException();
            } catch (ArithmeticException | NumberFormatException invalid) { errors.add(path + ".count: expected integer 1.." + MAX_COUNT); }
        }
        Optional<String> components = Optional.empty();
        if (item.has("components")) {
            JsonElement text = item.get("components");
            if (!text.isJsonPrimitive() || !text.getAsJsonPrimitive().isString() || text.getAsString().length() > MAX_COMPONENTS
                    || !text.getAsString().startsWith("[") || !text.getAsString().endsWith("]"))
                errors.add(path + ".components: expected [component=value,...] of at most " + MAX_COMPONENTS + " characters");
            else components = Optional.of(text.getAsString());
        }
        Optional<JsonObject> imported = Optional.empty();
        if (item.has("imported")) {
            JsonElement data = item.get("imported");
            if (!data.isJsonObject() || JsonDocuments.write(data).getBytes(StandardCharsets.UTF_8).length > MAX_IMPORTED_BYTES)
                errors.add(path + ".imported: expected an object of at most " + MAX_IMPORTED_BYTES + " bytes");
            else imported = Optional.of(data.getAsJsonObject());
        }
        if (errors.size() != before) return Optional.empty();
        return Optional.of(new NpcItem(itemId, count, components, imported));
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("id", id);
        json.addProperty("count", count);
        components.ifPresent((String text) -> json.addProperty("components", text));
        imported.ifPresent((JsonObject data) -> json.add("imported", data.deepCopy()));
        return json;
    }
}
