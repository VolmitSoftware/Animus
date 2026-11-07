package art.arcane.animus.core.toolkit;

import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.behavior.schema.NpcSchemaGenerator;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/** Every field of the NPC schema can be edited in game, or is on the documented exclusion list. */
class InGameParityTest {
    @Test void everyNpcFieldHasAnInGamePathOrADocumentedExclusion() {
        JsonObject schema = NpcSchemaGenerator.schema();
        Set<String> leaves = new TreeSet<>();
        leaves(schema, schema, "", leaves);
        Set<String> covered = PropertyScreen.covered(schema);
        List<String> missing = leaves.stream().filter((String path) -> !covered.contains(path) && !ParityExclusions.excluded(path)).toList();
        assertTrue(missing.isEmpty(), "Fields with no in-game path and no exclusion:\n" + String.join("\n", missing));
        List<String> stale = ParityExclusions.PATHS.stream().filter((String entry) -> leaves.stream().noneMatch((String path) -> ParityExclusions.matches(entry, path))).toList();
        assertTrue(stale.isEmpty(), "Exclusions that match no schema field:\n" + String.join("\n", stale));
        List<String> redundant = ParityExclusions.PATHS.stream().filter((String entry) -> leaves.stream().filter((String path) -> ParityExclusions.matches(entry, path)).allMatch(covered::contains)).toList();
        assertTrue(redundant.isEmpty(), "Exclusions for fields the screens already cover:\n" + String.join("\n", redundant));
    }

    /** Leaf paths: {@code a.b} for fields, {@code a[]} for list entries, {@code a.*} for map values; every branch of a choice. */
    static void leaves(JsonObject root, JsonObject node, String path, Set<String> out) {
        JsonObject here = node;
        if (here.has("$ref")) {
            String reference = here.get("$ref").getAsString();
            here = root.getAsJsonObject("$defs").getAsJsonObject(reference.substring(reference.lastIndexOf('/') + 1));
        }
        boolean branched = false;
        for (String choice : List.of("oneOf", "anyOf"))
            if (here.has(choice)) {
                branched = true;
                for (JsonElement branch : here.getAsJsonArray(choice)) leaves(root, branch.getAsJsonObject(), path, out);
            }
        if (here.has("properties") && !here.getAsJsonObject("properties").isEmpty()) {
            for (Map.Entry<String, JsonElement> property : here.getAsJsonObject("properties").entrySet())
                leaves(root, property.getValue().getAsJsonObject(), path.isEmpty() ? property.getKey() : path + "." + property.getKey(), out);
        } else if (here.has("items") && here.get("items").isJsonObject()) leaves(root, here.getAsJsonObject("items"), path + "[]", out);
        else if (here.has("additionalProperties") && here.get("additionalProperties").isJsonObject()) leaves(root, here.getAsJsonObject("additionalProperties"), path + ".*", out);
        else if (!branched) out.add(path);
    }
}
