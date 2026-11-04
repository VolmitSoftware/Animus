package art.arcane.animus.core.migration;

import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.npc.NpcAppearance;
import art.arcane.animus.core.npc.NpcInventory;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Maps Citizens equipment, inventory, hologram, pose, armor stand, owner and nameplate data to definition fields.
 * The original traits stay in the review copy; anything that does not map exactly is reported with its source path.
 */
final class CitizensPresentation {
    record Facing(double yaw, double pitch) {}
    record Result(JsonObject fields, Optional<Facing> facing, List<String> warnings, Set<String> converted) {
        Result { fields = fields.deepCopy(); warnings = List.copyOf(warnings); converted = Set.copyOf(converted); }
        @Override public JsonObject fields() { return fields.deepCopy(); }
    }
    private static final Map<String, EquipmentSlot> SLOTS = Map.of("hand", EquipmentSlot.MAIN_HAND, "off_hand", EquipmentSlot.OFF_HAND,
            "helmet", EquipmentSlot.HEAD, "chestplate", EquipmentSlot.CHEST, "leggings", EquipmentSlot.LEGS, "boots", EquipmentSlot.FEET,
            "body", EquipmentSlot.BODY, "saddle", EquipmentSlot.SADDLE);
    private static final Set<String> ITEM_BASICS = Set.of("type", "type_key", "type_namespace", "id", "amount", "durability", "data");
    private static final Set<String> HOLOGRAM_EXTRAS = Set.of("lineHeight", "viewRange", "verticalViewRange", "hologramUpdateRate", "default_renderer");
    private static final List<String> STAND_PARTS = List.of("head", "body", "leftArm", "rightArm", "leftLeg", "rightLeg");

    private final String type;
    private final JsonObject traits, record, fields = new JsonObject();
    private final List<String> warnings = new ArrayList<>();
    private final Set<String> converted = new HashSet<>();
    private Facing facing;

    private CitizensPresentation(String type, JsonObject traits, JsonObject record) { this.type = type; this.traits = traits; this.record = record; }

    static Result map(String type, JsonObject traits, JsonObject record) {
        CitizensPresentation mapping = new CitizensPresentation(type, traits, record);
        mapping.equipment(); mapping.inventory(); mapping.hologram(); mapping.pose(); mapping.armorStand(); mapping.facing(); mapping.owner(); mapping.nameplate();
        List<String> sorted = new ArrayList<>(mapping.warnings);
        Collections.sort(sorted);
        return new Result(mapping.fields, Optional.ofNullable(mapping.facing), sorted, mapping.converted);
    }

    private void equipment() {
        JsonObject equipment = object(traits.get("equipment"));
        if (equipment == null) return;
        Map<EquipmentSlot, JsonObject> shown = new EnumMap<>(EquipmentSlot.class);
        for (Map.Entry<String, JsonElement> entry : equipment.entrySet()) {
            String key = entry.getKey(), path = "/traits/equipment/" + key;
            boolean cosmetic = key.startsWith("cosmetic_");
            EquipmentSlot slot = SLOTS.get(cosmetic ? key.substring("cosmetic_".length()) : key);
            if (slot == null) { warnings.add(path + ": unknown Citizens equipment slot; kept for review"); continue; }
            String realKey = cosmetic ? key.substring("cosmetic_".length()) : key;
            if (!cosmetic && equipment.has("cosmetic_" + key) && item(equipment.get("cosmetic_" + key), "/traits/equipment/cosmetic_" + key, new ArrayList<>(), new HashSet<>()).isPresent()) continue;
            Optional<JsonObject> item = item(entry.getValue(), path, warnings, converted);
            if (item.isEmpty()) continue;
            if (cosmetic) warnings.add(path + ": cosmetic item shown in " + slot.key() + (equipment.has(realKey) ? " instead of /traits/equipment/" + realKey : ""));
            shown.put(slot, item.get());
        }
        if (shown.isEmpty()) return;
        JsonObject result = new JsonObject();
        shown.forEach((EquipmentSlot slot, JsonObject item) -> result.add(slot.key(), item));
        fields.add("equipment", result);
    }

