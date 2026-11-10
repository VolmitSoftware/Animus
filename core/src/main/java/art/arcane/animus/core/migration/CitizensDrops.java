package art.arcane.animus.core.migration;

import static art.arcane.animus.core.migration.CitizensBehaviors.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Citizens' drops trait as {@code animus:health} death drops. Citizens rolls each drop on its own chance when the NPC
 * dies; Animus does the same. An NPC with no health module gets one that is killable when Citizens left it
 * vulnerable ({@code protected: false}), as its drops only ever fell then.
 */
final class CitizensDrops {
    static final String TRAIT = "dropstrait";
    private static final int MAXIMUM = 32;

    private CitizensDrops() {}

    /** Adds the drops to {@code modules}; returns false when the trait holds none. */
    static boolean apply(JsonElement trait, JsonObject record, JsonObject modules, List<String> warnings, Set<String> converted) {
        JsonObject drops = object(trait);
        if (drops == null) return false;
        JsonArray rules = new JsonArray();
        for (Map.Entry<String, JsonElement> entry : entries(drops.get("drops"))) {
            String at = "/traits/" + TRAIT + "/drops/" + entry.getKey();
            JsonObject drop = object(entry.getValue());
            if (drop == null) continue;
            Optional<CitizensStacks.Stack> stack = CitizensStacks.read(drop.get("drop"));
            if (stack.isEmpty()) { warnings.add(at + ": not an item; not converted"); continue; }
            if (rules.size() == MAXIMUM) { warnings.add(at + ": only the first " + MAXIMUM + " drops convert"); break; }
            stack.get().inexact().ifPresent((String reason) -> warnings.add(at + "/drop: " + reason + "; it drops without that data"));
            JsonObject rule = new JsonObject();
            rule.add("item", stack.get().counted());
            double chance = number(drop.get("chance")) ? Math.max(0, Math.min(1, drop.get("chance").getAsDouble())) : 1;
            if (chance < 1) rule.addProperty("chance", chance);
            rules.add(rule);
            converted.add(at);
        }
        if (rules.isEmpty()) return false;
        if (!modules.has("animus:health")) {
            JsonObject metadata = object(record.get("metadata"));
            JsonObject health = new JsonObject();
            health.addProperty("killable", metadata != null && !bool(metadata.get("protected"), true));
            modules.add("animus:health", health);
        }
        JsonObject health = modules.getAsJsonObject("animus:health");
        if (!health.has("death") || !health.get("death").isJsonObject()) health.add("death", new JsonObject());
        JsonObject death = health.getAsJsonObject("death");
        JsonArray existing = death.has("drops") && death.get("drops").isJsonArray() ? death.getAsJsonArray("drops") : new JsonArray();
        for (JsonElement rule : rules) if (existing.size() < MAXIMUM) existing.add(rule);
        death.add("drops", existing);
        return true;
    }
}
