package art.arcane.animus.core.npc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What a definition declares about how its body looks: equipment, text above it, nameplate visibility, pose and
 * armor stand parts. Every viewer is shown the same thing, and it is restored from the definition after a restart.
 */
public record NpcAppearance(Map<EquipmentSlot, NpcItem> equipment, List<String> hologram, boolean nameplateVisible,
                            NpcPose pose, Optional<ArmorStandLook> armorStand) {
    public static final int MAX_TEXT = 256, MAX_LINES = 16;
    public static final NpcAppearance PLAIN = new NpcAppearance(Map.of(), List.of(), true, NpcPose.STANDING, Optional.empty());

    public NpcAppearance {
        equipment = Map.copyOf(equipment); hologram = List.copyOf(hologram);
        Objects.requireNonNull(pose); Objects.requireNonNull(armorStand);
    }

    /** Names and text lines: at most 256 UTF-16 units, no control characters. */
    public static boolean displayText(String text) {
        return text != null && text.length() <= MAX_TEXT && text.codePoints().noneMatch(Character::isISOControl);
    }

    public static NpcAppearance of(NpcDefinition definition) {
        List<String> errors = new ArrayList<>();
        NpcAppearance appearance = read(definition.json(), errors);
        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("; ", errors));
        return appearance;
    }

    static List<String> validate(JsonObject definition) {
        List<String> errors = new ArrayList<>();
        read(definition, errors);
        return errors;
    }

    private static NpcAppearance read(JsonObject definition, List<String> errors) {
        String type = definition.has("type") && definition.get("type").isJsonPrimitive() ? definition.get("type").getAsString() : "";
        Map<EquipmentSlot, NpcItem> equipment = new EnumMap<>(EquipmentSlot.class);
        if (definition.has("equipment")) {
            JsonElement value = definition.get("equipment");
            if (!value.isJsonObject()) errors.add("$.equipment: expected object");
            else for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
                Optional<EquipmentSlot> slot = EquipmentSlot.fromKey(entry.getKey());
                if (slot.isEmpty()) {
                    errors.add("$.equipment." + entry.getKey() + ": unknown slot; expected main_hand, off_hand, head, chest, legs, feet, body or saddle");
                    continue;
                }
                NpcItem.parse(entry.getValue(), "$.equipment." + entry.getKey(), errors).ifPresent((NpcItem item) -> equipment.put(slot.get(), item));
            }
        }
        List<String> lines = new ArrayList<>();
        if (definition.has("hologram")) {
            JsonElement value = definition.get("hologram");
            if (!value.isJsonObject()) errors.add("$.hologram: expected object");
            else {
                JsonObject hologram = value.getAsJsonObject();
                for (String key : hologram.keySet()) if (!key.equals("lines")) errors.add("$.hologram." + key + ": unknown field");
                JsonElement declared = hologram.get("lines");
                if (declared == null || !declared.isJsonArray()) errors.add("$.hologram.lines: expected array");
                else if (declared.getAsJsonArray().size() > MAX_LINES) errors.add("$.hologram.lines: expected at most " + MAX_LINES + " lines");
                else {
                    JsonArray array = declared.getAsJsonArray();
                    for (int i = 0; i < array.size(); i++) {
                        JsonElement line = array.get(i);
                        if (!line.isJsonPrimitive() || !line.getAsJsonPrimitive().isString()) errors.add("$.hologram.lines[" + i + "]: expected string");
                        else if (!displayText(line.getAsString())) errors.add("$.hologram.lines[" + i + "]: expected at most " + MAX_TEXT + " characters without control characters");
                        else lines.add(line.getAsString());
                    }
                }
            }
        }
        boolean nameplate = true;
        if (definition.has("nameplate")) {
            JsonElement value = definition.get("nameplate");
            if (!value.isJsonObject()) errors.add("$.nameplate: expected object");
            else {
                for (String key : value.getAsJsonObject().keySet()) if (!key.equals("visible")) errors.add("$.nameplate." + key + ": unknown field");
                JsonElement visible = value.getAsJsonObject().get("visible");
                if (visible != null && (!visible.isJsonPrimitive() || !visible.getAsJsonPrimitive().isBoolean())) errors.add("$.nameplate.visible: expected boolean");
                else if (visible != null) nameplate = visible.getAsBoolean();
            }
        }
        NpcPose pose = NpcPose.STANDING;
        if (definition.has("pose")) {
            JsonElement value = definition.get("pose");
            Optional<NpcPose> parsed = value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? NpcPose.fromKey(value.getAsString()) : Optional.empty();
            if (parsed.isEmpty()) errors.add("$.pose: expected standing, sneaking, sleeping, swimming or sitting");
            else if (!parsed.get().availableFor(type)) errors.add("$.pose: " + parsed.get().key() + " is available only for minecraft:player bodies");
            else pose = parsed.get();
        }
        Optional<ArmorStandLook> armorStand = Optional.empty();
        if (definition.has("armorStand")) {
            if (!type.equals("minecraft:armor_stand")) errors.add("$.armorStand: available only for minecraft:armor_stand bodies");
            else armorStand = ArmorStandLook.parse(definition.get("armorStand"), errors);
        }
        return new NpcAppearance(equipment, lines, nameplate, pose, armorStand);
    }
}