    private void inventory() {
        JsonObject inventory = object(traits.get("inventory"));
        if (inventory == null) return;
        TreeMap<Integer, JsonObject> slots = new TreeMap<>();
        for (Map.Entry<String, JsonElement> entry : inventory.entrySet()) {
            String path = "/traits/inventory/" + entry.getKey();
            if (!entry.getKey().matches("0|[1-9][0-9]{0,3}")) { warnings.add(path + ": not a slot number; kept for review"); continue; }
            int slot = Integer.parseInt(entry.getKey());
            if (slot >= NpcInventory.MAX_SIZE) { warnings.add(path + ": slot outside 0.." + (NpcInventory.MAX_SIZE - 1) + "; kept for review"); continue; }
            item(entry.getValue(), path, warnings, converted).ifPresent((JsonObject item) -> slots.put(slot, item));
        }
        if (slots.isEmpty()) return;
        JsonObject contents = new JsonObject();
        slots.forEach((Integer slot, JsonObject item) -> contents.add(Integer.toString(slot), item));
        JsonObject result = new JsonObject();
        result.addProperty("size", Math.max(9, (slots.lastKey() / 9 + 1) * 9));
        result.add("slots", contents);
        fields.add("inventory", result);
    }

    private Optional<JsonObject> item(JsonElement value, String path, List<String> problems, Set<String> paths) {
        JsonObject source = object(value);
        if (source == null) { problems.add(path + ": item is not a mapping; kept for review"); return Optional.empty(); }
        String raw;
        if (text(source.get("type_key")) != null) raw = Objects.requireNonNullElse(text(source.get("type_namespace")), "minecraft") + ":" + text(source.get("type_key"));
        else raw = text(source.get("type")) != null ? text(source.get("type")) : text(source.get("id"));
        if (raw == null) { problems.add(path + ": item has no type; kept for review"); return Optional.empty(); }
        String id = raw.toLowerCase(Locale.ROOT);
        if (!id.contains(":")) id = "minecraft:" + id;
        if (id.equals("minecraft:air")) return Optional.empty();
        if (!NpcItem.validId(id)) { problems.add(path + ": item type is not a namespaced id; kept for review"); return Optional.empty(); }
        long count = integer(source.get("amount"), 1);
        if (count < 1) { problems.add(path + ": item count below 1; kept for review"); return Optional.empty(); }
        if (count > NpcItem.MAX_COUNT) { problems.add(path + ": count " + count + " shown as " + NpcItem.MAX_COUNT); count = NpcItem.MAX_COUNT; }
        JsonObject item = new JsonObject();
        item.addProperty("id", id);
        item.addProperty("count", count);
        long damage = integer(source.has("durability") ? source.get("durability") : source.get("data"), 0);
        if (damage > 0) item.addProperty("components", "[damage=" + damage + "]");
        boolean extra = source.keySet().stream().anyMatch((String key) -> !ITEM_BASICS.contains(key));
        if (extra) {
            if (JsonDocuments.write(source).getBytes(StandardCharsets.UTF_8).length > NpcItem.MAX_IMPORTED_BYTES)
                problems.add(path + ": item data beyond id, count and damage is too large to copy; kept for review");
            else {
                item.add("imported", source.deepCopy());
                problems.add(path + ": item data beyond id, count and damage kept in imported; it is not shown");
            }
        }
        for (String key : source.keySet()) if (ITEM_BASICS.contains(key)) paths.add(path + "/" + key);
        return Optional.of(item);
    }

