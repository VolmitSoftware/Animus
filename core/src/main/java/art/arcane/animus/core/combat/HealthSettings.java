package art.arcane.animus.core.combat;

import art.arcane.animus.core.npc.NpcAttributes;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.platform.MinecraftIds;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Validated contents of {@code modules["animus:health"]}: whether the NPC can be hurt and killed, and what happens when
 * it dies. It is the only home of health, armor, drops, experience and respawn, with or without combat AI. An NPC
 * without the module cannot be hurt. Attributes fill what it leaves out: {@code max_health}, {@code armor},
 * {@code armor_toughness} and {@code knockback_resistance}. {@code dead} is written by Animus when a permadeath NPC dies.
 */
public record HealthSettings(boolean killable, Defense defense, Death death, Respawn respawn) {
    public static final String MODULE = "animus:health";
    public static final String DEAD = "dead";
    static final String ROOT = "$.modules." + MODULE;

    /**
     * @param armorFromEquipment  derive armor from worn items instead of {@code armor}
     * @param armorPoints         vanilla armor points from the {@code armor} attribute, used with {@code armorToughness} when the module sets no armor
     * @param knockbackResistance the {@code knockback_resistance} attribute: the share of knockback ignored
     * @param protectedFromIgnored whatever the combat module ignores cannot hurt the NPC
     * @param protectFromRange    only attackers within this many blocks can hurt the NPC; 0 means any distance
     * @param healRateTicks       ticks per healed point; 0 never heals
     * @param sources             what may hurt the NPC
     * @param attackerPermission  a node a player needs to hurt the NPC
     * @param showHealth          the player who hits the NPC sees its health on the action bar
     */
    public record Defense(double maximumHealth, double armor, boolean invincible, boolean knockback, boolean protectedFromIgnored, double protectFromRange,
                          int healRateTicks, int invulnerableTicks, boolean armorFromEquipment, double armorPoints, double armorToughness, double knockbackResistance,
                          Set<DamageSource> sources, Optional<String> attackerPermission, boolean showHealth) {
        public Defense { sources = sources.isEmpty() ? EnumSet.noneOf(DamageSource.class) : EnumSet.copyOf(sources); }
        @Override public Set<DamageSource> sources() { return sources.isEmpty() ? Set.of() : EnumSet.copyOf(sources); }
        /** Whether a hit of this kind from this attacker counts. */
        public boolean accepts(TargetKind attacker, CombatWorld.DamageKind kind) { return sources.contains(DamageSource.of(attacker, kind)); }
        /** Whether damage from this source can reach the NPC at all. */
        public boolean takes(DamageSource source) { return !invincible && sources.contains(source); }
    }
    /**
     * What may hurt an NPC: player and mob melee, another NPC's melee, projectiles from players, mobs and combat NPCs, and
     * the environment's fire, lava and falls. The environment counts only where listed.
     */
    public enum DamageSource {
        PLAYERS, MOBS, NPCS, PROJECTILES, FIRE, LAVA, FALL;
        /** Every attack source: what an NPC takes when its module lists none. */
        public static final Set<DamageSource> ATTACKS = Set.copyOf(EnumSet.of(PLAYERS, MOBS, NPCS, PROJECTILES));
        public String jsonName() { return name().toLowerCase(Locale.ROOT); }
        public static Optional<DamageSource> parse(String name) {
            for (DamageSource source : values()) if (source.jsonName().equals(name)) return Optional.of(source);
            return Optional.empty();
        }
        static DamageSource of(TargetKind attacker, CombatWorld.DamageKind kind) {
            if (kind == CombatWorld.DamageKind.PROJECTILE) return PROJECTILES;
            return switch (attacker) { case PLAYER -> PLAYERS; case MOB -> MOBS; case NPC -> NPCS; };
        }
    }
    /** {@code amount} to {@code maximum} items drop, rolled evenly; {@code components} as in a give command. */
    public record DropRule(String item, int amount, int maximum, Optional<String> components, double chance) {}
    /** @param dropEquipment the items the NPC wears and holds drop too */
    public record Death(List<DropRule> drops, int experience, boolean dropEquipment) {
        public Death { drops = List.copyOf(drops); }
    }
    /** How a respawn picks one of several points. */
    public enum Order {
        IN_ORDER, RANDOM, NEAREST;
        public String jsonName() { return name().toLowerCase(Locale.ROOT); }
    }
    /** What happens when the NPC dies: it comes back, stays dead until revived, or its definition is deleted. */
    public enum AfterDeath {
        RESPAWN, PERMADEATH, DELETE;
        public String jsonName() { return name().toLowerCase(Locale.ROOT); }
    }
    /**
     * @param delayTicks ticks before the NPC comes back; -1 when it does not
     * @param locations  respawn points; with none, the NPC comes back at its definition's position
     * @param dead       the definition says the NPC died for good; it is not presented until revived
     */
    public record Respawn(AfterDeath afterDeath, int delayTicks, List<CombatSettings.Location> locations, Order order, boolean dead) {
        public Respawn { locations = List.copyOf(locations); }
        public boolean permadeath() { return afterDeath == AfterDeath.PERMADEATH; }
        public boolean deleteOnDeath() { return afterDeath == AfterDeath.DELETE; }
    }

