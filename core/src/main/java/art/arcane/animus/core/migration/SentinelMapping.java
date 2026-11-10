package art.arcane.animus.core.migration;

import art.arcane.animus.core.combat.NamePattern;
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
 * Converts a Sentinel trait record into the {@code animus:combat} module of an import, switched on, and its
 * {@code animus:health} module: health, armor, invincibility, drops, experience, respawn time and spawn point.
 * A field absent from a save holds Sentinel's own default, so those defaults are written explicitly rather
 * than inheriting Animus defaults. A value that cannot be converted exactly is kept in preservedSettings.
 */
final class SentinelMapping {
    enum Disposition { CONVERTED, UNSUPPORTED, STATISTIC, UNKNOWN }

    /** @param health the {@code animus:health} module; empty when the Sentinel record is not an object */
    record Result(JsonObject module, Optional<JsonObject> health, List<String> notes) {
        Result { module = Objects.requireNonNull(module); health = Objects.requireNonNull(health); notes = List.copyOf(notes); }
    }

    private static final Set<String> CONVERTED = Set.of("allTargets", "allIgnores", "allAvoids", "avoidRange", "enemyTargetTime", "fightback", "realistic", "ignore_los", "retain_target",
            "reaction_slowdown", "runaway", "chaseRange", "speed", "close_chase", "ranged_chase", "squad", "guard_distance_minimum", "guard_selection_range", "guardedNPC", "guardingUpper",
            "guardingLower", "avoidReturnPoint", "disable_teleporting", "damage", "attackRate", "attackRateRanged", "range", "reach", "safeShot", "accuracy", "projectileRange", "health", "armor",
            "invincible", "protected", "protectFromRange", "allow_knockback", "healRate", "drops", "dropChances", "enemyDrops", "death_xp", "respawnTime", "spawnPoint",
            "worldguard_region", "needsAmmo", "weapon_damage_map", "warning_text", "greeting_text", "greet_range", "greet_rate");
    private static final Set<String> UNSUPPORTED = Set.of("autoswitch", "weapon_redirect_map");
    private static final String GUARD_REFERENCE = "/traits/sentinel/guardedNPC";
    private static final double WALKING_SPEED = 4.3;
    private static final Map<String, String> CATEGORIES = Map.of("PLAYER", "players", "PLAYERS", "players", "NPC", "npcs", "NPCS", "npcs",
            "MONSTER", "monsters", "MONSTERS", "monsters", "MOB", "mobs", "MOBS", "mobs", "PASSIVE_MOB", "animals", "PASSIVE_MOBS", "animals");
    private static final Map<String, String> ENTITY_ALIASES = Map.of("ENDERMEN", "enderman", "SNOWMEN", "snow_golem", "SNOWMAN", "snow_golem", "IRON_GOLEMS", "iron_golem",
            "ZOMBIE_PIGMEN", "zombified_piglin", "PIGZOMBIE", "zombified_piglin", "MUSHROOM_COW", "mooshroom");
    private static final Map<String, String> EFFECT_ALIASES = Map.ofEntries(Map.entry("SLOW", "slowness"), Map.entry("FAST_DIGGING", "haste"),
            Map.entry("SLOW_DIGGING", "mining_fatigue"), Map.entry("INCREASE_DAMAGE", "strength"), Map.entry("HEAL", "instant_health"), Map.entry("HARM", "instant_damage"),
            Map.entry("JUMP", "jump_boost"), Map.entry("CONFUSION", "nausea"), Map.entry("DAMAGE_RESISTANCE", "resistance"));
    private static final Map<String, String> FIGHTS = Map.of("pvp", "player_hurts_player", "pve", "player_hurts_mob", "eve", "mob_hurts_mob",
            "pvnpc", "player_hurts_npc", "pvsentinel", "player_hurts_npc", "npcvnpc", "npc_hurts_npc");
    private static final Map<String, String> HELD_WEAPONS = Map.of("bow", "arrow", "crossbow", "arrow", "trident", "trident", "blaze_rod", "fireball",
            "snowball", "snowball", "snow_ball", "snowball", "egg", "egg", "spectral_arrow", "spectral_arrow", "wither_skeleton_skull", "wither_skull");
    private static final Map<String, String> LABEL_KINDS = Map.of("player", "byPlayerName", "npc", "byNpcName", "entityname", "byEntityName", "helditem", "byHeldItem",
            "offhand", "byOffhandItem", "equipped", "byEquippedItem", "group", "byGroup");

