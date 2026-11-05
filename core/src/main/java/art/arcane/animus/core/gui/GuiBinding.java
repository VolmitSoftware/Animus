package art.arcane.animus.core.gui;

import art.arcane.animus.core.npc.NpcDefinition;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * {@code modules["animus:gui"]} in an NPC definition: a chest GUI, menu, shop or Gloss menu a click opens, and a
 * hologram that follows the NPC. A sneaking administrator's click keeps its selection meaning and opens nothing.
 */
public record GuiBinding(Optional<Open> open, Optional<Hologram> hologram) {
    public static final String MODULE = "animus:gui";
    public enum Kind { INVENTORY, MENU, SHOP, GLOSS }
    public enum OpenOn { RIGHT, LEFT, BOTH }
    public record Open(Kind kind, String id, OpenOn openOn) {
        public boolean opensOn(boolean attack) { return switch (openOn) { case RIGHT -> !attack; case LEFT -> attack; case BOTH -> true; }; }
    }
    /** {@code offset} is from the NPC's feet; empty puts the hologram above its head. */
    public record Hologram(String id, Optional<double[]> offset) {}

    /** Animus ids; a Gloss menu id is a path such as {@code lobby/main}. */
    public static final String ID = "[a-z0-9_-]+:[a-z0-9_/-]+", GLOSS_ID = "[A-Za-z0-9][A-Za-z0-9._/-]{0,254}";

    public static Optional<GuiBinding> of(NpcDefinition definition) { return parse(definition.json()); }

    /** Throws with the exact field when the module is malformed. */
    public static Optional<GuiBinding> parse(JsonObject definition) {
        if (!definition.has("modules") || !definition.get("modules").isJsonObject()) return Optional.empty();
        JsonElement value = definition.getAsJsonObject("modules").get(MODULE);
        if (value == null) return Optional.empty();
        String path = "$.modules[\"" + MODULE + "\"]";
        if (!value.isJsonObject()) throw new IllegalArgumentException(path + ": expected object");
        JsonObject module = value.getAsJsonObject();
        for (String key : module.keySet()) if (!Set.of("open", "hologram").contains(key)) throw new IllegalArgumentException(path + "." + key + ": unknown field");
        if (module.isEmpty()) throw new IllegalArgumentException(path + ": name an open target, a hologram or both");
        Optional<Open> open = Optional.empty();
        if (module.has("open")) {
            String at = path + ".open";
            if (!module.get("open").isJsonObject()) throw new IllegalArgumentException(at + ": expected object");
            JsonObject target = module.getAsJsonObject("open");
            for (String key : target.keySet()) if (!Set.of("kind", "id", "openOn").contains(key)) throw new IllegalArgumentException(at + "." + key + ": unknown field");
            Kind kind = Kind.valueOf(choice(target, "kind", at, Set.of("inventory", "menu", "shop", "gloss"), "inventory").toUpperCase(Locale.ROOT));
            String id = text(target, "id", at);
            if (!id.matches(kind == Kind.GLOSS ? GLOSS_ID : ID))
                throw new IllegalArgumentException(at + ".id: " + (kind == Kind.GLOSS ? "expected a Gloss menu id such as lobby/main" : "expected an id such as town:menu"));
            OpenOn openOn = OpenOn.valueOf(choice(target, "openOn", at, Set.of("right", "left", "both"), "right").toUpperCase(Locale.ROOT));
            open = Optional.of(new Open(kind, id, openOn));
        }
        Optional<Hologram> hologram = Optional.empty();
        if (module.has("hologram")) {
            String at = path + ".hologram";
            if (!module.get("hologram").isJsonObject()) throw new IllegalArgumentException(at + ": expected object");
            JsonObject bound = module.getAsJsonObject("hologram");
            for (String key : bound.keySet()) if (!Set.of("id", "offset").contains(key)) throw new IllegalArgumentException(at + "." + key + ": unknown field");
            String id = text(bound, "id", at);
            if (!id.matches(ID)) throw new IllegalArgumentException(at + ".id: expected a hologram id such as town:sign");
            Optional<double[]> offset = Optional.empty();
            if (bound.has("offset")) {
                JsonElement vector = bound.get("offset");
                if (!vector.isJsonArray() || vector.getAsJsonArray().size() != 3) throw new IllegalArgumentException(at + ".offset: expected [x, y, z]");
                JsonArray array = vector.getAsJsonArray();
                double[] out = new double[3];
                for (int index = 0; index < 3; index++) {
                    JsonElement number = array.get(index);
                    if (!number.isJsonPrimitive() || !number.getAsJsonPrimitive().isNumber() || !Double.isFinite(number.getAsDouble()) || Math.abs(number.getAsDouble()) > 16)
                        throw new IllegalArgumentException(at + ".offset[" + index + "]: expected a number within 16");
                    out[index] = number.getAsDouble();
                }
                offset = Optional.of(out);
            }
            hologram = Optional.of(new Hologram(id, offset));
        }
        return Optional.of(new GuiBinding(open, hologram));
    }

    private static String text(JsonObject object, String field, String path) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException(path + "." + field + ": expected string");
        return value.getAsString();
    }

    private static String choice(JsonObject object, String field, String path, Set<String> options, String fallback) {
        if (!object.has(field)) return fallback;
        String value = text(object, field, path);
        if (!options.contains(value)) throw new IllegalArgumentException(path + "." + field + ": expected one of " + new java.util.TreeSet<>(options));
        return value;
    }
}