    /** What an NPC without the module gets: it cannot be hurt. */
    public static final HealthSettings UNKILLABLE = parse(new JsonObject(), NpcAttributes.NONE);

    /** The module object, empty when the definition has none; a non-object is refused. */
    static Optional<JsonObject> module(JsonObject modules) {
        JsonElement value = modules.get(MODULE);
        if (value == null) return Optional.empty();
        if (!value.isJsonObject()) throw new IllegalArgumentException(ROOT + ": expected object");
        return Optional.of(value.getAsJsonObject());
    }

    /** Whether the definition marks the NPC dead for good. Malformed modules read as alive; validation reports them. */
    public static boolean dead(JsonObject definition) {
        JsonElement modules = definition.get("modules");
        if (modules == null || !modules.isJsonObject()) return false;
        JsonElement module = modules.getAsJsonObject().get(MODULE);
        if (module == null || !module.isJsonObject()) return false;
        JsonElement dead = module.getAsJsonObject().get(DEAD);
        return dead != null && dead.isJsonPrimitive() && dead.getAsJsonPrimitive().isBoolean() && dead.getAsBoolean();
    }

    public static HealthSettings parse(JsonObject module, NpcAttributes attributes) {
        CombatSettings.Reader root = new CombatSettings.Reader(module, ROOT);
        root.known("killable", "maximumHealth", "armor", "armorFromEquipment", "invulnerableTicks", "knockback", "regenerate", "regenerateTicks",
                "sources", "attackerPermission", "showHealth", "protectFromRange", "protectedFromIgnored", "death", "respawn", DEAD);
        boolean killable = root.bool("killable", false);
        double maximum = root.has("maximumHealth") ? root.number("maximumHealth", 20, 1, 2048)
                : attributes.get("max_health").isPresent() ? Math.max(1, Math.min(2048, attributes.value("max_health"))) : 20;
        boolean equipment = root.bool("armorFromEquipment", false);
        boolean fromAttributes = !root.has("armor") && !equipment;
        Set<DamageSource> sources = EnumSet.noneOf(DamageSource.class);
        if (!root.has("sources")) sources.addAll(DamageSource.ATTACKS);
        List<String> names = root.strings("sources");
        for (int i = 0; i < names.size(); i++) {
            int index = i;
            sources.add(DamageSource.parse(names.get(i)).orElseThrow(() ->
                    new IllegalArgumentException(root.field("sources") + "[" + index + "]: expected players, mobs, npcs, projectiles, fire, lava or fall")));
        }
        Optional<String> permission = Optional.empty();
        if (root.has("attackerPermission")) {
            String node = root.string("attackerPermission", "");
            if (!node.matches("[A-Za-z0-9_.-]{1,128}")) throw new IllegalArgumentException(root.field("attackerPermission") + ": expected a permission node");
            permission = Optional.of(node);
        }
        int regenerate = root.integer("regenerateTicks", 100, 1, 72000);
        Defense defense = new Defense(maximum, root.number("armor", 0, 0, 1), !killable, root.bool("knockback", true), root.bool("protectedFromIgnored", false),
                root.number("protectFromRange", 0, 0, 256), root.bool("regenerate", false) ? regenerate : 0, root.integer("invulnerableTicks", 10, 0, 100), equipment,
                fromAttributes ? attributes.value("armor") : 0, fromAttributes ? attributes.value("armor_toughness") : 0, attributes.value("knockback_resistance"),
                sources, permission, root.bool("showHealth", false));
        return new HealthSettings(killable, defense, death(root.child("death")), respawn(root.child("respawn"), root.bool(DEAD, false)));
    }

