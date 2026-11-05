package art.arcane.animus.core.combat;

import art.arcane.animus.core.npc.NpcDefinition;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;

/**
 * Validated contents of {@code modules["animus:combat"]} in an NPC definition. Runtime state such as
 * current health is never stored here. Unknown keys are rejected so that a typo cannot silently weaken a rule.
 */
public record CombatSettings(boolean enabled, boolean reviewAccepted, TargetRules targets, TargetRules ignores, TargetRules avoids,
                             Targeting targeting, Avoidance avoidance, Squad squad, Guard guard, Pursuit pursuit, Melee melee,
                             Ranged ranged, Defense defense, Death death, Respawn respawn, Greetings greetings) {
    public static final String MODULE = "animus:combat";
    private static final String ROOT = "$.modules." + MODULE;
    private static final int MAXIMUM_RULE_ENTRIES = 256;
    private static final int MAXIMUM_NESTED_RULES = 16;
    private static final String NAMESPACED = "[a-z0-9_.-]+:[a-z0-9_./-]+";
    /** Status effects that help whoever they reach; a potion of any other effect is an attack. */
    private static final Set<String> HELPFUL_EFFECTS = Set.of("minecraft:instant_health", "minecraft:regeneration", "minecraft:speed", "minecraft:strength",
            "minecraft:resistance", "minecraft:fire_resistance", "minecraft:absorption", "minecraft:health_boost", "minecraft:saturation", "minecraft:night_vision",
            "minecraft:water_breathing", "minecraft:invisibility", "minecraft:jump_boost", "minecraft:haste", "minecraft:luck", "minecraft:slow_falling",
            "minecraft:conduit_power", "minecraft:dolphins_grace", "minecraft:hero_of_the_village");

    public enum LineOfSight { REQUIRED, IGNORED, FIELD_OF_VIEW }

    public record Location(String world, double x, double y, double z) {}
    public record Targeting(double scanRange, int scanIntervalTicks, int reactionTicks, int memoryTicks, boolean retainTarget, boolean fightBack, LineOfSight lineOfSight) {}
    public record Avoidance(double range, boolean fleeWhenAttacked, Optional<Location> returnPoint) {}
    public record Squad(Optional<String> name, double alertRange) {}
    /**
     * @param pointRange      how far behind the charge the guard aims
     * @param assist          attack whatever the guarded player or NPC attacks
     * @param catchUpDistance teleport near the charge beyond this distance or across worlds; 0 never teleports
     * @param replanDistance  how far the charge moves before the guard replans its walk
     */
    public record Guard(Optional<UUID> player, Optional<UUID> npc, double followDistance, double pointRange, boolean assist,
                        double catchUpDistance, double replanDistance) {}
    /** @param region a protection region the fight must stay inside, where a protection plugin provides regions */
    public record Pursuit(boolean closeChase, boolean rangedChase, double speed, double giveUpRange, double leashRange, boolean returnToPost, boolean teleportWhenStuck,
                          int replanTicks, Optional<String> region) {}
    /**
     * @param fromEquipment derive damage from the held item instead of {@code damage}
     * @param weaponDamage  damage by held item id; replaces any other damage while that item is held
     */
    public record Melee(boolean enabled, double damage, double reach, int cooldownTicks, double knockback, boolean fromEquipment, Map<String, Double> weaponDamage) {
        public Melee { weaponDamage = Map.copyOf(weaponDamage); }
    }
    /** A status effect a potion applies. */
    public record Effect(String id, int amplifier, int durationTicks) {
        /** Whether this effect helps its target, so a potion of it supports friends instead of attacking. */
        public boolean helpful() { return HELPFUL_EFFECTS.contains(id); }
    }
    /**
     * @param effect  what a potion applies; present exactly when the weapon is a potion
     * @param useAmmo each shot uses one matching item counted from the stored inventory; the count is runtime state
     */
    public record Ranged(boolean enabled, RangedWeapon weapon, double damage, double range, double minimumRange, int cooldownTicks, double velocity, double inaccuracy,
                         boolean safeShot, double knockback, Optional<Effect> effect, boolean fromEquipment, boolean useAmmo) {
        /** Whether this weapon only helps whoever it reaches. */
        public boolean helpful() { return effect.map(Effect::helpful).orElse(false); }
    }
    /** @param armorFromEquipment derive armor from worn items instead of {@code armor} */
    public record Defense(double maximumHealth, double armor, boolean invincible, boolean knockback, boolean protectedFromIgnored, double protectFromRange,
                          int healRateTicks, int invulnerableTicks, boolean armorFromEquipment) {}
    public record DropRule(String item, int amount, double chance) {}
    public record Death(List<DropRule> drops, int experience, boolean victimDrops) {
        public Death { drops = List.copyOf(drops); }
    }
    /** @param deleteOnDeath delete the definition when the NPC dies; requires {@code delayTicks} -1 */
    public record Respawn(int delayTicks, List<Location> locations, boolean deleteOnDeath) {
        public Respawn { locations = List.copyOf(locations); }
    }
    /** Lines said to players who come within range: {@code hostile} to players it would target, {@code friendly} to the rest. */
    public record Greetings(Optional<String> friendly, Optional<String> hostile, double range, int cooldownTicks) {
        public boolean enabled() { return friendly.isPresent() || hostile.isPresent(); }
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
                "melee", "ranged", "defense", "death", "respawn", "greetings", "preservedSettings", "historicalStatistics", "migrationReport");
        return new CombatSettings(literalTrue(module, "enabled"), literalTrue(module, "reviewAccepted"),
                rules(root.child("targets"), Level.SEEKING), rules(root.child("ignores"), Level.IGNORING), rules(root.child("avoids"), Level.SEEKING),
                targeting(root.child("targeting")), avoidance(root.child("avoidance")), squad(root.child("squad")), guard(root.child("guard")),
                pursuit(root.child("pursuit")), melee(root.child("melee")), ranged(root.child("ranged")), defense(root.child("defense")),
                death(root.child("death")), respawn(root.child("respawn")), greetings(root.child("greetings")));
    }

    /** A wrong type is simply not an acceptance. Other fields reject; these two gate activation. */
    private static boolean literalTrue(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() && value.getAsBoolean();
    }

    /** Where a rule list sits: target and avoid lists may use scan-wide and event rules; a nested set matches one entity. */
    private enum Level { SEEKING, IGNORING, NESTED }
    private static final List<String> PLAIN_RULES = List.of("categories", "entityTypes", "players", "npcs", "names", "squads", "owner", "patterns", "items",
            "healthAbove", "healthBelow", "permissions", "groups", "effects");
    private static final List<String> SCAN_RULES = List.of("together", "events", "chat");

    private static TargetRules rules(Reader rules, Level level) {
        for (String key : rules.object().keySet()) {
            boolean allowed = PLAIN_RULES.contains(key) || key.equals("all") && level != Level.NESTED || SCAN_RULES.contains(key) && level == Level.SEEKING;
            if (allowed) continue;
            if (key.equals("all") || SCAN_RULES.contains(key))
                throw new IllegalArgumentException(rules.field(key) + ": not available " + (level == Level.NESTED ? "inside a nested rule set" : "in ignores"));
            throw new IllegalArgumentException(rules.field(key) + ": unknown setting");
        }
        Set<TargetCategory> categories = new HashSet<>();
        List<String> names = rules.strings("categories");
        for (int i = 0; i < names.size(); i++) {
            int index = i;
            categories.add(TargetCategory.parse(names.get(i)).orElseThrow(() -> new IllegalArgumentException(rules.field("categories") + "[" + index + "]: unknown category")));
        }
        Reader patterns = rules.child("patterns");
        patterns.known("players", "npcs", "names");
        Reader items = rules.child("items");
        items.known("mainHand", "offHand", "worn");
        List<String> effects = rules.matching("effects", NAMESPACED, "expected namespace:effect");
        List<String> permissions = rules.matching("permissions", "[A-Za-z0-9_.*-]{1,128}", "expected a permission node");
        List<String> groups = rules.matching("groups", "[A-Za-z0-9_.-]{1,64}", "expected a group name");
        List<TargetRules> all = new ArrayList<>();
        JsonArray allEntries = rules.array("all", MAXIMUM_NESTED_RULES);
        for (int i = 0; i < allEntries.size(); i++) all.add(nested(allEntries.get(i), rules.field("all") + "[" + i + "]"));
        List<List<TargetRules>> together = new ArrayList<>();
        JsonArray groupsTogether = rules.array("together", MAXIMUM_NESTED_RULES);
        for (int i = 0; i < groupsTogether.size(); i++) {
            String path = rules.field("together") + "[" + i + "]";
            if (!groupsTogether.get(i).isJsonArray()) throw new IllegalArgumentException(path + ": expected array of rule sets");
            JsonArray parts = groupsTogether.get(i).getAsJsonArray();
            if (parts.size() < 2 || parts.size() > 8) throw new IllegalArgumentException(path + ": expected 2..8 rule sets");
            List<TargetRules> group = new ArrayList<>();
            for (int j = 0; j < parts.size(); j++) group.add(nested(parts.get(j), path + "[" + j + "]"));
            together.add(group);
        }
        List<String> chat = rules.matching("chat", "(?s).{1,128}", "expected 1..128 characters");
        for (int i = 0; i < chat.size(); i++) if (chat.get(i).isBlank()) throw new IllegalArgumentException(rules.field("chat") + "[" + i + "]: expected text");
        return new TargetRules(categories, Set.copyOf(rules.strings("entityTypes")), Set.copyOf(rules.strings("players")),
                Set.copyOf(rules.strings("npcs")), Set.copyOf(rules.strings("names")), Set.copyOf(rules.strings("squads")), rules.bool("owner", false),
                new TargetRules.Patterns(patterns.patterns("players"), patterns.patterns("npcs"), patterns.patterns("names")),
                new TargetRules.Items(items.patterns("mainHand"), items.patterns("offHand"), items.patterns("worn")),
                percentage(rules, "healthAbove"), percentage(rules, "healthBelow"), Set.copyOf(permissions), Set.copyOf(groups), Set.copyOf(effects),
                all, together, level == Level.SEEKING ? fights(rules.child("events")) : TargetRules.Fights.NONE, Set.copyOf(chat));
    }
    private static TargetRules nested(JsonElement value, String path) {
        if (!value.isJsonObject()) throw new IllegalArgumentException(path + ": expected object");
        TargetRules rules = rules(new Reader(value.getAsJsonObject(), path), Level.NESTED);
        if (!rules.scans()) throw new IllegalArgumentException(path + ": expected at least one rule");
        return rules;
    }
    private static TargetRules.Fights fights(Reader reader) {
        reader.known("kinds", "playerAttacks", "anyAttacks");
        Set<FightKind> kinds = EnumSet.noneOf(FightKind.class);
        List<String> names = reader.strings("kinds");
        for (int i = 0; i < names.size(); i++) {
            int index = i;
            kinds.add(FightKind.parse(names.get(i)).orElseThrow(() -> new IllegalArgumentException(reader.field("kinds") + "[" + index
                    + "]: expected player_hurts_player, player_hurts_mob, mob_hurts_mob, player_hurts_npc or npc_hurts_npc")));
        }
        Optional<TargetRules> player = reader.has("playerAttacks") ? Optional.of(nested(reader.object().get("playerAttacks"), reader.field("playerAttacks"))) : Optional.empty();
        Optional<TargetRules> any = reader.has("anyAttacks") ? Optional.of(nested(reader.object().get("anyAttacks"), reader.field("anyAttacks"))) : Optional.empty();
        return new TargetRules.Fights(kinds, player, any);
    }
    private static OptionalDouble percentage(Reader reader, String key) {
        return reader.has(key) ? OptionalDouble.of(reader.number(key, 0, 0, 100)) : OptionalDouble.empty();
    }
    private static Targeting targeting(Reader reader) {
        reader.known("scanRange", "scanIntervalTicks", "reactionTicks", "memoryTicks", "retainTarget", "fightBack", "lineOfSight");
        String rule = reader.string("lineOfSight", "required");
        LineOfSight lineOfSight;
        try { lineOfSight = LineOfSight.valueOf(rule.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException(reader.field("lineOfSight") + ": expected required, ignored or field_of_view"); }
        return new Targeting(reader.number("scanRange", 16, 1, 256), reader.integer("scanIntervalTicks", 10, 1, 200), reader.integer("reactionTicks", 0, 0, 200),
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
        reader.known("player", "npc", "followDistance", "pointRange", "assist", "catchUpDistance", "replanDistance");
        Optional<UUID> player = reader.uuid("player");
        Optional<UUID> npc = reader.uuid("npc");
        if (player.isPresent() && npc.isPresent()) throw new IllegalArgumentException(reader.path() + ": guard either a player or an npc");
        return new Guard(player, npc, reader.number("followDistance", 7, 1, 64), reader.number("pointRange", 4, 0, 16), reader.bool("assist", false),
                reader.number("catchUpDistance", 24, 0, 256), reader.number("replanDistance", 1.5, 0.25, 16));
    }
    private static Pursuit pursuit(Reader reader) {
        reader.known("closeChase", "rangedChase", "speed", "giveUpRange", "leashRange", "returnToPost", "teleportWhenStuck", "replanTicks", "region");
        Optional<String> region = Optional.empty();
        if (reader.has("region")) {
            String name = reader.string("region", "");
            if (!name.matches("[A-Za-z0-9_,'+/-]{1,64}")) throw new IllegalArgumentException(reader.field("region") + ": expected a region name");
            region = Optional.of(name);
        }
        return new Pursuit(reader.bool("closeChase", true), reader.bool("rangedChase", false), reader.number("speed", 4, 1, 10), reader.number("giveUpRange", 32, 1, 256),
                reader.number("leashRange", 64, 1, 256), reader.bool("returnToPost", true), reader.bool("teleportWhenStuck", false), reader.integer("replanTicks", 20, 5, 200),
                region);
    }
    private static Melee melee(Reader reader) {
        reader.known("enabled", "damage", "reach", "cooldownTicks", "knockback", "fromEquipment", "weaponDamage");
        Reader map = reader.child("weaponDamage");
        if (map.object().size() > 64) throw new IllegalArgumentException(map.path() + ": at most 64 entries");
        Map<String, Double> weaponDamage = new HashMap<>();
        for (String item : map.object().keySet()) {
            if (!item.matches(NAMESPACED)) throw new IllegalArgumentException(map.field(item) + ": expected namespace:item key");
            weaponDamage.put(item, map.number(item, 1, 0, 100));
        }
        return new Melee(reader.bool("enabled", true), reader.number("damage", 2, .01, 100), reader.number("reach", 3, .5, 6),
                reader.integer("cooldownTicks", 20, 1, 1200), reader.number("knockback", .4, 0, 4), reader.bool("fromEquipment", false), weaponDamage);
    }
    private static Ranged ranged(Reader reader) {
        reader.known("enabled", "weapon", "damage", "range", "minimumRange", "cooldownTicks", "velocity", "inaccuracy", "safeShot", "knockback", "effect",
                "fromEquipment", "useAmmo");
        String name = reader.string("weapon", "arrow");
        RangedWeapon weapon = RangedWeapon.parse(name).orElseThrow(() -> new IllegalArgumentException(reader.field("weapon")
                + ": expected arrow, spectral_arrow, trident, snowball, egg, fireball, small_fireball, wither_skull, llama_spit or potion"));
        Optional<Effect> effect = Optional.empty();
        if (reader.has("effect")) {
            if (weapon != RangedWeapon.POTION) throw new IllegalArgumentException(reader.field("effect") + ": only a potion has an effect");
            Reader potion = reader.child("effect");
            potion.known("id", "amplifier", "durationTicks");
            String id = potion.string("id", "");
            if (!id.matches(NAMESPACED)) throw new IllegalArgumentException(potion.field("id") + ": expected namespace:effect");
            effect = Optional.of(new Effect(id, potion.integer("amplifier", 0, 0, 9), potion.integer("durationTicks", 200, 1, 72000)));
        } else if (weapon == RangedWeapon.POTION) throw new IllegalArgumentException(reader.field("effect") + ": required for a potion");
        return new Ranged(reader.bool("enabled", false), weapon, reader.number("damage", 3, .01, 100), reader.number("range", 24, 1, 256),
                reader.number("minimumRange", 0, 0, 256), reader.integer("cooldownTicks", 40, 1, 1200), reader.number("velocity", 1.6, .5, 4),
                reader.number("inaccuracy", 0, 0, 16), reader.bool("safeShot", true), reader.number("knockback", .2, 0, 4), effect,
                reader.bool("fromEquipment", false), reader.bool("useAmmo", false));
    }
    private static Defense defense(Reader reader) {
        reader.known("maximumHealth", "armor", "invincible", "knockback", "protectedFromIgnored", "protectFromRange", "healRateTicks", "invulnerableTicks",
                "armorFromEquipment");
        return new Defense(reader.number("maximumHealth", 20, 1, 2048), reader.number("armor", 0, 0, 1), reader.bool("invincible", false), reader.bool("knockback", true),
                reader.bool("protectedFromIgnored", false), reader.number("protectFromRange", 0, 0, 256), reader.integer("healRateTicks", 0, 0, 72000),
                reader.integer("invulnerableTicks", 10, 0, 100), reader.bool("armorFromEquipment", false));
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
            if (!item.matches(NAMESPACED)) throw new IllegalArgumentException(path + ".item: expected namespace:item");
            drops.add(new DropRule(item, drop.integer("amount", 1, 1, 64), drop.number("chance", 1, 0, 1)));
        }
        return new Death(drops, reader.integer("experience", 0, 0, 10000), reader.bool("victimDrops", true));
    }
    private static Respawn respawn(Reader reader) {
        reader.known("delayTicks", "locations", "deleteOnDeath");
        List<Location> locations = new ArrayList<>();
        JsonArray entries = reader.array("locations", 16);
        for (int i = 0; i < entries.size(); i++) {
            String path = reader.field("locations") + "[" + i + "]";
            if (!entries.get(i).isJsonObject()) throw new IllegalArgumentException(path + ": expected object");
            locations.add(location(new Reader(entries.get(i).getAsJsonObject(), path)));
        }
        int delay = reader.integer("delayTicks", 100, -1, 72000);
        boolean delete = reader.bool("deleteOnDeath", false);
        if (delete && delay != -1) throw new IllegalArgumentException(reader.field("deleteOnDeath") + ": requires delayTicks -1");
        return new Respawn(delay, locations, delete);
    }
    private static Greetings greetings(Reader reader) {
        reader.known("friendly", "hostile", "range", "cooldownTicks");
        return new Greetings(line(reader, "friendly"), line(reader, "hostile"), reader.number("range", 10, 1, 64), reader.integer("cooldownTicks", 100, 0, 72000));
    }
    private static Optional<String> line(Reader reader, String key) {
        if (!reader.has(key)) return Optional.empty();
        String text = reader.string(key, "");
        if (text.isBlank() || text.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException(reader.field(key) + ": expected text without control characters");
        return Optional.of(text);
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
        List<String> matching(String key, String form, String expected) {
            List<String> values = strings(key);
            for (int i = 0; i < values.size(); i++) {
                if (!values.get(i).matches(form)) throw new IllegalArgumentException(field(key) + "[" + i + "]: " + expected);
            }
            return values;
        }
        List<NamePattern> patterns(String key) {
            List<String> sources = strings(key);
            List<NamePattern> result = new ArrayList<>();
            for (int i = 0; i < sources.size(); i++) result.add(NamePattern.compile(sources.get(i), field(key) + "[" + i + "]"));
            return result;
        }
    }
}