    private void hologram() {
        JsonObject hologram = object(traits.get("hologramtrait"));
        if (hologram == null) return;
        JsonElement declared = hologram.get("lines");
        List<Map.Entry<String, JsonElement>> entries = new ArrayList<>();
        if (declared != null && declared.isJsonArray()) {
            for (int i = 0; i < declared.getAsJsonArray().size(); i++) entries.add(Map.entry(Integer.toString(i), declared.getAsJsonArray().get(i)));
        } else if (object(declared) != null) {
            for (Map.Entry<String, JsonElement> entry : object(declared).entrySet()) if (entry.getKey().matches("0|[1-9][0-9]{0,8}")) entries.add(entry);
            entries.sort(Comparator.comparingInt((Map.Entry<String, JsonElement> entry) -> Integer.parseInt(entry.getKey())));
        }
        boolean extras = hologram.keySet().stream().anyMatch(HOLOGRAM_EXTRAS::contains);
        JsonArray lines = new JsonArray();
        for (Map.Entry<String, JsonElement> entry : entries) {
            String path = "/traits/hologramtrait/lines/" + entry.getKey();
            String line = text(entry.getValue());
            String linePath = path;
            JsonObject structured = object(entry.getValue());
            if (structured != null) {
                for (String key : structured.keySet()) if (!key.equals("text") && !key.isEmpty()) extras = true;
                String key = structured.has("text") ? "text" : "";
                line = text(structured.get(key));
                linePath = path + "/" + key;
            }
            if (line == null) { warnings.add(path + ": line has no text; kept for review"); continue; }
            if (lines.size() == NpcAppearance.MAX_LINES) { warnings.add("/traits/hologramtrait/lines: only the first " + NpcAppearance.MAX_LINES + " lines are shown"); break; }
            String cleaned = line.codePoints().map((int point) -> Character.isISOControl(point) ? ' ' : point)
                    .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
            if (!cleaned.equals(line)) warnings.add(path + ": control characters shown as spaces");
            if (cleaned.length() > NpcAppearance.MAX_TEXT) {
                int end = Character.isHighSurrogate(cleaned.charAt(NpcAppearance.MAX_TEXT - 1)) ? NpcAppearance.MAX_TEXT - 1 : NpcAppearance.MAX_TEXT;
                cleaned = cleaned.substring(0, end);
                warnings.add(path + ": shortened to " + NpcAppearance.MAX_TEXT + " characters");
            }
            lines.add(cleaned);
            converted.add(linePath);
        }
        if (extras) warnings.add("/traits/hologramtrait: line margins, line height, view ranges and renderer settings are kept for review; lines use uniform spacing");
        if (lines.isEmpty()) return;
        JsonObject result = new JsonObject();
        result.add("lines", lines);
        fields.add("hologram", result);
    }

    private void pose() {
        boolean player = type.equals("minecraft:player");
        JsonObject sneak = object(traits.get("sneak"));
        boolean sneaking = sneak != null && Boolean.TRUE.equals(bool(sneak.get("sneaking")));
        JsonObject sit = object(traits.get("sittrait"));
        boolean sitting = sit != null && sit.get("sittingAt") != null && !sit.get("sittingAt").isJsonNull();
        if (sneaking && !player) { warnings.add("/traits/sneak: sneaking shows only on player bodies; kept for review"); sneaking = false; }
        if (sitting && !player) { warnings.add("/traits/sittrait: sitting shows only on player bodies; kept for review"); sitting = false; }
        if (sitting && object(traits.get("mounttrait")) != null && object(traits.get("mounttrait")).has("mountedon")) {
            warnings.add("/traits/sittrait: a mounted NPC rides instead of sitting; kept for review"); sitting = false;
        }
        if (sneaking && sitting) warnings.add("/traits/sneak: sitting is shown instead of sneaking");
        if (sitting) {
            fields.addProperty("pose", "sitting");
            converted.add("/traits/sittrait/sittingAt");
            object(sit.get("sittingAt")).keySet().forEach((String key) -> converted.add("/traits/sittrait/sittingAt/" + key));
        } else if (sneaking) {
            fields.addProperty("pose", "sneaking");
            converted.add("/traits/sneak/sneaking");
        }
    }

    private void armorStand() {
        JsonObject stand = object(traits.get("armorstandtrait"));
        if (stand == null) return;
        if (!type.equals("minecraft:armor_stand")) { warnings.add("/traits/armorstandtrait: applies only to armor stand bodies; kept for review"); return; }
        JsonObject look = new JsonObject();
        look.addProperty("small", flag(stand, "small", false));
        look.addProperty("arms", flag(stand, "hasarms", true));
        look.addProperty("basePlate", flag(stand, "hasbaseplate", true));
        look.addProperty("invisible", !flag(stand, "visible", true));
        for (String key : List.of("small", "hasarms", "hasbaseplate", "visible")) if (bool(stand.get(key)) != null) converted.add("/traits/armorstandtrait/" + key);
        for (String part : STAND_PARTS) {
            if (!stand.has(part) || stand.get(part).isJsonNull()) continue;
            JsonObject angle = object(stand.get(part));
            String path = "/traits/armorstandtrait/" + part;
            Double x = angle == null ? null : number(angle.get("x")), y = angle == null ? null : number(angle.get("y")), z = angle == null ? null : number(angle.get("z"));
            if (x == null || y == null || z == null) { warnings.add(path + ": angle not readable; kept for review"); continue; }
            JsonArray degrees = new JsonArray();
            for (double radians : new double[]{x, y, z}) degrees.add(degrees(radians));
            look.add(part, degrees);
            for (String axis : List.of("x", "y", "z")) converted.add(path + "/" + axis);
        }
        List<String> kept = new ArrayList<>();
        for (String key : List.of("marker", "gravity")) if (stand.has(key)) kept.add(key);
        if (!kept.isEmpty()) warnings.add("/traits/armorstandtrait: " + String.join(" and ", kept) + (kept.size() == 1 ? " is" : " are") + " kept for review");
        fields.add("armorStand", look);
    }

