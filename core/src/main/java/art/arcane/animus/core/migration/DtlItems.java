package art.arcane.animus.core.migration;

import art.arcane.animus.core.npc.NpcItem;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Bukkit-serialized items from dtlTraders files ({@code ==}, {@code v}, {@code type}, {@code amount}, {@code meta}) and
 * the short form ({@code type}, {@code amount}, {@code damage}, {@code name}, {@code lore}) as definition items. Name,
 * lore, enchantments, damage and unbreakable become item components; every other meta field is kept in
 * {@code imported} and reported for review.
 */
final class DtlItems {
    private static final Map<String, String> ENCHANTMENTS = Map.ofEntries(
            Map.entry("DAMAGE_ALL", "sharpness"), Map.entry("DURABILITY", "unbreaking"), Map.entry("DIG_SPEED", "efficiency"),
            Map.entry("LOOT_BONUS_BLOCKS", "fortune"), Map.entry("LOOT_BONUS_MOBS", "looting"), Map.entry("PROTECTION_ENVIRONMENTAL", "protection"),
            Map.entry("ARROW_DAMAGE", "power"), Map.entry("ARROW_INFINITE", "infinity"), Map.entry("ARROW_FIRE", "flame"),
            Map.entry("ARROW_KNOCKBACK", "punch"), Map.entry("PROTECTION_FIRE", "fire_protection"), Map.entry("PROTECTION_FALL", "feather_falling"),
            Map.entry("PROTECTION_EXPLOSIONS", "blast_protection"), Map.entry("PROTECTION_PROJECTILE", "projectile_protection"),
            Map.entry("OXYGEN", "respiration"), Map.entry("WATER_WORKER", "aqua_affinity"), Map.entry("DAMAGE_UNDEAD", "smite"),
            Map.entry("DAMAGE_ARTHROPODS", "bane_of_arthropods"), Map.entry("LUCK", "luck_of_the_sea"));
    private static final Set<String> HANDLED_META = Set.of("==", "meta-type", "display-name", "lore", "enchants", "Damage", "Unbreakable");
    private static final Map<String, Character> COLOURS = Map.ofEntries(Map.entry("black", '0'), Map.entry("dark_blue", '1'), Map.entry("dark_green", '2'),
            Map.entry("dark_aqua", '3'), Map.entry("dark_red", '4'), Map.entry("dark_purple", '5'), Map.entry("gold", '6'), Map.entry("gray", '7'),
            Map.entry("dark_gray", '8'), Map.entry("blue", '9'), Map.entry("green", 'a'), Map.entry("aqua", 'b'), Map.entry("red", 'c'),
            Map.entry("light_purple", 'd'), Map.entry("yellow", 'e'), Map.entry("white", 'f'));

    /** A converted item: the definition item, its real amount and the parts of its meta that were not converted. */
    record Converted(NpcItem item, int amount, Optional<String> name, List<String> lore, boolean review) {}

    private DtlItems() {}

