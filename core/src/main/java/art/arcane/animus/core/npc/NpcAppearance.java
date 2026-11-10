package art.arcane.animus.core.npc;

import art.arcane.animus.core.platform.MinecraftIds;
import art.arcane.animus.core.presentation.BodyRules;
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
                            NpcPose pose, Optional<ArmorStandLook> armorStand, HologramStyle hologramStyle, BodyTraits traits) {
    public static final int MAX_TEXT = 256, MAX_LINES = 16;
    public static final NpcAppearance PLAIN = new NpcAppearance(Map.of(), List.of(), true, NpcPose.STANDING, Optional.empty());

    public NpcAppearance {
        equipment = Map.copyOf(equipment); hologram = List.copyOf(hologram);
        Objects.requireNonNull(pose); Objects.requireNonNull(armorStand); Objects.requireNonNull(hologramStyle); Objects.requireNonNull(traits);
    }

    /** An appearance with the default hologram style and body traits. */
    public NpcAppearance(Map<EquipmentSlot, NpcItem> equipment, List<String> hologram, boolean nameplateVisible, NpcPose pose, Optional<ArmorStandLook> armorStand) {
        this(equipment, hologram, nameplateVisible, pose, armorStand, HologramStyle.DEFAULT, BodyTraits.DEFAULT);
    }

    /** This appearance with other attributes, as a runtime attribute change presents it. */
    /** The same appearance showing other hologram lines, such as the lines filled for one viewer. */
    public NpcAppearance withHologram(List<String> lines) {
        return new NpcAppearance(equipment, lines, nameplateVisible, pose, armorStand, hologramStyle, traits);
    }

    /** The same appearance with a speech bubble: {@code text} as the top hologram line. */
    public NpcAppearance withBubble(String text) {
        List<String> lines = new ArrayList<>();
        lines.add(text);
        lines.addAll(hologram);
        List<HologramStyle.Margin> margins = new ArrayList<>();
        margins.add(HologramStyle.Margin.NONE);
        margins.addAll(hologramStyle.margins().size() == hologram.size() ? hologramStyle.margins() : java.util.Collections.nCopies(hologram.size(), HologramStyle.Margin.NONE));
        return new NpcAppearance(equipment, lines, nameplateVisible, pose, armorStand, hologramStyle.withMargins(margins), traits);
    }

    public NpcAppearance withAttributes(NpcAttributes attributes) {
        return new NpcAppearance(equipment, hologram, nameplateVisible, pose, armorStand, hologramStyle, traits.withAttributes(attributes));
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
                String body = MinecraftIds.normalize(type);
                if (!BodyRules.equipmentSlots(body).contains(slot.get())) {
                    errors.add("$.equipment." + entry.getKey() + ": " + BodyRules.slotRefusal(body, slot.get()));
                    continue;
                }
                NpcItem.parse(entry.getValue(), "$.equipment." + entry.getKey(), errors).ifPresent((NpcItem item) -> equipment.put(slot.get(), item));
            }
        }
        List<String> lines = new ArrayList<>();
        HologramStyle style = HologramStyle.DEFAULT;
        if (definition.has("hologram")) {
            JsonElement value = definition.get("hologram");
            if (!value.isJsonObject()) errors.add("$.hologram: expected object");
            else {
                JsonObject hologram = value.getAsJsonObject();
                for (String key : hologram.keySet()) if (!HologramStyle.FIELDS.contains(key)) errors.add("$.hologram." + key + ": unknown field");
                JsonElement declared = hologram.get("lines");
                List<HologramStyle.Margin> margins = new ArrayList<>();
                if (declared == null || !declared.isJsonArray()) errors.add("$.hologram.lines: expected array");
                else if (declared.getAsJsonArray().size() > MAX_LINES) errors.add("$.hologram.lines: expected at most " + MAX_LINES + " lines");
                else {
                    JsonArray array = declared.getAsJsonArray();
                    for (int i = 0; i < array.size(); i++) {
                        String path = "$.hologram.lines[" + i + "]";
                        Optional<String> line = HologramStyle.line(array.get(i), path, margins, errors);
                        if (line.isEmpty()) continue;
                        if (!displayText(line.get())) errors.add(path + ": expected at most " + MAX_TEXT + " characters without control characters");
                        else lines.add(line.get());
                    }
                }
                style = HologramStyle.read(hologram, margins, errors);
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
        BodyTraits traits = BodyTraits.read(definition, errors);
        if (lines.size() != style.margins().size()) style = HologramStyle.DEFAULT;
        return new NpcAppearance(equipment, lines, nameplate, pose, armorStand, style, traits);
    }
}
