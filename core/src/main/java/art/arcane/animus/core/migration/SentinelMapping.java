package art.arcane.animus.core.migration;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Converts a Sentinel trait record into the disabled {@code animus:combat} module of an import. Nothing
 * here activates combat: {@code enabled} and {@code reviewAccepted} stay false until the review is accepted.
 * A field absent from a save holds Sentinel's own default, so those defaults are written explicitly rather
 * than inheriting Animus defaults. A value that cannot be converted exactly is kept in preservedSettings.
 */
final class SentinelMapping {
    enum Disposition { CONVERTED, UNSUPPORTED, STATISTIC, UNKNOWN }

    record Result(JsonObject module, List<String> notes) {
        Result { module = Objects.requireNonNull(module); notes = List.copyOf(notes); }
    }

    private static final Set<String> CONVERTED = Set.of("allTargets", "allIgnores", "allAvoids", "avoidRange", "enemyTargetTime", "fightback", "realistic", "ignore_los", "retain_target",
            "reaction_slowdown", "runaway", "chaseRange", "speed", "close_chase", "ranged_chase", "squad", "guard_distance_minimum", "guard_selection_range", "guardedNPC", "guardingUpper",
            "guardingLower", "avoidReturnPoint", "disable_teleporting", "damage", "attackRate", "attackRateRanged", "range", "reach", "safeShot", "accuracy", "projectileRange", "health", "armor",
            "invincible", "protected", "protectFromRange", "allow_knockback", "healRate", "drops", "dropChances", "enemyDrops", "death_xp", "respawnTime", "spawnPoint");
    private static final Set<String> UNSUPPORTED = Set.of("worldguard_region", "needsAmmo", "autoswitch", "weapon_damage_map", "weapon_redirect_map", "warning_text", "greeting_text", "greet_range", "greet_rate");
    private static final Set<String> STATISTICS = Set.of("stats_ticksSpawned", "stats_timesSpawned", "stats_arrowsFired", "stats_potionsThrow", "stats_fireballsFired", "stats_snowballsThrown", "stats_eggsThrown",
            "stats_skullsThrown", "stats_llamaSpitShot", "stats_shulkerBulletsShot", "stats_evokerFangsSpawned", "stats_pearlsUsed", "stats_punches", "stats_attackAttempts", "stats_damageTaken", "stats_damageGiven");
    private static final String GUARD_REFERENCE = "/traits/sentinel/guardedNPC";
    private static final double WALKING_SPEED = 4.3;
    private static final Map<String, String> CATEGORIES = Map.of("PLAYER", "players", "PLAYERS", "players", "NPC", "npcs", "NPCS", "npcs",
            "MONSTER", "monsters", "MONSTERS", "monsters", "MOB", "mobs", "MOBS", "mobs", "PASSIVE_MOB", "animals", "PASSIVE_MOBS", "animals");
    private static final Map<String, String> ENTITY_ALIASES = Map.of("ENDERMEN", "enderman", "SNOWMEN", "snow_golem", "SNOWMAN", "snow_golem", "IRON_GOLEMS", "iron_golem",
            "ZOMBIE_PIGMEN", "zombified_piglin", "PIGZOMBIE", "zombified_piglin", "MUSHROOM_COW", "mooshroom");

    static Disposition disposition(String field) {
        if (CONVERTED.contains(field)) return Disposition.CONVERTED;
        if (UNSUPPORTED.contains(field)) return Disposition.UNSUPPORTED;
        return field.startsWith("stats_") ? Disposition.STATISTIC : Disposition.UNKNOWN;
    }

    static Result convertRaw(JsonElement sentinel, JsonElement equipment, JsonArray references) {
        if (sentinel != null && sentinel.isJsonObject()) return convert(sentinel.getAsJsonObject(), equipment, references);
        JsonObject module = base();
        module.add("preservedSettings", sentinel == null ? JsonNull.INSTANCE : sentinel.deepCopy());
        module.add("historicalStatistics", new JsonObject());
        return new Result(module, List.of());
    }

    static Result convert(JsonObject sentinel, JsonElement equipment, JsonArray references) {
        return new Converter(sentinel, equipment, references).run();
    }