    static Optional<Converted> convert(JsonElement value, String path, ImportReport report) {
        if (value == null || !value.isJsonObject()) { report.field(path, ImportReport.Status.UNSUPPORTED_PRESERVED, "item is not a mapping"); return Optional.empty(); }
        JsonObject item = value.getAsJsonObject();
        if (!item.has("type") || !item.get("type").isJsonPrimitive()) { report.field(path + "/type", ImportReport.Status.UNSUPPORTED_PRESERVED, "item has no type"); return Optional.empty(); }
        String type = item.get("type").getAsString().toLowerCase(Locale.ROOT);
        String id = type.contains(":") ? type : "minecraft:" + type;
        if (!NpcItem.validId(id)) { report.field(path + "/type", ImportReport.Status.UNSUPPORTED_PRESERVED, "item type " + type + " is not an item id"); return Optional.empty(); }
        int amount = 1;
        if (item.has("amount")) {
            try { amount = Math.clamp(item.get("amount").getAsInt(), 1, 2304); }
            catch (RuntimeException invalid) { report.field(path + "/amount", ImportReport.Status.REQUIRES_REVIEW, "amount is not a number; 1 is used"); }
        }
        List<String> components = new ArrayList<>();
        Optional<String> name = Optional.empty();
        List<String> lore = new ArrayList<>();
        JsonObject kept = new JsonObject();
        boolean review = false;
        JsonObject meta = item.has("meta") && item.get("meta").isJsonObject() ? item.getAsJsonObject("meta") : new JsonObject();
        if (item.has("name")) meta.add("display-name", item.get("name"));
        if (item.has("lore")) meta.add("lore", item.get("lore"));
        if (item.has("damage")) meta.add("Damage", item.get("damage"));
        for (String key : item.keySet())
            if (!Set.of("==", "v", "type", "amount", "meta", "name", "lore", "damage").contains(key)) { kept.add(key, item.get(key).deepCopy()); review = true; }
        if (meta.has("display-name")) {
            name = Optional.of(text(meta.get("display-name")));
            components.add("custom_name=" + quote(section(name.get())));
        }
        if (meta.has("lore") && meta.get("lore").isJsonArray()) {
            List<String> quoted = new ArrayList<>();
            for (JsonElement line : meta.getAsJsonArray("lore")) {
                String plain = text(line);
                lore.add(plain);
                quoted.add(quote(section(plain)));
            }
            components.add("lore=[" + String.join(",", quoted) + "]");
        }
        if (meta.has("enchants") && meta.get("enchants").isJsonObject()) {
            List<String> levels = new ArrayList<>();
            for (Map.Entry<String, JsonElement> enchant : meta.getAsJsonObject("enchants").entrySet()) {
                String key = enchant.getKey().contains(":") ? enchant.getKey().toLowerCase(Locale.ROOT)
                        : "minecraft:" + ENCHANTMENTS.getOrDefault(enchant.getKey().toUpperCase(Locale.ROOT), enchant.getKey().toLowerCase(Locale.ROOT));
                levels.add(quote(key) + ":" + enchant.getValue().getAsInt());
            }
            components.add("enchantments={" + String.join(",", levels) + "}");
        }
        if (meta.has("Damage")) components.add("damage=" + meta.get("Damage").getAsInt());
        if (meta.has("Unbreakable") && meta.get("Unbreakable").getAsBoolean()) components.add("unbreakable={}");
        for (Map.Entry<String, JsonElement> entry : meta.entrySet()) {
            if (HANDLED_META.contains(entry.getKey())) continue;
            kept.add(entry.getKey(), entry.getValue().deepCopy());
            review = true;
            report.field(path + "/meta/" + entry.getKey(), ImportReport.Status.REQUIRES_REVIEW, "item data kept in imported; check the item in game");
        }
        String joined = components.isEmpty() ? null : "[" + String.join(",", components) + "]";
        if (joined != null && joined.length() > NpcItem.MAX_COMPONENTS) {
            report.field(path + "/meta", ImportReport.Status.REQUIRES_REVIEW, "item text is too long for components; kept in imported");
            joined = null;
            kept.add("meta", meta.deepCopy());
            review = true;
        }
        JsonObject original = new JsonObject();
        if (review) original.add("unconverted", kept);
        NpcItem converted = new NpcItem(id, Math.clamp(amount, 1, NpcItem.MAX_COUNT), Optional.ofNullable(joined), review ? Optional.of(original) : Optional.empty());
        return Optional.of(new Converted(converted, amount, name, lore, review));
    }

    /** Text from a JSON text component, or the string itself, keeping {@code &} codes as written. */
    static String text(JsonElement value) {
        if (value == null || value.isJsonNull()) return "";
        if (value.isJsonPrimitive()) {
            String raw = value.getAsString();
            String trimmed = raw.trim();
            if (trimmed.startsWith("{") || trimmed.startsWith("[") || (trimmed.startsWith("\"") && trimmed.endsWith("\""))) {
                try { return flatten(JsonParser.parseString(trimmed)); }
                catch (RuntimeException notJson) { return raw; }
            }
            return raw;
        }
        return flatten(value);
    }

    private static String flatten(JsonElement value) {
        if (value.isJsonPrimitive()) return value.getAsString();
        StringBuilder out = new StringBuilder();
        if (value.isJsonArray()) { for (JsonElement part : value.getAsJsonArray()) out.append(flatten(part)); return out.toString(); }
        if (!value.isJsonObject()) return "";
        JsonObject object = value.getAsJsonObject();
        if (object.has("color") && COLOURS.containsKey(object.get("color").getAsString())) out.append('&').append(COLOURS.get(object.get("color").getAsString()));
        else if (object.has("color") && object.get("color").getAsString().matches("#[0-9a-fA-F]{6}")) out.append("&").append(object.get("color").getAsString());
        if (flag(object, "bold")) out.append("&l");
        if (flag(object, "italic")) out.append("&o");
        if (flag(object, "underlined")) out.append("&n");
        if (flag(object, "strikethrough")) out.append("&m");
        if (flag(object, "obfuscated")) out.append("&k");
        if (object.has("text")) out.append(object.get("text").getAsString());
        if (object.has("extra")) for (JsonElement part : object.getAsJsonArray("extra")) out.append(flatten(part));
        return out.toString();
    }

    private static boolean flag(JsonObject object, String key) { return object.has(key) && object.get(key).isJsonPrimitive() && object.get(key).getAsBoolean(); }

    /** {@code &} codes as section codes, which the client draws in item names. */
    private static String section(String text) {
        return text.replaceAll("&#([0-9a-fA-F])([0-9a-fA-F])([0-9a-fA-F])([0-9a-fA-F])([0-9a-fA-F])([0-9a-fA-F])", "§x§$1§$2§$3§$4§$5§$6")
                .replaceAll("&([0-9a-fk-orA-FK-OR])", "§$1");
    }

    private static String quote(String text) { return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }
}
