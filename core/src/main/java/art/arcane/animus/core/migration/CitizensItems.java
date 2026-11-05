package art.arcane.animus.core.migration;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.*;

/**
 * Turns the item meta Citizens stores (a base64 Java object stream of Bukkit's serialized {@code ItemMeta}) into the
 * item components text the server parses, for example {@code [custom_name={"text":"Shield"},base_color="black"]}.
 * Only meta keys with an exact component equivalent are written; the others are returned in {@link Result#unmapped()}.
 */
final class CitizensItems {
    /**
     * @param components the component text, empty when nothing maps
     * @param unmapped serialized meta keys that have no component here
     * @param readable false when the meta exists but is not a readable Bukkit meta map
     */
    record Result(Optional<String> components, Set<String> unmapped, boolean readable) {
        Result { unmapped = Set.copyOf(unmapped); }
    }

    /** Meta keys that only describe the serialization itself. */
    private static final Set<String> BOOKKEEPING = Set.of("==", "meta-type");
    private static final Map<String, String> LEGACY_ENCHANTMENTS = Map.ofEntries(
            Map.entry("PROTECTION_ENVIRONMENTAL", "protection"), Map.entry("PROTECTION_FIRE", "fire_protection"), Map.entry("PROTECTION_FALL", "feather_falling"),
            Map.entry("PROTECTION_EXPLOSIONS", "blast_protection"), Map.entry("PROTECTION_PROJECTILE", "projectile_protection"), Map.entry("OXYGEN", "respiration"),
            Map.entry("WATER_WORKER", "aqua_affinity"), Map.entry("DAMAGE_ALL", "sharpness"), Map.entry("DAMAGE_UNDEAD", "smite"),
            Map.entry("DAMAGE_ARTHROPODS", "bane_of_arthropods"), Map.entry("LOOT_BONUS_MOBS", "looting"), Map.entry("DIG_SPEED", "efficiency"),
            Map.entry("DURABILITY", "unbreaking"), Map.entry("LOOT_BONUS_BLOCKS", "fortune"), Map.entry("ARROW_DAMAGE", "power"),
            Map.entry("ARROW_KNOCKBACK", "punch"), Map.entry("ARROW_FIRE", "flame"), Map.entry("ARROW_INFINITE", "infinity"), Map.entry("LUCK", "luck_of_the_sea"),
            Map.entry("SWEEPING_EDGE", "sweeping_edge"), Map.entry("SWEEPING", "sweeping_edge"));
    private static final String NAMESPACED = "[a-z0-9_.-]+:[a-z0-9_./-]+";

    private final Map<String, String> components = new LinkedHashMap<>();
    private final Set<String> unmapped = new HashSet<>();

    private CitizensItems() {}

    /** Reads one Citizens item; {@code damage} is the durability from its basic fields, used when the meta has none. */
    static Result read(JsonObject item, long damage) {
        CitizensItems mapping = new CitizensItems();
        boolean readable = true;
        JsonObject meta = null;
        if (item.has("meta")) {
            Optional<String> encoded = encoded(item.get("meta"));
            Optional<JsonElement> decoded = encoded.flatMap(JavaSerialized::decode);
            if (decoded.isPresent() && decoded.get().isJsonObject()) meta = decoded.get().getAsJsonObject();
            else readable = false;
        }
        if (meta != null) mapping.meta(meta);
        if (!mapping.components.containsKey("damage") && damage > 0) mapping.components.put("damage", Long.toString(damage));
        mapping.edited(item.get("editable_components"));
        return new Result(mapping.text(), mapping.unmapped, readable);
    }