    private static Death death(CombatSettings.Reader reader) {
        reader.known("drops", "experience", "dropEquipment");
        List<DropRule> drops = new ArrayList<>();
        JsonArray entries = reader.array("drops", 32);
        for (int i = 0; i < entries.size(); i++) {
            String path = reader.field("drops") + "[" + i + "]";
            if (!entries.get(i).isJsonObject()) throw new IllegalArgumentException(path + ": expected object");
            CombatSettings.Reader drop = new CombatSettings.Reader(entries.get(i).getAsJsonObject(), path);
            drop.known("item", "maxCount", "chance");
            List<String> errors = new ArrayList<>();
            Optional<NpcItem> item = NpcItem.parse(drop.object().get("item"), drop.field("item"), errors);
            if (item.isEmpty()) throw new IllegalArgumentException(errors.isEmpty() ? drop.field("item") + ": required" : errors.getFirst());
            int count = item.get().count();
            int most = drop.integer("maxCount", count, count, NpcItem.MAX_COUNT);
            drops.add(new DropRule(MinecraftIds.normalize(item.get().id()), count, most, item.get().components(), drop.number("chance", 1, 0, 1)));
        }
        return new Death(drops, reader.integer("experience", 0, 0, 10000), reader.bool("dropEquipment", false));
    }

    private static Respawn respawn(CombatSettings.Reader reader, boolean dead) {
        reader.known("afterDeath", "delayTicks", "points", "order");
        String after = reader.string("afterDeath", "respawn");
        AfterDeath chosenAfter = null;
        for (AfterDeath candidate : AfterDeath.values()) if (candidate.jsonName().equals(after)) chosenAfter = candidate;
        if (chosenAfter == null) throw new IllegalArgumentException(reader.field("afterDeath") + ": expected respawn, permadeath or delete");
        List<CombatSettings.Location> points = new ArrayList<>();
        JsonArray entries = reader.array("points", 16);
        for (int i = 0; i < entries.size(); i++) {
            String path = reader.field("points") + "[" + i + "]";
            if (!entries.get(i).isJsonObject()) throw new IllegalArgumentException(path + ": expected object");
            points.add(CombatSettings.location(new CombatSettings.Reader(entries.get(i).getAsJsonObject(), path)));
        }
        String order = reader.string("order", "in_order");
        Order chosen = null;
        for (Order candidate : Order.values()) if (candidate.jsonName().equals(order)) chosen = candidate;
        if (chosen == null) throw new IllegalArgumentException(reader.field("order") + ": expected in_order, random or nearest");
        int delay = reader.integer("delayTicks", 100, 0, 72000);
        return new Respawn(chosenAfter, chosenAfter == AfterDeath.RESPAWN ? delay : -1, points, chosen, dead);
    }
}