    /** Points a converted guard at the identity the NPC it guards received in this application. */
    static void remapGuard(JsonObject definition, JsonObject reference) {
        if (!GUARD_REFERENCE.equals(text(reference.get("source"))) || !definition.has("modules")) return;
        JsonObject modules = definition.getAsJsonObject("modules");
        if (!modules.has("animus:combat") || !modules.get("animus:combat").isJsonObject()) return;
        JsonElement guard = modules.getAsJsonObject("animus:combat").get("guard");
        if (guard != null && guard.isJsonObject() && guard.getAsJsonObject().has("npc"))
            guard.getAsJsonObject().addProperty("npc", text(reference.get("npcUuid")));
    }

    private static JsonObject base() {
        JsonObject module = new JsonObject();
        module.addProperty("enabled", false);
        module.addProperty("reviewAccepted", false);
        return module;
    }
    private static String text(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : null;
    }

    private static final class Converter {
        private final JsonObject source;
        private final JsonElement equipment;
        private final JsonArray references;
        private final JsonObject module = base();
        private final JsonObject preserved = new JsonObject();
        private final JsonArray converted = new JsonArray();
        private final JsonArray unsupported = new JsonArray();
        private final JsonArray unknown = new JsonArray();
        private final List<String> notes = new ArrayList<>();

        Converter(JsonObject source, JsonElement equipment, JsonArray references) {
            this.source = source;
            this.equipment = equipment;
            this.references = references;
        }

        Result run() {
            JsonObject statistics = new JsonObject();
            for (Map.Entry<String, JsonElement> field : source.entrySet()) {
                switch (disposition(field.getKey())) {
                    case STATISTIC -> statistics.add(field.getKey(), field.getValue().deepCopy());
                    case UNSUPPORTED -> {
                        preserved.add(field.getKey(), field.getValue().deepCopy());
                        unsupported.add(field.getKey());
                    }
                    case UNKNOWN -> {
                        preserved.add(field.getKey(), field.getValue().deepCopy());
                        unknown.add(field.getKey());
                    }
                    case CONVERTED -> { }
                }
            }
            targetLists();
            targeting();
            avoidance();
            guard();
            pursuit();
            attacks();
            defense();
            death();
            respawn();
            for (String field : source.keySet()) {
                if (disposition(field) == Disposition.CONVERTED && !preserved.has(field)) converted.add(field);
            }
            module.add("preservedSettings", preserved);
            module.add("historicalStatistics", statistics);
            JsonObject report = new JsonObject();
            report.add("converted", converted);
            report.add("unsupported", unsupported);
            report.add("unknown", unknown);
            JsonArray noteList = new JsonArray();
            notes.forEach(noteList::add);
            report.add("notes", noteList);
            module.add("migrationReport", report);
            return new Result(module, notes);
        }

