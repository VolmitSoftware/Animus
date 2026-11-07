package art.arcane.animus.core.npc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Settings only one body type has, each in a definition field named after the body: a horse's coat, a llama's
 * variant and chest, a frog's variant, an armadillo's state and a dragon's resting phase. A setting left out keeps
 * the client's own default, so nothing is sent for it.
 *
 * @param field   the definition field the settings came from, empty for none
 * @param choices chosen values by setting key
 * @param flags   boolean settings that are on
 */
public record BodyVariant(String field, Map<String, String> choices, Set<String> flags) {
    public static final BodyVariant NONE = new BodyVariant("", Map.of(), Set.of());

    /** One setting: its key, and its values in wire order, or no values for a boolean. */
    public record Setting(String key, List<String> values, String description) {
        public boolean flag() { return values.isEmpty(); }
    }

    /** A body type's own definition field and its settings. */
    public record Field(String field, String type, List<Setting> settings, String description) {
        public Optional<Setting> setting(String key) { return settings.stream().filter((Setting setting) -> setting.key().equals(key)).findFirst(); }
    }

    /** Values are in the order of the vanilla enums, so a value's index is its wire ID. */
    public static final Map<String, Field> FIELDS;
    static {
        Map<String, Field> fields = new LinkedHashMap<>();
        fields.put("horse", new Field("horse", "minecraft:horse", List.of(
                new Setting("color", List.of("white", "creamy", "chestnut", "brown", "black", "gray", "dark_brown"), "The coat colour. white by default."),
                new Setting("markings", List.of("none", "white", "white_field", "white_dots", "black_dots"), "White or black patterns on the coat. none by default.")),
                "minecraft:horse bodies only: the coat. The saddle and armour are the saddle and body equipment slots."));
        fields.put("llama", new Field("llama", "minecraft:llama", List.of(
                new Setting("variant", List.of("creamy", "white", "brown", "gray"), "The wool colour. creamy by default."),
                new Setting("chest", List.of(), "Carry a chest on its back.")),
                "minecraft:llama bodies only. The carpet it wears is the body equipment slot."));
        fields.put("frog", new Field("frog", "minecraft:frog", List.of(
                new Setting("variant", List.of("temperate", "warm", "cold"), "temperate (orange) by default, warm (white) or cold (green).")),
                "minecraft:frog bodies only."));
        fields.put("armadillo", new Field("armadillo", "minecraft:armadillo", List.of(
                new Setting("state", List.of("idle", "rolling", "scared", "unrolling"), "scared shows it rolled up. idle by default.")),
                "minecraft:armadillo bodies only."));
        fields.put("dragon", new Field("dragon", "minecraft:ender_dragon", List.of(
                new Setting("phase", List.of("hovering", "sitting"), "How it rests when it is not moving: hovering in the air or sitting with folded wings. "
                        + "By default it sits when something is under it and hovers otherwise.")),
                "minecraft:ender_dragon bodies only. A moving dragon always flaps."));
        FIELDS = java.util.Collections.unmodifiableMap(fields);
    }

    public BodyVariant { java.util.Objects.requireNonNull(field); choices = Map.copyOf(choices); flags = Set.copyOf(flags); }

    /** The wire index of a chosen value, -1 when the setting is left out. */
    public int index(String key) {
        String value = choices.get(key);
        Field owner = FIELDS.get(field);
        if (value == null || owner == null) return -1;
        return owner.setting(key).map((Setting setting) -> setting.values().indexOf(value)).orElse(-1);
    }

    public boolean flag(String key) { return flags.contains(key); }

    /** A horse's packed {@code DATA_ID_TYPE_VARIANT}: the colour in the low byte, the markings in the next. */
    public static int horseVariant(BodyVariant variant) {
        return Math.max(0, variant.index("color")) | Math.max(0, variant.index("markings")) << 8;
    }

    static BodyVariant read(JsonObject definition, String type, List<String> errors) {
        BodyVariant result = NONE;
        for (Field field : FIELDS.values()) {
            if (!definition.has(field.field())) continue;
            String path = "$." + field.field();
            if (!field.type().equals(type)) { errors.add(path + ": available only for " + field.type() + " bodies"); continue; }
            JsonElement value = definition.get(field.field());
            if (!value.isJsonObject()) { errors.add(path + ": expected object"); continue; }
            Map<String, String> choices = new TreeMap<>();
            Set<String> flags = new TreeSet<>();
            for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
                Optional<Setting> setting = field.setting(entry.getKey());
                String at = path + "." + entry.getKey();
                if (setting.isEmpty()) { errors.add(at + ": unknown field"); continue; }
                JsonElement declared = entry.getValue();
                if (setting.get().flag()) {
                    if (!declared.isJsonPrimitive() || !declared.getAsJsonPrimitive().isBoolean()) errors.add(at + ": expected boolean");
                    else if (declared.getAsBoolean()) flags.add(entry.getKey());
                } else if (declared.isJsonPrimitive() && declared.getAsJsonPrimitive().isString() && setting.get().values().contains(declared.getAsString())) {
                    choices.put(entry.getKey(), declared.getAsString());
                } else errors.add(at + ": expected " + either(setting.get().values()));
            }
            result = new BodyVariant(field.field(), choices, flags);
        }
        return result;
    }

    private static String either(List<String> values) {
        return values.size() == 1 ? values.getFirst() : String.join(", ", values.subList(0, values.size() - 1)) + " or " + values.getLast();
    }
}
