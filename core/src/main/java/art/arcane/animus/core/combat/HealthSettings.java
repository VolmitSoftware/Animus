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
import java.util.Optional;
import java.util.Set;

/**
 * Validated contents of {@code modules["animus:health"]}: whether the NPC can be hurt and killed, and what happens when
 * it dies. It needs no combat AI. On an NPC that also fights, it replaces the combat module's {@code defense},
 * {@code death} and {@code respawn}. Attributes fill what it leaves out: {@code max_health}, {@code armor},
 * {@code armor_toughness} and {@code knockback_resistance}. {@code dead} is written by Animus when a permadeath NPC dies.
 */
public record HealthSettings(boolean killable, CombatSettings.Defense defense, CombatSettings.Death death, CombatSettings.Respawn respawn) {
    public static final String MODULE = "animus:health";
    public static final String DEAD = "dead";
    static final String ROOT = "$.modules." + MODULE;
    public enum AfterDeath { RESPAWN, PERMADEATH }

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
                "sources", "attackerPermission", "showHealth", "death", "respawn", DEAD);
        boolean killable = root.bool("killable", false);
        double maximum = root.has("maximumHealth") ? root.number("maximumHealth", 20, 1, 2048)
                : attributes.get("max_health").isPresent() ? Math.max(1, Math.min(2048, attributes.value("max_health"))) : 20;
        boolean equipment = root.bool("armorFromEquipment", false);
        boolean fromAttributes = !root.has("armor") && !equipment;
        Set<CombatSettings.DamageSource> sources = EnumSet.noneOf(CombatSettings.DamageSource.class);
        if (!root.has("sources")) sources.addAll(CombatSettings.DamageSource.ATTACKS);
        List<String> names = root.strings("sources");
        for (int i = 0; i < names.size(); i++) {
            int index = i;
            sources.add(CombatSettings.DamageSource.parse(names.get(i)).orElseThrow(() ->
                    new IllegalArgumentException(root.field("sources") + "[" + index + "]: expected players, mobs, npcs, projectiles, fire, lava or fall")));
        }
        Optional<String> permission = Optional.empty();
        if (root.has("attackerPermission")) {
            String node = root.string("attackerPermission", "");
            if (!node.matches("[A-Za-z0-9_.-]{1,128}")) throw new IllegalArgumentException(root.field("attackerPermission") + ": expected a permission node");
            permission = Optional.of(node);
        }
        int regenerate = root.integer("regenerateTicks", 100, 1, 72000);
        CombatSettings.Defense defense = new CombatSettings.Defense(maximum, root.number("armor", 0, 0, 1), !killable, root.bool("knockback", true), false, 0,
                root.bool("regenerate", false) ? regenerate : 0, root.integer("invulnerableTicks", 10, 0, 100), equipment,
                fromAttributes ? attributes.value("armor") : 0, fromAttributes ? attributes.value("armor_toughness") : 0, attributes.value("knockback_resistance"),
                sources, permission, root.bool("showHealth", false));
        return new HealthSettings(killable, defense, death(root.child("death")), respawn(root.child("respawn"), root.bool(DEAD, false)));
    }

    private static CombatSettings.Death death(CombatSettings.Reader reader) {
        reader.known("drops", "experience", "dropEquipment");
        List<CombatSettings.DropRule> drops = new ArrayList<>();
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
            drops.add(new CombatSettings.DropRule(MinecraftIds.normalize(item.get().id()), count, most, item.get().components(), drop.number("chance", 1, 0, 1)));
        }
        return new CombatSettings.Death(drops, reader.integer("experience", 0, 0, 10000), true, reader.bool("dropEquipment", false));
    }

    private static CombatSettings.Respawn respawn(CombatSettings.Reader reader, boolean dead) {
        reader.known("afterDeath", "delayTicks", "points", "order");
        String after = reader.string("afterDeath", "respawn");
        boolean permadeath = switch (after) {
            case "respawn" -> false;
            case "permadeath" -> true;
            default -> throw new IllegalArgumentException(reader.field("afterDeath") + ": expected respawn or permadeath");
        };
        List<CombatSettings.Location> points = new ArrayList<>();
        JsonArray entries = reader.array("points", 16);
        for (int i = 0; i < entries.size(); i++) {
            String path = reader.field("points") + "[" + i + "]";
            if (!entries.get(i).isJsonObject()) throw new IllegalArgumentException(path + ": expected object");
            points.add(CombatSettings.location(new CombatSettings.Reader(entries.get(i).getAsJsonObject(), path)));
        }
        String order = reader.string("order", "in_order");
        CombatSettings.Order chosen = null;
        for (CombatSettings.Order candidate : CombatSettings.Order.values()) if (candidate.jsonName().equals(order)) chosen = candidate;
        if (chosen == null) throw new IllegalArgumentException(reader.field("order") + ": expected in_order, random or nearest");
        int delay = reader.integer("delayTicks", 100, 0, 72000);
        return new CombatSettings.Respawn(permadeath ? -1 : delay, points, false, chosen, true, permadeath, dead);
    }
}