        private JsonObject section(String name) {
            if (!module.has(name)) module.add(name, new JsonObject());
            return module.getAsJsonObject(name);
        }
        private void keep(String field, String why) {
            preserved.add(field, source.get(field).deepCopy());
            notes.add(field + ": " + why);
        }
        private double number(String field, double fallback, double minimum, double maximum) {
            JsonElement value = source.get(field);
            if (value == null) return fallback;
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber() || !Double.isFinite(value.getAsDouble())) {
                keep(field, "not a number; using " + fallback);
                return fallback;
            }
            double exact = value.getAsDouble();
            if (exact < minimum || exact > maximum) {
                double limited = Math.max(minimum, Math.min(maximum, exact));
                keep(field, exact + " is outside this module's range; using " + limited);
                return limited;
            }
            return exact;
        }
        private int ticks(String field, int fallback, int minimum, int maximum) {
            return (int) Math.round(number(field, fallback, minimum, maximum));
        }
        private boolean flag(String field, boolean fallback) {
            JsonElement value = source.get(field);
            if (value == null) return fallback;
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
                keep(field, "not a boolean; using " + fallback);
                return fallback;
            }
            return value.getAsBoolean();
        }

        private void targetLists() {
            rules("allTargets", "targets");
            rules("allIgnores", "ignores");
            rules("allAvoids", "avoids");
        }
        private void rules(String field, String into) {
            JsonElement list = source.get(field);
            if (list == null) return;
            if (!list.isJsonObject()) {
                keep(field, "not a target list");
                return;
            }
            JsonObject residue = new JsonObject();
            JsonObject rules = new JsonObject();
            for (Map.Entry<String, JsonElement> entry : list.getAsJsonObject().entrySet()) {
                List<String> values = strings(entry.getValue());
                JsonArray leftover = new JsonArray();
                if (values == null) {
                    residue.add(entry.getKey(), entry.getValue().deepCopy());
                    continue;
                }
                for (String value : values) {
                    if (!convertRule(entry.getKey(), value, rules)) leftover.add(value);
                }
                if (!leftover.isEmpty()) residue.add(entry.getKey(), leftover);
            }
            if (!rules.isEmpty()) module.add(into, rules);
            if (!residue.isEmpty()) {
                preserved.add(field, residue);
                notes.add(field + ": some entries have no equivalent rule and are not applied");
            }
        }
        private static boolean convertRule(String kind, String value, JsonObject rules) {
            switch (kind) {
                case "targets" -> {
                    String category = CATEGORIES.get(value.toUpperCase(Locale.ROOT));
                    if (category != null) return add(rules, "categories", category);
                    Optional<String> type = entityType(value);
                    return type.isPresent() && add(rules, "entityTypes", type.get());
                }
                case "byPlayerName" -> { return plainName(value) && add(rules, "players", value); }
                case "byNpcName" -> { return plainName(value) && add(rules, "npcs", value); }
                case "byEntityName" -> { return plainName(value) && add(rules, "names", value); }
                case "byOther" -> {
                    if (value.startsWith("squad:") && value.length() > 6) return add(rules, "squads", value.substring(6));
                    if (value.startsWith("uuid:") && canonicalUuid(value.substring(5))) return add(rules, "players", value.substring(5));
                    return false;
                }
                default -> { return false; }
            }
        }
        private static boolean add(JsonObject rules, String key, String value) {
            if (!rules.has(key)) rules.add(key, new JsonArray());
            rules.getAsJsonArray(key).add(value);
            return true;
        }
        /** Sentinel treats these as patterns. Only text with no pattern syntax converts to an exact name. */
        private static boolean plainName(String value) { return value.matches("[A-Za-z0-9_ ]{1,64}"); }
        private static boolean canonicalUuid(String value) {
            try { return UUID.fromString(value).toString().equals(value); }
            catch (IllegalArgumentException invalid) { return false; }
        }
        private static Optional<String> entityType(String name) {
            String upper = name.toUpperCase(Locale.ROOT);
            if (upper.equals("OWNER") || !upper.matches("[A-Z_]{2,48}")) return Optional.empty();
            String alias = ENTITY_ALIASES.get(upper);
            if (alias != null) return Optional.of("minecraft:" + alias);
            String singular = upper;
            if (upper.endsWith("CHES") || upper.endsWith("XES")) singular = upper.substring(0, upper.length() - 2);
            else if (upper.endsWith("S") && !upper.endsWith("SS")) singular = upper.substring(0, upper.length() - 1);
            return Optional.of("minecraft:" + singular.toLowerCase(Locale.ROOT));
        }
        private static List<String> strings(JsonElement value) {
            if (!value.isJsonArray()) return null;
            List<String> result = new ArrayList<>();
            for (JsonElement entry : value.getAsJsonArray()) {
                if (text(entry) == null) return null;
                result.add(entry.getAsString());
            }
            return result;
        }

        private void targeting() {
            JsonObject section = section("targeting");
            section.addProperty("scanRange", number("range", 20, 1, 64));
            section.addProperty("reactionTicks", ticks("reaction_slowdown", 0, 0, 200));
            section.addProperty("memoryTicks", ticks("enemyTargetTime", 0, 0, 1200));
            section.addProperty("retainTarget", flag("retain_target", false));
            section.addProperty("fightBack", flag("fightback", true));
            boolean ignore = flag("ignore_los", false);
            boolean realistic = flag("realistic", false);
            section.addProperty("lineOfSight", ignore ? "ignored" : realistic ? "field_of_view" : "required");
            if (source.has("squad")) {
                String name = text(source.get("squad"));
                if (name == null || name.isBlank() || name.length() > 256) keep("squad", "not a squad name");
                else section("squad").addProperty("name", name);
            }
        }
        private void avoidance() {
            JsonObject section = section("avoidance");
            section.addProperty("range", number("avoidRange", 10, 1, 64));
            section.addProperty("fleeWhenAttacked", flag("runaway", false));
            location("avoidReturnPoint").ifPresent((JsonObject point) -> section.add("returnPoint", point));
        }
        private Optional<JsonObject> location(String field) {
            JsonElement value = source.get(field);
            if (value == null || value.isJsonNull()) return Optional.empty();
            if (value.isJsonObject()) {
                JsonObject from = value.getAsJsonObject();
                String world = text(from.get("world"));
                JsonObject result = new JsonObject();
                boolean valid = world != null && !world.isBlank();
                if (valid) result.addProperty("world", world);
                for (String axis : List.of("x", "y", "z")) {
                    JsonElement coordinate = from.get(axis);
                    if (coordinate == null || !coordinate.isJsonPrimitive() || !coordinate.getAsJsonPrimitive().isNumber()
                            || !Double.isFinite(coordinate.getAsDouble()) || Math.abs(coordinate.getAsDouble()) > 30_000_000) valid = false;
                    else result.addProperty(axis, coordinate.getAsDouble());
                }
                if (valid) return Optional.of(result);
            }
            keep(field, "not a usable location");
            return Optional.empty();
        }
        private void guard() {
            JsonObject section = section("guard");
            section.addProperty("followDistance", number("guard_distance_minimum", 7, 1, 64));
            section.addProperty("pointRange", number("guard_selection_range", 4, 0, 16));
            Optional<Long> upper = exactLong("guardingUpper");
            Optional<Long> lower = exactLong("guardingLower");
            boolean player = false;
            if (upper.isPresent() && lower.isPresent() && (upper.get() != 0 || lower.get() != 0)) {
                section.addProperty("player", new UUID(upper.get(), lower.get()).toString());
                player = true;
            }
            if (source.has("guardedNPC")) guardedNpc(section, player);
        }
        private void guardedNpc(JsonObject section, boolean playerGuarded) {
            for (JsonElement entry : references) {
                JsonObject reference = entry.getAsJsonObject();
                if (!GUARD_REFERENCE.equals(text(reference.get("source")))) continue;
                String status = text(reference.get("status"));
                if ("none".equals(status)) return;
                String uuid = text(reference.get("npcUuid"));
                if ("resolved".equals(status) && uuid != null) {
                    if (playerGuarded) keep("guardedNPC", "this NPC also guards a player, which takes precedence");
                    else section.addProperty("npc", uuid);
                    return;
                }
            }
            keep("guardedNPC", "the guarded NPC is not part of this import");
        }
        private Optional<Long> exactLong(String field) {
            JsonElement value = source.get(field);
            if (value == null) return Optional.empty();
            try {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new ArithmeticException();
                BigDecimal exact = value.getAsBigDecimal();
                return Optional.of(exact.longValueExact());
            } catch (ArithmeticException | NumberFormatException invalid) {
                keep(field, "not half of a UUID");
                return Optional.empty();
            }
        }
        private void pursuit() {
            JsonObject section = section("pursuit");
            double range = number("chaseRange", 100, 1, 256);
            section.addProperty("leashRange", range);
            section.addProperty("giveUpRange", Math.min(range, 128));
            double speed = number("speed", 1, 0, Double.MAX_VALUE);
            double blocksPerSecond = speed * WALKING_SPEED;
            double limited = Math.max(1, Math.min(5, blocksPerSecond));
            if (limited != blocksPerSecond && !preserved.has("speed")) keep("speed", blocksPerSecond + " blocks per second is outside movement limits; using " + limited);
            section.addProperty("speed", limited);
            section.addProperty("closeChase", flag("close_chase", true));
            section.addProperty("rangedChase", flag("ranged_chase", false));
            section.addProperty("teleportWhenStuck", !flag("disable_teleporting", false));
            section.addProperty("returnToPost", true);
        }
        private void attacks() {
            JsonObject melee = section("melee");
            JsonObject ranged = section("ranged");
            double damage = source.has("damage") ? number("damage", -1, -1, 100) : -1;
            melee.addProperty("reach", number("reach", 3, .5, 6));
            melee.addProperty("cooldownTicks", ticks("attackRate", 30, 1, 1200));
            if (damage > 0) {
                melee.addProperty("damage", damage);
                ranged.addProperty("damage", damage);
            } else if (damage < 0) {
                melee.addProperty("damage", 1);
                if (source.has("damage")) keep("damage", "derived from equipment, which is not simulated; using 1");
            } else {
                melee.addProperty("enabled", false);
            }
            ranged.addProperty("enabled", holdsBow());
            ranged.addProperty("cooldownTicks", ticks("attackRateRanged", 30, 1, 1200));
            ranged.addProperty("range", number("projectileRange", 64, 1, 64));
            ranged.addProperty("inaccuracy", number("accuracy", 0, 0, 16));
            ranged.addProperty("safeShot", flag("safeShot", true));
        }
        private boolean holdsBow() {
            if (equipment == null || !equipment.isJsonObject()) return false;
            JsonElement hand = equipment.getAsJsonObject().get("hand");
            if (hand == null || !hand.isJsonObject()) return false;
            String type = text(hand.getAsJsonObject().get("type"));
            if (type == null) type = text(hand.getAsJsonObject().get("id"));
            if (type == null) return false;
            String item = type.toLowerCase(Locale.ROOT).replace("minecraft:", "");
            return item.equals("bow") || item.equals("crossbow");
        }
        private void defense() {
            JsonObject section = section("defense");
            section.addProperty("maximumHealth", number("health", 20, 1, 2048));
            double armor = source.has("armor") ? number("armor", -1, -1, 1) : -1;
            if (armor < 0) {
                section.addProperty("armor", 0);
                if (source.has("armor")) keep("armor", "derived from equipment, which is not simulated; using 0");
            } else {
                section.addProperty("armor", armor);
            }
            section.addProperty("invincible", flag("invincible", false));
            section.addProperty("protectedFromIgnored", flag("protected", false));
            section.addProperty("protectFromRange", number("protectFromRange", 0, 0, 256));
            section.addProperty("knockback", flag("allow_knockback", true));
            section.addProperty("healRateTicks", ticks("healRate", 100, 0, 72000));
        }
        private void death() {
            JsonObject section = section("death");
            section.addProperty("victimDrops", flag("enemyDrops", false));
            section.addProperty("experience", ticks("death_xp", 0, 0, 10000));
            JsonElement drops = source.get("drops");
            if (drops == null) return;
            if (!drops.isJsonArray()) {
                keep("drops", "not a list");
                return;
            }
            JsonArray chances = source.has("dropChances") && source.get("dropChances").isJsonArray() ? source.getAsJsonArray("dropChances") : new JsonArray();
            if (source.has("dropChances") && !source.get("dropChances").isJsonArray()) keep("dropChances", "not a list");
            JsonArray rules = new JsonArray();
            boolean complete = true;
            for (int i = 0; i < drops.getAsJsonArray().size() && rules.size() < 32; i++) {
                Optional<JsonObject> rule = dropRule(drops.getAsJsonArray().get(i), i < chances.size() ? chances.get(i) : null);
                if (rule.isPresent()) rules.add(rule.get());
                else complete = false;
            }
            if (drops.getAsJsonArray().size() > 32) complete = false;
            if (!rules.isEmpty()) section.add("drops", rules);
            if (!complete) keep("drops", "items with extra data cannot be represented and are not dropped");
        }
        private static Optional<JsonObject> dropRule(JsonElement item, JsonElement chance) {
            if (!item.isJsonObject()) return Optional.empty();
            JsonObject from = item.getAsJsonObject();
            for (String key : from.keySet()) {
                if (!Set.of("==", "v", "type", "id", "amount", "count", "schema_version", "DataVersion").contains(key)) return Optional.empty();
            }
            String type = text(from.get("type"));
            if (type == null) type = text(from.get("id"));
            if (type == null) return Optional.empty();
            String id = type.toLowerCase(Locale.ROOT);
            if (!id.contains(":")) id = "minecraft:" + id;
            if (!id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) return Optional.empty();
            JsonElement amount = from.has("amount") ? from.get("amount") : from.get("count");
            int count = amount != null && amount.isJsonPrimitive() && amount.getAsJsonPrimitive().isNumber() ? Math.max(1, Math.min(64, amount.getAsInt())) : 1;
            double probability = chance != null && chance.isJsonPrimitive() && chance.getAsJsonPrimitive().isNumber() ? Math.max(0, Math.min(1, chance.getAsDouble())) : 1;
            JsonObject rule = new JsonObject();
            rule.add("item", new JsonPrimitive(id));
            rule.addProperty("amount", count);
            rule.addProperty("chance", probability);
            return Optional.of(rule);
        }
        private void respawn() {
            JsonObject section = section("respawn");
            section.addProperty("delayTicks", ticks("respawnTime", 100, -1, 72000));
            location("spawnPoint").ifPresent((JsonObject point) -> {
                JsonArray locations = new JsonArray();
                locations.add(point);
                section.add("locations", locations);
            });
        }
    }

    private SentinelMapping() {}
}
