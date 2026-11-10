package art.arcane.animus.bukkit.integration.mythic;

import art.arcane.animus.core.integration.MythicBinding;
import art.arcane.animus.core.integration.NpcTemplates;
import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.npc.NpcAppearance;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.presentation.BodyRules;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The definition fields a new NPC takes from a Mythic mob: its body type, display name, equipment, glow and baby form,
 * its disguise and Model Engine model through {@link LookHooks}, and the {@code mythic:mob} module that runs its skills.
 * A body type Animus cannot show becomes a player body, and the template's notes say so.
 */
final class MobLook {
    private static final Pattern MODEL = Pattern.compile("(?<![A-Za-z])model\\{[^}]*?(?<![A-Za-z])(?:mid|modelid|m)=([A-Za-z0-9_.\\-]+)", Pattern.CASE_INSENSITIVE);
    private static final Map<String, EquipmentSlot> SLOT_NAMES = Map.of("HAND", EquipmentSlot.MAIN_HAND, "MAINHAND", EquipmentSlot.MAIN_HAND,
            "OFFHAND", EquipmentSlot.OFF_HAND, "HEAD", EquipmentSlot.HEAD, "CHEST", EquipmentSlot.CHEST, "LEGS", EquipmentSlot.LEGS,
            "FEET", EquipmentSlot.FEET, "BODY", EquipmentSlot.BODY, "SADDLE", EquipmentSlot.SADDLE);
    /** Mythic's numbered equipment slots. */
    private static final Map<String, EquipmentSlot> SLOT_NUMBERS = Map.of("0", EquipmentSlot.MAIN_HAND, "1", EquipmentSlot.FEET, "2", EquipmentSlot.LEGS,
            "3", EquipmentSlot.CHEST, "4", EquipmentSlot.HEAD, "5", EquipmentSlot.OFF_HAND, "-1", EquipmentSlot.OFF_HAND);

    private MobLook() {}

    /**
     * @param items  a Mythic item or vanilla material by name, as a definition item
     * @param bodies the body types the server shows; the mob keeps its own type when it is one of them
     */
    static NpcTemplates.Template template(MobSource mob, Function<String, Optional<NpcItem>> items, LookHooks hooks, java.util.Set<String> bodies) {
        List<String> notes = new ArrayList<>();
        JsonObject fields = new JsonObject();
        String written = mob.type().trim().toLowerCase(Locale.ROOT);
        boolean babyType = written.startsWith("baby_");
        String type = "minecraft:" + (babyType ? written.substring("baby_".length()) : written);
        if (!bodies.contains(type)) {
            notes.add(type + " cannot be shown; it uses a player body");
            type = BodyRules.PLAYER;
        }
        fields.addProperty("type", type);
        mob.display().map(String::strip).filter((String name) -> !name.isEmpty()).ifPresent((String name) -> {
            String shown = name.length() > NpcAppearance.MAX_TEXT ? name.substring(0, NpcAppearance.MAX_TEXT) : name;
            if (NpcAppearance.displayText(shown)) fields.addProperty("name", shown);
        });
        JsonObject equipment = new JsonObject();
        for (String line : mob.equipment()) equip(line, type, items, equipment, notes);
        if (!equipment.isEmpty()) fields.add("equipment", equipment);
        if (truthy(mob.options().get("Glowing"))) fields.addProperty("glowing", true);
        if ((babyType || baby(mob.options())) && BodyRules.canBeBaby(type)) fields.addProperty("baby", true);
        mob.disguise().map(String::strip).filter((String spec) -> !spec.isEmpty()).flatMap((String spec) -> hooks.disguise(fields, spec)).ifPresent(notes::add);
        model(mob.skills()).flatMap((String model) -> hooks.model(fields, model)).ifPresent(notes::add);
        JsonObject binding = new JsonObject();
        binding.addProperty("mob", mob.name());
        JsonObject modules = fields.has("modules") && fields.get("modules").isJsonObject() ? fields.getAsJsonObject("modules") : new JsonObject();
        modules.add(MythicBinding.MODULE, binding);
        fields.add("modules", modules);
        return new NpcTemplates.Template(fields, notes);
    }

    /**
     * Whether a Mythic mechanic of this class is Model Engine's own: those act on a real entity's model, and an NPC's
     * model is drawn from {@code animus:modelengine} instead, so they are not run with the NPC as caster.
     */
    static boolean drawnByAnimus(String mechanicClass) { return mechanicClass.startsWith("com.ticxo.modelengine."); }

    /** The Model Engine model the mob's {@code model{mid=...}} skill shows, if any. */
    static Optional<String> model(List<String> skills) {
        for (String line : skills) {
            Matcher matcher = MODEL.matcher(line);
            if (matcher.find()) return Optional.of(matcher.group(1));
        }
        return Optional.empty();
    }

    private static void equip(String line, String type, Function<String, Optional<NpcItem>> items, JsonObject equipment, List<String> notes) {
        String entry = line.strip();
        if (entry.startsWith("- ")) entry = entry.substring(2).strip();
        if (entry.isEmpty()) return;
        String item;
        EquipmentSlot slot;
        int space = entry.lastIndexOf(' '), colon = entry.lastIndexOf(':');
        if (space > 0 && SLOT_NAMES.containsKey(entry.substring(space + 1).toUpperCase(Locale.ROOT))) {
            item = entry.substring(0, space).strip();
            slot = SLOT_NAMES.get(entry.substring(space + 1).toUpperCase(Locale.ROOT));
        } else if (colon > 0 && SLOT_NUMBERS.containsKey(entry.substring(colon + 1).strip())) {
            item = entry.substring(0, colon).strip();
            slot = SLOT_NUMBERS.get(entry.substring(colon + 1).strip());
        } else { notes.add("equipment " + entry + " has no slot Animus reads"); return; }
        if (!BodyRules.equipmentSlots(type).contains(slot)) { notes.add(type + " bodies do not show " + slot.key() + "; " + item + " left out"); return; }
        Optional<NpcItem> found = items.apply(item);
        if (found.isEmpty()) { notes.add("no item named " + item + "; " + slot.key() + " left empty"); return; }
        equipment.add(slot.key(), found.get().toJson());
    }

    /** Mythic's baby options: {@code Baby: true}, {@code Adult: false} or a negative {@code Age}. */
    private static boolean baby(Map<String, String> options) {
        if (truthy(options.get("Baby"))) return true;
        if ("false".equalsIgnoreCase(options.getOrDefault("Adult", "").strip())) return true;
        try { return options.containsKey("Age") && Integer.parseInt(options.get("Age").strip()) < 0; }
        catch (NumberFormatException notNumber) { return false; }
    }

    private static boolean truthy(String value) { return value != null && value.strip().equalsIgnoreCase("true"); }
}
