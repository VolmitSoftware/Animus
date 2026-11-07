package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.behavior.schema.NpcSchemaGenerator;
import art.arcane.animus.core.combat.CombatSettings;
import art.arcane.animus.core.control.RideSettings;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The settings the combat and riding screens edit. Combat fields come from the NPC schema, so each row's lore is the
 * description the editor shows; values an NPC leaves out read as what {@link CombatSettings} uses instead.
 */
final class CombatFields {
    /** The combat sections with settings to edit, in screen order. */
    static final List<String> SECTIONS = List.of("targeting", "avoidance", "squad", "guard", "pursuit", "melee", "ranged", "defense", "death", "respawn", "greetings");
    private static final Map<String, Kind> SPECIAL = Map.of("guard.player", Kind.YOU, "guard.npc", Kind.NPC, "avoidance.returnPoint", Kind.HERE,
            "death.drops", Kind.DROPS, "respawn.locations", Kind.SPOTS, "melee.weaponDamage", Kind.WEAPONS);
    private static final JsonObject SCHEMA = NpcSchemaGenerator.schema();
    private static final CombatSettings DEFAULTS = CombatSettings.parseModule(new JsonObject());

    private CombatFields() {}

    /**
     * How a row edits its value: toggle, step, cycle or type; {@code YOU} is the viewer's UUID, {@code NPC} a wand pick,
     * {@code HERE} where the viewer stands, {@code OBJECT} a screen of its own, and the last three edit lists from the hand or feet.
     */
    enum Kind { BOOLEAN, NUMBER, INTEGER, CHOICE, TEXT, YOU, NPC, HERE, OBJECT, DROPS, SPOTS, WEAPONS }

    /** One editable setting, by its path inside the module. */
    record Field(List<String> path, String label, Kind kind, double minimum, double maximum, double step, List<String> options, String description, List<Field> children) {
        Field { path = List.copyOf(path); options = List.copyOf(options); children = List.copyOf(children); }
    }

    /** What a settings screen edits: a module, its rows, and the value an NPC that leaves a row out uses. */
    record Settings(String module, String title, List<Field> fields, Fallback fallback) {
        Settings { fields = List.copyOf(fields); }
    }

    @FunctionalInterface interface Fallback { Optional<JsonElement> value(JsonObject document, List<String> path); }

    /** The rows of one combat section, read from the schema. Settings with no in-game editor are left out. */
    static Settings combat(String section) {
        JsonObject node = resolve(property(resolve(property(resolve(property(SCHEMA, "modules")), CombatSettings.MODULE)), section));
        return new Settings(CombatSettings.MODULE, "Combat: " + section, fields(List.of(section), node), CombatFields::effective);
    }

    /** The section's schema description, for the combat screen's buttons. */
    static String description(String section) {
        JsonObject node = property(resolve(property(resolve(property(SCHEMA, "modules")), CombatSettings.MODULE)), section);
        return node.has("description") ? node.get("description").getAsString() : "";
    }

    /** The riding module; its bounds are the ones {@link RideSettings} checks. */
    static Settings riding() {
        List<Field> fields = List.of(
                plain("enabled", "Enabled", Kind.BOOLEAN, "Players with the permission may ride this NPC."),
                plain("reviewAccepted", "Review accepted", Kind.BOOLEAN, "Imported NPCs can be ridden only once this is true."),
                plain("permission", "Permission", Kind.TEXT, "The node a rider needs. " + RideSettings.DEFAULT_PERMISSION + " by default."),
                number("speed", "Speed", Kind.NUMBER, RideSettings.MINIMUM_SPEED, RideSettings.MAXIMUM_SPEED, 0.5, "Blocks a second. " + RideSettings.DEFAULT_SPEED + " by default."),
                number("leash", "Leash", Kind.NUMBER, RideSettings.MINIMUM_LEASH, RideSettings.MAXIMUM_LEASH, 4, "How far from its start a ride may go. " + RideSettings.DEFAULT_LEASH + " by default."),
                number("lifetimeSeconds", "Lifetime seconds", Kind.INTEGER, RideSettings.MINIMUM_LIFETIME_SECONDS, RideSettings.MAXIMUM_LIFETIME_SECONDS, 30,
                        "A ride ends after this long. " + RideSettings.DEFAULT_LIFETIME_SECONDS + " by default."));
        return new Settings(RideSettings.MODULE, "Riding", fields, (JsonObject document, List<String> path) -> Optional.of(switch (path.getFirst()) {
            case "permission" -> new JsonPrimitive(RideSettings.DEFAULT_PERMISSION);
            case "speed" -> new JsonPrimitive(RideSettings.DEFAULT_SPEED);
            case "leash" -> new JsonPrimitive(RideSettings.DEFAULT_LEASH);
            case "lifetimeSeconds" -> new JsonPrimitive(RideSettings.DEFAULT_LIFETIME_SECONDS);
            default -> new JsonPrimitive(false);
        }));
    }

    private static Field plain(String key, String label, Kind kind, String description) { return new Field(List.of(key), label, kind, 0, 0, 0, List.of(), description, List.of()); }

