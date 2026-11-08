package art.arcane.animus.core.behavior.schema;

import static art.arcane.animus.core.behavior.schema.NpcSchemaGenerator.choices;
import static art.arcane.animus.core.behavior.schema.NpcSchemaGenerator.closed;
import static art.arcane.animus.core.behavior.schema.NpcSchemaGenerator.described;
import static art.arcane.animus.core.behavior.schema.NpcSchemaGenerator.list;
import static art.arcane.animus.core.behavior.schema.NpcSchemaGenerator.pattern;
import static art.arcane.animus.core.behavior.schema.NpcSchemaGenerator.range;
import static art.arcane.animus.core.behavior.schema.NpcSchemaGenerator.reference;
import static art.arcane.animus.core.behavior.schema.NpcSchemaGenerator.strings;
import static art.arcane.animus.core.behavior.schema.NpcSchemaGenerator.type;

import art.arcane.animus.core.combat.CombatSettings;
import art.arcane.animus.core.combat.HealthSettings;
import art.arcane.animus.core.npc.NpcItem;
import com.google.gson.JsonObject;
import java.util.Arrays;
import java.util.List;

/** {@code modules["animus:health"]}, with the bounds {@link HealthSettings} reads. */
final class HealthSchema {
    private HealthSchema() {}

    static JsonObject module() {
        JsonObject health = described(closed(), "Health and death without combat AI. On a combat NPC it replaces defense, death and respawn.");
        JsonObject fields = new JsonObject();
        fields.add("killable", described(type("boolean"), "Players, mobs and NPCs can hurt and kill it. Off by default."));
        fields.add("maximumHealth", described(range("number", 1, 2048), "Left out, the max_health attribute or 20."));
        fields.add("armor", described(range("number", 0, 1), "Share of each hit absorbed. Left out, the armor attributes apply."));
        fields.add("armorFromEquipment", described(type("boolean"), "Armor from worn pieces instead."));
        fields.add("invulnerableTicks", described(range("integer", 0, 100), "Repeat hits ignored for this long. 10 by default."));
        fields.add("knockback", described(type("boolean"), "Hits push it back, less by knockback_resistance. True by default."));
        fields.add("regenerate", described(type("boolean"), "Heals 1 health each regenerateTicks."));
        fields.add("regenerateTicks", described(range("integer", 1, 72000), "100 by default."));
        JsonObject sources = described(type("array"), "What can hurt it. Every attack by default; fire, lava and fall only when listed.");
        sources.add("items", choices(Arrays.stream(CombatSettings.DamageSource.values()).map(CombatSettings.DamageSource::jsonName).toList()));
        sources.addProperty("uniqueItems", true);
        fields.add("sources", sources);
        fields.add("attackerPermission", described(pattern("^[A-Za-z0-9_.-]{1,128}$"), "Players need this node to hurt it."));
        fields.add("showHealth", described(type("boolean"), "Shows its health on the attacker's action bar."));
        JsonObject drop = closed();
        drop.add("required", strings("item"));
        JsonObject dropFields = new JsonObject();
        dropFields.add("item", described(reference("item"), "Its count is the fewest that drop."));
        dropFields.add("maxCount", described(range("integer", 1, NpcItem.MAX_COUNT), "The most that drop. The item count by default."));
        dropFields.add("chance", described(range("number", 0, 1), "1 by default."));
        drop.add("properties", dropFields);
        JsonObject death = closed();
        JsonObject deathFields = new JsonObject();
        deathFields.add("drops", list(drop, 0, 32, "At most 32."));
        deathFields.add("experience", range("integer", 0, 10000));
        deathFields.add("dropEquipment", described(type("boolean"), "Its worn and held items drop too."));
        death.add("properties", deathFields);
        fields.add("death", death);
        JsonObject respawn = closed();
        JsonObject respawnFields = new JsonObject();
        respawnFields.add("afterDeath", described(choices(List.of("respawn", "permadeath")), "permadeath keeps it dead until revived. respawn by default."));
        respawnFields.add("delayTicks", described(range("integer", 0, 72000), "100 by default."));
        respawnFields.add("points", list(reference("location"), 0, 16, "At most 16. None means its own position."));
        respawnFields.add("order", described(choices(Arrays.stream(CombatSettings.Order.values()).map(CombatSettings.Order::jsonName).toList()), "in_order by default."));
        respawn.add("properties", respawnFields);
        fields.add("respawn", respawn);
        fields.add(HealthSettings.DEAD, described(type("boolean"), "Set when a permadeath NPC dies. False revives it."));
        health.add("properties", fields);
        return health;
    }
}