    static Disposition disposition(String field) {
        if (CONVERTED.contains(field)) return Disposition.CONVERTED;
        if (UNSUPPORTED.contains(field)) return Disposition.UNSUPPORTED;
        return field.startsWith("stats_") ? Disposition.STATISTIC : Disposition.UNKNOWN;
    }

    static Result convertRaw(JsonElement sentinel, JsonElement equipment, JsonArray references) {
        if (sentinel != null && sentinel.isJsonObject()) return convert(sentinel.getAsJsonObject(), equipment, references);
        // A record that is not an object converts to nothing, so its combat stays off and the record is only kept.
        JsonObject module = base();
        module.addProperty("enabled", false);
        module.add("preservedSettings", sentinel == null ? JsonNull.INSTANCE : sentinel.deepCopy());
        module.add("historicalStatistics", new JsonObject());
        return new Result(module, Optional.empty(), List.of());
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
        module.addProperty("enabled", true);
        return module;
    }
    private static String text(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : null;
    }

    /** Where a Sentinel target list lands: target and avoid lists accept event rules, ignores do not, nested lists match one entity. */
    private enum Scope { TARGETS, AVOIDS, IGNORES, NESTED }

    /** Converts one Sentinel target list: the rules that apply and the entries that have no equivalent. */
    private static final class RuleList {
        final JsonObject rules = new JsonObject();
        final JsonObject residue = new JsonObject();
        boolean assist;

        RuleList(JsonObject list, Scope scope) {
            for (Map.Entry<String, JsonElement> entry : list.entrySet()) {
                String kind = entry.getKey();
                if (kind.equals("byAllInOne") || kind.equals("byMultiple")) {
                    nestedLists(kind, entry.getValue(), scope);
                    continue;
                }
                List<String> values = strings(entry.getValue());
                if (values == null) {
                    residue.add(kind, entry.getValue().deepCopy());
                    continue;
                }
                JsonArray leftover = new JsonArray();
                for (String value : values) {
                    if (kind.equals("byEvent") && value.equals("guarded_fight") && scope == Scope.TARGETS) assist = true;
                    else if (!rule(kind, value, rules, scope)) leftover.add(value);
                }
                if (!leftover.isEmpty()) residue.add(kind, leftover);
            }
        }

        private void nestedLists(String kind, JsonElement value, Scope scope) {
            List<JsonElement> lists = new ArrayList<>();
            if (value.isJsonArray()) value.getAsJsonArray().forEach(lists::add);
            else if (value.isJsonObject()) lists.addAll(value.getAsJsonObject().asMap().values());
            else {
                residue.add(kind, value.deepCopy());
                return;
            }
            JsonArray leftover = new JsonArray();
            for (JsonElement nested : lists) {
                Optional<JsonElement> converted = Optional.empty();
                if (nested.isJsonObject() && scope != Scope.NESTED) {
                    converted = kind.equals("byAllInOne") ? allInOne(nested.getAsJsonObject())
                            : scope == Scope.IGNORES ? Optional.empty() : together(nested.getAsJsonObject());
                }
                if (converted.isEmpty()) leftover.add(nested.deepCopy());
                else {
                    String into = kind.equals("byAllInOne") ? "all" : "together";
                    if (!rules.has(into)) rules.add(into, new JsonArray());
                    rules.getAsJsonArray(into).add(converted.get());
                }
            }
            if (!leftover.isEmpty()) residue.add(kind, leftover);
        }

        /** One entity must match every entry, so the list converts only when every entry does. */
        private static Optional<JsonElement> allInOne(JsonObject list) {
            RuleList nested = new RuleList(list, Scope.NESTED);
            return nested.residue.isEmpty() && !nested.rules.isEmpty() ? Optional.of(nested.rules) : Optional.empty();
        }

        /** Each entry is checked off by a different entity; the group completes when all are. */
        private static Optional<JsonElement> together(JsonObject list) {
            JsonArray parts = new JsonArray();
            for (Map.Entry<String, JsonElement> entry : list.entrySet()) {
                List<String> values = strings(entry.getValue());
                if (values == null) return Optional.empty();
                for (String value : values) {
                    JsonObject part = new JsonObject();
                    if (!rule(entry.getKey(), value, part, Scope.NESTED)) return Optional.empty();
                    parts.add(part);
                }
            }
            return parts.size() >= 2 && parts.size() <= 8 ? Optional.of(parts) : Optional.empty();
        }
    }