    private static Field number(String key, String label, Kind kind, double minimum, double maximum, double step, String description) {
        return new Field(List.of(key), label, kind, minimum, maximum, step, List.of(), description, List.of());
    }

    private static List<Field> fields(List<String> prefix, JsonObject node) {
        List<Field> fields = new ArrayList<>();
        if (!node.has("properties")) return fields;
        for (Map.Entry<String, JsonElement> entry : node.getAsJsonObject("properties").entrySet()) {
            List<String> path = new ArrayList<>(prefix);
            path.add(entry.getKey());
            JsonObject raw = entry.getValue().getAsJsonObject(), property = resolve(raw);
            String description = raw.has("description") ? raw.get("description").getAsString() : property.has("description") ? property.get("description").getAsString() : "";
            Optional<Kind> kind = kind(String.join(".", path), property);
            if (kind.isEmpty()) continue;
            double minimum = property.has("minimum") ? property.get("minimum").getAsDouble() : 0, maximum = property.has("maximum") ? property.get("maximum").getAsDouble() : 0;
            List<String> options = new ArrayList<>();
            if (property.has("enum")) for (JsonElement option : property.getAsJsonArray("enum")) options.add(option.getAsString());
            List<Field> children = kind.get() == Kind.OBJECT ? fields(path, property) : List.of();
            fields.add(new Field(path, label(entry.getKey()), kind.get(), minimum, maximum, step(kind.get(), maximum - minimum), options, description, children));
        }
        return fields;
    }

    private static Optional<Kind> kind(String key, JsonObject property) {
        if (SPECIAL.containsKey(key)) return Optional.of(SPECIAL.get(key));
        if (property.has("enum")) return Optional.of(Kind.CHOICE);
        String type = property.has("type") && property.get("type").isJsonPrimitive() ? property.get("type").getAsString() : "";
        return switch (type) {
            case "boolean" -> Optional.of(Kind.BOOLEAN);
            case "number" -> Optional.of(Kind.NUMBER);
            case "integer" -> Optional.of(Kind.INTEGER);
            case "string" -> Optional.of(Kind.TEXT);
            case "object" -> property.has("properties") ? Optional.of(Kind.OBJECT) : Optional.empty();
            default -> Optional.empty();
        };
    }

    /** A click moves a number by about a hundredth of its range, rounded to a plain step; sneak makes it ten. */
    static double step(Kind kind, double span) {
        if (kind == Kind.INTEGER) return span <= 20 ? 1 : span <= 300 ? 5 : span <= 3000 ? 20 : 100;
        return span <= 1 ? 0.05 : span <= 16 ? 0.25 : span <= 100 ? 1 : 4;
    }

    /** {@code catchUpDistance} reads as "Catch up distance", {@code npc} as "NPC". */
    static String label(String key) {
        String spaced = key.replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT).replace("npc", "NPC");
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    /** The value combat uses for a path when the NPC's module leaves it out, attributes included. */
    static Optional<JsonElement> effective(JsonObject document, List<String> path) {
        CombatSettings settings;
        try { settings = CombatSettings.parse(document).orElse(DEFAULTS); }
        catch (IllegalArgumentException invalid) { settings = DEFAULTS; }
        Object value = settings;
        for (String key : path) {
            value = component(value, key);
            if (value == null) return Optional.empty();
        }
        return json(value);
    }

    private static Object component(Object value, String key) {
        Object unwrapped = value instanceof Optional<?> optional ? optional.orElse(null) : value;
        if (unwrapped == null || !unwrapped.getClass().isRecord()) return null;
        for (RecordComponent component : unwrapped.getClass().getRecordComponents()) {
            if (!component.getName().equals(key)) continue;
            try { return component.getAccessor().invoke(unwrapped); }
            catch (IllegalAccessException | InvocationTargetException unreadable) { return null; }
        }
        return null;
    }

    private static Optional<JsonElement> json(Object value) {
        return switch (value) {
            case Optional<?> optional -> optional.flatMap(CombatFields::json);
            case Boolean bool -> Optional.of(new JsonPrimitive(bool));
            case Number number -> Optional.of(new JsonPrimitive(number));
            case String text -> Optional.of(new JsonPrimitive(text));
            case UUID uuid -> Optional.of(new JsonPrimitive(uuid.toString()));
            case Enum<?> constant -> Optional.of(new JsonPrimitive(constant.name().toLowerCase(Locale.ROOT)));
            default -> Optional.empty();
        };
    }

    private static JsonObject property(JsonObject node, String key) { return node.getAsJsonObject("properties").getAsJsonObject(key); }

    /** Follows {@code $ref} into the schema's {@code $defs}. */
    private static JsonObject resolve(JsonObject node) {
        JsonObject current = node;
        while (current.has("$ref")) {
            String reference = current.get("$ref").getAsString();
            current = SCHEMA.getAsJsonObject("$defs").getAsJsonObject(reference.substring(reference.lastIndexOf('/') + 1));
        }
        return current;
    }
}