    private static double degrees(double radians) {
        double degrees = Math.toDegrees(radians) % 360;
        return BigDecimal.valueOf(degrees).setScale(3, RoundingMode.HALF_UP).doubleValue();
    }

    private void facing() {
        JsonObject poses = object(traits.get("poses"));
        if (poses == null || text(poses.get("defaultPose")) == null) return;
        String wanted = text(poses.get("defaultPose")).toLowerCase(Locale.ROOT);
        List<JsonElement> list = new ArrayList<>();
        JsonElement declared = poses.get("list");
        if (declared != null && declared.isJsonArray()) declared.getAsJsonArray().forEach(list::add);
        else if (object(declared) != null) for (Map.Entry<String, JsonElement> entry : object(declared).entrySet()) list.add(entry.getValue());
        for (JsonElement entry : list) {
            String[] parts = Objects.requireNonNullElse(text(entry), "").split(";");
            if (parts.length < 3 || !parts[0].toLowerCase(Locale.ROOT).equals(wanted)) continue;
            try {
                double yaw = Double.parseDouble(parts[1]), pitch = Double.parseDouble(parts[2]);
                if (!Double.isFinite(yaw) || !Double.isFinite(pitch)) break;
                facing = new Facing(yaw, Math.max(-90, Math.min(90, pitch)));
                converted.add("/traits/poses/defaultPose");
                warnings.add("/traits/poses: default pose " + wanted + " sets the facing; other named poses are kept for review");
                return;
            } catch (NumberFormatException invalid) { break; }
        }
        warnings.add("/traits/poses: default pose " + wanted + " is not in the list; kept for review");
    }

    private void owner() {
        JsonObject owner = object(traits.get("owner"));
        if (owner == null || !owner.has("uuid")) return;
        String uuid = text(owner.get("uuid"));
        try {
            if (uuid == null || !UUID.fromString(uuid).toString().equals(uuid)) throw new IllegalArgumentException();
            fields.addProperty("owner", uuid);
            converted.add("/traits/owner/uuid");
        } catch (IllegalArgumentException invalid) { warnings.add("/traits/owner: no player UUID; kept for review"); }
    }

    private void nameplate() {
        JsonObject metadata = object(record.get("metadata"));
        if (metadata == null || !metadata.has("nameplate-visible")) return;
        String value = Objects.requireNonNullElse(text(metadata.get("nameplate-visible")), "").toLowerCase(Locale.ROOT);
        if (value.equals("false")) {
            JsonObject nameplate = new JsonObject();
            nameplate.addProperty("visible", false);
            fields.add("nameplate", nameplate);
            converted.add("/metadata/nameplate-visible");
        } else if (value.equals("true")) converted.add("/metadata/nameplate-visible");
        else warnings.add("/metadata/nameplate-visible: " + (value.equals("hover") ? "hover-only nameplates show always" : "unknown value; kept for review"));
    }

    private static JsonObject object(JsonElement value) { return value != null && value.isJsonObject() ? value.getAsJsonObject() : null; }
    private static String text(JsonElement value) { return value != null && value.isJsonPrimitive() ? value.getAsString() : null; }
    private static Boolean bool(JsonElement value) {
        if (value == null || !value.isJsonPrimitive()) return null;
        if (value.getAsJsonPrimitive().isBoolean()) return value.getAsBoolean();
        String text = value.getAsString();
        return text.equals("true") ? Boolean.TRUE : text.equals("false") ? Boolean.FALSE : null;
    }
    private static boolean flag(JsonObject object, String key, boolean fallback) { return Objects.requireNonNullElse(bool(object.get(key)), fallback); }
    private static Double number(JsonElement value) {
        if (value == null || !value.isJsonPrimitive()) return null;
        try { double number = Double.parseDouble(value.getAsString()); return Double.isFinite(number) ? number : null; }
        catch (NumberFormatException invalid) { return null; }
    }
    private static long integer(JsonElement value, long fallback) {
        Double number = number(value);
        return number == null ? fallback : (long) Math.floor(number);
    }
}
