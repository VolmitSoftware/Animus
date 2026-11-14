package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.behavior.schema.NpcSchemaGenerator;
import art.arcane.animus.core.combat.CombatSettings;
import art.arcane.animus.core.combat.TargetCategory;
import art.arcane.animus.core.control.RideSettings;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The settings the combat and riding screens edit. Combat fields come from the NPC schema, so each row's lore is the
 * description the editor shows; values an NPC leaves out read as what {@link CombatSettings} uses instead. Durations
 * the file keeps in ticks read and type as seconds.
 */
final class CombatFields {
    /** The combat sections with settings to edit, in screen order. */
    static final List<String> SECTIONS = List.of("targeting", "avoidance", "squad", "guard", "pursuit", "melee", "ranged", "greetings");
    private static final Map<String, String> ABOUT = Map.of("targeting", "How it finds and keeps a target", "avoidance", "Running from what it avoids",
            "squad", "NPCs that fight together", "guard", "Following and protecting one charge", "pursuit", "Chasing and going back to its post",
            "melee", "Close hits", "ranged", "Shots and throws", "greetings", "What it says to players nearby");
    private static final Map<String, Kind> SPECIAL = Map.of("guard.player", Kind.YOU, "guard.npc", Kind.NPC, "avoidance.returnPoint", Kind.HERE,
            "melee.weaponDamage", Kind.WEAPONS);
    private static final String TICKS = "Ticks";
    private static final double TICKS_PER_SECOND = 20;
    private static final Pattern DEFAULT = Pattern.compile("^(\\d+) by default\\.");
    private static final JsonObject SCHEMA = NpcSchemaGenerator.schema();
    private static final CombatSettings DEFAULTS = CombatSettings.parse(withCombat()).orElseThrow();

    /** What the Attacks control writes as the targets list: one of four category sets, nothing else. */
    record Attacks(String label, Set<TargetCategory> categories) {
        Attacks { categories = Set.copyOf(categories); }
    }

    static final List<Attacks> ATTACKS = List.of(new Attacks("Monsters", Set.of(TargetCategory.MONSTERS)), new Attacks("Players", Set.of(TargetCategory.PLAYERS)),
            new Attacks("Monsters and players", Set.of(TargetCategory.MONSTERS, TargetCategory.PLAYERS)), new Attacks("Nobody", Set.of()));

    private CombatFields() {}

    /** The Attacks choice a targets list matches, or empty when it holds anything besides one of the four category sets. */
    static Optional<Attacks> attacks(JsonObject targets) {
        if (!targets.keySet().stream().allMatch("categories"::equals)) return Optional.empty();
        Set<TargetCategory> categories = EnumSet.noneOf(TargetCategory.class);
        JsonElement listed = targets.get("categories");
        if (listed != null) {
            if (!listed.isJsonArray()) return Optional.empty();
            for (JsonElement category : listed.getAsJsonArray()) {
                Optional<TargetCategory> known = category.isJsonPrimitive() ? TargetCategory.parse(category.getAsString()) : Optional.empty();
                if (known.isEmpty()) return Optional.empty();
                categories.add(known.get());
            }
        }
        return ATTACKS.stream().filter((Attacks each) -> each.categories().equals(categories)).findFirst();
    }

    /** The targets list a choice writes; Nobody is an empty list, which removes it. */
    static JsonObject targets(Attacks attacks) {
        JsonObject targets = new JsonObject();
        if (attacks.categories().isEmpty()) return targets;
        JsonArray categories = new JsonArray();
        for (TargetCategory category : TargetCategory.values()) if (attacks.categories().contains(category)) categories.add(category.jsonName());
        targets.add("categories", categories);
        return targets;
    }

    /** A document with an empty combat module, whose parse gives every default. */
    private static JsonObject withCombat() {
        JsonObject modules = new JsonObject();
        modules.add(CombatSettings.MODULE, new JsonObject());
        JsonObject document = new JsonObject();
        document.add("modules", modules);
        return document;
    }

    /**
     * How a row edits its value: toggle, step, cycle or type; {@code YOU} is the viewer's UUID, {@code NPC} a wand pick,
     * {@code HERE} where the viewer stands, {@code OBJECT} a screen of its own, and {@code WEAPONS} damage by held item.
     */
    enum Kind { BOOLEAN, NUMBER, INTEGER, CHOICE, TEXT, YOU, NPC, HERE, OBJECT, WEAPONS }

