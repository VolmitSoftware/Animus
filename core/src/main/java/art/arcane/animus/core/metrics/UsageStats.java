package art.arcane.animus.core.metrics;

import art.arcane.animus.core.combat.HealthSettings;
import art.arcane.animus.core.npc.NpcDefinition;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * The anonymous usage figures Animus reports to bStats, read from the published NPC definitions.
 *
 * @param npcs             NPC definitions
 * @param importSources    where imported NPCs came from: {@code Citizens}, {@code Shopkeepers}, {@code Denizen} or {@code Module}
 * @param traitGroupsInUse distinct trait groups listed by at least one NPC
 * @param killable         at least one NPC can be killed
 */
public record UsageStats(int npcs, Set<String> importSources, int traitGroupsInUse, boolean killable) {
    public UsageStats { importSources = Set.copyOf(importSources); }

    /** One pass over the definitions; callers cache the result per published registry. */
    public static UsageStats of(Collection<NpcDefinition> definitions) {
        Set<String> sources = new TreeSet<>();
        Set<String> groups = new HashSet<>();
        boolean killable = false;
        for (NpcDefinition definition : definitions) {
            groups.addAll(definition.groups());
            JsonObject data = definition.json();
            sources(data, definition, sources);
            killable |= killable(data);
        }
        return new UsageStats(definitions.size(), sources, groups.size(), killable);
    }

    /** A coarse range for a count, so a chart groups servers by scale instead of exact numbers. */
    public static String bucket(int count) {
        if (count <= 0) return "0";
        if (count <= 10) return "1-10";
        if (count <= 50) return "11-50";
        if (count <= 200) return "51-200";
        if (count <= 1000) return "201-1000";
        return "1000+";
    }

    /** Denizen NPCs also came from Citizens, so one NPC can name two sources. */
    private static void sources(JsonObject data, NpcDefinition definition, Set<String> sources) {
        if (definition.behaviors().stream().anyMatch((String behavior) -> behavior.startsWith("denizen:"))) sources.add("Denizen");
        JsonElement migration = data.get("migration");
        if (migration == null || !migration.isJsonObject()) return;
        JsonObject record = migration.getAsJsonObject();
        JsonElement origin = record.get("source");
        if (record.has("citizensId")) sources.add("Citizens");
        else if (origin != null && origin.isJsonPrimitive() && origin.getAsString().equals("Shopkeepers")) sources.add("Shopkeepers");
        else if (record.has("converter")) sources.add("Module");
    }

    private static boolean killable(JsonObject data) {
        JsonElement modules = data.get("modules");
        if (modules == null || !modules.isJsonObject()) return false;
        JsonElement health = modules.getAsJsonObject().get(HealthSettings.MODULE);
        if (health == null || !health.isJsonObject()) return false;
        JsonElement killable = health.getAsJsonObject().get("killable");
        return killable != null && killable.isJsonPrimitive() && killable.getAsJsonPrimitive().isBoolean() && killable.getAsBoolean();
    }
}