    private static boolean rule(String kind, String value, JsonObject rules, Scope scope) {
        switch (kind) {
            case "targets" -> {
                String upper = value.toUpperCase(Locale.ROOT);
                if (upper.equals("OWNER")) {
                    rules.addProperty("owner", true);
                    return true;
                }
                String category = CATEGORIES.get(upper);
                if (category != null) return add(rules, "categories", category);
                Optional<String> type = entityType(value);
                return type.isPresent() && add(rules, "entityTypes", type.get());
            }
            case "byPlayerName" -> { return pattern(rules, "patterns", "players", value); }
            case "byNpcName" -> { return pattern(rules, "patterns", "npcs", value); }
            case "byEntityName" -> { return pattern(rules, "patterns", "names", value); }
            case "byHeldItem" -> { return item(rules, "mainHand", value); }
            case "byOffhandItem" -> { return item(rules, "offHand", value); }
            case "byEquippedItem" -> { return item(rules, "worn", value); }
            case "byGroup" -> { return value.matches("[A-Za-z0-9_.-]{1,64}") && add(rules, "groups", value); }
            case "byOther" -> { return other(value, rules); }
            case "byEvent" -> { return scope == Scope.TARGETS || scope == Scope.AVOIDS ? event(value, rules) : false; }
            default -> { return false; }
        }
    }

    private static boolean other(String value, JsonObject rules) {
        int colon = value.indexOf(':');
        if (colon <= 0 || colon == value.length() - 1) return false;
        String prefix = value.substring(0, colon).toLowerCase(Locale.ROOT);
        String rest = value.substring(colon + 1);
        switch (prefix) {
            case "squad" -> { return add(rules, "squads", rest); }
            case "uuid" -> { return canonicalUuid(rest) && add(rules, "players", rest); }
            case "healthabove", "healthbelow" -> {
                double percent;
                try { percent = Double.parseDouble(rest); }
                catch (NumberFormatException invalid) { return false; }
                if (!Double.isFinite(percent) || percent < 0 || percent > 100) return false;
                String key = prefix.equals("healthabove") ? "healthAbove" : "healthBelow";
                if (rules.has(key)) {
                    double current = rules.get(key).getAsDouble();
                    percent = prefix.equals("healthabove") ? Math.min(current, percent) : Math.max(current, percent);
                }
                rules.addProperty(key, percent);
                return true;
            }
            case "potion" -> {
                String upper = rest.toUpperCase(Locale.ROOT);
                if (!upper.matches("[A-Z_]{2,48}")) return false;
                return add(rules, "effects", "minecraft:" + EFFECT_ALIASES.getOrDefault(upper, upper.toLowerCase(Locale.ROOT)));
            }
            case "permission" -> { return rest.matches("[A-Za-z0-9_.*-]{1,128}") && add(rules, "permissions", rest); }
            default -> { return false; }
        }
    }

    private static boolean event(String value, JsonObject rules) {
        String lower = value.toLowerCase(Locale.ROOT);
        String fight = FIGHTS.get(lower);
        JsonObject events = rules.has("events") ? rules.getAsJsonObject("events") : new JsonObject();
        boolean converted = false;
        if (fight != null) {
            if (!events.has("kinds")) events.add("kinds", new JsonArray());
            if (!events.getAsJsonArray("kinds").contains(new JsonPrimitive(fight))) events.getAsJsonArray("kinds").add(fight);
            converted = true;
        } else if (lower.startsWith("message:") || lower.startsWith("message,")) {
            String message = value.substring("message:".length());
            if (message.isBlank() || message.length() > 128) return false;
            return add(rules, "chat", message);
        } else if (lower.startsWith("pv:") || lower.startsWith("ev:")) {
            String into = lower.startsWith("pv:") ? "playerAttacks" : "anyAttacks";
            JsonObject attacked = events.has(into) ? events.getAsJsonObject(into) : new JsonObject();
            if (!label(value.substring(3), attacked)) return false;
            events.add(into, attacked);
            converted = true;
        }
        if (converted) rules.add("events", events);
        return converted;
    }

    /** A single Sentinel target label such as {@code zombies}, {@code player:Bob} or {@code healthbelow:50}. */
    private static boolean label(String label, JsonObject rules) {
        int colon = label.indexOf(':');
        if (colon < 0) return rule("targets", label, rules, Scope.NESTED);
        String prefix = label.substring(0, colon).toLowerCase(Locale.ROOT);
        String kind = LABEL_KINDS.get(prefix);
        return kind != null ? rule(kind, label.substring(colon + 1), rules, Scope.NESTED) : other(label, rules);
    }