    /** The base64 text of a {@code meta} value: a string, or Citizens' {@code ""} or {@code encoded-meta} child. */
    private static Optional<String> encoded(JsonElement value) {
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) return Optional.of(value.getAsString());
        if (!value.isJsonObject()) return Optional.empty();
        JsonObject object = value.getAsJsonObject();
        if (object.has("encoded-meta")) return encoded(object.get("encoded-meta"));
        if (object.has("")) return encoded(object.get(""));
        return Optional.empty();
    }

    private static final List<String> ORDER = List.of("damage", "custom_name", "item_name", "lore", "enchantments", "stored_enchantments", "enchantment_glint_override",
            "unbreakable", "repair_cost", "custom_model_data", "item_model", "dyed_color", "base_color", "banner_patterns", "potion_contents", "map_id", "custom_data");

    private Optional<String> text() {
        if (components.isEmpty()) return Optional.empty();
        StringJoiner joined = new StringJoiner(",", "[", "]");
        for (String name : ORDER) if (components.containsKey(name)) joined.add(name + "=" + components.get(name));
        return Optional.of(joined.toString());
    }

    private void meta(JsonObject meta) {
        for (Map.Entry<String, JsonElement> entry : meta.entrySet()) {
            String key = entry.getKey();
            JsonElement value = entry.getValue();
            if (BOOKKEEPING.contains(key)) continue;
            boolean mapped = switch (key) {
                case "display-name" -> put("custom_name", text(value));
                case "item-name" -> put("item_name", text(value));
                case "lore" -> put("lore", lore(value));
                case "enchants" -> put("enchantments", enchantments(value));
                case "stored-enchants" -> put("stored_enchantments", enchantments(value));
                case "enchantment-glint-override" -> put("enchantment_glint_override", bool(value));
                case "Unbreakable" -> bool(value) != null && (!value.getAsBoolean() || put("unbreakable", "{}"));
                case "Damage" -> put("damage", whole(value, 0));
                case "repair-cost" -> put("repair_cost", whole(value, 0));
                case "custom-model-data" -> put("custom_model_data", Optional.ofNullable(whole(value, Integer.MIN_VALUE)).map((String number) -> "{floats:[" + number + "f]}").orElse(null));
                case "item-model" -> put("item_model", namespaced(value));
                case "color" -> put("dyed_color", color(value));
                case "base-color" -> put("base_color", dye(value));
                case "patterns" -> put("banner_patterns", patterns(value));
                case "potion-type", "custom-color" -> true;
                case "map-id" -> put("map_id", whole(value, 0));
                case "PublicBukkitValues" -> put("custom_data", value.isJsonObject() ? "{PublicBukkitValues:" + snbt(value) + "}" : null);
                default -> false;
            };
            if (!mapped) unmapped.add(key);
        }
        if ((meta.has("potion-type") || meta.has("custom-color")) && !put("potion_contents", potion(meta)))
            for (String key : List.of("potion-type", "custom-color")) if (meta.has(key)) unmapped.add(key);
    }

    /** Citizens' own editor fields; when {@code edited} they replace the meta's name and lore. */
    private void edited(JsonElement value) {
        if (value == null || !value.isJsonObject()) return;
        JsonObject edited = value.getAsJsonObject();
        JsonElement flag = edited.get("edited");
        if (flag == null || !flag.isJsonPrimitive() || !flag.getAsJsonPrimitive().isBoolean() || !flag.getAsBoolean()) return;
        JsonElement name = edited.get("display_name");
        if (name != null && name.isJsonPrimitive()) components.put("custom_name", quote(name.getAsString()));
        JsonElement lore = edited.get("lore");
        if (lore != null && lore.isJsonPrimitive()) {
            StringJoiner lines = new StringJoiner(",", "[", "]");
            for (String line : lore.getAsString().split("<br>", -1)) lines.add(quote(line));
            components.put("lore", lines.toString());
        }
    }

    private boolean put(String component, String value) {
        if (value == null) return false;
        components.put(component, value);
        return true;
    }

    /** JSON text components are valid component text as written; any other text becomes a literal string. */
    private static String text(JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return null;
        String text = value.getAsString();
        if (!text.isEmpty() && "{[\"".indexOf(text.charAt(0)) >= 0) return text;
        return quote(text);
    }

    private static String lore(JsonElement value) {
        if (!value.isJsonArray()) return null;
        StringJoiner lines = new StringJoiner(",", "[", "]");
        for (JsonElement line : value.getAsJsonArray()) {
            String text = text(line);
            if (text == null) return null;
            lines.add(text);
        }
        return lines.toString();
    }

    private static String enchantments(JsonElement value) {
        if (!value.isJsonObject()) return null;
        StringJoiner levels = new StringJoiner(",", "{", "}");
        for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
            String key = entry.getKey();
            String id = key.contains(":") ? key : LEGACY_ENCHANTMENTS.getOrDefault(key, key.matches("[A-Z_]+") ? key.toLowerCase(Locale.ROOT) : "");
            if (!id.contains(":")) id = "minecraft:" + id;
            String level = whole(entry.getValue(), 1);
            if (!id.matches(NAMESPACED) || level == null) return null;
            levels.add(quote(id) + ":" + level);
        }
        return levels.toString();
    }

    private static String bool(JsonElement value) {
        return value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() ? Boolean.toString(value.getAsBoolean()) : null;
    }

    /** A whole number at least {@code minimum}, as text. */
    private static String whole(JsonElement value, long minimum) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return null;
        double number = value.getAsDouble();
        if (number != Math.rint(number) || number < minimum || Math.abs(number) > Integer.MAX_VALUE) return null;
        return Long.toString((long) number);
    }

    private static String namespaced(JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return null;
        String id = value.getAsString().toLowerCase(Locale.ROOT);
        if (!id.contains(":")) id = "minecraft:" + id;
        return id.matches(NAMESPACED) ? quote(id) : null;
    }

    /** A Bukkit {@code Color}: its RGB as one integer. */
    private static String color(JsonElement value) {
        if (!value.isJsonObject()) return null;
        JsonObject color = value.getAsJsonObject();
        long rgb = 0;
        for (String channel : List.of("RED", "GREEN", "BLUE")) {
            String part = color.has(channel) ? whole(color.get(channel), 0) : null;
            if (part == null || Long.parseLong(part) > 255) return null;
            rgb = rgb << 8 | Long.parseLong(part);
        }
        return Long.toString(rgb);
    }

    private static String dye(JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || !value.getAsString().matches("[A-Z_]{3,16}")) return null;
        return quote(value.getAsString().toLowerCase(Locale.ROOT));
    }

    private static String patterns(JsonElement value) {
        if (!value.isJsonArray()) return null;
        StringJoiner layers = new StringJoiner(",", "[", "]");
        for (JsonElement layer : value.getAsJsonArray()) {
            if (!layer.isJsonObject()) return null;
            JsonObject pattern = layer.getAsJsonObject();
            String id = pattern.has("pattern") ? namespaced(pattern.get("pattern")) : null;
            String colour = pattern.has("color") ? dye(pattern.get("color")) : null;
            if (id == null || colour == null || pattern.getAsJsonPrimitive("pattern").getAsString().matches("[a-z]{1,3}")) return null;
            layers.add("{pattern:" + id + ",color:" + colour + "}");
        }
        return layers.toString();
    }

    private static String potion(JsonObject meta) {
        StringJoiner contents = new StringJoiner(",", "{", "}");
        if (meta.has("potion-type")) {
            String id = namespaced(meta.get("potion-type"));
            if (id == null) return null;
            contents.add("potion:" + id);
        }
        if (meta.has("custom-color")) {
            String rgb = color(meta.get("custom-color"));
            if (rgb == null) return null;
            contents.add("custom_color:" + rgb);
        }
        return contents.toString();
    }

    /** Persistent data: Bukkit serializes each value as component text already, so it is written as it is. */
    private static String snbt(JsonElement value) {
        if (value.isJsonObject()) {
            StringJoiner entries = new StringJoiner(",", "{", "}");
            for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) entries.add(quote(entry.getKey()) + ":" + snbt(entry.getValue()));
            return entries.toString();
        }
        if (value.isJsonArray()) {
            StringJoiner entries = new StringJoiner(",", "[", "]");
            for (JsonElement entry : value.getAsJsonArray()) entries.add(snbt(entry));
            return entries.toString();
        }
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) return value.getAsString();
        return quote(value.isJsonNull() ? "" : value.toString());
    }

    /** A double-quoted string literal of component text. */
    static String quote(String text) {
        StringBuilder quoted = new StringBuilder(text.length() + 2).append('"');
        for (int index = 0; index < text.length(); index++) {
            char c = text.charAt(index);
            if (c == '"' || c == '\\') quoted.append('\\').append(c);
            else if (c < 0x20) quoted.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            else quoted.append(c);
        }
        return quoted.append('"').toString();
    }
}
