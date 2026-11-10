package art.arcane.animus.core.npc;

import art.arcane.animus.core.presentation.BodyRules;
import art.arcane.animus.core.presentation.BodySkin;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * A definition's {@code disguise}: disguise text a disguise plugin reads, such as LibsDisguises' {@code zombie setBaby true}
 * or the name of one of its custom disguises. The plugin's integration installs a {@link Resolver} that turns the text
 * into body fields. The effective definition takes those fields over its own and drops the body fields its new body
 * type cannot carry. With no resolver installed, or text the resolver cannot read, the NPC keeps its own body.
 */
public final class NpcDisguise {
    public static final String FIELD = "disguise";
    /** Room for a disguise that carries a whole signed skin. */
    public static final int MAX_LENGTH = 8192;

    /**
     * What one disguise looks like as definition fields.
     *
     * @param fields      top-level definition fields: {@code type}, {@code name}, {@code nameplate}, {@code equipment},
     *                    {@code pose}, {@code armorStand}, the body variant fields, the body trait flags and {@code attributes}
     * @param skin        signed textures for a player body, saved as a skin document
     * @param unsupported disguise options the body cannot show, in the plugin's own words
     */
    public record Body(JsonObject fields, Optional<BodySkin> skin, List<String> unsupported) {
        public Body {
            fields = fields.deepCopy();
            Objects.requireNonNull(skin);
            unsupported = List.copyOf(unsupported);
        }
        @Override public JsonObject fields() { return fields.deepCopy(); }
    }

    /** Reads disguise text. Throws {@link IllegalArgumentException} with the plugin's reason when it cannot. */
    @FunctionalInterface
    public interface Resolver {
        Body resolve(String disguise);
    }

    /**
     * The effective definition with its disguise applied.
     *
     * @param definition the definition to publish: disguised, or the original when the disguise could not apply
     * @param skin       the skin document to save before the definition is published, as {@code path} and text
     * @param notes      options left out and why the disguise did not apply
     */
    public record Applied(JsonObject definition, Optional<Map.Entry<String, String>> skin, List<String> notes) {
        public Applied { definition = definition.deepCopy(); Objects.requireNonNull(skin); notes = List.copyOf(notes); }
        @Override public JsonObject definition() { return definition.deepCopy(); }
    }

    private static volatile Resolver resolver;

    private NpcDisguise() {}

    /** Installs the resolver of the running disguise plugin, replacing any other. */
    public static void install(Resolver next) { resolver = Objects.requireNonNull(next); }

    /** Removes {@code installed} if it is still the resolver. */
    public static void uninstall(Resolver installed) { if (resolver == installed) resolver = null; }

    /** True while a disguise plugin's resolver is installed. */
    public static boolean available() { return resolver != null; }

    /**
     * Reads disguise text into body fields, for any caller that holds disguise text: a definition, an import or another
     * integration such as a mob plugin's disguise option. Empty when no disguise plugin is installed.
     *
     * @throws IllegalArgumentException when the plugin cannot read the text
     */
    public static Optional<Body> resolve(String disguise) {
        Resolver current = resolver;
        return current == null ? Optional.empty() : Optional.of(current.resolve(disguise));
    }

    /** The disguise text a definition declares, if any. */
    public static Optional<String> declared(JsonObject definition) {
        JsonElement value = definition.get(FIELD);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? Optional.of(value.getAsString()) : Optional.empty();
    }

    static List<String> validate(JsonObject definition) {
        if (!definition.has(FIELD)) return List.of();
        Optional<String> text = declared(definition);
        if (text.isEmpty() || text.get().isBlank() || text.get().length() > MAX_LENGTH || text.get().codePoints().anyMatch(Character::isISOControl))
            return List.of("$." + FIELD + ": expected disguise text of 1-" + MAX_LENGTH + " characters without control characters");
        return List.of();
    }

    /** Applies the declared disguise through the installed resolver; a definition without one comes back unchanged. */
    public static Applied apply(JsonObject definition) {
        Optional<String> text = declared(definition);
        Resolver current = resolver;
        if (text.isEmpty() || current == null) return new Applied(definition, Optional.empty(), List.of());
        try { return apply(definition, current.resolve(text.get())); }
        catch (RuntimeException unreadable) {
            return new Applied(definition, Optional.empty(), List.of("disguise not shown: " + (unreadable.getMessage() == null ? unreadable.getClass().getSimpleName() : unreadable.getMessage())));
        }
    }

