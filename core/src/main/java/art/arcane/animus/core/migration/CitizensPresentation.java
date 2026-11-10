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
    record Result(JsonObject fields, Optional<Facing> facing, List<String> warnings, Set<String> converted, Map<String, CitizensImporter.Note> notes) {
        Result { fields = fields.deepCopy(); warnings = List.copyOf(warnings); converted = Set.copyOf(converted); notes = Map.copyOf(notes); }
        @Override public JsonObject fields() { return fields.deepCopy(); }
    }
    private static final Map<String, EquipmentSlot> SLOTS = Map.of("hand", EquipmentSlot.MAIN_HAND, "off_hand", EquipmentSlot.OFF_HAND,
            "helmet", EquipmentSlot.HEAD, "chestplate", EquipmentSlot.CHEST, "leggings", EquipmentSlot.LEGS, "boots", EquipmentSlot.FEET,
            "body", EquipmentSlot.BODY, "saddle", EquipmentSlot.SADDLE);
    private static final Set<String> ITEM_BASICS = Set.of("type", "type_key", "type_namespace", "id", "amount", "durability", "data");
    /** Citizens renderer settings per hologram line, with the Animus hologram field each becomes. */
    private static final Map<String, String> RENDERER = Map.of("billboard", "billboard", "seeThrough", "seeThrough", "shadowed", "shadowed",
            "interpolationDelay", "interpolationDelay", "interpolationDuration", "interpolationDuration");
    private static final List<String> STAND_PARTS = List.of("head", "body", "leftArm", "rightArm", "leftLeg", "rightLeg");

    private final String type;
    private final JsonObject traits, record, fields = new JsonObject();
    private final List<String> warnings = new ArrayList<>();
    private final Set<String> converted = new HashSet<>();
    private final Map<String, CitizensImporter.Note> notes = new HashMap<>();
    private Facing facing;

    private CitizensPresentation(String type, JsonObject traits, JsonObject record) { this.type = type; this.traits = traits; this.record = record; }

    static Result map(String type, JsonObject traits, JsonObject record) {
        CitizensPresentation mapping = new CitizensPresentation(type, traits, record);
        mapping.equipment(); mapping.inventory(); mapping.hologram(); mapping.pose(); mapping.sleep(); mapping.contents(); mapping.armorStand(); mapping.facing(); mapping.owner(); mapping.nameplate();
        List<String> sorted = new ArrayList<>(mapping.warnings);
        Collections.sort(sorted);
        return new Result(mapping.fields, Optional.ofNullable(mapping.facing), sorted, mapping.converted, mapping.notes);
    }

    private void equipment() {
        JsonObject equipment = object(traits.get("equipment"));
        if (equipment == null) { Map<EquipmentSlot, JsonObject> shown = new EnumMap<>(EquipmentSlot.class); horse(shown); store(shown); return; }
        Map<EquipmentSlot, JsonObject> shown = new EnumMap<>(EquipmentSlot.class);
        for (Map.Entry<String, JsonElement> entry : equipment.entrySet()) {
            String key = entry.getKey(), path = "/traits/equipment/" + key;
            boolean cosmetic = key.startsWith("cosmetic_");
            EquipmentSlot slot = SLOTS.get(cosmetic ? key.substring("cosmetic_".length()) : key);
            if (slot == null) { warnings.add(path + ": unknown Citizens equipment slot; kept for review"); continue; }
            String realKey = cosmetic ? key.substring("cosmetic_".length()) : key;
            if (!cosmetic && equipment.has("cosmetic_" + key) && item(equipment.get("cosmetic_" + key), "/traits/equipment/cosmetic_" + key, new ArrayList<>(), new HashSet<>()).isPresent()) continue;
            Optional<JsonObject> item = shown(entry.getValue(), path, slot);
            if (item.isEmpty()) continue;
            if (cosmetic) warnings.add(path + ": cosmetic item shown in " + slot.key() + (equipment.has(realKey) ? " instead of /traits/equipment/" + realKey : ""));
            shown.put(slot, item.get());
        }
        horse(shown);
        store(shown);
    }

    private void store(Map<EquipmentSlot, JsonObject> shown) {
        if (shown.isEmpty()) return;
        JsonObject result = new JsonObject();
        shown.forEach((EquipmentSlot slot, JsonObject item) -> result.add(slot.key(), item));
        fields.add("equipment", result);
    }

    /** A horse's saddle and armour from HorseModifiers, and Citizens' saddle trait, fill the slots that Equipment left empty. */
    private void horse(Map<EquipmentSlot, JsonObject> shown) {
        Boolean saddled = bool(traits.get("saddle"));
        if (Boolean.TRUE.equals(saddled) && !shown.containsKey(EquipmentSlot.SADDLE)) {
            JsonObject saddle = new JsonObject();
            saddle.addProperty("id", "minecraft:saddle");
            saddle.addProperty("count", 1);
            if (art.arcane.animus.core.presentation.BodyRules.equipmentSlots(type).contains(EquipmentSlot.SADDLE)) { shown.put(EquipmentSlot.SADDLE, saddle); converted.add("/traits/saddle"); }
            else notes.put("/traits/saddle", new CitizensImporter.Note(CitizensImporter.Status.UNSUPPORTED_PRESERVED, "a " + type + " body does not show a saddle"));
        } else if (saddled != null) converted.add("/traits/saddle");
        JsonObject horse = object(traits.get("horsemodifiers"));
        if (horse == null) return;
        for (Map.Entry<String, EquipmentSlot> part : List.of(Map.entry("saddle", EquipmentSlot.SADDLE), Map.entry("armor", EquipmentSlot.BODY))) {
            if (!horse.has(part.getKey()) || horse.get(part.getKey()).isJsonNull()) continue;
            String path = "/traits/horsemodifiers/" + part.getKey();
            if (shown.containsKey(part.getValue())) {
                notes.put(path, new CitizensImporter.Note(CitizensImporter.Status.UNSUPPORTED_PRESERVED, "the Equipment trait already fills this slot"));
                continue;
            }
            shown(horse.get(part.getKey()), path, part.getValue()).ifPresent((JsonObject item) -> shown.put(part.getValue(), item));
        }
    }

    /** The item for {@code slot}, or empty with the item kept when this body does not show that slot. */
    private Optional<JsonObject> shown(JsonElement value, String path, EquipmentSlot slot) {
        List<String> problems = new ArrayList<>();
        Set<String> paths = new HashSet<>();
        Optional<JsonObject> item = item(value, path, problems, paths);
        if (item.isPresent() && !art.arcane.animus.core.presentation.BodyRules.equipmentSlots(type).contains(slot)) {
            notes.put(path, new CitizensImporter.Note(CitizensImporter.Status.UNSUPPORTED_PRESERVED, "a " + type + " body does not show the " + slot.key() + " slot"));
            return Optional.empty();
        }
        warnings.addAll(problems);
        converted.addAll(paths);
        return item;
    }

    private void inventory() {
        JsonElement declared = traits.get("inventory");
        if (declared == null || !(declared.isJsonObject() || declared.isJsonArray())) return;
        List<Map.Entry<String, JsonElement>> entries = new ArrayList<>();
        if (declared.isJsonArray()) for (int i = 0; i < declared.getAsJsonArray().size(); i++) entries.add(Map.entry(Integer.toString(i), declared.getAsJsonArray().get(i)));
        else entries.addAll(declared.getAsJsonObject().entrySet());
        TreeMap<Integer, JsonObject> slots = new TreeMap<>();
        for (Map.Entry<String, JsonElement> entry : entries) {
            String path = "/traits/inventory/" + entry.getKey();
            if (entry.getValue().isJsonNull()) { converted.add(path); continue; }
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
        CitizensItems.Result meta = CitizensItems.read(source, damage);
        boolean shown = true;
        if (meta.components().isPresent() && meta.components().get().length() > NpcItem.MAX_COMPONENTS) {
            notes.put(path + "/meta", new CitizensImporter.Note(CitizensImporter.Status.REQUIRES_REVIEW, "the item's components are longer than "
                    + NpcItem.MAX_COMPONENTS + " characters; the item shows without them"));
            shown = false;
        } else meta.components().ifPresent((String text) -> item.addProperty("components", text));
        Set<String> other = new TreeSet<>();
        for (String key : source.keySet()) if (!ITEM_BASICS.contains(key) && !key.equals("meta") && !key.equals("editable_components")) other.add(key);
        if (source.has("meta")) {
            if (!meta.readable()) {
                notes.put(path + "/meta", new CitizensImporter.Note(CitizensImporter.Status.REQUIRES_REVIEW, "the item meta could not be read; the item shows without it"));
                problems.add(path + ": the item's name, enchantments and other data could not be read, so it shows as a plain " + id + "; set the item again in the editor if it matters");
            } else if (!meta.unmapped().isEmpty())
                notes.put(path + "/meta", new CitizensImporter.Note(CitizensImporter.Status.REQUIRES_REVIEW,
                        "item meta " + String.join(", ", new TreeSet<>(meta.unmapped())) + " has no item component here; kept in the item's imported data"));
            else if (shown) paths.add(path + "/meta");
        }
        if (source.has("editable_components")) notes.put(path + "/editable_components", new CitizensImporter.Note(CitizensImporter.Status.CONVERTED,
                "Citizens' copy of the item name and lore, read with the meta"));
        for (String key : other) notes.put(path + "/" + CitizensBehaviors.pointer(key), new CitizensImporter.Note(CitizensImporter.Status.REQUIRES_REVIEW,
                "item field " + key + " has no item component here; kept in the item's imported data"));
        if (!other.isEmpty() || !meta.readable() || !meta.unmapped().isEmpty() || !shown) {
            if (JsonDocuments.write(source).getBytes(StandardCharsets.UTF_8).length <= NpcItem.MAX_IMPORTED_BYTES) item.add("imported", source.deepCopy());
        }
        for (String key : source.keySet()) if (ITEM_BASICS.contains(key)) paths.add(path + "/" + key);
        return Optional.of(item);
    }

    /**
     * Citizens keeps hologram lines bottom-up: line 0 sits just above the name. Animus lists them top-down, so the
     * order is reversed. Margins and the first line's renderer settings become the hologram's own.
     */
    private void hologram() {
        JsonObject hologram = object(traits.get("hologramtrait"));
        if (hologram == null) return;
        String base = "/traits/hologramtrait";
        JsonElement declared = hologram.get("lines");
        List<Map.Entry<String, JsonElement>> entries = CitizensBehaviors.entries(declared);
        JsonArray lines = new JsonArray();
        JsonObject style = new JsonObject();
        JsonObject first = null;
        List<JsonElement> shown = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : entries) {
            String path = base + "/lines/" + CitizensBehaviors.pointer(entry.getKey());
            String line = text(entry.getValue());
            String linePath = path;
            JsonObject structured = object(entry.getValue());
            double top = 0, bottom = 0;
            if (structured != null) {
                String key = structured.has("text") ? "text" : "";
                line = text(structured.get(key));
                linePath = path + "/" + key;
                JsonObject margin = object(structured.get("margin"));
                if (margin != null) {
                    Double t = number(margin.get("top")), b = number(margin.get("bottom"));
                    if (t != null) { top = clampMargin(t, path + "/margin/top"); converted.add(path + "/margin/top"); }
                    if (b != null) { bottom = clampMargin(b, path + "/margin/bottom"); converted.add(path + "/margin/bottom"); }
                }
                JsonObject renderer = object(structured.get("renderer"));
                if (renderer != null) {
                    if (first == null) first = renderer;
                    for (Map.Entry<String, JsonElement> setting : renderer.entrySet()) {
                        String at = path + "/renderer/" + CitizensBehaviors.pointer(setting.getKey());
                        if (!RENDERER.containsKey(setting.getKey())) {
                            notes.put(at, new CitizensImporter.Note(CitizensImporter.Status.REQUIRES_REVIEW, "renderer setting " + setting.getKey() + " has no hologram equivalent"));
                            continue;
                        }
                        if (renderer == first) continue;
                        if (setting.getValue().equals(first.get(setting.getKey()))) converted.add(at);
                        else notes.put(at, new CitizensImporter.Note(CitizensImporter.Status.REQUIRES_REVIEW, "every hologram line shares one style; the bottom line's " + setting.getKey() + " is used"));
                    }
                }
                for (String field : structured.keySet())
                    if (!Set.of("text", "", "margin", "renderer").contains(field))
                        notes.put(path + "/" + CitizensBehaviors.pointer(field), new CitizensImporter.Note(CitizensImporter.Status.REQUIRES_REVIEW, "line setting " + field + " has no hologram equivalent"));
            }
            if (line == null) { warnings.add(path + ": the line has no text and was left out; add it in the editor if it is needed"); continue; }
            if (lines.size() == NpcAppearance.MAX_LINES) { warnings.add(base + "/lines: only the bottom " + NpcAppearance.MAX_LINES + " lines are shown; move the others into fewer lines"); break; }
            String cleaned = line.codePoints().map((int point) -> Character.isISOControl(point) ? ' ' : point)
                    .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
            if (cleaned.length() > NpcAppearance.MAX_TEXT) {
                int end = Character.isHighSurrogate(cleaned.charAt(NpcAppearance.MAX_TEXT - 1)) ? NpcAppearance.MAX_TEXT - 1 : NpcAppearance.MAX_TEXT;
                cleaned = cleaned.substring(0, end);
                warnings.add(path + ": shortened to " + NpcAppearance.MAX_TEXT + " characters; shorten the line in the editor");
            }
            JsonElement shownLine;
            if (top != 0 || bottom != 0) {
                JsonObject object = new JsonObject();
                object.addProperty("text", cleaned);
                if (top != 0) object.addProperty("marginTop", top);
                if (bottom != 0) object.addProperty("marginBottom", bottom);
                shownLine = object;
            } else shownLine = new com.google.gson.JsonPrimitive(cleaned);
            shown.add(shownLine);
            lines.add(cleaned);
            converted.add(linePath);
        }
        if (first != null) for (Map.Entry<String, JsonElement> setting : first.entrySet()) {
            String field = RENDERER.get(setting.getKey());
            if (field == null) continue;
            String at = base + "/lines/" + entries.get(firstIndex(entries)).getKey() + "/renderer/" + CitizensBehaviors.pointer(setting.getKey());
            JsonElement value = setting.getValue();
            if (field.equals("billboard")) {
                String billboard = text(value) == null ? "" : text(value).toLowerCase(Locale.ROOT);
                if (!art.arcane.animus.core.npc.HologramStyle.BILLBOARDS.contains(billboard)) {
                    notes.put(at, new CitizensImporter.Note(CitizensImporter.Status.REQUIRES_REVIEW, "billboard " + text(value) + " is not one Animus knows"));
                    continue;
                }
                if (!billboard.equals("center")) style.addProperty("billboard", billboard);
            } else if (field.startsWith("interpolation")) {
                Double ticks = number(value);
                if (ticks == null) continue;
                long whole = Math.max(0, Math.min(art.arcane.animus.core.npc.HologramStyle.MAX_INTERPOLATION, (long) Math.floor(ticks)));
                if (whole != 0) style.addProperty(field, whole);
            } else {
                Boolean flag = bool(value);
                if (flag == null) continue;
                if (flag) style.addProperty(field, true);
            }
            converted.add(at);
        }
        Double height = number(hologram.get("lineHeight"));
        if (height != null) {
            if (height >= 0 && height <= art.arcane.animus.core.npc.HologramStyle.MAX_LINE_HEIGHT) style.addProperty("lineHeight", height);
            else if (height >= 0) {
                style.addProperty("lineHeight", art.arcane.animus.core.npc.HologramStyle.MAX_LINE_HEIGHT);
                warnings.add(base + "/lineHeight: lines more than 4 blocks apart became 4 apart; lower lineHeight in the editor if that matters");
            }
            converted.add(base + "/lineHeight");
        }
        Double range = number(hologram.get("viewRange"));
        if (range != null) {
            if (range >= 1) style.addProperty("viewRange", Math.min(art.arcane.animus.core.npc.HologramStyle.MAX_VIEW_RANGE, range));
            converted.add(base + "/viewRange");
        }
        if (hologram.has("hologramUpdateRate")) notes.put(base + "/hologramUpdateRate", new CitizensImporter.Note(CitizensImporter.Status.CONVERTED,
                "Animus hologram text has no placeholders to refresh"));
        if (hologram.has("verticalViewRange")) notes.put(base + "/verticalViewRange", new CitizensImporter.Note(CitizensImporter.Status.REQUIRES_REVIEW,
                "holograms are hidden by distance only, not by height"));
        if (hologram.has("default_renderer")) notes.put(base + "/default_renderer", new CitizensImporter.Note(CitizensImporter.Status.REQUIRES_REVIEW,
                "Animus draws every hologram line as text"));
        if (lines.isEmpty()) return;
        JsonArray ordered = new JsonArray();
        for (int index = shown.size() - 1; index >= 0; index--) ordered.add(shown.get(index));
        JsonObject result = new JsonObject();
        result.add("lines", ordered);
        for (Map.Entry<String, JsonElement> entry : style.entrySet()) result.add(entry.getKey(), entry.getValue());
        fields.add("hologram", result);
    }

    private static int firstIndex(List<Map.Entry<String, JsonElement>> entries) {
        for (int index = 0; index < entries.size(); index++) {
            JsonObject line = object(entries.get(index).getValue());
            if (line != null && object(line.get("renderer")) != null) return index;
        }
        return 0;
    }

    private double clampMargin(double margin, String path) {
        double limit = art.arcane.animus.core.npc.HologramStyle.MAX_MARGIN;
        if (Math.abs(margin) <= limit) return margin;
        warnings.add(path + ": margins beyond 4 blocks became 4; adjust the line in the editor if that matters");
        return margin < 0 ? -limit : limit;
    }

    private void pose() {
        boolean player = type.equals("minecraft:player");
        JsonObject sneak = object(traits.get("sneak"));
        JsonObject extension = object(traits.get("sneaking"));
        String sneakPath = sneak != null && Boolean.TRUE.equals(bool(sneak.get("sneaking"))) ? "/traits/sneak/sneaking"
                : extension != null && Boolean.TRUE.equals(bool(extension.get("sneaking"))) ? "/traits/sneaking/sneaking" : null;
        boolean sneaking = sneakPath != null;
        for (String path : List.of("/traits/sneak/sneaking", "/traits/sneaking/sneaking")) {
            JsonObject owner = object(traits.get(path.split("/")[2]));
            if (owner != null && Boolean.FALSE.equals(bool(owner.get("sneaking")))) converted.add(path);
        }
        JsonObject sit = object(traits.get("sittrait"));
        boolean sitting = sit != null && sit.get("sittingAt") != null && !sit.get("sittingAt").isJsonNull();
        if (sneaking && !player) {
            notes.put(sneakPath, new CitizensImporter.Note(CitizensImporter.Status.UNSUPPORTED_PRESERVED, "sneaking shows only on player bodies"));
            sneaking = false;
        }
        if (sitting && !player) { warnings.add("/traits/sittrait: sitting shows only on player bodies; kept for review"); sitting = false; }
        if (sitting && object(traits.get("mounttrait")) != null && object(traits.get("mounttrait")).has("mountedon")) {
            warnings.add("/traits/sittrait: a mounted NPC rides instead of sitting; kept for review"); sitting = false;
        }
        if (sneaking && sitting) notes.put(sneakPath, new CitizensImporter.Note(CitizensImporter.Status.UNSUPPORTED_PRESERVED, "the NPC sits, which is shown instead of sneaking"));
        if (sitting) {
            fields.addProperty("pose", "sitting");
            converted.add("/traits/sittrait/sittingAt");
            object(sit.get("sittingAt")).keySet().forEach((String key) -> converted.add("/traits/sittrait/sittingAt/" + key));
        } else if (sneaking) {
            fields.addProperty("pose", "sneaking");
            converted.add(sneakPath);
        }
    }

    /** Citizens' entity pose and sleep traits, on a player body that neither sits nor sneaks. */
    private void sleep() {
        boolean player = type.equals("minecraft:player");
        JsonObject sleep = object(traits.get("sleeptrait"));
        if (sleep != null && sleep.has("at") && !sleep.get("at").isJsonNull()) {
            if (!player) notes.put("/traits/sleeptrait", new CitizensImporter.Note(CitizensImporter.Status.UNSUPPORTED_PRESERVED, "sleeping shows only on player bodies"));
            else if (fields.has("pose")) notes.put("/traits/sleeptrait", new CitizensImporter.Note(CitizensImporter.Status.UNSUPPORTED_PRESERVED, "the NPC already sits or sneaks"));
            else {
                fields.addProperty("pose", "sleeping");
                notes.put("/traits/sleeptrait/at", new CitizensImporter.Note(CitizensImporter.Status.CONVERTED, "the NPC sleeps where it stands"));
            }
        }
        JsonObject entity = object(traits.get("entitypose"));
        if (entity == null || !entity.has("pose")) return;
        String path = "/traits/entitypose/pose";
        String pose = Objects.requireNonNullElse(text(entity.get("pose")), "").toLowerCase(Locale.ROOT);
        String shown = switch (pose) {
            case "crouching", "sneaking" -> "sneaking";
            case "sleeping" -> "sleeping";
            case "swimming" -> "swimming";
            case "sitting" -> "sitting";
            case "standing" -> "standing";
            default -> null;
        };
        if (shown == null) { notes.put(path, new CitizensImporter.Note(CitizensImporter.Status.UNSUPPORTED_PRESERVED, "pose " + pose + " has no Animus equivalent")); return; }
        if (shown.equals("standing")) { converted.add(path); return; }
        if (!player) { notes.put(path, new CitizensImporter.Note(CitizensImporter.Status.UNSUPPORTED_PRESERVED, "poses other than standing show only on player bodies")); return; }
        if (fields.has("pose")) { notes.put(path, new CitizensImporter.Note(CitizensImporter.Status.UNSUPPORTED_PRESERVED, "another trait already sets the pose")); return; }
        fields.addProperty("pose", shown);
        converted.add(path);
    }

    private static final List<String> ROTATIONS = List.of("none", "clockwise_45", "clockwise", "clockwise_135", "flipped", "flipped_45", "counter_clockwise", "counter_clockwise_45");

    /**
     * What a block or item body shows: an item frame's item, rotation and facing, and the block or item Citizens keeps in
     * the NPC's item metadata for falling blocks, dropped items and block and item displays.
     */
    private void contents() {
        JsonObject frame = object(traits.get("itemframe"));
        if (frame != null) {
            String field = type.equals("minecraft:item_frame") ? "itemFrame" : type.equals("minecraft:glow_item_frame") ? "glowItemFrame" : null;
            if (field == null) notes.put("/traits/itemframe", new CitizensImporter.Note(CitizensImporter.Status.UNSUPPORTED_PRESERVED, "applies only to item frame bodies, so Citizens did not show it either"));
            else {
                JsonObject settings = new JsonObject();
                if (frame.has("item") && !frame.get("item").isJsonNull()) item(frame.get("item"), "/traits/itemframe/item", warnings, converted).ifPresent((JsonObject item) -> settings.add("item", item));
                String rotation = Objects.requireNonNullElse(text(frame.get("rotation")), "").toLowerCase(Locale.ROOT);
                if (ROTATIONS.contains(rotation)) {
                    if (ROTATIONS.indexOf(rotation) > 0) settings.addProperty("rotation", ROTATIONS.indexOf(rotation));
                    converted.add("/traits/itemframe/rotation");
                }
                String facing = Objects.requireNonNullElse(text(frame.get("facing")), "").toLowerCase(Locale.ROOT);
                if (List.of("down", "up", "north", "south", "west", "east").contains(facing)) { settings.addProperty("facing", facing); converted.add("/traits/itemframe/facing"); }
                if (frame.has("fixed")) notes.put("/traits/itemframe/fixed", new CitizensImporter.Note(CitizensImporter.Status.CONVERTED, "NPC frames never drop their item"));
                if (!settings.isEmpty()) fields.add(field, settings);
            }
        }
        JsonObject metadata = object(record.get("metadata"));
        String material = metadata == null ? null : text(metadata.get("item-type-id"));
        if (material == null) return;
        String path = "/metadata/item-type-id";
        String id = material.toLowerCase(Locale.ROOT);
        if (!id.contains(":")) id = "minecraft:" + id;
        String key = switch (type) {
            case "minecraft:falling_block" -> "fallingBlock";
            case "minecraft:block_display" -> "blockDisplay";
            case "minecraft:item_display" -> "itemDisplay";
            case "minecraft:item" -> "droppedItem";
            default -> null;
        };
        if (key == null || !id.matches(art.arcane.animus.core.npc.BodyVariant.ID)) {
            notes.put(path, new CitizensImporter.Note(CitizensImporter.Status.UNSUPPORTED_PRESERVED, key == null ? "only block and item bodies show an item" : material + " is not an id"));
            return;
        }
        JsonObject settings = new JsonObject();
        if (key.equals("fallingBlock") || key.equals("blockDisplay")) settings.addProperty("block", id);
        else {
            JsonObject item = new JsonObject();
            item.addProperty("id", id);
            long count = Math.max(1, Math.min(NpcItem.MAX_COUNT, integer(metadata.get("item-type-amount"), 1)));
            item.addProperty("count", count);
            settings.add("item", item);
            if (metadata.has("item-type-amount")) converted.add("/metadata/item-type-amount");
        }
        converted.add(path);
        if (metadata.has("item-type-data")) notes.put("/metadata/item-type-data", new CitizensImporter.Note(CitizensImporter.Status.CONVERTED, "legacy data values have no meaning on current versions"));
        fields.add(key, settings);
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
        Boolean gravity = bool(stand.get("gravity"));
        if (gravity != null) {
            if (!gravity) fields.addProperty("gravity", false);
            converted.add("/traits/armorstandtrait/gravity");
        }
        Boolean marker = bool(stand.get("marker"));
        if (marker != null) {
            if (marker) look.addProperty("marker", true);
            converted.add("/traits/armorstandtrait/marker");
        }
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