    private static boolean pattern(JsonObject rules, String section, String key, String value) {
        try { NamePattern.compile(value, "$"); }
        catch (IllegalArgumentException invalid) { return false; }
        if (!rules.has(section)) rules.add(section, new JsonObject());
        return add(rules.getAsJsonObject(section), key, value);
    }
    /** Item matchers on display names and lore have no equivalent; plain matchers apply to the item id. */
    private static boolean item(JsonObject rules, String slot, String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.startsWith("name:") || lower.startsWith("lore:")) return false;
        return pattern(rules, "items", slot, value);
    }
    private static boolean add(JsonObject rules, String key, String value) {
        if (!rules.has(key)) rules.add(key, new JsonArray());
        if (!rules.getAsJsonArray(key).contains(new JsonPrimitive(value))) rules.getAsJsonArray(key).add(value);
        return true;
    }
    private static boolean canonicalUuid(String value) {
        try { return UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException invalid) { return false; }
    }
    private static Optional<String> entityType(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        if (!upper.matches("[A-Z_]{2,48}")) return Optional.empty();
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

    private static final class Converter {
        private final JsonObject source;
        private final JsonElement equipment;
        private final JsonArray references;
        private final JsonObject module = base();
        private final JsonObject health = new JsonObject();
        private final JsonObject preserved = new JsonObject();
        private final JsonArray converted = new JsonArray();
        private final JsonArray unsupported = new JsonArray();
        private final JsonArray unknown = new JsonArray();
        private final List<String> notes = new ArrayList<>();
        private boolean assist;

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
            greetings();
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
            return new Result(module, Optional.of(health), notes);
        }

        private JsonObject section(String name) {
            if (!module.has(name)) module.add(name, new JsonObject());
            return module.getAsJsonObject(name);
        }
        private JsonObject healthSection(String name) {
            if (!health.has(name)) health.add(name, new JsonObject());
            return health.getAsJsonObject(name);
        }
        private void keep(String field, String why) {
            preserved.add(field, source.get(field).deepCopy());
            notes.add(field + ": " + why);
        }
        private boolean present(String field) {
            JsonElement value = source.get(field);
            return value != null && !value.isJsonNull();
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
            rules("allTargets", "targets", Scope.TARGETS);
            rules("allIgnores", "ignores", Scope.IGNORES);
            rules("allAvoids", "avoids", Scope.AVOIDS);
        }
        private void rules(String field, String into, Scope scope) {
            JsonElement list = source.get(field);
            if (list == null) return;
            if (!list.isJsonObject()) {
                keep(field, "not a target list");
                return;
            }
            RuleList result = new RuleList(list.getAsJsonObject(), scope);
            if (result.assist) assist = true;
            if (!result.rules.isEmpty()) module.add(into, result.rules);
            if (!result.residue.isEmpty()) {
                preserved.add(field, result.residue);
                notes.add(field + ": some entries have no equivalent rule and are not applied");
            }
        }

        private void targeting() {
            JsonObject section = section("targeting");
            section.addProperty("scanRange", number("range", 20, 1, 256));
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
            if (assist) section.addProperty("assist", true);
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
            section.addProperty("giveUpRange", range);
            double speed = number("speed", 1, 0, Double.MAX_VALUE);
            double blocksPerSecond = speed * WALKING_SPEED;
            double limited = Math.max(1, Math.min(10, blocksPerSecond));
            if (limited != blocksPerSecond && !preserved.has("speed")) keep("speed", blocksPerSecond + " blocks per second is outside movement limits; using " + limited);
            section.addProperty("speed", limited);
            section.addProperty("closeChase", flag("close_chase", true));
            section.addProperty("rangedChase", flag("ranged_chase", false));
            section.addProperty("teleportWhenStuck", !flag("disable_teleporting", false));
            section.addProperty("returnToPost", true);
            if (present("worldguard_region")) {
                String region = text(source.get("worldguard_region"));
                if (region != null && region.matches("[A-Za-z0-9_,'+/-]{1,64}")) section.addProperty("region", region);
                else keep("worldguard_region", "not a usable region name");
            }
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
                melee.addProperty("fromEquipment", true);
                ranged.addProperty("fromEquipment", true);
            } else {
                melee.addProperty("enabled", false);
            }
            weaponDamage(melee);
            Optional<String> weapon = heldWeapon();
            ranged.addProperty("enabled", weapon.isPresent());
            weapon.ifPresent((String name) -> ranged.addProperty("weapon", name));
            ranged.addProperty("cooldownTicks", ticks("attackRateRanged", 30, 1, 1200));
            ranged.addProperty("range", number("projectileRange", 100, 1, 256));
            ranged.addProperty("inaccuracy", number("accuracy", 0, 0, 16));
            ranged.addProperty("safeShot", flag("safeShot", true));
            ranged.addProperty("useAmmo", flag("needsAmmo", false));
        }
        private void weaponDamage(JsonObject melee) {
            JsonElement map = source.get("weapon_damage_map");
            if (map == null || map.isJsonNull()) return;
            if (!map.isJsonObject() || map.getAsJsonObject().size() > 64) {
                keep("weapon_damage_map", "not a map of at most 64 items");
                return;
            }
            JsonObject converted = new JsonObject();
            for (Map.Entry<String, JsonElement> entry : map.getAsJsonObject().entrySet()) {
                String item = "minecraft:" + entry.getKey().toLowerCase(Locale.ROOT).replace("minecraft:", "");
                JsonElement value = entry.getValue();
                if (!item.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
                        || !Double.isFinite(value.getAsDouble()) || value.getAsDouble() < 0 || value.getAsDouble() > 100) {
                    keep("weapon_damage_map", "an entry is not an item with damage 0..100");
                    return;
                }
                converted.addProperty(item, value.getAsDouble());
            }
            if (!converted.isEmpty()) melee.add("weaponDamage", converted);
        }
        /** The ranged weapon the imported held item fires, as Sentinel chooses it. Potions need their effect and are not converted. */
        private Optional<String> heldWeapon() {
            if (equipment == null || !equipment.isJsonObject()) return Optional.empty();
            JsonElement hand = equipment.getAsJsonObject().get("hand");
            if (hand == null || !hand.isJsonObject()) return Optional.empty();
            String type = text(hand.getAsJsonObject().get("type"));
            if (type == null) type = text(hand.getAsJsonObject().get("id"));
            if (type == null) return Optional.empty();
            return Optional.ofNullable(HELD_WEAPONS.get(type.toLowerCase(Locale.ROOT).replace("minecraft:", "")));
        }
        /** Sentinel's defense becomes the health module; an NPC that is not invincible is killable. */
        private void defense() {
            health.addProperty("killable", !flag("invincible", false));
            health.addProperty("maximumHealth", number("health", 20, 1, 2048));
            double armor = source.has("armor") ? number("armor", -1, -1, 1) : -1;
            if (armor < 0) health.addProperty("armorFromEquipment", true);
            else health.addProperty("armor", armor);
            health.addProperty("knockback", flag("allow_knockback", true));
            int heal = ticks("healRate", 100, 0, 72000);
            health.addProperty("regenerate", heal > 0);
            if (heal > 0) health.addProperty("regenerateTicks", heal);
            health.addProperty("protectedFromIgnored", flag("protected", false));
            health.addProperty("protectFromRange", number("protectFromRange", 0, 0, 256));
        }
        private void death() {
            module.addProperty("victimDrops", flag("enemyDrops", false));
            JsonObject section = healthSection("death");
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
            JsonObject stack = new JsonObject();
            stack.addProperty("id", id);
            stack.addProperty("count", count);
            JsonObject rule = new JsonObject();
            rule.add("item", stack);
            rule.addProperty("chance", probability);
            return Optional.of(rule);
        }
        /** Sentinel keeps an NPC with a respawn time of 0 dead, and deletes one with a negative time when it dies. */
        private void respawn() {
            JsonObject section = healthSection("respawn");
            double time = number("respawnTime", 100, -Double.MAX_VALUE, 72000);
            long ticks = Math.round(time);
            if (time < 0) section.addProperty("afterDeath", "delete");
            else if (ticks == 0) section.addProperty("afterDeath", "permadeath");
            else section.addProperty("delayTicks", ticks);
            location("spawnPoint").ifPresent((JsonObject point) -> {
                JsonArray points = new JsonArray();
                points.add(point);
                section.add("points", points);
            });
        }
        /** Empty text means no line in Sentinel. */
        private void greetings() {
            JsonObject section = section("greetings");
            line("greeting_text").ifPresent((String text) -> section.addProperty("friendly", text));
            line("warning_text").ifPresent((String text) -> section.addProperty("hostile", text));
            section.addProperty("range", number("greet_range", 10, 1, 64));
            section.addProperty("cooldownTicks", ticks("greet_rate", 100, 0, 72000));
        }
        private Optional<String> line(String field) {
            if (!present(field)) return Optional.empty();
            String text = text(source.get(field));
            if (text == null || text.length() > 256 || text.codePoints().anyMatch(Character::isISOControl)) {
                keep(field, "not a line of at most 256 characters");
                return Optional.empty();
            }
            return text.isBlank() ? Optional.empty() : Optional.of(text);
        }
    }

    private SentinelMapping() {}
}