    /**
     * {@code definition} wearing {@code body}: the body's fields replace the definition's, equipment and attributes slot
     * by slot, then body fields the new type cannot carry are dropped. A combination that still does not validate leaves
     * the definition as it was.
     */
    public static Applied apply(JsonObject definition, Body body) {
        JsonObject disguised = definition.deepCopy();
        JsonObject fields = body.fields();
        for (Map.Entry<String, JsonElement> field : fields.entrySet()) {
            JsonElement current = disguised.get(field.getKey());
            if (Set.of("equipment", NpcAttributes.FIELD).contains(field.getKey()) && field.getValue().isJsonObject() && current != null && current.isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : field.getValue().getAsJsonObject().entrySet()) current.getAsJsonObject().add(entry.getKey(), entry.getValue().deepCopy());
            } else disguised.add(field.getKey(), field.getValue().deepCopy());
        }
        List<String> notes = new ArrayList<>();
        Optional<Map.Entry<String, String>> skin = Optional.empty();
        if (body.skin().isPresent()) {
            JsonObject document = new JsonObject();
            document.addProperty("textureRaw", body.skin().get().textureValue());
            if (body.skin().get().signature() != null) document.addProperty("signature", body.skin().get().signature());
            document.addProperty("label", label(fields));
            String text = JsonDocuments.write(document), hash = JsonDocuments.hash(text);
            disguised.addProperty("skin", hash);
            skin = Optional.of(Map.entry("skins/" + hash + ".json", text));
        }
        String type = disguised.has("type") && disguised.get("type").isJsonPrimitive() ? disguised.get("type").getAsString() : "";
        Set<String> dropped = fit(disguised, type);
        if (!dropped.isEmpty()) notes.add("not shown on a " + type + " body: " + String.join(", ", dropped));
        if (!body.unsupported().isEmpty()) notes.add("disguise options not shown: " + String.join(", ", body.unsupported()));
        List<String> errors = NpcDefinition.validate(disguised);
        if (!errors.isEmpty()) return new Applied(definition, Optional.empty(), List.of("disguise not shown: " + String.join("; ", errors)));
        return new Applied(disguised, skin, notes);
    }

    /** Drops the body fields a body of {@code type} cannot carry and returns their paths. */
    private static Set<String> fit(JsonObject definition, String type) {
        Set<String> dropped = new TreeSet<>();
        JsonElement equipment = definition.get("equipment");
        if (equipment != null && equipment.isJsonObject()) {
            JsonObject slots = equipment.getAsJsonObject();
            for (String slot : new ArrayList<>(slots.keySet())) {
                Optional<EquipmentSlot> known = EquipmentSlot.fromKey(slot);
                if (known.isPresent() && !BodyRules.equipmentSlots(type).contains(known.get())) { slots.remove(slot); dropped.add("equipment." + slot); }
            }
            if (slots.isEmpty()) definition.remove("equipment");
        }
        JsonElement pose = definition.get("pose");
        if (pose != null && pose.isJsonPrimitive() && NpcPose.fromKey(pose.getAsString()).filter((NpcPose known) -> !known.availableFor(type)).isPresent()) {
            definition.remove("pose");
            dropped.add("pose");
        }
        if (definition.has("armorStand") && !type.equals("minecraft:armor_stand")) { definition.remove("armorStand"); dropped.add("armorStand"); }
        for (BodyVariant.Field field : BodyVariant.FIELDS.values())
            if (definition.has(field.field()) && !field.type().equals(type)) { definition.remove(field.field()); dropped.add(field.field()); }
        if (definition.has("skin") && !type.equals(BodyRules.PLAYER)) definition.remove("skin");
        if (definition.has("baby") && !BodyRules.canBeBaby(type)) { definition.remove("baby"); dropped.add("baby"); }
        return dropped;
    }

    /** The skin document's label: the disguise's player name, else its body type. */
    private static String label(JsonObject fields) {
        JsonElement name = fields.get("name");
        if (name != null && name.isJsonPrimitive() && !name.getAsString().isBlank())
            return name.getAsString().substring(0, Math.min(64, name.getAsString().length()));
        return "Disguise";
    }
}