    /**
     * One editable setting, by its path inside the module. A number shows as {@code value / scale} with {@code unit}:
     * a tick count reads in seconds, scale 20, unit {@code s}.
     */
    record Field(List<String> path, String label, Kind kind, double minimum, double maximum, double step, List<String> options, String description, List<Field> children,
                 double scale, String unit) {
        Field { path = List.copyOf(path); options = List.copyOf(options); children = List.copyOf(children); }

        /** The key as the file has it, such as {@code targeting.scanIntervalTicks}. */
        String key() { return String.join(".", path); }

        /** A stored number as people read it: {@code 0.5 s} for 10 ticks. */
        String shown(double stored) { return SettingsScreen.number(stored / scale) + (unit.isEmpty() ? "" : " " + unit); }
    }

    /** What a settings screen edits: a module, its rows, the value an NPC that leaves a row out uses, and its help line. */
    record Settings(String module, String title, List<Field> fields, Fallback fallback, String help, String page, String anchor) {
        Settings { fields = List.copyOf(fields); }
    }

    @FunctionalInterface interface Fallback { Optional<JsonElement> value(JsonObject document, List<String> path); }

    /** The rows of one combat section, read from the schema. Settings with no in-game editor are left out. */
    static Settings combat(String section) {
        JsonObject node = resolve(property(resolve(property(resolve(property(SCHEMA, "modules")), CombatSettings.MODULE)), section));
        return new Settings(CombatSettings.MODULE, label(section), fields(List.of(section), node), CombatFields::effective,
                "Clicks change a draft that Save writes to every selected NPC. A row the NPC leaves out shows what it runs with.", "21-in-game-toolkit", "profile-sections");
    }

    /** One grey line on what a section holds, for the combat screen's buttons. */
    static String about(String section) { return ABOUT.getOrDefault(section, ""); }

    /** The riding module; its bounds are the ones {@link RideSettings} checks. */
    static Settings riding() {
        List<Field> fields = List.of(
                plain("enabled", "Enabled", Kind.BOOLEAN, "Players with the permission may ride this NPC."),
                plain("permission", "Permission", Kind.TEXT, "The node a rider needs. " + RideSettings.DEFAULT_PERMISSION + " by default."),
                number("speed", "Speed", Kind.NUMBER, RideSettings.MINIMUM_SPEED, RideSettings.MAXIMUM_SPEED, 0.5, "Blocks a second. " + RideSettings.DEFAULT_SPEED + " by default.", ""),
                number("leash", "Leash", Kind.NUMBER, RideSettings.MINIMUM_LEASH, RideSettings.MAXIMUM_LEASH, 4, "How far from its start a ride may go. " + RideSettings.DEFAULT_LEASH + " by default.", ""),
                number("lifetimeSeconds", "Lifetime", Kind.INTEGER, RideSettings.MINIMUM_LIFETIME_SECONDS, RideSettings.MAXIMUM_LIFETIME_SECONDS, 30,
                        "A ride ends after this long. " + RideSettings.DEFAULT_LIFETIME_SECONDS + " s by default.", "s"));
        return new Settings(RideSettings.MODULE, "Riding", fields, (JsonObject document, List<String> path) -> Optional.of(switch (path.getFirst()) {
            case "permission" -> new JsonPrimitive(RideSettings.DEFAULT_PERMISSION);
            case "speed" -> new JsonPrimitive(RideSettings.DEFAULT_SPEED);
            case "leash" -> new JsonPrimitive(RideSettings.DEFAULT_LEASH);
            case "lifetimeSeconds" -> new JsonPrimitive(RideSettings.DEFAULT_LIFETIME_SECONDS);
            default -> new JsonPrimitive(false);
        }), "Lets players ride the selected NPC. Clicks change a draft that Save writes to every selected NPC.", "05-movement-mounts-riding", "riding-an-npc");
    }

    private static Field plain(String key, String label, Kind kind, String description) { return new Field(List.of(key), label, kind, 0, 0, 0, List.of(), description, List.of(), 1, ""); }

    private static Field number(String key, String label, Kind kind, double minimum, double maximum, double step, String description, String unit) {
        return new Field(List.of(key), label, kind, minimum, maximum, step, List.of(), description, List.of(), 1, unit);
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
            boolean ticks = kind.get() == Kind.INTEGER && entry.getKey().endsWith(TICKS) && entry.getKey().length() > TICKS.length();
            String key = ticks ? entry.getKey().substring(0, entry.getKey().length() - TICKS.length()) : entry.getKey();
            fields.add(new Field(path, label(key), kind.get(), minimum, maximum, step(kind.get(), maximum - minimum), options, ticks ? seconds(description) : description, children,
                    ticks ? TICKS_PER_SECOND : 1, ticks ? "s" : ""));
        }
        return fields;
    }

    /** {@code 10 by default.} about a tick count reads {@code 0.5 s by default.} */
    private static String seconds(String description) {
        Matcher matcher = DEFAULT.matcher(description);
        if (!matcher.find()) return description;
        return SettingsScreen.number(Long.parseLong(matcher.group(1)) / TICKS_PER_SECOND) + " s by default." + description.substring(matcher.end());
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
