package art.arcane.animus.core.combat;

import art.arcane.animus.core.npc.NpcDefinition;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Validated contents of {@code modules["animus:combat"]} in an NPC definition. Runtime state such as
 * current health is never stored here. Unknown keys are rejected so that a typo cannot silently weaken a rule.
 */
public record CombatSettings(boolean enabled, boolean reviewAccepted, TargetRules targets, TargetRules ignores, TargetRules avoids,
                             Targeting targeting, Avoidance avoidance, Squad squad, Guard guard, Pursuit pursuit, Melee melee,
                             Ranged ranged, Defense defense, Death death, Respawn respawn) {
    public static final String MODULE = "animus:combat";
    private static final String ROOT = "$.modules." + MODULE;
    private static final int MAXIMUM_RULE_ENTRIES = 256;

    public enum LineOfSight { REQUIRED, IGNORED, FIELD_OF_VIEW }

    public record Location(String world, double x, double y, double z) {}
    public record Targeting(double scanRange, int scanIntervalTicks, int reactionTicks, int memoryTicks, boolean retainTarget, boolean fightBack, LineOfSight lineOfSight) {}
    public record Avoidance(double range, boolean fleeWhenAttacked, Optional<Location> returnPoint) {}
    public record Squad(Optional<String> name, double alertRange) {}
    public record Guard(Optional<UUID> player, Optional<UUID> npc, double followDistance, double pointRange) {}
    public record Pursuit(boolean closeChase, boolean rangedChase, double speed, double giveUpRange, double leashRange, boolean returnToPost, boolean teleportWhenStuck, int replanTicks) {}
    public record Melee(boolean enabled, double damage, double reach, int cooldownTicks, double knockback) {}
    public record Ranged(boolean enabled, String weapon, double damage, double range, double minimumRange, int cooldownTicks, double velocity, double inaccuracy, boolean safeShot, double knockback) {}
    public record Defense(double maximumHealth, double armor, boolean invincible, boolean knockback, boolean protectedFromIgnored, double protectFromRange, int healRateTicks, int invulnerableTicks) {}
    public record DropRule(String item, int amount, double chance) {}
    public record Death(List<DropRule> drops, int experience, boolean victimDrops) {
        public Death { drops = List.copyOf(drops); }
    }
    public record Respawn(int delayTicks, List<Location> locations) {
        public Respawn { locations = List.copyOf(locations); }
    }

    /** Parses the module from a raw definition document; empty when it has no combat module. */
    public static Optional<CombatSettings> parse(JsonObject definition) {
        JsonElement modules = definition.get("modules");
        if (modules == null || !modules.isJsonObject() || !modules.getAsJsonObject().has(MODULE)) return Optional.empty();
        JsonElement module = modules.getAsJsonObject().get(MODULE);
        if (!module.isJsonObject()) throw new IllegalArgumentException(ROOT + ": expected object");
        return Optional.of(parseModule(module.getAsJsonObject()));
    }

    /** The settings to run, or empty when combat is absent, off, or an import whose review was not accepted. */
    public static Optional<CombatSettings> active(NpcDefinition definition) {
        JsonObject document = definition.json();
        Optional<CombatSettings> settings = parse(document);
        if (settings.isEmpty() || !settings.get().enabled()) return Optional.empty();
        if (document.has("migration") && !settings.get().reviewAccepted()) return Optional.empty();
        return settings;
    }

    public static CombatSettings parseModule(JsonObject module) {
        Reader root = new Reader(module, ROOT);
        root.known("enabled", "reviewAccepted", "targets", "ignores", "avoids", "targeting", "avoidance", "squad", "guard", "pursuit",
                "melee", "ranged", "defense", "death", "respawn", "preservedSettings", "historicalStatistics", "migrationReport");
        return new CombatSettings(literalTrue(module, "enabled"), literalTrue(module, "reviewAccepted"),
                rules(root, "targets"), rules(root, "ignores"), rules(root, "avoids"),
                targeting(root.child("targeting")), avoidance(root.child("avoidance")), squad(root.child("squad")), guard(root.child("guard")),
                pursuit(root.child("pursuit")), melee(root.child("melee")), ranged(root.child("ranged")), defense(root.child("defense")),
                death(root.child("death")), respawn(root.child("respawn")));
    }

    /** A wrong type is simply not an acceptance. Other fields reject; these two gate activation. */
    private static boolean literalTrue(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() && value.getAsBoolean();
    }

    private static TargetRules rules(Reader root, String key) {
        Reader rules = root.child(key);
        rules.known("categories", "entityTypes", "players", "npcs", "names", "squads");
        Set<TargetCategory> categories = new HashSet<>();
        List<String> names = rules.strings("categories");
        for (int i = 0; i < names.size(); i++) {
            int index = i;
            categories.add(TargetCategory.parse(names.get(i)).orElseThrow(() -> new IllegalArgumentException(rules.field("categories") + "[" + index + "]: unknown category")));
        }
        return new TargetRules(categories, Set.copyOf(rules.strings("entityTypes")), Set.copyOf(rules.strings("players")),
                Set.copyOf(rules.strings("npcs")), Set.copyOf(rules.strings("names")), Set.copyOf(rules.strings("squads")));
    }
    private static Targeting targeting(Reader reader) {
        reader.known("scanRange", "scanIntervalTicks", "reactionTicks", "memoryTicks", "retainTarget", "fightBack", "lineOfSight");
        String rule = reader.string("lineOfSight", "required");
        LineOfSight lineOfSight;
        try { lineOfSight = LineOfSight.valueOf(rule.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException(reader.field("lineOfSight") + ": expected required, ignored or field_of_view"); }
        return new Targeting(reader.number("scanRange", 16, 1, 64), reader.integer("scanIntervalTicks", 10, 1, 200), reader.integer("reactionTicks", 0, 0, 200),
                reader.integer("memoryTicks", 60, 0, 1200), reader.bool("retainTarget", false), reader.bool("fightBack", true), lineOfSight);
    }
    private static Avoidance avoidance(Reader reader) {
        reader.known("range", "fleeWhenAttacked", "returnPoint");
        Optional<Location> point = reader.has("returnPoint") ? Optional.of(location(reader.child("returnPoint"))) : Optional.empty();
        return new Avoidance(reader.number("range", 10, 1, 64), reader.bool("fleeWhenAttacked", false), point);
    }
    private static Squad squad(Reader reader) {
        reader.known("name", "alertRange");
        return new Squad(reader.has("name") ? Optional.of(reader.string("name", "")) : Optional.empty(), reader.number("alertRange", 16, 1, 64));
    }
    private static Guard guard(Reader reader) {
        reader.known("player", "npc", "followDistance", "pointRange");
        Optional<UUID> player = reader.uuid("player");
        Optional<UUID> npc = reader.uuid("npc");
        if (player.isPresent() && npc.isPresent()) throw new IllegalArgumentException(reader.path() + ": guard either a player or an npc");
        return new Guard(player, npc, reader.number("followDistance", 7, 1, 64), reader.number("pointRange", 4, 0, 16));
    }
    private static Pursuit pursuit(Reader reader) {
        reader.known("closeChase", "rangedChase", "speed", "giveUpRange", "leashRange", "returnToPost", "teleportWhenStuck", "replanTicks");
        return new Pursuit(reader.bool("closeChase", true), reader.bool("rangedChase", false), reader.number("speed", 4, 1, 5), reader.number("giveUpRange", 32, 1, 128),
                reader.number("leashRange", 64, 1, 256), reader.bool("returnToPost", true), reader.bool("teleportWhenStuck", false), reader.integer("replanTicks", 20, 5, 200));
    }
    private static Melee melee(Reader reader) {
        reader.known("enabled", "damage", "reach", "cooldownTicks", "knockback");
        return new Melee(reader.bool("enabled", true), reader.number("damage", 2, .01, 100), reader.number("reach", 3, .5, 6),
                reader.integer("cooldownTicks", 20, 1, 1200), reader.number("knockback", .4, 0, 4));
    }
    private static Ranged ranged(Reader reader) {
        reader.known("enabled", "weapon", "damage", "range", "minimumRange", "cooldownTicks", "velocity", "inaccuracy", "safeShot", "knockback");
        String weapon = reader.string("weapon", "arrow");
        if (!weapon.equals("arrow")) throw new IllegalArgumentException(reader.field("weapon") + ": only arrow is supported");
        return new Ranged(reader.bool("enabled", false), weapon, reader.number("damage", 3, .01, 100), reader.number("range", 24, 1, 64), reader.number("minimumRange", 0, 0, 64),
                reader.integer("cooldownTicks", 40, 1, 1200), reader.number("velocity", 1.6, .5, 4), reader.number("inaccuracy", 0, 0, 16), reader.bool("safeShot", true), reader.number("knockback", .2, 0, 4));
    }
    private static Defense defense(Reader reader) {
        reader.known("maximumHealth", "armor", "invincible", "knockback", "protectedFromIgnored", "protectFromRange", "healRateTicks", "invulnerableTicks");
        return new Defense(reader.number("maximumHealth", 20, 1, 2048), reader.number("armor", 0, 0, 1), reader.bool("invincible", false), reader.bool("knockback", true),
                reader.bool("protectedFromIgnored", false), reader.number("protectFromRange", 0, 0, 256), reader.integer("healRateTicks", 0, 0, 72000),
                reader.integer("invulnerableTicks", 10, 0, 100));
    }
    private static Death death(Reader reader) {
        reader.known("drops", "experience", "victimDrops");
        List<DropRule> drops = new ArrayList<>();
        JsonArray entries = reader.array("drops", 32);
        for (int i = 0; i < entries.size(); i++) {
            String path = reader.field("drops") + "[" + i + "]";
            if (!entries.get(i).isJsonObject()) throw new IllegalArgumentException(path + ": expected object");
            Reader drop = new Reader(entries.get(i).getAsJsonObject(), path);
            drop.known("item", "amount", "chance");
            String item = drop.string("item", "");
            if (!item.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IllegalArgumentException(path + ".item: expected namespace:item");
            drops.add(new DropRule(item, drop.integer("amount", 1, 1, 64), drop.number("chance", 1, 0, 1)));
        }
        return new Death(drops, reader.integer("experience", 0, 0, 10000), reader.bool("victimDrops", true));
    }
    private static Respawn respawn(Reader reader) {
        reader.known("delayTicks", "locations");
        List<Location> locations = new ArrayList<>();
        JsonArray entries = reader.array("locations", 16);
        for (int i = 0; i < entries.size(); i++) {
            String path = reader.field("locations") + "[" + i + "]";
            if (!entries.get(i).isJsonObject()) throw new IllegalArgumentException(path + ": expected object");
            locations.add(location(new Reader(entries.get(i).getAsJsonObject(), path)));
        }
        return new Respawn(reader.integer("delayTicks", 100, -1, 72000), locations);
    }
    private static Location location(Reader reader) {
        reader.known("world", "x", "y", "z");
        String world = reader.string("world", "");
        if (world.isBlank()) throw new IllegalArgumentException(reader.field("world") + ": expected nonblank string");
        return new Location(world, reader.requiredNumber("x"), reader.requiredNumber("y"), reader.requiredNumber("z"));
    }

    private record Reader(JsonObject object, String path) {
        String field(String key) { return path + "." + key; }
        boolean has(String key) { return object.has(key); }
        void known(String... keys) {
            Set<String> allowed = Set.of(keys);
            for (String key : object.keySet()) {
                if (!allowed.contains(key)) throw new IllegalArgumentException(field(key) + ": unknown setting");
            }
        }
        Reader child(String key) {
            if (!object.has(key)) return new Reader(new JsonObject(), field(key));
            if (!object.get(key).isJsonObject()) throw new IllegalArgumentException(field(key) + ": expected object");
            return new Reader(object.getAsJsonObject(key), field(key));
        }
        boolean bool(String key, boolean fallback) {
            if (!object.has(key)) return fallback;
            JsonElement value = object.get(key);
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException(field(key) + ": expected boolean");
            return value.getAsBoolean();
        }
        String string(String key, String fallback) {
            if (!object.has(key)) return fallback;
            JsonElement value = object.get(key);
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || value.getAsString().length() > 256) throw new IllegalArgumentException(field(key) + ": expected string of at most 256 characters");
            return value.getAsString();
        }
        double number(String key, double fallback, double minimum, double maximum) {
            if (!object.has(key)) return fallback;
            double value = finite(key);
            if (value < minimum || value > maximum) throw new IllegalArgumentException(field(key) + ": must be within " + minimum + ".." + maximum);
            return value;
        }
        double requiredNumber(String key) {
            if (!object.has(key)) throw new IllegalArgumentException(field(key) + ": required");
            double value = finite(key);
            if (Math.abs(value) > 30_000_000) throw new IllegalArgumentException(field(key) + ": must be within +-30000000");
            return value;
        }
        private double finite(String key) {
            JsonElement value = object.get(key);
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber() || !Double.isFinite(value.getAsDouble())) throw new IllegalArgumentException(field(key) + ": expected finite number");
            return value.getAsDouble();
        }
        int integer(String key, int fallback, int minimum, int maximum) {
            if (!object.has(key)) return fallback;
            JsonElement value = object.get(key);
            int exact;
            try {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new ArithmeticException();
                exact = value.getAsBigDecimal().intValueExact();
            } catch (ArithmeticException | NumberFormatException invalid) { throw new IllegalArgumentException(field(key) + ": expected integer"); }
            if (exact < minimum || exact > maximum) throw new IllegalArgumentException(field(key) + ": must be within " + minimum + ".." + maximum);
            return exact;
        }
        Optional<UUID> uuid(String key) {
            if (!object.has(key)) return Optional.empty();
            String text = string(key, "");
            try {
                UUID value = UUID.fromString(text);
                if (!value.toString().equals(text)) throw new IllegalArgumentException();
                return Optional.of(value);
            } catch (IllegalArgumentException invalid) { throw new IllegalArgumentException(field(key) + ": expected canonical UUID"); }
        }
        JsonArray array(String key, int maximum) {
            if (!object.has(key)) return new JsonArray();
            if (!object.get(key).isJsonArray()) throw new IllegalArgumentException(field(key) + ": expected array");
            JsonArray value = object.getAsJsonArray(key);
            if (value.size() > maximum) throw new IllegalArgumentException(field(key) + ": at most " + maximum + " entries");
            return value;
        }
        List<String> strings(String key) {
            JsonArray values = array(key, MAXIMUM_RULE_ENTRIES);
            List<String> result = new ArrayList<>();
            for (int i = 0; i < values.size(); i++) {
                JsonElement value = values.get(i);
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException(field(key) + "[" + i + "]: expected string");
                result.add(value.getAsString());
            }
            return result;
        }
    }
}
